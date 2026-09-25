// Copyright 2020 The Bazel Authors. All rights reserved.
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
package net.starlark.java.syntax;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import javax.annotation.Nullable;
import net.starlark.java.eval.compiler.BytecodeChunk;
import net.starlark.java.eval.compiler.BytecodeCompiler;

/**
 * An opaque, executable representation of a valid Starlark program. Programs may
 * [eventually---TODO(adonovan)] be efficiently serialized and deserialized without parsing and
 * recompiling.
 */
public final class Program {

  private final Resolver.Function body;
  private final ImmutableList<String> loads;
  private final ImmutableList<Location> loadLocations;

  // Compiled bytecode representation (may be null if compilation is disabled)
  @Nullable private final BytecodeChunk bytecode;

  private Program(
      Resolver.Function body, ImmutableList<String> loads, ImmutableList<Location> loadLocations) {
    // Bytecode compilation disabled by default until all features implemented
    // Set system property -Dstarlark.bytecode=true to enable
    this(body, loads, loadLocations, Boolean.getBoolean("starlark.bytecode"));
  }

  private Program(
      Resolver.Function body,
      ImmutableList<String> loads,
      ImmutableList<Location> loadLocations,
      boolean enableBytecode) {
    Preconditions.checkArgument(
        loads.size() == loadLocations.size(), "each load must have a corresponding location");

    this.body = body;
    this.loads = loads;
    this.loadLocations = loadLocations;

    // Compile to bytecode if enabled
    BytecodeChunk compiledBytecode = null;
    if (enableBytecode) {
      try {
        compiledBytecode = BytecodeCompiler.compileFunction(body);
      } catch (Exception e) {
        // If bytecode compilation fails, fall back to interpreted mode
        // This ensures backward compatibility
        System.err.println("Warning: Bytecode compilation failed: " + e.getMessage());
        compiledBytecode = null;
      }
    }
    this.bytecode = compiledBytecode;
  }

  // TODO(adonovan): eliminate once Eval no longer needs access to syntax.
  public Resolver.Function getResolvedFunction() {
    return body;
  }

  /**
   * Returns the compiled bytecode for this program, or null if bytecode compilation
   * is disabled or failed.
   */
  @Nullable
  public BytecodeChunk getBytecode() {
    return bytecode;
  }

  /**
   * Returns true if this program has compiled bytecode available.
   */
  public boolean hasBytecode() {
    return bytecode != null;
  }

  /** Returns the file name of this compiled program. */
  public String getFilename() {
    return body.getLocation().file();
  }

  /** Returns the list of load strings of this compiled program, in source order. */
  public ImmutableList<String> getLoads() {
    return loads;
  }

  /*** Returns the location of the ith load (see {@link #getLoads}). */
  public Location getLoadLocation(int i) {
    return loadLocations.get(i);
  }

  /**
   * Resolves a file syntax tree in the specified environment and compiles it to a Program. This
   * operation mutates the syntax tree, both by resolving identifiers and recording local variables,
   * and in case of error, by appending to {@code file.errors()}.
   *
   * @throws SyntaxError.Exception in case of resolution error, or if the syntax tree already
   *     contained syntax scan/parse errors. Resolution errors are added to {@code file.errors()}.
   */
  public static Program compileFile(StarlarkFile file, Resolver.Module env)
      throws SyntaxError.Exception {
    Resolver.resolveFile(file, env);
    if (!file.ok()) {
      throw new SyntaxError.Exception(file.errors());
    }

    // Extract load statements.
    ImmutableList.Builder<String> loads = ImmutableList.builder();
    ImmutableList.Builder<Location> loadLocations = ImmutableList.builder();
    for (Statement stmt : file.getStatements()) {
      if (stmt instanceof LoadStatement) {
        LoadStatement load = (LoadStatement) stmt;
        String module = load.getImport().getValue();
        loads.add(module);
        loadLocations.add(load.getImport().getLocation());
      }
    }

    return new Program(file.getResolvedFunction(), loads.build(), loadLocations.build());
  }

  /**
   * Resolves an expression syntax tree in the specified environment and compiles it to a Program.
   * This operation mutates the syntax tree. The {@code options} must match those used when parsing
   * expression.
   *
   * @throws SyntaxError.Exception in case of resolution error.
   */
  public static Program compileExpr(Expression expr, Resolver.Module module, FileOptions options)
      throws SyntaxError.Exception {
    Resolver.Function body = Resolver.resolveExpr(expr, module, options);
    return new Program(body, /*loads=*/ ImmutableList.of(), /*loadLocations=*/ ImmutableList.of());
  }
}
