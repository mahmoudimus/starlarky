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

import java.util.ArrayList;
import java.util.List;
import net.starlark.java.eval.*;

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
    try {
      return Starlark.add(a, b);
    } catch (EvalException e) {
      throw e;
    }
  }

  /** Starlark subtraction: a - b */
  public static Object subtract(Object a, Object b) throws EvalException {
    try {
      return Starlark.subtract(a, b);
    } catch (EvalException e) {
      throw e;
    }
  }

  /** Starlark multiplication: a * b */
  public static Object multiply(Object a, Object b) throws EvalException {
    try {
      return Starlark.multiply(a, b);
    } catch (EvalException e) {
      throw e;
    }
  }

  /** Starlark division: a / b */
  public static Object divide(Object a, Object b) throws EvalException {
    try {
      return Starlark.divide(a, b);
    } catch (EvalException e) {
      throw e;
    }
  }

  /** Starlark floor division: a // b */
  public static Object floorDivide(Object a, Object b) throws EvalException {
    try {
      return Starlark.floorDivide(a, b);
    } catch (EvalException e) {
      throw e;
    }
  }

  /** Starlark modulo: a % b */
  public static Object modulo(Object a, Object b) throws EvalException {
    try {
      return Starlark.remainder(a, b);
    } catch (EvalException e) {
      throw e;
    }
  }

  /** Starlark negation: -a */
  public static Object negate(Object a) throws EvalException {
    try {
      return Starlark.minus(a);
    } catch (EvalException e) {
      throw e;
    }
  }

  /** Starlark positive: +a */
  public static Object positive(Object a) throws EvalException {
    try {
      return Starlark.plus(a);
    } catch (EvalException e) {
      throw e;
    }
  }

  // ==================== Comparisons ====================

  /** Starlark equality: a == b */
  public static Object equal(Object a, Object b) {
    return a.equals(b);
  }

  /** Starlark inequality: a != b */
  public static Object notEqual(Object a, Object b) {
    return !a.equals(b);
  }

  /** Starlark less than: a < b */
  public static Object less(Object a, Object b) throws EvalException {
    return Starlark.compare(a, b) < 0;
  }

  /** Starlark less than or equal: a <= b */
  public static Object lessEqual(Object a, Object b) throws EvalException {
    return Starlark.compare(a, b) <= 0;
  }

  /** Starlark greater than: a > b */
  public static Object greater(Object a, Object b) throws EvalException {
    return Starlark.compare(a, b) > 0;
  }

  /** Starlark greater than or equal: a >= b */
  public static Object greaterEqual(Object a, Object b) throws EvalException {
    return Starlark.compare(a, b) >= 0;
  }

  /** Starlark 'in' operator: a in b */
  public static Object in(Object element, Object container) throws EvalException {
    return Starlark.isIn(element, container);
  }

  /** Starlark 'not in' operator: a not in b */
  public static Object notIn(Object element, Object container) throws EvalException {
    return !Starlark.isIn(element, container);
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
    return Starlark.binaryAnd(a, b);
  }

  /** Starlark bitwise or: a | b */
  public static Object bitOr(Object a, Object b) throws EvalException {
    return Starlark.binaryOr(a, b);
  }

  /** Starlark bitwise xor: a ^ b */
  public static Object bitXor(Object a, Object b) throws EvalException {
    return Starlark.binaryXor(a, b);
  }

  /** Starlark bitwise not: ~a */
  public static Object bitNot(Object a) throws EvalException {
    return Starlark.bitwiseNot(a);
  }

  /** Starlark left shift: a << b */
  public static Object leftShift(Object a, Object b) throws EvalException {
    return Starlark.leftShift(a, b);
  }

  /** Starlark right shift: a >> b */
  public static Object rightShift(Object a, Object b) throws EvalException {
    return Starlark.rightShift(a, b);
  }

  // ==================== Collections ====================

  /** Build an empty Starlark list. */
  public static Object buildList(int expectedSize) {
    return StarlarkList.newList(Mutability.IMMUTABLE);
  }

  /** Build a Starlark list from stack values. */
  public static Object buildListFromStack(Object[] values) {
    return StarlarkList.immutableCopyOf(values);
  }

  /** Build an empty Starlark dict. */
  public static Object buildDict(int expectedSize) {
    return Dict.empty();
  }

  /** Build a Starlark tuple from stack values. */
  public static Object buildTuple(Object[] values) {
    return Tuple.wrap(values);
  }

  // ==================== Indexing ====================

  /** Starlark index operation: obj[key] */
  public static Object index(Object object, Object key) throws EvalException {
    return Starlark.index(object, key);
  }

  /** Starlark index assignment: obj[key] = value */
  public static void setIndex(Object object, Object key, Object value) throws EvalException {
    Starlark.setIndex(object, key, value);
  }

  /** Starlark slice operation: obj[start:stop:step] */
  public static Object slice(Object object, Object start, Object stop, Object step)
      throws EvalException {
    return Starlark.slice(object, start, stop, step);
  }

  // ==================== Attributes ====================

  /** Get attribute from object. */
  public static Object getAttr(Object object, String name, StarlarkSemantics semantics)
      throws EvalException {
    return Starlark.getattr(semantics, object, name);
  }

  /** Set attribute on object. */
  public static void setAttr(Object object, String name, Object value) throws EvalException {
    Starlark.setField(object, name, value);
  }

  // ==================== Function Calls ====================

  /** Call a Starlark function. */
  public static Object call(StarlarkThread thread, Object fn, Object[] positional, Object[] named)
      throws EvalException, InterruptedException {
    return Starlark.call(thread, fn, positional, named);
  }

  /** Call a Starlark function with only positional arguments. */
  public static Object callPositional(StarlarkThread thread, Object fn, Object... args)
      throws EvalException, InterruptedException {
    return Starlark.call(thread, fn, args, new Object[0]);
  }

  // ==================== Iteration ====================

  /** Get iterator from iterable. */
  public static Object getIterator(Object iterable) throws EvalException {
    return Starlark.iter(iterable);
  }

  /** Get next element from iterator, returns null if exhausted. */
  public static Object iteratorNext(Object iterator) throws EvalException {
    return Starlark.iteratorNext(iterator);
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
