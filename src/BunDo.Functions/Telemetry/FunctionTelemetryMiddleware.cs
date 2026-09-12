using System.Diagnostics;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Azure.Functions.Worker.Middleware;

namespace BunDo.Functions.Telemetry;

public sealed class FunctionTelemetryMiddleware(BackendTelemetry telemetry) : IFunctionsWorkerMiddleware
{
    internal static readonly object OperationKey = new();
    internal static readonly object HttpSnapshotKey = new();

    public async Task Invoke(FunctionContext context, FunctionExecutionDelegate next)
    {
        var parent = Activity.Current;
        var isHttp = context.FunctionDefinition.InputBindings.Values.Any(binding =>
            string.Equals(binding.Type, "httpTrigger", StringComparison.OrdinalIgnoreCase));
        // Public callers cannot choose our correlation ID, baggage or sampling.
        Activity.Current = null;
        using var activity = telemetry.StartInvocation(context.FunctionDefinition.Name, isHttp);
        context.Items[OperationKey] = activity!;
        var outcome = "success";
        try
        {
            await next(context);
        }
        catch (OperationCanceledException) when (context.CancellationToken.IsCancellationRequested
            || GetHttpSnapshot(context)?.RequestAborted.IsCancellationRequested == true)
        {
            outcome = "cancelled";
            throw;
        }
        catch (Exception exception)
        {
            outcome = "failure";
            BackendTelemetry.RecordException(activity, exception);
            throw;
        }
        finally
        {
            // The proxy has released ASP.NET by now. HttpContext can already be
            // disposed or pooled for another request. Only read our snapshot.
            var http = GetHttpSnapshot(context);
            if (http is not null)
            {
                activity?.SetTag("http.request.method", http.Method);
                var status = http.StatusCode ?? (outcome == "failure" ? 500 : (int?)null);
                if (status is not null)
                {
                    activity?.SetTag("http.response.status_code", status);
                    if (status >= 500)
                    {
                        outcome = "failure";
                        activity?.SetStatus(ActivityStatusCode.Error);
                    }
                }
            }
            activity?.SetTag("operation.outcome", outcome);
            if (outcome == "success")
                activity?.SetTag("operation.stage", "completed");
            context.Items.Remove(OperationKey);
            context.Items.Remove(HttpSnapshotKey);
            activity?.Stop();
            Activity.Current = parent;
        }
    }

    private static HttpSnapshot? GetHttpSnapshot(FunctionContext context) =>
        context.Items.TryGetValue(HttpSnapshotKey, out var value) ? value as HttpSnapshot : null;

    internal sealed class HttpSnapshot(string method, CancellationToken requestAborted)
    {
        public string Method { get; } = method.ToUpperInvariant() switch
        {
            "GET" or "HEAD" or "POST" or "PUT" or "DELETE" or "CONNECT" or "OPTIONS"
                or "TRACE" or "PATCH" => method.ToUpperInvariant(),
            _ => "_OTHER",
        };
        public CancellationToken RequestAborted { get; } = requestAborted;
        // OnStarting runs while ASP.NET still owns the context. A status-only
        // result may start after our span ends; omit that unavailable status.
        private int statusCode;
        public int? StatusCode => Volatile.Read(ref statusCode) is var code && code != 0 ? code : null;
        public void RecordStatus(int code) => Volatile.Write(ref statusCode, code);
    }
}

// Registered inside the HTTP proxy. Returning from this middleware starts
// IActionResult execution, which the outer middleware must still observe.
public sealed class FunctionExecutionTelemetryMiddleware : IFunctionsWorkerMiddleware
{
    public async Task Invoke(FunctionContext context, FunctionExecutionDelegate next)
    {
        var activity = context.Items.TryGetValue(FunctionTelemetryMiddleware.OperationKey, out var value)
            ? value as Activity : null;
        var http = context.GetHttpContext();
        if (http is not null)
        {
            var snapshot = new FunctionTelemetryMiddleware.HttpSnapshot(
                http.Request.Method, http.RequestAborted);
            context.Items[FunctionTelemetryMiddleware.HttpSnapshotKey] = snapshot;
            http.Response.OnStarting(() =>
            {
                snapshot.RecordStatus(http.Response.StatusCode);
                return Task.CompletedTask;
            });
        }
        activity?.SetTag("operation.stage", "function");
        await next(context);
        activity?.SetTag("operation.stage", "response");
    }
}
