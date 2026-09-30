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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A chunk of bytecode representing a compiled Starlark function or module.
 *
 * A bytecode chunk contains:
 * - A constant pool
 * - A sequence of instructions
 * - Metadata (arity, local count, etc.)
 * - Debug information (line numbers, source locations)
 */
public final class BytecodeChunk {
  private final String name;
  private final ConstantPool constantPool;
  private final List<Instruction> instructions;
  private final int localCount;
  private final int parameterCount;
  private final List<String> parameterNames;
  private final List<String> localNames; // Names of all local variables for error messages
  private final List<Integer> lineNumbers; // Line number for each instruction
  private final List<Integer> columnNumbers; // Column number for each instruction
  private final boolean frozen;

  private BytecodeChunk(
      String name,
      ConstantPool constantPool,
      List<Instruction> instructions,
      int localCount,
      int parameterCount,
      List<String> parameterNames,
      List<String> localNames,
      List<Integer> lineNumbers,
      List<Integer> columnNumbers,
      boolean frozen) {
    this.name = name;
    this.constantPool = constantPool;
    this.instructions = instructions;
    this.localCount = localCount;
    this.parameterCount = parameterCount;
    this.parameterNames = parameterNames;
    this.localNames = localNames != null ? localNames : new ArrayList<>();
    this.lineNumbers = lineNumbers;
    this.columnNumbers = columnNumbers != null ? columnNumbers : new ArrayList<>();
    this.frozen = frozen;
  }

  public String getName() {
    return name;
  }

  public ConstantPool getConstantPool() {
    return constantPool;
  }

  public List<Instruction> getInstructions() {
    return Collections.unmodifiableList(instructions);
  }

  public int getInstructionCount() {
    return instructions.size();
  }

  public Instruction getInstruction(int index) {
    return instructions.get(index);
  }

  public int getLocalCount() {
    return localCount;
  }

  public int getParameterCount() {
    return parameterCount;
  }

  public List<String> getParameterNames() {
    return Collections.unmodifiableList(parameterNames);
  }

  public List<String> getLocalNames() {
    return Collections.unmodifiableList(localNames);
  }

  public List<Integer> getLineNumbers() {
    return Collections.unmodifiableList(lineNumbers);
  }

  public List<Integer> getColumnNumbers() {
    return Collections.unmodifiableList(columnNumbers);
  }

  public boolean isFrozen() {
    return frozen;
  }

  /**
   * Returns the line number for the instruction at the given index.
   */
  public int getLineNumber(int instructionIndex) {
    if (instructionIndex < 0 || instructionIndex >= lineNumbers.size()) {
      return -1;
    }
    return lineNumbers.get(instructionIndex);
  }

  /**
   * Returns the column number for the instruction at the given index.
   */
  public int getColumnNumber(int instructionIndex) {
    if (instructionIndex < 0 || instructionIndex >= columnNumbers.size()) {
      return 0; // Return 0 as default column if not available
    }
    return columnNumbers.get(instructionIndex);
  }

  /**
   * Serializes this bytecode chunk to bytes.
   */
  public byte[] toBytes() throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(baos);

    // Write magic number and version
    dos.writeInt(0x5354524C); // "STRL" in hex
    dos.writeInt(1); // Version 1

    // Write metadata
    dos.writeUTF(name);
    dos.writeInt(localCount);
    dos.writeInt(parameterCount);

    // Write parameter names
    dos.writeInt(parameterNames.size());
    for (String param : parameterNames) {
      dos.writeUTF(param);
    }

    // Write constant pool
    List<Object> constants = constantPool.getConstants();
    dos.writeInt(constants.size());
    for (Object constant : constants) {
      writeConstant(dos, constant);
    }

    // Write instructions
    dos.writeInt(instructions.size());
    for (Instruction instruction : instructions) {
      dos.writeByte(instruction.getOpcode().toByte());
      if (instruction.getOpcode().getOperandCount() >= 1) {
        dos.writeInt(instruction.getOperand1());
      }
      if (instruction.getOpcode().getOperandCount() >= 2) {
        dos.writeInt(instruction.getOperand2());
      }
    }

    // Write line numbers
    dos.writeInt(lineNumbers.size());
    for (int lineNumber : lineNumbers) {
      dos.writeInt(lineNumber);
    }

    dos.flush();
    return baos.toByteArray();
  }

  private void writeConstant(DataOutputStream dos, Object constant) throws IOException {
    if (constant instanceof String) {
      dos.writeByte(0); // Type tag for String
      dos.writeUTF((String) constant);
    } else if (constant instanceof Integer) {
      dos.writeByte(1); // Type tag for Integer
      dos.writeInt((Integer) constant);
    } else if (constant instanceof Long) {
      dos.writeByte(2); // Type tag for Long
      dos.writeLong((Long) constant);
    } else if (constant instanceof Double) {
      dos.writeByte(3); // Type tag for Double
      dos.writeDouble((Double) constant);
    } else if (constant instanceof Boolean) {
      dos.writeByte(4); // Type tag for Boolean
      dos.writeBoolean((Boolean) constant);
    } else {
      throw new IOException("Unsupported constant type: " + constant.getClass());
    }
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("BytecodeChunk '").append(name).append("' {\n");
    sb.append("  Parameters: ").append(parameterCount).append(" ");
    sb.append(parameterNames).append("\n");
    sb.append("  Locals: ").append(localCount).append("\n");
    sb.append("  Instructions: ").append(instructions.size()).append("\n\n");

    sb.append(constantPool).append("\n");

    sb.append("  Bytecode:\n");
    for (int i = 0; i < instructions.size(); i++) {
      Instruction instr = instructions.get(i);
      int lineNum = i < lineNumbers.size() ? lineNumbers.get(i) : -1;
      sb.append("    ");
      if (lineNum >= 0) {
        sb.append(String.format("L%-4d ", lineNum));
      } else {
        sb.append("      ");
      }
      sb.append(instr).append("\n");
    }

    sb.append("}");
    return sb.toString();
  }

  /**
   * Builder for creating BytecodeChunk instances.
   */
  public static class Builder {
    private final String name;
    private final ConstantPool constantPool;
    private final List<Instruction> instructions;
    private final List<Integer> lineNumbers;
    private final List<Integer> columnNumbers;
    private final List<String> parameterNames;
    private final List<String> localNames;
    private int localCount;
    private int parameterCount;
    private int currentOffset;

    public Builder(String name) {
      this.name = name;
      this.constantPool = new ConstantPool();
      this.instructions = new ArrayList<>();
      this.lineNumbers = new ArrayList<>();
      this.columnNumbers = new ArrayList<>();
      this.parameterNames = new ArrayList<>();
      this.localNames = new ArrayList<>();
      this.localCount = 0;
      this.parameterCount = 0;
      this.currentOffset = 0;
    }

    public Builder setLocalCount(int count) {
      this.localCount = count;
      return this;
    }

    public Builder setParameterCount(int count) {
      this.parameterCount = count;
      return this;
    }

    public Builder addParameter(String name) {
      this.parameterNames.add(name);
      return this;
    }

    public Builder addLocalName(String name) {
      this.localNames.add(name);
      return this;
    }

    public ConstantPool getConstantPool() {
      return constantPool;
    }

    public int addConstant(Object value) {
      return constantPool.addConstant(value);
    }

    public Builder emit(Opcode opcode, int lineNumber) {
      Instruction instr = Instruction.create(opcode, currentOffset);
      instructions.add(instr);
      lineNumbers.add(lineNumber);
      columnNumbers.add(0); // Default column to 0
      currentOffset += instr.getSize();
      return this;
    }

    public Builder emit(Opcode opcode, int operand1, int lineNumber) {
      Instruction instr = Instruction.create(opcode, operand1, currentOffset);
      instructions.add(instr);
      lineNumbers.add(lineNumber);
      columnNumbers.add(0); // Default column to 0
      currentOffset += instr.getSize();
      return this;
    }

    public Builder emit(Opcode opcode, int operand1, int operand2, int lineNumber) {
      Instruction instr = Instruction.create(opcode, operand1, operand2, currentOffset);
      instructions.add(instr);
      lineNumbers.add(lineNumber);
      columnNumbers.add(0); // Default column to 0
      currentOffset += instr.getSize();
      return this;
    }

    // New methods with explicit column numbers (for future use)
    public Builder emitWithColumn(Opcode opcode, int lineNumber, int columnNumber) {
      Instruction instr = Instruction.create(opcode, currentOffset);
      instructions.add(instr);
      lineNumbers.add(lineNumber);
      columnNumbers.add(columnNumber);
      currentOffset += instr.getSize();
      return this;
    }

    public Builder emitWithColumn(Opcode opcode, int operand1, int lineNumber, int columnNumber) {
      Instruction instr = Instruction.create(opcode, operand1, currentOffset);
      instructions.add(instr);
      lineNumbers.add(lineNumber);
      columnNumbers.add(columnNumber);
      currentOffset += instr.getSize();
      return this;
    }

    public Builder emitWithColumn(Opcode opcode, int operand1, int operand2, int lineNumber, int columnNumber) {
      Instruction instr = Instruction.create(opcode, operand1, operand2, currentOffset);
      instructions.add(instr);
      lineNumbers.add(lineNumber);
      columnNumbers.add(columnNumber);
      currentOffset += instr.getSize();
      return this;
    }

    public int getCurrentOffset() {
      return currentOffset;
    }

    public int getInstructionCount() {
      return instructions.size();
    }

    // Update an instruction's first operand (for jump patching)
    public void updateInstructionOperand(int index, int newOperand) {
      if (index < 0 || index >= instructions.size()) {
        throw new IllegalArgumentException("Invalid instruction index: " + index);
      }
      Instruction oldInstr = instructions.get(index);
      // Create new instruction with updated operand (preserve old offset)
      Instruction newInstr = Instruction.create(oldInstr.getOpcode(), newOperand, oldInstr.getOffset());
      if (oldInstr.getOpcode().getOperandCount() == 2) {
        newInstr = Instruction.create(oldInstr.getOpcode(), newOperand, oldInstr.getOperand2(), oldInstr.getOffset());
      }
      instructions.set(index, newInstr);
    }

    public BytecodeChunk build() {
      constantPool.freeze();
      return new BytecodeChunk(
          name,
          constantPool,
          new ArrayList<>(instructions),
          localCount,
          parameterCount,
          new ArrayList<>(parameterNames),
          new ArrayList<>(localNames),
          new ArrayList<>(lineNumbers),
          new ArrayList<>(columnNumbers),
          true);
    }
  }
}
