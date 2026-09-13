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

var functions = builder.AddAzureFunctionsProject<Projects.BunDo_Functions>("functions")
    .WithHostStorage(hostStorage)
    .WithHttpHealthCheck("/api/health")
    .WaitFor(hostStorage);

var identityMode = builder.Configuration["BunDoIdentity:Mode"] ?? "Microsoft";
if (identityMode is not ("Microsoft" or "Local"))
    throw new InvalidOperationException("Select Microsoft or Local identity explicitly.");
functions.WithEnvironment("BunDoIdentity__Mode", identityMode);

// Real Microsoft sign-in is an opt-in live gate. Ordinary local startup has no identity dependency.
if (builder.Configuration["BunDoIdentity:Audience"] is { Length: > 0 } identityAudience)
{
    functions.WithEnvironment("BunDoIdentity__Audience", identityAudience);
}

builder.Build().Run();
