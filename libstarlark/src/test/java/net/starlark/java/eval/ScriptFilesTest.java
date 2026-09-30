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

import static com.google.common.truth.Truth.assertWithMessage;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Runs each ScriptTest file in testdata/ as a JUnit test, so that Maven runs them. Failure
 * details are printed to stderr by ScriptTest.
 */
@RunWith(Parameterized.class)
public final class ScriptFilesTest {

  // json.star expects "nesting depth limit exceeded" from 10000 levels of nesting, which
  // relies on a StackOverflowError; a bounded stack makes that independent of the JVM default.
  private static final long STACK_SIZE = 512 * 1024;

  private static final File TESTDATA = new File("src/test/java/net/starlark/java/eval/testdata");

  @Parameters(name = "{0}")
  public static List<Object[]> files() {
    String[] names = TESTDATA.list((dir, name) -> name.endsWith(".star"));
    Arrays.sort(names);
    List<Object[]> params = new ArrayList<>();
    for (String name : names) {
      params.add(new Object[] {name});
    }
    return params;
  }

  private final String name;

  public ScriptFilesTest(String name) {
    this.name = name;
  }

  @Test
  public void runFile() throws Throwable {
    AtomicReference<Boolean> ok = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    Thread thread =
        new Thread(
            null,
            () -> {
              try {
                ok.set(ScriptTest.runFile(new File(TESTDATA, name)));
              } catch (Throwable t) {
                error.set(t);
              }
            },
            "script-" + name,
            STACK_SIZE);
    thread.start();
    thread.join();
    if (error.get() != null) {
      throw error.get();
    }
    assertWithMessage("%s failed; see stderr for details", name).that(ok.get()).isTrue();
  }
}
