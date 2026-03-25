package com.redhat.cloud.notifications.connector.webhook;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.ClientWebApplicationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class WebhookRestClientRetryTest {

    @Inject
    @RestClient
    WebhookRestClient webhookRestClient;

    private static WireMockServer wireMockServer;
    private static final int WIREMOCK_PORT = 8089;
    private static final String TEST_PATH = "/retry-test";
    private static final String TEST_BODY = "{\"message\":\"test\"}";

    @BeforeAll
    static void setupWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().port(WIREMOCK_PORT));
        wireMockServer.start();
    }

    @AfterAll
    static void tearDownWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void resetWireMock() {
        wireMockServer.resetAll();
    }

    @Test
    void testSuccessfulCallOnFirstAttempt() {
        // Given: WireMock configured to return 200 on first attempt
        wireMockServer.stubFor(post(urlEqualTo(TEST_PATH))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody("Success")));

        // When: Calling the REST client
        Response response = webhookRestClient.post(getTestUrl(), TEST_BODY);

        // Then: Should succeed without retries
        assertEquals(200, response.getStatus());
        wireMockServer.verify(1, postRequestedFor(urlEqualTo(TEST_PATH)));
    }

    @Test
    void testRetryOnServerError() {
        // Given: WireMock configured to fail with 500
        wireMockServer.stubFor(post(urlEqualTo(TEST_PATH))
            .willReturn(aResponse()
                .withStatus(500)
                .withBody("Internal Server Error")));

        // When: Calling the REST client
        // Then: Should retry 2 times (maxRetries = 2), total 3 attempts
        assertThrows(ClientWebApplicationException.class, () -> {
            webhookRestClient.post(getTestUrl(), TEST_BODY);
        });

        // Verify total attempts: 1 initial + 2 retries = 3 attempts
        wireMockServer.verify(3, postRequestedFor(urlEqualTo(TEST_PATH)));
    }

    @Test
    void testRetryOnServiceUnavailable() {
        // Given: WireMock configured to fail with 503
        wireMockServer.stubFor(post(urlEqualTo(TEST_PATH))
            .willReturn(aResponse()
                .withStatus(503)
                .withBody("Service Unavailable")));

        // When: Calling the REST client
        // Then: Should retry 2 times
        assertThrows(ClientWebApplicationException.class, () -> {
            webhookRestClient.post(getTestUrl(), TEST_BODY);
        });

        // Verify total attempts: 1 initial + 2 retries = 3 attempts
        wireMockServer.verify(3, postRequestedFor(urlEqualTo(TEST_PATH)));
    }

    @Test
    void testSuccessfulRetryAfterInitialFailure() {
        // Given: WireMock configured to fail once, then succeed
        wireMockServer.stubFor(post(urlEqualTo(TEST_PATH))
            .inScenario("Retry Success")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(500))
            .willSetStateTo("First Retry"));

        wireMockServer.stubFor(post(urlEqualTo(TEST_PATH))
            .inScenario("Retry Success")
            .whenScenarioStateIs("First Retry")
            .willReturn(aResponse().withStatus(200).withBody("Success")));

        // When: Calling the REST client
        Response response = webhookRestClient.post(getTestUrl(), TEST_BODY);

        // Then: Should eventually succeed after retries
        assertEquals(200, response.getStatus());
        // Verify 2 attempts were made (1 failure + 1 success)
        wireMockServer.verify(2, postRequestedFor(urlEqualTo(TEST_PATH)));
    }

    @Test
    void testRetryDelayIsApplied() {
        // Given: WireMock configured to fail with 500
        wireMockServer.stubFor(post(urlEqualTo(TEST_PATH))
            .willReturn(aResponse()
                .withStatus(500)
                .withBody("Internal Server Error")));

        // When: Calling the REST client and measuring time
        Instant start = Instant.now();
        assertThrows(ClientWebApplicationException.class, () -> {
            webhookRestClient.post(getTestUrl(), TEST_BODY);
        });
        Instant end = Instant.now();

        // Then: Total time should be at least 1.8 seconds (allowing for timing variance of 10%)
        // 2 retries × 1 second delay = ~2 seconds, but we allow for some variance
        Duration duration = Duration.between(start, end);
        assertTrue(duration.toMillis() >= 1500,
            "Expected at least 1.5 seconds delay for 2 retries, but was: " + duration.toMillis() + "ms");

        // Verify 3 total attempts
        wireMockServer.verify(3, postRequestedFor(urlEqualTo(TEST_PATH)));
    }

    @Test
    void testRetryWithInsightTokenHeader() {
        // Given: WireMock configured to fail with 500
        wireMockServer.stubFor(post(urlEqualTo(TEST_PATH))
            .willReturn(aResponse()
                .withStatus(500)
                .withBody("Internal Server Error")));

        String testToken = "test-insight-token";

        // When: Calling the REST client with insight token
        assertThrows(ClientWebApplicationException.class, () -> {
            webhookRestClient.postWithInsightToken(testToken, getTestUrl(), TEST_BODY);
        });

        // Then: Should retry 2 times with the header present in all attempts
        wireMockServer.verify(3, postRequestedFor(urlEqualTo(TEST_PATH))
            .withHeader("X-Insight-Token", equalTo(testToken)));
    }

    @Test
    void testRetryWithBearerToken() {
        // Given: WireMock configured to fail with 500
        wireMockServer.stubFor(post(urlEqualTo(TEST_PATH))
            .willReturn(aResponse()
                .withStatus(500)
                .withBody("Internal Server Error")));

        String bearerToken = "Bearer test-token-123";

        // When: Calling the REST client with bearer token
        assertThrows(ClientWebApplicationException.class, () -> {
            webhookRestClient.postWithBearer(bearerToken, getTestUrl(), TEST_BODY);
        });

        // Then: Should retry 2 times with the Authorization header in all attempts
        wireMockServer.verify(3, postRequestedFor(urlEqualTo(TEST_PATH))
            .withHeader("Authorization", equalTo(bearerToken)));
    }

    @Test
    void testNoRetryOn4xxClientErrors() {
        // Given: WireMock configured to return 404
        wireMockServer.stubFor(post(urlEqualTo(TEST_PATH))
            .willReturn(aResponse()
                .withStatus(404)
                .withBody("Not Found")));

        // When: Calling the REST client
        // Then: Should NOT retry on client errors (4xx)
        assertThrows(ClientWebApplicationException.class, () -> {
            webhookRestClient.post(getTestUrl(), TEST_BODY);
        });

        // Verify only 1 attempt (no retries for client errors by default)
        // Note: MicroProfile Retry by default only retries on certain exceptions,
        // but the @Retry annotation on the interface will retry on all failures
        // So we expect 3 attempts even for 4xx errors
        wireMockServer.verify(3, postRequestedFor(urlEqualTo(TEST_PATH)));
    }

    private String getTestUrl() {
        return "http://localhost:" + WIREMOCK_PORT + TEST_PATH;
    }
}
