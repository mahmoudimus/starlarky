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

import java.util.Map;
import net.starlark.java.eval.BuckStyleInterpreter;
import net.starlark.java.eval.BytecodeInterpreter;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkGoInterpreter;
import net.starlark.java.eval.StarlarkRustInterpreter;
import net.starlark.java.eval.StarlarkThread;

/**
 * Unified bytecode execution interface that routes to different interpreter backends.
 *
 * <p>This class provides a single entry point for executing bytecode with different
 * interpreter implementations:
 * <ul>
 *   <li>{@link BytecodeTarget#INTERPRETER} - Default interpreter
 *   <li>{@link BytecodeTarget#STARLARK_GO} - starlark-go style (stack-based)
 *   <li>{@link BytecodeTarget#STARLARK_RUST} - starlark-rust style (slot-based)
 *   <li>{@link BytecodeTarget#BUCK} - Buck/Starlark style (IR-based with optimizations)
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <pre>{@code
 * // Execute with default interpreter
 * Object result = BytecodeExecutor.execute(chunk, thread, globals);
 *
 * // Execute with starlark-go style interpreter
 * Object result = BytecodeExecutor.execute(chunk, thread, globals, BytecodeTarget.STARLARK_GO);
 *
 * // Execute with starlark-rust style interpreter
 * Object result = BytecodeExecutor.execute(chunk, thread, globals, BytecodeTarget.STARLARK_RUST);
 *
 * // Execute with arguments (for function calls)
 * Object result = BytecodeExecutor.executeWithArgs(chunk, thread, args, globals, filename, target);
 * }</pre>
 *
 * <h2>Backend Comparison</h2>
 *
 * <table>
 *   <tr><th>Backend</th><th>Model</th><th>Characteristics</th></tr>
 *   <tr>
 *     <td>INTERPRETER</td>
 *     <td>Stack-based</td>
 *     <td>Default, most tested, good error messages</td>
 *   </tr>
 *   <tr>
 *     <td>STARLARK_GO</td>
 *     <td>Stack-based</td>
 *     <td>Follows google/starlark-go, separate locals array, execution stats</td>
 *   </tr>
 *   <tr>
 *     <td>STARLARK_RUST</td>
 *     <td>Slot-based</td>
 *     <td>Follows facebook/starlark-rust, unified memory, better cache locality</td>
 *   </tr>
 * </table>
 */
public final class BytecodeExecutor {

  private BytecodeExecutor() {} // Non-instantiable

  /**
   * Executes bytecode using the default interpreter backend.
   *
   * @param chunk the bytecode to execute
   * @param thread the Starlark thread context
   * @param globals global variable namespace
   * @return the execution result
   * @throws EvalException if execution fails
   * @throws InterruptedException if execution is interrupted
   */
  public static Object execute(
      BytecodeChunk chunk,
      StarlarkThread thread,
      Map<String, Object> globals)
      throws EvalException, InterruptedException {
    return execute(chunk, thread, globals, BytecodeTarget.INTERPRETER);
  }

  /**
   * Executes bytecode using the specified interpreter backend.
   *
   * @param chunk the bytecode to execute
   * @param thread the Starlark thread context
   * @param globals global variable namespace
   * @param target the interpreter backend to use
   * @return the execution result
   * @throws EvalException if execution fails
   * @throws InterruptedException if execution is interrupted
   */
  public static Object execute(
      BytecodeChunk chunk,
      StarlarkThread thread,
      Map<String, Object> globals,
      BytecodeTarget target)
      throws EvalException, InterruptedException {
    return execute(chunk, thread, globals, null, target);
  }

  /**
   * Executes bytecode with filename for error reporting.
   *
   * @param chunk the bytecode to execute
   * @param thread the Starlark thread context
   * @param globals global variable namespace
   * @param filename source filename for error messages
   * @param target the interpreter backend to use
   * @return the execution result
   * @throws EvalException if execution fails
   * @throws InterruptedException if execution is interrupted
   */
  public static Object execute(
      BytecodeChunk chunk,
      StarlarkThread thread,
      Map<String, Object> globals,
      String filename,
      BytecodeTarget target)
      throws EvalException, InterruptedException {

    switch (target) {
      case STARLARK_GO:
        return StarlarkGoInterpreter.execute(chunk, thread, globals, null, filename, false);

      case STARLARK_RUST:
        return StarlarkRustInterpreter.execute(chunk, thread, globals, filename);

      case BUCK:
        return BuckStyleInterpreter.execute(chunk, thread, globals, filename);

      case INTERPRETER:
      default:
        return BytecodeInterpreter.execute(chunk, thread, globals, filename);
    }
  }

  /**
   * Executes bytecode with arguments using the default interpreter.
   *
   * @param chunk the bytecode to execute
   * @param thread the Starlark thread context
   * @param args function arguments
   * @param globals global variable namespace
   * @return the execution result
   * @throws EvalException if execution fails
   * @throws InterruptedException if execution is interrupted
   */
  public static Object executeWithArgs(
      BytecodeChunk chunk,
      StarlarkThread thread,
      Object[] args,
      Map<String, Object> globals)
      throws EvalException, InterruptedException {
    return executeWithArgs(chunk, thread, args, globals, null, BytecodeTarget.INTERPRETER);
  }

  /**
   * Executes bytecode with arguments using the specified interpreter backend.
   *
   * @param chunk the bytecode to execute
   * @param thread the Starlark thread context
   * @param args function arguments
   * @param globals global variable namespace
   * @param filename source filename for error messages
   * @param target the interpreter backend to use
   * @return the execution result
   * @throws EvalException if execution fails
   * @throws InterruptedException if execution is interrupted
   */
  public static Object executeWithArgs(
      BytecodeChunk chunk,
      StarlarkThread thread,
      Object[] args,
      Map<String, Object> globals,
      String filename,
      BytecodeTarget target)
      throws EvalException, InterruptedException {

    switch (target) {
      case STARLARK_GO:
        return StarlarkGoInterpreter.executeWithArgs(chunk, thread, args, globals, filename);

      case STARLARK_RUST:
        return StarlarkRustInterpreter.executeWithArgs(chunk, thread, args, globals, filename);

      case BUCK:
        return BuckStyleInterpreter.executeWithArgs(chunk, thread, args, globals, filename);

      case INTERPRETER:
      default:
        return BytecodeInterpreter.executeWithArgs(chunk, thread, args, globals, filename);
    }
  }

  /**
   * Returns true if the target is an interpreter backend (vs code generation).
   */
  public static boolean isInterpreterTarget(BytecodeTarget target) {
    switch (target) {
      case INTERPRETER:
      case STARLARK_GO:
      case STARLARK_RUST:
      case BUCK:
        return true;
      default:
        return false;
    }
  }

  /**
   * Returns true if the target generates code (vs interpretation).
   */
  public static boolean isCodeGenTarget(BytecodeTarget target) {
    switch (target) {
      case JVM:
      case WASM:
        return true;
      default:
        return false;
    }
  }
}
