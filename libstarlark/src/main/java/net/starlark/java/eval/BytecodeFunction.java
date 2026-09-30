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

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.starlark.java.eval.compiler.BytecodeChunk;
import net.starlark.java.spelling.SpellChecker;
import net.starlark.java.syntax.Location;

/**
 * A BytecodeFunction is a function value created by compiling a Starlark {@code def} statement
 * to bytecode.
 *
 * <p>This class handles function argument processing similar to StarlarkFunction, supporting:
 * <ul>
 *   <li>Positional arguments
 *   <li>Optional parameters with defaults
 *   <li>*args (varargs)
 *   <li>Keyword-only parameters (after * or *args)
 *   <li>**kwargs
 * </ul>
 */
public final class BytecodeFunction implements UserDefinedFunction {

  private final String name;
  private final Location location;
  private final BytecodeChunk chunk;
  private final ImmutableList<String> parameterNames;
  private final boolean hasVarargs;
  private final boolean hasKwargs;
  private final int numKeywordOnlyParams;
  private final Tuple defaultValues;
  private final int localCount;
  private final String filename;

  // Captured globals from function definition
  private Map<String, Object> globals;

  // Captured free variables (cells from enclosing functions).
  // Indexed by LOAD_FREE/STORE_FREE operands.
  private Tuple freevars;

  // Indices of locals that need to be wrapped in Cells at function entry.
  // These are variables shared with nested functions.
  private ImmutableList<Integer> cellIndices = ImmutableList.of();

  /**
   * A Cell is a local variable shared between an inner and an outer function.
   * It wraps a value that can be modified through the closure.
   */
  public static final class Cell implements StarlarkValue {
    public Object x;

    public Cell(Object x) {
      this.x = x;
    }
  }

  /**
   * Sentinel value indicating a required parameter in the defaultValues tuple.
   * This mirrors StarlarkFunction.MANDATORY.
   */
  public static final Object MANDATORY = new Mandatory();

  private static class Mandatory implements StarlarkValue {
    @Override
    public String toString() {
      return "<mandatory>";
    }
  }

  /**
   * Creates a BytecodeFunction with full signature information.
   */
  public BytecodeFunction(
      String name,
      Location location,
      BytecodeChunk chunk,
      ImmutableList<String> parameterNames,
      boolean hasVarargs,
      boolean hasKwargs,
      int numKeywordOnlyParams,
      Tuple defaultValues,
      int localCount,
      String filename) {
    this.name = name;
    this.location = location;
    this.chunk = chunk;
    this.parameterNames = parameterNames;
    this.hasVarargs = hasVarargs;
    this.hasKwargs = hasKwargs;
    this.numKeywordOnlyParams = numKeywordOnlyParams;
    this.defaultValues = defaultValues;
    this.localCount = localCount;
    this.filename = filename;
  }

  /**
   * Legacy constructor for backward compatibility.
   */
  public BytecodeFunction(
      String name,
      Location location,
      BytecodeChunk chunk,
      List<String> parameterNames,
      String filename) {
    this(
        name,
        location,
        chunk,
        ImmutableList.copyOf(parameterNames),
        /*hasVarargs=*/ false,
        /*hasKwargs=*/ false,
        /*numKeywordOnlyParams=*/ 0,
        /*defaultValues=*/ Tuple.empty(),
        /*localCount=*/ parameterNames.size(),
        filename);
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public Location getLocation() {
    return location;
  }

  public BytecodeChunk getChunk() {
    return chunk;
  }

  @Override
  public ImmutableList<String> getParameterNames() {
    return parameterNames;
  }

  @Override
  public boolean hasVarargs() {
    return hasVarargs;
  }

  @Override
  public boolean hasKwargs() {
    return hasKwargs;
  }

  @Override
  public int numKeywordOnlyParams() {
    return numKeywordOnlyParams;
  }

  /**
   * Returns the default value of the ith parameter, or null if the parameter
   * is required or is *args/**kwargs.
   */
  @Override
  public Object getDefaultValue(int i) {
    if (i < 0 || i >= parameterNames.size()) {
      throw new IndexOutOfBoundsException();
    }
    int nparams = parameterNames.size() - (hasKwargs ? 1 : 0) - (hasVarargs ? 1 : 0);
    int prefix = nparams - defaultValues.size();
    if (i < prefix) {
      return null; // implicit prefix of mandatory parameters
    }
    if (i < nparams) {
      Object v = defaultValues.get(i - prefix);
      return v == MANDATORY ? null : v;
    }
    return null; // *args or **kwargs
  }

  public int getLocalCount() {
    return localCount;
  }

  public String getFilename() {
    return filename;
  }

  public void setGlobals(Map<String, Object> globals) {
    this.globals = globals;
  }

  public Map<String, Object> getGlobals() {
    return globals;
  }

  public void setFreevars(Tuple freevars) {
    this.freevars = freevars;
  }

  public Tuple getFreevars() {
    return freevars != null ? freevars : Tuple.empty();
  }

  /**
   * Gets a free variable Cell by index.
   * Used by nested functions to access captured variables.
   */
  public Cell getFreeVar(int index) {
    return (Cell) freevars.get(index);
  }

  public void setCellIndices(ImmutableList<Integer> cellIndices) {
    this.cellIndices = cellIndices;
  }

  public ImmutableList<Integer> getCellIndices() {
    return cellIndices;
  }

  @Override
  public Object call(StarlarkThread thread, Tuple args, Dict<String, Object> kwargs)
      throws EvalException, InterruptedException {
    // Convert Tuple and Dict to positional/named arrays for processArgs
    Object[] positional = new Object[args.size()];
    for (int i = 0; i < args.size(); i++) {
      positional[i] = args.get(i);
    }

    // Build named arguments array: [name, value, name, value, ...]
    Object[] named = new Object[kwargs.size() * 2];
    int idx = 0;
    for (Map.Entry<String, Object> entry : kwargs.entrySet()) {
      named[idx++] = entry.getKey();
      named[idx++] = entry.getValue();
    }

    return fastcall(thread, positional, named);
  }

  @Override
  public Object fastcall(StarlarkThread thread, Object[] positional, Object[] named)
      throws EvalException, InterruptedException {
    // Check for disallowed recursion
    if (!thread.isRecursionAllowed() && thread.isRecursiveCall(this)) {
      throw Starlark.errorf("function '%s' called recursively", getName());
    }

    // Compute the effective parameter values
    Object[] locals = processArgs(thread.mutability(), positional, named);

    // Spill indicated locals to cells.
    // This wraps locals that are shared with nested functions in Cell objects.
    for (int index : cellIndices) {
      locals[index] = new Cell(locals[index]);
    }

    // Set the frame's locals so debugging APIs can access them
    // Note: Starlark.fastcall already pushes us onto the call stack,
    // so we just need to set the locals on the current frame.
    StarlarkThread.Frame fr = thread.frame(0);
    fr.locals = locals;

    // Execute the function body bytecode with the processed locals and captured free variables
    return BytecodeInterpreter.executeWithLocals(
        chunk, thread, locals, globals, filename, getFreevars());
  }

  /**
   * Processes positional and named arguments to produce local variable values.
   * This is modeled after StarlarkFunction.processArgs.
   */
  private Object[] processArgs(Mutability mu, Object[] positional, Object[] named)
      throws EvalException {

    // General schema of a function:
    //   def f(p1, p2=dp2, p3=dp3, *args, k1, k2=dk2, k3, **kwargs)
    //
    // Parameters p1..p3 are non-kwonly (can be positional)
    // Parameters k1..k3 are kwonly (must be by name)
    // *args collects surplus positional arguments
    // **kwargs collects surplus keyword arguments

    Object[] locals = new Object[localCount];

    // nparams is the number of ordinary parameters (excluding *args/**kwargs)
    int nparams = parameterNames.size() - (hasKwargs ? 1 : 0) - (hasVarargs ? 1 : 0);

    // numPositionalParams is the number of non-kwonly parameters
    int numPositionalParams = nparams - numKeywordOnlyParams;

    // Too many positional args?
    int n = positional.length;
    if (n > numPositionalParams) {
      if (!hasVarargs) {
        if (numPositionalParams > 0) {
          throw Starlark.errorf(
              "%s() accepts no more than %d positional argument%s but got %d",
              name, numPositionalParams, plural(numPositionalParams), n);
        } else {
          throw Starlark.errorf(
              "%s() does not accept positional arguments, but got %d", name, n);
        }
      }
      n = numPositionalParams;
    }

    // Bind positional arguments to non-kwonly parameters
    for (int i = 0; i < n; i++) {
      locals[i] = positional[i];
    }

    // Bind surplus positional arguments to *args parameter
    if (hasVarargs) {
      locals[nparams] = Tuple.wrap(Arrays.copyOfRange(positional, n, positional.length));
    }

    List<String> unexpected = null;

    // Named arguments
    Dict<String, Object> kwargsDict = null;
    if (hasKwargs) {
      kwargsDict = Dict.of(mu);
      locals[parameterNames.size() - 1] = kwargsDict;
    }

    for (int i = 0; i < named.length; i += 2) {
      String keyword = (String) named[i];
      Object value = named[i + 1];
      int pos = parameterNames.indexOf(keyword);
      if (0 <= pos && pos < nparams) {
        // keyword is the name of a named parameter
        if (locals[pos] != null) {
          throw Starlark.errorf("%s() got multiple values for parameter '%s'", name, keyword);
        }
        locals[pos] = value;
      } else if (kwargsDict != null) {
        // residual keyword argument
        int sz = kwargsDict.size();
        kwargsDict.putEntry(keyword, value);
        if (kwargsDict.size() == sz) {
          throw Starlark.errorf(
              "%s() got multiple values for keyword argument '%s'", name, keyword);
        }
      } else {
        // unexpected keyword argument
        if (unexpected == null) {
          unexpected = new ArrayList<>();
        }
        unexpected.add(keyword);
      }
    }

    if (unexpected != null) {
      // Give a spelling hint if there is exactly one
      throw Starlark.errorf(
          "%s() got unexpected keyword argument%s: %s%s",
          name,
          plural(unexpected.size()),
          Joiner.on(", ").join(unexpected),
          unexpected.size() == 1
              ? SpellChecker.didYouMean(unexpected.get(0), parameterNames.subList(0, nparams))
              : "");
    }

    // Apply defaults and report errors for missing required arguments
    int m = nparams - defaultValues.size(); // first default
    List<String> missingPositional = null;
    List<String> missingKwonly = null;

    for (int i = n; i < nparams; i++) {
      // provided?
      if (locals[i] != null) {
        continue;
      }

      // optional?
      if (i >= m) {
        Object dflt = defaultValues.get(i - m);
        if (dflt != MANDATORY) {
          locals[i] = dflt;
          continue;
        }
      }

      // missing
      if (i < numPositionalParams) {
        if (missingPositional == null) {
          missingPositional = new ArrayList<>();
        }
        missingPositional.add(parameterNames.get(i));
      } else {
        if (missingKwonly == null) {
          missingKwonly = new ArrayList<>();
        }
        missingKwonly.add(parameterNames.get(i));
      }
    }

    if (missingPositional != null) {
      throw Starlark.errorf(
          "%s() missing %d required positional argument%s: %s",
          name,
          missingPositional.size(),
          plural(missingPositional.size()),
          Joiner.on(", ").join(missingPositional));
    }

    if (missingKwonly != null) {
      throw Starlark.errorf(
          "%s() missing %d required keyword-only argument%s: %s",
          name,
          missingKwonly.size(),
          plural(missingKwonly.size()),
          Joiner.on(", ").join(missingKwonly));
    }

    return locals;
  }

  private static String plural(int n) {
    return n == 1 ? "" : "s";
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

  @Override
  public boolean isImmutable() {
    return true;
  }
}
