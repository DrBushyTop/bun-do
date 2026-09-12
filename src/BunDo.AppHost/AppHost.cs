var builder = DistributedApplication.CreateBuilder(args);

if (builder.ExecutionContext.IsPublishMode)
{
    throw new InvalidOperationException("Bun Do's AppHost is local-only. Deploy Azure resources with Bicep.");
}

var localProfile = builder.Configuration["BunDo:LocalProfile"] ?? "container-azurite";
if (!string.Equals(localProfile, "container-azurite", StringComparison.Ordinal))
{
    throw new InvalidOperationException(
        $"Bun Do's AppHost only supports the local 'container-azurite' profile. Received '{localProfile}'.");
}

var hostStorage = builder.AddAzureStorage("host-storage")
    .RunAsEmulator();

builder.AddAzureFunctionsProject<Projects.BunDo_Functions>("functions")
    .WithHostStorage(hostStorage)
    .WithHttpHealthCheck("/api/health")
    .WaitFor(hostStorage);

builder.Build().Run();
