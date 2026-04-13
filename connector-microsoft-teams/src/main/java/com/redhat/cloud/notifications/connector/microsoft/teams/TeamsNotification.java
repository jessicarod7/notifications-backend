package com.redhat.cloud.notifications.connector.microsoft.teams;

import com.redhat.cloud.notifications.connector.v2.models.NotificationToConnector;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public class TeamsNotification extends NotificationToConnector {

    @NotNull
    @NotBlank
    public String webhookUrl;

    @NotNull
    public Map<String, Object> eventData;
}
