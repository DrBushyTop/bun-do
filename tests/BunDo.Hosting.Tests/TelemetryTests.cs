using System.Collections.Immutable;
using System.Diagnostics;
using System.IO.Compression;
using System.Net;
using System.Text.Json;
using Azure.Core.Pipeline;
using Azure.Monitor.OpenTelemetry.Exporter;
using BunDo.Functions.Telemetry;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Azure.Functions.Worker.Middleware;

using OpenTelemetry;
using OpenTelemetry.Resources;
using OpenTelemetry.Trace;

namespace BunDo.Hosting.Tests;

public sealed class TelemetryTests
{
    [Fact]
    public async Task Completion_has_timing_status_and_enrichment_without_request_content()
    {
        var recorded = new List<Activity>();
        using var listener = Listen(recorded);
        var context = new TestContext();
        context.Http.Request.QueryString = new QueryString("?token=private-query");
        context.Http.Request.Headers.Authorization = "Bearer private-token";
        await Invoke(context, async _ =>
        {
            Activity.Current!.SetTag("app.result.code", "ready");
            await Task.Yield();
            context.Http.Response.StatusCode = 204;
        });

        var activity = Assert.Single(recorded);
        Assert.Equal(ActivityKind.Server, activity.Kind);
        Assert.Equal("Health", activity.DisplayName);
        Assert.True(activity.Duration > TimeSpan.Zero);
        Assert.Equal("success", activity.GetTagItem("operation.outcome"));
        Assert.Equal("completed", activity.GetTagItem("operation.stage"));
        Assert.Equal("ready", activity.GetTagItem("app.result.code"));
        Assert.Equal(204, activity.GetTagItem("http.response.status_code"));
        Assert.Equal("GET", activity.GetTagItem("http.request.method"));
        Assert.Equal(ActivityStatusCode.Unset, activity.Status);
        Assert.Empty(activity.Events);
        Assert.DoesNotContain("private-", JsonSerializer.Serialize(activity.TagObjects));
        Assert.DoesNotContain(context.Items, item => item.Value is Activity);
    }

    [Fact]
    public async Task Failure_is_rethrown_with_code_frames_but_without_incoming_context()
    {
        var recorded = new List<Activity>();
        using var listener = Listen(recorded);
        using var parent = new Activity("private-request").SetIdFormat(ActivityIdFormat.W3C).Start();
        parent.TraceStateString = "private=synthetic-content";
        parent.AddBaggage("email", "synthetic@example.invalid");
        var error = new InvalidOperationException("private-message", new Exception("private-inner"));
        error.Data["token"] = "private-token";
        var thrown = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            Invoke(new TestContext(), _ => throw error));

        Assert.Same(error, thrown);
        Assert.Same(parent, Activity.Current);
        var activity = Assert.Single(recorded);
        Assert.Null(activity.ParentId);
        Assert.NotEqual(parent.TraceId, activity.TraceId);
        Assert.True(string.IsNullOrEmpty(activity.TraceStateString));
        Assert.Empty(activity.Baggage);
        Assert.Equal(ActivityStatusCode.Error, activity.Status);
        Assert.Null(activity.StatusDescription);
        Assert.Equal("Health", activity.GetTagItem("function.name"));
        Assert.Equal("failure", activity.GetTagItem("operation.outcome"));
        Assert.Equal("function", activity.GetTagItem("operation.stage"));
        Assert.Equal(500, activity.GetTagItem("http.response.status_code"));
        var tags = Assert.Single(activity.Events).Tags.ToDictionary();
        Assert.Equal("System.InvalidOperationException", tags["exception.type"]);
        Assert.Contains(nameof(TelemetryTests), (string)tags["exception.stacktrace"]!);
        Assert.DoesNotContain("private-", JsonSerializer.Serialize(tags));
        Assert.DoesNotContain("/Users/", (string)tags["exception.stacktrace"]!);
    }

    [Fact]
    public async Task Failure_executing_http_result_keeps_response_stage()
    {
        var recorded = new List<Activity>();
        using var listener = Listen(recorded);
        var context = new TestContext();
        var middleware = new FunctionTelemetryMiddleware(new BackendTelemetry());
        await Assert.ThrowsAsync<InvalidOperationException>(() => middleware.Invoke(context, async current =>
        {
            // Mirror FunctionsHttpProxyingMiddleware's handler-then-result order.
            await new FunctionExecutionTelemetryMiddleware().Invoke(current, _ => Task.CompletedTask);
            await new ThrowingResult().ExecuteResultAsync(new ActionContext { HttpContext = context.Http });
        }));
        var activity = Assert.Single(recorded);
        Assert.Equal("failure", activity.GetTagItem("operation.outcome"));
        Assert.Equal("response", activity.GetTagItem("operation.stage"));
        Assert.Contains(nameof(ThrowingResult), (string)Assert.Single(activity.Events)
            .Tags.Single(tag => tag.Key == "exception.stacktrace").Value!);
    }

    [Fact]
    public async Task Expected_cancellation_has_completion_without_exception_but_unexpected_is_failure()
    {
        var recorded = new List<Activity>();
        using var listener = Listen(recorded);
        foreach (var context in new[] { new TestContext(new CancellationToken(true)), new TestContext() })
        {
            if (!context.CancellationToken.IsCancellationRequested)
                context.Http.RequestAborted = new CancellationToken(true);
            await Assert.ThrowsAsync<OperationCanceledException>(() =>
                Invoke(context, _ => throw new OperationCanceledException("private-cancel")));
        }
        await Assert.ThrowsAsync<OperationCanceledException>(() =>
            Invoke(new TestContext(), _ => throw new OperationCanceledException("private-cancel")));
        Assert.Equal(3, recorded.Count);
        foreach (var activity in recorded.Take(2))
        {
            Assert.Equal("cancelled", activity.GetTagItem("operation.outcome"));
            Assert.Null(activity.GetTagItem("http.response.status_code"));
            Assert.Empty(activity.Events);
            Assert.Equal(ActivityStatusCode.Unset, activity.Status);
        }
        Assert.Equal("failure", recorded[2].GetTagItem("operation.outcome"));
        Assert.Single(recorded[2].Events);
    }

    [Fact]
    public async Task Overlapping_and_nested_invocations_are_isolated_and_not_rate_dropped()
    {
        var recorded = new List<Activity>();
        using var listener = Listen(recorded);
        var started = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var entered = 0;
        await Task.WhenAll(Enumerable.Range(0, 32).Select(async index =>
            await Invoke(new TestContext(), async _ =>
            {
                var activity = Activity.Current!;
                activity.SetTag("app.batch.index", index);
                if (Interlocked.Increment(ref entered) == 32)
                    started.SetResult();
                await started.Task;
                await Invoke(new TestContext(), _ => Task.CompletedTask);
                Assert.Same(activity, Activity.Current);
                Assert.Equal(index, Activity.Current!.GetTagItem("app.batch.index"));
            })));
        Assert.Equal(64, recorded.Count);
        Assert.Equal(64, recorded.Select(activity => activity.TraceId).Distinct().Count());
        Assert.All(recorded, activity => Assert.Null(activity.ParentId));
        Assert.Equal(32, recorded.Count(activity => activity.GetTagItem("app.batch.index") is not null));
    }

    [Fact]
    public async Task Failures_are_not_dropped_by_the_old_per_minute_limit()
    {
        var recorded = new List<Activity>();
        using var listener = Listen(recorded);
        await Task.WhenAll(Enumerable.Range(0, 24).Select(_ =>
            Assert.ThrowsAsync<InvalidOperationException>(() =>
                Invoke(new TestContext(), _ => throw new InvalidOperationException("private-failure")))));
        Assert.Equal(24, recorded.Count);
        Assert.All(recorded, activity => Assert.Single(activity.Events));
    }

    [Fact]
    public async Task Returned_server_error_is_a_failed_completion_and_untrusted_method_is_bounded()
    {
        var recorded = new List<Activity>();
        using var listener = Listen(recorded);
        var context = new TestContext();
        context.Http.Request.Method = "private-method";
        await Invoke(context, _ =>
        {
            context.Http.Response.StatusCode = 503;
            return Task.CompletedTask;
        });
        var activity = Assert.Single(recorded);
        Assert.Equal(ActivityStatusCode.Error, activity.Status);
        Assert.Equal("failure", activity.GetTagItem("operation.outcome"));
        Assert.Equal(503, activity.GetTagItem("http.response.status_code"));
        Assert.Equal("_OTHER", activity.GetTagItem("http.request.method"));
        Assert.Empty(activity.Events);
    }

    [Fact]
    public async Task Pinned_exporter_preserves_wide_completions_and_code_frames_without_private_canaries()
    {
        using var resourceMetrics = new EnvironmentSetting("OTEL_DOTNET_AZURE_MONITOR_ENABLE_RESOURCE_METRICS", "false");
        using var statsbeat = new EnvironmentSetting("APPLICATIONINSIGHTS_STATSBEAT_DISABLED", "true");
        using var sdkStats = new EnvironmentSetting("APPLICATIONINSIGHTS_SDKSTATS_DISABLED", "true");
        using var handler = new CaptureHandler();
        using var client = new HttpClient(handler);
        using var exporter = new AzureMonitorTraceExporter(new AzureMonitorExporterOptions
        {
            ConnectionString = "InstrumentationKey=00000000-0000-0000-0000-000000000001",
            Transport = new HttpClientTransport(client),
            DisableOfflineStorage = true,
            SamplingRatio = 1,
            TracesPerSecond = null,
            EnableLiveMetrics = false,
            EnableStandardMetrics = false,
            EnablePerformanceCounters = false,
        });
        using var provider = Sdk.CreateTracerProviderBuilder()
            .SetResourceBuilder(ResourceBuilder.CreateEmpty().AddService(
                BackendTelemetry.ServiceName, serviceVersion: BackendTelemetry.BuildVersion))
            .AddSource(BackendTelemetry.SourceName)
            .SetSampler(new AlwaysOnSampler())
            .AddProcessor(new SimpleActivityExportProcessor(exporter)).Build();
        using var parent = new Activity("private-parent").Start();
        parent.AddBaggage("private-key", "private-baggage");
        parent.TraceStateString = "private=tracestate";
        var context = new TestContext();
        context.Http.Request.Path = "/private-path";
        context.Http.Request.QueryString = new QueryString("?token=private-query");
        context.Http.Request.Headers.Authorization = "Bearer private-token";
        await Invoke(context, _ =>
        {
            Activity.Current!.SetTag("app.result.code", "ready");
            return Task.CompletedTask;
        });
        var error = new InvalidOperationException("private-message", new Exception("private-inner"));
        error.Data["token"] = "private-data";
        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            Invoke(new TestContext(), _ => throw error));

        Assert.Equal(2, handler.Payloads.Count);
        var payload = string.Join("\n", handler.Payloads);
        Assert.Contains("ExceptionData", payload);
        Assert.Contains("RequestData", payload);
        Assert.Contains("System.InvalidOperationException", payload);
        Assert.Contains(nameof(TelemetryTests), payload);
        Assert.Contains(JsonEncodedText.Encode(BackendTelemetry.BuildVersion).ToString(), payload);
        Assert.Contains("bun-do-backend", payload);
        Assert.Contains("app.result.code", payload);
        Assert.Contains("ready", payload);
        Assert.Contains("operation.outcome", payload);
        Assert.Contains("success", payload);
        Assert.Contains("failure", payload);
        Assert.DoesNotContain(parent.TraceId.ToString(), payload);
        Assert.DoesNotContain("private-", payload);
        Assert.DoesNotContain("synthetic-invocation", payload);
        Assert.DoesNotContain("/Users/", payload);
        Assert.DoesNotContain("MetricData", payload);
        Assert.DoesNotContain("RemoteDependencyData", payload);
        Assert.DoesNotContain("MessageData", payload);
    }

    [Fact]
    public async Task Exception_frames_are_bounded()
    {
        var recorded = new List<Activity>();
        using var listener = Listen(recorded);
        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            Invoke(new TestContext(), _ => RecursiveFailure(30)));
        var stack = (string)Assert.Single(Assert.Single(recorded).Events)
            .Tags.Single(tag => tag.Key == "exception.stacktrace").Value!;
        Assert.True(stack.Length <= 2048);
        Assert.True(stack.Split('\n').Length <= 12);
        Assert.Contains(nameof(RecursiveFailure), stack);
    }

    private static Task RecursiveFailure(int depth)
    {
        if (depth == 0)
            throw new InvalidOperationException("private-recursion");
        return RecursiveFailure(depth - 1);
    }

    [Fact]
    public async Task Released_http_context_cannot_replace_success_failure_or_cancellation()
    {
        var recorded = new List<Activity>();
        using var listener = Listen(recorded);
        using var parent = new Activity("parent").Start();
        foreach (var outcome in new[] { "success", "failure", "cancelled" })
        {
            var context = new TestContext(outcome == "cancelled" ? new CancellationToken(true) : default);
            var error = outcome == "cancelled"
                ? new OperationCanceledException("private-cancel") : new Exception("private-failure");
            async Task Invocation() => await new FunctionTelemetryMiddleware(new BackendTelemetry())
                .Invoke(context, async current =>
                {
                    await new FunctionExecutionTelemetryMiddleware().Invoke(current, _ => Task.CompletedTask);
                    context.Http.Uninitialize();
                    if (outcome != "success") throw error;
                });
            if (outcome == "success") await Invocation();
            else Assert.Same(error, await Assert.ThrowsAnyAsync<Exception>(Invocation));
            Assert.Same(parent, Activity.Current);
            Assert.DoesNotContain(context.Items, item => item.Value is Activity);
            Assert.Single(context.Items);
            Assert.Equal(outcome, recorded[^1].GetTagItem("operation.outcome"));
            Assert.Equal("GET", recorded[^1].GetTagItem("http.request.method"));
            if (outcome == "failure") Assert.Equal(500, recorded[^1].GetTagItem("http.response.status_code"));
            else Assert.Null(recorded[^1].GetTagItem("http.response.status_code"));
        }
    }

    private static Task Invoke(TestContext context, FunctionExecutionDelegate next) =>
        new FunctionTelemetryMiddleware(new BackendTelemetry()).Invoke(context, async current =>
        {
            await new FunctionExecutionTelemetryMiddleware().Invoke(current, next);
            await ((TestResponseFeature)context.Http.Features.Get<IHttpResponseFeature>()!).Start();
        });

    private sealed class ThrowingResult : IActionResult
    {
        public Task ExecuteResultAsync(ActionContext context) => throw new InvalidOperationException("private-result");
    }

    private static ActivityListener Listen(List<Activity> recorded)
    {
        var listener = new ActivityListener
        {
            ShouldListenTo = source => source.Name == BackendTelemetry.SourceName,
            Sample = (ref ActivityCreationOptions<ActivityContext> _) => ActivitySamplingResult.AllDataAndRecorded,
            ActivityStopped = activity => { lock (recorded) recorded.Add(activity); },
        };
        ActivitySource.AddActivityListener(listener);
        return listener;
    }

    private sealed class EnvironmentSetting(string name, string value) : IDisposable
    {
        private readonly string? previous = Set(name, value);
        private static string? Set(string name, string value)
        {
            var previous = Environment.GetEnvironmentVariable(name);
            Environment.SetEnvironmentVariable(name, value);
            return previous;
        }
        public void Dispose() => Environment.SetEnvironmentVariable(name, previous);
    }

    private sealed class CaptureHandler : HttpMessageHandler
    {
        public List<string> Payloads { get; } = [];
        protected override HttpResponseMessage Send(HttpRequestMessage request, CancellationToken cancellationToken) =>
            SendAsync(request, cancellationToken).GetAwaiter().GetResult();

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var stream = await request.Content!.ReadAsStreamAsync(cancellationToken);
            if (request.Content.Headers.ContentEncoding.Contains("gzip"))
                stream = new GZipStream(stream, CompressionMode.Decompress);
            using var reader = new StreamReader(stream);
            Payloads.Add(await reader.ReadToEndAsync(cancellationToken));
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent("""{"itemsReceived":2,"itemsAccepted":2,"errors":[]}"""),
            };
        }
    }

    // Use the pinned ASP.NET integration key to model its attached HttpContext.
    private sealed class TestContext(CancellationToken cancellation = default) : FunctionContext
    {
        public DefaultHttpContext Http => (DefaultHttpContext)Items["HttpRequestContext"];
        public override string InvocationId => "synthetic-invocation";
        public override string FunctionId => "health";
        public override FunctionDefinition FunctionDefinition { get; } = new TestDefinition();
        public override CancellationToken CancellationToken => cancellation;
        public override TraceContext TraceContext => throw new NotSupportedException();
        public override BindingContext BindingContext => throw new NotSupportedException();
        public override RetryContext RetryContext => throw new NotSupportedException();
        public override IServiceProvider InstanceServices { get; set; } = null!;
        public override IDictionary<object, object> Items { get; set; } = new Dictionary<object, object>
        {
            ["HttpRequestContext"] = CreateHttpContext(),
        };
        private static DefaultHttpContext CreateHttpContext()
        {
            var http = new DefaultHttpContext();
            http.Features.Set<IHttpResponseFeature>(new TestResponseFeature());
            http.Request.Method = "GET";
            return http;
        }
        public override IInvocationFeatures Features => throw new NotSupportedException();
    }

    private sealed class TestResponseFeature : HttpResponseFeature
    {
        private readonly List<(Func<object, Task> Callback, object State)> starting = [];
        public override void OnStarting(Func<object, Task> callback, object state) => starting.Add((callback, state));
        public async Task Start()
        {
            foreach (var item in starting.AsEnumerable().Reverse()) await item.Callback(item.State);
        }
    }

    private sealed class TestBinding : BindingMetadata
    {
        public override string Name => "request";
        public override string Type => "httpTrigger";
        public override BindingDirection Direction => BindingDirection.In;
    }

    private sealed class TestDefinition : FunctionDefinition
    {
        public override string Name => "Health";
        public override string Id => "health";
        public override string PathToAssembly => "";
        public override string EntryPoint => "";
        public override ImmutableArray<FunctionParameter> Parameters => [];
        public override IImmutableDictionary<string, BindingMetadata> InputBindings =>
            ImmutableDictionary<string, BindingMetadata>.Empty.Add("request", new TestBinding());
        public override IImmutableDictionary<string, BindingMetadata> OutputBindings =>
            ImmutableDictionary<string, BindingMetadata>.Empty;
    }
}
