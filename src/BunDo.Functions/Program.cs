using Azure.Identity;
using Azure.Monitor.OpenTelemetry.Exporter;
using BunDo.Functions.Telemetry;
using OpenTelemetry.Resources;
using OpenTelemetry.Trace;
using Microsoft.Azure.Functions.Worker.Builder;
using Microsoft.Azure.Functions.Worker.OpenTelemetry;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using OpenTelemetry;

var builder = FunctionsApplication.CreateBuilder(args);

builder.Services.AddSingleton<BackendTelemetry>();
// Wrap the HTTP proxy too, including IActionResult execution/serialization.
builder.UseMiddleware<FunctionTelemetryMiddleware>();
builder.ConfigureFunctionsWebApplication();
builder.UseMiddleware<FunctionExecutionTelemetryMiddleware>();

var telemetry = builder.Services.AddOpenTelemetry()
    .ConfigureResource(resource => resource.Clear().AddService(
        BackendTelemetry.ServiceName, serviceVersion: BackendTelemetry.BuildVersion))
    .WithTracing(traces => traces.AddSource(BackendTelemetry.SourceName)
        .SetSampler(new AlwaysOnSampler()));
var cloudConnection = builder.Configuration["BunDoTelemetry:ConnectionString"];
if (!string.IsNullOrWhiteSpace(cloudConnection))
{
    // The worker's default gRPC logger otherwise forwards original exceptions
    // to the host, bypassing the redacted exporter.
    builder.Logging.ClearProviders();
    var credential = new ManagedIdentityCredential(ManagedIdentityId.FromUserAssignedClientId(
        builder.Configuration["AZURE_CLIENT_ID"]
        ?? throw new InvalidOperationException("Cloud telemetry requires the backend identity.")));
    var options = new AzureMonitorExporterOptions
    {
        ConnectionString = cloudConnection,
        Credential = credential,
        DisableOfflineStorage = true,
        SamplingRatio = 1,
        TracesPerSecond = null,
        EnableLiveMetrics = false,
        EnableStandardMetrics = false,
        EnablePerformanceCounters = false,
    };

    // Use the trace exporter directly. Its convenience registration also adds
    // metric extraction. Explicit completion spans supply safe operation context.
    telemetry.WithTracing(traces => traces.AddProcessor(new BatchActivityExportProcessor(
        new AzureMonitorTraceExporter(options), maxQueueSize: 128, maxExportBatchSize: 32)));
}
else
{
    telemetry.UseFunctionsWorkerDefaults().UseOtlpExporter();
}

builder.Build().Run();
