package com.redhat.cloud.notifications.unleash;

import io.getunleash.UnleashContext;

public class UnleashContextBuilder {

    public static UnleashContext buildUnleashContextWithEnv(String unleashEnvironment) {
        UnleashContext unleashContext = UnleashContext.builder()
            .environment(unleashEnvironment)
            .build();
        return unleashContext;
    }

    public static UnleashContext buildUnleashContextWithEnvAndOrgId(String unleashEnvironment, String orgId) {
        UnleashContext unleashContext = UnleashContext.builder()
            .environment(unleashEnvironment)
            .addProperty("orgId", orgId)
            .build();
        return unleashContext;
    }
}
