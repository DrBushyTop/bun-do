# OpenTelemetry completion spans

The owner selected full useful traces for the low-traffic app on September 12,
2026. One context-rich span records each Function invocation, including normal
completions, failures and cancellation. There is no intentional sampling or
per-minute failure dropper. We do not operate a collector just to sample traces.
This replaces the earlier exception-only design.

`observability.bicep` owns a pay-as-you-go Log Analytics workspace and a
workspace-based Application Insights component. There are no enabled availability
checks, paid alert rules, dashboards or budget alerts. Azure's automatically
created Failure Anomalies smart-detector rule is explicitly disabled, with an
empty action-group list. The template removes its accidental cross-project
notification link without changing the unrelated action group.

## What a completion tells us

`FunctionTelemetryMiddleware` starts an independent trace before the HTTP proxy
runs. `FunctionExecutionTelemetryMiddleware` marks entry into application code
and return to response execution. The outer middleware ends the span after
`IActionResult` execution, including when serialization fails.

HTTP invocations produce `AppRequests`. Non-HTTP invocations use consumer spans.
Exception events also produce correlated `AppExceptions`. `AppDependencies`
remains available for meaningful child operations when adapters need them.
The request span contains:

- Compiled function name, trace/span IDs, start time and duration.
- Service name and assembly informational version, including the source revision
  supplied by the .NET build. Uncommitted edits do not get a distinct revision.
- Outcome and last execution stage. Stages distinguish invocation setup,
  function execution and response execution. Success ends at `completed`.
- HTTP method from a fixed verb list and response status, where available.
  Unhandled failures before response start use the host's expected 500 status.
  Cancellation before response start does not invent an HTTP status.
  Status-only responses can start after the invocation span ends. Their status
  may be absent. The middleware never reads a released or reused HTTP context.
  Local OTLP retains the method attribute. The pinned Azure request exporter
  omits it from stored rows; function name remains the operation identifier.
- On unexpected exceptions, the exception type and at most 12 code-only stack
  frames, capped at 2,048 characters. Messages remain a fixed redaction string.

Application boundaries can enrich their current span with actual result codes,
counts, provider status, retry count or token/RU usage as those operations are
implemented. The health endpoint has no invented household or provider context.
Add child spans for useful external-call timing, not for every method. Follow
[the repo telemetry skill](../.agents/skills/otel-wide-events/SKILL.md).

Expected host or HTTP-request cancellation has an explicit cancelled completion
without an exception event. Unexpected cancellation remains a failure. Observed
HTTP 5xx also marks failure even when no exception was thrown. An unavailable
status cannot change the invocation outcome. Other observed HTTP status codes
remain visible on successful invocation completions.

## Privacy and coverage

Never attach household text, request bodies, raw URLs, headers, email, audio,
prompts, credentials or invitation secrets. Do not call `RecordException` with
an exporter that serializes the original exception. Our exception event reads
reflected code identifiers, not `Exception.ToString()`, source file paths,
exception `Data` or inner messages. Incoming trace IDs, baggage and trace state
do not become the operation's correlation context.

The middleware rethrows the original exception and restores the ambient host
activity. Independent invocations remain isolated when they overlap or nest.
It cannot observe requests rejected before worker invocation, host startup
failures, process crashes or operations outside this boundary. Future background
workers need their own completion boundary.

Azure adds service and backend host metadata, including host OS and approximate
server location. These are not household request fields.

## Cost and delivery limits

- Retain emitted request, exception and dependency tables for 30 days, including
  total retention.
- Configure the workspace cap at 100 MiB/day. Azure may overshoot or delay its
  cutoff. This is a backstop, not a spending guarantee or a free-service claim.
- The exporter queue holds at most 128 activities, in batches of 32. There is no
  disk spool. Shutdown, queue overflow, rate limits, ingestion caps and network
  outages can lose diagnostics. Full collection does not guarantee delivery.
- Do not add metric/log exporters, automatic HTTP instrumentation, performance
  counters or live metrics to the cloud worker incidentally. Local Aspire keeps
  its existing OTLP diagnostics and also receives these completion spans.

The pinned Azure Monitor exporter is 1.9.0. Its trace-registration helper also
installs a metric processor, so `Program.cs` registers the trace exporter
directly. Backend settings disable resource metrics, customer SDK stats and
Statsbeat. Tests inspect actual serialized request and exception envelopes,
including useful enrichment and build version, absence of metrics and private
canaries. Recheck this behavior when upgrading the package.

## Authentication and inspection

Application Insights and Log Analytics disable local/key authentication.
Backend hosting grants its user-assigned identity Monitoring Metrics Publisher
only on this Application Insights component. The worker receives
`BunDoTelemetry__ConnectionString`, not the standard host Application Insights
connection setting. The Functions host therefore has no cloud exporter that can
bypass worker redaction.

Cloud worker logger providers are removed and the host's default log level is
`None`, since invocation RPC can contain original exception messages. Local
Aspire logging remains enabled. Do not add a host diagnostic route or override
these levels without reviewing content handling again.

Use the workspace Logs view to find the failing operation, build and code frames:

```kusto
AppRequests
| where TimeGenerated > ago(24h)
| where Success == false
| project TimeGenerated, OperationId, Name, AppVersion, DurationMs, ResultCode,
    Outcome = tostring(Properties["operation.outcome"]),
    Stage = tostring(Properties["operation.stage"])
| join kind=leftouter (
    AppExceptions
    | where TimeGenerated > ago(24h)
    | project OperationId, ExceptionType, Details
) on OperationId
| order by TimeGenerated desc
```

For cancellations, query `Properties["operation.outcome"] == "cancelled"` rather
than `Success == false`. Review full records when verifying privacy; a query
projection alone cannot establish absence of private fields.

The implementation follows [Boris Tane's wide-event guidance](https://loggingsucks.com/).
Pinned exporter behavior is defined by its
[registration](https://github.com/Azure/azure-sdk-for-net/blob/Azure.Monitor.OpenTelemetry.Exporter_1.9.0/sdk/monitor/Azure.Monitor.OpenTelemetry.Exporter/src/AzureMonitorExporterExtensions.cs),
[resource conversion](https://github.com/Azure/azure-sdk-for-net/blob/Azure.Monitor.OpenTelemetry.Exporter_1.9.0/sdk/monitor/Azure.Monitor.OpenTelemetry.Exporter/src/Internals/ResourceExtensions.cs)
and [environment settings](https://github.com/Azure/azure-sdk-for-net/blob/Azure.Monitor.OpenTelemetry.Exporter_1.9.0/sdk/monitor/Azure.Monitor.OpenTelemetry.Exporter/src/Internals/Platform/EnvironmentVariableConstants.cs).
