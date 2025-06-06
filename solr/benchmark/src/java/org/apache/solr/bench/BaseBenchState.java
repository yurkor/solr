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
package org.apache.solr.bench;

import java.lang.invoke.MethodHandles;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.apache.commons.io.file.PathUtils;
import org.apache.solr.bench.generators.SolrGen;
import org.apache.solr.common.util.SuppressForbidden;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The type Base bench state. */
@State(Scope.Benchmark)
public class BaseBenchState {

  public static final long RANDOM_SEED;

  static {
    Long seed = Long.getLong("solr.bench.seed");

    if (seed == null) {
      String prop = System.getProperty("tests.seed"); // RandomizedTesting framework
      if (prop != null) {
        // if there is a test failure we remain reproducible based on the test seed:
        prop = prop.split(":")[0]; // main seed
        seed = Long.parseUnsignedLong(prop, 16);
      } else {
        seed = System.nanoTime();
      }
    }

    log("");
    log("benchmark random seed: " + seed);

    RANDOM_SEED = seed;
  }

  private static final SplittableRandom random = new SplittableRandom(RANDOM_SEED);

  /**
   * Gets random seed.
   *
   * @return the random seed
   */
  public static Long getRandomSeed() {
    return random.split().nextLong();
  }

  private static final AtomicBoolean HEAP_DUMPED = new AtomicBoolean();

  /** The constant QUIET_LOG. */
  public static final boolean QUIET_LOG = Boolean.getBoolean("quietLog");

  /**
   * Log.
   *
   * @param value the value
   */
  @SuppressForbidden(reason = "JMH uses std out for user output")
  public static void log(String value) {
    if (!QUIET_LOG) {
      System.out.println((value.isEmpty() ? "" : "--> ") + value);
    }
  }

  /** The Work dir. */
  public String workDir;

  /**
   * Do setup.
   *
   * @param benchmarkParams the benchmark params
   */
  @Setup(Level.Trial)
  public void doSetup(BenchmarkParams benchmarkParams) {

    workDir = System.getProperty("workBaseDir", "build/work");

    System.setProperty("solr.log.dir", workDir + "/logs");
    System.setProperty("solr.log.name", benchmarkParams.id());
  }

  /**
   * Do tear down.
   *
   * @param benchmarkParams the benchmark params
   * @throws Exception the exception
   */
  @TearDown(Level.Trial)
  public static void doTearDown(BenchmarkParams benchmarkParams) throws Exception {

    dumpHeap(benchmarkParams);

    Logger randomCountsLog =
        LoggerFactory.getLogger(
            MethodHandles.lookup().lookupClass().getName()
                + ".RandomCounts"); // nowarn_valid_logger

    SolrGen.countsReport().forEach(randomCountsLog::info);
    SolrGen.COUNTS.clear();
  }

  /**
   * Dump heap.
   *
   * @param benchmarkParams the benchmark params
   */
  @SuppressForbidden(reason = "access to force heapdump")
  public static void dumpHeap(BenchmarkParams benchmarkParams) {
    String heapDump = System.getProperty("dumpheap");
    if (heapDump != null) {

      boolean dumpHeap = HEAP_DUMPED.compareAndExchange(false, true);
      if (dumpHeap) {
        Path file = Path.of(heapDump);
        try {
          PathUtils.deleteDirectory(file);
          Files.createDirectories(file);
          Path dumpFile = file.resolve(benchmarkParams.id() + ".hprof");

          MBeanServer server = ManagementFactory.getPlatformMBeanServer();
          ObjectName hotSpot = ObjectName.getInstance("com.sun.management:type=HotSpotDiagnostic");
          Object[] params = new Object[] {dumpFile.toAbsolutePath().toString(), true};
          String signature[] = new String[] {"java.lang.String", "boolean"};
          server.invoke(hotSpot, "dumpHeap", params, signature);
        } catch (Exception e) {
          log("unable to dump heap " + e.getMessage());
        }
      }
    }
  }
}
