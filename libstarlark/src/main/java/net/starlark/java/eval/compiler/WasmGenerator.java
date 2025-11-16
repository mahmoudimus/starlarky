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

package net.starlark.java.eval.compiler;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates WebAssembly code from Starlark bytecode.
 *
 * <p>This generator translates our stack-based bytecode into WebAssembly text format (WAT).
 * The generated code can be compiled to .wasm binary format using tools like wat2wasm.
 *
 * <p>Key design decisions:
 * - Dynamic values are represented as externref (opaque references to JS/host objects)
 * - Numeric operations use WebAssembly's native numeric types when possible
 * - Complex operations delegate to imported runtime functions
 * - The value stack is implemented using WebAssembly's built-in stack
 *
 * <p>Generated modules import runtime functions for:
 * - Object allocation and manipulation
 * - Type conversions
 * - Complex operations (dict/list creation, attribute access, etc.)
 */
public final class WasmGenerator {

  private final BytecodeChunk chunk;
  private final StringBuilder wat;
  private final Map<Integer, String> instructionLabels;
  private int labelCounter;

  private WasmGenerator(BytecodeChunk chunk) {
    this.chunk = chunk;
    this.wat = new StringBuilder();
    this.instructionLabels = new HashMap<>();
    this.labelCounter = 0;
  }

  /**
   * Generates WebAssembly text format (WAT) from a bytecode chunk.
   *
   * @param chunk the bytecode to translate
   * @return WAT source code as a string
   */
  public static String generate(BytecodeChunk chunk) {
    WasmGenerator generator = new WasmGenerator(chunk);
    return generator.generateModule();
  }

  private String generateModule() {
    emit("(module");
    indent();

    // Import runtime functions
    emitRuntimeImports();

    // Emit constant pool as global data
    emitConstantPool();

    // Emit local variables declaration
    emitLocals();

    // Emit the main function
    emitFunction();

    dedent();
    emit(")");

    return wat.toString();
  }

  private void emitRuntimeImports() {
    emit(";; Runtime function imports");

    // Import basic operations
    emit("(import \"starlark\" \"add\" (func $add (param externref externref) (result externref)))");
    emit(
        "(import \"starlark\" \"subtract\" (func $subtract (param externref externref) (result"
            + " externref)))");
    emit(
        "(import \"starlark\" \"multiply\" (func $multiply (param externref externref) (result"
            + " externref)))");
    emit(
        "(import \"starlark\" \"divide\" (func $divide (param externref externref) (result"
            + " externref)))");
    emit(
        "(import \"starlark\" \"modulo\" (func $modulo (param externref externref) (result"
            + " externref)))");
    emit("(import \"starlark\" \"negate\" (func $negate (param externref) (result externref)))");

    // Import comparison operations
    emit(
        "(import \"starlark\" \"equal\" (func $equal (param externref externref) (result"
            + " externref)))");
    emit(
        "(import \"starlark\" \"less\" (func $less (param externref externref) (result"
            + " externref)))");
    emit(
        "(import \"starlark\" \"compare\" (func $compare (param externref externref) (result"
            + " i32)))");

    // Import collection operations
    emit(
        "(import \"starlark\" \"build_list\" (func $build_list (param i32) (result"
            + " externref)))");
    emit(
        "(import \"starlark\" \"build_dict\" (func $build_dict (param i32) (result"
            + " externref)))");
    emit(
        "(import \"starlark\" \"build_tuple\" (func $build_tuple (param i32) (result"
            + " externref)))");

    // Import indexing and attribute access
    emit(
        "(import \"starlark\" \"index\" (func $index (param externref externref) (result"
            + " externref)))");
    emit(
        "(import \"starlark\" \"get_attr\" (func $get_attr (param externref externref) (result"
            + " externref)))");
    emit(
        "(import \"starlark\" \"set_attr\" (func $set_attr (param externref externref externref)"
            + " (result externref)))");

    // Import call operation
    emit(
        "(import \"starlark\" \"call\" (func $call (param externref i32 i32) (result"
            + " externref)))");

    // Import constant constructors
    emit("(import \"starlark\" \"const_none\" (func $const_none (result externref)))");
    emit("(import \"starlark\" \"const_true\" (func $const_true (result externref)))");
    emit("(import \"starlark\" \"const_false\" (func $const_false (result externref)))");
    emit("(import \"starlark\" \"const_int\" (func $const_int (param i64) (result externref)))");
    emit(
        "(import \"starlark\" \"const_float\" (func $const_float (param f64) (result"
            + " externref)))");
    emit(
        "(import \"starlark\" \"const_string\" (func $const_string (param i32) (result"
            + " externref)))");

    // Import truth testing
    emit("(import \"starlark\" \"truth\" (func $truth (param externref) (result i32)))");

    // Import iteration
    emit("(import \"starlark\" \"get_iter\" (func $get_iter (param externref) (result externref)))");
    emit("(import \"starlark\" \"iter_next\" (func $iter_next (param externref) (result externref)))");

    // Memory for constant string data
    emit("(import \"env\" \"memory\" (memory 1))");

    emit("");
  }

  private void emitConstantPool() {
    emit(";; Constant pool");

    List<Object> constants = chunk.getConstantPool().getConstants();
    if (constants.isEmpty()) {
      return;
    }

    // Emit constant pool as a table of externrefs
    emit("(table $constants " + constants.size() + " externref)");

    // We'll initialize constants at module load time using start function
    // For now, we'll access them via runtime calls

    emit("");
  }

  private void emitLocals() {
    emit(";; Local variables");
    int localCount = chunk.getLocalCount();
    if (localCount > 0) {
      // Locals will be declared within the function
    }
    emit("");
  }

  private void emitFunction() {
    emit(";; Main function: " + chunk.getName());
    emit("(func $" + safeName(chunk.getName()) + " (export \"" + chunk.getName() + "\")");
    indent();

    // Emit parameters
    for (int i = 0; i < chunk.getParameterCount(); i++) {
      emit("(param $param" + i + " externref)");
    }

    // Return type
    emit("(result externref)");

    // Emit local variables
    int localCount = chunk.getLocalCount();
    if (localCount > 0) {
      for (int i = chunk.getParameterCount(); i < localCount; i++) {
        emit("(local $local" + i + " externref)");
      }
    }

    emit("");

    // Pre-scan for jump targets to create labels
    prescanJumpTargets();

    // Emit instructions
    emit(";; Bytecode instructions");
    emitInstructions();

    dedent();
    emit(")");
  }

  private void prescanJumpTargets() {
    List<Instruction> instructions = chunk.getInstructions();
    for (Instruction instr : instructions) {
      if (instr.getOpcode().isJump()) {
        int target = instr.getOperand1();
        if (!instructionLabels.containsKey(target)) {
          instructionLabels.put(target, newLabel());
        }
      }
    }
  }

  private void emitInstructions() {
    List<Instruction> instructions = chunk.getInstructions();

    for (int i = 0; i < instructions.size(); i++) {
      Instruction instr = instructions.get(i);
      int offset = instr.getOffset();

      // Emit label if this is a jump target
      String label = instructionLabels.get(offset);
      if (label != null) {
        dedent();
        emit("(" + label);
        indent();
      }

      emitInstruction(instr, i);

      // Close label block if needed
      if (label != null) {
        dedent();
        emit(")");
        indent();
      }
    }
  }

  private void emitInstruction(Instruction instr, int index) {
    Opcode op = instr.getOpcode();
    int line = chunk.getLineNumber(index);
    emit(";; " + line + ": " + instr);

    switch (op) {
      case POP:
        emit("drop");
        break;

      case DUP:
        emit("local.tee $temp");
        emit("local.get $temp");
        break;

      case LOAD_CONST:
        {
          int constIndex = instr.getOperand1();
          Object constant = chunk.getConstantPool().getConstant(constIndex);
          emitConstant(constant);
        }
        break;

      case LOAD_NONE:
        emit("call $const_none");
        break;

      case LOAD_TRUE:
        emit("call $const_true");
        break;

      case LOAD_FALSE:
        emit("call $const_false");
        break;

      case LOAD_LOCAL:
        emit("local.get $local" + instr.getOperand1());
        break;

      case STORE_LOCAL:
        emit("local.set $local" + instr.getOperand1());
        break;

      case LOAD_GLOBAL:
        {
          String name = (String) chunk.getConstantPool().getConstant(instr.getOperand1());
          emit(";; TODO: Load global '" + name + "'");
          emit("call $const_none ;; placeholder");
        }
        break;

      case STORE_GLOBAL:
        {
          String name = (String) chunk.getConstantPool().getConstant(instr.getOperand1());
          emit(";; TODO: Store global '" + name + "'");
          emit("drop ;; placeholder");
        }
        break;

      case ADD:
        emit("call $add");
        break;

      case SUBTRACT:
        emit("call $subtract");
        break;

      case MULTIPLY:
        emit("call $multiply");
        break;

      case DIVIDE:
        emit("call $divide");
        break;

      case MODULO:
        emit("call $modulo");
        break;

      case NEGATE:
        emit("call $negate");
        break;

      case EQUAL:
        emit("call $equal");
        break;

      case LESS:
        emit("call $less");
        break;

      case BUILD_LIST:
        emit("i32.const " + instr.getOperand1());
        emit("call $build_list");
        break;

      case BUILD_TUPLE:
        emit("i32.const " + instr.getOperand1());
        emit("call $build_tuple");
        break;

      case BUILD_DICT:
        emit("i32.const " + instr.getOperand1());
        emit("call $build_dict");
        break;

      case INDEX:
        emit("call $index");
        break;

      case LOAD_ATTR:
        {
          int nameIndex = instr.getOperand1();
          emit("i32.const " + nameIndex);
          emit("call $const_string");
          emit("call $get_attr");
        }
        break;

      case CALL:
        {
          int posArgs = instr.getOperand1();
          int kwArgs = instr.getOperand2();
          emit("i32.const " + posArgs);
          emit("i32.const " + kwArgs);
          emit("call $call");
        }
        break;

      case RETURN:
        emit("return");
        break;

      case JUMP:
        {
          String targetLabel = instructionLabels.get(instr.getOperand1());
          if (targetLabel != null) {
            emit("br $" + targetLabel);
          } else {
            emit(";; ERROR: Invalid jump target");
          }
        }
        break;

      case JUMP_IF_TRUE:
        {
          String targetLabel = instructionLabels.get(instr.getOperand1());
          if (targetLabel != null) {
            emit("call $truth");
            emit("if");
            indent();
            emit("br $" + targetLabel);
            dedent();
            emit("end");
          }
        }
        break;

      case POP_JUMP_IF_FALSE:
        {
          String targetLabel = instructionLabels.get(instr.getOperand1());
          if (targetLabel != null) {
            emit("call $truth");
            emit("i32.eqz");
            emit("if");
            indent();
            emit("br $" + targetLabel);
            dedent();
            emit("end");
          }
        }
        break;

      case GET_ITER:
        emit("call $get_iter");
        break;

      case FOR_ITER:
        {
          String targetLabel = instructionLabels.get(instr.getOperand1());
          if (targetLabel != null) {
            emit("call $iter_next");
            emit("local.tee $temp");
            emit("call $const_none");
            emit("call $equal");
            emit("call $truth");
            emit("if");
            indent();
            emit("br $" + targetLabel);
            dedent();
            emit("end");
            emit("local.get $temp");
          }
        }
        break;

      case NOP:
        emit("nop");
        break;

      default:
        emit(";; TODO: Implement " + op);
        emit("call $const_none ;; placeholder");
        break;
    }

    emit("");
  }

  private void emitConstant(Object constant) {
    if (constant instanceof Integer || constant instanceof Long) {
      long value = ((Number) constant).longValue();
      emit("i64.const " + value);
      emit("call $const_int");
    } else if (constant instanceof Double || constant instanceof Float) {
      double value = ((Number) constant).doubleValue();
      emit("f64.const " + value);
      emit("call $const_float");
    } else if (constant instanceof String) {
      // For now, pass string index - the runtime will handle actual strings
      int index = chunk.getConstantPool().indexOf(constant);
      emit("i32.const " + index);
      emit("call $const_string");
    } else if (constant instanceof Boolean) {
      if ((Boolean) constant) {
        emit("call $const_true");
      } else {
        emit("call $const_false");
      }
    } else {
      emit(";; Unknown constant type: " + constant.getClass());
      emit("call $const_none");
    }
  }

  private String newLabel() {
    return "label" + (labelCounter++);
  }

  private String safeName(String name) {
    // Make name safe for WebAssembly
    return name.replaceAll("[^a-zA-Z0-9_]", "_");
  }

  private int indentLevel = 0;

  private void indent() {
    indentLevel++;
  }

  private void dedent() {
    if (indentLevel > 0) {
      indentLevel--;
    }
  }

  private void emit(String line) {
    for (int i = 0; i < indentLevel; i++) {
      wat.append("  ");
    }
    wat.append(line).append("\n");
  }

  /**
   * Generates a complete WebAssembly module with runtime stubs for testing.
   *
   * @param chunk the bytecode to translate
   * @return WAT source code with runtime stubs
   */
  public static String generateStandalone(BytecodeChunk chunk) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);

    pw.println(";; Standalone WebAssembly module for: " + chunk.getName());
    pw.println(";; Generated from Starlark bytecode");
    pw.println();
    pw.println(generate(chunk));
    pw.println();
    pw.println(";; Runtime stub implementations would go here");
    pw.println(";; In a real implementation, these would be provided by the host environment");

    return sw.toString();
  }
}
