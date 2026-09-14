using System.Text.Json;
using BunDo.Functions.AI;

namespace BunDo.Hosting.Tests;

public sealed class CleanupProviderTests
{
    private static byte[] Response(object content, string status = "completed") => JsonSerializer.SerializeToUtf8Bytes(new {
        status, output = new[] { new { type = "message", content = new[] { content } } },
    });
    [Theory]
    [InlineData("Osta maitoa", "fi")][InlineData("Buy milk", "en")][InlineData("Osta oat milk", "mixed")]
    public void Validates_structured_multilingual_text(string title, string language)
    {
        var result = FoundryCleanupProvider.ParseResponse(Response(new { type = "output_text",
            text = JsonSerializer.Serialize(new { title, description = (string?)null, language, needsReview = false }) }));
        Assert.Equal(title, result.Title); Assert.Equal(language, result.Language);
    }
    [Theory]
    [InlineData("{}")][InlineData("{\"title\":\"x\",\"description\":null,\"language\":\"de\",\"needsReview\":false}")]
    [InlineData("{\"title\":\"x\",\"title\":\"y\",\"description\":null,\"language\":\"en\",\"needsReview\":false}")]
    [InlineData("{\"title\":\"\",\"description\":null,\"language\":\"en\",\"needsReview\":false}")]
    public void Rejects_malformed_or_semantically_invalid_output(string text) =>
        Assert.Equal("INVALID_OUTPUT", Assert.Throws<CleanupProviderException>(() =>
            FoundryCleanupProvider.ParseResponse(Response(new { type = "output_text", text }))).Code);
    [Fact]
    public void Refusal_and_incomplete_responses_do_not_become_task_text()
    {
        Assert.Equal("REFUSED", Assert.Throws<CleanupProviderException>(() =>
            FoundryCleanupProvider.ParseResponse(Response(new { type = "refusal", refusal = "private" }))).Code);
        Assert.Equal("INCOMPLETE_OUTPUT", Assert.Throws<CleanupProviderException>(() =>
            FoundryCleanupProvider.ParseResponse(Response(new { }, "incomplete"))).Code);
    }
}
