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
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkThread;

/**
 * Loads and executes JVM bytecode generated from Starlark.
 *
 * <p>This class provides a bridge between Starlark bytecode compilation and JVM execution.
 * It handles:
 * <ul>
 *   <li>Dynamic class generation from BytecodeChunk
 *   <li>Class loading via a custom ClassLoader
 *   <li>Caching of compiled classes
 *   <li>Execution with proper StarlarkThread context
 * </ul>
 *
 * <p>Stack trace preservation: Generated classes include LineNumberTable attributes
 * that map JVM bytecode offsets back to Starlark source lines, ensuring meaningful
 * stack traces when exceptions occur.
 */
public final class CompiledStarlarkLoader {

  private static final AtomicLong classCounter = new AtomicLong(0);
  private static final Map<String, Class<?>> classCache = new ConcurrentHashMap<>();

  private CompiledStarlarkLoader() {
    // Utility class
  }

  /**
   * Compiles a BytecodeChunk to JVM bytecode, loads it, and returns the class.
   *
   * @param chunk the Starlark bytecode to compile
   * @return the loaded Java class
   * @throws IOException if bytecode generation fails
   */
  public static Class<?> loadClass(BytecodeChunk chunk) throws IOException {
    String className = generateClassName(chunk.getName());
    return loadClass(chunk, className);
  }

  /**
   * Compiles a BytecodeChunk to JVM bytecode, loads it with the given class name.
   *
   * @param chunk the Starlark bytecode to compile
   * @param className the fully qualified class name
   * @return the loaded Java class
   * @throws IOException if bytecode generation fails
   */
  public static Class<?> loadClass(BytecodeChunk chunk, String className) throws IOException {
    // Check cache first
    Class<?> cached = classCache.get(className);
    if (cached != null) {
      return cached;
    }

    // Generate JVM bytecode
    byte[] classBytes = JvmBytecodeGenerator.generate(chunk, className.replace('.', '/'));

    // Load the class
    StarlarkClassLoader loader = new StarlarkClassLoader();
    Class<?> clazz = loader.defineClass(className, classBytes);

    // Cache the class
    classCache.put(className, clazz);

    return clazz;
  }

  /**
   * Compiles and executes a BytecodeChunk.
   *
   * @param chunk the Starlark bytecode to execute
   * @param thread the Starlark thread context
   * @return the result of execution
   * @throws Exception if execution fails
   */
  public static Object execute(BytecodeChunk chunk, StarlarkThread thread) throws Exception {
    Class<?> clazz = loadClass(chunk);
    return executeClass(clazz, thread);
  }

  /**
   * Executes a previously loaded compiled class.
   *
   * @param clazz the compiled Starlark class
   * @param thread the Starlark thread context
   * @return the result of execution
   * @throws Exception if execution fails
   */
  public static Object executeClass(Class<?> clazz, StarlarkThread thread) throws Exception {
    try {
      // Create instance
      Object instance = clazz.getDeclaredConstructor().newInstance();

      // Find and invoke the execute method
      Method executeMethod = clazz.getMethod("execute", StarlarkThread.class);
      return executeMethod.invoke(instance, thread);

    } catch (java.lang.reflect.InvocationTargetException e) {
      // Unwrap the actual exception
      Throwable cause = e.getCause();
      if (cause instanceof EvalException) {
        throw (EvalException) cause;
      } else if (cause instanceof InterruptedException) {
        throw (InterruptedException) cause;
      } else if (cause instanceof Exception) {
        throw (Exception) cause;
      } else {
        throw new EvalException("Execution failed", cause);
      }
    }
  }

  /**
   * Generates a unique class name for a Starlark function.
   *
   * @param baseName the base name (usually the function/module name)
   * @return a unique fully qualified class name
   */
  public static String generateClassName(String baseName) {
    // Sanitize the base name
    String safe = baseName.replaceAll("[^a-zA-Z0-9_]", "_");
    if (safe.isEmpty() || Character.isDigit(safe.charAt(0))) {
      safe = "Starlark_" + safe;
    }
    long id = classCounter.incrementAndGet();
    return "net.starlark.compiled." + safe + "_" + id;
  }

  /**
   * Clears the class cache.
   */
  public static void clearCache() {
    classCache.clear();
  }

  /**
   * Returns the number of cached classes.
   */
  public static int getCacheSize() {
    return classCache.size();
  }

  /**
   * Writes the generated bytecode to a byte array (for debugging/inspection).
   *
   * @param chunk the Starlark bytecode
   * @param className the class name
   * @return the JVM bytecode bytes
   * @throws IOException if generation fails
   */
  public static byte[] generateBytes(BytecodeChunk chunk, String className) throws IOException {
    return JvmBytecodeGenerator.generate(chunk, className.replace('.', '/'));
  }

  /**
   * Custom ClassLoader for loading generated Starlark classes.
   */
  private static class StarlarkClassLoader extends ClassLoader {

    StarlarkClassLoader() {
      super(StarlarkClassLoader.class.getClassLoader());
    }

    /**
     * Defines a class from bytecode.
     */
    Class<?> defineClass(String name, byte[] bytes) {
      return defineClass(name, bytes, 0, bytes.length);
    }
  }

  /**
   * Holds information about a compiled Starlark program.
   */
  public static class CompiledProgram {
    private final Class<?> clazz;
    private final String className;
    private final BytecodeChunk sourceChunk;

    CompiledProgram(Class<?> clazz, String className, BytecodeChunk sourceChunk) {
      this.clazz = clazz;
      this.className = className;
      this.sourceChunk = sourceChunk;
    }

    public Class<?> getCompiledClass() {
      return clazz;
    }

    public String getClassName() {
      return className;
    }

    public BytecodeChunk getSourceChunk() {
      return sourceChunk;
    }

    /**
     * Executes this compiled program.
     */
    public Object execute(StarlarkThread thread) throws Exception {
      return CompiledStarlarkLoader.executeClass(clazz, thread);
    }
  }

  /**
   * Compiles a BytecodeChunk and returns a CompiledProgram that can be executed multiple times.
   *
   * @param chunk the Starlark bytecode
   * @return the compiled program
   * @throws IOException if compilation fails
   */
  public static CompiledProgram compile(BytecodeChunk chunk) throws IOException {
    String className = generateClassName(chunk.getName());
    Class<?> clazz = loadClass(chunk, className);
    return new CompiledProgram(clazz, className, chunk);
  }
}
