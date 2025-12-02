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

import com.google.common.collect.ImmutableList;
import net.starlark.java.syntax.Location;

/**
 * Descriptor for a bytecode-compiled function, containing all metadata needed to create
 * a BytecodeFunction instance at runtime.
 *
 * <p>This class captures function signature information needed for proper argument handling:
 * <ul>
 *   <li>Parameter names (including *args and **kwargs if present)
 *   <li>Whether the function has *args (varargs)
 *   <li>Whether the function has **kwargs
 *   <li>Number of keyword-only parameters
 *   <li>Default values for optional parameters
 *   <li>Local variable count for frame allocation
 * </ul>
 */
public final class FunctionDescriptor {
  private final String name;
  private final Location location;
  private final BytecodeChunk chunk;
  private final ImmutableList<String> parameterNames;
  private final boolean hasVarargs;
  private final boolean hasKwargs;
  private final int numKeywordOnlyParams;
  private final ImmutableList<Object> defaultValues;
  private final int localCount;

  /**
   * Creates a simple function descriptor with no special parameters.
   * This constructor is kept for backward compatibility.
   */
  public FunctionDescriptor(
      String name,
      Location location,
      BytecodeChunk chunk,
      ImmutableList<String> parameterNames) {
    this(name, location, chunk, parameterNames,
        /*hasVarargs=*/ false,
        /*hasKwargs=*/ false,
        /*numKeywordOnlyParams=*/ 0,
        /*defaultValues=*/ ImmutableList.of(),
        /*localCount=*/ parameterNames.size());
  }

  /**
   * Creates a full function descriptor with all signature information.
   *
   * @param name the function name
   * @param location the source location
   * @param chunk the compiled bytecode
   * @param parameterNames all parameter names including *args/**kwargs names if present
   * @param hasVarargs whether the function has *args
   * @param hasKwargs whether the function has **kwargs
   * @param numKeywordOnlyParams number of keyword-only parameters (after *args or *)
   * @param defaultValues default values for optional parameters (contains MANDATORY sentinel for required kwonly params)
   * @param localCount total number of local variables (including parameters)
   */
  public FunctionDescriptor(
      String name,
      Location location,
      BytecodeChunk chunk,
      ImmutableList<String> parameterNames,
      boolean hasVarargs,
      boolean hasKwargs,
      int numKeywordOnlyParams,
      ImmutableList<Object> defaultValues,
      int localCount) {
    this.name = name;
    this.location = location;
    this.chunk = chunk;
    this.parameterNames = parameterNames;
    this.hasVarargs = hasVarargs;
    this.hasKwargs = hasKwargs;
    this.numKeywordOnlyParams = numKeywordOnlyParams;
    this.defaultValues = defaultValues;
    this.localCount = localCount;
  }

  public String getName() {
    return name;
  }

  public Location getLocation() {
    return location;
  }

  public BytecodeChunk getChunk() {
    return chunk;
  }

  public ImmutableList<String> getParameterNames() {
    return parameterNames;
  }

  /** Returns true if this function has a *args parameter. */
  public boolean hasVarargs() {
    return hasVarargs;
  }

  /** Returns true if this function has a **kwargs parameter. */
  public boolean hasKwargs() {
    return hasKwargs;
  }

  /**
   * Returns the number of keyword-only parameters.
   * These are parameters that appear after *args or after a bare *.
   */
  public int getNumKeywordOnlyParams() {
    return numKeywordOnlyParams;
  }

  /**
   * Returns the default values for optional parameters.
   * The list corresponds to parameters after the initial required positional params
   * and before *args/**kwargs. Contains MANDATORY sentinel for required keyword-only params.
   */
  public ImmutableList<Object> getDefaultValues() {
    return defaultValues;
  }

  /**
   * Returns the total number of local variables (including parameters).
   * This is used for frame allocation.
   */
  public int getLocalCount() {
    return localCount;
  }
}
