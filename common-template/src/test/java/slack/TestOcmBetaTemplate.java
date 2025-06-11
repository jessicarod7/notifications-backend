package slack;

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

import static com.redhat.cloud.notifications.qute.templates.IntegrationType.SLACK;
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
        TemplateDefinition templateConfig = new TemplateDefinition(SLACK, BUNDLE_NAME, CLUSTER_MANAGER_APP_NAME, null, true);
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
        // Extract message body
        JsonArray result = new JsonObject(resultEncoded).getJsonArray("blocks");

        // Check title and intro
        assertEquals("header", result.getJsonObject(0).getString("type"));
        assertEquals("Triggered events - Cluster Manager - OpenShift", result.getJsonObject(0).getJsonObject("text").getString("text"));

        assertEquals("4 events have been triggered by cluster Atlantic.", result.getJsonObject(1).getJsonArray("elements").getJsonObject(0)
                .getJsonArray("elements").getJsonObject(0).getString("text"));
        assertEquals("The first 3 events:", result.getJsonObject(1).getJsonArray("elements").getJsonObject(1)
                .getJsonArray("elements").getJsonObject(0).getString("text"));

        // Check events
        checkEvent(result, 0, "Info", "Subject line!");
        checkEvent(result, 1, "Critical", "Cluster down");
        checkEvent(result, 2, "Major", "Cluster upgrade in progress");

        // Check link to Hybrid Cloud Console
        assertEquals("View event details in ", result.getJsonObject(3).getJsonArray("elements").getJsonObject(0)
                .getJsonArray("elements").getJsonObject(0).getString("text"));
        assertEquals("Cluster Manager", result.getJsonObject(3).getJsonArray("elements").getJsonObject(0)
                .getJsonArray("elements").getJsonObject(1).getString("text"));
        assertEquals(TestOcmTemplate.CLUSTER_MANAGER_DEFAULT_EVENT_URL, result.getJsonObject(3).getJsonArray("elements").getJsonObject(0)
                .getJsonArray("elements").getJsonObject(1).getString("url"));
        assertEquals(true, result.getJsonObject(3).getJsonArray("elements").getJsonObject(0)
                .getJsonArray("elements").getJsonObject(1).getJsonObject("style").getBoolean("bold"));
    }

    private void checkEvent(final JsonArray result, int position, String severity, String subject) {
        assertEquals("_" + severity + "_", result.getJsonObject(2).getJsonArray("fields").getJsonObject((position + 1) * 2).getString("text"));
        assertEquals(subject, result.getJsonObject(2).getJsonArray("fields").getJsonObject((position + 1) * 2 + 1).getString("text"));
    }
}
