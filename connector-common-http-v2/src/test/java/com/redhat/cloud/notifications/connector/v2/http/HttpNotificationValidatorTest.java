package com.redhat.cloud.notifications.connector.v2.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.cloud.notifications.connector.v2.http.models.NotificationToConnectorHttp;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.ce.IncomingCloudEventMetadata;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
class HttpNotificationValidatorTest {

    @Inject
    HttpNotificationValidator httpNotificationValidator;

    @Inject
    ObjectMapper objectMapper;

    @Test
    void testParseAndValidateSuccess() {
        // Given - valid notification
        NotificationToConnectorHttp notification = new NotificationToConnectorHttp();
        notification.setOrgId("12345");

        NotificationToConnectorHttp.EndpointProperties endpointProperties = new NotificationToConnectorHttp.EndpointProperties();
        endpointProperties.setTargetUrl("https://example.com/webhook");
        notification.setEndpointProperties(endpointProperties);

        JsonObject payloadData = new JsonObject().put("message", "test");
        notification.setPayload(payloadData);

        JsonObject cloudEventData = objectMapper.convertValue(notification, JsonObject.class);

        IncomingCloudEventMetadata<JsonObject> mockMetadata = mock(IncomingCloudEventMetadata.class);
        when(mockMetadata.getData()).thenReturn(cloudEventData);

        // When
        NotificationToConnectorHttp result = httpNotificationValidator.parseAndValidate(mockMetadata);

        // Then
        assertNotNull(result);
        assertEquals("12345", result.getOrgId());
        assertEquals("https://example.com/webhook", result.getEndpointProperties().getTargetUrl());
        assertNotNull(result.getPayload());
    }

    @Test
    void testParseAndValidateFailsForNullEndpointProperties() {
        // Given - notification with null endpointProperties
        NotificationToConnectorHttp notification = new NotificationToConnectorHttp();
        notification.setOrgId("12345");
        notification.setEndpointProperties(null);  // Missing required field
        notification.setPayload(new JsonObject().put("message", "test"));

        JsonObject cloudEventData = objectMapper.convertValue(notification, JsonObject.class);

        IncomingCloudEventMetadata<JsonObject> mockMetadata = mock(IncomingCloudEventMetadata.class);
        when(mockMetadata.getData()).thenReturn(cloudEventData);

        // When & Then
        ConstraintViolationException exception = assertThrows(ConstraintViolationException.class, () -> {
            httpNotificationValidator.parseAndValidate(mockMetadata);
        });

        assertTrue(exception.getMessage().contains("Validation failed:"));
        assertTrue(exception.getMessage().contains("endpointProperties"));
        assertTrue(exception.getMessage().contains("must not be null"));
    }

    @Test
    void testParseAndValidateFailsForNullPayload() {
        // Given - notification with null payload
        NotificationToConnectorHttp notification = new NotificationToConnectorHttp();
        notification.setOrgId("12345");

        NotificationToConnectorHttp.EndpointProperties endpointProperties = new NotificationToConnectorHttp.EndpointProperties();
        endpointProperties.setTargetUrl("https://example.com/webhook");
        notification.setEndpointProperties(endpointProperties);

        notification.setPayload(null);  // Missing required field

        JsonObject cloudEventData = objectMapper.convertValue(notification, JsonObject.class);

        IncomingCloudEventMetadata<JsonObject> mockMetadata = mock(IncomingCloudEventMetadata.class);
        when(mockMetadata.getData()).thenReturn(cloudEventData);

        // When & Then
        ConstraintViolationException exception = assertThrows(ConstraintViolationException.class, () -> {
            httpNotificationValidator.parseAndValidate(mockMetadata);
        });

        assertTrue(exception.getMessage().contains("Validation failed:"));
        assertTrue(exception.getMessage().contains("payload"));
        assertTrue(exception.getMessage().contains("must not be null"));
    }

    @Test
    void testParseAndValidateFailsForMultipleNullFields() {
        // Given - notification with multiple null required fields
        NotificationToConnectorHttp notification = new NotificationToConnectorHttp();
        notification.setOrgId("12345");
        notification.setEndpointProperties(null);
        notification.setPayload(null);

        JsonObject cloudEventData = objectMapper.convertValue(notification, JsonObject.class);

        IncomingCloudEventMetadata<JsonObject> mockMetadata = mock(IncomingCloudEventMetadata.class);
        when(mockMetadata.getData()).thenReturn(cloudEventData);

        // When & Then
        ConstraintViolationException exception = assertThrows(ConstraintViolationException.class, () -> {
            httpNotificationValidator.parseAndValidate(mockMetadata);
        });

        String errorMessage = exception.getMessage();
        assertTrue(errorMessage.contains("Validation failed:"));
        assertTrue(errorMessage.contains("endpointProperties"));
        assertTrue(errorMessage.contains("payload"));
        assertTrue(errorMessage.contains("must not be null"));
        assertEquals(2, exception.getConstraintViolations().size());
    }

    @Test
    void testParseAndValidateWithOptionalAuthenticationNull() {
        // Given - valid notification with null authentication (optional field)
        NotificationToConnectorHttp notification = new NotificationToConnectorHttp();
        notification.setOrgId("12345");

        NotificationToConnectorHttp.EndpointProperties endpointProperties = new NotificationToConnectorHttp.EndpointProperties();
        endpointProperties.setTargetUrl("https://example.com/webhook");
        notification.setEndpointProperties(endpointProperties);

        JsonObject payloadData = new JsonObject().put("message", "test");
        notification.setPayload(payloadData);

        notification.setAuthentication(null);  // Optional field

        JsonObject cloudEventData = objectMapper.convertValue(notification, JsonObject.class);

        IncomingCloudEventMetadata<JsonObject> mockMetadata = mock(IncomingCloudEventMetadata.class);
        when(mockMetadata.getData()).thenReturn(cloudEventData);

        // When
        NotificationToConnectorHttp result = httpNotificationValidator.parseAndValidate(mockMetadata);

        // Then - should succeed (authentication is optional)
        assertNotNull(result);
        assertEquals("12345", result.getOrgId());
    }

    @Test
    void testErrorMessageFormat() {
        // Given - notification with null payload
        NotificationToConnectorHttp notification = new NotificationToConnectorHttp();
        notification.setOrgId("12345");

        NotificationToConnectorHttp.EndpointProperties endpointProperties = new NotificationToConnectorHttp.EndpointProperties();
        endpointProperties.setTargetUrl("https://example.com/webhook");
        notification.setEndpointProperties(endpointProperties);

        notification.setPayload(null);

        JsonObject cloudEventData = objectMapper.convertValue(notification, JsonObject.class);

        IncomingCloudEventMetadata<JsonObject> mockMetadata = mock(IncomingCloudEventMetadata.class);
        when(mockMetadata.getData()).thenReturn(cloudEventData);

        // When
        ConstraintViolationException exception = assertThrows(ConstraintViolationException.class, () -> {
            httpNotificationValidator.parseAndValidate(mockMetadata);
        });

        // Then - verify error message format
        String errorMessage = exception.getMessage();
        assertTrue(errorMessage.startsWith("Validation failed:"));
        assertTrue(errorMessage.matches(".*payload:\\s*must not be null.*"));
    }
}
