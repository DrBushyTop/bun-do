# Exception-focused observability

September 12, 2026. Resumed the interrupted backend thread and applied the owner's
last request: cheap cloud diagnostics focused on exceptions for two users. All
budget alerts remain skipped.

The owner later replaced this collection policy with full completion traces.
This note preserves the earlier experiment; see [the current implementation](../../infra/observability.md).

## Implementation and review

The worker exports redacted unhandled exceptions and a correlated failed span.
Successful requests, health counters, routine logs, automatic request/dependency
instrumentation and performance metrics are not exported. Reporting is bounded
at ten failures per minute per worker, with a 128-activity in-memory queue and
no disk spool. Failures within that limit are not sampled. The workspace cap is
100 MiB/day, not a strict spending guarantee.

A fresh medium-reasoning adversarial review found two material defects, both
fixed before deployment:

- The exception middleware originally ran inside the HTTP proxy and missed
  failures while executing or serializing `IActionResult`. It now wraps the
  proxy; a registration-order regression test protects this placement.
- The worker and host could still log raw exception messages independently of
  the redacted exporter. Cloud worker log providers are removed, the host's
  default log level is `None`, and filesystem/HTTP/error tracing settings are
  checked live. Local Aspire retains its existing diagnostics.

The pinned exporter serialization test also caught an automatic resource
metrics payload. Resource metrics and SDK statistics are disabled in the
backend configuration. The worker registers the trace exporter directly,
without the convenience helper's metric extraction processor.

## Infrastructure evidence

The reviewer independently verified the compiled-source, saved-template,
parameter and what-if hashes and the fresh plan stamp before each deployment.
The first plan had five creates, 12 modifications and seven unchanged resources.
All belonged to `rg-bun-do-dev-swc`. Existing-store differences matched the
previously reviewed provider defaults. What-if omitted workspace feature flags
and application settings, so the review required live reads of both.

The first guarded deployment succeeded. Live reads confirmed identity-only
Application Insights/Log Analytics access, 30-day workspace and emitted-table
retention, 30-day total table retention, the daily cap and host log suppression.
The backend identity gained Monitoring Metrics Publisher only on the dedicated
Application Insights component. Existing identity IDs, backend configuration,
Cosmos RIDs/policies/manual 400 RU/s and snapshot IDs/policies were preserved.

The controlled-redeployment plan had 16 modifications and eight unchanged
resources, with no creation or deletion. New differences were Application
Insights creation-method metadata, unresolved identity references, and omitted
standard table schema/Analytics plan defaults. The guarded redeployment
succeeded. Follow-up reads preserved workspace/component IDs, ingestion
configuration, full table schemas, Analytics plans and 30-day retention.
Backend identity/grants and the checked Cosmos/snapshot settings also remained
unchanged.

## Verification boundaries

Local verification passed locked restore, a warning-free Release build, 44
domain tests, six hosting tests, repository invariants and 51 Python tests.
The local Aspire health request returned HTTP 200 with a loopback-only Function
listener; Aspire was stopped afterward.

A temporary, opt-in, function-key-protected probe returned a throwing
`IActionResult` to exercise the real HTTP proxy exception boundary. Its
unauthenticated request returned 401; the authorized request returned 500 with
an empty body. The normal publish artifact was rebuilt after cleaning and its
Functions metadata contains only `Health`.

The original failure at 17:09:06 UTC first appeared in the query at 17:17 UTC.
Earlier queries were empty. This establishes delayed visibility, not an exact
ingestion latency. No second failure probe was sent. A numeric-only diagnostic
prototype was built while investigating, but never deployed and then removed.

Full stored-record inspection found exactly one `AppExceptions` row and one
correlated `AppDependencies` row. Both used a new operation ID. The exception
contained only its type and fixed redaction message; the span named the compiled
probe function. No synthetic exception/inner-message, query or baggage canary,
incoming trace ID, request URL or stack appeared. Azure also adds backend host
OS/location and service metadata; these are not application request fields.
No successful request, trace-log or metric row appeared in the bounded query.

The ordinary package was republished successfully. Live health returned 200,
the removed probe returned 404, and the Azure function list contained only
`Health`. The reviewer checked both archive metadata and these live results.
No material finding or deferred bug remains in this increment.

Detailed plans, readbacks, query results and probe artifacts remain in ignored
`.azure/foundation/`. No function key was printed or saved. This increment does
not prove host startup/process-crash capture, guaranteed exception delivery,
application Cosmos/snapshot data access, Foundry schemas, sync or restore.
