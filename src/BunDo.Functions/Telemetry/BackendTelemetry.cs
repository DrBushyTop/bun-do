using System.Diagnostics;
using System.Reflection;

namespace BunDo.Functions.Telemetry;

public sealed class BackendTelemetry
{
    public const string SourceName = "BunDo.Backend";
    public const string ServiceName = "bun-do-backend";
    public static readonly string BuildVersion = typeof(BackendTelemetry).Assembly
        .GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "unknown";
    private static readonly ActivitySource Activities = new(SourceName, BuildVersion);

    public Activity? StartInvocation(string functionName, bool isHttp)
    {
        var activity = Activities.StartActivity(functionName, isHttp ? ActivityKind.Server : ActivityKind.Consumer);
        activity?.SetTag("function.name", functionName);
        activity?.SetTag("operation.stage", "invocation");
        return activity;
    }

    public static void RecordException(Activity? activity, Exception exception)
    {
        activity?.SetStatus(ActivityStatusCode.Error);
        activity?.AddEvent(new ActivityEvent("exception", tags: new ActivityTagsCollection
        {
            { "exception.type", exception.GetType().FullName },
            { "exception.message", "Unhandled function failure; message omitted." },
            { "exception.stacktrace", CodeFrames(exception) },
        }));
    }

    private static string CodeFrames(Exception exception)
    {
        // Read reflected code identifiers, never Exception.StackTrace/ToString,
        // source file paths, arguments, Data or inner exception messages.
        var frames = new StackTrace(exception, fNeedFileInfo: false).GetFrames();
        var stack = string.Join('\n', frames.Take(12).Select(frame => frame.GetMethod())
            .Where(method => method?.DeclaringType is not null)
            .Select(method => $"at {method!.DeclaringType!.FullName}.{method.Name}()"));
        return stack[..Math.Min(2048, stack.Length)];
    }
}
