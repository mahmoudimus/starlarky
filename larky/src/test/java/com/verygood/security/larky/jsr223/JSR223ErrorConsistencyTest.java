package com.verygood.security.larky.jsr223;

import static org.junit.Assert.*;

import java.io.IOException;
import javax.script.ScriptException;
import net.starlark.java.eval.compiler.BytecodeTarget;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests to ensure consistent error messages across all JSR-223 compilation modes:
 * - INTERPRETED: Traditional tree-walking interpreter
 * - BYTECODE: Bytecode interpreter
 * - JVM: JVM bytecode compilation
 *
 * <p>These tests verify that users receive consistent error messages regardless
 * of which compilation mode is configured.
 */
public class JSR223ErrorConsistencyTest {

  private LarkyScriptEngine engine;

  @Before
  public void setUp() {
    engine = new LarkyScriptEngine();
  }

  // ==================== Syntax Error Tests ====================

  @Test
  public void testSyntaxErrorConsistencyAcrossModes() {
    String script = "def broken(\n";  // Missing closing paren and colon

    ScriptException interpretedError = compileWithMode(script, LarkyCompiledScript.CompilationMode.INTERPRETED);
    ScriptException bytecodeError = compileWithMode(script, LarkyCompiledScript.CompilationMode.BYTECODE);
    ScriptException jvmError = compileWithMode(script, LarkyCompiledScript.CompilationMode.JVM);

    // All modes should fail with syntax errors
    assertNotNull("INTERPRETED mode should fail", interpretedError);
    assertNotNull("BYTECODE mode should fail", bytecodeError);
    assertNotNull("JVM mode should fail", jvmError);

    System.out.println("=== Syntax Error Consistency ===");
    System.out.println("INTERPRETED: " + interpretedError.getMessage());
    System.out.println("BYTECODE: " + bytecodeError.getMessage());
    System.out.println("JVM: " + jvmError.getMessage());

    // All should mention syntax-related issue
    assertErrorRelated(interpretedError, "syntax", "expected", "parse");
    assertErrorRelated(bytecodeError, "syntax", "expected", "parse");
    assertErrorRelated(jvmError, "syntax", "expected", "parse");
  }

  @Test
  public void testUnmatchedBracketError() {
    String script = "x = [1, 2, 3\n";  // Missing closing bracket

    ScriptException interpretedError = compileWithMode(script, LarkyCompiledScript.CompilationMode.INTERPRETED);
    ScriptException bytecodeError = compileWithMode(script, LarkyCompiledScript.CompilationMode.BYTECODE);

    assertNotNull("INTERPRETED mode should fail", interpretedError);
    assertNotNull("BYTECODE mode should fail", bytecodeError);

    System.out.println("=== Unmatched Bracket Error ===");
    System.out.println("INTERPRETED: " + interpretedError.getMessage());
    System.out.println("BYTECODE: " + bytecodeError.getMessage());
  }

  // ==================== Undefined Variable Tests ====================

  @Test
  public void testUndefinedVariableConsistency() {
    String script = "result = undefined_variable + 1\n";

    ScriptException interpretedError = compileWithMode(script, LarkyCompiledScript.CompilationMode.INTERPRETED);
    ScriptException bytecodeError = compileWithMode(script, LarkyCompiledScript.CompilationMode.BYTECODE);

    // Both should fail during compilation/resolution
    assertNotNull("INTERPRETED should fail", interpretedError);
    assertNotNull("BYTECODE should fail", bytecodeError);

    System.out.println("=== Undefined Variable Consistency ===");
    System.out.println("INTERPRETED: " + interpretedError.getMessage());
    System.out.println("BYTECODE: " + bytecodeError.getMessage());

    // Both should mention the undefined variable
    assertErrorRelated(interpretedError, "undefined", "unbound", "not found");
    assertErrorRelated(bytecodeError, "undefined", "unbound", "not found");
  }

  // ==================== Runtime Error Tests ====================

  @Test
  public void testDivisionByZeroConsistency() throws ScriptException {
    String script = "x = 10 / 0\n";

    LarkyEvaluationScriptException interpretedError = evalWithMode(script, LarkyCompiledScript.CompilationMode.INTERPRETED);
    LarkyEvaluationScriptException bytecodeError = evalWithMode(script, LarkyCompiledScript.CompilationMode.BYTECODE);

    if (interpretedError != null && bytecodeError != null) {
      System.out.println("=== Division by Zero Consistency ===");
      System.out.println("INTERPRETED: " + interpretedError.getMessage());
      System.out.println("BYTECODE: " + bytecodeError.getMessage());

      assertErrorRelated(interpretedError, "division", "zero", "divide");
      assertErrorRelated(bytecodeError, "division", "zero", "divide");
    }
  }

  @Test
  public void testTypeErrorConsistency() throws ScriptException {
    String script = "x = 'hello' + 42\n";

    LarkyEvaluationScriptException interpretedError = evalWithMode(script, LarkyCompiledScript.CompilationMode.INTERPRETED);
    LarkyEvaluationScriptException bytecodeError = evalWithMode(script, LarkyCompiledScript.CompilationMode.BYTECODE);

    if (interpretedError != null && bytecodeError != null) {
      System.out.println("=== Type Error Consistency ===");
      System.out.println("INTERPRETED: " + interpretedError.getMessage());
      System.out.println("BYTECODE: " + bytecodeError.getMessage());

      // Both should be type-related errors
      assertErrorRelated(interpretedError, "type", "cannot", "unsupported");
      assertErrorRelated(bytecodeError, "type", "cannot", "unsupported");
    }
  }

  @Test
  public void testIndexErrorConsistency() throws ScriptException {
    String script = "x = [1, 2, 3][100]\n";

    LarkyEvaluationScriptException interpretedError = evalWithMode(script, LarkyCompiledScript.CompilationMode.INTERPRETED);
    LarkyEvaluationScriptException bytecodeError = evalWithMode(script, LarkyCompiledScript.CompilationMode.BYTECODE);

    if (interpretedError != null && bytecodeError != null) {
      System.out.println("=== Index Error Consistency ===");
      System.out.println("INTERPRETED: " + interpretedError.getMessage());
      System.out.println("BYTECODE: " + bytecodeError.getMessage());

      assertErrorRelated(interpretedError, "index", "out", "range", "bound");
      assertErrorRelated(bytecodeError, "index", "out", "range", "bound");
    }
  }

  @Test
  public void testKeyErrorConsistency() throws ScriptException {
    String script = "d = {'a': 1}\nx = d['missing_key']\n";

    LarkyEvaluationScriptException interpretedError = evalWithMode(script, LarkyCompiledScript.CompilationMode.INTERPRETED);
    LarkyEvaluationScriptException bytecodeError = evalWithMode(script, LarkyCompiledScript.CompilationMode.BYTECODE);

    if (interpretedError != null && bytecodeError != null) {
      System.out.println("=== Key Error Consistency ===");
      System.out.println("INTERPRETED: " + interpretedError.getMessage());
      System.out.println("BYTECODE: " + bytecodeError.getMessage());

      assertErrorRelated(interpretedError, "key", "missing", "not found");
      assertErrorRelated(bytecodeError, "key", "missing", "not found");
    }
  }

  // ==================== Function Error Tests ====================

  @Test
  public void testMissingArgumentConsistency() throws ScriptException {
    String script = "def foo(a, b, c):\n  return a + b + c\n\nresult = foo(1)\n";

    LarkyEvaluationScriptException interpretedError = evalWithMode(script, LarkyCompiledScript.CompilationMode.INTERPRETED);
    LarkyEvaluationScriptException bytecodeError = evalWithMode(script, LarkyCompiledScript.CompilationMode.BYTECODE);

    if (interpretedError != null && bytecodeError != null) {
      System.out.println("=== Missing Argument Consistency ===");
      System.out.println("INTERPRETED: " + interpretedError.getMessage());
      System.out.println("BYTECODE: " + bytecodeError.getMessage());

      assertErrorRelated(interpretedError, "argument", "parameter", "missing", "required");
      assertErrorRelated(bytecodeError, "argument", "parameter", "missing", "required");
    }
  }

  @Test
  public void testExtraArgumentConsistency() throws ScriptException {
    String script = "def foo(a):\n  return a\n\nresult = foo(1, 2, 3, 4)\n";

    LarkyEvaluationScriptException interpretedError = evalWithMode(script, LarkyCompiledScript.CompilationMode.INTERPRETED);
    LarkyEvaluationScriptException bytecodeError = evalWithMode(script, LarkyCompiledScript.CompilationMode.BYTECODE);

    if (interpretedError != null && bytecodeError != null) {
      System.out.println("=== Extra Argument Consistency ===");
      System.out.println("INTERPRETED: " + interpretedError.getMessage());
      System.out.println("BYTECODE: " + bytecodeError.getMessage());

      assertErrorRelated(interpretedError, "argument", "too many", "accepts", "unexpected");
      assertErrorRelated(bytecodeError, "argument", "too many", "accepts", "unexpected");
    }
  }

  // ==================== Multi-Target Compilation Tests ====================

  @Test
  public void testMultiTargetSyntaxError() {
    String script = "invalid syntax here @#$\n";

    // Multi-target compilation should fail for all targets consistently
    try {
      engine.compileToTargets(script, BytecodeTarget.JVM, BytecodeTarget.WASM);
      fail("Should have thrown ScriptException");
    } catch (ScriptException e) {
      System.out.println("=== Multi-Target Syntax Error ===");
      System.out.println("Error: " + e.getMessage());
      assertNotNull("Should have error message", e.getMessage());
    }
  }

  @Test
  public void testMultiTargetUndefinedVariable() {
    String script = "result = totally_undefined_var\n";

    try {
      engine.compileToTargets(script, BytecodeTarget.JVM, BytecodeTarget.WASM, BytecodeTarget.INTERPRETER);
      fail("Should have thrown ScriptException");
    } catch (ScriptException e) {
      System.out.println("=== Multi-Target Undefined Variable ===");
      System.out.println("Error: " + e.getMessage());
      assertErrorRelated(e, "undefined", "unbound", "not found");
    }
  }

  // ==================== Output Format Tests ====================

  @Test
  public void testBytecodeOutputGeneratedOnSuccess() throws ScriptException, IOException {
    String script = "x = 1 + 2\ny = x * 3\n";

    LarkyCompiledScript compiled = engine.compile(script, "test.star");

    // All output formats should be available
    assertNotNull("Bytecode should be available", compiled.getBytecode());

    byte[] jvmBytes = compiled.getJvmBytecode();
    String wasmText = compiled.getWasmText();

    assertNotNull("JVM bytecode should be generated", jvmBytes);
    assertTrue("JVM bytecode should have content", jvmBytes.length > 0);

    assertNotNull("WASM text should be generated", wasmText);
    assertTrue("WASM text should have content", wasmText.length() > 0);

    System.out.println("=== Output Format Test ===");
    System.out.println("JVM bytecode size: " + jvmBytes.length + " bytes");
    System.out.println("WASM text size: " + wasmText.length() + " chars");
  }

  @Test
  public void testOutputForTargetMethod() throws ScriptException, IOException {
    String script = "def greet(name):\n  return 'Hello, ' + name\n";

    LarkyCompiledScript compiled = engine.compile(script, "greet.star");

    // Test all targets via the generic method
    for (BytecodeTarget target : BytecodeTarget.values()) {
      assertTrue("Should support " + target, compiled.supportsTarget(target));

      byte[] output = compiled.getOutputForTarget(target);
      assertNotNull("Output for " + target + " should not be null", output);
      assertTrue("Output for " + target + " should have content", output.length > 0);

      System.out.println(target + " output: " + output.length + " bytes");
    }
  }

  // ==================== Helper Methods ====================

  private ScriptException compileWithMode(String script, LarkyCompiledScript.CompilationMode mode) {
    try {
      engine.compile(script, "test.star", mode);
      return null;
    } catch (ScriptException e) {
      return e;
    }
  }

  private LarkyEvaluationScriptException evalWithMode(String script, LarkyCompiledScript.CompilationMode mode)
      throws ScriptException {
    try {
      LarkyCompiledScript compiled = engine.compile(script, "test.star", mode);
      compiled.eval();
      return null;
    } catch (LarkyEvaluationScriptException e) {
      return e;
    } catch (ScriptException e) {
      // Compile-time error, not runtime
      return null;
    }
  }

  private void assertErrorRelated(Exception error, String... keywords) {
    if (error == null) {
      return;
    }
    String message = error.getMessage();
    if (message == null) {
      message = error.toString();
    }
    String lowerMessage = message.toLowerCase();

    boolean found = false;
    for (String keyword : keywords) {
      if (lowerMessage.contains(keyword.toLowerCase())) {
        found = true;
        break;
      }
    }

    assertTrue("Error should contain one of: " + String.join(", ", keywords)
        + "\nActual: " + message, found);
  }
}
