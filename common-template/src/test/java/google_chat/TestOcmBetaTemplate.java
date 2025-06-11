package google_chat;

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

import static com.redhat.cloud.notifications.qute.templates.IntegrationType.GOOGLE_CHAT;
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
        TemplateDefinition templateConfig = new TemplateDefinition(GOOGLE_CHAT, BUNDLE_NAME, CLUSTER_MANAGER_APP_NAME, null, true);
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
        JsonObject header = new JsonObject(resultEncoded).getJsonArray("cardsV2").getJsonObject(0).getJsonObject("card").getJsonObject("header");
        JsonArray body = new JsonObject(resultEncoded).getJsonArray("cardsV2").getJsonObject(0).getJsonObject("card").getJsonArray("sections")
                .getJsonObject(0).getJsonArray("widgets");

        // Check title and intro
        assertEquals("Cluster Manager - OpenShift", header.getString("title"));
        assertEquals("Triggered events", header.getString("subtitle"));

        assertEquals("4 events have been triggered by cluster Atlantic.<br>The first 3 events:", body.getJsonObject(0).getJsonObject("textParagraph").getString("text"));

        // Check events
        checkEvent(body, 0, "Info", "Subject line!");
        checkEvent(body, 1, "Critical", "Cluster down");
        checkEvent(body, 2, "Major", "Cluster upgrade in progress");

        // Check link to Hybrid Cloud Console
        assertEquals("View event details in Cluster Manager.", body.getJsonObject(4).getJsonObject("textParagraph").getString("text"));

        JsonObject button = body.getJsonObject(5).getJsonObject("buttonList").getJsonArray("buttons").getJsonObject(0);
        assertEquals("Open Cluster Manager", button.getString("text"));
        assertEquals("Opens a new tab to Cluster Manager at " + TestOcmTemplate.CLUSTER_MANAGER_DEFAULT_EVENT_URL, button.getString("altText"));
        assertEquals(TestOcmTemplate.CLUSTER_MANAGER_DEFAULT_EVENT_URL, button.getJsonObject("onClick").getJsonObject("openLink").getString("url"));
    }

    private void checkEvent(final JsonArray body, int position, String severity, String subject) {
        assertEquals("- <b>[" + severity + "]</b> " + subject, body.getJsonObject(position + 1).getJsonObject("textParagraph").getString("text"));
    }
}
