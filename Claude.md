# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Starlarky is VGS's fork of Bazel's Java Starlark interpreter, used to run untrusted user-submitted scripts. Maven multi-module repo (Java 17):

- `libstarlark/` — the Starlark lexer/parser/resolver/evaluator (`net.starlark.java.*`), periodically synced from bazelbuild via `bin/update-starlark.py`. Avoid gratuitous divergence from upstream outside the bytecode work below.
- `larky/` — VGS additions (`com.verygood.security.larky.*`): JSR223 engine, Python-compat object model (`objects/`, `modules/types/`), native Java modules (`modules/`), and a Starlark stdlib written in `.star` (`src/main/resources/stdlib`, `vendor`, `vgs`).
- `runlarky/` — Quarkus/GraalVM native CLI (`larky-runner`). Adding or moving a native module in `larky/modules` requires updating `runlarky/src/main/resources/reflect-config.json`.
- `larky-api/`, `pylarky/` — Java API and pip wrapper around the runner (not in the root reactor).

## Commands

```bash
mvn clean install -DskipTests                      # build everything; larky depends on libstarlark 1.0.0-SNAPSHOT from ~/.m2
mvn test -pl libstarlark                           # libstarlark tests (tree-walker)
mvn test -pl libstarlark -Dstarlark.bytecode=true -Dstarlark.bytecode.strict=true  # same suite on the bytecode VM, failing instead of falling back
mvn test -pl libstarlark,larky -Dstarlark.bytecode=true -Dstarlark.bytecode.strict=true -Dstarlark.bytecode.vm=starlark-go  # pick the VM: interpreter (default), starlark-go, starlark-rust, buck
mvn test -pl libstarlark -Dtest=EvaluationTest#testExec -Dstarlark.bytecode=true   # single test
mvn test -pl larky -Dtest=StdLibTests -Dlarky.stdlib_test=test_bytes.star           # one larky stdlib .star test
```

- After changing `libstarlark`, run `mvn install -pl libstarlark -DskipTests` before testing `larky`, or use `-pl larky -am`.
- Surefire reports: `<module>/target/surefire-reports/*.txt`. Check file timestamps; a failed Maven run (e.g. `-o` plugin-resolution failure) leaves the previous run's report in place.
- Some interrupt tests can leave Maven hanging after tests finish; wrap long runs in `timeout`.
- Debug flags (system properties): `-Ddebug.bytecode=true` (dump chunks + trace each instruction), `-Ddebug.globals=true`.
- `.star` test locations: libstarlark `src/test/java/net/starlark/java/eval/testdata/` — each file is a JUnit case of `ScriptFilesTest` (runs `ScriptTest.runFile` on a thread with a 512k stack; `json.star`'s nesting-depth cases depend on hitting `StackOverflowError`). larky `src/test/resources/{stdlib_tests,vendor_tests,vgs_tests,quick_tests}` (run by `StdLibTests`, `VendorLibTests`, `VGSLibTests`, `LarkyQuickTests`).
- CI parity: `docker-compose run local bash /src/build-and-test-java.sh`.

## Bytecode execution path (libstarlark)

An alternative to the tree-walking `Eval`, enabled only when `-Dstarlark.bytecode=true`:

1. `syntax/Program` constructor reads the property and calls `BytecodeCompiler.compileFunction(body)`. On compile failure it prints a warning and **silently falls back** to the tree-walker unless `-Dstarlark.bytecode.strict=true` is set — always use strict when testing bytecode. `Program.compileFile(file, env, enableBytecode)` forces compilation explicitly.
2. `Starlark.execFileProgram()` runs `BytecodeInterpreter.execute(...)` when `prog.hasBytecode()`, then writes globals back into the `Module`. The VM's namespace (`BytecodeGlobals`) is a live view of the `Module`'s globals (functions see later assignments, e.g. from another file run in the same module, as in a REPL); PREDECLARED/UNIVERSAL names are read with `LOAD_BUILTIN`, so file-level bindings shadow builtins as in the tree-walker. Top-level frames are `BytecodeToplevel`, which, like `BytecodeFunction`, reports its module to `Module.ofInnermostEnclosingStarlarkFunction`.
3. `eval/compiler/`: `BytecodeCompiler` (AST → `BytecodeChunk`: instructions + `ConstantPool` + locals/line/column metadata), `Opcode`, `Instruction`. `def` bodies compile to nested chunks wrapped at runtime as `BytecodeFunction` (must report `type()` as `"function"` and participate in recursion detection like `StarlarkFunction`).
4. VMs: all opcode semantics live in `AbstractBytecodeVM`. `BytecodeInterpreter` (default), `StarlarkGoInterpreter`, `StarlarkRustInterpreter` and `BuckStyleInterpreter` are subclasses that only choose storage (`push`/`pop`/`getLocal`...: separate stack list, unified growable slot array, ...) and optional hooks (`binaryOp` specialization, `getAttr` caching, stats). Fix semantics in the base class, never in a subclass. `-Dstarlark.bytecode.vm` (read once by `BytecodeTarget.configuredVm()`) selects the VM for top-level code and function bodies via `BytecodeVms`. `JVM`/`WASM` targets go through `BytecodeBackend` (`JvmBytecodeGenerator` + `StarlarkRuntime` helpers, `WasmGenerator`) and are not execution VMs.

Invariants that have caused bugs:
- `Instruction.create(Opcode, int operand, int offset)` — operand before offset.
- Labels/jump targets are **instruction indices**, not byte offsets; nested functions must `patchJumps()` before their chunk is built; labels must be unique across nested if/else.
- `FOR_ITER` pops the iterator itself on exhaustion — no extra `POP` after a loop. Iteration must call `EvalUtils.addIterator/removeIterator` so mutation-during-iteration errors fire.
- Comprehensions: each `for` clause adds one iterator to the stack; `LIST_APPEND`/`DICT_ADD` depth operands must account for it.
- `InterruptedException` must propagate unwrapped; the main loop must count steps / honor `thread` interrupt and expiry. Other runtime exceptions propagate too (no catch-all); top-level ones are wrapped as `UncheckedEvalException`, as `Starlark.fastcall` does.
- Calls pass `positional[]`/`named[]` arrays to `Starlark.fastcall` in source order (positional < keyword < `*` < `**`); duplicate/unexpected-keyword errors come from the callee.
- Each error-raising instruction is emitted with the tree-walker's error location (`emitAt(loc, ...)`: operator, dot, lbracket, `=`, for-clause start). Iteration locks are released in `run()`'s `finally` for loops exited by `return`/exception.
- `ConstantPool` dedups by class + value (floats by bits): `1 == 1.0` in Starlark, but they must stay distinct constants.
- Error text and locations must match the tree-walker exactly (`EvalException.withLocation`); `ErrorConsistencyTest` and `EvaluationTest` compare them.

## Larky layer

- `ModuleSupplier` defines what scripts can see: `CORE_MODULES`/`CORE_ENVIRONMENT` (globals such as `LarkyGlobals`, Python builtins, `classmethod`), `STD_MODULES`, `VGS_MODULES`, `TEST_MODULES`. Native modules are `@StarlarkBuiltin`-annotated classes; `.star` stdlib modules wrap them (e.g. `stdlib/re.star` over `RegexModule`).
- `LarkySemantics` holds Larky-specific `StarlarkSemantics` flags.
- Code that special-cases user functions must test `UserDefinedFunction` (implemented by both `StarlarkFunction` and `BytecodeFunction`), not `StarlarkFunction` — e.g. `LarkyProvidedTypeClass` wraps class members as `LarkyFunction` descriptors to bind `self`.
- JSR223 (`jsr223/LarkyCompiledScript`): `compile()` resolves leniently (bindings arrive at eval) and produces bytecode artifacts; `eval()` goes through the Larky interpreter except in JVM mode. Never open `context.getReader()` unless there is no compiled source — the default reader wraps `System.in`, and closing it kills the surefire fork.

## Rules (from `.cursorrules`)

- Scripts are untrusted: no file/OS/process/network access, reflection, dynamic class loading, or JNI escape hatches from builtins; no unseeded randomness or wall-clock/timezone access; don't leak Java stack traces in Starlark errors.
- Keep Starlark semantics (not Python): respect freezing/mutability, don't add Python-only features. Return Starlark values from builtins and validate arguments strictly.
- Error message form: ``fn(arg=…) expected `<type>`; got <type>``.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
