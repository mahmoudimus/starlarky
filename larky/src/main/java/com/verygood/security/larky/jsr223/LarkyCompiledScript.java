package com.verygood.security.larky.jsr223;

import com.google.common.io.CharStreams;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

import com.verygood.security.larky.parser.DefaultLarkyInterpreter;
import com.verygood.security.larky.parser.InMemMapBackedStarFile;
import com.verygood.security.larky.parser.LarkyScript;
import com.verygood.security.larky.parser.ParsedStarFile;
import com.verygood.security.larky.parser.StarFile;

import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkEvalWrapper;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.BytecodeInterpreter;
import net.starlark.java.eval.compiler.BytecodeBackend;
import net.starlark.java.eval.compiler.BytecodeChunk;
import net.starlark.java.eval.compiler.BytecodeTarget;
import net.starlark.java.eval.compiler.CompiledStarlarkLoader;
import net.starlark.java.eval.compiler.JvmBytecodeGenerator;
import net.starlark.java.eval.compiler.MultiTargetCompiler;
import net.starlark.java.eval.compiler.WasmGenerator;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.Program;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.SyntaxError;

import javax.annotation.Nullable;
import javax.script.Bindings;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;


/**
 * A compiled Starlark script that can be executed multiple times efficiently.
 *
 * <p>This class implements JSR-223's {@link CompiledScript} interface and provides
 * true compilation support using the Starlark bytecode compiler. Scripts are compiled
 * to bytecode on the first compilation, and the bytecode is cached for subsequent
 * executions.
 *
 * <p>Compilation modes:
 * <ul>
 *   <li><b>INTERPRETED</b>: Traditional tree-walking interpretation (fallback)
 *   <li><b>BYTECODE</b>: Execute via bytecode interpreter (default)
 *   <li><b>JVM</b>: Compile to JVM bytecode for native execution
 * </ul>
 *
 * <p>Stack trace preservation: All modes preserve stack traces with proper source
 * file and line number information for debugging.
 */
public class LarkyCompiledScript extends CompiledScript {

  /**
   * Compilation mode for script execution.
   */
  public enum CompilationMode {
    /** Use traditional tree-walking interpreter */
    INTERPRETED,
    /** Use bytecode interpreter (default) */
    BYTECODE,
    /** Compile to JVM bytecode for native execution */
    JVM
  }

  private static final LarkyScript.StarlarkMode LARKY_MODE = LarkyScript.StarlarkMode.STRICT;
  private static final String DEFAULT_SCRIPT_NAME = "larky.star";

  private final LarkyScriptEngine engine;
  private final CompilationMode compilationMode;

  // Cached compilation results
  @Nullable private String cachedSource;
  @Nullable private String cachedScriptName;
  @Nullable private BytecodeChunk cachedBytecode;
  @Nullable private CompiledStarlarkLoader.CompiledProgram cachedJvmProgram;
  @Nullable private Program cachedProgram;

  /**
   * Construct a {@link LarkyCompiledScript} with default bytecode compilation.
   *
   * @param engine the {@link LarkyScriptEngine} that compiled this script
   */
  LarkyCompiledScript(LarkyScriptEngine engine) {
    this(engine, CompilationMode.BYTECODE);
  }

  /**
   * Construct a {@link LarkyCompiledScript} with specified compilation mode.
   *
   * @param engine the {@link LarkyScriptEngine} that compiled this script
   * @param mode the compilation mode to use
   */
  LarkyCompiledScript(LarkyScriptEngine engine, CompilationMode mode) {
    this.engine = engine;
    this.compilationMode = mode;
  }

  @Override
  public ScriptEngine getEngine() {
    return engine;
  }

  /**
   * Pre-compiles the script from the given source.
   *
   * <p>This method parses and compiles the script to bytecode, caching the result
   * for efficient repeated execution via {@link #eval(ScriptContext)}.
   *
   * @param source the script source code
   * @param scriptName the script name for error reporting
   * @throws LarkyEvaluationScriptException if compilation fails
   */
  public void compile(String source, String scriptName) throws LarkyEvaluationScriptException {
    try {
      this.cachedSource = source;
      this.cachedScriptName = scriptName;

      // Parse the script
      ParserInput input = ParserInput.fromString(source, scriptName);
      StarlarkFile file = StarlarkFile.parse(input, FileOptions.DEFAULT);

      if (!file.ok()) {
        throw new SyntaxError.Exception(file.errors());
      }

      // Resolve and compile
      this.cachedProgram = Program.compileFile(file, Module.create());

      // Get bytecode from program
      this.cachedBytecode = cachedProgram.getBytecode();

      // If JVM mode, also compile to JVM bytecode
      if (compilationMode == CompilationMode.JVM && cachedBytecode != null) {
        this.cachedJvmProgram = CompiledStarlarkLoader.compile(cachedBytecode);
      }

    } catch (SyntaxError.Exception | IOException e) {
      throw LarkyEvaluationScriptException.of(e);
    }
  }

  /**
   * Returns true if this script has been compiled and cached.
   */
  public boolean isCompiled() {
    return cachedBytecode != null || cachedProgram != null;
  }

  /**
   * Returns the compilation mode.
   */
  public CompilationMode getCompilationMode() {
    return compilationMode;
  }

  /**
   * Returns the cached bytecode, or null if not compiled.
   */
  @Nullable
  public BytecodeChunk getBytecode() {
    return cachedBytecode;
  }

  @Override
  public Object eval(ScriptContext context) throws LarkyEvaluationScriptException {
    Bindings globalBindings = context.getBindings(ScriptContext.GLOBAL_SCOPE);
    Bindings engineBindings = context.getBindings(ScriptContext.ENGINE_SCOPE);

    // If we have cached bytecode and not in INTERPRETED mode, use it
    if (cachedBytecode != null && compilationMode != CompilationMode.INTERPRETED) {
      return evalWithBytecode(context, globalBindings, engineBindings);
    }

    // Fall back to traditional interpretation
    return evalInterpreted(context, globalBindings, engineBindings);
  }

  /**
   * Evaluates using the bytecode interpreter or JVM-compiled code.
   */
  private Object evalWithBytecode(ScriptContext context, Bindings globalBindings, Bindings engineBindings)
      throws LarkyEvaluationScriptException {
    try {
      // Create execution environment
      Mutability mutability = Mutability.create("larky");
      StarlarkThread thread = new StarlarkThread(mutability, StarlarkSemantics.DEFAULT);

      // Set up globals from bindings
      Map<String, Object> globals = new HashMap<>();
      if (globalBindings != null) {
        globals.putAll(globalBindings);
      }
      if (engineBindings != null) {
        globals.putAll(engineBindings);
      }

      Object result;

      if (compilationMode == CompilationMode.JVM && cachedJvmProgram != null) {
        // Execute JVM-compiled code
        result = cachedJvmProgram.execute(thread);
      } else {
        // Execute via bytecode interpreter
        result = BytecodeInterpreter.execute(
            cachedBytecode,
            thread,
            globals,
            cachedScriptName);
      }

      // Update bindings with any new globals
      // Note: In bytecode mode, we'd need to track global modifications
      // For now, this is a simplified implementation

      mutability.freeze();
      return result;

    } catch (EvalException | InterruptedException e) {
      throw LarkyEvaluationScriptException.of(e);
    } catch (Exception e) {
      throw LarkyEvaluationScriptException.of(e);
    }
  }

  /**
   * Evaluates using traditional tree-walking interpretation.
   */
  private Object evalInterpreted(ScriptContext context, Bindings globalBindings, Bindings engineBindings)
      throws LarkyEvaluationScriptException {
    ParsedStarFile result;

    try (Reader reader = context.getReader()) {
      String source = cachedSource != null ? cachedSource : CharStreams.toString(reader);
      String scriptName = cachedScriptName != null ? cachedScriptName : DEFAULT_SCRIPT_NAME;

      final StarFile script = InMemMapBackedStarFile.createStarFile(scriptName, source);
      final DefaultLarkyInterpreter larkyInterpreter = new DefaultLarkyInterpreter(LARKY_MODE, globalBindings, engineBindings);
      result = larkyInterpreter.evaluate(script, context.getWriter());
    } catch (IOException | StarlarkEvalWrapper.Exc.RuntimeEvalException | Starlark.UncheckedEvalException |
             EvalException e) {
      throw LarkyEvaluationScriptException.of(e);
    }
    setBindingsValue(globalBindings, engineBindings, result.getGlobals());
    return result;
  }

  private void setBindingsValue(Bindings globalBindings, Bindings engineBindings, Map<String, Object> moduleGlobals) {
    for (Map.Entry<String, Object> entry : moduleGlobals.entrySet()) {
      String name = entry.getKey();
      Object value = entry.getValue();
      if (globalBindings != null && globalBindings.containsKey(name)) {
        globalBindings.put(name, value);
      }
      // by default, if defined values are not globals, they belong in engine binding scope
      // to allow for multiple evals() of an instance
      // TODO(mahmoudimus): is this threadsafe?
      else if (engineBindings != null) {
        engineBindings.put(name, value);
      }
    }
  }

  /**
   * Returns the generated JVM bytecode for this script, if available.
   *
   * @return JVM class file bytes, or null if not compiled to JVM
   * @throws IOException if bytecode generation fails
   */
  @Nullable
  public byte[] getJvmBytecode() throws IOException {
    if (cachedBytecode == null) {
      return null;
    }
    String className = "com/verygood/security/larky/compiled/"
        + cachedScriptName.replace(".star", "").replace(".", "_");
    return JvmBytecodeGenerator.generate(cachedBytecode, className, cachedScriptName);
  }

  /**
   * Returns the generated WebAssembly text format (WAT) for this script.
   *
   * @return WAT string, or null if not compiled
   */
  @Nullable
  public String getWasmText() {
    if (cachedBytecode == null) {
      return null;
    }
    return WasmGenerator.generate(cachedBytecode);
  }

  /**
   * Returns the generated WebAssembly text format as bytes (UTF-8 encoded).
   *
   * @return WAT bytes, or null if not compiled
   */
  @Nullable
  public byte[] getWasmBytes() {
    String wat = getWasmText();
    if (wat == null) {
      return null;
    }
    return wat.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  /**
   * Generates output for a specific bytecode target.
   *
   * @param target the compilation target
   * @return the generated bytes for the target
   * @throws IOException if generation fails
   */
  @Nullable
  public byte[] getOutputForTarget(BytecodeTarget target) throws IOException {
    if (cachedBytecode == null) {
      return null;
    }

    BytecodeBackend backend = BytecodeBackend.forTarget(target);
    String className = "com/verygood/security/larky/compiled/"
        + cachedScriptName.replace(".star", "").replace(".", "_");
    return backend.generate(cachedBytecode, className, cachedScriptName);
  }

  /**
   * Returns true if this script supports the given target.
   */
  public boolean supportsTarget(BytecodeTarget target) {
    return cachedBytecode != null;
  }
}
