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

var identityMode = builder.Configuration["BunDoIdentity:Mode"] ?? "Microsoft";
var cosmosEndpoint = builder.Configuration["WorkspaceStore:Endpoint"];
if (!string.IsNullOrWhiteSpace(cosmosEndpoint))
{
    BunDo.Functions.Storage.Cosmos.IdentityRegistrations.Configure(builder.Services, builder.Configuration);
}
#if DEBUG
else if (builder.Configuration["AZURE_FUNCTIONS_ENVIRONMENT"] == "Development"
    && string.IsNullOrEmpty(builder.Configuration["WEBSITE_INSTANCE_ID"]))
{
    builder.Services.AddSingleton<BunDo.Functions.Households.IHouseholdDocuments>(
        new BunDo.Functions.Identity.Development.LocalHouseholdDocuments(
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "BunDo", "local-households")));
    builder.Services.AddSingleton<BunDo.Functions.Households.HouseholdService>();
    builder.Services.AddSingleton<BunDo.Functions.Identity.IRegistrationStore>(
        new BunDo.Functions.Identity.Development.LocalRegistrationStore(
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "BunDo", "local-registrations")));
}
#endif
#if DEBUG
if (identityMode == "Local")
{
    var localIdentity = new BunDo.Functions.Identity.Development.LocalIdentity(builder.Configuration);
    builder.Services.AddSingleton(localIdentity);
    builder.Services.AddSingleton(localIdentity.Validator);
}
else
#endif
{
    if (identityMode != "Microsoft")
        throw new InvalidOperationException("This build does not support the selected identity mode.");
    builder.Services.AddSingleton(BunDo.Functions.Identity.AccessTokens.Create(
        builder.Configuration["BunDoIdentity:Audience"]));
}

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
