---
name: otel-wide-events
description: Add or review Bun Do OpenTelemetry instrumentation using one context-rich completion span per request or job. Use for backend tracing, exception diagnostics, telemetry enrichment and export tests, not ordinary application logging or Azure provisioning.
---

# OpenTelemetry wide events

Use a completed operation span as the structured record of what happened.
Enrich it during execution and end it once, including on failure. Add child
spans where an external call or independently useful operation needs timing,
not for every method. Choose attributes to answer concrete troubleshooting
questions rather than meet a field-count target.

This approach follows [Boris Tane's logging guidance](https://loggingsucks.com/),
read September 12, 2026. OpenTelemetry supplies collection and transport;
application code must supply the diagnostic context.

## Bun Do implementation

- Start from `src/BunDo.Functions/Program.cs` and `Telemetry/`. Keep tracing out
  of deterministic `BunDo.Domain` decisions. Instrument their host/adapter
  boundaries instead.
- Cover the entire Function invocation, including HTTP result execution.
  Middleware order matters with the isolated worker's ASP.NET integration.
  Capture HTTP context while the proxy owns it. The outer completion boundary
  must not read a context the proxy has released for disposal or reuse.
- Include operation/function name, start/duration, outcome, HTTP status where
  available, service/build version and a random correlation ID. Add actual
  operation stage, result codes, counts, provider status, retry count and usage
  as the implementation learns them. Do not invent business context for the
  health endpoint or build an unused universal attribute framework.
- Capture useful exception type and bounded code-only stack frames. Exclude
  arbitrary exception messages, `Data`, inner-message text and absolute source
  paths. Check both the emitted span and the exporter's serialized payload.
- Derive route names from code, not raw URLs. Do not copy incoming baggage or
  trace state into exports. Keep independent invocations isolated across
  concurrency, cancellation and nested calls.
- Never record household titles, notes, audio, prompts/responses, email,
  credentials, invitation secrets, headers or request bodies. New identifier
  attributes need a real diagnostic purpose and a trusted, validated source.
  An opaque ID is not permission to dump the surrounding object.

## Sampling and delivery

The owner chose complete useful traces for the low-traffic app on September 12,
2026. Do not add a collector or 95% sampling merely because the article discusses
high-volume systems. Start with no intentional trace sampling and no arbitrary
per-minute exception dropping. Keep bounded queues and explicit delivery limits.

If sampling becomes necessary, retain failures and slow operations before
sampling normal completions. Operation-local completion filtering is not
distributed trace tail sampling. Never claim a head sampler preserves every
failed trace or that a bounded exporter guarantees delivery.

Keep the existing direct Azure export and local Aspire OTLP paths unless the
task explicitly changes deployment architecture. Do not enable noisy SDK logs,
automatic metrics or a second duplicate exporter incidentally.

## Prove usefulness

Tests must cover a successful operation, failure after handler return,
cancellation, overlapping requests and enrichment. Verify useful attributes
survive serialization while private canaries and incoming baggage do not.
Include a query showing which operation/build failed and where in code.

For cloud changes, follow `docs/azure-development.md`, then prove live ingestion
with synthetic inputs. Inspect full records, not just a projection of safe
columns. Remove temporary probes and verify normal routes afterward. Update
the owning telemetry contract and record delivery gaps without claiming them
fixed by sampling.
