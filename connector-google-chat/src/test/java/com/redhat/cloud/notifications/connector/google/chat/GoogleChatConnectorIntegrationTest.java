package com.redhat.cloud.notifications.connector.google.chat;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.redhat.cloud.notifications.TestConstants;
import com.redhat.cloud.notifications.connector.v2.TestLifecycleManager;
import com.redhat.cloud.notifications.connector.v2.http.BaseHttpConnectorIntegrationTest;
import com.redhat.cloud.notifications.qute.templates.mapping.SubscriptionServices;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.redhat.cloud.notifications.MockServerLifecycleManager.getClient;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@QuarkusTestResource(TestLifecycleManager.class)
class GoogleChatConnectorIntegrationTest extends BaseHttpConnectorIntegrationTest {

    @Override
    protected String getRemoteServerPath() {
        return "/test-webhook";
    }

    @Override
    protected JsonObject buildIncomingPayload(String targetUrl) {
        JsonObject payload = new JsonObject();
        payload.put("org_id", "12345");
        payload.put("webhookUrl", targetUrl);

        // Add event_data required by GoogleChatMessageHandler
        Map<String, Object> source = new HashMap<>();
        source.put("event_type", Map.of("display_name", SubscriptionServices.ERRATA_NEW_SUBSCRIPTION_BUGFIX_ERRATA));
        source.put("application", Map.of("display_name", SubscriptionServices.ERRATA_APP_NAME));
        source.put("bundle", Map.of("display_name", SubscriptionServices.BUNDLE_NAME));

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("bundle", SubscriptionServices.BUNDLE_NAME);
        eventData.put("application", SubscriptionServices.ERRATA_APP_NAME);
        eventData.put("event_type", SubscriptionServices.ERRATA_NEW_SUBSCRIPTION_BUGFIX_ERRATA);
        eventData.put("events", new ArrayList<>());
        eventData.put("environment", Map.of("url", new ArrayList<>()));
        eventData.put("orgId", TestConstants.DEFAULT_ORG_ID);
        eventData.put("source", source);

        payload.put("eventData", eventData);

        return payload;
    }

    /**
     * Google Chat provides webhook URLs with encoded query parameters.
     * The handler must decode them before sending to avoid double-encoding
     * by the REST client.
     */
    @Test
    void testSuccessfulNotificationWithEncodedUrl() {
        String webhookPath = "/v1/spaces/AAAA/messages";

        // Stub WireMock to accept requests at the decoded path
        getClient().stubFor(
            post(urlPathEqualTo(webhookPath))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withBody("OK"))
        );

        // Simulate an encoded URL as Google provides: query separators are percent-encoded
        String encodedUrl = getMockServerUrl() + webhookPath + "%3Fkey%3DsomeKey%26token%3DsomeToken";

        JsonObject incomingPayload = buildIncomingPayload(encodedUrl);
        String cloudEventId = sendCloudEventMessage(incomingPayload);

        assertSuccessfulOutgoingMessage(cloudEventId, encodedUrl, 200);
        assertMetricsIncrement(1, 1, 0);

        // Verify WireMock received the request at the decoded path with query params
        List<LoggedRequest> requests = getClient().findAll(
            postRequestedFor(urlPathEqualTo(webhookPath))
        );
        assertEquals(1, requests.size());
        LoggedRequest request = requests.getFirst();
        assertEquals("someKey", request.queryParameter("key").firstValue());
        assertEquals("someToken", request.queryParameter("token").firstValue());
    }

    /**
     * Tests that a webhook URL with encoded characters in query parameter
     * values (e.g. base64 padding "==" encoded as "%3D%3D") is correctly
     * decoded before sending.
     */
    @Test
    void testSuccessfulNotificationWithEncodedQueryParamValues() {
        String webhookPath = "/v1/spaces/BBBB/messages";

        getClient().stubFor(
            post(urlPathEqualTo(webhookPath))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withBody("OK"))
        );

        // URL with encoded values in query params (e.g. token contains base64 with "==" → "%3D%3D")
        String encodedUrl = getMockServerUrl() + webhookPath + "?key=AIzaSyABC123&token=tokenValue%3D%3D";

        JsonObject incomingPayload = buildIncomingPayload(encodedUrl);
        String cloudEventId = sendCloudEventMessage(incomingPayload);

        assertSuccessfulOutgoingMessage(cloudEventId, encodedUrl, 200);
        assertMetricsIncrement(1, 1, 0);

        List<LoggedRequest> requests = getClient().findAll(
            postRequestedFor(urlPathEqualTo(webhookPath))
        );
        assertEquals(1, requests.size());
        LoggedRequest request = requests.getFirst();
        assertEquals("AIzaSyABC123", request.queryParameter("key").firstValue());
        // After URLDecoder.decode, %3D%3D becomes "==", so token value should end with "=="
        assertEquals("tokenValue==", request.queryParameter("token").firstValue(),
            "Token value should contain decoded '==' characters");
    }
}
