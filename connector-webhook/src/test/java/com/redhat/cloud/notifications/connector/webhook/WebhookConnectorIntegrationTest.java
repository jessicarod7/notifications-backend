package com.redhat.cloud.notifications.connector.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.redhat.cloud.notifications.connector.authentication.v2.AuthenticationLoader;
import com.redhat.cloud.notifications.connector.authentication.v2.AuthenticationResult;
import com.redhat.cloud.notifications.connector.authentication.v2.sources.SourcesPskClient;
import com.redhat.cloud.notifications.connector.authentication.v2.sources.SourcesSecretResponse;
import com.redhat.cloud.notifications.connector.v2.TestLifecycleManager;
import com.redhat.cloud.notifications.connector.v2.http.BaseHttpConnectorIntegrationTest;
import com.redhat.cloud.notifications.connector.v2.http.models.NotificationToConnectorHttp;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.smallrye.reactive.messaging.ce.IncomingCloudEventMetadata;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.apache.commons.lang3.RandomStringUtils;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.ClientWebApplicationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.redhat.cloud.notifications.connector.authentication.v2.AuthenticationType.BEARER;
import static com.redhat.cloud.notifications.connector.authentication.v2.AuthenticationType.SECRET_TOKEN;
import static com.redhat.cloud.notifications.connector.webhook.WebhookMessageHandler.X_INSIGHT_TOKEN_HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.wildfly.common.Assert.assertFalse;

@QuarkusTest
@QuarkusTestResource(TestLifecycleManager.class)
class WebhookConnectorIntegrationTest extends BaseHttpConnectorIntegrationTest {

    @Inject
    ObjectMapper objectMapper;

    @Inject
    WebhookMessageHandler webhookMessageHandler;

    @InjectSpy
    AuthenticationLoader authenticationLoader;

    @Override
    protected String getRemoteServerPath() {
        return "/test-webhook";
    }

    boolean addInsightsToken = false;
    boolean addBearerToken = false;
    NotificationToConnectorHttp notificationToConnectorHttp;

    @InjectMock
    @RestClient
    SourcesPskClient sourcesClient;

    @Override
    protected JsonObject buildIncomingPayload(String targetUrl) {
        notificationToConnectorHttp = new NotificationToConnectorHttp();
        NotificationToConnectorHttp.EndpointProperties endpointProperties = new NotificationToConnectorHttp.EndpointProperties();
        endpointProperties.setTargetUrl(targetUrl);
        notificationToConnectorHttp.setEndpointProperties(endpointProperties);
        notificationToConnectorHttp.setOrgId("12345");
        notificationToConnectorHttp.setPayload((new JsonObject())
            .put("random_data", RandomStringUtils.secure().nextAlphanumeric(50)));

        JsonObject authentication = new JsonObject();
        if (addInsightsToken) {
            authentication.put("type", SECRET_TOKEN.name());
            authentication.put("secretId", 123L);
        }
        if (addBearerToken) {
            authentication.put("type", BEARER.name());
            authentication.put("secretId", 456L);
        }
        if (!authentication.isEmpty()) {
            notificationToConnectorHttp.setAuthentication(authentication);
        }

        return objectMapper.convertValue(notificationToConnectorHttp, JsonObject.class);
    }

    @Test
    void testSuccessfulNotificationWithInsightHeader() {
        SourcesSecretResponse sourcesSecret = new SourcesSecretResponse();
        sourcesSecret.username = "john_doe";
        sourcesSecret.password = "passw0rd_insightHeader";
        when(sourcesClient.getById(anyString(), anyString(), anyLong())).thenReturn(sourcesSecret);
        try {
            addInsightsToken = true;
            testSuccessfulNotification();
        } finally {
            addInsightsToken = false;
        }
    }

    @Test
    void testSuccessfulNotificationWithBearerToken() {
        SourcesSecretResponse sourcesSecret = new SourcesSecretResponse();
        sourcesSecret.username = "john_doe";
        sourcesSecret.password = "passw0rd_bearer";
        when(sourcesClient.getById(anyString(), anyString(), anyLong())).thenReturn(sourcesSecret);
        try {
            addBearerToken = true;
            testSuccessfulNotification();
        } finally {
            addBearerToken = false;
        }
    }

    @Test
    void testSourcesClientFailureWithBearerToken() {
        ClientWebApplicationException exception = new ClientWebApplicationException("Sources is not written in java");
        when(sourcesClient.getById(anyString(), anyString(), anyLong())).thenThrow(exception);
        try {
            addBearerToken = true;

            String targetUrl = getConnectorSpecificTargetUrl() + getRemoteServerPath();
            JsonObject incomingPayload = buildIncomingPayload(targetUrl);

            // Send message via InMemory messaging
            String cloudEventId = sendCloudEventMessage(incomingPayload);

            // Assert failed response
            assertFailedOutgoingMessage(cloudEventId, "Error fetching authentication data");

        } finally {
            addBearerToken = false;
        }
    }

    @Test
    void testUnsupportedAuthenticationType() {
        // Create a mock AuthenticationResult with null authenticationType to simulate an edge case
        AuthenticationResult mockResult = mock(AuthenticationResult.class);
        mockResult.username = "test_user";
        mockResult.password = "test_password";
        mockResult.authenticationType = null;

        when(authenticationLoader.fetchAuthenticationData(anyString(), any())).thenReturn(Optional.of(mockResult));

        // Create test data
        JsonObject payload = new JsonObject()
            .put("org_id", "12345")
            .put("endpoint_properties", new JsonObject().put("url", "http://example.com"))
            .put("payload", new JsonObject().put("test", "data"));

        IncomingCloudEventMetadata<JsonObject> mockMetadata = mock(IncomingCloudEventMetadata.class);
        when(mockMetadata.getData()).thenReturn(payload);

        // Execute and verify exception is thrown
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            webhookMessageHandler.handle(mockMetadata);
        });

        assertEquals("Unsupported authentication type: null", exception.getMessage());
    }

    @Override
    protected void afterSuccessfulNotification(List<LoggedRequest> loggedRequests) {
        for (LoggedRequest loggedRequest : loggedRequests) {
            // check body
            assertEquals(notificationToConnectorHttp.getPayload(), new JsonObject(loggedRequest.getBodyAsString()));

            // check headers
            HttpHeaders headers = loggedRequest.getHeaders();
            assertEquals("application/json;charset=UTF-8", headers.getHeader("Content-Type").firstValue());
            if (addInsightsToken) {
                assertEquals("passw0rd_insightHeader", headers.getHeader(X_INSIGHT_TOKEN_HEADER).firstValue());
            } else {
                assertFalse(headers.keys().contains(X_INSIGHT_TOKEN_HEADER));
            }
            if (addBearerToken) {
                assertEquals("Bearer passw0rd_bearer", headers.getHeader("Authorization").firstValue());
            } else {
                assertFalse(headers.keys().contains("Authorization"));
            }
        }
    }
}
