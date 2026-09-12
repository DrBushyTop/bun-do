# Wide OpenTelemetry completion spans

September 12, 2026. The owner replaced exception-only collection with full useful
traces because application traffic is small. The implementation follows the
wide-event guidance at `https://loggingsucks.com/`. The repo skill at
`.agents/skills/otel-wide-events/SKILL.md` records the approach and privacy rules.
Its validator passed.

## Implementation and review

An implementation subagent added context-rich completion spans for successful,
failed and cancelled Function invocations. The Azure exporter sends request and
exception records without intentional sampling or the former per-minute failure
dropper. Meaningful future adapter calls can add child spans and actual result,
count and provider context. No collector was added.

A fresh adversarial reviewer reproduced a lifecycle defect. The outer middleware
read `HttpContext` after the HTTP proxy had released it. A disposed context threw
from telemetry cleanup, replaced the original outcome and left an incomplete
span. The main agent fixed it by capturing method/cancellation inside the proxy
and response status through `OnStarting`. The outer boundary reads only that
snapshot. A regression covers disposed-context success, failure and cancellation,
original exception preservation, ambient activity restoration and item cleanup.
The reviewer passed the fix.

Late-starting status-only responses can lack an observed status. They do not
change the invocation outcome without an observed 5xx or an exception. This
limitation is explicit in the telemetry contract rather than hidden behind a
made-up 200. Expected cancellation is not an exception record.

Local verification passed 11 hosting tests, 44 domain tests, 54 Python tests and
repository invariants. Actual exporter serialization tests preserve enrichment,
build identity and bounded code-only frames while excluding private canaries.
Aspire also received four independent Health completion spans with GET, HTTP
200, success and completed stage. The Function listener was loopback-only.
Aspire was stopped after verification.

## Infrastructure

AppRequests now has 30-day retention and total retention, alongside dependencies
and exceptions. A guarded plan caught an Azure-created anomaly rule attached to
another project's action group. The template now owns that rule in Bun Do's
group, disables it and clears its action groups. It did not change the unrelated
action group. No budget alerts were added.

The combined AI/observability deployment and controlled redeployment succeeded.
Live reads preserved table schemas and Analytics plans, existing identities,
storage policies and Cosmos RIDs/manual 400 RU/s. All three emitted tables have
30-day retention. Identity-only ingestion and host-log suppression remain active.

## Live application check

A temporary function-key-protected probe exercised a throwing `IActionResult`
and the three synthetic AI schema gates. The unauthorized request returned 401.
The result-execution failure returned 500 without the synthetic private message.

The 18:11:17 UTC query contained four `AppRequests` rows and one correlated
`AppExceptions` row. The failure began at 18:07:27 UTC. An earlier query had only
the exception, so visibility was delayed and the correlated rows did not appear
together. The failure span recorded outcome `failure`, stage `response`, HTTP
500 and build `1.0.0-wide-gate-20260912`. Its exception frames named
`FoundationProbe.ThrowingResult.ExecuteResultAsync` and the middleware.

The other three requests recorded HTTP 200, successful completion, schema name,
deployment, provider status and input/output usage. Full stored-record inspection
found no synthetic exception/inner-message, query, baggage or trace-state canary,
incoming trace ID, prompt/output text or source path. No log or metric rows
appeared. The exporter uses `://` as the empty request URL placeholder; it does
not contain the caller's URL. The Azure request exporter also omits the HTTP
method attribute from stored rows, although the local OTLP spans retain it.

The clean normal artifact uses build `1.0.0-wide-20260912` and contains only
Health. Both artifacts have separately recorded SHA-256 hashes. The normal
package was republished successfully. At 18:11:14 UTC, health returned 200, the
removed probe returned 404 and Azure listed only Health. No temporary probe
source remains in the application. A separate query subsequently found the
normal build's Health span at 18:11:15 UTC with HTTP 200, success and completed
stage.

Private artifacts, full queries and readbacks remain under ignored
`.azure/foundation/`. These checks do not prove crash/startup coverage, guaranteed
delivery or every late-starting HTTP status. No claim of a maximum ingestion
delay follows from this experiment.

The fresh reviewer checked the artifact hashes, complete stored records, local
and normal-build traces, and probe cleanup. No material finding remains.
