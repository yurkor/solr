/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.jersey;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.List;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.security.AuthorizationContext;
import org.apache.solr.security.AuthorizationUtils;
import org.apache.solr.security.HttpServletAuthorizationContext;
import org.apache.solr.security.PermissionNameProvider;
import org.apache.solr.servlet.ServletUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A JAX-RS request filter that blocks or allows requests based on the authorization plugin
 * configured in security.json.
 */
@Provider
public class SolrRequestAuthorizer implements ContainerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @Context private ResourceInfo resourceInfo;

  public SolrRequestAuthorizer() {
    log.info("Creating a new SolrRequestAuthorizer");
  }

  @SuppressWarnings("unchecked")
  @Override
  public void filter(ContainerRequestContext requestContext) throws IOException {
    final CoreContainer coreContainer =
        (CoreContainer) requestContext.getProperty(RequestContextKeys.CORE_CONTAINER);
    final HttpServletRequest servletRequest =
        (HttpServletRequest) requestContext.getProperty(RequestContextKeys.HTTP_SERVLET_REQ);
    final HttpServletResponse servletResponse =
        (HttpServletResponse) requestContext.getProperty(RequestContextKeys.HTTP_SERVLET_RSP);
    final AuthorizationContext.RequestType requestType =
        (AuthorizationContext.RequestType)
            requestContext.getProperty(RequestContextKeys.REQUEST_TYPE);
    final List<String> collectionNames =
        (List<String>) requestContext.getProperty(RequestContextKeys.COLLECTION_LIST);
    final SolrParams solrParams =
        (SolrParams) requestContext.getProperty(RequestContextKeys.SOLR_PARAMS);

    /*
     * HttpSolrCall has more involved logic to check whether a request requires authorization, but most of that
     * revolves around checking for (1) static paths (e.g. index.html) or (2) HttpSolrCall 'action's that don't need
     * authorization (e.g. request-forwarding)
     *
     * Since we don't invoke Jersey code in those particular cases we can ignore those checks here.
     */
    if (coreContainer.getAuthorizationPlugin() == null
        || isAlreadyAuthorizedByPKI(coreContainer, servletRequest)) {
      return;
    }

    final AuthorizationContext authzContext =
        getAuthzContext(servletRequest, requestType, collectionNames, solrParams);
    log.debug("Attempting authz with context {}", authzContext);
    AuthorizationUtils.AuthorizationFailure authzFailure =
        AuthorizationUtils.authorize(servletRequest, servletResponse, coreContainer, authzContext);
    if (authzFailure != null) {
      final Response failureResponse =
          Response.status(authzFailure.getStatusCode()).entity(authzFailure.getMessage()).build();
      requestContext.abortWith(failureResponse);
    }
  }

  private boolean isAlreadyAuthorizedByPKI(CoreContainer coreContainer, HttpServletRequest req) {
    if (coreContainer.getPkiAuthenticationSecurityBuilder() != null
        && req.getUserPrincipal() != null) {
      boolean needsAuthz =
          coreContainer.getPkiAuthenticationSecurityBuilder().needsAuthorization(req);
      log.debug("PkiAuthenticationPlugin says authorization required : {} ", needsAuthz);
      return !needsAuthz;
    }

    return false;
  }

  private AuthorizationContext getAuthzContext(
      HttpServletRequest servletRequest,
      AuthorizationContext.RequestType reqType,
      List<String> collectionNames,
      SolrParams solrParams) {
    return new HttpServletAuthorizationContext(servletRequest) {

      @Override
      public List<CollectionRequest> getCollectionRequests() {
        return AuthorizationUtils.getCollectionRequests(
            ServletUtils.getPathAfterContext(servletRequest), collectionNames, solrParams);
      }

      @Override
      public Object getHandler() {
        return new PermissionNameProvider() {
          @Override
          public Name getPermissionName(AuthorizationContext request) {
            return resourceInfo.getResourceMethod().getAnnotation(PermissionName.class).value();
          }
        };
      }

      @Override
      public SolrParams getParams() {
        return solrParams;
      }

      @Override
      public RequestType getRequestType() {
        return reqType;
      }
    };
  }
}
