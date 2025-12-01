# Starlark Bytecode Compiler & WebAssembly Target

This document describes the bytecode compiler implementation for Starlark that targets WebAssembly.

## Overview

The bytecode compiler transforms Starlark abstract syntax trees (AST) into a stack-based bytecode intermediate representation (IR) that can be:
1. Executed by a bytecode interpreter
2. Translated to WebAssembly (WASM)
3. Serialized for caching

## Architecture

### Components

#### 1. Bytecode Data Structures (`net.starlark.java.eval.compiler`)

- **Opcode.java**: Defines 100+ bytecode instructions
  - Stack manipulation (POP, DUP, SWAP)
  - Constants (LOAD_CONST, LOAD_NONE, LOAD_TRUE, LOAD_FALSE)
  - Variables (LOAD_LOCAL, STORE_LOCAL, LOAD_GLOBAL, STORE_GLOBAL)
  - Arithmetic (ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO, etc.)
  - Comparison (EQUAL, LESS, GREATER, IN, etc.)
  - Collections (BUILD_LIST, BUILD_TUPLE, BUILD_DICT)
  - Control flow (JUMP, JUMP_IF_TRUE, RETURN)
  - Function calls (CALL, MAKE_FUNCTION)
  - WebAssembly hints (WASM_I32_CONST, WASM_F64_CONST, etc.)

- **Instruction.java**: Represents a single bytecode instruction
  - Opcode + 0-2 operands
  - Serialization/deserialization support
  - Size: 1 byte (opcode) + 4 bytes per operand

- **ConstantPool.java**: Stores constant values
  - Deduplicates constants
  - Supports strings, numbers, booleans
  - Indexed access for bytecode references

- **BytecodeChunk.java**: Complete bytecode unit
  - Constant pool
  - Instruction sequence
  - Metadata (parameters, locals, line numbers)
  - Builder pattern for construction

#### 2. BytecodeCompiler.java

Compiles Starlark AST to bytecode using a single-pass visitor pattern:

```
AST → BytecodeCompiler → BytecodeChunk
```

Key features:
- Handles all Starlark statement and expression types
- Generates efficient stack-based code
- Preserves line number information for debugging
- Supports closures and free variables
- Short-circuit evaluation for AND/OR

#### 3. BytecodeInterpreter.java

Stack-based virtual machine that executes bytecode:

```
BytecodeChunk → BytecodeInterpreter → Result
```

Features:
- Pure Java implementation
- Interoperates with existing Starlark runtime
- Supports all bytecode instructions
- Compatible with StarlarkThread execution context

#### 4. WasmGenerator.java

Translates bytecode to WebAssembly text format (WAT):

```
BytecodeChunk → WasmGenerator → WAT → (wat2wasm) → WASM binary
```

Design decisions:
- Dynamic values represented as `externref` (opaque references)
- Numeric operations use WASM native types when possible
- Complex operations delegate to imported runtime functions
- Generated code can run in browsers or WASM runtimes

Runtime imports:
- Arithmetic: `add`, `subtract`, `multiply`, `divide`, etc.
- Collections: `build_list`, `build_dict`, `build_tuple`
- Operations: `index`, `get_attr`, `call`
- Constants: `const_none`, `const_true`, `const_int`, etc.

#### 5. BytecodeSerializer.java

Serializes/deserializes bytecode for caching:

```
BytecodeChunk ⇄ BytecodeSerializer ⇄ Binary file (.stc)
```

Format:
- Magic number: 0x5354524C ("STRL")
- Version: 1
- Efficient binary encoding
- Support for file I/O

#### 6. Program.java Integration

The compiler is integrated into the compilation pipeline:

```java
StarlarkFile file = StarlarkFile.parse(input);
Program program = Program.compileFile(file, module);

// Bytecode is automatically compiled
if (program.hasBytecode()) {
    BytecodeChunk bytecode = program.getBytecode();
    // Use bytecode for execution or serialization
}
```

## Usage Examples

### Compile Starlark to Bytecode

```java
// Parse source
ParserInput input = ParserInput.fromString("x = 1 + 2", "test.star");
StarlarkFile file = StarlarkFile.parse(input);

// Compile to program (includes bytecode compilation)
Program program = Program.compileFile(file, Module.create());

// Access bytecode
BytecodeChunk bytecode = program.getBytecode();
System.out.println(bytecode);
```

### Execute Bytecode

```java
// Create execution context
Mutability mu = Mutability.create("test");
StarlarkThread thread = new StarlarkThread(mu, StarlarkSemantics.DEFAULT);
Map<String, Object> globals = new HashMap<>();

// Execute bytecode
Object result = BytecodeInterpreter.execute(bytecode, thread, globals);
```

### Generate WebAssembly

```java
// Compile to bytecode
BytecodeChunk bytecode = program.getBytecode();

// Generate WAT
String wat = WasmGenerator.generate(bytecode);

// Write to file
Files.writeString(Path.of("output.wat"), wat);

// Convert to binary WASM (requires wat2wasm tool)
// $ wat2wasm output.wat -o output.wasm
```

### Serialize/Deserialize Bytecode

```java
// Serialize
byte[] bytes = BytecodeSerializer.serialize(bytecode);
Files.write(Path.of("program.stc"), bytes);

// Deserialize
byte[] loaded = Files.readAllBytes(Path.of("program.stc"));
BytecodeChunk restored = BytecodeSerializer.deserialize(loaded);
```

## Bytecode Instruction Set

### Stack Operations
- `POP`: Remove top value
- `DUP`: Duplicate top value
- `SWAP`: Swap top two values

### Constants
- `LOAD_CONST <index>`: Load from constant pool
- `LOAD_NONE`, `LOAD_TRUE`, `LOAD_FALSE`: Load literals

### Variables
- `LOAD_LOCAL <slot>`: Load local variable
- `STORE_LOCAL <slot>`: Store local variable
- `LOAD_GLOBAL <name_index>`: Load global
- `STORE_GLOBAL <name_index>`: Store global

### Arithmetic
- `ADD`, `SUBTRACT`, `MULTIPLY`, `DIVIDE`
- `FLOOR_DIV`, `MODULO`, `POWER`
- `NEGATE`, `POSITIVE`

### Comparisons
- `EQUAL`, `NOT_EQUAL`
- `LESS`, `LESS_EQUAL`, `GREATER`, `GREATER_EQUAL`
- `IN`, `NOT_IN`

### Collections
- `BUILD_LIST <count>`: Create list from stack values
- `BUILD_TUPLE <count>`: Create tuple
- `BUILD_DICT <count>`: Create dict from key-value pairs
- `INDEX`: a[b]
- `SLICE`: a[b:c:d]

### Control Flow
- `JUMP <offset>`: Unconditional jump
- `JUMP_IF_TRUE <offset>`: Jump if true
- `POP_JUMP_IF_FALSE <offset>`: Pop and jump if false
- `RETURN`: Return from function

### Iteration
- `GET_ITER`: Get iterator from object
- `FOR_ITER <end_offset>`: Get next item or jump to end

### Function Calls
- `CALL <pos_args> <kw_args>`: Call function
- `MAKE_FUNCTION <defaults>`: Create function object

## WebAssembly Output

Example Starlark:
```python
def add(a, b):
    return a + b
```

Generated WAT:
```wasm
(module
  ;; Runtime function imports
  (import "starlark" "add" (func $add (param externref externref) (result externref)))

  ;; Main function
  (func $add (export "add")
    (param $param0 externref)
    (param $param1 externref)
    (result externref)

    ;; Load parameters
    local.get $param0
    local.get $param1

    ;; Call add
    call $add

    ;; Return result
    return
  )
)
```

## Testing

Run the test suite:

```bash
# Build the project
mvn clean compile

# Run tests
mvn test -Dtest=BytecodeCompilerTest

# Or run the standalone test
mvn exec:java -Dexec.mainClass="net.starlark.java.eval.compiler.BytecodeCompilerTest"
```

## Stack Trace Preservation

The bytecode compiler **fully preserves stack traces** when exceptions occur:

### How It Works

1. **Line Number Tracking**: Each bytecode instruction stores its source line number
2. **Location Information**: Exceptions include file:line:column information
3. **Call Stack Integration**: The interpreter integrates with StarlarkThread's call stack
4. **Complete Stack Traces**: Error messages show the full chain of function calls

### Example

Given this Starlark code:
```python
# file: test.star
def divide(a, b):
    return a / b  # Line 2

def calculate():
    return divide(10, 0)  # Line 5 - error!

result = calculate()  # Line 7
```

An error will produce:
```
Traceback (most recent call last):
  File "test.star", line 7, in <toplevel>
  File "test.star", line 5, in calculate
  File "test.star", line 2, in divide
Error: division by zero
```

### Implementation Details

- **BytecodeCompiler**: Passes line numbers when emitting instructions via `getLine(node)`
- **BytecodeChunk**: Stores parallel arrays of instructions and line numbers
- **BytecodeInterpreter**:
  - Wraps execution in try-catch to capture exceptions
  - Calls `ex.ensureStack(thread)` to attach call stack
  - Creates Location objects with file:line information
  - Integrates with StarlarkThread for complete stack traces

### Testing

The test suite includes `testStackTracePreservation()` which verifies:
- Line numbers are captured during compilation
- Each instruction has associated line number information
- Exceptions contain proper source location data

## Performance Considerations

### Bytecode Benefits
1. **Faster execution**: Bytecode interpretation is faster than tree-walking
2. **Smaller memory footprint**: Bytecode is more compact than AST
3. **Caching**: Serialized bytecode can be cached to disk
4. **Analysis**: Easier to optimize and analyze than AST
5. **Debug information**: Line numbers preserved without performance penalty

### WebAssembly Benefits
1. **Near-native performance**: WASM executes at near-native speed
2. **Platform-independent**: Runs anywhere WASM is supported
3. **Sandboxed**: Secure execution environment
4. **Browser compatibility**: Can run Starlark in web browsers

### JVM Bytecode Benefits
1. **Native JVM execution**: No interpreter overhead
2. **JIT compilation**: Hotspot can optimize the generated code
3. **Full debugging support**: Works with standard Java debuggers
4. **Interoperability**: Direct integration with Java libraries
5. **Stack traces**: Full source line mapping via LineNumberTable

## Configurable Backend System

The bytecode compiler supports multiple compilation targets through a pluggable backend system.

### Available Targets (BytecodeTarget)

| Target | File Extension | Description |
|--------|---------------|-------------|
| `INTERPRETER` | `.stc` | Default bytecode interpreter execution |
| `STARLARK_GO` | `.stc` | starlark-go style stack-based interpreter |
| `STARLARK_RUST` | `.stc` | starlark-rust style slot-based interpreter |
| `BUCK` | `.stc` | Buck/Starlark style IR-based interpreter |
| `JVM` | `.class` | Native JVM class files |
| `WASM` | `.wat` | WebAssembly text format |

### Interpreter Backend Comparison

| Backend | Model | Memory Layout | Best For |
|---------|-------|---------------|----------|
| `INTERPRETER` | Stack-based | Separate stack + locals | Default, most tested |
| `STARLARK_GO` | Stack-based | Separate stack + locals | google/starlark-go compatibility |
| `STARLARK_RUST` | Slot-based | Unified slots array | Cache locality, performance |
| `BUCK` | IR-based | Slots + call cache | Optimization, call site caching |

#### starlark-go Style (Stack-Based)

Follows the [google/starlark-go](https://github.com/google/starlark-go) execution model:
- Stack-based virtual machine with separate operand stack
- Local variables stored in a separate array
- Simple switch-based opcode dispatch
- Delta-encoded position tracking for debugging

```java
// Execute with starlark-go style interpreter
Object result = StarlarkGoInterpreter.execute(chunk, thread, globals);

// Or via unified executor
Object result = BytecodeExecutor.execute(chunk, thread, globals, BytecodeTarget.STARLARK_GO);
```

#### starlark-rust Style (Slot-Based)

Follows the [facebook/starlark-rust](https://github.com/facebook/starlark-rust) execution model:
- Slot-based memory model: unified array for locals AND stack
- Fixed frame size computed at compile time
- Optimized for cache-friendly sequential memory access

Memory layout:
```
┌──────────────────────────────────────────────────────┐
│ Local Variables (0..n-1) │ Stack (n..max_stack) │
└──────────────────────────────────────────────────────┘
```

```java
// Execute with starlark-rust style interpreter
Object result = StarlarkRustInterpreter.execute(chunk, thread, globals);

// Or via unified executor
Object result = BytecodeExecutor.execute(chunk, thread, globals, BytecodeTarget.STARLARK_RUST);
```

#### Buck/Starlark Style (IR-Based)

Follows the [facebook/buck](https://github.com/facebook/buck/tree/dev/starlark) Starlark execution model:
- IR-based intermediate representation layer
- Slot-based variable management (Local, Global, Cell, Free)
- Call site caching for repeated method calls
- Type-specialized operations (PLUS_STRING, PLUS_LIST)

```java
// Execute with Buck-style interpreter
Object result = BuckStyleInterpreter.execute(chunk, thread, globals);

// Or via unified executor
Object result = BytecodeExecutor.execute(chunk, thread, globals, BytecodeTarget.BUCK);
```

### Using the Backend API

```java
// Use specific backend
BytecodeBackend backend = BytecodeBackend.forTarget(BytecodeTarget.JVM);
byte[] output = backend.generate(chunk, "com/example/Script", "script.star");

// Check backend capabilities
if (backend.isTextOutput()) {
    String text = backend.generateText(chunk, "module_name");
}
```

### Multi-Target Compilation

Compile to multiple backends simultaneously:

```java
MultiTargetCompiler compiler = new MultiTargetCompiler.Builder()
    .addTarget(BytecodeTarget.JVM)
    .addTarget(BytecodeTarget.WASM)
    .setSourceFile("script.star")
    .setClassName("com/example/Script")
    .build();

MultiTargetCompiler.CompilationResult result = compiler.compile(source);

// Get outputs for each target
byte[] jvmBytes = result.getOutput(BytecodeTarget.JVM);
String watText = result.getTextOutput(BytecodeTarget.WASM);
```

Or compile to all targets at once:

```java
MultiTargetCompiler compiler = new MultiTargetCompiler.Builder()
    .allTargets()  // INTERPRETER, JVM, WASM
    .build();
```

### JSR-223 Integration

The script engine supports backend selection:

```java
LarkyScriptEngine engine = new LarkyScriptEngine();

// Set default target
engine.setBytecodeTarget(BytecodeTarget.JVM);

// Compile to multiple targets
MultiTargetCompiler.CompilationResult result = engine.compileToTargets(
    script, BytecodeTarget.JVM, BytecodeTarget.WASM);

// Or get specific output from compiled script
LarkyCompiledScript compiled = engine.compile(script);
byte[] jvmBytes = compiled.getJvmBytecode();
String wat = compiled.getWasmText();
```

## JVM Bytecode Generation

The compiler can also generate native JVM bytecode (.class files):

### Components

- **JvmBytecodeGenerator.java**: Generates .class files from Starlark bytecode
- **StarlarkRuntime.java**: Runtime support methods called by generated code
- **CompiledStarlarkLoader.java**: Dynamic class loading and execution

### Usage

```java
// Compile Starlark to our bytecode
BytecodeChunk chunk = program.getBytecode();

// Generate JVM class file
byte[] classBytes = JvmBytecodeGenerator.generate(chunk, "com/example/MyScript");

// Or use the loader for dynamic execution
CompiledStarlarkLoader.CompiledProgram compiled = CompiledStarlarkLoader.compile(chunk);
Object result = compiled.execute(thread);
```

### Generated Class Structure

```java
// Generated class structure
public final class MyScript {
    public MyScript() { }

    public Object execute(StarlarkThread thread) {
        // Compiled Starlark code
        // Calls to StarlarkRuntime for operations
    }
}
```

### Stack Trace Example

When an error occurs in JVM-compiled Starlark:

```
Exception in thread "main" net.starlark.java.eval.EvalException: division by zero
    at net.starlark.compiled.MyScript_1.execute(myfile.star:5)
    at net.starlark.java.eval.compiler.CompiledStarlarkLoader.executeClass(CompiledStarlarkLoader.java:89)
    at MyApp.main(MyApp.java:15)
```

The stack trace shows:
- Original Starlark source file name (`myfile.star`)
- Line number where the error occurred (`:5`)
- Full Java call stack for context

## Future Enhancements

### Optimizations
- [ ] Constant folding
- [ ] Dead code elimination
- [ ] Peephole optimization
- [ ] Type specialization
- [ ] Inline caching

### Features
- [ ] Full closure support
- [ ] Exception handling bytecode
- [ ] Debugging support (breakpoints, stepping)
- [ ] Profile-guided optimization
- [ ] JIT compilation to native code

### WebAssembly
- [ ] Complete runtime library in JavaScript
- [ ] WASM binary generation (skip WAT)
- [ ] SIMD instructions for performance
- [ ] Threading support
- [ ] DOM integration for browser usage

## File Locations

```
libstarlark/src/main/java/net/starlark/java/
├── eval/
│   ├── BytecodeInterpreter.java        # Default bytecode executor
│   ├── BytecodeFunction.java           # Bytecode function wrapper
│   └── compiler/
│       ├── Opcode.java                 # Bytecode instruction set
│       ├── Instruction.java            # Single instruction
│       ├── ConstantPool.java           # Constant storage
│       ├── BytecodeChunk.java          # Bytecode container
│       ├── BytecodeCompiler.java       # AST → Bytecode
│       ├── StarlarkGoInterpreter.java  # starlark-go style executor
│       ├── StarlarkRustInterpreter.java # starlark-rust style executor
│       ├── BuckStyleInterpreter.java   # Buck/Starlark style executor
│       ├── BytecodeExecutor.java       # Unified execution interface
│       ├── WasmGenerator.java          # Bytecode → WASM
│       ├── BytecodeSerializer.java     # Serialization
│       ├── JvmBytecodeGenerator.java   # Bytecode → JVM .class
│       ├── StarlarkRuntime.java        # JVM runtime support
│       ├── CompiledStarlarkLoader.java # Dynamic class loading
│       ├── BytecodeTarget.java         # Compilation target enum
│       ├── BytecodeBackend.java        # Pluggable backend interface
│       └── MultiTargetCompiler.java    # Multi-backend compilation
└── syntax/
    └── Program.java                    # Modified for bytecode support

larky/src/main/java/com/verygood/security/larky/jsr223/
├── LarkyScriptEngine.java              # JSR-223 engine with bytecode support
└── LarkyCompiledScript.java            # Compiled script with multi-target output

libstarlark/src/test/java/net/starlark/java/eval/compiler/
└── BytecodeCompilerTest.java           # Comprehensive tests
```

## References

- [WebAssembly Specification](https://webassembly.github.io/spec/)
- [Starlark Language Specification](https://github.com/bazelbuild/starlark)
- [Java Virtual Machine Specification](https://docs.oracle.com/javase/specs/jvms/se17/html/)

## License

Copyright 2025 The Bazel Authors. Licensed under Apache License 2.0.
