using System.Diagnostics;
using Azure.Identity;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Azure.Functions.Worker;

namespace BunDo.FoundationGates;

// Copied into a temporary package for a reviewed live gate, never normal source.
public sealed class FoundationGateFunction
{
    private static readonly HttpClient Client = new(new HttpClientHandler { AllowAutoRedirect = false })
    {
        Timeout = TimeSpan.FromSeconds(30),
    };

    [Function("FoundationGate")]
    public async Task<IActionResult> Run(
        [HttpTrigger(AuthorizationLevel.Function, "post", Route = "foundation-gate/{kind}")] HttpRequest request, string kind)
    {
        if (kind is not ("storage" or "ai-configuration" or "ai-incomplete")) return new BadRequestResult();
        var checks = new FoundationGateChecks(Client, new ManagedIdentityCredential(
            ManagedIdentityId.FromUserAssignedClientId(Environment.GetEnvironmentVariable("AZURE_CLIENT_ID")!)));
        Activity.Current?.SetTag("app.gate", kind);
        if (kind == "storage")
        {
            var report = await checks.Storage(new Uri(Environment.GetEnvironmentVariable("WorkspaceStore__Endpoint")!),
                new Uri(Environment.GetEnvironmentVariable("Snapshots__BlobEndpoint")!));
            Activity.Current?.SetTag("app.result.code", report["result"]);
            return new OkObjectResult(report);
        }
        var result = await checks.AiFailure(new Uri(Environment.GetEnvironmentVariable("AI__Endpoint")!),
            Environment.GetEnvironmentVariable("AI__LunaDeployment")!, kind == "ai-incomplete");
        Activity.Current?.SetTag("app.result.code", result.Classification);
        Activity.Current?.SetTag("app.provider.status_code", result.HttpStatus);
        Activity.Current?.SetTag("app.ai.input_tokens", result.InputTokens);
        Activity.Current?.SetTag("app.ai.output_tokens", result.OutputTokens);
        return new OkObjectResult(result);
    }
}
