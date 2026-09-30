package com.verygood.security.larky.parser;

import com.google.common.collect.ImmutableSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.compiler.BytecodeCompiler;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.Identifier;
import net.starlark.java.syntax.NodeVisitor;
import net.starlark.java.syntax.Program;
import net.starlark.java.syntax.Resolver;
import net.starlark.java.syntax.StarlarkFile;

/**
 * Process-wide cache of compiled {@link Program}s for Larky's own modules (stdlib, vendor, vgs),
 * so that each evaluation does not re-parse and re-resolve them.
 *
 * <p>Only the compiled program is shared. Every evaluation still executes it into a fresh {@link
 * Module}, so no module values are shared between evaluations. A resolved Program is not mutated
 * by execution, so one can be run by several threads at once.
 *
 * <p>Resolution depends on the environment only through the names a file resolves as PREDECLARED
 * or UNIVERSAL; a cached program is reused only while those still resolve the same way, i.e. its
 * PREDECLARED names are still predeclared and none of its UNIVERSAL names has become predeclared.
 * Other environment entries (such as per-evaluation JSR-223 bindings) do not affect it.
 *
 * <p>Set {@code -Dlarky.programCache.disable=true} to turn the cache off.
 */
final class ProgramCache {

  private ProgramCache() {}

  /** A program's source, parsed on a cache miss. */
  interface Compiler {
    Program compile(StarlarkFile[] parsed) throws EvalException;
  }

  private record Key(
      String path, FileOptions options, StarlarkSemantics semantics, boolean bytecode) {}

  private record Entry(
      Program program, ImmutableSet<String> predeclared, ImmutableSet<String> universal) {

    boolean resolvesTheSameIn(Module module) {
      Map<String, Object> env = module.getPredeclaredBindings();
      for (String name : predeclared) {
        if (!env.containsKey(name)) {
          return false;
        }
      }
      for (String name : universal) {
        if (env.containsKey(name)) {
          return false;
        }
      }
      return true;
    }
  }

  private static final boolean DISABLED = Boolean.getBoolean("larky.programCache.disable");
  private static final ConcurrentHashMap<Key, Entry> CACHE = new ConcurrentHashMap<>();

  /**
   * Returns the program for the trusted module at {@code path}, compiling it with {@code compiler}
   * if no cached program resolves the same way in {@code module}'s environment.
   */
  static Program get(
      String path,
      Module module,
      FileOptions options,
      StarlarkSemantics semantics,
      Compiler compiler)
      throws EvalException {
    if (DISABLED) {
      return compiler.compile(new StarlarkFile[1]);
    }
    Key key = new Key(path, options, semantics, BytecodeCompiler.enabledByDefault());
    Entry cached = CACHE.get(key);
    if (cached != null && cached.resolvesTheSameIn(module)) {
      return cached.program();
    }
    StarlarkFile[] parsed = new StarlarkFile[1];
    Program program = compiler.compile(parsed);
    if (parsed[0] != null) {
      CACHE.put(key, entryFor(program, parsed[0]));
    }
    return program;
  }

  private static Entry entryFor(Program program, StarlarkFile file) {
    ImmutableSet.Builder<String> predeclared = ImmutableSet.builder();
    ImmutableSet.Builder<String> universal = ImmutableSet.builder();
    new NodeVisitor() {
      @Override
      public void visit(Identifier id) {
        Resolver.Binding binding = id.getBinding();
        if (binding == null) {
          return;
        }
        if (binding.getScope() == Resolver.Scope.PREDECLARED) {
          predeclared.add(id.getName());
        } else if (binding.getScope() == Resolver.Scope.UNIVERSAL) {
          universal.add(id.getName());
        }
      }
    }.visit(file);
    return new Entry(program, predeclared.build(), universal.build());
  }

  /** Number of cached programs, for tests. */
  static int size() {
    return CACHE.size();
  }

  /** Empties the cache, for tests. */
  static void clear() {
    CACHE.clear();
  }
}
