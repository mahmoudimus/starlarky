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

import java.util.Map;
import net.starlark.java.eval.compiler.BytecodeChunk;
import net.starlark.java.eval.compiler.BytecodeTarget;

/**
 * Dispatches file and function bodies to the VM selected by {@code -Dstarlark.bytecode.vm}, so
 * that the same program runs entirely on one VM.
 */
final class BytecodeVms {

  private BytecodeVms() {}

  private static final BytecodeTarget VM = BytecodeTarget.configuredVm();

  /** Runs a file's top-level code. */
  static Object execute(
      BytecodeChunk chunk, StarlarkThread thread, Map<String, Object> globals, String filename)
      throws EvalException, InterruptedException {
    switch (VM) {
      case STARLARK_GO:
        return StarlarkGoInterpreter.execute(chunk, thread, globals, null, filename, false);
      case STARLARK_RUST:
        return StarlarkRustInterpreter.execute(chunk, thread, globals, filename);
      case BUCK:
        return BuckStyleInterpreter.execute(chunk, thread, globals, filename);
      default:
        return BytecodeInterpreter.execute(chunk, thread, globals, filename);
    }
  }

  /** Runs a function body whose frame has already been pushed and whose locals are bound. */
  static Object executeWithLocals(
      BytecodeChunk chunk,
      StarlarkThread thread,
      Object[] locals,
      Map<String, Object> globals,
      String filename,
      Tuple freevars)
      throws EvalException, InterruptedException {
    switch (VM) {
      case STARLARK_GO:
        return StarlarkGoInterpreter.executeWithLocals(
            chunk, thread, locals, globals, filename, freevars);
      case STARLARK_RUST:
        return StarlarkRustInterpreter.executeWithLocals(
            chunk, thread, locals, globals, filename, freevars);
      case BUCK:
        return BuckStyleInterpreter.executeWithLocals(
            chunk, thread, locals, globals, filename, freevars);
      default:
        return BytecodeInterpreter.executeWithLocals(
            chunk, thread, locals, globals, filename, freevars);
    }
  }
}
