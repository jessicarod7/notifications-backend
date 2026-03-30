package com.redhat.cloud.notifications.connector.google.chat;

import com.redhat.cloud.notifications.connector.v2.models.NotificationToConnector;
import java.util.Map;

public class GoogleChatNotification extends NotificationToConnector {

    public String webhookUrl;

    public Map<String, Object> eventData;
}
