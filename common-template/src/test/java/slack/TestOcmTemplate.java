package slack;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.cloud.notifications.qute.templates.TemplateDefinition;
import com.redhat.cloud.notifications.qute.templates.TemplateService;
import helpers.OcmTestHelpers;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static com.redhat.cloud.notifications.qute.templates.IntegrationType.SLACK;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class TestOcmTemplate {

    public static final String CLUSTER_MANAGER_DEFAULT_EVENT_URL = "https://cloud.redhat.com/openshift/details/s/" + OcmTestHelpers.SUBSCRIPTION_ID + "?from=notifications&integration=slack";

    @Inject
    TemplateService templateService;

    @Inject
    ObjectMapper objectMapper;

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void testRenderedOcmTemplates(boolean multipleEvents) {
        JsonObject message = multipleEvents
                ? OcmTestHelpers.createOcmMessageWith4Events("Atlantic", "OSDTrial", "<b>Altlantic</b> server is experiencing flooding issues", "Subject line!")
                : OcmTestHelpers.createOcmMessage("Atlantic", "OSDTrial", "<b>Altlantic</b> server is experiencing flooding issues", "Subject line!");
        String result = renderTemplate(null, message);
        checkResult(null, result, multipleEvents);
    }

    String renderTemplate(final String eventType, final JsonObject message) {
        TemplateDefinition templateConfig = new TemplateDefinition(SLACK, "openshift", "cluster-manager", eventType);
        Map<String, Object> messageAsMap;
        try {
            messageAsMap = objectMapper.readValue(message.encode(), Map.class);
            return templateService.renderTemplate(templateConfig, messageAsMap);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("OCM notification data transformation failed", e);
        }
    }

    private void checkResult(String eventType, String result, boolean multipleEvents) {
        if (eventType == null) {
            if (multipleEvents) {
                assertEquals("4 events have been triggered by cluster Atlantic.\\nThe first 3 events:" +
                        "\\n- *[" + OcmTestHelpers.DEFAULT_SEVERITY + "]* Subject line!" +
                        "\\n- *[Critical]* Cluster down" +
                        "\\n- *[Major]* Cluster upgrade in progress" +
                        "\\n\\nView event details in <" + CLUSTER_MANAGER_DEFAULT_EVENT_URL + "|Cluster Manager - OpenShift>.", result);
            } else {
                assertEquals("1 event has been triggered by cluster Atlantic:\\n- *[" + OcmTestHelpers.DEFAULT_SEVERITY + "]* Subject line!" +
                        "\\n\\nView event details in <" + CLUSTER_MANAGER_DEFAULT_EVENT_URL + "|Cluster Manager - OpenShift>.", result);
            }
        } else {
            throw new IllegalArgumentException(eventType + "is not a valid event type");
        }
    }
}
