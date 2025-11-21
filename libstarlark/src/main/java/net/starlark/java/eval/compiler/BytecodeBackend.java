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
      // Return serialized bytecode for the interpreter
      return BytecodeSerializer.serialize(chunk);
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
}
