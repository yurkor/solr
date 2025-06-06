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

package org.apache.solr.cli;

import java.io.StringReader;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrResponse;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.common.LinkedHashMapWriter;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.common.util.Utils;
import org.apache.solr.util.LogLevel;
import org.apache.solr.util.SecurityJson;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.resource.PathResourceFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.noggit.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@LogLevel("org.apache=INFO")
public class PackageToolTest extends SolrCloudTestCase {

  // Note for those who want to modify the jar files used in the packages used in this test:
  // You need to re-sign the jars for install step, as follows:
  // $ openssl dgst -sha1 -sign
  // ./solr/core/src/test-files/solr/question-answer-repository-private-key.pem
  // ./solr/core/src/test-files/solr/question-answer-repository/question-answer-request-handler-1.1.jar | openssl enc -base64
  // You can place the new signature thus obtained (removing any whitespaces) in the
  // repository.json.

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static LocalWebServer repositoryServer;

  @BeforeClass
  public static void setupClusterWithSecurityEnabled() throws Exception {
    System.setProperty("enable.packages", "true");

    configureCluster(2)
        .addConfig(
            "conf1", TEST_PATH().resolve("configsets").resolve("cloud-minimal").resolve("conf"))
        .addConfig(
            "conf3", TEST_PATH().resolve("configsets").resolve("cloud-minimal").resolve("conf"))
        .withSecurityJson(SecurityJson.SIMPLE)
        .configure();

    repositoryServer =
        new LocalWebServer(TEST_PATH().resolve("question-answer-repository").toString());
    repositoryServer.start();
  }

  @AfterClass
  public static void teardown() throws Exception {
    try {
      if (repositoryServer != null) {
        repositoryServer.stop();
      }
    } finally {
      System.clearProperty("enable.packages");
    }
  }

  private <T extends SolrRequest<? extends SolrResponse>> T withBasicAuth(T req) {
    req.setBasicAuthCredentials(SecurityJson.USER, SecurityJson.PASS);
    return req;
  }

  @Test
  public void testPackageTool() throws Exception {
    ToolRuntime runtime = new CLITestHelper.TestingRuntime(false);
    PackageTool tool = new PackageTool(runtime);

    String solrUrl = cluster.getJettySolrRunner(0).getBaseUrl().toString();

    run(
        tool,
        new String[] {
          "--solr-url", solrUrl, "list-installed", "--credentials", SecurityJson.USER_PASS
        });

    run(
        tool,
        new String[] {
          "--solr-url",
          solrUrl,
          "add-repo",
          "fullstory",
          "http://localhost:" + repositoryServer.getPort(),
          "--credentials",
          SecurityJson.USER_PASS
        });

    run(
        tool,
        new String[] {
          "--solr-url", solrUrl, "list-available", "--credentials", SecurityJson.USER_PASS
        });

    run(
        tool,
        new String[] {
          "--solr-url",
          solrUrl,
          "install",
          "question-answer:1.0.0",
          "--credentials",
          SecurityJson.USER_PASS
        });

    run(
        tool,
        new String[] {
          "--solr-url", solrUrl, "list-installed", "--credentials", SecurityJson.USER_PASS
        });

    withBasicAuth(CollectionAdminRequest.createCollection("abc", "conf1", 1, 1))
        .processAndWait(cluster.getSolrClient(), 10);
    withBasicAuth(CollectionAdminRequest.createCollection("def", "conf3", 1, 1))
        .processAndWait(cluster.getSolrClient(), 10);

    String rhPath = "/mypath2";

    run(
        tool,
        new String[] {
          "--solr-url",
          solrUrl,
          "list-deployed",
          "question-answer",
          "--credentials",
          SecurityJson.USER_PASS
        });

    // Leaving -p in for --param to test the deprecated value continues to work.
    run(
        tool,
        new String[] {
          "--solr-url",
          solrUrl,
          "deploy",
          "question-answer",
          "-y",
          "--collections",
          "abc",
          "--param",
          "RH-HANDLER-PATH=" + rhPath,
          "--credentials",
          SecurityJson.USER_PASS
        });
    assertPackageVersion(
        "abc", "question-answer", "1.0.0", rhPath, "1.0.0", SecurityJson.USER_PASS);

    run(
        tool,
        new String[] {
          "--solr-url",
          solrUrl,
          "list-deployed",
          "question-answer",
          "--credentials",
          SecurityJson.USER_PASS
        });

    run(
        tool,
        new String[] {
          "--solr-url",
          solrUrl,
          "list-deployed",
          "-c",
          "abc",
          "--credentials",
          SecurityJson.USER_PASS
        });

    // Should we test the "auto-update to latest" functionality or the default explicit deploy
    // functionality
    boolean autoUpdateToLatest = random().nextBoolean();

    if (autoUpdateToLatest) {
      log.info("Testing auto-update to latest installed");

      // This command pegs the version to the latest available
      run(
          tool,
          new String[] {
            "--solr-url",
            solrUrl,
            "deploy",
            "question-answer:latest",
            "-y",
            "--collections",
            "abc",
            "--credentials",
            SecurityJson.USER_PASS
          });
      assertPackageVersion(
          "abc", "question-answer", "$LATEST", rhPath, "1.0.0", SecurityJson.USER_PASS);

      run(
          tool,
          new String[] {
            "--solr-url",
            solrUrl,
            "install",
            "question-answer",
            "--credentials",
            SecurityJson.USER_PASS
          });
      assertPackageVersion(
          "abc", "question-answer", "$LATEST", rhPath, "1.1.0", SecurityJson.USER_PASS);
    } else {
      log.info("Testing explicit deployment to a different/newer version");

      run(
          tool,
          new String[] {
            "--solr-url",
            solrUrl,
            "install",
            "question-answer",
            "--credentials",
            SecurityJson.USER_PASS
          });
      assertPackageVersion(
          "abc", "question-answer", "1.0.0", rhPath, "1.0.0", SecurityJson.USER_PASS);

      // even if parameters are not passed in, they should be picked up from previous deployment
      if (random().nextBoolean()) {
        run(
            tool,
            new String[] {
              "--solr-url",
              solrUrl,
              "deploy",
              "--update",
              "-y",
              "question-answer",
              "--collections",
              "abc",
              "--param",
              "RH-HANDLER-PATH=" + rhPath,
              "--credentials",
              SecurityJson.USER_PASS
            });
      } else {
        run(
            tool,
            new String[] {
              "--solr-url",
              solrUrl,
              "deploy",
              "--update",
              "-y",
              "question-answer",
              "--collections",
              "abc",
              "--credentials",
              SecurityJson.USER_PASS
            });
      }
      assertPackageVersion(
          "abc", "question-answer", "1.1.0", rhPath, "1.1.0", SecurityJson.USER_PASS);
    }

    log.info("Running undeploy...");
    run(
        tool,
        new String[] {
          "--solr-url",
          solrUrl,
          "undeploy",
          "question-answer",
          "--collections",
          "abc",
          "--credentials",
          SecurityJson.USER_PASS
        });

    run(
        tool,
        new String[] {
          "--solr-url",
          solrUrl,
          "list-deployed",
          "question-answer",
          "--credentials",
          SecurityJson.USER_PASS
        });
  }

  void assertPackageVersion(
      String collection,
      String pkg,
      String version,
      String component,
      String componentVersion,
      String credentials)
      throws Exception {

    testForResponseElement(
        cluster.getJettySolrRunner(0).getBaseUrl().toString() + "/" + collection,
        "/config/params?meta=true",
        credentials,
        Arrays.asList("response", "params", "PKG_VERSIONS", pkg),
        version);

    testForResponseElement(
        cluster.getJettySolrRunner(0).getBaseUrl().toString() + "/" + collection,
        "/config/requestHandler?componentName=" + component + "&meta=true",
        credentials,
        Arrays.asList("config", "requestHandler", component, "_packageinfo_", "version"),
        componentVersion);
  }

  @SuppressWarnings({"rawtypes"})
  public static void testForResponseElement(
      String testServerBaseUrl,
      String uri,
      String credentials,
      List<String> jsonPath,
      Object expected)
      throws Exception {

    // Copied method from TestSolrConfigHandler.java
    // and then tweaked it to handle basic auth.  We need a nice pattern for doing
    // http gets/puts to various end points that can be used across all tests.
    // using the ApiTool makes me sad ;-)

    boolean success = false;

    ToolRuntime runtime = new CLITestHelper.TestingRuntime(false);
    ApiTool apiTool = new ApiTool(runtime);
    String response = apiTool.callGet(testServerBaseUrl + uri, credentials);

    LinkedHashMapWriter m =
        (LinkedHashMapWriter)
            Utils.MAPWRITEROBJBUILDER.apply(new JSONParser(new StringReader(response))).getVal();
    Object actual = Utils.getObjectByPath(m, false, jsonPath);

    if (Objects.equals(expected, actual)) {
      success = true;
    }

    assertTrue(
        StrUtils.formatString(
            "Could not get expected value  ''{0}'' for path ''{1}'' full output: {2},  from server:  {3}",
            expected, StrUtils.join(jsonPath, '/'), m.toString(), testServerBaseUrl),
        success);
  }

  private void run(PackageTool tool, String[] args) throws Exception {
    int res = tool.runTool(SolrCLI.processCommandLineArgs(tool, args));
    assertEquals("Non-zero status returned for: " + Arrays.toString(args), 0, res);
  }

  static class LocalWebServer {
    private final int port = 0;
    private final String resourceDir;
    Server server;
    ServerConnector connector;

    public LocalWebServer(String resourceDir) {
      this.resourceDir = resourceDir;
    }

    public int getPort() {
      return connector != null ? connector.getLocalPort() : port;
    }

    public void start() throws Exception {
      server = new Server();

      connector = new ServerConnector(server);
      connector.setPort(port);
      server.addConnector(connector);
      server.setStopAtShutdown(true);

      ResourceHandler resourceHandler = new ResourceHandler();
      resourceHandler.setBaseResource(new PathResourceFactory().newResource(resourceDir));

      Handler.Sequence sequence = new Handler.Sequence();
      sequence.addHandler(resourceHandler);
      sequence.addHandler(new DefaultHandler());

      server.setHandler(sequence);
      server.start();
    }

    public void stop() throws Exception {
      server.stop();
    }
  }
}
