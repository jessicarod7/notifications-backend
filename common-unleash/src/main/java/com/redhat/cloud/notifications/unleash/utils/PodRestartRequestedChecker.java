package com.redhat.cloud.notifications.unleash.utils;

import io.getunleash.FeatureDefinition;
import io.getunleash.Unleash;
import io.getunleash.UnleashContext;
import io.getunleash.variant.Payload;
import io.getunleash.variant.Variant;
import io.quarkus.logging.Log;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Optional;

import static com.redhat.cloud.notifications.unleash.UnleashContextBuilder.buildUnleashContextWithEnv;

@Singleton
public class PodRestartRequestedChecker {

    private static final String UNLEASH_TOGGLE_NAME = "notifications.pod-restart-requested";
    private static final String UNLEASH_ENVIRONMENT = "notifications.unleash-environment";

    @ConfigProperty(name = "host-name", defaultValue = "localhost")
    String hostName;

    @ConfigProperty(name = UNLEASH_ENVIRONMENT, defaultValue = "default")
    protected String unleashEnvironment;

    @Inject
    Unleash unleash;

    boolean restartRequestedFromUnleash = false;

    public void process(@Observes List<FeatureDefinition> featureDefinitions) {
        UnleashContext unleashContext = buildUnleashContextWithEnv(unleashEnvironment);
        Variant variant = unleash.getVariant(UNLEASH_TOGGLE_NAME, unleashContext);
        if (variant.isEnabled()) {
            Optional<Payload> payload = variant.getPayload();
            if (payload.isEmpty() || payload.get().getValue() == null) {
                Log.warn("Variant ignored because of an empty payload");
                return;
            }

            restartRequestedFromUnleash = hostName.equals(payload.get().getValue());
        }
    }

    public boolean isRestartRequestedFromUnleash() {
        return restartRequestedFromUnleash;
    }
}
