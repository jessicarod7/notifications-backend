package com.redhat.cloud.notifications.connector.v2.http;

import com.redhat.cloud.notifications.connector.v2.http.models.NotificationToConnectorHttp;
import io.smallrye.reactive.messaging.ce.IncomingCloudEventMetadata;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for parsing and validating HTTP notifications from incoming CloudEvents.
 * This bean can be injected into any HTTP-based connector to perform validation
 * and throw formatted exceptions when validation fails.
 */
@ApplicationScoped
public class HttpNotificationValidator {

    @Inject
    Validator validator;

    /**
     * Parses the incoming CloudEvent data as NotificationToConnectorHttp and validates it.
     * If validation fails, throws a ConstraintViolationException with a formatted error message.
     *
     * @param incomingCloudEvent the incoming CloudEvent metadata containing the notification data
     * @return the parsed and validated NotificationToConnectorHttp object
     * @throws ConstraintViolationException if validation constraints are violated
     */
    public NotificationToConnectorHttp parseAndValidate(IncomingCloudEventMetadata<JsonObject> incomingCloudEvent) {
        NotificationToConnectorHttp notification = incomingCloudEvent.getData().mapTo(NotificationToConnectorHttp.class);

        Set<ConstraintViolation<NotificationToConnectorHttp>> violations = validator.validate(notification);
        if (!violations.isEmpty()) {
            String errorMessage = violations.stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining(", "));
            throw new ConstraintViolationException("Validation failed: " + errorMessage, violations);
        }

        return notification;
    }
}
