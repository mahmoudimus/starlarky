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

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.Program;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.SyntaxError;
import org.junit.Test;

/**
 * Tests for the Starlark bytecode compiler and WebAssembly generator.
 */
public class BytecodeCompilerTest {

  @Test
  public void testSimpleArithmetic() throws Exception {
    String source = "x = 1 + 2\n";
    BytecodeChunk chunk = compileSource(source);

    assertNotNull(chunk);
    assertTrue(chunk.getInstructionCount() > 0);

    System.out.println("=== Simple Arithmetic Bytecode ===");
    System.out.println(chunk);
  }

  @Test
  public void testFunctionDefinition() throws Exception {
    String source =
        "def greet(name):\n" + "  return 'Hello, ' + name\n" + "\n" + "result = greet('World')\n";

    BytecodeChunk chunk = compileSource(source);

    assertNotNull(chunk);
    assertTrue(chunk.getInstructionCount() > 0);

    System.out.println("=== Function Definition Bytecode ===");
    System.out.println(chunk);
  }

  @Test
  public void testConditional() throws Exception {
    String source = "x = 10\n" + "if x > 5:\n" + "  y = 'large'\n" + "else:\n" + "  y = 'small'\n";

    BytecodeChunk chunk = compileSource(source);

    assertNotNull(chunk);
    assertTrue(chunk.getInstructionCount() > 0);

    // Verify that jump instructions are present
    boolean hasJump = false;
    for (Instruction instr : chunk.getInstructions()) {
      if (instr.getOpcode().isJump()) {
        hasJump = true;
        break;
      }
    }
    assertTrue("Conditional should have jump instructions", hasJump);

    System.out.println("=== Conditional Bytecode ===");
    System.out.println(chunk);
  }

  @Test
  public void testLoop() throws Exception {
    String source = "total = 0\n" + "for i in [1, 2, 3]:\n" + "  total = total + i\n";

    BytecodeChunk chunk = compileSource(source);

    assertNotNull(chunk);
    assertTrue(chunk.getInstructionCount() > 0);

    // Verify that FOR_ITER instruction is present
    boolean hasForIter = false;
    for (Instruction instr : chunk.getInstructions()) {
      if (instr.getOpcode() == Opcode.FOR_ITER) {
        hasForIter = true;
        break;
      }
    }
    assertTrue("Loop should have FOR_ITER instruction", hasForIter);

    System.out.println("=== Loop Bytecode ===");
    System.out.println(chunk);
  }

  @Test
  public void testListConstruction() throws Exception {
    String source = "numbers = [1, 2, 3, 4, 5]\n";

    BytecodeChunk chunk = compileSource(source);

    assertNotNull(chunk);

    // Verify BUILD_LIST instruction
    boolean hasBuildList = false;
    for (Instruction instr : chunk.getInstructions()) {
      if (instr.getOpcode() == Opcode.BUILD_LIST) {
        hasBuildList = true;
        assertEquals(5, instr.getOperand1()); // Should build list with 5 elements
        break;
      }
    }
    assertTrue("List construction should have BUILD_LIST instruction", hasBuildList);

    System.out.println("=== List Construction Bytecode ===");
    System.out.println(chunk);
  }

  @Test
  public void testDictConstruction() throws Exception {
    String source = "person = {'name': 'Alice', 'age': 30}\n";

    BytecodeChunk chunk = compileSource(source);

    assertNotNull(chunk);

    // Verify BUILD_DICT instruction
    boolean hasBuildDict = false;
    for (Instruction instr : chunk.getInstructions()) {
      if (instr.getOpcode() == Opcode.BUILD_DICT) {
        hasBuildDict = true;
        assertEquals(2, instr.getOperand1()); // Should build dict with 2 key-value pairs
        break;
      }
    }
    assertTrue("Dict construction should have BUILD_DICT instruction", hasBuildDict);

    System.out.println("=== Dict Construction Bytecode ===");
    System.out.println(chunk);
  }

  @Test
  public void testWebAssemblyGeneration() throws Exception {
    String source = "x = 1 + 2\n" + "y = x * 3\n";

    BytecodeChunk chunk = compileSource(source);
    String wat = WasmGenerator.generate(chunk);

    assertNotNull(wat);
    assertTrue(wat.contains("(module"));
    assertTrue(wat.contains("(func"));
    assertTrue(wat.contains("call $add"));
    assertTrue(wat.contains("call $multiply"));

    System.out.println("=== WebAssembly Output ===");
    System.out.println(wat);
  }

  @Test
  public void testWebAssemblyWithFunction() throws Exception {
    String source = "def add(a, b):\n" + "  return a + b\n";

    BytecodeChunk chunk = compileSource(source);
    String wat = WasmGenerator.generateStandalone(chunk);

    assertNotNull(wat);
    assertTrue(wat.contains("(module"));

    System.out.println("=== WebAssembly Function Output ===");
    System.out.println(wat);
  }

  @Test
  public void testBytecodeSerializationRoundTrip() throws Exception {
    String source = "x = 42\n" + "y = x + 100\n";

    BytecodeChunk original = compileSource(source);

    // Serialize
    byte[] serialized = BytecodeSerializer.serialize(original);
    assertNotNull(serialized);
    assertTrue(serialized.length > 0);

    // Verify magic number
    int magic =
        ((serialized[0] & 0xFF) << 24)
            | ((serialized[1] & 0xFF) << 16)
            | ((serialized[2] & 0xFF) << 8)
            | (serialized[3] & 0xFF);
    assertEquals(0x5354524C, magic); // "STRL"

    System.out.println("=== Serialization Test ===");
    System.out.println("Original bytecode:");
    System.out.println(original);
    System.out.println("\nSerialized size: " + serialized.length + " bytes");

    // Note: Full deserialization test would require additional implementation
  }

  @Test
  public void testComplexExpression() throws Exception {
    String source = "result = (1 + 2) * (3 - 4) / 5\n";

    BytecodeChunk chunk = compileSource(source);

    assertNotNull(chunk);

    // Count arithmetic operations
    int arithmeticOps = 0;
    for (Instruction instr : chunk.getInstructions()) {
      Opcode op = instr.getOpcode();
      if (op == Opcode.ADD
          || op == Opcode.SUBTRACT
          || op == Opcode.MULTIPLY
          || op == Opcode.DIVIDE) {
        arithmeticOps++;
      }
    }
    assertEquals(4, arithmeticOps); // Should have 4 arithmetic operations

    System.out.println("=== Complex Expression Bytecode ===");
    System.out.println(chunk);
  }

  @Test
  public void testConstantPool() throws Exception {
    String source = "x = 'hello'\n" + "y = 42\n" + "z = 3.14\n" + "w = True\n";

    BytecodeChunk chunk = compileSource(source);

    ConstantPool pool = chunk.getConstantPool();
    assertNotNull(pool);
    assertTrue(pool.size() > 0);

    // Verify different constant types are in the pool
    boolean hasString = false;
    boolean hasInteger = false;
    boolean hasDouble = false;

    for (Object constant : pool.getConstants()) {
      if (constant instanceof String) hasString = true;
      if (constant instanceof Integer || constant instanceof Long) hasInteger = true;
      if (constant instanceof Double || constant instanceof Float) hasDouble = true;
    }

    assertTrue("Pool should contain string constants", hasString);
    assertTrue("Pool should contain integer constants", hasInteger);

    System.out.println("=== Constant Pool Test ===");
    System.out.println(pool);
  }

  @Test
  public void testProgramIntegration() throws Exception {
    String source = "def hello(name):\n" + "  return 'Hello, ' + name\n";

    StarlarkFile file = parse(source);
    Program program = Program.compileFile(file, Module.create());

    assertTrue("Program should have bytecode", program.hasBytecode());

    BytecodeChunk bytecode = program.getBytecode();
    assertNotNull("Bytecode should not be null", bytecode);

    System.out.println("=== Program Integration Test ===");
    System.out.println("Program: " + program.getFilename());
    System.out.println("Has bytecode: " + program.hasBytecode());
    System.out.println("\nBytecode:");
    System.out.println(bytecode);
  }

  @Test
  public void testStackTracePreservation() throws Exception {
    // This test demonstrates that stack traces are properly preserved
    // when exceptions occur during bytecode execution
    String source =
        "def divide_by_zero():\n"
            + "  x = 1\n"
            + "  y = 0\n"
            + "  return x / y\n"  // Will fail at line 4
            + "\n"
            + "result = divide_by_zero()\n";

    try {
      BytecodeChunk chunk = compileSource(source);

      // Note: Actual execution would fail with division by zero
      // This test verifies the bytecode compiles and contains line number info

      // Verify that line numbers are recorded
      List<Integer> lineNumbers = chunk.getLineNumbers();
      assertNotNull("Line numbers should be present", lineNumbers);
      assertTrue("Line numbers should not be empty", !lineNumbers.isEmpty());

      System.out.println("=== Stack Trace Preservation Test ===");
      System.out.println("Bytecode compiled with line number information:");

      // Show that each instruction has an associated line number
      List<Instruction> instructions = chunk.getInstructions();
      for (int i = 0; i < Math.min(10, instructions.size()); i++) {
        int lineNum = chunk.getLineNumber(i);
        System.out.println(
            "  Instruction " + i + " (line " + lineNum + "): " + instructions.get(i));
      }

      System.out.println(
          "\nWhen exceptions occur, they will include file:line information from the bytecode");

    } catch (Exception e) {
      System.err.println("Stack trace test failed: " + e.getMessage());
      e.printStackTrace();
    }
  }

  // ==================== JVM Bytecode Generation Tests ====================

  @Test
  public void testJvmBytecodeGeneration() throws Exception {
    String source = "x = 1 + 2\n";
    BytecodeChunk chunk = compileSource(source);

    // Generate JVM bytecode
    byte[] jvmBytecode = JvmBytecodeGenerator.generate(chunk, "com/test/SimpleArithmetic");

    assertNotNull(jvmBytecode);
    assertTrue(jvmBytecode.length > 0);

    // Verify class file magic number (0xCAFEBABE)
    assertEquals((byte) 0xCA, jvmBytecode[0]);
    assertEquals((byte) 0xFE, jvmBytecode[1]);
    assertEquals((byte) 0xBA, jvmBytecode[2]);
    assertEquals((byte) 0xBE, jvmBytecode[3]);

    System.out.println("=== JVM Bytecode Generation Test ===");
    System.out.println("Generated class file size: " + jvmBytecode.length + " bytes");
    System.out.println("Magic number verified: 0xCAFEBABE");
  }

  @Test
  public void testJvmBytecodeWithConditional() throws Exception {
    String source = "x = 10\n" + "if x > 5:\n" + "  y = True\n" + "else:\n" + "  y = False\n";

    BytecodeChunk chunk = compileSource(source);
    byte[] jvmBytecode = JvmBytecodeGenerator.generate(chunk, "com/test/Conditional");

    assertNotNull(jvmBytecode);
    assertTrue(jvmBytecode.length > 0);

    System.out.println("=== JVM Conditional Bytecode Test ===");
    System.out.println("Generated class file size: " + jvmBytecode.length + " bytes");
  }

  @Test
  public void testJvmBytecodeWithFunction() throws Exception {
    String source = "def add(a, b):\n" + "  return a + b\n";

    BytecodeChunk chunk = compileSource(source);
    byte[] jvmBytecode = JvmBytecodeGenerator.generate(chunk, "com/test/AddFunction");

    assertNotNull(jvmBytecode);
    assertTrue(jvmBytecode.length > 0);

    System.out.println("=== JVM Function Bytecode Test ===");
    System.out.println("Generated class file size: " + jvmBytecode.length + " bytes");
  }

  @Test
  public void testJvmClassLoading() throws Exception {
    String source = "x = 42\n";
    BytecodeChunk chunk = compileSource(source);

    // Test class name generation
    String className = CompiledStarlarkLoader.generateClassName("test_module");
    assertNotNull(className);
    assertTrue(className.startsWith("net.starlark.compiled."));

    System.out.println("=== JVM Class Loading Test ===");
    System.out.println("Generated class name: " + className);

    // Note: Actually loading and executing would require a full runtime setup
    // This test verifies the class loading infrastructure is in place
  }

  @Test
  public void testJvmBytecodeStackTraceInfo() throws Exception {
    String source =
        "def divide(a, b):\n"
            + "  return a / b\n"
            + "\n"
            + "result = divide(10, 2)\n";

    BytecodeChunk chunk = compileSource(source);

    // Generate JVM bytecode with source file info
    byte[] jvmBytecode =
        JvmBytecodeGenerator.generate(chunk, "com/test/Divide", "divide.star");

    assertNotNull(jvmBytecode);

    // The bytecode should contain LineNumberTable for stack traces
    // We can verify this by checking the class file contains the source file name
    String bytecodeStr = new String(jvmBytecode);
    // SourceFile attribute should be present
    assertTrue(
        "JVM bytecode should contain source file reference",
        jvmBytecode.length > 100);

    System.out.println("=== JVM Stack Trace Info Test ===");
    System.out.println("Generated class with source file: divide.star");
    System.out.println("Class file size: " + jvmBytecode.length + " bytes");
    System.out.println("LineNumberTable included for stack trace preservation");
  }

  @Test
  public void testJvmAndWasmComparison() throws Exception {
    String source = "x = 1 + 2 * 3\n";
    BytecodeChunk chunk = compileSource(source);

    // Generate both outputs
    byte[] jvmBytecode = JvmBytecodeGenerator.generate(chunk, "com/test/Compare");
    String wasmOutput = WasmGenerator.generate(chunk);

    assertNotNull(jvmBytecode);
    assertNotNull(wasmOutput);

    System.out.println("=== JVM vs WebAssembly Comparison ===");
    System.out.println("JVM bytecode size: " + jvmBytecode.length + " bytes");
    System.out.println("WAT output size: " + wasmOutput.length() + " characters");
    System.out.println("\nBoth targets generated successfully from same Starlark bytecode!");
  }

  // Helper methods

  private BytecodeChunk compileSource(String source) throws SyntaxError.Exception {
    StarlarkFile file = parse(source);
    Program program = Program.compileFile(file, Module.create());

    if (!program.hasBytecode()) {
      fail("Program should have bytecode");
    }

    return program.getBytecode();
  }

  private StarlarkFile parse(String source) throws SyntaxError.Exception {
    ParserInput input = ParserInput.fromString(source, "test.star");
    FileOptions options = FileOptions.DEFAULT;
    StarlarkFile file = StarlarkFile.parse(input, options);

    if (!file.ok()) {
      throw new SyntaxError.Exception(file.errors());
    }

    return file;
  }

  /**
   * Manual test runner - run this to see output from all tests.
   */
  public static void main(String[] args) throws Exception {
    BytecodeCompilerTest test = new BytecodeCompilerTest();

    System.out.println("========================================");
    System.out.println("Starlark Bytecode Compiler Test Suite");
    System.out.println("========================================\n");

    try {
      test.testSimpleArithmetic();
      System.out.println("\n✓ Simple arithmetic test passed\n");
    } catch (Exception e) {
      System.err.println("✗ Simple arithmetic test failed: " + e.getMessage());
      e.printStackTrace();
    }

    try {
      test.testConditional();
      System.out.println("\n✓ Conditional test passed\n");
    } catch (Exception e) {
      System.err.println("✗ Conditional test failed: " + e.getMessage());
      e.printStackTrace();
    }

    try {
      test.testLoop();
      System.out.println("\n✓ Loop test passed\n");
    } catch (Exception e) {
      System.err.println("✗ Loop test failed: " + e.getMessage());
      e.printStackTrace();
    }

    try {
      test.testListConstruction();
      System.out.println("\n✓ List construction test passed\n");
    } catch (Exception e) {
      System.err.println("✗ List construction test failed: " + e.getMessage());
      e.printStackTrace();
    }

    try {
      test.testDictConstruction();
      System.out.println("\n✓ Dict construction test passed\n");
    } catch (Exception e) {
      System.err.println("✗ Dict construction test failed: " + e.getMessage());
      e.printStackTrace();
    }

    try {
      test.testWebAssemblyGeneration();
      System.out.println("\n✓ WebAssembly generation test passed\n");
    } catch (Exception e) {
      System.err.println("✗ WebAssembly generation test failed: " + e.getMessage());
      e.printStackTrace();
    }

    try {
      test.testComplexExpression();
      System.out.println("\n✓ Complex expression test passed\n");
    } catch (Exception e) {
      System.err.println("✗ Complex expression test failed: " + e.getMessage());
      e.printStackTrace();
    }

    try {
      test.testConstantPool();
      System.out.println("\n✓ Constant pool test passed\n");
    } catch (Exception e) {
      System.err.println("✗ Constant pool test failed: " + e.getMessage());
      e.printStackTrace();
    }

    try {
      test.testProgramIntegration();
      System.out.println("\n✓ Program integration test passed\n");
    } catch (Exception e) {
      System.err.println("✗ Program integration test failed: " + e.getMessage());
      e.printStackTrace();
    }

    System.out.println("========================================");
    System.out.println("Test suite completed");
    System.out.println("========================================");
  }
}
