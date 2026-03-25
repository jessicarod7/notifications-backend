package com.redhat.cloud.notifications.connector.authentication.v2.sources;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.HashMap;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;

public class SourcesServerMockResource implements QuarkusTestResourceLifecycleManager {

    private static WireMockServer wireMockServer;

    @Override
    public Map<String, String> start() {

        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();

        String serverUrl = "http://localhost:" + wireMockServer.port();

        setupMockExpectations();

        Map<String, String> config = new HashMap<>();
        config.put("quarkus.rest-client.sources-oidc.url", serverUrl);

        System.out.println("Sources server mock started on port: " + wireMockServer.port());

        return config;
    }

    private static void setupMockExpectations() {

        // Mock Sources endpoints - Return 200 for correct Authorization header, 401 otherwise

        // Specific failure scenarios for testing error handling

        // Secret ID 404001 - Not Found
        wireMockServer.stubFor(get(urlEqualTo("/internal/v2.0/secrets/404001"))
            .atPriority(1)
            .withHeader("Authorization", equalTo("Bearer " + OidcServerMockResource.TEST_ACCESS_TOKEN))
            .willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "error": "Secret not found"
                    }
                    """)));

        // Secret ID 503001 - Service Unavailable (for retry testing)
        wireMockServer.stubFor(get(urlEqualTo("/internal/v2.0/secrets/503001"))
            .atPriority(1)
            .withHeader("Authorization", equalTo("Bearer " + OidcServerMockResource.TEST_ACCESS_TOKEN))
            .willReturn(aResponse()
                .withStatus(503)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "error": "Service temporarily unavailable"
                    }
                    """)));

        // Secret ID 500001 - Internal Server Error
        wireMockServer.stubFor(get(urlEqualTo("/internal/v2.0/secrets/500001"))
            .atPriority(1)
            .withHeader("Authorization", equalTo("Bearer " + OidcServerMockResource.TEST_ACCESS_TOKEN))
            .willReturn(aResponse()
                .withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "error": "Internal server error"
                    }
                    """)));

        // Secret ID 429001 - Rate limited (Too Many Requests)
        wireMockServer.stubFor(get(urlEqualTo("/internal/v2.0/secrets/429001"))
            .atPriority(1)
            .withHeader("Authorization", equalTo("Bearer " + OidcServerMockResource.TEST_ACCESS_TOKEN))
            .willReturn(aResponse()
                .withStatus(429)
                .withHeader("Content-Type", "application/json")
                .withHeader("Retry-After", "60")
                .withBody("""
                    {
                      "error": "Rate limit exceeded"
                    }
                    """)));

        // Mock Sources getById endpoint - Generic success case for any secret ID with valid auth
        // Priority 5 (lower priority) to match after specific error cases
        wireMockServer.stubFor(get(urlMatching("/internal/v2.0/secrets/[0-9]+"))
            .atPriority(5)
            .withHeader("Authorization", equalTo("Bearer " + OidcServerMockResource.TEST_ACCESS_TOKEN))
            .withHeader("x-rh-sources-org-id", matching(".*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "username": "test-username",
                      "password": "test-password"
                    }
                    """)));

        // Mock Sources getById endpoint - Generic unauthorized case for any secret ID without auth
        // Priority 10 (lowest priority) acts as catch-all for requests without proper auth
        wireMockServer.stubFor(get(urlMatching("/internal/v2.0/secrets/[0-9]+"))
            .atPriority(10)
            .willReturn(aResponse()
                .withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "error": "Unauthorized - missing or invalid Authorization header"
                    }
                    """)));

        System.out.println("Mock expectations configured successfully for Sources endpoints");
    }

    @Override
    public void stop() {
        if (wireMockServer != null) {
            wireMockServer.stop();
            System.out.println("Sources server mock stopped");
        }
    }
}
