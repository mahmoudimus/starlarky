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

import java.io.IOException;
import javax.annotation.Nullable;

/**
 * Interface for bytecode compilation backends.
 *
 * <p>Each backend transforms Starlark bytecode ({@link BytecodeChunk}) into a
 * target-specific format. Implementations include:
 * <ul>
 *   <li>{@link JvmBackend}: Generates JVM .class files
 *   <li>{@link WasmBackend}: Generates WebAssembly text format
 *   <li>{@link InterpreterBackend}: Returns bytecode for interpreter execution
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * BytecodeChunk chunk = compiler.compile(source);
 * BytecodeBackend backend = BytecodeBackend.forTarget(BytecodeTarget.JVM);
 * byte[] output = backend.generate(chunk, "MyScript", "script.star");
 * }</pre>
 */
public interface BytecodeBackend {

  /**
   * Returns the target type for this backend.
   */
  BytecodeTarget getTarget();

  /**
   * Generates target-specific output from the given bytecode.
   *
   * @param chunk the compiled bytecode
   * @param className the class/module name for the output (e.g., "com/example/Script")
   * @param sourceFile the original source file name for debugging
   * @return the generated output bytes
   * @throws IOException if generation fails
   */
  byte[] generate(BytecodeChunk chunk, String className, String sourceFile) throws IOException;

  /**
   * Generates target-specific output as a string (for text-based targets like WASM).
   *
   * @param chunk the compiled bytecode
   * @param moduleName the module name for the output
   * @return the generated output as a string, or null if this backend produces binary output
   */
  @Nullable
  default String generateText(BytecodeChunk chunk, String moduleName) {
    return null;
  }

  /**
   * Returns true if this backend produces text output (e.g., WAT).
   */
  default boolean isTextOutput() {
    return false;
  }

  /**
   * Returns a backend for the specified target.
   */
  static BytecodeBackend forTarget(BytecodeTarget target) {
    switch (target) {
      case JVM:
        return new JvmBackend();
      case WASM:
        return new WasmBackend();
      case STARLARK_GO:
        return new StarlarkGoBackend();
      case STARLARK_RUST:
        return new StarlarkRustBackend();
      case BUCK:
        return new BuckBackend();
      case INTERPRETER:
      default:
        return new InterpreterBackend();
    }
  }

  // ==================== Built-in Backend Implementations ====================

  /**
   * Backend that returns bytecode for interpreter execution.
   */
  class InterpreterBackend implements BytecodeBackend {
    @Override
    public BytecodeTarget getTarget() {
      return BytecodeTarget.INTERPRETER;
    }

    @Override
    public byte[] generate(BytecodeChunk chunk, String className, String sourceFile)
        throws IOException {
      // BytecodeSerializer temporarily disabled - serialization not needed for testing
      throw new UnsupportedOperationException("BytecodeSerializer disabled - not needed for execution");
    }
  }

  /**
   * Backend that generates JVM bytecode.
   */
  class JvmBackend implements BytecodeBackend {
    @Override
    public BytecodeTarget getTarget() {
      return BytecodeTarget.JVM;
    }

    @Override
    public byte[] generate(BytecodeChunk chunk, String className, String sourceFile)
        throws IOException {
      return JvmBytecodeGenerator.generate(chunk, className, sourceFile);
    }
  }

  /**
   * Backend that generates WebAssembly text format.
   */
  class WasmBackend implements BytecodeBackend {
    @Override
    public BytecodeTarget getTarget() {
      return BytecodeTarget.WASM;
    }

    @Override
    public byte[] generate(BytecodeChunk chunk, String className, String sourceFile)
        throws IOException {
      String wat = WasmGenerator.generate(chunk);
      return wat.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public String generateText(BytecodeChunk chunk, String moduleName) {
      return WasmGenerator.generate(chunk);
    }

    @Override
    public boolean isTextOutput() {
      return true;
    }
  }

  /**
   * Backend that executes bytecode using the starlark-go style interpreter.
   *
   * <p>This follows the google/starlark-go execution model:
   * <ul>
   *   <li>Stack-based virtual machine with separate operand stack
   *   <li>Local variables stored in a separate array
   *   <li>Simple switch-based opcode dispatch
   * </ul>
   *
   * @see StarlarkGoInterpreter
   */
  class StarlarkGoBackend implements BytecodeBackend {
    @Override
    public BytecodeTarget getTarget() {
      return BytecodeTarget.STARLARK_GO;
    }

    @Override
    public byte[] generate(BytecodeChunk chunk, String className, String sourceFile)
        throws IOException {
      // This backend is for interpretation, not code generation
      throw new UnsupportedOperationException(
          "StarlarkGoBackend is an interpreter backend - use StarlarkGoInterpreter.execute() directly");
    }

    /**
     * Returns a description of this backend for debugging.
     */
    @Override
    public String toString() {
      return "StarlarkGoBackend{stack-based interpreter following google/starlark-go model}";
    }
  }

  /**
   * Backend that executes bytecode using the starlark-rust style interpreter.
   *
   * <p>This follows the facebook/starlark-rust execution model:
   * <ul>
   *   <li>Slot-based memory model: unified array for locals AND stack
   *   <li>Fixed frame size computed at compile time
   *   <li>Optimized for cache-friendly sequential memory access
   * </ul>
   *
   * @see StarlarkRustInterpreter
   */
  class StarlarkRustBackend implements BytecodeBackend {
    @Override
    public BytecodeTarget getTarget() {
      return BytecodeTarget.STARLARK_RUST;
    }

    @Override
    public byte[] generate(BytecodeChunk chunk, String className, String sourceFile)
        throws IOException {
      // This backend is for interpretation, not code generation
      throw new UnsupportedOperationException(
          "StarlarkRustBackend is an interpreter backend - use StarlarkRustInterpreter.execute() directly");
    }

    /**
     * Returns a description of this backend for debugging.
     */
    @Override
    public String toString() {
      return "StarlarkRustBackend{slot-based interpreter following facebook/starlark-rust model}";
    }
  }

  /**
   * Backend that executes bytecode using the Buck/Starlark style interpreter.
   *
   * <p>This follows the facebook/buck Starlark execution model:
   * <ul>
   *   <li>IR-based intermediate representation for optimization
   *   <li>Slot-based variable management (Local, Global, Cell, Free)
   *   <li>Call site caching for repeated method calls
   *   <li>Type-specialized operations (PLUS_STRING, PLUS_LIST)
   * </ul>
   *
   * @see BuckStyleInterpreter
   */
  class BuckBackend implements BytecodeBackend {
    @Override
    public BytecodeTarget getTarget() {
      return BytecodeTarget.BUCK;
    }

    @Override
    public byte[] generate(BytecodeChunk chunk, String className, String sourceFile)
        throws IOException {
      // This backend is for interpretation, not code generation
      throw new UnsupportedOperationException(
          "BuckBackend is an interpreter backend - use BuckStyleInterpreter.execute() directly");
    }

    /**
     * Returns a description of this backend for debugging.
     */
    @Override
    public String toString() {
      return "BuckBackend{IR-based interpreter following facebook/buck model with call site caching}";
    }
  }
}
