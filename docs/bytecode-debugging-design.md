# Bytecode Interpreter Debugging Design

## Overview

This document outlines the changes required to support debugging in a bytecode-based Starlark interpreter while maintaining compatibility with the existing Debug API.

## Current State Analysis

### What Stays the Same
- `Debug` public API (Debugger interface, Stepping enum, Frame interface)
- `EvalException` stack trace mechanism
- `StarlarkThread.CallStackEntry` external representation
- Client code using the debugger

### What Changes
- Internal location tracking mechanism (AST nodes → PC + line tables)
- Frame representation (add PC field)
- How locations are materialized for errors
- Performance characteristics (cheaper to track locations)

---

## Required Changes

### 1. Line Number Table

**New Class: `LineNumberTable`**

```java
package net.starlark.java.eval;

import net.starlark.java.syntax.Location;

/**
 * Maps bytecode program counter (PC) offsets to source locations.
 * Used for error reporting and debugging.
 */
public final class LineNumberTable {

  // Compact representation: array of (pc, location) pairs
  // Invariant: pcs are in ascending order
  private final int[] pcs;
  private final Location[] locations;

  private LineNumberTable(int[] pcs, Location[] locations) {
    this.pcs = pcs;
    this.locations = locations;
  }

  /**
   * Returns the source location for the given program counter.
   * Uses binary search for O(log n) lookup.
   */
  public Location getLocation(int pc) {
    // Binary search to find the largest pc <= target pc
    int left = 0;
    int right = pcs.length - 1;
    int result = 0;

    while (left <= right) {
      int mid = (left + right) / 2;
      if (pcs[mid] <= pc) {
        result = mid;
        left = mid + 1;
      } else {
        right = mid - 1;
      }
    }

    return locations[result];
  }

  /** Builder for constructing line number tables during compilation. */
  public static final class Builder {
    private final List<Integer> pcs = new ArrayList<>();
    private final List<Location> locations = new ArrayList<>();

    /**
     * Records that bytecode starting at 'pc' corresponds to source at 'location'.
     * PCs must be added in ascending order.
     */
    public void add(int pc, Location location) {
      if (!pcs.isEmpty() && pc <= pcs.get(pcs.size() - 1)) {
        throw new IllegalArgumentException("PCs must be added in ascending order");
      }

      // Optimization: coalesce consecutive entries with same location
      if (!locations.isEmpty() && locations.get(locations.size() - 1).equals(location)) {
        return; // Skip redundant entry
      }

      pcs.add(pc);
      locations.add(location);
    }

    public LineNumberTable build() {
      int[] pcArray = pcs.stream().mapToInt(Integer::intValue).toArray();
      Location[] locArray = locations.toArray(new Location[0]);
      return new LineNumberTable(pcArray, locArray);
    }
  }
}
```

**Usage during compilation:**

```java
// In bytecode compiler
LineNumberTable.Builder lineTable = new LineNumberTable.Builder();

// When compiling an if statement at line 10
lineTable.add(currentPC, ifStmt.getStartLocation());
emit(LOAD_LOCAL, varIndex);

lineTable.add(currentPC, ifStmt.getCondition().getStartLocation());
emit(JUMP_IF_FALSE, elseLabel);

// ... etc
```

---

### 2. Bytecode Function Representation

**Update `StarlarkFunction` or create new `BytecodeFunction`:**

```java
package net.starlark.java.eval;

/**
 * A compiled Starlark function with bytecode representation.
 */
public final class BytecodeFunction implements StarlarkCallable {

  // Existing fields
  private final String name;
  private final Location location;
  private final Module module;

  // New bytecode-specific fields
  private final byte[] bytecode;           // Compiled bytecode instructions
  private final Object[] constants;        // Constant pool
  private final LineNumberTable lineTable; // PC -> source location mapping
  private final String[] localNames;       // Local variable names for debugging
  private final int numLocals;             // Number of local variables
  private final int numParams;             // Number of parameters

  // Parameter metadata
  private final Tuple<Object> defaults;    // Default parameter values
  private final boolean hasVarargs;        // Has *args
  private final boolean hasKwargs;         // Has **kwargs

  @Override
  public Object fastcall(StarlarkThread thread, Object[] positional, Object[] named)
      throws EvalException, InterruptedException {

    // Set up new frame with bytecode context
    BytecodeFrame frame = new BytecodeFrame(
        this,
        thread,
        positional,
        named
    );

    // Execute bytecode
    return BytecodeInterpreter.execute(frame);
  }

  // For debugging
  Location getLocationForPC(int pc) {
    return lineTable.getLocation(pc);
  }

  String getLocalName(int index) {
    return index < localNames.length ? localNames[index] : "unknown";
  }
}
```

---

### 3. Bytecode Execution Frame

**New Class: `BytecodeFrame`**

```java
package net.starlark.java.eval;

/**
 * Execution frame for bytecode interpreter.
 * Contains program counter, operand stack, and local variables.
 */
final class BytecodeFrame {

  final BytecodeFunction function;
  final StarlarkThread thread;

  // Program counter - current bytecode offset
  int pc = 0;

  // Operand stack
  final Object[] stack;
  int sp = 0; // stack pointer

  // Local variables (parameters + locals)
  final Object[] locals;

  // Current source location (cached from line table)
  Location currentLocation;

  BytecodeFrame(
      BytecodeFunction function,
      StarlarkThread thread,
      Object[] positional,
      Object[] named) {

    this.function = function;
    this.thread = thread;
    this.stack = new Object[256]; // Max stack depth
    this.locals = new Object[function.numLocals];

    // Initialize parameters into locals
    // ... parameter binding logic ...

    // Initialize location
    this.currentLocation = function.getLocationForPC(0);
  }

  /** Updates the current location based on PC. Called before each operation. */
  void updateLocation() {
    currentLocation = function.getLocationForPC(pc);
  }

  /** For debugger: get current location. */
  Location getLocation() {
    return currentLocation;
  }

  /** For debugger: get local variables as a map. */
  ImmutableMap<String, Object> getLocals() {
    ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
    for (int i = 0; i < function.numLocals; i++) {
      if (locals[i] != null) {
        builder.put(function.getLocalName(i), locals[i]);
      }
    }
    return builder.buildOrThrow();
  }
}
```

---

### 4. Bytecode Interpreter Loop

**New Class: `BytecodeInterpreter`**

```java
package net.starlark.java.eval;

/**
 * Executes Starlark bytecode.
 */
final class BytecodeInterpreter {

  private BytecodeInterpreter() {} // uninstantiable

  static Object execute(BytecodeFrame fr) throws EvalException, InterruptedException {

    StarlarkThread thread = fr.thread;
    byte[] code = fr.function.bytecode;
    Object[] constants = fr.function.constants;
    Object[] stack = fr.stack;
    Object[] locals = fr.locals;

    // Push frame onto call stack
    thread.push(fr);

    try {
      // Main interpreter loop
      while (fr.pc < code.length) {

        // Update location for debugging and error reporting
        fr.updateLocation();

        // Check for debugger breakpoint
        Debugger debugger = Debug.debugger.get();
        if (debugger != null) {
          debugger.before(thread, fr.currentLocation);
        }

        // Check for interrupts and limits
        thread.checkInterrupt();
        if (++thread.steps >= thread.stepLimit) {
          throw Starlark.errorf("Starlark computation cancelled: too many steps");
        }

        // Decode instruction
        int opcode = code[fr.pc] & 0xFF;

        // Execute instruction
        switch (opcode) {

          case Opcode.LOAD_CONST: {
            int constIndex = code[fr.pc + 1] & 0xFF;
            stack[fr.sp++] = constants[constIndex];
            fr.pc += 2;
            break;
          }

          case Opcode.LOAD_LOCAL: {
            int localIndex = code[fr.pc + 1] & 0xFF;
            stack[fr.sp++] = locals[localIndex];
            fr.pc += 2;
            break;
          }

          case Opcode.STORE_LOCAL: {
            int localIndex = code[fr.pc + 1] & 0xFF;
            locals[localIndex] = stack[--fr.sp];
            fr.pc += 2;
            break;
          }

          case Opcode.BINARY_ADD: {
            Object right = stack[--fr.sp];
            Object left = stack[--fr.sp];
            try {
              stack[fr.sp++] = EvalUtils.binaryOp(TokenKind.PLUS, left, right, thread);
            } catch (EvalException ex) {
              // Location already set via updateLocation()
              throw ex;
            }
            fr.pc += 1;
            break;
          }

          case Opcode.CALL: {
            int nargs = code[fr.pc + 1] & 0xFF;
            Object function = stack[fr.sp - nargs - 1];
            Object[] args = new Object[nargs];
            System.arraycopy(stack, fr.sp - nargs, args, 0, nargs);
            fr.sp -= nargs + 1;

            try {
              Object result = Starlark.fastcall(
                  thread,
                  (StarlarkCallable) function,
                  args,
                  new Object[0] // no named args in this simplified example
              );
              stack[fr.sp++] = result;
            } catch (EvalException ex) {
              // Location already set
              throw ex;
            }

            fr.pc += 2;
            break;
          }

          case Opcode.RETURN: {
            Object returnValue = fr.sp > 0 ? stack[--fr.sp] : Starlark.NONE;
            return returnValue;
          }

          case Opcode.JUMP_IF_FALSE: {
            int offset = (code[fr.pc + 1] << 8) | (code[fr.pc + 2] & 0xFF);
            Object condition = stack[--fr.sp];
            if (!Starlark.truth(condition)) {
              fr.pc += offset;
            } else {
              fr.pc += 3;
            }
            break;
          }

          case Opcode.JUMP: {
            int offset = (code[fr.pc + 1] << 8) | (code[fr.pc + 2] & 0xFF);
            fr.pc += offset;
            break;
          }

          // ... more opcodes ...

          default:
            throw new IllegalStateException("Unknown opcode: " + opcode);
        }
      }

      // Reached end of bytecode without explicit return
      return Starlark.NONE;

    } finally {
      // Pop frame from call stack
      thread.pop();
    }
  }
}
```

---

### 5. Opcode Definitions

**New Class: `Opcode`**

```java
package net.starlark.java.eval;

/**
 * Bytecode instruction opcodes.
 */
final class Opcode {

  private Opcode() {} // uninstantiable

  // Stack operations
  static final int LOAD_CONST = 0x01;    // Push constant
  static final int LOAD_LOCAL = 0x02;    // Push local variable
  static final int STORE_LOCAL = 0x03;   // Pop and store to local
  static final int LOAD_GLOBAL = 0x04;   // Push global variable
  static final int STORE_GLOBAL = 0x05;  // Pop and store to global
  static final int POP = 0x06;           // Pop and discard
  static final int DUP = 0x07;           // Duplicate top of stack

  // Binary operations
  static final int BINARY_ADD = 0x10;
  static final int BINARY_SUB = 0x11;
  static final int BINARY_MUL = 0x12;
  static final int BINARY_DIV = 0x13;
  static final int BINARY_FLOOR_DIV = 0x14;
  static final int BINARY_MOD = 0x15;
  static final int BINARY_POW = 0x16;

  // Comparison operations
  static final int COMPARE_EQ = 0x20;
  static final int COMPARE_NE = 0x21;
  static final int COMPARE_LT = 0x22;
  static final int COMPARE_LE = 0x23;
  static final int COMPARE_GT = 0x24;
  static final int COMPARE_GE = 0x25;
  static final int COMPARE_IN = 0x26;

  // Unary operations
  static final int UNARY_MINUS = 0x30;
  static final int UNARY_PLUS = 0x31;
  static final int UNARY_NOT = 0x32;

  // Control flow
  static final int JUMP = 0x40;              // Unconditional jump
  static final int JUMP_IF_FALSE = 0x41;     // Jump if top of stack is false
  static final int JUMP_IF_TRUE = 0x42;      // Jump if top of stack is true
  static final int RETURN = 0x43;            // Return from function

  // Function calls
  static final int CALL = 0x50;              // Call function
  static final int CALL_KW = 0x51;           // Call with keyword args

  // Data structure operations
  static final int BUILD_LIST = 0x60;        // Build list from stack
  static final int BUILD_TUPLE = 0x61;       // Build tuple from stack
  static final int BUILD_DICT = 0x62;        // Build dict from stack
  static final int LIST_APPEND = 0x63;       // Append to list
  static final int DICT_SET = 0x64;          // Set dict item

  // Attribute/index operations
  static final int LOAD_ATTR = 0x70;         // Load attribute
  static final int STORE_ATTR = 0x71;        // Store attribute
  static final int LOAD_INDEX = 0x72;        // Load by index
  static final int STORE_INDEX = 0x73;       // Store by index

  // Iteration
  static final int GET_ITER = 0x80;          // Get iterator
  static final int FOR_ITER = 0x81;          // Iterate (jump if done)
}
```

---

### 6. Update StarlarkThread

**Modifications to `StarlarkThread.java`:**

```java
public final class StarlarkThread {

  // Existing fields...

  // Frame stack - now can hold both old Frame and BytecodeFrame
  private final ArrayList<Object> frames = new ArrayList<>();

  /** Push a frame onto the call stack. */
  void push(Object frame) {
    frames.add(frame);

    // Notify debugger if needed
    if (frames.size() == 1 && Debug.threadHook != null) {
      Debug.threadHook.onPushFirst(this);
    }
  }

  /** Pop a frame from the call stack. */
  void pop() {
    frames.remove(frames.size() - 1);

    // Notify debugger if needed
    if (frames.isEmpty() && Debug.threadHook != null) {
      Debug.threadHook.onPopLast(this);
    }
  }

  /** Get current frame for debugging. */
  @Nullable
  Object getCurrentFrame() {
    return frames.isEmpty() ? null : frames.get(frames.size() - 1);
  }

  // Update getCallStack to handle BytecodeFrame
  public ImmutableList<CallStackEntry> getCallStack() {
    ImmutableList.Builder<CallStackEntry> result = ImmutableList.builder();

    for (Object frame : frames) {
      if (frame instanceof BytecodeFrame) {
        BytecodeFrame bf = (BytecodeFrame) frame;
        result.add(new CallStackEntry(
            bf.function.getName(),
            bf.getLocation()
        ));
      } else if (frame instanceof Frame) {
        Frame f = (Frame) frame;
        result.add(new CallStackEntry(
            f.fn.getName(),
            f.loc
        ));
      }
    }

    return result.build();
  }

  // For Debug API
  ImmutableList<Debug.Frame> getDebugCallStack() {
    ImmutableList.Builder<Debug.Frame> result = ImmutableList.builder();

    for (Object frame : frames) {
      if (frame instanceof BytecodeFrame) {
        BytecodeFrame bf = (BytecodeFrame) frame;
        result.add(new DebugFrameAdapter(bf));
      } else if (frame instanceof Frame) {
        Frame f = (Frame) frame;
        result.add(f); // Old frames implement Debug.Frame
      }
    }

    return result.build();
  }

  /** Adapter to expose BytecodeFrame as Debug.Frame */
  private static class DebugFrameAdapter implements Debug.Frame {
    private final BytecodeFrame frame;

    DebugFrameAdapter(BytecodeFrame frame) {
      this.frame = frame;
    }

    @Override
    public StarlarkCallable getFunction() {
      return frame.function;
    }

    @Override
    public Location getLocation() {
      return frame.getLocation();
    }

    @Override
    public ImmutableMap<String, Object> getLocals() {
      return frame.getLocals();
    }
  }
}
```

---

### 7. Compiler Integration

**New Class: `BytecodeCompiler`**

```java
package net.starlark.java.eval;

import net.starlark.java.syntax.*;

/**
 * Compiles Starlark AST to bytecode.
 */
final class BytecodeCompiler {

  private final ByteArrayOutputStream bytecode = new ByteArrayOutputStream();
  private final List<Object> constants = new ArrayList<>();
  private final LineNumberTable.Builder lineTable = new LineNumberTable.Builder();
  private final Map<String, Integer> localIndices = new HashMap<>();

  BytecodeFunction compile(Resolver.Function rfn) {

    // Compile function body
    for (Statement stmt : rfn.getStatements()) {
      compileStatement(stmt);
    }

    // Implicit return None at end
    emit(Opcode.LOAD_CONST, addConstant(Starlark.NONE));
    emit(Opcode.RETURN);

    return new BytecodeFunction(
        rfn.getName(),
        rfn.getLocation(),
        bytecode.toByteArray(),
        constants.toArray(),
        lineTable.build(),
        getLocalNames(rfn),
        rfn.getParameterCount(),
        rfn.getDefaultValues()
    );
  }

  private void compileStatement(Statement stmt) {
    // Record source location
    lineTable.add(bytecode.size(), stmt.getStartLocation());

    if (stmt instanceof AssignmentStatement) {
      compileAssignment((AssignmentStatement) stmt);
    } else if (stmt instanceof IfStatement) {
      compileIf((IfStatement) stmt);
    } else if (stmt instanceof ForStatement) {
      compileFor((ForStatement) stmt);
    } else if (stmt instanceof ReturnStatement) {
      compileReturn((ReturnStatement) stmt);
    }
    // ... etc
  }

  private void compileExpression(Expression expr) {
    lineTable.add(bytecode.size(), expr.getStartLocation());

    if (expr instanceof IntLiteral) {
      int value = ((IntLiteral) expr).getValue();
      emit(Opcode.LOAD_CONST, addConstant(StarlarkInt.of(value)));
    } else if (expr instanceof BinaryOperatorExpression) {
      BinaryOperatorExpression binop = (BinaryOperatorExpression) expr;
      compileExpression(binop.getX());
      compileExpression(binop.getY());
      emit(opcodeForBinaryOp(binop.getOperator()));
    }
    // ... etc
  }

  private void emit(int opcode) {
    bytecode.write(opcode);
  }

  private void emit(int opcode, int arg) {
    bytecode.write(opcode);
    bytecode.write(arg);
  }

  private int addConstant(Object value) {
    int index = constants.indexOf(value);
    if (index < 0) {
      index = constants.size();
      constants.add(value);
    }
    return index;
  }
}
```

---

## Migration Strategy

### Phase 1: Infrastructure (No Behavior Change)
1. Add `LineNumberTable` class
2. Add `BytecodeFrame` class
3. Add `Opcode` definitions
4. Update `StarlarkThread` to handle both frame types
5. **Tests pass with tree-walking interpreter**

### Phase 2: Compiler Implementation
1. Implement `BytecodeCompiler`
2. Compile simple expressions first
3. Add support for all statement types
4. **Tests pass with bytecode for simple functions**

### Phase 3: Interpreter Implementation
1. Implement `BytecodeInterpreter`
2. Start with basic opcodes
3. Gradually add complex operations
4. **Tests pass with full bytecode interpreter**

### Phase 4: Performance Optimization
1. Optimize hot paths
2. Add instruction fusion
3. Consider JIT compilation
4. **Benchmarks show performance improvement**

### Phase 5: Cleanup
1. Remove tree-walking interpreter
2. Clean up legacy code
3. Update documentation

---

## Testing Strategy

### Unit Tests for Each Component

**LineNumberTable:**
```java
@Test
public void testLineNumberTable() {
  LineNumberTable.Builder builder = new LineNumberTable.Builder();
  builder.add(0, Location.fromFileLineColumn("test.star", 1, 1));
  builder.add(5, Location.fromFileLineColumn("test.star", 2, 3));
  builder.add(10, Location.fromFileLineColumn("test.star", 3, 5));

  LineNumberTable table = builder.build();

  assertThat(table.getLocation(0).line()).isEqualTo(1);
  assertThat(table.getLocation(3).line()).isEqualTo(1); // Before pc=5
  assertThat(table.getLocation(5).line()).isEqualTo(2);
  assertThat(table.getLocation(7).line()).isEqualTo(2);
  assertThat(table.getLocation(10).line()).isEqualTo(3);
  assertThat(table.getLocation(100).line()).isEqualTo(3); // Last entry
}
```

**Bytecode Compiler:**
```java
@Test
public void testCompileSimpleFunction() {
  // def add(a, b): return a + b
  BytecodeFunction fn = compileFunction("def add(a, b): return a + b");

  assertThat(fn.getName()).isEqualTo("add");
  assertThat(fn.numParams).isEqualTo(2);

  // Should generate: LOAD_LOCAL 0, LOAD_LOCAL 1, BINARY_ADD, RETURN
  byte[] expected = {
    Opcode.LOAD_LOCAL, 0,
    Opcode.LOAD_LOCAL, 1,
    Opcode.BINARY_ADD,
    Opcode.RETURN
  };
  assertThat(fn.bytecode).isEqualTo(expected);
}
```

**Debugger Integration:**
```java
@Test
public void testDebuggerWithBytecode() throws Exception {
  List<Location> visited = new ArrayList<>();

  Debug.setDebugger(new Debug.Debugger() {
    @Override
    public void before(StarlarkThread thread, Location loc) {
      visited.add(loc);
    }

    @Override
    public void close() {}
  });

  try {
    eval("x = 1 + 2");

    // Should have visited the assignment location
    assertThat(visited).isNotEmpty();
    assertThat(visited.get(0).line()).isEqualTo(1);
  } finally {
    Debug.setDebugger(null);
  }
}
```

---

## Performance Expectations

### Location Tracking
- **Before**: AST node materialization on every error
- **After**: Simple array lookup O(log n) or cached O(1)
- **Improvement**: ~10-50x faster error handling

### Stepping
- **Before**: Complex AST traversal to find next statement
- **After**: Simple PC increment
- **Improvement**: ~100x faster step operations

### Memory
- **Before**: Full AST kept in memory during execution
- **After**: Compact bytecode + line table
- **Improvement**: ~5-10x less memory per function

### Execution
- **Before**: AST dispatch overhead
- **After**: Tight interpreter loop with better cache locality
- **Improvement**: ~2-5x faster execution (before JIT)

---

## Example: Complete Flow

**Source:**
```python
def factorial(n):
    if n <= 1:
        return 1
    return n * factorial(n - 1)
```

**Compiled Bytecode:**
```
PC  Opcode           Arg    Line  Description
--  ---------------  -----  ----  -----------
0   LOAD_LOCAL       0      2     Load n
1   LOAD_CONST       0      2     Load 1
2   COMPARE_LE              2     n <= 1
3   JUMP_IF_FALSE    3      2     Jump to PC 6
4   LOAD_CONST       0      3     Load 1
5   RETURN                  3     Return 1
6   LOAD_LOCAL       0      4     Load n
7   LOAD_GLOBAL      0      4     Load factorial
8   LOAD_LOCAL       0      4     Load n
9   LOAD_CONST       0      4     Load 1
10  BINARY_SUB              4     n - 1
11  CALL             1      4     Call factorial(n-1)
12  BINARY_MUL              4     n * result
13  RETURN                  4     Return result
```

**Debug Session:**
```
>>> factorial(3)
[Debugger] before: factorial:2 (PC 0)
[Debugger] before: factorial:4 (PC 6)
[Debugger] before: factorial:2 (PC 0)  # Recursive call
[Debugger] before: factorial:4 (PC 6)
[Debugger] before: factorial:2 (PC 0)  # Recursive call
[Debugger] before: factorial:3 (PC 4)  # Base case
6
```

---

## Conclusion

The bytecode interpreter enhances debugging by:
1. **More precise location tracking** via PC and line tables
2. **Cheaper location updates** before every operation
3. **Better stepping granularity** at instruction level
4. **Improved profiling** with instruction-level detail
5. **Maintains API compatibility** with existing debugger clients

All while improving performance and reducing memory usage.
