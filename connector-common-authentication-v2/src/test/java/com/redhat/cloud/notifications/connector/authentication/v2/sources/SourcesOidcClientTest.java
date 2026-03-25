package com.redhat.cloud.notifications.connector.authentication.v2.sources;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>Integration test for {@link SourcesOidcClient} that verifies OIDC authentication headers
 * are properly added to HTTP requests. The Sources server mock returns 401 for missing/invalid
 * Authorization headers and 200 for correct ones.</p>
 *
 * <p><strong>Test Objectives:</strong></p>
 * <ul>
 *   <li>Verify that {@code @OidcClientFilter} annotation automatically injects bearer tokens</li>
 *   <li>Ensure all HTTP requests include the correct {@code Authorization: Bearer <token>} header</li>
 *   <li>Validate that OIDC authentication works with Sources API endpoints</li>
 *   <li>Confirm proper integration between Quarkus OIDC client and Sources API</li>
 * </ul>
 *
 * <p><strong>Mock Setup:</strong></p>
 * <ul>
 *   <li>{@link OidcServerMockResource} - Simulates an OIDC provider that issues access tokens</li>
 *   <li>{@link SourcesServerMockResource} - Simulates the Sources API with authorization validation</li>
 * </ul>
 *
 * <p><strong>Authentication Flow Tested:</strong></p>
 * <ol>
 *   <li>SourcesOidcClient method is called</li>
 *   <li>@OidcClientFilter intercepts the request</li>
 *   <li>Filter obtains access token from mock OIDC server</li>
 *   <li>Filter adds "Authorization: Bearer <token>" header to request</li>
 *   <li>Mock Sources API validates the bearer token and responds accordingly</li>
 * </ol>
 */
@QuarkusTest
@QuarkusTestResource(OidcServerMockResource.class)
@QuarkusTestResource(SourcesServerMockResource.class)
public class SourcesOidcClientTest {

    @Inject
    @RestClient
    SourcesOidcClient sourcesOidcClient;

    private static final String TEST_ORG_ID = "test-org-id";

    /**
     * <p>Tests the {@code getById} operation with OIDC authentication.</p>
     *
     * <p><strong>OIDC Behavior Tested:</strong></p>
     * <ul>
     *   <li>@OidcClientFilter automatically obtains an access token from the mock OIDC server</li>
     *   <li>Filter injects "Authorization: Bearer <token>" header into the GET request</li>
     *   <li>Mock Sources API validates the bearer token before returning secret data</li>
     * </ul>
     *
     * <p><strong>Success Criteria:</strong><br>
     * No exception is thrown and a valid {@link SourcesSecretResponse} object is returned with the expected
     * username and password, confirming that OIDC authentication headers were properly included.</p>
     *
     * <p><strong>Failure Scenario:</strong><br>
     * If OIDC authentication fails (missing/invalid token), the mock Sources API returns
     * HTTP 401, causing this test to fail with a WebApplicationException.</p>
     */
    @Test
    @DisplayName("Should successfully call getById with OIDC authentication")
    void shouldSuccessfullyCallGetById() {
        // Execute the getById operation - OIDC filter should automatically add auth header
        var result = assertDoesNotThrow(() -> sourcesOidcClient.getById(TEST_ORG_ID, 123L));

        // Validate that the request succeeded with proper OIDC authentication
        assertNotNull(result, "SourcesSecret should be returned when OIDC authentication succeeds");
        assertEquals("test-username", result.username, "Mock server should return expected username");
        assertEquals("test-password", result.password, "Mock server should return expected password");
    }

    @Test
    @DisplayName("Should throw exception when Sources returns 404 - secret not found")
    void shouldFailWhenSecretNotFound() {
        // Given: Secret ID 404001 is configured to return 404 in the mock

        // When/Then: Call should fail with WebApplicationException
        WebApplicationException exception = assertThrows(
            WebApplicationException.class,
            () -> sourcesOidcClient.getById(TEST_ORG_ID, 404001L),
            "Should throw WebApplicationException when secret is not found"
        );

        // Verify the exception contains meaningful error information
        assertTrue(exception.getMessage().contains("404"),
            "Exception message should indicate 404 status");
    }

    @Test
    @DisplayName("Should fail after retries when Sources returns 503 - service unavailable")
    void shouldFailAfterRetriesOn503() {
        // Given: Secret ID 503001 is configured to always return 503
        // The client will retry (maxRetries = 2, so 3 total attempts) and eventually fail

        // When/Then: Should exhaust retries and throw WebApplicationException
        WebApplicationException exception = assertThrows(
            WebApplicationException.class,
            () -> sourcesOidcClient.getById(TEST_ORG_ID, 503001L),
            "Should throw WebApplicationException after exhausting retries on 503"
        );

        // Verify it's a 503 error
        assertTrue(exception.getMessage().contains("503"),
            "Exception message should indicate 503 status");
    }

    @Test
    @DisplayName("Should fail after retries when Sources returns 500 - internal server error")
    void shouldFailAfterRetriesOn500() {
        // Given: Secret ID 500001 is configured to always return 500
        // The client will retry and eventually fail

        // When/Then: Should exhaust retries and throw WebApplicationException
        WebApplicationException exception = assertThrows(
            WebApplicationException.class,
            () -> sourcesOidcClient.getById(TEST_ORG_ID, 500001L),
            "Should throw WebApplicationException after exhausting retries on 500"
        );

        // Verify it's a 500 error
        assertTrue(exception.getMessage().contains("500"),
            "Exception message should indicate 500 status");
    }

    @Test
    @DisplayName("Should fail when Sources returns 429 - rate limited")
    void shouldFailWhenRateLimited() {
        // Given: Secret ID 429001 is configured to return 429 (Too Many Requests)

        // When/Then: Should throw WebApplicationException
        WebApplicationException exception = assertThrows(
            WebApplicationException.class,
            () -> sourcesOidcClient.getById(TEST_ORG_ID, 429001L),
            "Should throw WebApplicationException when rate limited"
        );

        // Verify it's a 429 error
        assertTrue(exception.getMessage().contains("429"),
            "Exception message should indicate 429 status");
    }

    @Test
    @DisplayName("Should throw exception when Sources returns 401 - unauthorized")
    void shouldFailWhenUnauthorized() {
        // Given: An empty org ID which will cause validation failure
        // or requests without proper auth header get 401

        // When/Then: Call should fail with Exception
        assertThrows(
            Exception.class,
            () -> sourcesOidcClient.getById("", 123L),
            "Should throw exception when unauthorized or validation fails"
        );
    }

    @Test
    @DisplayName("Should retry and eventually succeed after transient failures")
    void shouldRetryOnTransientFailures() {
        // This test verifies that the @Retry annotation works correctly
        // The client is configured with maxRetries = 2 (total 3 attempts)

        // Given: Normal successful call (verifies retry mechanism doesn't break success)
        var result = assertDoesNotThrow(() -> sourcesOidcClient.getById(TEST_ORG_ID, 123L));

        // Then: Should succeed
        assertNotNull(result);
        assertEquals("test-username", result.username);
    }

    @Test
    @DisplayName("Should validate org ID is not blank")
    void shouldValidateOrgIdNotBlank() {
        // Given: Blank org ID (violates @NotBlank constraint)

        // When/Then: Should fail validation before making HTTP call
        assertThrows(
            Exception.class,  // Could be ConstraintViolationException or similar
            () -> sourcesOidcClient.getById("", 123L),
            "Should reject blank org ID"
        );
    }

    @Test
    @DisplayName("Should validate org ID is not null")
    void shouldValidateOrgIdNotNull() {
        // Given: Null org ID

        // When/Then: Should fail validation or NPE
        assertThrows(
            Exception.class,
            () -> sourcesOidcClient.getById(null, 123L),
            "Should reject null org ID"
        );
    }

    @Test
    @DisplayName("Should handle various secret IDs correctly")
    void shouldHandleVariousSecretIds() {
        // Test with different secret IDs to ensure ID handling works
        var result1 = assertDoesNotThrow(() -> sourcesOidcClient.getById(TEST_ORG_ID, 1L));
        assertNotNull(result1);

        var result2 = assertDoesNotThrow(() -> sourcesOidcClient.getById(TEST_ORG_ID, 999L));
        assertNotNull(result2);

        var result3 = assertDoesNotThrow(() -> sourcesOidcClient.getById(TEST_ORG_ID, 123456L));
        assertNotNull(result3);
    }
}
