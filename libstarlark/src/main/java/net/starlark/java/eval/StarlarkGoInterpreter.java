// Copyright 2025 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package net.starlark.java.eval;

import net.starlark.java.eval.compiler.BytecodeChunk;
import net.starlark.java.eval.compiler.FunctionDescriptor;
import net.starlark.java.eval.compiler.Instruction;
import net.starlark.java.eval.compiler.Opcode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.TokenKind;

/**
 * A starlark-go style bytecode interpreter.
 *
 * <p>This interpreter follows the starlark-go execution model:
 * <ul>
 *   <li>Stack-based virtual machine with separate operand stack
 *   <li>Local variables stored in a separate array (not on the stack)
 *   <li>Simple switch-based opcode dispatch
 *   <li>Position tracking via delta-encoded line/column information
 *   <li>Free variables for closures stored separately
 * </ul>
 *
 * <p>Key differences from starlark-rust style:
 * <ul>
 *   <li>Operand stack is separate from local variable storage
 *   <li>Uses dynamic stack growth (ArrayList) rather than fixed slots
 *   <li>Simpler memory model at the cost of some performance
 * </ul>
 *
 * @see StarlarkRustInterpreter for the slot-based alternative
 */
public final class StarlarkGoInterpreter {

  /** Execution statistics for profiling. */
  public static final class Stats {
    public long instructionsExecuted;
    public long stackOperations;
    public long functionCalls;
    public long iterations;

    @Override
    public String toString() {
      return String.format(
          "Stats{instructions=%d, stackOps=%d, calls=%d, iterations=%d}",
          instructionsExecuted, stackOperations, functionCalls, iterations);
    }
  }

  // Execution state - mirrors starlark-go's frame structure
  private final BytecodeChunk code;
  private final StarlarkThread thread;
  private final Object[] locals;           // Local variables (separate from stack)
  private final List<Object> stack;        // Operand stack (dynamic)
  private final Map<String, Object> globals;
  private final Object[] freeVars;         // Closure variables
  private final String filename;
  private int pc;                          // Program counter
  private final Stats stats;
  private final Map<Iterator<?>, Object> iteratorToIterable;

  private StarlarkGoInterpreter(
      BytecodeChunk code,
      StarlarkThread thread,
      Map<String, Object> globals,
      Object[] freeVars,
      String filename,
      boolean collectStats) {
    this.code = code;
    this.thread = thread;
    this.locals = new Object[code.getLocalCount()];
    this.stack = new ArrayList<>(16); // Initial capacity like starlark-go
    this.globals = globals != null ? globals : new HashMap<>();
    this.freeVars = freeVars != null ? freeVars : new Object[0];
    this.filename = filename != null ? filename : "<starlark-go>";
    this.pc = 0;
    this.stats = collectStats ? new Stats() : null;
    this.iteratorToIterable = new HashMap<>();
  }

  /**
   * Executes bytecode using the starlark-go interpreter model.
   *
   * @param code the bytecode chunk to execute
   * @param thread the Starlark thread context
   * @param globals global variable namespace
   * @return the execution result
   */
  public static Object execute(BytecodeChunk code, StarlarkThread thread, Map<String, Object> globals)
      throws EvalException, InterruptedException {
    return execute(code, thread, globals, null, null, false);
  }

  /**
   * Executes bytecode with full configuration options.
   */
  public static Object execute(
      BytecodeChunk code,
      StarlarkThread thread,
      Map<String, Object> globals,
      Object[] freeVars,
      String filename,
      boolean collectStats)
      throws EvalException, InterruptedException {

    StarlarkCallable callable = new GoStyleCallable(code.getName(), filename);
    thread.push(callable);
    try {
      StarlarkGoInterpreter interp = new StarlarkGoInterpreter(
          code, thread, globals, freeVars, filename, collectStats);
      return interp.run();
    } catch (EvalException ex) {
      throw ex.ensureStack(thread);
    } finally {
      thread.pop();
    }
  }

  /**
   * Executes bytecode with arguments (for function calls).
   */
  public static Object executeWithArgs(
      BytecodeChunk code,
      StarlarkThread thread,
      Object[] args,
      Map<String, Object> globals,
      String filename)
      throws EvalException, InterruptedException {

    StarlarkGoInterpreter interp = new StarlarkGoInterpreter(
        code, thread, globals, null, filename, false);

    // Initialize parameters - starlark-go style
    int paramCount = Math.min(args.length, code.getParameterCount());
    System.arraycopy(args, 0, interp.locals, 0, paramCount);

    return interp.run();
  }

  private Location currentLocation() {
    int line = code.getLineNumber(pc);
    int col = code.getColumnNumber(pc);
    return Location.fromFileLineColumn(filename, Math.max(0, line), col);
  }

  /**
   * Main execution loop - starlark-go style switch dispatch.
   *
   * <p>This follows starlark-go's interpreter structure with a simple
   * switch statement for opcode dispatch. Each case handles one opcode
   * and manipulates the operand stack and local variables.
   */
  private Object run() throws EvalException, InterruptedException {
    List<Instruction> instructions = code.getInstructions();

    while (pc < instructions.size()) {
      // Check thread state - starlark-go checks cancellation here
      thread.checkInterrupt();
      if (++thread.steps >= thread.stepLimit) {
        throw new EvalException("Starlark computation cancelled: too many steps");
      }

      Instruction instr = instructions.get(pc);
      Opcode op = instr.getOpcode();

      if (stats != null) {
        stats.instructionsExecuted++;
      }

      // Main dispatch - mirrors starlark-go's switch statement
      switch (op) {
        // ===== Stack Operations =====
        case POP:
          pop();
          break;

        case DUP:
          push(peek());
          break;

        case SWAP: {
          Object a = pop();
          Object b = pop();
          push(a);
          push(b);
          break;
        }

        // ===== Constants =====
        case LOAD_CONST:
          push(code.getConstantPool().getConstant(instr.getOperand1()));
          break;

        case LOAD_NONE:
          push(Starlark.NONE);
          break;

        case LOAD_TRUE:
          push(Boolean.TRUE);
          break;

        case LOAD_FALSE:
          push(Boolean.FALSE);
          break;

        // ===== Local Variables (starlark-go: LOCAL/SETLOCAL) =====
        case LOAD_LOCAL: {
          int index = instr.getOperand1();
          Object value = locals[index];
          if (value == null) {
            String name = getLocalName(index);
            throw Starlark.errorf("local variable '%s' referenced before assignment", name);
          }
          push(value);
          break;
        }

        case STORE_LOCAL:
          locals[instr.getOperand1()] = pop();
          break;

        // ===== Global Variables (starlark-go: GLOBAL/SETGLOBAL) =====
        case LOAD_GLOBAL: {
          String name = (String) code.getConstantPool().getConstant(instr.getOperand1());
          Object value = globals.get(name);
          if (value == null) {
            throw Starlark.errorf("name '%s' is not defined", name);
          }
          push(value);
          break;
        }

        case STORE_GLOBAL: {
          String name = (String) code.getConstantPool().getConstant(instr.getOperand1());
          globals.put(name, pop());
          break;
        }

        // ===== Free Variables (starlark-go: FREE/FREECELL) =====
        case LOAD_FREE: {
          int index = instr.getOperand1();
          if (index >= freeVars.length) {
            throw Starlark.errorf("free variable index out of range: %d", index);
          }
          push(freeVars[index]);
          break;
        }

        case STORE_FREE: {
          int index = instr.getOperand1();
          if (index >= freeVars.length) {
            throw Starlark.errorf("free variable index out of range: %d", index);
          }
          freeVars[index] = pop();
          break;
        }

        // ===== Binary Operations (starlark-go: PLUS, MINUS, etc.) =====
        case ADD:
          binaryOp(TokenKind.PLUS);
          break;

        case SUBTRACT:
          binaryOp(TokenKind.MINUS);
          break;

        case MULTIPLY:
          binaryOp(TokenKind.STAR);
          break;

        case DIVIDE:
          binaryOp(TokenKind.SLASH);
          break;

        case FLOOR_DIV:
          binaryOp(TokenKind.SLASH_SLASH);
          break;

        case MODULO:
          binaryOp(TokenKind.PERCENT);
          break;

        // ===== Comparisons (starlark-go: LT, GT, EQL, etc.) =====
        case EQUAL:
          binaryOp(TokenKind.EQUALS_EQUALS);
          break;

        case NOT_EQUAL:
          binaryOp(TokenKind.NOT_EQUALS);
          break;

        case LESS:
          binaryOp(TokenKind.LESS);
          break;

        case LESS_EQUAL:
          binaryOp(TokenKind.LESS_EQUALS);
          break;

        case GREATER:
          binaryOp(TokenKind.GREATER);
          break;

        case GREATER_EQUAL:
          binaryOp(TokenKind.GREATER_EQUALS);
          break;

        case IN:
          binaryOp(TokenKind.IN);
          break;

        case NOT_IN:
          binaryOp(TokenKind.NOT_IN);
          break;

        // ===== Bitwise Operations =====
        case BIT_AND:
          binaryOp(TokenKind.AMPERSAND);
          break;

        case BIT_OR:
          binaryOp(TokenKind.PIPE);
          break;

        case BIT_XOR:
          binaryOp(TokenKind.CARET);
          break;

        case LEFT_SHIFT:
          binaryOp(TokenKind.LESS_LESS);
          break;

        case RIGHT_SHIFT:
          binaryOp(TokenKind.GREATER_GREATER);
          break;

        // ===== Unary Operations =====
        case NEGATE:
          push(EvalUtils.unaryOp(TokenKind.MINUS, pop()));
          break;

        case POSITIVE:
          push(EvalUtils.unaryOp(TokenKind.PLUS, pop()));
          break;

        case NOT:
          push(!Starlark.truth(pop()));
          break;

        case BIT_NOT:
          push(EvalUtils.unaryOp(TokenKind.TILDE, pop()));
          break;

        // ===== Collections (starlark-go: MAKELIST, MAKETUPLE, MAKEDICT) =====
        case BUILD_LIST: {
          int count = instr.getOperand1();
          List<Object> elements = new ArrayList<>(count);
          for (int i = 0; i < count; i++) {
            elements.add(0, pop());
          }
          push(StarlarkList.copyOf(thread.mutability(), elements));
          break;
        }

        case BUILD_TUPLE: {
          int count = instr.getOperand1();
          Object[] elements = new Object[count];
          for (int i = count - 1; i >= 0; i--) {
            elements[i] = pop();
          }
          push(Tuple.of(elements));
          break;
        }

        case BUILD_DICT: {
          int count = instr.getOperand1();
          // Pop pairs in reverse order
          Object[] pairs = new Object[count * 2];
          for (int i = count - 1; i >= 0; i--) {
            pairs[i * 2 + 1] = pop(); // value
            pairs[i * 2] = pop();     // key
          }
          Dict<Object, Object> dict = Dict.of(thread.mutability());
          for (int i = 0; i < count; i++) {
            Object key = pairs[i * 2];
            Object value = pairs[i * 2 + 1];
            int before = dict.size();
            dict.putEntry(key, value);
            if (dict.size() == before) {
              throw Starlark.errorf("dictionary expression has duplicate key: %s", Starlark.repr(key));
            }
          }
          push(dict);
          break;
        }

        case UNPACK_SEQUENCE: {
          int count = instr.getOperand1();
          Object seq = pop();
          List<Object> elements = new ArrayList<>();
          for (Object elem : Starlark.toIterable(seq)) {
            elements.add(elem);
          }
          if (elements.size() != count) {
            throw Starlark.errorf(
                "too %s values to unpack (expected %d, got %d)",
                elements.size() < count ? "few" : "many", count, elements.size());
          }
          // Push in forward order (rightmost on top)
          for (Object elem : elements) {
            push(elem);
          }
          break;
        }

        // ===== Indexing (starlark-go: INDEX, SETINDEX) =====
        case INDEX: {
          Object key = pop();
          Object obj = pop();
          push(EvalUtils.index(thread, obj, key));
          break;
        }

        case STORE_INDEX: {
          Object value = pop();
          Object key = pop();
          Object obj = pop();
          EvalUtils.setIndex(thread, obj, key, value);
          break;
        }

        case SLICE: {
          Object step = pop();
          Object stop = pop();
          Object start = pop();
          Object obj = pop();
          push(Starlark.slice(thread.mutability(), obj, start, stop, step));
          break;
        }

        // ===== Attributes (starlark-go: ATTR, SETFIELD) =====
        case LOAD_ATTR: {
          String name = (String) code.getConstantPool().getConstant(instr.getOperand1());
          Object obj = pop();
          push(Starlark.getattr(thread.mutability(), thread.getSemantics(), obj, name, null));
          break;
        }

        case STORE_ATTR: {
          String name = (String) code.getConstantPool().getConstant(instr.getOperand1());
          Object value = pop();
          Object obj = pop();
          EvalUtils.setField(obj, name, value);
          break;
        }

        // ===== Function Calls (starlark-go: CALL, CALL_VAR, CALL_KW) =====
        case CALL: {
          if (stats != null) stats.functionCalls++;
          int posArgs = instr.getOperand1();
          int encodedKwArgs = instr.getOperand2();
          boolean hasStarStar = (encodedKwArgs & 0x8000) != 0;
          int kwArgs = encodedKwArgs & 0x7FFF;

          // Handle **kwargs
          Map<String, Object> starStarDict = null;
          if (hasStarStar) {
            Object kwObj = pop();
            if (!(kwObj instanceof Dict)) {
              throw Starlark.errorf("argument after ** must be a dict, not '%s'", Starlark.type(kwObj));
            }
            starStarDict = new HashMap<>();
            for (Map.Entry<?, ?> e : ((Dict<?, ?>) kwObj).entrySet()) {
              if (!(e.getKey() instanceof String)) {
                throw Starlark.errorf("keywords must be strings, not '%s'", Starlark.type(e.getKey()));
              }
              starStarDict.put((String) e.getKey(), e.getValue());
            }
          }

          // Collect keyword args
          Map<String, Object> kwargs = new HashMap<>();
          for (int i = 0; i < kwArgs; i++) {
            Object value = pop();
            String key = (String) pop();
            if (kwargs.containsKey(key)) {
              throw Starlark.errorf("got multiple values for argument '%s'", key);
            }
            kwargs.put(key, value);
          }

          // Merge **kwargs
          if (starStarDict != null) {
            for (Map.Entry<String, Object> e : starStarDict.entrySet()) {
              if (kwargs.containsKey(e.getKey())) {
                throw Starlark.errorf("got multiple values for argument '%s'", e.getKey());
              }
              kwargs.put(e.getKey(), e.getValue());
            }
          }

          // Collect positional args
          List<Object> posArgList = new ArrayList<>(posArgs);
          for (int i = 0; i < posArgs; i++) {
            posArgList.add(0, pop());
          }

          Object fn = pop();
          push(Starlark.call(thread, fn, posArgList, kwargs));
          break;
        }

        case RETURN:
          return pop();

        // ===== Control Flow (starlark-go: JMP, CJMP) =====
        case JUMP:
          pc = instr.getOperand1() - 1;
          break;

        case JUMP_IF_TRUE:
          if (Starlark.truth(peek())) {
            pc = instr.getOperand1() - 1;
          }
          break;

        case JUMP_IF_FALSE:
          if (!Starlark.truth(peek())) {
            pc = instr.getOperand1() - 1;
          }
          break;

        case POP_JUMP_IF_TRUE:
          if (Starlark.truth(pop())) {
            pc = instr.getOperand1() - 1;
          }
          break;

        case POP_JUMP_IF_FALSE:
          if (!Starlark.truth(pop())) {
            pc = instr.getOperand1() - 1;
          }
          break;

        // ===== Iteration (starlark-go: ITERPUSH, ITERJMP, ITERPOP) =====
        case GET_ITER: {
          if (stats != null) stats.iterations++;
          Object iterable = pop();
          if (iterable instanceof String) {
            throw new EvalException("type 'string' is not iterable");
          }
          Iterator<?> iter = Starlark.toIterable(iterable).iterator();
          EvalUtils.addIterator(iterable);
          iteratorToIterable.put(iter, iterable);
          push(iter);
          break;
        }

        case FOR_ITER: {
          @SuppressWarnings("unchecked")
          Iterator<Object> iter = (Iterator<Object>) peek();
          if (!iter.hasNext()) {
            pop();
            Object iterable = iteratorToIterable.remove(iter);
            if (iterable != null) {
              EvalUtils.removeIterator(iterable);
            }
            pc = instr.getOperand1() - 1;
          } else {
            push(iter.next());
          }
          break;
        }

        // ===== Comprehension helpers =====
        case LIST_APPEND: {
          int offset = instr.getOperand1();
          @SuppressWarnings("unchecked")
          StarlarkList<Object> list = (StarlarkList<Object>) stackGet(offset);
          list.addElement(pop());
          break;
        }

        case DICT_ADD: {
          int offset = instr.getOperand1();
          @SuppressWarnings("unchecked")
          Dict<Object, Object> dict = (Dict<Object, Object>) stackGet(offset);
          Object value = pop();
          Object key = pop();
          dict.putEntry(key, value);
          break;
        }

        // ===== Function creation =====
        case MAKE_FUNCTION: {
          FunctionDescriptor desc = (FunctionDescriptor) code.getConstantPool().getConstant(instr.getOperand1());
          BytecodeFunction fn = new BytecodeFunction(
              desc.getName(), desc.getLocation(), desc.getChunk(), desc.getParameterNames(), filename);
          fn.setGlobals(globals);
          push(fn);
          break;
        }

        case LOAD_MODULE: {
          String modName = (String) code.getConstantPool().getConstant(instr.getOperand1());
          StarlarkThread.Loader loader = thread.getLoader();
          if (loader == null) {
            throw Starlark.errorf("load statements may not be executed in this thread");
          }
          Module mod = loader.load(modName);
          if (mod == null) {
            throw Starlark.errorf("module '%s' not found", modName);
          }
          push(mod);
          break;
        }

        case NOP:
          break;

        default:
          throw new UnsupportedOperationException("Unsupported opcode: " + op);
      }

      pc++;
    }

    return Starlark.NONE;
  }

  // Stack operations
  private void push(Object value) {
    stack.add(value);
    if (stats != null) stats.stackOperations++;
  }

  private Object pop() throws EvalException {
    if (stack.isEmpty()) {
      throw new EvalException("stack underflow");
    }
    if (stats != null) stats.stackOperations++;
    return stack.remove(stack.size() - 1);
  }

  private Object peek() throws EvalException {
    if (stack.isEmpty()) {
      throw new EvalException("stack underflow");
    }
    return stack.get(stack.size() - 1);
  }

  private Object stackGet(int offset) throws EvalException {
    int index = stack.size() - offset;
    if (index < 0 || index >= stack.size()) {
      throw new EvalException("stack index out of range: " + offset);
    }
    return stack.get(index);
  }

  private void binaryOp(TokenKind op) throws EvalException, InterruptedException {
    Object b = pop();
    Object a = pop();
    push(EvalUtils.binaryOp(op, a, b, thread));
  }

  private String getLocalName(int index) {
    if (index < code.getLocalNames().size()) {
      return code.getLocalNames().get(index);
    }
    return "?";
  }

  /** Callable wrapper for starlark-go style execution. */
  private static class GoStyleCallable implements StarlarkCallable {
    private final String name;
    private final Location location;

    GoStyleCallable(String name, String filename) {
      this.name = name != null ? name : "<toplevel>";
      this.location = filename != null
          ? Location.fromFileLineColumn(filename, 0, 0)
          : Location.BUILTIN;
    }

    @Override
    public String getName() { return name; }

    @Override
    public Location getLocation() { return location; }

    @Override
    public Object call(StarlarkThread thread, Tuple args, Dict<String, Object> kwargs) {
      throw new UnsupportedOperationException("GoStyleCallable should not be called directly");
    }
  }
}
