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
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.starlark.java.eval.Module;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.Program;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.SyntaxError;

/**
 * A compiler that can generate output for multiple bytecode targets simultaneously.
 *
 * <p>This class provides a unified interface for compiling Starlark source code
 * to multiple backend formats (interpreter bytecode, JVM, WebAssembly) in a single
 * compilation pass.
 *
 * <p>Example usage:
 * <pre>{@code
 * MultiTargetCompiler compiler = new MultiTargetCompiler.Builder()
 *     .addTarget(BytecodeTarget.JVM)
 *     .addTarget(BytecodeTarget.WASM)
 *     .setSourceFile("script.star")
 *     .setClassName("com/example/Script")
 *     .build();
 *
 * CompilationResult result = compiler.compile("x = 1 + 2");
 *
 * byte[] jvmBytes = result.getOutput(BytecodeTarget.JVM);
 * String wat = result.getTextOutput(BytecodeTarget.WASM);
 * }</pre>
 */
public class MultiTargetCompiler {

  private final Set<BytecodeTarget> targets;
  private final String sourceFile;
  private final String className;

  private MultiTargetCompiler(Builder builder) {
    this.targets = Collections.unmodifiableSet(builder.targets);
    this.sourceFile = builder.sourceFile;
    this.className = builder.className;
  }

  /**
   * Compiles the given source code to all configured targets.
   *
   * @param source the Starlark source code
   * @return compilation result with outputs for each target
   * @throws SyntaxError.Exception if parsing fails
   * @throws IOException if code generation fails
   */
  public CompilationResult compile(String source) throws SyntaxError.Exception, IOException {
    // Parse source
    ParserInput input = ParserInput.fromString(source, sourceFile);
    StarlarkFile file = StarlarkFile.parse(input, FileOptions.DEFAULT);

    if (!file.ok()) {
      throw new SyntaxError.Exception(file.errors());
    }

    // Compile to bytecode
    Program program = Program.compileFile(file, Module.create());
    BytecodeChunk bytecode = program.getBytecode();

    if (bytecode == null) {
      throw new IOException("Bytecode compilation failed");
    }

    // Generate output for each target
    Map<BytecodeTarget, byte[]> outputs = new EnumMap<>(BytecodeTarget.class);
    Map<BytecodeTarget, String> textOutputs = new EnumMap<>(BytecodeTarget.class);

    for (BytecodeTarget target : targets) {
      BytecodeBackend backend = BytecodeBackend.forTarget(target);
      byte[] output = backend.generate(bytecode, className, sourceFile);
      outputs.put(target, output);

      if (backend.isTextOutput()) {
        String text = backend.generateText(bytecode, className);
        textOutputs.put(target, text);
      }
    }

    return new CompilationResult(bytecode, outputs, textOutputs);
  }

  /**
   * Returns the configured targets.
   */
  public Set<BytecodeTarget> getTargets() {
    return targets;
  }

  /**
   * Result of a multi-target compilation.
   */
  public static class CompilationResult {
    private final BytecodeChunk bytecode;
    private final Map<BytecodeTarget, byte[]> outputs;
    private final Map<BytecodeTarget, String> textOutputs;

    CompilationResult(
        BytecodeChunk bytecode,
        Map<BytecodeTarget, byte[]> outputs,
        Map<BytecodeTarget, String> textOutputs) {
      this.bytecode = bytecode;
      this.outputs = Collections.unmodifiableMap(outputs);
      this.textOutputs = Collections.unmodifiableMap(textOutputs);
    }

    /**
     * Returns the intermediate bytecode representation.
     */
    public BytecodeChunk getBytecode() {
      return bytecode;
    }

    /**
     * Returns the binary output for the given target, or null if not compiled.
     */
    @Nullable
    public byte[] getOutput(BytecodeTarget target) {
      return outputs.get(target);
    }

    /**
     * Returns the text output for the given target (for text-based backends like WASM).
     */
    @Nullable
    public String getTextOutput(BytecodeTarget target) {
      return textOutputs.get(target);
    }

    /**
     * Returns all compiled outputs.
     */
    public Map<BytecodeTarget, byte[]> getAllOutputs() {
      return outputs;
    }

    /**
     * Returns true if the given target was compiled.
     */
    public boolean hasOutput(BytecodeTarget target) {
      return outputs.containsKey(target);
    }
  }

  /**
   * Builder for creating MultiTargetCompiler instances.
   */
  public static class Builder {
    private final Set<BytecodeTarget> targets = EnumSet.noneOf(BytecodeTarget.class);
    private String sourceFile = "script.star";
    private String className = "net/starlark/compiled/Script";

    /**
     * Adds a compilation target.
     */
    public Builder addTarget(BytecodeTarget target) {
      targets.add(target);
      return this;
    }

    /**
     * Adds multiple compilation targets.
     */
    public Builder addTargets(BytecodeTarget... targets) {
      for (BytecodeTarget target : targets) {
        this.targets.add(target);
      }
      return this;
    }

    /**
     * Sets all three targets (interpreter, JVM, WASM).
     */
    public Builder allTargets() {
      targets.add(BytecodeTarget.INTERPRETER);
      targets.add(BytecodeTarget.JVM);
      targets.add(BytecodeTarget.WASM);
      return this;
    }

    /**
     * Sets the source file name for debugging.
     */
    public Builder setSourceFile(String sourceFile) {
      this.sourceFile = sourceFile;
      return this;
    }

    /**
     * Sets the class/module name for generated output.
     */
    public Builder setClassName(String className) {
      this.className = className;
      return this;
    }

    /**
     * Builds the compiler.
     */
    public MultiTargetCompiler build() {
      if (targets.isEmpty()) {
        targets.add(BytecodeTarget.INTERPRETER);
      }
      return new MultiTargetCompiler(this);
    }
  }
}
