package com.redhat.cloud.notifications.events;

import com.redhat.cloud.notifications.config.EngineConfig;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.RedisAPI;
import io.vertx.redis.client.RedisOptions;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.UUID;

/** Stores and retrieves data from remote cache (i.e. Valkey). */
@ApplicationScoped
public class ValkeyService {

    private static final String KAFKA_MESSAGE_KEY = "engine:kafka-message:";
    private static final String NOT_USED = "";

    @ConfigProperty(name = "valkey-service.ttl", defaultValue = "PT24H")
    Duration ttl;

    @ConfigProperty(name = "quarkus.redis.hosts", defaultValue = "")
    String valkeyHost;

    @ConfigProperty(name = "quarkus.redis.password", defaultValue = "")
    String valkeyPassword;

    @Inject
    EngineConfig config;

    @Inject
    Vertx vertx;

    /** The underlying client connecting to Valkey. */
    private Redis valkeyClient;

    /** Implementation of the Redis/Valkey API, using {@link #valkeyClient} */
    private RedisAPI valkey;

    @PostConstruct
    void initialize() {
        if (config.isInMemoryDbEnabled()) {
            RedisOptions valkeyOptions = new RedisOptions().setConnectionString(valkeyHost);
            if (valkeyPassword != null && valkeyPassword.isEmpty()) {
                valkeyOptions.setPassword(valkeyPassword);
            }
            this.valkeyClient = Redis.createClient(vertx, valkeyOptions);
            this.valkey = RedisAPI.api(this.valkeyClient);
        }
    }

    /**
     * Verifies that another Kafka consumer didn't already process the given message and then failed to commit its
     * offset. Such failure can happen when a consumer is kicked out of its consumer group because it didn't poll new
     * messages fast enough. We experienced that already in production.
     *
     * @param messageId ID of an incoming message
     * @return true if the message has not been processed yet
     */
    public boolean isNewMessageId(UUID messageId) {
        String key = KAFKA_MESSAGE_KEY + messageId;

        boolean isNew = valkey.setnxAndAwait(key, NOT_USED).toBoolean();
        if (isNew) {
            valkey.setexAndForget(key, String.valueOf(ttl.toSeconds()), NOT_USED);
        }

        return isNew;
    }
}
