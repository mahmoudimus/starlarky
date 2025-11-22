# Starlark Bytecode Compiler Implementation Progress

## Project Overview

Implementing a bytecode compilation and execution system for Starlark as an alternative to the tree-walking interpreter. The goal is 100% feature parity with all 60 EvaluationTest tests passing.

**Branch:** `claude/multi-backend-compilation-015oF9gXw953BieDzsJgaAoC`

**Current Status:** 39/60 tests passing (65%)

## Architecture

```
AST (syntax tree)
  ↓
BytecodeCompiler (compiles statements/expressions)
  ↓
BytecodeChunk (contains instructions + constant pool)
  ↓
BytecodeInterpreter (stack-based VM executor)
```

### Key Components

1. **BytecodeCompiler** - Translates AST nodes to bytecode instructions
2. **BytecodeChunk** - Container for bytecode (instructions, constants, metadata)
3. **BytecodeInterpreter** - Executes bytecode using a stack-based VM
4. **Opcode** - Enum of all bytecode instructions
5. **Instruction** - Individual bytecode instruction (opcode + operands)
6. **BytecodeFunction** - Represents compiled function objects

## Completed Work

### 1. Basic Bytecode Infrastructure ✅
- Opcode definitions for all basic operations
- Instruction format: 1-byte opcode + 4-byte operands
- Constant pool for literals
- Stack-based execution engine

### 2. Control Flow ✅
- IF/ELSE statements with conditional jumps
- FOR loops with iterator protocol
- RETURN statements
- BREAK/CONTINUE (basic support)

### 3. Tuple Destructuring ✅
```python
a, b, c = [1, 2, 3]  # Works correctly
```
- Implemented UNPACK_SEQUENCE opcode
- Proper validation of sequence length
- Correct element ordering

### 4. List/Dict Comprehensions ✅
```python
y = [x + 1 for x in [1, 2, 3]]  # Now works!
```
- Recursive clause compilation
- Stack depth tracking for nested comprehensions
- LIST_APPEND and DICT_ADD opcodes

### 5. Jump Patching (CRITICAL FIX) ✅
**Bugs Fixed:**
- `markLabel()` was storing byte offsets instead of instruction indices
- `updateInstructionOperand()` had wrong parameter order
- Redundant POP after FOR_ITER loops

**Impact:** Increased passing tests from 36/60 → 39/60

### 6. Globals Persistence ✅
- Bytecode-modified globals written back to Module
- Proper handling of predeclared bindings vs user-defined globals

### 7. Thread Interruption (Partial) ⚠️
- `thread.checkInterrupt()` called during execution
- Some interrupt tests still failing (see below)

### 8. Duplicate Key Detection ✅
- Runtime validation in BUILD_DICT
- Correct error messages for duplicate dictionary keys

## Test Results

### Passing: 39/60 (65%)

All basic functionality works:
- Arithmetic operations
- Variable assignments
- Function definitions and calls
- Basic loops and conditionals
- Tuple/list/dict construction
- Comprehensions (list and dict)
- String operations
- Boolean logic

### Failing: 21/60 (35%)

#### Category 1: Interrupt Handling (4 tests)
- `testExecutionNotStartedOnInterrupt`
- `testForComprehensionAbortedOnInterrupt`
- `testForLoopAbortedOnInterrupt`
- `testFunctionCallsNotStartedOnInterrupt`

**Issue:** Bytecode interpreter throws EvalException instead of InterruptedException

**Root Cause:**
```java
// BytecodeInterpreter catches InterruptedException and wraps it:
catch (InterruptedException e) {
  throw new EvalException("interrupted", e);
}
```

**Fix Required:** Let InterruptedException propagate directly, don't wrap it.

---

#### Category 2: Mutation Tracking (4 tests)
- `testDictComprehensionUpdate`
- `testListComprehensionUpdate`
- `testListComprehensionUpdateInClause`
- `testNestedListComprehensionUpdate`

**Issue:** No error when mutating collection during for-loop iteration

**Example:**
```python
x = [1, 2, 3]
for i in x:
    x.append(4)  # Should error: "list value is temporarily immutable"
```

**Root Cause:** Not calling `EvalUtils.addIterator()` / `removeIterator()`

**Fix Required:**
1. Call `EvalUtils.addIterator(iterable)` in GET_ITER
2. Call `EvalUtils.removeIterator(iterable)` when FOR_ITER exhausts
3. These methods lock collections during iteration

---

#### Category 3: Type Checking (3 tests)
- `testConcatLists`
- `testListConcatenation`
- `testAccessDictWithATupleKey`

**Issue:** Missing runtime type validation

**Examples:**
```python
[1] + (2,)  # Should error: "unsupported binary operation: list + tuple"
{'key': [1, 2]}  # Should error if key contains unhashable list
```

**Root Cause:** Bytecode ADD opcode doesn't validate operand types

**Fix Required:**
1. In ADD/MULTIPLY/etc: check types match expected combinations
2. In BUILD_DICT: validate keys are hashable
3. Use `Starlark.type()` to get type names for error messages

---

#### Category 4: Comprehension Validation (3 tests)
- `testDictComprehensionOnNonIterable`
- `testListComprehensionFailsOnNonSequence`
- `testListComprehensionOnStringIsForbidden`

**Issue:** Missing validation that comprehension sources are iterable/sequences

**Examples:**
```python
[x for x in 5]  # Should error: "type 'int' is not iterable"
[x for x in "abc"]  # Should error: strings forbidden in comprehensions
```

**Root Cause:** GET_ITER doesn't validate the object is iterable

**Fix Required:**
1. In GET_ITER: check `Starlark.isIterable()` before creating iterator
2. Add special check to forbid string iteration in comprehensions
3. Provide clear error messages with type information

---

#### Category 5: Execution Limits (2 tests)
- `testExecutionSteps`
- `testExpiration`

**Issue:** No execution step counting or time limits

**Root Cause:** Not integrating with `StarlarkThread` step counter

**Fix Required:**
1. In BytecodeInterpreter main loop: call `thread.steps++`
2. Check `thread.isExpired()` periodically
3. Throw appropriate exception when limits exceeded

---

#### Category 6: Error Messages (2 tests)
- `testListComprehensionDefinitionOrder`
- `testDictKeysDuplicateKeyArgs`

**Issue:** Error messages don't match tree-walking interpreter

**Examples:**
- Expected: "local variable 'y' is referenced before assignment"
- Got: "local variable not initialized"

**Fix Required:** Update error messages to match exactly

---

#### Category 7: Load Statement (1 test)
- `testLoadsBindLocally`

**Issue:** LOAD statement not implemented

**Fix Required:**
1. Implement LOAD_MODULE opcode
2. Handle module loading and binding
3. This is a larger feature - may defer

---

#### Category 8: Module Rebinding (1 test)
- `testTopLevelRebinding`

**Issue:** Internal error during bytecode execution

**Fix Required:** Debug this specific test case (likely globals-related)

---

#### Category 9: Format Strings (1 test)
- `testExec`

**Issue:** Format string handling incorrect

**Error:** "not enough arguments for format pattern '%s%d': (['foo', 1],)"

**Fix Required:** Debug format string implementation in bytecode

---

## Key Files

### Compilation
- `/home/user/starlarky/libstarlark/src/main/java/net/starlark/java/eval/compiler/BytecodeCompiler.java`
  - Main compiler: AST → bytecode
  - Jump patching logic
  - Comprehension compilation

- `/home/user/starlarky/libstarlark/src/main/java/net/starlark/java/eval/compiler/BytecodeChunk.java`
  - Bytecode container
  - Instruction storage
  - `updateInstructionOperand()` for jump patching

- `/home/user/starlarky/libstarlark/src/main/java/net/starlark/java/eval/compiler/Opcode.java`
  - All bytecode instruction definitions
  - Operand counts

### Execution
- `/home/user/starlarky/libstarlark/src/main/java/net/starlark/java/eval/BytecodeInterpreter.java`
  - Stack-based VM
  - Main execution loop (line ~200-650)
  - Opcode handlers

- `/home/user/starlarky/libstarlark/src/main/java/net/starlark/java/eval/BytecodeFunction.java`
  - Compiled function objects
  - Argument validation
  - Closure support (partial)

### Integration
- `/home/user/starlarky/libstarlark/src/main/java/net/starlark/java/eval/Starlark.java`
  - `execFileProgram()` - chooses bytecode vs tree-walker
  - Globals write-back mechanism
  - Entry point integration

- `/home/user/starlarky/libstarlark/src/main/java/net/starlark/java/syntax/Program.java`
  - Compilation trigger
  - Controlled by `starlark.bytecode` system property

### Tests
- `/home/user/starlarky/libstarlark/src/test/java/net/starlark/java/eval/EvaluationTest.java`
  - Main test suite (60 tests)
  - Run with: `mvn test -Dtest=EvaluationTest -Dstarlark.bytecode=true`

## Running Tests

### Enable Bytecode Mode
```bash
mvn test -Dtest=EvaluationTest -Dstarlark.bytecode=true
```

### Debug Bytecode Output
```bash
mvn test -Dtest=EvaluationTest -Dstarlark.bytecode=true -Ddebug.bytecode=true
```

### Run Specific Test
```bash
mvn test -Dtest=EvaluationTest#testListComprehensionAtTopLevel -Dstarlark.bytecode=true
```

### View Test Results
```bash
cat /home/user/starlarky/libstarlark/target/surefire-reports/net.starlark.java.eval.EvaluationTest.txt
```

## Next Steps (Priority Order)

### 1. Fix Interrupt Handling (Quick Win - 4 tests)
**File:** `BytecodeInterpreter.java`
**Change:** Don't wrap InterruptedException in EvalException
```java
// Current:
catch (InterruptedException e) {
  throw new EvalException("interrupted", e);
}

// Should be:
catch (InterruptedException e) {
  throw e;  // Let it propagate
}
```

### 2. Implement Mutation Tracking (Medium - 4 tests)
**File:** `BytecodeInterpreter.java`
**Changes:**
- GET_ITER: `EvalUtils.addIterator(iterable)`
- FOR_ITER (when exhausted): `EvalUtils.removeIterator(iterable)`

### 3. Add Type Validation (Medium - 3 tests)
**File:** `BytecodeInterpreter.java`
**Changes:**
- ADD/MULTIPLY: Check operand type compatibility
- BUILD_DICT: Validate keys are hashable
- Use `Starlark.type()` for error messages

### 4. Add Comprehension Validation (Medium - 3 tests)
**File:** `BytecodeInterpreter.java`
**Changes:**
- GET_ITER: Check `Starlark.isIterable()`
- Special handling to forbid string iteration in comprehensions

### 5. Implement Execution Limits (Easy - 2 tests)
**File:** `BytecodeInterpreter.java`
**Changes:**
- Main loop: `thread.steps++`
- Check `thread.isExpired()` periodically

### 6. Fix Error Messages (Easy - 2 tests)
**Files:** Various
**Changes:** Update error strings to match tree-walker

### 7. Debug Remaining (Hard - 3 tests)
- testExec (format strings)
- testTopLevelRebinding (globals)
- testLoadsBindLocally (LOAD_MODULE - may defer)

## Important Notes

### Instruction.create() Parameter Order
**CRITICAL:** The signature is `create(Opcode opcode, int operand, int offset)`
- operand comes BEFORE offset
- This was a source of bugs in jump patching

### FOR_ITER Behavior
FOR_ITER already pops the iterator when jumping to break label:
```java
if (!iterator.hasNext()) {
  pop();  // Iterator is removed here
  ip = jumpTarget - 1;
}
```
Don't add extra POP after the loop!

### Stack Depth in Comprehensions
When compiling nested comprehensions, track stack depth carefully:
- Each for clause adds +1 to stack depth (the iterator)
- LIST_APPEND/DICT_ADD operands must account for this
- Formula: `stackDepth + 2` for LIST_APPEND (result + iterator + value)

### Debug Logging
Enable with `-Ddebug.bytecode=true`:
- Prints full bytecode chunk before execution
- Prints each instruction as it executes with stack depth
- Prints label marking and jump patching

### Maven Hanging
Some interrupt tests cause Maven to hang after completion. The tests DO complete (check surefire-reports), but the process doesn't exit. Use `timeout` or `pkill` if needed.

## Recent Commits

1. **24333cb** - Implement tuple/list destructuring for bytecode execution
2. **00c245c** - Implement list and dict comprehension compilation (partial)
3. **64f34d5** - Fix comprehension stack depth tracking and implement jump patching
4. **85ed46a** - Add critical fixes: globals persistence, thread interruption, duplicate key detection
5. **c4da1a7** - Fix jump patching in bytecode compiler - now 39/60 tests pass ⭐ **(latest)**

## How to Resume Work

1. **Checkout the branch:**
   ```bash
   git checkout claude/multi-backend-compilation-015oF9gXw953BieDzsJgaAoC
   ```

2. **Run tests to confirm baseline:**
   ```bash
   mvn test -Dtest=EvaluationTest -Dstarlark.bytecode=true
   ```
   Should show: `Tests: 60, Failures: 14, Errors: 7` (39 passing)

3. **Pick a category from "Next Steps" above**
   Start with interrupt handling for quick wins

4. **Make changes and test:**
   ```bash
   mvn clean compile
   mvn test -Dtest=EvaluationTest#testForLoopAbortedOnInterrupt -Dstarlark.bytecode=true
   ```

5. **Commit incrementally:**
   ```bash
   git add -A
   git commit -m "Fix interrupt handling - now 43/60 tests pass"
   git push -u origin claude/multi-backend-compilation-015oF9gXw953BieDzsJgaAoC
   ```

## Reference: Facebook Buck Implementation

User emphasized studying Facebook's Buck implementation:
https://github.com/jasonnam/buck/tree/ad735af4d06040e78c85427fb6baeae90b64632b/starlark/src/main/java/net/starlark/java/eval

Key differences:
- Buck may use register-based VM vs our stack-based
- Buck may have IR layer
- Study their approach to comprehensions, scoping, and iteration

## Goal

**Target:** 60/60 tests passing (100%)
**Current:** 39/60 tests passing (65%)
**Remaining:** 21 tests to fix

The low-hanging fruit (interrupt handling + mutation tracking) could get us to ~47/60 (78%) quickly.
