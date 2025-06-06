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
package org.apache.solr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.SortedMap;
import org.apache.commons.io.file.PathUtils;
import org.apache.http.client.HttpClient;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.embedded.JettyConfig;
import org.apache.solr.embedded.JettySolrRunner;
import org.apache.solr.util.SolrJettyTestRule;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.junit.ClassRule;

@Deprecated // just use SolrJettyTestRule
public abstract class SolrJettyTestBase extends SolrTestCaseJ4 {
  @ClassRule public static SolrJettyTestRule solrClientTestRule = new SolrJettyTestRule();

  protected static JettySolrRunner createAndStartJetty(
      Path solrHome,
      String configFile,
      String schemaFile,
      String context,
      boolean stopAtShutdown,
      SortedMap<ServletHolder, String> extraServlets)
      throws Exception {
    // creates the data dir

    assert context == null || context.equals("/solr"); // deprecated

    JettyConfig jettyConfig =
        JettyConfig.builder().stopAtShutdown(stopAtShutdown).withServlets(extraServlets).build();

    Properties nodeProps = new Properties();
    if (configFile != null) nodeProps.setProperty("solrconfig", configFile);
    if (schemaFile != null) nodeProps.setProperty("schema", schemaFile);
    if (System.getProperty("solr.data.dir") == null) {
      nodeProps.setProperty("solr.data.dir", createTempDir().toRealPath().toString());
    }

    return createAndStartJetty(solrHome, nodeProps, jettyConfig);
  }

  protected static JettySolrRunner createAndStartJetty(
      Path solrHome, String configFile, String context) throws Exception {
    return createAndStartJetty(solrHome, configFile, null, context, true, null);
  }

  protected static JettySolrRunner createAndStartJetty(Path solrHome, JettyConfig jettyConfig)
      throws Exception {

    return createAndStartJetty(solrHome, new Properties(), jettyConfig);
  }

  protected static JettySolrRunner createAndStartJetty(Path solrHome) throws Exception {
    return createAndStartJetty(solrHome, new Properties(), JettyConfig.builder().build());
  }

  protected static JettySolrRunner createAndStartJetty(
      Path solrHome, Properties nodeProperties, JettyConfig jettyConfig) throws Exception {

    Path coresDir = createTempDir().resolve("cores");

    Properties props = new Properties();
    props.setProperty("name", DEFAULT_TEST_CORENAME);
    props.setProperty("configSet", "collection1");
    props.setProperty("config", "${solrconfig:solrconfig.xml}");
    props.setProperty("schema", "${schema:schema.xml}");

    writeCoreProperties(coresDir.resolve("core"), props, "RestTestBase");

    Properties nodeProps = new Properties(nodeProperties);
    nodeProps.setProperty("coreRootDirectory", coresDir.toString());
    nodeProps.setProperty("configSetBaseDir", solrHome.toString());

    solrClientTestRule.startSolr(solrHome, nodeProps, jettyConfig);
    return getJetty();
  }

  protected static JettySolrRunner getJetty() {
    return solrClientTestRule.getJetty();
  }

  /** URL to Solr */
  protected static String getBaseUrl() {
    return solrClientTestRule.getBaseUrl();
  }

  /** URL to the core */
  protected static String getCoreUrl() {
    return getBaseUrl() + "/" + DEFAULT_TEST_CORENAME;
  }

  protected SolrClient getSolrClient() {
    return solrClientTestRule.getSolrClient();
  }

  /**
   * Create a new solr client. If createJetty was called, a http implementation will be created,
   * otherwise an embedded implementation will be created. Subclasses should override for other
   * options.
   */
  public SolrClient createNewSolrClient() {
    return new HttpSolrClient.Builder(getBaseUrl())
        .withDefaultCollection(DEFAULT_TEST_CORENAME)
        .build();
  }

  protected HttpClient getHttpClient() {
    HttpSolrClient client = (HttpSolrClient) getSolrClient();
    return client.getHttpClient();
  }

  // Sets up the necessary config files for Jetty. At least some tests require that the solrconfig
  // from the test file directory are used, but some also require that the solr.xml file be
  // explicitly there as of SOLR-4817
  @Deprecated // Instead use a basic config + whatever is needed or default config
  protected static void setupJettyTestHome(Path solrHome, String collection) throws Exception {
    // TODO remove these sys props!
    System.setProperty("solr.test.sys.prop1", "propone");
    System.setProperty("solr.test.sys.prop2", "proptwo");
    copySolrHomeToTemp(solrHome, collection);
  }

  protected static void cleanUpJettyHome(Path solrHome) throws Exception {
    if (Files.exists(solrHome)) {
      PathUtils.deleteDirectory(solrHome);
    }
  }
}
