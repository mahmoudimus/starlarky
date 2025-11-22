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

/**
 * Bytecode opcodes for the Starlark bytecode compiler.
 *
 * This instruction set is designed to be:
 * 1. Stack-based for simplicity and WebAssembly compatibility
 * 2. Compact and efficient for interpretation
 * 3. Mappable to WebAssembly instructions
 */
public enum Opcode {
  // Stack manipulation
  POP(0),           // Pop top value from stack
  DUP(0),           // Duplicate top of stack
  SWAP(0),          // Swap top two stack values

  // Constants and literals
  LOAD_CONST(1),    // Push constant from constant pool (operand: index)
  LOAD_NONE(0),     // Push None
  LOAD_TRUE(0),     // Push True
  LOAD_FALSE(0),    // Push False

  // Variables
  LOAD_LOCAL(1),    // Load local variable (operand: slot index)
  STORE_LOCAL(1),   // Store to local variable (operand: slot index)
  LOAD_GLOBAL(1),   // Load global variable (operand: name index)
  STORE_GLOBAL(1),  // Store to global variable (operand: name index)
  LOAD_FREE(1),     // Load free variable from closure (operand: index)
  STORE_FREE(1),    // Store to free variable (operand: index)

  // Arithmetic operations
  ADD(0),           // Binary +
  SUBTRACT(0),      // Binary -
  MULTIPLY(0),      // Binary *
  DIVIDE(0),        // Binary /
  FLOOR_DIV(0),     // Binary //
  MODULO(0),        // Binary %
  NEGATE(0),        // Unary -
  POSITIVE(0),      // Unary +
  POWER(0),         // Binary **

  // Comparison operations
  EQUAL(0),         // ==
  NOT_EQUAL(0),     // !=
  LESS(0),          // <
  LESS_EQUAL(0),    // <=
  GREATER(0),       // >
  GREATER_EQUAL(0), // >=
  IN(0),            // in operator
  NOT_IN(0),        // not in operator

  // Logical operations
  AND(0),           // Logical and
  OR(0),            // Logical or
  NOT(0),           // Logical not

  // Bitwise operations
  BIT_AND(0),       // &
  BIT_OR(0),        // |
  BIT_XOR(0),       // ^
  BIT_NOT(0),       // ~
  LEFT_SHIFT(0),    // <<
  RIGHT_SHIFT(0),   // >>

  // Collections
  BUILD_LIST(1),    // Build list (operand: count of elements on stack)
  BUILD_TUPLE(1),   // Build tuple (operand: count)
  BUILD_DICT(1),    // Build dict (operand: count of key-value pairs)
  BUILD_SET(1),     // Build set (operand: count)
  UNPACK_SEQUENCE(1), // Unpack sequence into N values (operand: count)

  // Indexing and slicing
  INDEX(0),         // a[b] - indexing operation
  SLICE(0),         // a[b:c] or a[b:c:d] - slicing operation
  STORE_INDEX(0),   // a[b] = c - indexed assignment

  // Attributes
  LOAD_ATTR(1),     // Load attribute (operand: name index)
  STORE_ATTR(1),    // Store attribute (operand: name index)

  // Function calls
  CALL(2),          // Call function (operand1: positional args, operand2: keyword args)
  CALL_METHOD(2),   // Call method (operand1: positional args, operand2: keyword args)
  RETURN(0),        // Return from function

  // Function definition
  MAKE_FUNCTION(1), // Create function object (operand: default args count)
  MAKE_CLOSURE(2),  // Create closure (operand1: free vars, operand2: default args)

  // Control flow
  JUMP(1),          // Unconditional jump (operand: offset)
  JUMP_IF_TRUE(1),  // Jump if top of stack is true (operand: offset)
  JUMP_IF_FALSE(1), // Jump if top of stack is false (operand: offset)
  POP_JUMP_IF_TRUE(1),   // Pop and jump if true (operand: offset)
  POP_JUMP_IF_FALSE(1),  // Pop and jump if false (operand: offset)

  // Iteration
  GET_ITER(0),      // Get iterator from object
  FOR_ITER(1),      // Iterate (operand: jump offset if exhausted)

  // Comprehensions
  LIST_APPEND(1),   // Append to list (operand: list stack offset)
  DICT_ADD(1),      // Add key-value to dict (operand: dict stack offset)
  SET_ADD(1),       // Add to set (operand: set stack offset)

  // Exception handling (minimal - Starlark has limited exception support)
  RAISE(1),         // Raise exception (operand: argc)

  // Special
  NOP(0),           // No operation
  BREAK(0),         // Break from loop
  CONTINUE(0),      // Continue loop
  LOAD_MODULE(1),   // Load module (operand: name index)

  // WebAssembly-specific hints (for optimization)
  WASM_I32_CONST(1),     // Hint: this is an i32 constant
  WASM_I64_CONST(1),     // Hint: this is an i64 constant
  WASM_F32_CONST(1),     // Hint: this is an f32 constant
  WASM_F64_CONST(1);     // Hint: this is an f64 constant

  private final int operandCount;

  Opcode(int operandCount) {
    this.operandCount = operandCount;
  }

  /**
   * Returns the number of operands this opcode expects.
   */
  public int getOperandCount() {
    return operandCount;
  }

  /**
   * Returns the byte value of this opcode.
   */
  public byte toByte() {
    return (byte) ordinal();
  }

  /**
   * Returns the opcode corresponding to the given byte value.
   */
  public static Opcode fromByte(byte b) {
    int index = Byte.toUnsignedInt(b);
    Opcode[] values = values();
    if (index >= values.length) {
      throw new IllegalArgumentException("Invalid opcode: " + index);
    }
    return values[index];
  }

  /**
   * Returns true if this opcode is a jump instruction.
   */
  public boolean isJump() {
    return this == JUMP || this == JUMP_IF_TRUE || this == JUMP_IF_FALSE
        || this == POP_JUMP_IF_TRUE || this == POP_JUMP_IF_FALSE || this == FOR_ITER;
  }

  /**
   * Returns true if this opcode modifies control flow.
   */
  public boolean isControlFlow() {
    return isJump() || this == RETURN || this == BREAK || this == CONTINUE || this == RAISE;
  }
}
