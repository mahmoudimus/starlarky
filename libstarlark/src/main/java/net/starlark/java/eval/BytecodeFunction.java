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
import java.util.List;
import java.util.Map;
import net.starlark.java.eval.compiler.BytecodeChunk;
import net.starlark.java.syntax.Location;

/**
 * A BytecodeFunction is a function value created by compiling a Starlark {@code def} statement
 * to bytecode.
 */
public final class BytecodeFunction implements StarlarkCallable {

  private final String name;
  private final Location location;
  private final BytecodeChunk chunk;
  private final ImmutableList<String> parameterNames;
  private final int parameterCount;
  private final String filename;

  public BytecodeFunction(
      String name,
      Location location,
      BytecodeChunk chunk,
      List<String> parameterNames,
      String filename) {
    this.name = name;
    this.location = location;
    this.chunk = chunk;
    this.parameterNames = ImmutableList.copyOf(parameterNames);
    this.parameterCount = parameterNames.size();
    this.filename = filename;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public Location getLocation() {
    return location;
  }

  private Map<String, Object> globals; // Captured globals from function definition

  public void setGlobals(Map<String, Object> globals) {
    this.globals = globals;
  }

  @Override
  public Object call(StarlarkThread thread, Tuple args, Dict<String, Object> kwargs)
      throws EvalException, InterruptedException {

    // Validate argument count
    int providedArgs = args.size() + kwargs.size();
    if (args.size() < parameterCount) {
      throw Starlark.errorf(
          "%s() missing %d required positional argument%s: %s",
          name,
          parameterCount - args.size(),
          parameterCount - args.size() == 1 ? "" : "s",
          String.join(", ", parameterNames.subList(args.size(), parameterCount)));
    }
    if (args.size() > parameterCount) {
      throw Starlark.errorf(
          "%s() accepts %d positional argument%s but %d %s given",
          name,
          parameterCount,
          parameterCount == 1 ? "" : "s",
          args.size(),
          args.size() == 1 ? "was" : "were");
    }

    // Check for unexpected keyword arguments
    if (!kwargs.isEmpty()) {
      throw Starlark.errorf(
          "%s() got unexpected keyword argument%s: %s",
          name,
          kwargs.size() == 1 ? "" : "s",
          String.join(", ", kwargs.keySet()));
    }

    // Convert Tuple to Object[]
    Object[] argArray = new Object[args.size()];
    for (int i = 0; i < args.size(); i++) {
      argArray[i] = args.get(i);
    }

    // Execute the function body bytecode with the arguments
    return BytecodeInterpreter.executeWithArgs(chunk, thread, argArray, globals, filename);
  }

  @Override
  public void repr(Printer printer) {
    printer.append("<function ");
    printer.append(name);
    printer.append(">");
  }

  @Override
  public String toString() {
    return "<function " + name + ">";
  }
}
