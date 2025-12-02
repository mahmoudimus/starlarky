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
 * A starlark-rust style bytecode interpreter using slot-based execution.
 *
 * <p>This interpreter follows the starlark-rust execution model:
 * <ul>
 *   <li>Slot-based memory model: unified array for locals AND stack
 *   <li>Fixed frame size computed at compile time
 *   <li>Slots indexed by position: [locals...][stack...]
 *   <li>Type-safe instruction dispatch via handler pattern
 *   <li>Optimized for cache-friendly sequential memory access
 * </ul>
 *
 * <p>Memory Layout (starlark-rust style):
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │ Local Variables (0..localCount-1) │ Stack (localCount...) │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>Key differences from starlark-go style:
 * <ul>
 *   <li>Locals and stack share the same contiguous slot array
 *   <li>Stack pointer (sp) is an index into the slot array
 *   <li>Better cache locality for hot loops
 *   <li>Fixed maximum stack depth (fails if exceeded)
 * </ul>
 *
 * @see StarlarkGoInterpreter for the stack-based alternative
 */
public final class StarlarkRustInterpreter {

  /** Maximum stack depth to prevent runaway recursion. */
  private static final int MAX_STACK_SIZE = 1024;

  /** Slot index type - mimics BcSlot from starlark-rust. */
  private static final class Slot {
    final int index;
    Slot(int index) { this.index = index; }
  }

  /** Slot range - mimics BcSlotRange from starlark-rust. */
  private static final class SlotRange {
    final int start;
    final int end;
    SlotRange(int start, int end) {
      this.start = start;
      this.end = end;
    }
    int len() { return end - start; }
  }

  // Frame state - mirrors starlark-rust's BcFrame
  private final BytecodeChunk code;
  private final StarlarkThread thread;
  private final Object[] slots;            // Unified: [locals | stack]
  private final int localCount;
  private int sp;                          // Stack pointer (index into slots)
  private final Map<String, Object> globals;
  private final String filename;
  private int pc;                          // Program counter
  private final Map<Iterator<?>, Object> iteratorToIterable;

  private StarlarkRustInterpreter(
      BytecodeChunk code,
      StarlarkThread thread,
      Map<String, Object> globals,
      String filename) {
    this.code = code;
    this.thread = thread;
    this.localCount = code.getLocalCount();
    // Allocate slots: locals + max stack
    this.slots = new Object[localCount + MAX_STACK_SIZE];
    this.sp = localCount; // Stack starts after locals
    this.globals = globals != null ? globals : new HashMap<>();
    this.filename = filename != null ? filename : "<starlark-rust>";
    this.pc = 0;
    this.iteratorToIterable = new HashMap<>();

    // Initialize locals to null (like starlark-rust's None)
    for (int i = 0; i < localCount; i++) {
      slots[i] = null;
    }
  }

  /**
   * Executes bytecode using the starlark-rust slot-based model.
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

    StarlarkCallable callable = new RustStyleCallable(code.getName(), filename);
    thread.push(callable);
    try {
      StarlarkRustInterpreter interp = new StarlarkRustInterpreter(code, thread, globals, filename);
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

    StarlarkRustInterpreter interp = new StarlarkRustInterpreter(code, thread, globals, filename);

    // Initialize parameters in local slots (0..paramCount-1)
    int paramCount = Math.min(args.length, code.getParameterCount());
    System.arraycopy(args, 0, interp.slots, 0, paramCount);

    return interp.run();
  }

  private Location currentLocation() {
    int line = code.getLineNumber(pc);
    int col = code.getColumnNumber(pc);
    return Location.fromFileLineColumn(filename, Math.max(0, line), col);
  }

  // ===== Slot Operations (starlark-rust style) =====

  /** Get value from slot (BcSlotIn) */
  private Object getSlot(int index) throws EvalException {
    if (index < 0 || index >= sp) {
      throw new EvalException("invalid slot read: " + index);
    }
    return slots[index];
  }

  /** Set value in slot (BcSlotOut) */
  private void setSlot(int index, Object value) throws EvalException {
    if (index < 0 || index >= slots.length) {
      throw new EvalException("invalid slot write: " + index);
    }
    slots[index] = value;
  }

  /** Push value onto stack (increments sp) */
  private void push(Object value) throws EvalException {
    if (sp >= slots.length) {
      throw new EvalException("stack overflow");
    }
    slots[sp++] = value;
  }

  /** Pop value from stack (decrements sp) */
  private Object pop() throws EvalException {
    if (sp <= localCount) {
      throw new EvalException("stack underflow");
    }
    return slots[--sp];
  }

  /** Peek at top of stack */
  private Object peek() throws EvalException {
    if (sp <= localCount) {
      throw new EvalException("stack underflow");
    }
    return slots[sp - 1];
  }

  /** Get value at stack offset (1 = top, 2 = second, etc.) */
  private Object stackGet(int offset) throws EvalException {
    int index = sp - offset;
    if (index < localCount) {
      throw new EvalException("stack access out of range");
    }
    return slots[index];
  }

  /**
   * Main execution loop - starlark-rust style with slot-based operations.
   *
   * <p>This uses the slot-based model where locals and stack share the same
   * contiguous array. The stack pointer (sp) points to the next free slot.
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

      // Dispatch via handler pattern (like starlark-rust's BcOpcodeHandler)
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

        // ===== Local Variables (slot-based: LoadLocal, StoreLocal) =====
        case LOAD_LOCAL: {
          int slotIndex = instr.getOperand1();
          Object value = slots[slotIndex]; // Direct slot access
          if (value == null) {
            String name = getLocalName(slotIndex);
            throw Starlark.errorf("local variable '%s' referenced before assignment", name);
          }
          push(value);
          break;
        }

        case STORE_LOCAL: {
          int slotIndex = instr.getOperand1();
          slots[slotIndex] = pop(); // Direct slot write
          break;
        }

        // ===== Globals (starlark-rust: LoadModule, StoreModule) =====
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

        // ===== Free Variables =====
        case LOAD_FREE:
          throw new UnsupportedOperationException("LOAD_FREE not implemented in slot-based interpreter");

        case STORE_FREE:
          throw new UnsupportedOperationException("STORE_FREE not implemented in slot-based interpreter");

        // ===== Binary Operations (starlark-rust: Add, Sub, etc.) =====
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

        // ===== Comparisons =====
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

        // ===== Unary =====
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

        // ===== Collections (starlark-rust: ListNew, TupleNPop, DictNew) =====
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

        // ===== Indexing (starlark-rust: ArrayIndex, SetArrayIndex) =====
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

        // ===== Attributes =====
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

        // ===== Function Calls =====
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

        // ===== Control Flow (starlark-rust: Br, IfBr, IfNotBr) =====
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

        // ===== Iteration =====
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

  /** Callable wrapper for starlark-rust style execution. */
  private static class RustStyleCallable implements StarlarkCallable {
    private final String name;
    private final Location location;

    RustStyleCallable(String name, String filename) {
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
      throw new UnsupportedOperationException("RustStyleCallable should not be called directly");
    }
  }
}
