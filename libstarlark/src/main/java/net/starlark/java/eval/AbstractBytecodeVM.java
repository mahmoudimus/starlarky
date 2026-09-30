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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.starlark.java.eval.compiler.BytecodeChunk;
import net.starlark.java.eval.compiler.FunctionDescriptor;
import net.starlark.java.eval.compiler.Instruction;
import net.starlark.java.eval.compiler.Opcode;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.TokenKind;

/**
 * The semantics of every bytecode instruction, shared by all bytecode VMs.
 *
 * <p>Subclasses decide only how the operand stack and local variables are stored ({@link #push},
 * {@link #pop}, {@link #getLocal}, ...) and may observe or specialize a few operations through
 * hooks ({@link #binaryOp}, {@link #getAttr}, {@link #onInstruction}, ...). Because the opcode
 * semantics live here, every VM behaves the same way as the tree-walker.
 */
abstract class AbstractBytecodeVM {

  /**
   * Wrapper for a Module with its name, used during load statement execution to provide better
   * error messages.
   */
  private static final class ModuleWithName {
    final Module module;
    final String moduleName;

    ModuleWithName(Module module, String moduleName) {
      this.module = module;
      this.moduleName = moduleName;
    }
  }

  protected final BytecodeChunk chunk;
  protected final StarlarkThread thread;
  protected final Map<String, Object> globals;
  protected final String filename;
  private final Tuple freevars; // cells captured from enclosing functions
  private final Map<Iterator<?>, Object> iteratorToIterable; // iteration locks to release
  protected int ip; // instruction pointer

  protected AbstractBytecodeVM(
      BytecodeChunk chunk,
      StarlarkThread thread,
      Map<String, Object> globals,
      String filename,
      Tuple freevars) {
    this.chunk = chunk;
    this.thread = thread;
    this.globals = globals != null ? globals : new HashMap<>();
    this.filename = filename != null ? filename : "<bytecode>";
    this.freevars = freevars != null ? freevars : Tuple.empty();
    this.iteratorToIterable = new HashMap<>();
    this.ip = 0;
  }

  // ---- storage, provided by each VM ----

  protected abstract void push(Object value) throws EvalException;

  protected abstract Object pop() throws EvalException;

  protected abstract Object peek() throws EvalException;

  /** Returns the value at stack[-offset] (1 = top of stack). */
  protected abstract Object stackGet(int offset) throws EvalException;

  protected abstract int stackSize();

  protected abstract Object getLocal(int index);

  protected abstract void setLocal(int index, Object value);

  // ---- hooks ----

  /** Applies a binary operator. VMs may specialize common cases but must keep the semantics. */
  protected Object binaryOp(TokenKind op, Object x, Object y) throws EvalException {
    return EvalUtils.binaryOp(op, x, y, thread);
  }

  /** Reads an attribute. VMs may cache lookups but must keep the semantics. */
  protected Object getAttr(Object object, String name) throws EvalException, InterruptedException {
    return Starlark.getattr(
        thread.mutability(), thread.getSemantics(), object, name, /*defaultValue=*/ null);
  }

  protected void onInstruction(Opcode opcode) {}

  protected void onCall() {}

  protected void onIteration() {}

  // ---- entry points ----

  /** Runs a file's top-level code in a new {@link BytecodeToplevel} frame. */
  static Object runToplevel(AbstractBytecodeVM vm) throws EvalException, InterruptedException {
    StarlarkThread thread = vm.thread;
    thread.push(new BytecodeToplevel(vm.chunk.getName(), vm.filename, vm.globals));
    try {
      return vm.run();
    } catch (EvalException ex) {
      throw ex.ensureStack(thread);
    } catch (RuntimeException ex) {
      throw Starlark.uncheckedEval(ex, thread);
    } catch (Error ex) {
      throw Starlark.uncheckedEval(ex, thread);
    } finally {
      thread.pop();
    }
  }

  /** Runs a function body whose frame is already pushed, starting from the given locals. */
  final Object runWithLocals(Object[] locals) throws EvalException, InterruptedException {
    int n = Math.min(locals.length, chunk.getLocalCount());
    for (int i = 0; i < n; i++) {
      setLocal(i, locals[i]);
    }
    return run();
  }

  // ---- execution ----

  /** Creates a Location object for the current instruction. */
  protected final Location currentLocation() {
    int lineNum = Math.max(0, chunk.getLineNumber(ip));
    return Location.fromFileLineColumn(filename, lineNum, chunk.getColumnNumber(ip));
  }

  /** Records the current instruction as the error location of the current frame. */
  private EvalException withLocation(EvalException ex) {
    if (!thread.getCallStack().isEmpty()) {
      thread.frame(0).setErrorLocation(currentLocation());
    }
    return ex.ensureStack(thread);
  }

  final Object run() throws EvalException, InterruptedException {
    List<Instruction> instructions = chunk.getInstructions();
    boolean debug = Boolean.getBoolean("debug.bytecode");

    if (debug) {
      System.out.println("=== BYTECODE CHUNK (" + instructions.size() + " instructions) ===");
      for (int i = 0; i < instructions.size(); i++) {
        Instruction instr = instructions.get(i);
        System.out.printf("[%3d] %-20s", i, instr.getOpcode());
        if (instr.getOpcode().getOperandCount() >= 1) {
          System.out.printf(" %d", instr.getOperand1());
        }
        if (instr.getOpcode().getOperandCount() >= 2) {
          System.out.printf(" %d", instr.getOperand2());
        }
        System.out.println();
      }
      System.out.println("=== EXECUTION ===");
    }

    try {
      while (ip < instructions.size()) {
        thread.checkInterrupt();
        if (++thread.steps >= thread.stepLimit) {
          throw new EvalException("Starlark computation cancelled: too many steps");
        }
        if (thread.isExpired()) {
          throw new EvalException("Starlark computation cancelled: past expiration date");
        }

        Instruction instr = instructions.get(ip);
        Opcode opcode = instr.getOpcode();
        onInstruction(opcode);

        if (debug) {
          System.out.printf("[%3d] %-20s  stack=%d", ip, opcode, stackSize());
          if (opcode.getOperandCount() >= 1) {
            System.out.printf(" op1=%d", instr.getOperand1());
          }
          if (opcode.getOperandCount() >= 2) {
            System.out.printf(" op2=%d", instr.getOperand2());
          }
          System.out.println();
        }

        // Execute instruction
        switch (opcode) {
          // Stack manipulation
          case POP:
            pop();
            break;

          case DUP:
            push(peek());
            break;

          case SWAP:
            {
              Object a = pop();
              Object b = pop();
              push(a);
              push(b);
            }
            break;

          case DUP_TOP_TWO:
            {
              Object b = pop();
              Object a = pop();
              push(a);
              push(b);
              push(a);
              push(b);
            }
            break;

          case ROT_THREE:
            {
              Object c = pop();
              Object b = pop();
              Object a = pop();
              push(c);
              push(a);
              push(b);
            }
            break;

          // Constants
          case LOAD_CONST:
            push(chunk.getConstantPool().getConstant(instr.getOperand1()));
            break;

          case LOAD_BYTES:
            push(StarlarkBytes.wrap(
                thread.mutability(), (byte[]) chunk.getConstantPool().getConstant(instr.getOperand1())));
            break;

          case LOAD_NONE:
            push(Starlark.NONE);
            break;

          case LOAD_TRUE:
            push(true);
            break;

          case LOAD_FALSE:
            push(false);
            break;

          // Variables
          case LOAD_LOCAL:
            {
              int index = instr.getOperand1();
              Object value = getLocal(index);
              if (value == null) {
                // Get the variable name from the chunk's local names
                String varName = "?";
                if (index < chunk.getLocalNames().size()) {
                  varName = chunk.getLocalNames().get(index);
                }
                throw Starlark.errorf("local variable '%s' is referenced before assignment.", varName);
              }
              push(value);
            }
            break;

          case STORE_LOCAL:
            {
              int index = instr.getOperand1();
              setLocal(index, pop());
            }
            break;

          case LOAD_GLOBAL:
            {
              String name = (String) chunk.getConstantPool().getConstant(instr.getOperand1());
              Object value = globals.get(name);
              if (value == null) {
                throw Starlark.errorf(
                    "global variable '%s' is referenced before assignment.", name);
              }
              push(value);
            }
            break;

          case LOAD_BUILTIN:
            push(BytecodeGlobals.lookupBuiltin(
                globals, (String) chunk.getConstantPool().getConstant(instr.getOperand1())));
            break;

          case STORE_GLOBAL:
            {
              String name = (String) chunk.getConstantPool().getConstant(instr.getOperand1());
              globals.put(name, pop());
            }
            break;

          case LOAD_FREE:
            {
              // LOAD_FREE loads a value from a captured free variable (closure cell)
              int index = instr.getOperand1();
              BytecodeFunction.Cell cell = (BytecodeFunction.Cell) freevars.get(index);
              push(cell.x);
            }
            break;

          case STORE_FREE:
            {
              // STORE_FREE stores a value into a captured free variable (closure cell)
              int index = instr.getOperand1();
              BytecodeFunction.Cell cell = (BytecodeFunction.Cell) freevars.get(index);
              cell.x = pop();
            }
            break;

          case LOAD_CELL:
            {
              // LOAD_CELL loads a value from a cell in locals (for CELL scope variables)
              int index = instr.getOperand1();
              BytecodeFunction.Cell cell = (BytecodeFunction.Cell) getLocal(index);
              push(cell.x);
            }
            break;

          case STORE_CELL:
            {
              // STORE_CELL stores a value into a cell in locals (for CELL scope variables)
              int index = instr.getOperand1();
              BytecodeFunction.Cell cell = (BytecodeFunction.Cell) getLocal(index);
              cell.x = pop();
            }
            break;

          // Arithmetic
          case ADD:
            binary(TokenKind.PLUS);
            break;

          case SUBTRACT:
            binary(TokenKind.MINUS);
            break;

          case MULTIPLY:
            binary(TokenKind.STAR);
            break;

          case DIVIDE:
            binary(TokenKind.SLASH);
            break;

          case FLOOR_DIV:
            binary(TokenKind.SLASH_SLASH);
            break;

          case MODULO:
            binary(TokenKind.PERCENT);
            break;

          case BIT_AND:
            binary(TokenKind.AMPERSAND);
            break;

          case BIT_OR:
            binary(TokenKind.PIPE);
            break;

          case BIT_XOR:
            binary(TokenKind.CARET);
            break;

          case LEFT_SHIFT:
            binary(TokenKind.LESS_LESS);
            break;

          case RIGHT_SHIFT:
            binary(TokenKind.GREATER_GREATER);
            break;

          case INPLACE_OP:
            {
              TokenKind op = TokenKind.values()[instr.getOperand1()];
              Object y = pop();
              Object x = pop();
              push(Eval.inplaceBinaryOp(thread, op, x, y));
            }
            break;

          case BIT_NOT:
            push(EvalUtils.unaryOp(TokenKind.TILDE, pop()));
            break;

          case NEGATE:
            push(EvalUtils.unaryOp(TokenKind.MINUS, pop()));
            break;

          case POSITIVE:
            push(EvalUtils.unaryOp(TokenKind.PLUS, pop()));
            break;

          // Comparison
          case EQUAL:
            binary(TokenKind.EQUALS_EQUALS);
            break;

          case NOT_EQUAL:
            binary(TokenKind.NOT_EQUALS);
            break;

          case LESS:
            binary(TokenKind.LESS);
            break;

          case LESS_EQUAL:
            binary(TokenKind.LESS_EQUALS);
            break;

          case GREATER:
            binary(TokenKind.GREATER);
            break;

          case GREATER_EQUAL:
            binary(TokenKind.GREATER_EQUALS);
            break;

          case IN:
            binary(TokenKind.IN);
            break;

          case NOT_IN:
            binary(TokenKind.NOT_IN);
            break;

          // Logical
          case AND:
            {
              Object b = pop();
              Object a = pop();
              push(Starlark.truth(a) && Starlark.truth(b));
            }
            break;

          case OR:
            {
              Object b = pop();
              Object a = pop();
              push(Starlark.truth(a) || Starlark.truth(b));
            }
            break;

          case NOT:
            push(!Starlark.truth(pop()));
            break;

          // Collections
          case BUILD_LIST:
            {
              int count = instr.getOperand1();
              List<Object> elements = new ArrayList<>(count);
              for (int i = 0; i < count; i++) {
                elements.add(0, pop()); // Reverse order
              }
              push(StarlarkList.copyOf(thread.mutability(), elements));
            }
            break;

          case BUILD_TUPLE:
            {
              int count = instr.getOperand1();
              Object[] elements = new Object[count];
              for (int i = count - 1; i >= 0; i--) {
                elements[i] = pop();
              }
              push(Tuple.of(elements));
            }
            break;

          case BUILD_DICT:
            {
              int count = instr.getOperand1();
              // Pop all key-value pairs from stack (they're in reverse order)
              Object[] pairs = new Object[count * 2];
              for (int i = count - 1; i >= 0; i--) {
                pairs[i * 2 + 1] = pop(); // value
                pairs[i * 2] = pop();     // key
              }

              // Build dict in correct order, checking for duplicates
              Dict<Object, Object> dict = Dict.of(thread.mutability());
              for (int i = 0; i < count; i++) {
                Object key = pairs[i * 2];
                Object value = pairs[i * 2 + 1];
                int before = dict.size();
                dict.putEntry(key, value);
                if (dict.size() == before) {
                  throw Starlark.errorf(
                      "dictionary expression has duplicate key: %s", Starlark.repr(key));
                }
              }
              push(dict);
            }
            break;

          case UNPACK_SEQUENCE:
            for (Object elem : unpackSequence(pop(), instr.getOperand1())) {
              push(elem); // leftmost first, so the rightmost is on top of the stack
            }
            break;

          // Indexing
          case INDEX:
            {
              Object key = pop();
              Object object = pop();
              push(EvalUtils.index(thread, object, key));
            }
            break;

          case STORE_INDEX:
            {
              // Stack order: [value, object, key] (value pushed first by RHS, then object and key by LHS)
              Object key = pop();
              Object object = pop();
              Object value = pop();
              EvalUtils.setIndex(thread, object, key, value);
            }
            break;

          case SLICE:
            {
              Object step = pop();
              Object stop = pop();
              Object start = pop();
              Object object = pop();
              push(Starlark.slice(thread.mutability(), object, start, stop, step));
            }
            break;

          // Attributes
          case LOAD_ATTR:
            {
              String name = (String) chunk.getConstantPool().getConstant(instr.getOperand1());
              Object object = pop();

              // Special handling for ModuleWithName (used in load statements)
              if (object instanceof ModuleWithName) {
                ModuleWithName mwn = (ModuleWithName) object;
                Object value = mwn.module.getGlobal(name);
                if (value == null) {
                  throw Starlark.errorf(
                      "file '%s' does not contain symbol '%s'", mwn.moduleName, name);
                }
                push(value);
              } else {
                push(getAttr(object, name));
              }
            }
            break;

          case STORE_ATTR:
            {
              // Stack layout: [value (TOS1), object (TOS)]
              // Compiler pushes: RHS value first, then LHS object
              String name = (String) chunk.getConstantPool().getConstant(instr.getOperand1());
              Object object = pop();  // TOS: the object to set field on
              Object value = pop();   // TOS1: the value to store
              EvalUtils.setField(object, name, value);
            }
            break;

          // Function calls
          case CALL:
            {
              // Stack: [function, pos1..posN, name1, value1, ..., nameK, valueK, (starstar)?]
              // Arguments are passed to Starlark.fastcall in source order, as the tree-walker
              // does, so that duplicate/unexpected keyword errors come from the callee.
              int posArgs = instr.getOperand1();
              int encodedKwArgs = instr.getOperand2();
              boolean hasStarStar = (encodedKwArgs & 0x8000) != 0;
              int kwArgs = encodedKwArgs & 0x7FFF;

              Object starStar = hasStarStar ? pop() : null;
              Object[] named = new Object[2 * kwArgs];
              for (int i = named.length - 1; i >= 0; i--) {
                named[i] = pop();
              }
              if (hasStarStar) {
                named = appendStarStar(named, starStar);
              }
              Object[] positional = new Object[posArgs];
              for (int i = posArgs - 1; i >= 0; i--) {
                positional[i] = pop();
              }
              Object function = pop();

              onCall();
              thread.frame(0).setLocation(currentLocation());
              push(Starlark.fastcall(thread, function, positional, named));
            }
            break;

          case CALL_EX:
            {
              // Stack: [func, pos_list, kw_dict, star_arg?, starstar_arg?]
              int flags = instr.getOperand1();
              Object starStarArg = (flags & 2) != 0 ? pop() : null;
              Object starArg = (flags & 1) != 0 ? pop() : null;
              Object kwDictObj = pop();
              Object posListObj = pop();
              Object function = pop();

              ArrayList<Object> positional = new ArrayList<>();
              Iterables.addAll(positional, (Iterable<?>) posListObj);
              if (starArg != null) {
                if (!(starArg instanceof StarlarkIterable)) {
                  throw Starlark.errorf(
                      "argument after * must be an iterable, not %s", Starlark.type(starArg));
                }
                Iterables.addAll(positional, (Iterable<?>) starArg);
              }

              Dict<?, ?> kwDict = (Dict<?, ?>) kwDictObj;
              Object[] named = new Object[2 * kwDict.size()];
              int j = 0;
              for (Map.Entry<?, ?> e : kwDict.entrySet()) {
                named[j++] = e.getKey();
                named[j++] = e.getValue();
              }
              if (starStarArg != null) {
                named = appendStarStar(named, starStarArg);
              }

              onCall();
              thread.frame(0).setLocation(currentLocation());
              push(Starlark.fastcall(thread, function, positional.toArray(), named));
            }
            break;

          case RETURN:
            return pop();

          // Control flow
          case JUMP:
            ip = instr.getOperand1() - 1; // -1 because we increment at end of loop
            break;

          case JUMP_IF_TRUE:
            if (Starlark.truth(peek())) {
              ip = instr.getOperand1() - 1;
            }
            break;

          case JUMP_IF_FALSE:
            if (!Starlark.truth(peek())) {
              ip = instr.getOperand1() - 1;
            }
            break;

          case POP_JUMP_IF_TRUE:
            if (Starlark.truth(pop())) {
              ip = instr.getOperand1() - 1;
            }
            break;

          case POP_JUMP_IF_FALSE:
            if (!Starlark.truth(pop())) {
              ip = instr.getOperand1() - 1;
            }
            break;

          // Iteration
          case GET_ITER:
            {
              Object iterable = pop();

              // Check if strings are forbidden in this context (comprehensions)
              if (iterable instanceof String) {
                throw withLocation(new EvalException("type 'string' is not iterable"));
              }

              // toIterable() will throw an error if the object is not iterable
              Iterable<?> starlarkIterable;
              try {
                starlarkIterable = Starlark.toIterable(iterable);
              } catch (EvalException e) {
                throw withLocation(e);
              }
              Iterator<?> iterator = starlarkIterable.iterator();
              // Track mutations on the iterable during iteration
              EvalUtils.addIterator(iterable);
              iteratorToIterable.put(iterator, iterable); // Store mapping for cleanup later
              push(iterator);
            }
            break;

          case FOR_ITER:
            {
              @SuppressWarnings("unchecked")
              Iterator<Object> iterator = (Iterator<Object>) peek();
              if (!iterator.hasNext()) {
                // Iterator exhausted, jump to end of loop
                // Note: Don't pop or cleanup here - END_FOR will handle it
                ip = instr.getOperand1() - 1;
              } else {
                onIteration();
                push(iterator.next());
              }
            }
            break;

          case END_FOR:
            {
              // Pop the iterator and remove the iteration lock
              @SuppressWarnings("unchecked")
              Iterator<Object> iterator = (Iterator<Object>) pop();
              Object iterable = iteratorToIterable.remove(iterator);
              if (iterable != null) {
                EvalUtils.removeIterator(iterable);
              }
            }
            break;

          case LIST_APPEND:
            {
              // LIST_APPEND(i) pops value from top of stack and appends it to list at stack[-i]
              // The offset is relative to stack BEFORE the pop
              int offset = instr.getOperand1();
              @SuppressWarnings("unchecked")
              StarlarkList<Object> list = (StarlarkList<Object>) stackGet(offset);
              Object value = pop();
              list.addElement(value);
            }
            break;

          case DICT_ADD:
            {
              // DICT_ADD(i) pops value and key from stack, adds to dict at stack[-i]
              // The offset is relative to stack BEFORE the pops
              int offset = instr.getOperand1();
              @SuppressWarnings("unchecked")
              Dict<Object, Object> dict = (Dict<Object, Object>) stackGet(offset);
              Object value = pop();
              Object key = pop();
              dict.putEntry(key, value);
            }
            break;

          case NOP:
            // No operation
            break;

          case MAKE_FUNCTION:
            {
              // Get function descriptor from constant pool
              FunctionDescriptor descriptor =
                  (FunctionDescriptor) chunk.getConstantPool().getConstant(instr.getOperand1());

              // Get number of defaults from operand2
              int numDefaults = instr.getOperand2();

              // Pop default values from stack (in reverse order - last default is on top)
              Object[] defaultsArray = new Object[numDefaults];
              for (int i = numDefaults - 1; i >= 0; i--) {
                defaultsArray[i] = pop();
              }
              Tuple defaultValues = Tuple.wrap(defaultsArray);

              // Create BytecodeFunction with full signature info
              BytecodeFunction function =
                  new BytecodeFunction(
                      descriptor.getName(),
                      descriptor.getLocation(),
                      descriptor.getChunk(),
                      descriptor.getParameterNames(),
                      descriptor.hasVarargs(),
                      descriptor.hasKwargs(),
                      descriptor.getNumKeywordOnlyParams(),
                      defaultValues,
                      descriptor.getLocalCount(),
                      filename);

              // Set globals so the function can access them when called
              function.setGlobals(globals);

              // Set cell indices so the function knows which locals to wrap in Cells
              function.setCellIndices(descriptor.getCellIndices());

              // Capture free variables for closures
              ImmutableList<FunctionDescriptor.FreevarInfo> freevarInfos = descriptor.getFreevarInfos();
              if (!freevarInfos.isEmpty()) {
                Object[] capturedCells = new Object[freevarInfos.size()];
                for (int i = 0; i < freevarInfos.size(); i++) {
                  FunctionDescriptor.FreevarInfo info = freevarInfos.get(i);
                  if (info.isFromEnclosingFreevars) {
                    // Get from enclosing function's freevars
                    capturedCells[i] = freevars.get(info.index);
                  } else {
                    // Get from current locals (it should be a Cell)
                    Object local = getLocal(info.index);
                    if (local instanceof BytecodeFunction.Cell) {
                      capturedCells[i] = local;
                    } else {
                      // Create a new cell wrapping the value
                      capturedCells[i] = new BytecodeFunction.Cell(local);
                      // Also update locals so subsequent access sees the cell
                      setLocal(info.index, capturedCells[i]);
                    }
                  }
                }
                function.setFreevars(Tuple.wrap(capturedCells));
              }

              push(function);
            }
            break;

          case LOAD_MODULE:
            {
              // Get module name from constant pool
              String moduleName = (String) chunk.getConstantPool().getConstant(instr.getOperand1());

              // Get the loader from the thread
              StarlarkThread.Loader loader = thread.getLoader();
              if (loader == null) {
                throw Starlark.errorf("load statements may not be executed in this thread");
              }

              // Load the module
              Module module = loader.load(moduleName);
              if (module == null) {
                throw Starlark.errorf("module '%s' not found", moduleName);
              }

              // Push the module with its name onto the stack (for better error messages)
              push(new ModuleWithName(module, moduleName));
            }
            break;

          default:
            throw new UnsupportedOperationException("Unsupported opcode: " + opcode);
        }

        ip++;
      }

      // If we reach here without returning, return None
      return Starlark.NONE;

    } catch (EvalException ex) {
      throw withLocation(ex);
    } finally {
      // Release iteration locks of loops left by return or by an exception, as the
      // tree-walker's finally blocks do. END_FOR has already released loops that completed.
      for (Object iterable : iteratorToIterable.values()) {
        EvalUtils.removeIterator(iterable);
      }
      iteratorToIterable.clear();
    }
    // Other exceptions propagate unchanged, as in the tree-walker: Starlark.fastcall already
    // reports failures inside callees as UncheckedEvalException.
  }

  private void binary(TokenKind op) throws EvalException {
    Object y = pop();
    Object x = pop();
    push(binaryOp(op, x, y));
  }

  /** Checks {@code x} for an n-element sequence assignment, as Eval.assignSequence does. */
  static Iterable<?> unpackSequence(Object x, int count) throws EvalException {
    int n = Starlark.len(x);
    if (n < 0 || x instanceof String) { // strings are not iterable
      throw Starlark.errorf(
          "got '%s' in sequence assignment (want %d-element sequence)", Starlark.type(x), count);
    }
    if (n != count) {
      throw Starlark.errorf(
          "too %s values to unpack (got %d, want %d)", n < count ? "few" : "many", n, count);
    }
    return Starlark.toIterable(x);
  }

  /** Appends the entries of a {@code **kwargs} argument to {@code named}, as Eval.evalCall does. */
  private static Object[] appendStarStar(Object[] named, Object value) throws EvalException {
    if (!(value instanceof Dict)) {
      throw Starlark.errorf("argument after ** must be a dict, not %s", Starlark.type(value));
    }
    Dict<?, ?> kwargs = (Dict<?, ?>) value;
    int j = named.length;
    named = Arrays.copyOf(named, j + 2 * kwargs.size());
    for (Map.Entry<?, ?> e : kwargs.entrySet()) {
      if (!(e.getKey() instanceof String)) {
        throw Starlark.errorf("keywords must be strings, not %s", Starlark.type(e.getKey()));
      }
      named[j++] = e.getKey();
      named[j++] = e.getValue();
    }
    return named;
  }
}
