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
package org.apache.solr.schema;

import com.fasterxml.jackson.core.type.TypeReference;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.solr.client.api.model.CoreStatusResponse;
import org.apache.solr.client.solrj.JacksonContentWriter;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.cloud.AbstractFullDistribZkTestBase;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.zookeeper.KeeperException;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestCloudManagedSchema extends AbstractFullDistribZkTestBase {

  public TestCloudManagedSchema() {
    super();
  }

  @BeforeClass
  public static void initSysProperties() {
    System.setProperty("managed.schema.mutable", "false");
    System.setProperty("enable.update.log", "true");
  }

  @Override
  protected String getCloudSolrConfig() {
    return "solrconfig-managed-schema.xml";
  }

  @Test
  public void test() throws Exception {
    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set(CoreAdminParams.ACTION, CoreAdminParams.CoreAdminAction.STATUS.toString());
    QueryRequest request = new QueryRequest(params);
    request.setPath("/admin/cores");
    int which = r.nextInt(clients.size());

    // create a client that does not have the /collection1 as part of the URL.
    try (SolrClient rootClient =
        new HttpSolrClient.Builder(buildUrl(jettys.get(which).getLocalPort())).build()) {
      NamedList<?> namedListResponse = rootClient.request(request);
      final var statusByCore =
          JacksonContentWriter.DEFAULT_MAPPER.convertValue(
              namedListResponse.get("status"),
              new TypeReference<Map<String, CoreStatusResponse.SingleCoreData>>() {});
      final String coreName = statusByCore.keySet().stream().findFirst().get();
      final var collectionStatus = statusByCore.get(coreName);
      // Make sure the upgrade to managed schema happened
      assertEquals(
          "Schema resource name differs from expected name",
          "managed-schema.xml",
          collectionStatus.schema);
    }

    try (SolrZkClient zkClient =
        new SolrZkClient.Builder()
            .withUrl(zkServer.getZkHost())
            .withTimeout(30000, TimeUnit.MILLISECONDS)
            .build()) {
      // Make sure "DO NOT EDIT" is in the content of the managed schema
      String fileContent =
          getFileContentFromZooKeeper(zkClient, "/solr/configs/conf1/managed-schema.xml");
      assertTrue("Managed schema is missing", fileContent.contains("DO NOT EDIT"));

      // Make sure the original non-managed schema is no longer in ZooKeeper
      assertFileNotInZooKeeper(zkClient, "/solr/configs/conf1", "schema.xml");

      // Make sure the renamed non-managed schema is present in ZooKeeper
      fileContent = getFileContentFromZooKeeper(zkClient, "/solr/configs/conf1/schema.xml.bak");
      assertTrue("schema file doesn't contain '<schema'", fileContent.contains("<schema"));
    }
  }

  private String getFileContentFromZooKeeper(SolrZkClient zkClient, String fileName)
      throws KeeperException, InterruptedException {

    return (new String(zkClient.getData(fileName, null, null, true), StandardCharsets.UTF_8));
  }

  protected final void assertFileNotInZooKeeper(
      SolrZkClient zkClient, String parent, String fileName) throws Exception {
    List<String> kids = zkClient.getChildren(parent, null, true);
    for (String kid : kids) {
      if (kid.equalsIgnoreCase(fileName)) {
        String rawContent =
            new String(zkClient.getData(fileName, null, null, true), StandardCharsets.UTF_8);
        fail(
            "File '"
                + fileName
                + "' was unexpectedly found in ZooKeeper.  Content starts with '"
                + rawContent.substring(0, 100)
                + " [...]'");
      }
    }
  }
}
