using System.Text.Json;
using BunDo.Functions;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace BunDo.Hosting.Tests;

public sealed class HealthFunctionTests
{
    [Fact]
    public void Health_returns_an_ok_status_without_disclosing_configuration()
    {
        var request = new DefaultHttpContext().Request;
        request.Method = "GET";

        var result = new HealthFunction().Run(request);

        var ok = Assert.IsType<OkObjectResult>(result);
        Assert.Equal(StatusCodes.Status200OK, ok.StatusCode);
        Assert.Equal("{\"status\":\"ok\"}", JsonSerializer.Serialize(ok.Value));
    }
}
