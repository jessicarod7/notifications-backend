package com.redhat.cloud.notifications.auth.kessel;

import com.redhat.cloud.notifications.auth.kessel.permission.IntegrationPermission;
import com.redhat.cloud.notifications.auth.kessel.permission.KesselPermission;
import com.redhat.cloud.notifications.auth.kessel.permission.WorkspacePermission;
import com.redhat.cloud.notifications.auth.principal.rhid.RhIdentity;
import com.redhat.cloud.notifications.config.BackendConfig;
import com.redhat.cloud.notifications.routers.SecurityContextUtil;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.quarkus.logging.Log;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.SecurityContext;
import org.project_kessel.api.relations.v1beta1.CheckRequest;
import org.project_kessel.api.relations.v1beta1.CheckResponse;
import org.project_kessel.api.relations.v1beta1.LookupResourcesRequest;
import org.project_kessel.api.relations.v1beta1.LookupResourcesResponse;
import org.project_kessel.api.relations.v1beta1.ObjectReference;
import org.project_kessel.api.relations.v1beta1.ObjectType;
import org.project_kessel.api.relations.v1beta1.SubjectReference;
import org.project_kessel.relations.client.CheckClient;
import org.project_kessel.relations.client.LookupClient;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class KesselAuthorization {
    /**
     * Represents the namespace to be used for the requests that must specify
     * a subject.
     */
    public static final String KESSEL_RBAC_NAMESPACE = "rbac";
    /**
     * Represents the subject's type in Kessel. As told by them, there is only
     * going to exist the "user" type in the schema.
     */
    public static final String KESSEL_IDENTITY_SUBJECT_TYPE = "principal";
    /**
     * Represents the key for the "permission" tag used in the timer.
     */
    private static final String KESSEL_METRICS_TAG_PERMISSION_KEY = "permission";
    /**
     * Represents the timer's name to measure the time spent looking up for
     * authorized resources for a particular subject.
     */
    private static final String KESSEL_METRICS_LOOKUP_RESOURCES_TIMER_NAME = "notifications.kessel.relationships.lookup.resources.requests";
    /**
     * Represents the timer's name to measure the time spent checking for a
     * particular permission for a subject.
     */
    private static final String KESSEL_METRICS_PERMISSION_CHECK_TIMER_NAME = "notifications.kessel.relationships.permission.check.requests";
    /**
     * Represents the counter name to count permission check requests.
     */
    public static final String KESSEL_METRICS_PERMISSION_CHECK_COUNTER_NAME = "notifications.kessel.relationships.permission.check.count";
    /**
     * Represents the counter name to count lookup resources requests.
     */
    public static final String KESSEL_METRICS_LOOKUP_RESOURCES_COUNTER_NAME = "notifications.kessel.relationships.lookup.check.count";

    protected static final String COUNTER_TAG_FAILURES = "failures";
    protected static final String COUNTER_TAG_REQUEST_RESULT = "result";
    protected static final String COUNTER_TAG_SUCCESSES = "successes";

    @Inject
    CheckClient checkClient;

    @Inject
    LookupClient lookupClient;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    BackendConfig backendConfig;

    /**
     * Checks if the subject on the security context has permission on the
     * given resource. Throws
     * @param securityContext the security context to extract the subject from.
     * @param permission the permission we want to check.
     * @param resourceType the resource type we should check the permission
     *                     against.
     * @param resourceId the identifier of the resource.
     * @throws ForbiddenException in case of not being authorized.
     */
    public void hasPermissionOnResource(final SecurityContext securityContext, final KesselPermission permission, final ResourceType resourceType, final String resourceId) {
        // Identify the subject.
        final RhIdentity identity = SecurityContextUtil.extractRhIdentity(securityContext);

        // Build the request for Kessel.
        final CheckRequest permissionCheckRequest = this.buildCheckRequest(identity, permission, resourceType, resourceId);

        Log.tracef("[identity: %s][permission: %s][resource_type: %s][resource_id: %s] Payload for the permission check: %s", identity, permission, resourceType, resourceId, permissionCheckRequest);

        // Measure the time it takes to perform the operation with Kessel.
        final Timer.Sample permissionCheckTimer = Timer.start(this.meterRegistry);

        // Call Kessel.
        final CheckResponse response;
        try {
            response = this.checkClient.check(permissionCheckRequest);
        } catch (final Exception e) {
            Log.errorf(
                e,
                "[identity: %s][permission: %s][resource_type: %s][resource_id: %s] Unable to query Kessel for a permission on a resource",
                identity, permission, resourceType, resourceId
            );
            meterRegistry.counter(KESSEL_METRICS_PERMISSION_CHECK_COUNTER_NAME, Tags.of(COUNTER_TAG_REQUEST_RESULT, COUNTER_TAG_FAILURES)).increment();
            throw e;
        } finally {
            // Stop the timer.
            permissionCheckTimer.stop(this.meterRegistry.timer(KESSEL_METRICS_PERMISSION_CHECK_TIMER_NAME, Tags.of(KESSEL_METRICS_TAG_PERMISSION_KEY, permission.getKesselPermissionName(), Constants.KESSEL_METRICS_TAG_RESOURCE_TYPE_KEY, resourceType.name())));
        }

        meterRegistry.counter(KESSEL_METRICS_PERMISSION_CHECK_COUNTER_NAME, Tags.of(COUNTER_TAG_REQUEST_RESULT, COUNTER_TAG_SUCCESSES)).increment();

        Log.tracef("[identity: %s][permission: %s][resource_type: %s][resource_id: %s] Received payload for the permission check: %s", identity, permission, resourceType, resourceId, response);

        // Verify whether the subject has permission on the resource or not.
        if (CheckResponse.Allowed.ALLOWED_TRUE != response.getAllowed()) {
            Log.debugf("[identity: %s][permission: %s][resource_type: %s][resource_id: %s] Permission denied", identity, permission, resourceType, resourceId);

            throw new ForbiddenException();
        }

        Log.debugf("[identity: %s][permission: %s][resource_type: %s][resource_id: %s] Permission granted", identity, resourceType, permission, resourceId);
    }

    /**
     * Looks up the integrations the security context's subject has the given
     * permission for.
     * @param securityContext the security context holding the subject's
     *                        identity.
     * @param integrationPermission the integration's permission we want to use
     *                              to filter the target integrations with.
     * @return a set of integration IDs the user has permission to access.
     */
    public Set<UUID> lookupAuthorizedIntegrations(final SecurityContext securityContext, final IntegrationPermission integrationPermission) {
        // Identify the subject.
        final RhIdentity identity = SecurityContextUtil.extractRhIdentity(securityContext);

        // Build the lookup request for Kessel.
        final LookupResourcesRequest request = this.buildLookupResourcesRequest(identity, integrationPermission);

        Log.tracef("[identity: %s][permission: %s][resource_type: %s] Payload for the resource lookup: %s", identity, integrationPermission, ResourceType.INTEGRATION, request);

        // Measure the time it takes to perform the lookup operation.
        final Timer.Sample lookupTimer = Timer.start(this.meterRegistry);

        // Call Kessel.
        final Iterator<LookupResourcesResponse> responses;
        try {
            responses = this.lookupClient.lookupResources(request);
        } catch (final Exception e) {
            Log.errorf(
                e,
                "[identity: %s][permission: %s][resource_type: %s] Runtime error when querying Kessel for integration resources with request payload: %s",
                identity, integrationPermission, ResourceType.INTEGRATION, request
            );
            meterRegistry.counter(KESSEL_METRICS_LOOKUP_RESOURCES_COUNTER_NAME, Tags.of(COUNTER_TAG_REQUEST_RESULT, COUNTER_TAG_FAILURES)).increment();

            throw e;
        } finally {
            // Stop the timer.
            lookupTimer.stop(this.meterRegistry.timer(KESSEL_METRICS_LOOKUP_RESOURCES_TIMER_NAME, Tags.of(KESSEL_METRICS_TAG_PERMISSION_KEY, integrationPermission.getKesselPermissionName(), Constants.KESSEL_METRICS_TAG_RESOURCE_TYPE_KEY, ResourceType.INTEGRATION.name())));
        }

        meterRegistry.counter(KESSEL_METRICS_LOOKUP_RESOURCES_COUNTER_NAME, Tags.of(COUNTER_TAG_REQUEST_RESULT, COUNTER_TAG_SUCCESSES)).increment();

        // Process the incoming responses.
        final Set<UUID> uuids = new HashSet<>();
        while (responses.hasNext()) {
            final LookupResourcesResponse response = responses.next();

            Log.tracef("[identity: %s][permission: %s][resource_type: %s] Received payload for the resource lookup: %s", identity, integrationPermission, ResourceType.INTEGRATION, response);

            uuids.add(UUID.fromString(response.getResource().getId()));
        }

        return uuids;
    }

    /**
     * Checks whether the provided principal has the specified permission on
     * the given integration, and throws a {@link NotFoundException} if they
     * do not.
     * @param securityContext the security context to extract the principal
     *                        from.
     * @param integrationPermission the integration permission we want to
     *                              check.
     * @param integrationId the integration's identifier.
        */
    public void hasPermissionOnIntegration(final SecurityContext securityContext, final IntegrationPermission integrationPermission, final UUID integrationId) {
        try {
            this.hasPermissionOnResource(securityContext, integrationPermission, ResourceType.INTEGRATION, integrationId.toString());
        } catch (final ForbiddenException ignored) {
            final JsonObject responseBody = new JsonObject();
            responseBody.put("error", "Integration not found");

            throw new NotFoundException(responseBody.encode());
        }
    }

    /**
     * Checks whether the provided principal has the specified permission on
     * the given workspace.
     * @param securityContext the security context to extract the principal
     *                        from.
     * @param workspacePermission the workspace permission we want to
     *                            check.
     * @param workspaceId the workspace's identifier.
     */
    public void hasPermissionOnWorkspace(final SecurityContext securityContext, final WorkspacePermission workspacePermission, final UUID workspaceId) {
        this.hasPermissionOnResource(securityContext, workspacePermission, ResourceType.WORKSPACE, workspaceId.toString());
    }

    /**
     * Build a check request for a particular resource, to see if the subject
     * of the identity has permission on it.
     * @param identity the subject's identity.
     * @param permission the permission we want to check for the given subject
     *                   and resource.
     * @param resourceType the resource type we are attempting to verify.
     * @param resourceId the resource's identifier.
     * @return the built check request for Kessel ready to be sent.
     */
    protected CheckRequest buildCheckRequest(final RhIdentity identity, final KesselPermission permission, final ResourceType resourceType, final String resourceId) {
        return CheckRequest.newBuilder()
            .setResource(
                ObjectReference.newBuilder()
                    .setType(resourceType.getKesselObjectType())
                    .setId(resourceId)
                    .build()
            )
            .setRelation(permission.getKesselPermissionName())
            .setSubject(
                SubjectReference.newBuilder()
                    .setSubject(
                        ObjectReference.newBuilder()
                            .setType(ObjectType.newBuilder().setNamespace(KESSEL_RBAC_NAMESPACE).setName(KESSEL_IDENTITY_SUBJECT_TYPE).build())
                            .setId(getUserId(identity))
                            .build()
                    ).build()
            ).build();
    }

    /**
     * Build a lookup request for integrations.
     * @param identity the subject's identity.
     * @param kesselPermission the permission we want to check against the
     *                         subject's integrations.
     * @return a built lookup request that aims at finding integrations for the
     * given subject.
     */
    protected LookupResourcesRequest buildLookupResourcesRequest(final RhIdentity identity, final KesselPermission kesselPermission) {
        return LookupResourcesRequest.newBuilder()
            .setSubject(
                SubjectReference.newBuilder()
                    .setSubject(
                        ObjectReference.newBuilder()
                            .setType(ObjectType.newBuilder().setNamespace(KESSEL_RBAC_NAMESPACE).setName(KESSEL_IDENTITY_SUBJECT_TYPE).build())
                            .setId(getUserId(identity))
                    ).build()
            ).setRelation(kesselPermission.getKesselPermissionName())
            .setResourceType(ResourceType.INTEGRATION.getKesselObjectType())
            .build();
    }

    private String getUserId(RhIdentity identity) {
        return backendConfig.getKesselDomain() + "/" + identity.getUserId();
    }
}
