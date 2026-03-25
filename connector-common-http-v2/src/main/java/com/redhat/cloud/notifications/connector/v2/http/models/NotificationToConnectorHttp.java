package com.redhat.cloud.notifications.connector.v2.http.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.redhat.cloud.notifications.connector.v2.models.NotificationToConnector;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.vertx.core.json.JsonObject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.URL;

@RegisterForReflection
public class NotificationToConnectorHttp extends NotificationToConnector {

    @NotNull
    @Valid
    @JsonProperty("endpoint_properties")
    private EndpointProperties endpointProperties;

    @NotNull
    @JsonProperty("payload")
    private JsonObject payload;

    @JsonProperty("authentication")
    private JsonObject authentication;

    @RegisterForReflection
    public static class EndpointProperties {

        @NotNull
        @NotBlank
        @URL
        @JsonProperty("url")
        private String targetUrl;

        public String getTargetUrl() {
            return targetUrl;
        }

        public void setTargetUrl(String targetUrl) {
            this.targetUrl = targetUrl;
        }
    }

    public EndpointProperties getEndpointProperties() {
        return endpointProperties;
    }

    public void setEndpointProperties(EndpointProperties endpointProperties) {
        this.endpointProperties = endpointProperties;
    }

    public JsonObject getPayload() {
        return payload != null ? payload.copy() : null;
    }

    public void setPayload(JsonObject payload) {
        this.payload = payload != null ? payload.copy() : null;
    }

    public JsonObject getAuthentication() {
        return authentication != null ? authentication.copy() : null;
    }

    public void setAuthentication(JsonObject authentication) {
        this.authentication = authentication != null ? authentication.copy() : null;
    }
}
