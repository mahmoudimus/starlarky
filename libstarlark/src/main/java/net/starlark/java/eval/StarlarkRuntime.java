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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.starlark.java.syntax.TokenKind;

/**
 * Runtime support methods for JVM-compiled Starlark code.
 *
 * <p>This class provides static methods that are called by generated JVM bytecode
 * to implement Starlark semantics. These methods handle:
 * <ul>
 *   <li>Arithmetic operations with proper Starlark semantics
 *   <li>Comparison operations
 *   <li>Collection construction and manipulation
 *   <li>Type conversions and boxing
 *   <li>Truth value testing
 * </ul>
 *
 * <p>All methods preserve stack trace information by throwing EvalException
 * with proper context when errors occur.
 */
public final class StarlarkRuntime {

  private StarlarkRuntime() {
    // Static utility class
  }

  /**
   * Returns a default StarlarkThread for runtime operations.
   * This thread has default semantics and is used for operations that don't
   * require specific thread context.
   */
  private static StarlarkThread getDefaultThread() {
    return new StarlarkThread(Mutability.IMMUTABLE, StarlarkSemantics.DEFAULT);
  }

  // ==================== Constants ====================

  /** Returns Starlark None value. */
  public static Object none() {
    return Starlark.NONE;
  }

  /** Returns Starlark True value. */
  public static Object constTrue() {
    return Boolean.TRUE;
  }

  /** Returns Starlark False value. */
  public static Object constFalse() {
    return Boolean.FALSE;
  }

  // ==================== Boxing ====================

  /** Boxes an int to Object for Starlark. */
  public static Object boxInt(int value) {
    return StarlarkInt.of(value);
  }

  /** Boxes a long to Object for Starlark. */
  public static Object boxLong(long value) {
    return StarlarkInt.of(value);
  }

  /** Boxes a double to Object for Starlark. */
  public static Object boxDouble(double value) {
    return StarlarkFloat.of(value);
  }

  // ==================== Arithmetic ====================

  /** Starlark addition: a + b */
  public static Object add(Object a, Object b) throws EvalException {
    return EvalUtils.binaryOp(TokenKind.PLUS, a, b, getDefaultThread());
  }

  /** Starlark subtraction: a - b */
  public static Object subtract(Object a, Object b) throws EvalException {
    return EvalUtils.binaryOp(TokenKind.MINUS, a, b, getDefaultThread());
  }

  /** Starlark multiplication: a * b */
  public static Object multiply(Object a, Object b) throws EvalException {
    return EvalUtils.binaryOp(TokenKind.STAR, a, b, getDefaultThread());
  }

  /** Starlark division: a / b */
  public static Object divide(Object a, Object b) throws EvalException {
    return EvalUtils.binaryOp(TokenKind.SLASH, a, b, getDefaultThread());
  }

  /** Starlark floor division: a // b */
  public static Object floorDivide(Object a, Object b) throws EvalException {
    return EvalUtils.binaryOp(TokenKind.SLASH_SLASH, a, b, getDefaultThread());
  }

  /** Starlark modulo: a % b */
  public static Object modulo(Object a, Object b) throws EvalException {
    return EvalUtils.binaryOp(TokenKind.PERCENT, a, b, getDefaultThread());
  }

  /** Starlark negation: -a */
  public static Object negate(Object a) throws EvalException {
    return EvalUtils.unaryOp(TokenKind.MINUS, a);
  }

  /** Starlark positive: +a */
  public static Object positive(Object a) throws EvalException {
    return EvalUtils.unaryOp(TokenKind.PLUS, a);
  }

  // ==================== Comparisons ====================

  /** Starlark equality: a == b */
  public static Object equal(Object a, Object b) throws EvalException {
    return EvalUtils.binaryOp(TokenKind.EQUALS_EQUALS, a, b, getDefaultThread());
  }

  /** Starlark inequality: a != b */
  public static Object notEqual(Object a, Object b) throws EvalException {
    return EvalUtils.binaryOp(TokenKind.NOT_EQUALS, a, b, getDefaultThread());
  }

  /** Starlark less than: a < b */
  public static Object less(Object a, Object b) throws EvalException {
    return EvalUtils.binaryOp(TokenKind.LESS, a, b, getDefaultThread());
  }

  /** Starlark less than or equal: a <= b */
  public static Object lessEqual(Object a, Object b) throws EvalException {
    return EvalUtils.binaryOp(TokenKind.LESS_EQUALS, a, b, getDefaultThread());
  }

  /** Starlark greater than: a > b */
  public static Object greater(Object a, Object b) throws EvalException {
    return EvalUtils.binaryOp(TokenKind.GREATER, a, b, getDefaultThread());
  }

  /** Starlark greater than or equal: a >= b */
  public static Object greaterEqual(Object a, Object b) throws EvalException {
    return EvalUtils.binaryOp(TokenKind.GREATER_EQUALS, a, b, getDefaultThread());
  }

  /** Starlark 'in' operator: a in b */
  public static Object in(Object element, Object container) throws EvalException {
    return EvalUtils.binaryOp(TokenKind.IN, element, container, getDefaultThread());
  }

  /** Starlark 'not in' operator: a not in b */
  public static Object notIn(Object element, Object container) throws EvalException {
    return EvalUtils.binaryOp(TokenKind.NOT_IN, element, container, getDefaultThread());
  }

  // ==================== Logical ====================

  /** Starlark truth test - returns boolean for JVM control flow. */
  public static boolean truth(Object value) {
    return Starlark.truth(value);
  }

  /** Starlark not operator: not a */
  public static Object not(Object a) {
    return !Starlark.truth(a);
  }

  // ==================== Bitwise ====================

  /** Starlark bitwise and: a & b */
  public static Object bitAnd(Object a, Object b) throws EvalException {
    return EvalUtils.binaryOp(TokenKind.AMPERSAND, a, b, getDefaultThread());
  }

  /** Starlark bitwise or: a | b */
  public static Object bitOr(Object a, Object b) throws EvalException {
    return EvalUtils.binaryOp(TokenKind.PIPE, a, b, getDefaultThread());
  }

  /** Starlark bitwise xor: a ^ b */
  public static Object bitXor(Object a, Object b) throws EvalException {
    return EvalUtils.binaryOp(TokenKind.CARET, a, b, getDefaultThread());
  }

  /** Starlark bitwise not: ~a */
  public static Object bitNot(Object a) throws EvalException {
    return EvalUtils.unaryOp(TokenKind.TILDE, a);
  }

  /** Starlark left shift: a << b */
  public static Object leftShift(Object a, Object b) throws EvalException {
    return EvalUtils.binaryOp(TokenKind.LESS_LESS, a, b, getDefaultThread());
  }

  /** Starlark right shift: a >> b */
  public static Object rightShift(Object a, Object b) throws EvalException {
    return EvalUtils.binaryOp(TokenKind.GREATER_GREATER, a, b, getDefaultThread());
  }

  // ==================== Collections ====================

  /** Build an empty Starlark list. */
  public static Object buildList(int expectedSize) {
    return StarlarkList.newList(Mutability.IMMUTABLE);
  }

  /** Build a Starlark list from stack values. */
  public static Object buildListFromStack(Object[] values) {
    return StarlarkList.immutableCopyOf(Arrays.asList(values));
  }

  /** Build an empty Starlark dict. */
  public static Object buildDict(int expectedSize) {
    return Dict.empty();
  }

  /** Build a Starlark tuple from stack values. */
  public static Object buildTuple(Object[] values) {
    return Tuple.of(values);
  }

  // ==================== Indexing ====================

  /** Starlark index operation: obj[key] */
  public static Object index(Object object, Object key) throws EvalException {
    return EvalUtils.index(getDefaultThread(), object, key);
  }

  /** Starlark index assignment: obj[key] = value */
  public static void setIndex(Object object, Object key, Object value) throws EvalException {
    EvalUtils.setIndex(getDefaultThread(), object, key, value);
  }

  /** Starlark slice operation: obj[start:stop:step] */
  public static Object slice(Object object, Object start, Object stop, Object step)
      throws EvalException {
    return Starlark.slice(Mutability.IMMUTABLE, object, start, stop, step);
  }

  // ==================== Attributes ====================

  /** Get attribute from object. */
  public static Object getAttr(Object object, String name, StarlarkSemantics semantics)
      throws EvalException, InterruptedException {
    return Starlark.getattr(Mutability.IMMUTABLE, semantics, object, name, null);
  }

  /** Set attribute on object. */
  public static void setAttr(Object object, String name, Object value) throws EvalException {
    EvalUtils.setField(object, name, value);
  }

  // ==================== Function Calls ====================

  /** Call a Starlark function. */
  public static Object call(StarlarkThread thread, Object fn, Object[] positional, Object[] named)
      throws EvalException, InterruptedException {
    java.util.HashMap<String, Object> kwargs = new java.util.HashMap<>();
    for (int i = 0; i < named.length; i += 2) {
      kwargs.put((String) named[i], named[i + 1]);
    }
    return Starlark.call(thread, fn, Arrays.asList(positional), kwargs);
  }

  /** Call a Starlark function with only positional arguments. */
  public static Object callPositional(StarlarkThread thread, Object fn, Object... args)
      throws EvalException, InterruptedException {
    return Starlark.call(thread, fn, Arrays.asList(args), java.util.Collections.emptyMap());
  }

  // ==================== Iteration ====================

  /** Get iterator from iterable. */
  public static Object getIterator(Object iterable) throws EvalException {
    Iterable<?> iter = Starlark.toIterable(iterable);
    return iter.iterator();
  }

  /** Get next element from iterator, returns null if exhausted. */
  public static Object iteratorNext(Object iterator) throws EvalException {
    @SuppressWarnings("unchecked")
    java.util.Iterator<Object> iter = (java.util.Iterator<Object>) iterator;
    return iter.hasNext() ? iter.next() : null;
  }

  // ==================== Type Checking ====================

  /** Check if object is None. */
  public static boolean isNone(Object obj) {
    return Starlark.isNullOrNone(obj);
  }

  /** Get the Starlark type name of an object. */
  public static String typeName(Object obj) {
    return Starlark.type(obj);
  }

  // ==================== String Operations ====================

  /** Starlark string formatting: "format" % args */
  public static Object formatString(String format, Object args) throws EvalException {
    // Simplified - full implementation would handle tuple args
    return String.format(format.replace("%s", "%s").replace("%d", "%s"), args);
  }

  /** String concatenation. */
  public static Object concatStrings(String a, String b) {
    return a + b;
  }

  // ==================== Exception Helpers ====================

  /** Throw an EvalException with the given message. */
  public static void throwError(String message) throws EvalException {
    throw new EvalException(message);
  }

  /** Throw an EvalException for type error. */
  public static void throwTypeError(String expected, Object actual) throws EvalException {
    throw new EvalException(
        String.format("expected %s, got %s", expected, Starlark.type(actual)));
  }

  /** Throw an EvalException for undefined name. */
  public static void throwUndefinedError(String name) throws EvalException {
    throw new EvalException(String.format("name '%s' is not defined", name));
  }
}
