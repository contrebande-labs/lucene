/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.lucene.util;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;

/** A provider of VectorUtil implementations. */
interface VectorUtilProvider {

  /** Calculates the dot product of the given float arrays. */
  float dotProduct(float[] a, float[] b);

  /** Returns the cosine similarity between the two vectors. */
  float cosine(float[] v1, float[] v2);

  /** Returns the sum of squared differences of the two vectors. */
  float squareDistance(float[] a, float[] b);

  /** Returns the dot product computed over signed bytes. */
  int dotProduct(byte[] a, byte[] b);

  /** Returns the cosine similarity between the two byte vectors. */
  float cosine(byte[] a, byte[] b);

  /** Returns the sum of squared differences of the two byte vectors. */
  int squareDistance(byte[] a, byte[] b);

  // -- provider lookup mechanism

  static final Logger LOG = Logger.getLogger(VectorUtilProvider.class.getName());

  static VectorUtilProvider lookup() {
    final int runtimeVersion = Runtime.version().feature();
    if (runtimeVersion == 20) {
      // is locale sane (only buggy in Java 20)
      if (runtimeVersion <= 20 && !hasWorkingDefaultLocale()) {
        LOG.warning(
            "Java runtime is using a buggy default locale; Java vector incubator API can't be enabled: "
                + Locale.getDefault());
        return new VectorUtilDefaultProvider();
      }
      // is the incubator module present and readable (JVM providers may to exclude them or it is
      // build with jlink)
      if (!vectorModulePresentAndReadable()) {
        LOG.warning(
            "Java vector incubator module is not readable. For optimal vector performance, pass '--add-modules jdk.incubator.vector' to enable Vector API.");
        return new VectorUtilDefaultProvider();
      }
      try {
        if (System.getProperty("java.vm.info").contains("emulated-client")) {
          LOG.warning("C2 compiler is disabled: Java vector incubator API can't be enabled");
          return new VectorUtilDefaultProvider();
        }
      } catch (SecurityException e) {
        LOG.warning(
            "SecurityManager denies permission to java.vm.info system property, trying anyway and hoping for the best");
      }
      try {
        // we use method handles with lookup, so we do not need to deal with setAccessible as we
        // have private access through the lookup:
        final var lookup = MethodHandles.lookup();
        final var cls = lookup.findClass("org.apache.lucene.util.VectorUtilPanamaProvider");
        final var constr = lookup.findConstructor(cls, MethodType.methodType(void.class));
        try {
          return (VectorUtilProvider) constr.invoke();
        } catch (UnsupportedOperationException uoe) {
          // not supported because preferred vector size too small or similar
          LOG.warning("Java vector incubator API was not enabled. " + uoe.getMessage());
          return new VectorUtilDefaultProvider();
        } catch (RuntimeException | Error e) {
          throw e;
        } catch (Throwable th) {
          throw new AssertionError(th);
        }
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new LinkageError(
            "VectorUtilPanamaProvider is missing correctly typed constructor", e);
      } catch (ClassNotFoundException cnfe) {
        throw new LinkageError("VectorUtilPanamaProvider is missing in Lucene JAR file", cnfe);
      }
    } else if (runtimeVersion >= 21) {
      LOG.warning(
          "You are running with Java 21 or later. To make full use of the Vector API, please update Apache Lucene.");
    }
    return new VectorUtilDefaultProvider();
  }

  private static boolean vectorModulePresentAndReadable() {
    var opt =
        ModuleLayer.boot().modules().stream()
            .filter(m -> m.getName().equals("jdk.incubator.vector"))
            .findFirst();
    if (opt.isPresent()) {
      VectorUtilProvider.class.getModule().addReads(opt.get());
      return true;
    }
    return false;
  }

  // Workaround for JDK-8301190, avoids assertion when default locale is say tr.
  private static boolean hasWorkingDefaultLocale() {
    return Objects.equals("I", "i".toUpperCase(Locale.getDefault()));
  }
}
