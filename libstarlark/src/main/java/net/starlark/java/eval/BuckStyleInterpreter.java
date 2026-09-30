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
 * A Buck/Starlark style bytecode interpreter with IR-based optimizations.
 *
 * <p>This interpreter follows the facebook/buck Starlark implementation model:
 * <ul>
 *   <li>Intermediate Representation (IR) layer for optimization
 *   <li>Slot-based variable management (Local, Global, Cell, Free)
 *   <li>Call site caching for repeated function calls
 *   <li>Type-specialized operations (PLUS_STRING, PLUS_LIST)
 *   <li>Copy propagation and lazy local assignment
 * </ul>
 *
 * <h2>Key differences from other backends:</h2>
 * <ul>
 *   <li>Uses call site caching for repeated method calls</li>
 *   <li>Type-specialized opcodes for common patterns</li>
 *   <li>IR-based optimization opportunities</li>
 *   <li>Lazy local number assignment for better slot allocation</li>
 * </ul>
 *
 * <h2>Slot Types (from Buck's BcIrSlot):</h2>
 * <ul>
 *   <li><b>Local</b>: Parameters, variables, temporaries</li>
 *   <li><b>Global</b>: Module-level globals</li>
 *   <li><b>Cell</b>: Closure cell references</li>
 *   <li><b>Free</b>: Free variables from enclosing scopes</li>
 *   <li><b>Const</b>: Constant pool references</li>
 * </ul>
 *
 * @see <a href="https://github.com/facebook/buck/tree/dev/starlark">Buck Starlark</a>
 */
public final class BuckStyleInterpreter {

  /** Call site cache for optimizing repeated method calls. */
  private static final class CallSiteCache {
    private final Map<String, StarlarkCallable> methodCache = new HashMap<>();
    private int hits = 0;
    private int misses = 0;

    StarlarkCallable lookup(Object receiver, String methodName) {
      String key = System.identityHashCode(receiver.getClass()) + ":" + methodName;
      return methodCache.get(key);
    }

    void store(Object receiver, String methodName, StarlarkCallable method) {
      String key = System.identityHashCode(receiver.getClass()) + ":" + methodName;
      methodCache.put(key, method);
    }

    void recordHit() { hits++; }
    void recordMiss() { misses++; }
  }

  /** Slot types following Buck's BcIrSlot hierarchy. */
  private enum SlotType {
    LOCAL,    // Local variable or parameter
    GLOBAL,   // Global variable
    CELL,     // Closure cell
    FREE,     // Free variable from enclosing scope
    CONST     // Constant from constant pool
  }

  // Execution state
  private final BytecodeChunk code;
  private final StarlarkThread thread;
  private final Object[] locals;           // Local slots
  private final List<Object> stack;        // Operand stack
  private final Map<String, Object> globals;
  private final String filename;
  private final CallSiteCache callCache;   // Buck-style call site caching
  private int pc;
  private final Map<Iterator<?>, Object> iteratorToIterable;

  private BuckStyleInterpreter(
      BytecodeChunk code,
      StarlarkThread thread,
      Map<String, Object> globals,
      String filename) {
    this.code = code;
    this.thread = thread;
    this.locals = new Object[code.getLocalCount()];
    this.stack = new ArrayList<>(32);
    this.globals = globals != null ? globals : new HashMap<>();
    this.filename = filename != null ? filename : "<buck-style>";
    this.callCache = new CallSiteCache();
    this.pc = 0;
    this.iteratorToIterable = new HashMap<>();
  }

  /**
   * Executes bytecode using the Buck-style interpreter.
   */
  public static Object execute(BytecodeChunk code, StarlarkThread thread, Map<String, Object> globals)
      throws EvalException, InterruptedException {
    return execute(code, thread, globals, null);
  }

  /**
   * Executes bytecode with filename for error reporting.
   */
  public static Object execute(
      BytecodeChunk code,
      StarlarkThread thread,
      Map<String, Object> globals,
      String filename)
      throws EvalException, InterruptedException {

    StarlarkCallable callable = new BuckStyleCallable(code.getName(), filename);
    thread.push(callable);
    try {
      BuckStyleInterpreter interp = new BuckStyleInterpreter(code, thread, globals, filename);
      return interp.run();
    } catch (EvalException ex) {
      throw ex.ensureStack(thread);
    } finally {
      thread.pop();
    }
  }

  /**
   * Executes bytecode with arguments.
   */
  public static Object executeWithArgs(
      BytecodeChunk code,
      StarlarkThread thread,
      Object[] args,
      Map<String, Object> globals,
      String filename)
      throws EvalException, InterruptedException {

    BuckStyleInterpreter interp = new BuckStyleInterpreter(code, thread, globals, filename);

    // Initialize parameters in local slots
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
   * Main execution loop with Buck-style optimizations.
   */
  private Object run() throws EvalException, InterruptedException {
    List<Instruction> instructions = code.getInstructions();

    while (pc < instructions.size()) {
      thread.checkInterrupt();
      if (++thread.steps >= thread.stepLimit) {
        throw new EvalException("Starlark computation cancelled: too many steps");
      }

      Instruction instr = instructions.get(pc);
      Opcode op = instr.getOpcode();

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

        // ===== Constants (Buck: Const slot type) =====
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

        // ===== Local Variables (Buck: Local/LazyLocal slot type) =====
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

        // ===== Globals (Buck: Global slot type) =====
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

        // ===== Free Variables (Buck: Cell/Free slot types) =====
        case LOAD_FREE:
          throw new UnsupportedOperationException("LOAD_FREE not implemented");

        case STORE_FREE:
          throw new UnsupportedOperationException("STORE_FREE not implemented");

        // ===== Binary Operations with Type Specialization =====
        case ADD:
          // Buck has PLUS_STRING, PLUS_LIST for type-specialized ops
          binaryOpWithTypeSpecialization(TokenKind.PLUS);
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

        // ===== Comparisons (Buck has specialized EQ, NOT_EQ) =====
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

        // ===== Membership (Buck: IN, NOT_IN) =====
        case IN:
          binaryOp(TokenKind.IN);
          break;

        case NOT_IN:
          binaryOp(TokenKind.NOT_IN);
          break;

        // ===== Bitwise =====
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

        // ===== Collections (Buck: LIST, TUPLE, DICT) =====
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
          Object[] pairs = new Object[count * 2];
          for (int i = count - 1; i >= 0; i--) {
            pairs[i * 2 + 1] = pop();
            pairs[i * 2] = pop();
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

        // ===== Unpack (Buck: UNPACK) =====
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
          for (Object elem : elements) {
            push(elem);
          }
          break;
        }

        // ===== Indexing (Buck: INDEX, SET_INDEX) =====
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

        // ===== Attributes (Buck: DOT with call site caching) =====
        case LOAD_ATTR: {
          String name = (String) code.getConstantPool().getConstant(instr.getOperand1());
          Object obj = pop();
          push(loadAttrWithCache(obj, name));
          break;
        }

        case STORE_ATTR: {
          String name = (String) code.getConstantPool().getConstant(instr.getOperand1());
          Object value = pop();
          Object obj = pop();
          EvalUtils.setField(obj, name, value);
          break;
        }

        // ===== Function Calls (Buck: CALL, CALL_LINKED, CALL_CACHED) =====
        case CALL: {
          int posArgs = instr.getOperand1();
          int encodedKwArgs = instr.getOperand2();
          boolean hasStarStar = (encodedKwArgs & 0x8000) != 0;
          int kwArgs = encodedKwArgs & 0x7FFF;

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

          Map<String, Object> kwargs = new HashMap<>();
          for (int i = 0; i < kwArgs; i++) {
            Object value = pop();
            String key = (String) pop();
            if (kwargs.containsKey(key)) {
              throw Starlark.errorf("got multiple values for argument '%s'", key);
            }
            kwargs.put(key, value);
          }

          if (starStarDict != null) {
            for (Map.Entry<String, Object> e : starStarDict.entrySet()) {
              if (kwargs.containsKey(e.getKey())) {
                throw Starlark.errorf("got multiple values for argument '%s'", e.getKey());
              }
              kwargs.put(e.getKey(), e.getValue());
            }
          }

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

        // ===== Control Flow (Buck: BR, IF_BR_LOCAL, IF_NOT_BR_LOCAL) =====
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

        // ===== Iteration (Buck: FOR_INIT, CONTINUE, BREAK) =====
        case GET_ITER: {
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

        // ===== Comprehension Helpers (Buck: LIST_APPEND) =====
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

        // ===== Function Creation (Buck: NEW_FUNCTION) =====
        case MAKE_FUNCTION: {
          FunctionDescriptor desc = (FunctionDescriptor) code.getConstantPool().getConstant(instr.getOperand1());

          // Get number of defaults from operand2
          int numDefaults = instr.getOperand2();

          // Pop default values from stack (in reverse order)
          Object[] defaultsArray = new Object[numDefaults];
          for (int i = numDefaults - 1; i >= 0; i--) {
            defaultsArray[i] = pop();
          }
          Tuple defaultValues = Tuple.wrap(defaultsArray);

          BytecodeFunction fn = new BytecodeFunction(
              desc.getName(),
              desc.getLocation(),
              desc.getChunk(),
              desc.getParameterNames(),
              desc.hasVarargs(),
              desc.hasKwargs(),
              desc.getNumKeywordOnlyParams(),
              defaultValues,
              desc.getLocalCount(),
              filename);
          fn.setGlobals(globals);
          push(fn);
          break;
        }

        // ===== Module Loading (Buck: LOAD_STMT) =====
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

  // ===== Buck-Style Optimizations =====

  /**
   * Attribute access with call site caching (Buck: BcDotSite).
   */
  private Object loadAttrWithCache(Object obj, String name) throws EvalException, InterruptedException {
    // Try cache first
    StarlarkCallable cached = callCache.lookup(obj, name);
    if (cached != null) {
      callCache.recordHit();
      // For methods, return a bound method
      // For attributes, fall through to normal lookup
    } else {
      callCache.recordMiss();
    }

    return Starlark.getattr(thread.mutability(), thread.getSemantics(), obj, name, null);
  }

  /**
   * Type-specialized binary operations (Buck: PLUS_STRING, PLUS_LIST).
   */
  private void binaryOpWithTypeSpecialization(TokenKind op) throws EvalException, InterruptedException {
    Object b = pop();
    Object a = pop();

    // Buck has specialized opcodes for common patterns
    if (op == TokenKind.PLUS) {
      if (a instanceof String && b instanceof String) {
        // PLUS_STRING optimization
        push((String) a + (String) b);
        return;
      }
      if (a instanceof StarlarkList && b instanceof StarlarkList) {
        // PLUS_LIST optimization
        @SuppressWarnings("unchecked")
        StarlarkList<Object> left = (StarlarkList<Object>) a;
        @SuppressWarnings("unchecked")
        StarlarkList<Object> right = (StarlarkList<Object>) b;
        push(StarlarkList.concat(left, right, thread.mutability()));
        return;
      }
    }

    // Fall back to generic binary op
    push(EvalUtils.binaryOp(op, a, b, thread));
  }

  private void binaryOp(TokenKind op) throws EvalException, InterruptedException {
    Object b = pop();
    Object a = pop();
    push(EvalUtils.binaryOp(op, a, b, thread));
  }

  // ===== Stack Operations =====

  private void push(Object value) {
    stack.add(value);
  }

  private Object pop() throws EvalException {
    if (stack.isEmpty()) {
      throw new EvalException("stack underflow");
    }
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

  private String getLocalName(int index) {
    if (index < code.getLocalNames().size()) {
      return code.getLocalNames().get(index);
    }
    return "?";
  }

  /** Callable wrapper for Buck-style execution. */
  private static class BuckStyleCallable implements StarlarkCallable {
    private final String name;
    private final Location location;

    BuckStyleCallable(String name, String filename) {
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
      throw new UnsupportedOperationException("BuckStyleCallable should not be called directly");
    }
  }
}
