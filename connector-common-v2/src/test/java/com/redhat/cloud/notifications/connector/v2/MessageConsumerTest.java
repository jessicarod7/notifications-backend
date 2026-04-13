package com.redhat.cloud.notifications.connector.v2;

import com.redhat.cloud.notifications.MicrometerAssertionHelper;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.ce.IncomingCloudEventMetadata;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.redhat.cloud.notifications.connector.v2.MessageConsumer.FAILED_COUNTER_NAME;
import static com.redhat.cloud.notifications.connector.v2.MessageConsumer.X_RH_NOTIFICATIONS_CONNECTOR_HEADER;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
class MessageConsumerTest {

    @Inject
    MessageConsumer messageConsumer;

    @Inject
    ConnectorConfig connectorConfig;

    @Inject
    MicrometerAssertionHelper micrometerAssertionHelper;

    @BeforeEach
    void setUp() {
        micrometerAssertionHelper.saveCounterValueFilteredByTagsBeforeTest(FAILED_COUNTER_NAME, "connector", connectorConfig.getConnectorName());
    }

    @Test
    void testMissingCloudEventMetadata() {
        Message<JsonObject> message = Message.of(new JsonObject());

        messageConsumer.processMessage(message);

        micrometerAssertionHelper.assertCounterValueFilteredByTagsIncrement(FAILED_COUNTER_NAME, "connector", connectorConfig.getConnectorName(), 1);
    }

    @Test
    void testNullCloudEventData() {
        IncomingCloudEventMetadata<JsonObject> cloudEvent = mock(IncomingCloudEventMetadata.class);
        when(cloudEvent.getData()).thenReturn(null);

        Headers headers = new RecordHeaders()
            .add(X_RH_NOTIFICATIONS_CONNECTOR_HEADER, connectorConfig.getConnectorName().getBytes(UTF_8));

        OutgoingKafkaRecordMetadata<String> kafkaHeaders = OutgoingKafkaRecordMetadata.<String>builder()
            .withHeaders(headers)
            .build();

        Message<JsonObject> message = Message.of(new JsonObject())
            .addMetadata(kafkaHeaders)
            .addMetadata(cloudEvent);

        messageConsumer.processMessage(message);

        micrometerAssertionHelper.assertCounterValueFilteredByTagsIncrement(FAILED_COUNTER_NAME, "connector", connectorConfig.getConnectorName(), 1);
    }
}
