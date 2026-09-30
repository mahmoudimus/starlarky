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
 * Enumeration of available bytecode compilation targets.
 *
 * <p>The Starlark bytecode compiler can generate code for multiple backends:
 * <ul>
 *   <li><b>INTERPRETER</b>: Execute via the built-in bytecode interpreter
 *   <li><b>STARLARK_GO</b>: Execute via starlark-go style stack-based interpreter
 *   <li><b>STARLARK_RUST</b>: Execute via starlark-rust style slot-based interpreter
 *   <li><b>JVM</b>: Generate JVM .class files for native Java execution
 *   <li><b>WASM</b>: Generate WebAssembly text format (WAT) for browser/WASM runtime execution
 * </ul>
 */
public enum BytecodeTarget {

  /**
   * Execute bytecode using the built-in stack-based interpreter.
   *
   * <p>This is the default and most portable option. The bytecode is executed
   * directly by {@link net.starlark.java.eval.BytecodeInterpreter} without any
   * additional compilation step.
   */
  INTERPRETER("interpreter", "stc", "Starlark Bytecode Interpreter"),

  /**
   * Execute bytecode using the starlark-go style interpreter.
   *
   * <p>This follows the google/starlark-go execution model:
   * <ul>
   *   <li>Stack-based virtual machine with separate operand stack
   *   <li>Local variables stored in a separate array
   *   <li>Simple switch-based opcode dispatch
   *   <li>Delta-encoded position tracking for debugging
   * </ul>
   *
   * @see StarlarkGoInterpreter
   */
  STARLARK_GO("starlark-go", "stc", "Starlark-Go Style Interpreter"),

  /**
   * Execute bytecode using the starlark-rust style interpreter.
   *
   * <p>This follows the facebook/starlark-rust execution model:
   * <ul>
   *   <li>Slot-based memory model: unified array for locals AND stack
   *   <li>Fixed frame size computed at compile time
   *   <li>Type-safe instruction dispatch via handler pattern
   *   <li>Optimized for cache-friendly sequential memory access
   * </ul>
   *
   * @see StarlarkRustInterpreter
   */
  STARLARK_RUST("starlark-rust", "stc", "Starlark-Rust Style Interpreter"),

  /**
   * Execute bytecode using the Buck/Starlark style interpreter.
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
  BUCK("buck", "stc", "Buck/Starlark Style Interpreter"),

  /**
   * Compile to JVM bytecode (.class files).
   *
   * <p>This generates valid Java class files that can be loaded and executed
   * directly on the JVM. Benefits include:
   * <ul>
   *   <li>Near-native performance via JIT compilation
   *   <li>Full stack trace support with source line mapping
   *   <li>Integration with Java debugging tools
   * </ul>
   */
  JVM("jvm", "class", "JVM Bytecode"),

  /**
   * Compile to WebAssembly text format (WAT).
   *
   * <p>This generates WebAssembly text format that can be converted to binary
   * WASM and run in browsers or standalone WASM runtimes. Benefits include:
   * <ul>
   *   <li>Cross-platform execution
   *   <li>Browser compatibility
   *   <li>Sandboxed execution environment
   * </ul>
   */
  WASM("wasm", "wat", "WebAssembly");

  private final String id;
  private final String fileExtension;
  private final String displayName;

  BytecodeTarget(String id, String fileExtension, String displayName) {
    this.id = id;
    this.fileExtension = fileExtension;
    this.displayName = displayName;
  }

  /**
   * Returns the unique identifier for this target.
   */
  public String getId() {
    return id;
  }

  /**
   * Returns the file extension for output files from this target.
   */
  public String getFileExtension() {
    return fileExtension;
  }

  /**
   * Returns a human-readable name for this target.
   */
  public String getDisplayName() {
    return displayName;
  }

  /**
   * Returns the target for the given ID, or null if not found.
   */
  public static BytecodeTarget fromId(String id) {
    for (BytecodeTarget target : values()) {
      if (target.id.equals(id)) {
        return target;
      }
    }
    return null;
  }

  /**
   * Returns the default compilation target.
   */
  public static BytecodeTarget getDefault() {
    return INTERPRETER;
  }

  @Override
  public String toString() {
    return displayName;
  }
}
