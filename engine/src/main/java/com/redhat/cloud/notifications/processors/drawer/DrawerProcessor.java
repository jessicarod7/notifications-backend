package com.redhat.cloud.notifications.processors.drawer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.cloud.notifications.config.EngineConfig;
import com.redhat.cloud.notifications.db.repositories.BundleRepository;
import com.redhat.cloud.notifications.db.repositories.DrawerNotificationRepository;
import com.redhat.cloud.notifications.db.repositories.EventRepository;
import com.redhat.cloud.notifications.db.repositories.EventTypeRepository;
import com.redhat.cloud.notifications.db.repositories.NotificationHistoryRepository;
import com.redhat.cloud.notifications.db.repositories.SubscriptionRepository;
import com.redhat.cloud.notifications.models.DrawerEntryPayload;
import com.redhat.cloud.notifications.models.Endpoint;
import com.redhat.cloud.notifications.models.Event;
import com.redhat.cloud.notifications.processors.ConnectorSender;
import com.redhat.cloud.notifications.processors.InsightsUrlsBuilder;
import com.redhat.cloud.notifications.processors.SystemEndpointTypeProcessor;
import com.redhat.cloud.notifications.processors.email.connector.dto.RecipientSettings;
import com.redhat.cloud.notifications.qute.templates.IntegrationType;
import com.redhat.cloud.notifications.qute.templates.TemplateDefinition;
import com.redhat.cloud.notifications.qute.templates.TemplateService;
import com.redhat.cloud.notifications.transformers.BaseTransformer;
import com.redhat.cloud.notifications.utils.RecipientsAuthorizationCriterionExtractor;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.redhat.cloud.notifications.models.SubscriptionType.DRAWER;

@ApplicationScoped
public class DrawerProcessor extends SystemEndpointTypeProcessor {

    @Inject
    BaseTransformer baseTransformer;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    InsightsUrlsBuilder insightsUrlsBuilder;

    @Inject
    DrawerNotificationRepository drawerNotificationRepository;

    @Inject
    EventRepository eventRepository;

    @Inject
    EngineConfig engineConfig;

    @Inject
    ConnectorSender connectorSender;

    @Inject
    SubscriptionRepository subscriptionRepository;

    @Inject
    NotificationHistoryRepository notificationHistoryRepository;

    @Inject
    BundleRepository bundleRepository;

    @Inject
    RecipientsAuthorizationCriterionExtractor recipientsAuthorizationCriterionExtractor;

    @Inject
    TemplateService templateService;

    @Inject
    EventTypeRepository eventTypeRepository;

    @Override
    public void process(Event event, List<Endpoint> endpoints) {
        if (!engineConfig.isDrawerEnabled()) {
            return;
        }
        if (endpoints == null || endpoints.isEmpty()) {
            return;
        }

        // build event thought qute template
        JsonObject transformedData = baseTransformer.toJsonObject(event);
        String renderedData = buildNotificationMessage(event);

        // store it on event table
        event.setRenderedDrawerNotification(renderedData);
        eventRepository.updateDrawerNotification(event);

        // get default endpoint
        DrawerEntryPayload drawerEntryPayload = buildJsonPayloadFromEvent(event, transformedData, endpoints.getFirst());

        final Set<String> unsubscribers =
                Set.copyOf(subscriptionRepository.getUnsubscribers(event.getOrgId(), event.getEventType().getId(), DRAWER));
        final Set<RecipientSettings> recipientSettings = extractAndTransformRecipientSettings(event, endpoints);

        // Prepare all the data to be sent to the connector.
        final DrawerNotificationToConnector drawerNotificationToConnector = new DrawerNotificationToConnector(
            event.getOrgId(),
            drawerEntryPayload,
            recipientSettings,
            unsubscribers,
            recipientsAuthorizationCriterionExtractor.extract(event)
        );

        connectorSender.send(event, endpoints.getFirst(), JsonObject.mapFrom(drawerNotificationToConnector));
    }

    private DrawerEntryPayload buildJsonPayloadFromEvent(Event event, JsonObject data, Endpoint ep) {
        DrawerEntryPayload drawerEntryPayload = new DrawerEntryPayload();
        drawerEntryPayload.setDescription(event.getRenderedDrawerNotification());
        drawerEntryPayload.setTitle(event.getEventTypeDisplayName());
        drawerEntryPayload.setCreated(event.getCreated());
        drawerEntryPayload.setSource(String.format("%s - %s", event.getApplicationDisplayName(), event.getBundleDisplayName()));
        drawerEntryPayload.setBundle(bundleRepository.getBundle(event.getBundleId()).getName());
        drawerEntryPayload.setEventId(event.getId());

        /* Note: typically inventory_url is not provided if blank. However, drawer payloads must be assembled from raw
         * objects, as called in DrawerNotificationRepository.getNotifications. So in this case, a blank string is
         * provided. */
        drawerEntryPayload.setInventoryUrl(insightsUrlsBuilder.buildInventoryUrl(data, ep.getType().name()).orElse(""));
        drawerEntryPayload.setApplicationUrl(insightsUrlsBuilder.buildApplicationUrl(data, ep.getType().name()));

        return drawerEntryPayload;
    }

    public String buildNotificationMessage(Event event) {
        JsonObject data = baseTransformer.toJsonObject(event);

        Map<String, Object> dataAsMap;
        try {
            dataAsMap = objectMapper.readValue(data.encode(), Map.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Drawer notification data transformation failed", e);
        }

        TemplateDefinition templateDefinition = new TemplateDefinition(
            IntegrationType.DRAWER,
            event.getEventType().getApplication().getBundle().getName(),
            event.getEventType().getApplication().getName(),
            event.getEventType().getName());
        return templateService.renderTemplate(templateDefinition, dataAsMap);
    }

    public void manageConnectorDrawerReturnsIfNeeded(Map<String, Object> decodedPayload, UUID historyId) {
        String inventoryUrl = "";
        String applicationUrl = "";
        Map<String, Object> drawerPayload = (HashMap<String, Object>) decodedPayload.get("payload");
        if (drawerPayload != null) {
            inventoryUrl = (String) drawerPayload.getOrDefault("inventoryUrl", "");
            applicationUrl = (String) drawerPayload.getOrDefault("applicationUrl", "");
        }

        Map<String, Object> details = (HashMap<String, Object>) decodedPayload.get("details");
        if (null != details && "com.redhat.console.notification.toCamel.drawer".equals(details.get("type"))) {
            com.redhat.cloud.notifications.models.Event event = notificationHistoryRepository.getEventIdFromHistoryId(historyId);
            List<String> recipients = (List<String>) details.get("resolved_recipient_list");
            if (null != recipients && recipients.size() > 0) {
                String drawerNotificationIds = String.join(",", recipients);
                drawerNotificationRepository.create(event, drawerNotificationIds, inventoryUrl, applicationUrl);
                details.remove("resolved_recipient_list");
                details.put("new_drawer_entry_counter", recipients.size());
            }
        }
    }
}
