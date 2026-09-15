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
            text = JsonSerializer.Serialize(new { title, description = (string?)null, language, needsReview = false, due = (object?)null }) }));
        Assert.Equal(title, result.Title); Assert.Equal(language, result.Language);
    }
    [Theory]
    [InlineData("{}")][InlineData("{\"title\":\"x\",\"description\":null,\"language\":\"de\",\"needsReview\":false}")]
    [InlineData("{\"title\":\"x\",\"title\":\"y\",\"description\":null,\"language\":\"en\",\"needsReview\":false}")]
    [InlineData("{\"title\":\"\",\"description\":null,\"language\":\"en\",\"needsReview\":false}")]
    public void Rejects_malformed_or_semantically_invalid_output(string text) =>
        Assert.Equal("INVALID_OUTPUT", Assert.Throws<CleanupProviderException>(() =>
            FoundryCleanupProvider.ParseResponse(Response(new { type = "output_text", text }))).Code);
    [Theory]
    [InlineData("2026-03-29", "03:30", "2026-03-29T01:30:00Z")]
    [InlineData("2026-10-25", "03:30", "2026-10-25T00:30:00Z")]
    public void Explicit_deadline_uses_the_same_nominal_DST_policy(string date, string time, string expected)
    {
        var result = FoundryCleanupProvider.ParseResponse(Response(new { type = "output_text",
            text = JsonSerializer.Serialize(new { title = "Milk", description = (string?)null, language = "en", needsReview = false,
                due = new { kind = "DATE_TIME", localDate = date, localTime = time, zoneId = "Europe/Helsinki" } }) }));
        Assert.Equal(DateTimeOffset.Parse(expected), result.Due!.Instant);
        Assert.Equal(time, result.Due.LocalTime);
    }

    [Theory]
    [InlineData("Pese astiat", "fi")][InlineData("Wash dishes", "en")][InlineData("Pese dinner dishes", "mixed")]
    public void Split_output_is_a_validated_list_not_a_task_mutation(string title, string language)
    {
        var proposal = FoundryCleanupProvider.ParseSplitResponse(Response(new { type = "output_text",
            text = JsonSerializer.Serialize(new { items = new[] { title }, language }) }));
        Assert.Equal(new[] { title }, proposal.Items); Assert.True(proposal.NeedsReview);
    }
    [Theory]
    [InlineData("[]")][InlineData("[\"Wash\",\"wash\"]")][InlineData("[\" padded \"]")]
    [InlineData("[null]")][InlineData("[\"line\\nbreak\"]")]
    public void Invalid_split_lists_are_rejected(string items) =>
        Assert.Equal("INVALID_OUTPUT", Assert.Throws<CleanupProviderException>(() => FoundryCleanupProvider.ParseSplitResponse(
            Response(new { type = "output_text", text = "{\"items\":" + items + ",\"language\":\"en\"}" }))).Code);

    [Fact]
    public void Refusal_and_incomplete_responses_do_not_become_task_text()
    {
        Assert.Equal("REFUSED", Assert.Throws<CleanupProviderException>(() =>
            FoundryCleanupProvider.ParseResponse(Response(new { type = "refusal", refusal = "private" }))).Code);
        Assert.Equal("INCOMPLETE_OUTPUT", Assert.Throws<CleanupProviderException>(() =>
            FoundryCleanupProvider.ParseResponse(Response(new { }, "incomplete"))).Code);
    }
}
