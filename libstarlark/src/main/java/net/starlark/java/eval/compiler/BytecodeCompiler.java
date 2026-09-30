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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.starlark.java.syntax.*;

/**
 * Compiles Starlark AST nodes to bytecode.
 *
 * <p>This compiler performs a single pass over the AST and generates stack-based bytecode
 * that can be executed by BytecodeInterpreter or translated to WebAssembly.
 *
 * <p>The compiler assumes the AST has been resolved (variables bound, scopes determined).
 */
public final class BytecodeCompiler {

  private final BytecodeChunk.Builder builder;
  private final Map<String, Integer> labelOffsets;
  private final List<PatchLocation> jumpsToPatch;
  private int loopDepth;
  private List<BreakContinue> breakContinueStack;

  private static class PatchLocation {
    final int instructionIndex;
    final String targetLabel;

    PatchLocation(int instructionIndex, String targetLabel) {
      this.instructionIndex = instructionIndex;
      this.targetLabel = targetLabel;
    }
  }

  private static class BreakContinue {
    final List<Integer> breakInstructions;
    final String continueLabel;

    BreakContinue(String continueLabel) {
      this.breakInstructions = new ArrayList<>();
      this.continueLabel = continueLabel;
    }
  }

  private BytecodeCompiler(String name) {
    this.builder = new BytecodeChunk.Builder(name);
    this.labelOffsets = new HashMap<>();
    this.jumpsToPatch = new ArrayList<>();
    this.loopDepth = 0;
    this.breakContinueStack = new ArrayList<>();
  }

  /**
   * Compiles a Starlark function to bytecode.
   *
   * @param func the resolved function to compile
   * @return the compiled bytecode chunk
   */
  public static BytecodeChunk compileFunction(Resolver.Function func) {
    BytecodeCompiler compiler = new BytecodeCompiler(func.getName());

    // Set up function metadata
    compiler.builder.setParameterCount(func.getParameterNames().size());
    for (String param : func.getParameterNames()) {
      compiler.builder.addParameter(param);
    }
    compiler.builder.setLocalCount(func.getLocals().size());

    // Compile function body
    for (Statement stmt : func.getBody()) {
      compiler.compileStatement(stmt);
    }

    // Ensure function returns (implicit None return)
    compiler.builder.emit(Opcode.LOAD_NONE, -1);
    compiler.builder.emit(Opcode.RETURN, -1);

    // Patch all forward jumps
    compiler.patchJumps();

    return compiler.builder.build();
  }

  /**
   * Compiles a Starlark module (top-level statements) to bytecode.
   *
   * @param statements the list of statements in the module
   * @param moduleName the name of the module
   * @return the compiled bytecode chunk
   */
  public static BytecodeChunk compileModule(List<Statement> statements, String moduleName) {
    BytecodeCompiler compiler = new BytecodeCompiler(moduleName);

    for (Statement stmt : statements) {
      compiler.compileStatement(stmt);
    }

    // Module execution returns None
    compiler.builder.emit(Opcode.LOAD_NONE, -1);
    compiler.builder.emit(Opcode.RETURN, -1);

    compiler.patchJumps();

    return compiler.builder.build();
  }

  private void compileStatement(Statement stmt) {
    switch (stmt.kind()) {
      case ASSIGNMENT:
        visit((AssignmentStatement) stmt);
        break;
      case EXPRESSION:
        visit((ExpressionStatement) stmt);
        break;
      case IF:
        visit((IfStatement) stmt);
        break;
      case FOR:
        visit((ForStatement) stmt);
        break;
      case DEF:
        visit((DefStatement) stmt);
        break;
      case RETURN:
        visit((ReturnStatement) stmt);
        break;
      case FLOW:
        visit((FlowStatement) stmt);
        break;
      case LOAD:
        visit((LoadStatement) stmt);
        break;
    }
  }

  private void compileExpression(Expression expr) {
    switch (expr.kind()) {
      case IDENTIFIER:
        visit((Identifier) expr);
        break;
      case INT_LITERAL:
        visit((IntLiteral) expr);
        break;
      case FLOAT_LITERAL:
        visit((FloatLiteral) expr);
        break;
      case STRING_LITERAL:
        visit((StringLiteral) expr);
        break;
      case BINARY_OPERATOR:
        visit((BinaryOperatorExpression) expr);
        break;
      case UNARY_OPERATOR:
        visit((UnaryOperatorExpression) expr);
        break;
      case LIST_EXPR:
        visit((ListExpression) expr);
        break;
      case DICT_EXPR:
        visit((DictExpression) expr);
        break;
      case CALL:
        visit((CallExpression) expr);
        break;
      case DOT:
        visit((DotExpression) expr);
        break;
      case INDEX:
        visit((IndexExpression) expr);
        break;
      case SLICE:
        visit((SliceExpression) expr);
        break;
      case CONDITIONAL:
        visit((ConditionalExpression) expr);
        break;
      case COMPREHENSION:
        visit((Comprehension) expr);
        break;
      case LAMBDA:
        visit((LambdaExpression) expr);
        break;
      default:
        throw new UnsupportedOperationException("Unsupported expression: " + expr.kind());
    }
  }

  // Statement visitors

  public void visit(AssignmentStatement node) {
    // Compile the RHS expression
    compileExpression(node.getRHS());

    // Handle LHS assignment
    compileLValue(node.getLHS());
  }

  public void visit(ExpressionStatement node) {
    compileExpression(node.getExpression());
    // Expression result is not used, pop it
    builder.emit(Opcode.POP, getLine(node));
  }

  public void visit(IfStatement node) {
    int lineNum = getLine(node);

    // Compile condition
    compileExpression(node.getCondition());

    String elseLabel = newLabel("else");
    String endLabel = newLabel("endif");

    // Jump to else if condition is false
    emitJump(Opcode.POP_JUMP_IF_FALSE, elseLabel, lineNum);

    // Compile then branch
    for (Statement stmt : node.getThenBlock()) {
      compileStatement(stmt);
    }

    // Jump to end (skip else)
    emitJump(Opcode.JUMP, endLabel, lineNum);

    // Else branch
    markLabel(elseLabel);
    for (Statement stmt : node.getElseBlock()) {
      compileStatement(stmt);
    }

    markLabel(endLabel);
  }

  public void visit(ForStatement node) {
    int lineNum = getLine(node);
    loopDepth++;

    String continueLabel = newLabel("for_continue");
    String breakLabel = newLabel("for_break");

    BreakContinue bc = new BreakContinue(continueLabel);
    breakContinueStack.add(bc);

    // Compile iterable expression
    compileExpression(node.getCollection());

    // Get iterator
    builder.emit(Opcode.GET_ITER, lineNum);

    // Loop start
    markLabel(continueLabel);

    // Try to get next item
    emitJump(Opcode.FOR_ITER, breakLabel, lineNum);

    // Assign to loop variable
    compileLValue(node.getVars());

    // Execute loop body
    for (Statement stmt : node.getBody()) {
      compileStatement(stmt);
    }

    // Jump back to loop start
    emitJump(Opcode.JUMP, continueLabel, lineNum);

    // Loop end
    markLabel(breakLabel);
    builder.emit(Opcode.POP, lineNum); // Pop iterator

    // Patch break statements
    for (int breakInstr : bc.breakInstructions) {
      patchJump(breakInstr, breakLabel);
    }

    breakContinueStack.remove(breakContinueStack.size() - 1);
    loopDepth--;
  }

  public void visit(DefStatement node) {
    int lineNum = getLine(node);

    // For now, we'll compile function definitions as constants
    // and create function objects at runtime
    // This is a simplified implementation

    Identifier id = node.getIdentifier();
    int nameIndex = builder.addConstant(id.getName());

    // TODO: Recursively compile the function body
    // For now, just store a placeholder
    builder.emit(Opcode.MAKE_FUNCTION, 0, lineNum);

    // Store function in variable
    storeVariable(id);
  }

  public void visit(ReturnStatement node) {
    int lineNum = getLine(node);

    Expression result = node.getResult();
    if (result != null) {
      compileExpression(result);
    } else {
      builder.emit(Opcode.LOAD_NONE, lineNum);
    }

    builder.emit(Opcode.RETURN, lineNum);
  }

  public void visit(FlowStatement node) {
    int lineNum = getLine(node);

    switch (node.getKind()) {
      case BREAK:
        if (breakContinueStack.isEmpty()) {
          throw new IllegalStateException("break outside loop");
        }
        BreakContinue bc = breakContinueStack.get(breakContinueStack.size() - 1);
        int breakInstr = builder.getInstructionCount();
        builder.emit(Opcode.JUMP, 0, lineNum); // Will be patched later
        bc.breakInstructions.add(breakInstr);
        break;

      case CONTINUE:
        if (breakContinueStack.isEmpty()) {
          throw new IllegalStateException("continue outside loop");
        }
        BreakContinue bcCont = breakContinueStack.get(breakContinueStack.size() - 1);
        emitJump(Opcode.JUMP, bcCont.continueLabel, lineNum);
        break;

      case PASS:
        builder.emit(Opcode.NOP, lineNum);
        break;
    }
  }

  public void visit(LoadStatement node) {
    // Load statements are typically handled at module initialization
    // For now, emit a placeholder
    int lineNum = getLine(node);
    int moduleIndex = builder.addConstant(node.getImport().getValue());
    builder.emit(Opcode.LOAD_MODULE, moduleIndex, lineNum);
  }

  // Expression visitors

  public void visit(Identifier node) {
    loadVariable(node);
  }

  public void visit(IntLiteral node) {
    int index = builder.addConstant(node.getValue());
    builder.emit(Opcode.LOAD_CONST, index, getLine(node));
  }

  public void visit(FloatLiteral node) {
    int index = builder.addConstant(node.getValue());
    builder.emit(Opcode.LOAD_CONST, index, getLine(node));
  }

  public void visit(StringLiteral node) {
    int index = builder.addConstant(node.getValue());
    builder.emit(Opcode.LOAD_CONST, index, getLine(node));
  }

  public void visit(BinaryOperatorExpression node) {
    int lineNum = getLine(node);

    // Special handling for short-circuit operators
    if (node.getOperator() == TokenKind.AND) {
      compileExpression(node.getX());
      String endLabel = newLabel("and_end");
      builder.emit(Opcode.DUP, lineNum);
      emitJump(Opcode.POP_JUMP_IF_FALSE, endLabel, lineNum);
      builder.emit(Opcode.POP, lineNum);
      compileExpression(node.getY());
      markLabel(endLabel);
      return;
    }

    if (node.getOperator() == TokenKind.OR) {
      compileExpression(node.getX());
      String endLabel = newLabel("or_end");
      builder.emit(Opcode.DUP, lineNum);
      emitJump(Opcode.POP_JUMP_IF_TRUE, endLabel, lineNum);
      builder.emit(Opcode.POP, lineNum);
      compileExpression(node.getY());
      markLabel(endLabel);
      return;
    }

    // Regular binary operators
    compileExpression(node.getX());
    compileExpression(node.getY());

    Opcode opcode = getOpcodeForBinaryOp(node.getOperator());
    builder.emit(opcode, lineNum);
  }

  public void visit(UnaryOperatorExpression node) {
    compileExpression(node.getX());

    Opcode opcode = getOpcodeForUnaryOp(node.getOperator());
    builder.emit(opcode, getLine(node));
  }

  public void visit(ListExpression node) {
    int lineNum = getLine(node);

    // Compile all elements
    for (Expression elem : node.getElements()) {
      compileExpression(elem);
    }

    // Build list from elements on stack
    builder.emit(Opcode.BUILD_LIST, node.getElements().size(), lineNum);
  }

  public void visit(DictExpression node) {
    int lineNum = getLine(node);

    // Compile all key-value pairs
    for (DictExpression.Entry entry : node.getEntries()) {
      compileExpression(entry.getKey());
      compileExpression(entry.getValue());
    }

    // Build dict from key-value pairs on stack
    builder.emit(Opcode.BUILD_DICT, node.getEntries().size(), lineNum);
  }

  public void visit(CallExpression node) {
    int lineNum = getLine(node);

    // Compile function expression
    compileExpression(node.getFunction());

    // Compile positional arguments
    int posArgCount = 0;
    int kwArgCount = 0;

    for (Argument arg : node.getArguments()) {
      if (arg instanceof Argument.Positional) {
        compileExpression(((Argument.Positional) arg).getValue());
        posArgCount++;
      } else if (arg instanceof Argument.Keyword) {
        Argument.Keyword kwArg = (Argument.Keyword) arg;
        int nameIndex = builder.addConstant(kwArg.getName());
        builder.emit(Opcode.LOAD_CONST, nameIndex, lineNum);
        compileExpression(kwArg.getValue());
        kwArgCount++;
      }
    }

    // Emit call instruction
    builder.emit(Opcode.CALL, posArgCount, kwArgCount, lineNum);
  }

  public void visit(DotExpression node) {
    int lineNum = getLine(node);

    // Compile object expression
    compileExpression(node.getObject());

    // Load attribute
    int nameIndex = builder.addConstant(node.getField().getName());
    builder.emit(Opcode.LOAD_ATTR, nameIndex, lineNum);
  }

  public void visit(IndexExpression node) {
    int lineNum = getLine(node);

    // Compile object and key
    compileExpression(node.getObject());
    compileExpression(node.getKey());

    builder.emit(Opcode.INDEX, lineNum);
  }

  public void visit(SliceExpression node) {
    int lineNum = getLine(node);

    // Compile object
    compileExpression(node.getObject());

    // Compile start, stop, step (push None if not present)
    if (node.getStart() != null) {
      compileExpression(node.getStart());
    } else {
      builder.emit(Opcode.LOAD_NONE, lineNum);
    }

    if (node.getStop() != null) {
      compileExpression(node.getStop());
    } else {
      builder.emit(Opcode.LOAD_NONE, lineNum);
    }

    if (node.getStep() != null) {
      compileExpression(node.getStep());
    } else {
      builder.emit(Opcode.LOAD_NONE, lineNum);
    }

    builder.emit(Opcode.SLICE, lineNum);
  }

  public void visit(ConditionalExpression node) {
    int lineNum = getLine(node);

    String elseLabel = newLabel("cond_else");
    String endLabel = newLabel("cond_end");

    // Compile condition
    compileExpression(node.getCondition());

    // Jump to else if false
    emitJump(Opcode.POP_JUMP_IF_FALSE, elseLabel, lineNum);

    // True branch
    compileExpression(node.getThenCase());
    emitJump(Opcode.JUMP, endLabel, lineNum);

    // False branch
    markLabel(elseLabel);
    compileExpression(node.getElseCase());

    markLabel(endLabel);
  }

  public void visit(Comprehension node) {
    // Comprehensions are complex - simplified implementation
    int lineNum = getLine(node);

    // Create empty collection
    if (node.isDict()) {
      builder.emit(Opcode.BUILD_DICT, 0, lineNum);
    } else {
      builder.emit(Opcode.BUILD_LIST, 0, lineNum);
    }

    // TODO: Implement comprehension logic
    // This requires handling nested loops and filters
  }

  public void visit(LambdaExpression node) {
    // Lambdas are simplified functions
    int lineNum = getLine(node);

    // TODO: Compile lambda body as a separate function
    builder.emit(Opcode.MAKE_FUNCTION, 0, lineNum);
  }

  // Helper methods

  private void compileLValue(Expression lhs) {
    if (lhs instanceof Identifier) {
      storeVariable((Identifier) lhs);
    } else if (lhs instanceof IndexExpression) {
      IndexExpression idx = (IndexExpression) lhs;
      compileExpression(idx.getObject());
      compileExpression(idx.getKey());
      builder.emit(Opcode.STORE_INDEX, getLine(lhs));
    } else if (lhs instanceof DotExpression) {
      DotExpression dot = (DotExpression) lhs;
      compileExpression(dot.getObject());
      int nameIndex = builder.addConstant(dot.getField().getName());
      builder.emit(Opcode.STORE_ATTR, nameIndex, getLine(lhs));
    } else if (lhs instanceof ListExpression) {
      // Tuple/list unpacking
      ListExpression list = (ListExpression) lhs;
      for (Expression elem : list.getElements()) {
        compileLValue(elem);
      }
    } else {
      throw new IllegalArgumentException("Invalid lvalue: " + lhs);
    }
  }

  private void loadVariable(Identifier id) {
    Resolver.Binding binding = id.getBinding();
    int lineNum = getLine(id);

    if (binding == null) {
      // Unresolved - treat as global
      int nameIndex = builder.addConstant(id.getName());
      builder.emit(Opcode.LOAD_GLOBAL, nameIndex, lineNum);
      return;
    }

    switch (binding.getScope()) {
      case LOCAL:
        builder.emit(Opcode.LOAD_LOCAL, binding.getIndex(), lineNum);
        break;
      case GLOBAL:
      case PREDECLARED:
      case UNIVERSAL:
        int nameIndex = builder.addConstant(id.getName());
        builder.emit(Opcode.LOAD_GLOBAL, nameIndex, lineNum);
        break;
      case FREE:
        builder.emit(Opcode.LOAD_FREE, binding.getIndex(), lineNum);
        break;
      case CELL:
        // Cell variables are used for closures
        builder.emit(Opcode.LOAD_FREE, binding.getIndex(), lineNum);
        break;
    }
  }

  private void storeVariable(Identifier id) {
    Resolver.Binding binding = id.getBinding();
    int lineNum = getLine(id);

    if (binding == null) {
      // Unresolved - treat as global
      int nameIndex = builder.addConstant(id.getName());
      builder.emit(Opcode.STORE_GLOBAL, nameIndex, lineNum);
      return;
    }

    switch (binding.getScope()) {
      case LOCAL:
        builder.emit(Opcode.STORE_LOCAL, binding.getIndex(), lineNum);
        break;
      case GLOBAL:
        int nameIndex = builder.addConstant(id.getName());
        builder.emit(Opcode.STORE_GLOBAL, nameIndex, lineNum);
        break;
      case FREE:
      case CELL:
        builder.emit(Opcode.STORE_FREE, binding.getIndex(), lineNum);
        break;
      default:
        throw new IllegalStateException("Cannot store to " + binding.getScope());
    }
  }

  private Opcode getOpcodeForBinaryOp(TokenKind op) {
    switch (op) {
      case PLUS:
        return Opcode.ADD;
      case MINUS:
        return Opcode.SUBTRACT;
      case STAR:
        return Opcode.MULTIPLY;
      case SLASH:
        return Opcode.DIVIDE;
      case SLASH_SLASH:
        return Opcode.FLOOR_DIV;
      case PERCENT:
        return Opcode.MODULO;
      case EQUALS_EQUALS:
        return Opcode.EQUAL;
      case NOT_EQUALS:
        return Opcode.NOT_EQUAL;
      case LESS:
        return Opcode.LESS;
      case LESS_EQUALS:
        return Opcode.LESS_EQUAL;
      case GREATER:
        return Opcode.GREATER;
      case GREATER_EQUALS:
        return Opcode.GREATER_EQUAL;
      case IN:
        return Opcode.IN;
      case NOT_IN:
        return Opcode.NOT_IN;
      case PIPE:
        return Opcode.BIT_OR;
      default:
        throw new UnsupportedOperationException("Unsupported binary operator: " + op);
    }
  }

  private Opcode getOpcodeForUnaryOp(TokenKind op) {
    switch (op) {
      case MINUS:
        return Opcode.NEGATE;
      case PLUS:
        return Opcode.POSITIVE;
      case NOT:
        return Opcode.NOT;
      case TILDE:
        return Opcode.BIT_NOT;
      default:
        throw new UnsupportedOperationException("Unsupported unary operator: " + op);
    }
  }

  private String newLabel(String prefix) {
    return prefix + "_" + labelOffsets.size();
  }

  private void markLabel(String label) {
    labelOffsets.put(label, builder.getCurrentOffset());
  }

  private void emitJump(Opcode jumpOpcode, String targetLabel, int lineNum) {
    int instrIndex = builder.getInstructionCount();
    builder.emit(jumpOpcode, 0, lineNum); // Placeholder offset
    jumpsToPatch.add(new PatchLocation(instrIndex, targetLabel));
  }

  private void patchJump(int instructionIndex, String targetLabel) {
    Integer targetOffset = labelOffsets.get(targetLabel);
    if (targetOffset == null) {
      throw new IllegalStateException("Undefined label: " + targetLabel);
    }
    // Note: This is a simplified patch - in practice, we'd need to modify
    // the instruction's operand to point to the target offset
  }

  private void patchJumps() {
    // Patch all forward jumps to their target offsets
    // This is a simplified implementation
    for (PatchLocation patch : jumpsToPatch) {
      patchJump(patch.instructionIndex, patch.targetLabel);
    }
  }

  private int getLine(Node node) {
    Location loc = node.getStartLocation();
    return loc != null ? loc.line() : -1;
  }
}
