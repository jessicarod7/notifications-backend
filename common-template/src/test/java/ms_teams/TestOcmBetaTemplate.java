package ms_teams;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.cloud.notifications.qute.templates.TemplateDefinition;
import com.redhat.cloud.notifications.qute.templates.TemplateService;
import helpers.OcmTestHelpers;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.redhat.cloud.notifications.qute.templates.IntegrationType.MS_TEAMS;
import static com.redhat.cloud.notifications.qute.templates.mapping.OpenShift.BUNDLE_NAME;
import static com.redhat.cloud.notifications.qute.templates.mapping.OpenShift.CLUSTER_MANAGER_APP_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
public class TestOcmBetaTemplate {

    private static final JsonObject MESSAGE = OcmTestHelpers.createOcmMessageWith4Events("Atlantic", "OSDTrial", "<b>Altlantic</b> server is experiencing flooding issues", "Subject line!");

    @Inject
    TemplateService templateService;

    @Inject
    ObjectMapper objectMapper;

    @Test
    void testRenderedOcmTemplates() {
        TemplateDefinition templateConfig = new TemplateDefinition(MS_TEAMS, BUNDLE_NAME, CLUSTER_MANAGER_APP_NAME, null, true);
        Map<String, Object> messageAsMap;
        try {
            messageAsMap = objectMapper.readValue(MESSAGE.encode(), Map.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("OCM notification data transformation failed", e);
        }

        String result = templateService.renderTemplate(templateConfig, messageAsMap);
        checkResult(result);
    }

    private void checkResult(String resultEncoded) {
        // Check content from parent message
        AdaptiveCardValidatorHelper.validate(resultEncoded);

        // Extract message body
        JsonArray result = new JsonObject(resultEncoded)
                .getJsonArray("attachments").getJsonObject(0)
                .getJsonObject("content").getJsonArray("body");

        // Check title and intro
        assertEquals("Triggered events - Cluster Manager - OpenShift", result.getJsonObject(0).getString("text"));
        assertEquals("heading", result.getJsonObject(0).getString("style"));

        assertEquals("4 events have been triggered by cluster Atlantic.", result.getJsonObject(1).getString("text"));
        assertEquals("The first 3 events:", result.getJsonObject(2).getString("text"));

        // Check events
        checkEvent(result, 0, "Info", null, "Subject line!");
        checkEvent(result, 1, "Critical", "attention", "Cluster down");
        checkEvent(result, 2, "Major", "warning", "Cluster upgrade in progress");

        // Check link to Hybrid Cloud Console
        assertEquals("View event details in [Cluster Manager](" + TestOcmTemplate.CLUSTER_MANAGER_DEFAULT_EVENT_URL + ").", result.getJsonObject(4).getString("text"));
    }

    private void checkEvent(final JsonArray result, int position, String severity, String color, String subject) {
        JsonArray event = result.getJsonObject(3).getJsonArray("rows").getJsonObject(position + 1).getJsonArray("cells");
        assertEquals(severity, event.getJsonObject(0).getJsonArray("items").getJsonObject(0).getString("text"));
        assertEquals(color, event.getJsonObject(0).getJsonArray("items").getJsonObject(0).getString("color"));
        assertEquals(subject, event.getJsonObject(1).getJsonArray("items").getJsonObject(0).getString("text"));
    }
}
