# Error Handling Guidelines

Rules and conventions for error handling in `notifications-backend`, derived from existing patterns. All agents MUST follow these during implementation and review.

## 1. Custom Exception Hierarchy

### Existing exceptions and when to use them

- **`DelayedException`** (`common`): Thrown by `DelayedThrower` to wrap multiple suppressed exceptions from loop processing. Never throw directly; use `DelayedThrower.throwEventually()` instead.
- **`ActionParsingException`** (`common`): Thrown when an incoming Kafka payload cannot be parsed as an `Action`. Extends `RuntimeException`.
- **`TemplateNotFoundException`** (`common-template`): Thrown when a Qute template lookup fails. Extends `RuntimeException`.
- **`KesselTransientException`** (`backend`): Marker exception for retryable gRPC failures. Only this type triggers `@Retry` on Kessel calls. Wraps `StatusRuntimeException`.
- **`IllegalIdentityHeaderException`** (`backend`): Checked exception for invalid x-rh-identity headers.
- **`UnsupportedFormatException`**, **`TransformationException`**, **`FilterExtractionException`** (`engine`): Checked exceptions for export service failures.

### Rules for new exceptions

- Place shared exceptions in the `common` module. Place module-specific exceptions in their own module.
- Use `RuntimeException` for unrecoverable errors that should propagate. Use checked exceptions only when callers must explicitly handle them (e.g., export transformations).
- Name exceptions with the pattern `<Domain><Problem>Exception` (e.g., `KesselTransientException`, `ActionParsingException`).
- When creating marker exceptions for retry (like `KesselTransientException`), always use `retryOn` in the `@Retry` annotation to scope retries narrowly.

## 2. DelayedThrower Pattern

Use `DelayedThrower.throwEventually()` when processing a collection where one failure must not prevent processing the remaining items. This is the standard pattern in the engine for endpoint processing.

```java
// CORRECT: process all endpoints, rethrow all failures at the end
DelayedThrower.throwEventually("Exceptions were thrown during an event processing", accumulator -> {
    for (Endpoint endpoint : endpoints) {
        try {
            process(event, endpoint);
        } catch (Exception e) {
            accumulator.add(e);
        }
    }
});
```

- Always use the constant `EndpointProcessor.DELAYED_EXCEPTION_MSG` as the message when used in endpoint processing context.
- All accumulated exceptions are attached as suppressed exceptions on the resulting `DelayedException`.
- Nested `DelayedException` instances are automatically flattened (suppressed exceptions are promoted up).

## 3. JAX-RS Exception Mappers

The backend module registers these `@Provider` exception mappers. Follow the same patterns when adding new ones.

| Mapper | Exception | HTTP Status | Response Body |
|---|---|---|---|
| `JaxRsExceptionMapper` | `WebApplicationException` | Original status (400 for `BadRequestException`, unwraps `JsonParseException` from cause) | `exception.getMessage()` |
| `ConstraintViolationExceptionMapper` | `ConstraintViolationException` | 400 | JSON with `title`, `description`, and `violations[]` array containing `field` and `message` |
| `NotFoundExceptionMapper` | `NotFoundException` | Original status | `exception.getMessage()` as `text/plain` |
| `JsonParseExceptionMapper` | `JsonParseException` | 400 | `exception.getMessage()` (legacy, may be unused since Quarkus 2.15.1) |

### Rules

- The `ConstraintViolationExceptionMapper` exists because Quarkus returns 500 instead of 400 when `@Valid` is used on non-top-level handler methods. Always use this structured JSON format for validation errors.
- For REST client interfaces, use `@ClientExceptionMapper` (Quarkus reactive) or `@RegisterProvider(SomeMapper.class)` (MicroProfile) to map remote service errors.
- The `recipients-resolver` module has its own `WebApplicationExceptionMapper` that mirrors the backend's `JaxRsExceptionMapper`. Keep them consistent.
- Never return stack traces in HTTP responses. Return the exception message only.

## 4. Retry Patterns (MicroProfile Fault Tolerance)

### REST client retries (connectors v2)

```java
@Retry(delay = 1, delayUnit = ChronoUnit.SECONDS, maxRetries = 2)
// Comment: 1 initial + 2 retries = 3 attempts
```

This pattern is used on `WebhookRestClient`, `SourcesPskClient`, and `SourcesOidcClient` in the v2 connector modules. Always include the comment explaining total attempts.

### Internal service retries (backend)

```java
@Retry(maxRetries = 3)
```

Used on REST client interfaces for internal services: `SourcesPskService`, `SourcesOidcService`, `ExportService`, `DailyDigestService`, `EndpointTestService`, `GeneralCommunicationsService`.

### Selective retry (Kessel)

```java
@Retry(maxRetries = 3, delay = 100, retryOn = KesselTransientException.class)
```

Use `retryOn` to limit retries to specific transient exceptions. The `KesselCheckClient` classifies gRPC errors into transient (`UNAVAILABLE`, `DEADLINE_EXCEEDED`, `RESOURCE_EXHAUSTED`, `ABORTED`) vs non-transient, wrapping only transient ones in `KesselTransientException`. `UNAUTHENTICATED` errors trigger channel recreation with fresh credentials before being wrapped as transient.

### Camel connector redelivery (v1 connectors)

Configured via `ConnectorConfig`:
- `notifications.connector.redelivery.delay` (default: 1000ms)
- `notifications.connector.redelivery.max-attempts` (default: 2)

### Kafka reinjection (v1 connectors)

When a connector fails to deliver a message, `ExceptionProcessor` reinjects it to the incoming Kafka topic up to `notifications.connector.kafka.maximum-reinjections` times (default: 3). After exhausting reinjections, the failure is reported back to the engine.

## 5. Connector Error Handling

### V1 connectors (Camel-based, `connector-common`)

- Extend `ExceptionProcessor` to customize failure behavior. The base class handles logging, Kafka reinjection, and sending failure results to the engine.
- Extend `HttpExceptionProcessor` for HTTP-based connectors. It classifies errors into `HttpErrorType` enum values (`HTTP_3XX`, `HTTP_4XX`, `HTTP_5XX`, `SOCKET_TIMEOUT`, `SSL_HANDSHAKE`, `UNKNOWN_HOST`, `CONNECTION_REFUSED`, `CONNECT_TIMEOUT`, `UNSUPPORTED_SSL_MESSAGE`).
- Log levels for HTTP errors are configurable: client errors (4xx) and server errors (5xx/3xx) can use different log levels via `HttpConnectorConfig`.
- For retry purposes, 429 (Too Many Requests) is intentionally classified as `HTTP_5XX` to enable retries, even though it is officially a 4xx client error in the HTTP specification. This classification prioritizes retry behavior over HTTP semantics.

### V2 connectors (`connector-common-v2`)

- Extend `ExceptionHandler` to customize failure behavior. Override `process(Throwable, IncomingCloudEventMetadata)` to return typed `HandledExceptionDetails`.
- Extend `HttpExceptionHandler` for HTTP connectors. It classifies exceptions identically to v1 but returns `HandledHttpExceptionDetails` with `httpStatusCode`, `httpErrorType`, and `targetUrl`.
- `MessageConsumer` catches all exceptions from `MessageHandler.handle()`, passes them to `ExceptionHandler`, and sends failure responses via `OutgoingMessageSender`. Messages are always acknowledged.
- The `OutgoingMessageSender.sendResponse()` wraps emitter failures in `RuntimeException("Failed to send response to engine", e)`.

### Endpoint auto-disabling (engine)

`EndpointErrorFromConnectorHelper` processes connector results and disables endpoints:
- **4xx/3xx errors**: Endpoint is disabled immediately (config problem).
- **5xx/server errors**: `endpointRepository.incrementEndpointServerErrors()` is called. Endpoint is disabled only after exceeding the max allowed server errors.
- Successful delivery resets the server error counter.

## 6. Kafka Consumer Error Handling

### EventConsumer (engine)

- All processing exceptions are caught, logged at `info` level with the payload, and counted via `input.processing.exception` metric. The message is always acknowledged.
- Unknown event types throw `NoResultException` and increment the `input.rejected` counter.
- Parsing failures try `Action` format first, then `CloudEvent` format. If both fail, the `ActionParsingException` carries the `CloudEventParsingException` as a suppressed exception.
- Endpoint processing failures increment the `input.processing.error` counter.
- Duplicate events are silently skipped with a `debug` log and `input.duplicate.event` counter.

### ConnectorReceiver (engine)

- Processes return messages from connectors. Failures are caught, logged at `error` level, and counted via `camel.messages.error`. Messages are always processed (counter incremented in `finally`).
- Uses `@Acknowledgment(Strategy.POST_PROCESSING)` so Kafka offset is committed after processing.

### Rules

- Never let a Kafka consumer throw an unhandled exception. Always catch at the top level and log + count.
- Always acknowledge messages even on failure to prevent consumer stalling.
- Use Micrometer counters for all error categories to enable alerting.

## 7. Logging Conventions

- Use `io.quarkus.logging.Log` (static import), never instantiate loggers manually.
- Use `Log.infof()`, `Log.warnf()`, `Log.errorf()` with format strings. Always include contextual identifiers: `orgId`, `historyId`, `endpointId`, `eventTypeId`.
- Log processing failures at `info` level in Kafka consumers (high volume, expected failures).
- Log connector HTTP errors at configurable levels (client vs server errors).
- Log non-transient errors at `error`, transient/retryable errors at `warn`.
- Sentry is configured via `quarkus.log.sentry.*` properties, disabled by default, enabled in OpenShift. Set `in-app-packages` to `com.redhat.cloud.notifications` (or `*` for backend/recipients-resolver).

## 8. Validation Error Handling

- Use Jakarta Bean Validation annotations (`@NotNull`, `@NotBlank`, `@Size`, `@Valid`) on DTOs and REST parameters.
- The `ConstraintViolationExceptionMapper` ensures all validation failures return 400 with a structured JSON body, not 500.
- For REST client interfaces, use `@ClientExceptionMapper` to convert remote 4xx responses into appropriate local exceptions.

## 9. Metrics and Observability

Every error path must have an associated Micrometer counter. Established counter names:

| Counter | Location | Meaning |
|---|---|---|
| `input.rejected` | EventConsumer | Unknown event type |
| `input.processing.error` | EventConsumer | Endpoint processing failure |
| `input.processing.exception` | EventConsumer | Any processing exception |
| `input.processing.blacklisted` | EventConsumer | Blacklisted event type skipped |
| `input.duplicate.event` | EventConsumer | Duplicate event skipped |
| `camel.messages.error` | ConnectorReceiver | Connector return processing failure |
| `processor.webhook.disabled.endpoints` | EndpointErrorFromConnectorHelper | Endpoints disabled (tagged by `error_type`) |

Use `Timer.Sample` for latency tracking on message processing paths (see `EventConsumer.CONSUMED_TIMER_NAME`).
