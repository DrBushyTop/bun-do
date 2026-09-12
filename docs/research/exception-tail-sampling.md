# Error retention with tail sampling

Reviewed 2026-09-12 against Microsoft documentation and upstream OpenTelemetry source. No implementation, deployment, or billing measurement was performed.

## Answer

A customer-hosted OpenTelemetry Collector can retain traces containing errors and approximately 5% of other traces, then send the retained traces to Azure Monitor. This is not a built-in outcome-aware switch in the .NET Azure Monitor SDK. Microsoft's current native OTLP ingestion guide documents the Collector-to-Azure path, while upstream provides the tail-sampling processor. Combining them is an architectural inference, not a verified Bun Do deployment. [Native OTLP ingestion](https://learn.microsoft.com/en-us/azure/azure-monitor/containers/opentelemetry-protocol-ingestion), [tail-sampling processor](https://github.com/open-telemetry/opentelemetry-collector-contrib/tree/main/processor/tailsamplingprocessor).

There are three different support questions:

| Component | What the sources establish |
| --- | --- |
| .NET Azure Monitor SDK/exporter | Fixed-percentage and rate-limited trace sampling. These do not implement the full-trace error policy. |
| Azure native OTLP ingestion and managed collection | Microsoft documents direct ingestion from a customer-deployed Collector, Azure Monitor Agent on supported machines, and an AKS add-on. The ingestion guide does not document a managed tail-sampling policy setting for Functions Flex. |
| Customer-hosted upstream Collector | The beta `tail_sampling` processor supports error-status and probability policies. Microsoft explicitly limits support for the open-source components to community channels, while supporting its Azure ingestion resources. |

Sources: [SDK sampling](https://learn.microsoft.com/en-us/azure/azure-monitor/app/opentelemetry-sampling), [native OTLP setup and support boundary](https://learn.microsoft.com/en-us/azure/azure-monitor/containers/opentelemetry-protocol-ingestion#configure-your-opentelemetry-collector), [processor status and policies](https://github.com/open-telemetry/opentelemetry-collector-contrib/tree/main/processor/tailsamplingprocessor).

## Policy and limits

The following processor fragment expresses the intended policy. It is not a complete deployment configuration.

```yaml
processors:
  tail_sampling:
    decision_wait: 30s
    policies:
      - name: errors
        type: status_code
        status_code:
          status_codes: [ERROR]
      - name: normal-sample
        type: probabilistic
        probabilistic:
          sampling_percentage: 5
```

These policies combine as OR when no overriding drop policy exists. Retain all traces matching `ERROR`, plus approximately 5% of the rest. Instrumentation must mark the error on a span. A separate exception log does not automatically satisfy this trace policy. The processor also supports span-event conditions if the application records exceptions as events. [Policy evaluation and configuration](https://github.com/open-telemetry/opentelemetry-collector-contrib/tree/main/processor/tailsamplingprocessor).

Send every candidate span to the Collector before sampling. An upstream sampler that already discarded 95% of spans prevents the Collector from recovering later failures. Route every span in a trace to the same Collector instance. Buffer capacity, crashes, and error spans arriving after a decision can still cause loss, so "retain errors" is a policy, not a delivery guarantee. Tail sampling affects traces, not independent log or metric pipelines. [Sampling concepts](https://opentelemetry.io/docs/concepts/sampling/), [statefulness and late spans](https://github.com/open-telemetry/opentelemetry-collector-contrib/tree/main/processor/tailsamplingprocessor).

## Documentation discrepancy

The Application Insights FAQ still says full tail sampling is unsupported and describes the community Azure Monitor Collector exporter as unsupported by Microsoft. The newer native OTLP guide explicitly documents Collector ingestion and separates Azure-service support from community-component support. The broad FAQ Collector statement therefore conflicts with the newer guide. Do not turn either statement into a claim that Azure now manages tail sampling. [FAQ](https://learn.microsoft.com/en-us/azure/azure-monitor/app/application-insights-faq#opentelemetry-sampling), [native OTLP guide](https://learn.microsoft.com/en-us/azure/azure-monitor/containers/opentelemetry-protocol-ingestion).

The community `azuremonitorexporter` is a separate legacy-protocol route from native OTLP ingestion. It can export Collector telemetry to Application Insights, but is beta and community-supported. A new design should evaluate native OTLP with its DCR/DCE and identity setup rather than assume the older exporter has become Microsoft-supported. [Exporter](https://github.com/open-telemetry/opentelemetry-collector-contrib/tree/main/exporter/azuremonitorexporter), [native resource setup](https://learn.microsoft.com/en-us/azure/azure-monitor/containers/opentelemetry-protocol-ingestion).

## Fit for Bun Do

Functions Flex supports code deployments, not custom containers, and normally scales to zero. A reliable central tail sampler therefore needs separately hosted compute and trace buffering rather than a Collector sidecar added to the existing Flex deployment. Keeping that service available adds operational work and compute usage when the application is idle. This is a deployment inference from the hosting and processor constraints, not a measured cost estimate. [Hosting support](https://learn.microsoft.com/en-us/azure/azure-functions/functions-scale#operating-system-support), [Flex scaling](https://learn.microsoft.com/en-us/azure/azure-functions/flex-consumption-plan), [Collector statefulness](https://github.com/open-telemetry/opentelemetry-collector-contrib/tree/main/processor/tailsamplingprocessor).

For this low-traffic app, do not add the Collector solely on an assumed ingestion saving. First measure sanitized trace volume and decide whether complete failed traces justify the extra service. If adopted, verify error marking, distributed trace routing, redaction before export, overload and shutdown behavior, and the resulting Application Insights views. Existing bounded exception reporting is a different policy from collecting complete failed traces.
