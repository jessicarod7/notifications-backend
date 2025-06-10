package helpers;

import com.redhat.cloud.notifications.ingress.Context;
import com.redhat.cloud.notifications.ingress.Event;
import com.redhat.cloud.notifications.ingress.Metadata;
import com.redhat.cloud.notifications.ingress.Payload;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.redhat.cloud.notifications.TestConstants.DEFAULT_ORG_ID;

public class OcmTestHelpers {

    public static JsonObject createOcmMessage(String clusterDisplayName, String subscriptionPlan, String logDescription, String subject) {
        return createOcmMessage(clusterDisplayName, subscriptionPlan, logDescription, subject, null, null, Optional.empty());
    }

    public static JsonObject createOcmMessage(String clusterDisplayName, String subscriptionPlan, String logDescription, String subject, String title, String severity, Optional<Map<String, Object>> specificGlobalVars) {
        JsonObject emailActionMessage = new JsonObject();
        emailActionMessage.put("bundle", "openshift");
        emailActionMessage.put("application", "cluster-manager");
        emailActionMessage.put("timestamp", LocalDateTime.now());
        emailActionMessage.put("event_type", "testEmailSubscriptionInstant");

        emailActionMessage.put("context",
                new Context.ContextBuilder()
                        .withAdditionalProperty("system_check_in", "2021-07-13T15:22:42.199046")
                        .withAdditionalProperty("tags", List.of())
                        .withAdditionalProperty("environment_url", "http://localhost")
                        .build()
        );
        Map<String, Object> globalVars = buildGlobalVars(clusterDisplayName, subscriptionPlan, logDescription, severity, specificGlobalVars);

        Event event = new Event.EventBuilder()
                .withMetadata(new Metadata.MetadataBuilder().build())
                .withPayload(
                        new Payload.PayloadBuilder()
                                .withAdditionalProperty("global_vars", globalVars)
                                .withAdditionalProperty("subject", subject)
                                .withAdditionalProperty("title", title)
                                .build()
                )
                .build();

        emailActionMessage.put("events", JsonArray.of(
                Map.of(
                        "metadata", JsonObject.mapFrom(event.getMetadata()),
                        "payload", JsonObject.mapFrom(event.getPayload())
                )
        ));

        emailActionMessage.put("org_id", DEFAULT_ORG_ID);
        emailActionMessage.put("source", JsonObject.of(
                "bundle", JsonObject.of("display_name", "OpenShift"),
                "application", JsonObject.of("display_name", "Cluster Manager"),
                "event_type", JsonObject.of("display_name", "Test instant email subscription")
        ));

        return emailActionMessage;
    }

    private static Map<String, Object> buildGlobalVars(String clusterDisplayName, String subscriptionPlan, String logDescription, String severity, Optional<Map<String, Object>> specificGlobalVars) {
        Map<String, Object> globalVars = new HashMap<String, Object>(Map.of(
                "cluster_display_name", clusterDisplayName,
                "subscription_id", "2XqNHRdLNEAzshh7MkkOql6fx6I",
                "subscription_plan", subscriptionPlan,
                "log_description", logDescription,
                "internal_cluster_id", "fekelklflef",
                "severity", severity
        ));

        specificGlobalVars.ifPresent(globalVars::putAll);

        return globalVars;
    }

    public static JsonObject createOcmMessageWith4Events(String clusterDisplayName, String subscriptionPlan, String logDescription, String subject) {
        JsonObject message = createOcmMessage(clusterDisplayName, subscriptionPlan, logDescription, subject);

        List<Event> additionalEvents = List.of(
                new Event.EventBuilder()
                        .withMetadata(new Metadata.MetadataBuilder().build())
                        .withPayload(
                                new Payload.PayloadBuilder()
                                        .withAdditionalProperty(
                                                "global_vars",
                                                buildGlobalVars(clusterDisplayName, subscriptionPlan, "All pods on this cluster are offline", "CRITICAL", Optional.empty()))
                                        .withAdditionalProperty("subject", "Cluster down")
                                        .withAdditionalProperty("title", null)
                                        .build()
                        )
                        .build(),
                new Event.EventBuilder()
                        .withMetadata(new Metadata.MetadataBuilder().build())
                        .withPayload(
                                new Payload.PayloadBuilder()
                                        .withAdditionalProperty(
                                                "global_vars",
                                                buildGlobalVars(clusterDisplayName, subscriptionPlan, "Upgrading Postgres to 16", "Major", Optional.empty()))
                                        .withAdditionalProperty("subject", "Cluster upgrade in progress")
                                        .withAdditionalProperty("title", null)
                                        .build()
                        )
                        .build(),
                new Event.EventBuilder()
                        .withMetadata(new Metadata.MetadataBuilder().build())
                        .withPayload(
                                new Payload.PayloadBuilder()
                                        .withAdditionalProperty(
                                                "global_vars",
                                                buildGlobalVars(clusterDisplayName, subscriptionPlan, "Failed to generate daily report", "Warning", Optional.empty()))
                                        .withAdditionalProperty("subject", "Daily report failed")
                                        .withAdditionalProperty("title", null)
                                        .build()
                        )
                        .build()
        );

        JsonArray events = message.getJsonArray("events");
        events.addAll(additionalEvents.stream()
                .map(e -> Map.of(
                        "metadata", JsonObject.mapFrom(e.getMetadata()),
                        "payload", JsonObject.mapFrom(e.getPayload())
                ))
                .collect(JsonArray::new, JsonArray::add, JsonArray::addAll)
        );

        return message;
    }
}
