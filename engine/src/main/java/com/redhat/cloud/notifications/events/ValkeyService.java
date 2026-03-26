package com.redhat.cloud.notifications.events;

import com.redhat.cloud.notifications.config.EngineConfig;
import io.quarkus.logging.Log;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.RedisAPI;
import io.vertx.redis.client.RedisOptions;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/** Stores and retrieves data from remote cache (i.e. Valkey). */
@ApplicationScoped
public class ValkeyService {

    private static final String EVENT_DEDUPLICATION_KEY = "engine:event-deduplication:";
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
     * Verifies that the event has not been previously processed. The format of saved keys is
     * {@code engine:event-deduplication:<event_type>:<deduplication_key>}.
     *
     * @param eventId only used for debugging
     * @see com.redhat.cloud.notifications.events.deduplication.EventDeduplicator EventDeduplicator
     */
    public boolean isNewEvent(UUID eventTypeId, String deduplicationKey, LocalDateTime deleteAfter, UUID eventId) {
        String key = String.format("%s:%s:%s", EVENT_DEDUPLICATION_KEY, eventTypeId, deduplicationKey);
        String deleteAfterIso = deleteAfter.format(DateTimeFormatter.ISO_DATE_TIME);

        boolean isNew = valkey.setnxAndAwait(key, deleteAfterIso).toBoolean();
        if (isNew) {
            boolean expireSet = valkey.expireatAndAwait(List.of(
                    key,
                    String.valueOf(deleteAfter.toEpochSecond(ZoneOffset.UTC))
            )).toBoolean();

            if (!expireSet) {
                // dedup key may include private information, so other fields are used
                Log.warnf("unable to set expiry for Valkey event deduplication [event_type_id=%s, event_id=%s, delete_after=%s]",
                        eventTypeId, eventId, deleteAfterIso);
            }
        }

        return isNew;
    }
}
