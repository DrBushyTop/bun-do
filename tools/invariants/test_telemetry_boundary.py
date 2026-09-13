"""Preserve the existing application middleware guard, independent of Azure configuration."""

from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class TelemetryBoundaryTests(unittest.TestCase):
    def test_exception_boundary_wraps_http_result_execution(self):
        # Moved from the removed backend template suite. This protects application
        # exception handling, not a Bicep setting. Behavioral tests also live in
        # BunDo.Hosting.Tests/TelemetryTests.cs.
        program = (ROOT / "src/BunDo.Functions/Program.cs").read_text()
        self.assertLess(
            program.index("builder.UseMiddleware<FunctionTelemetryMiddleware>()"),
            program.index("builder.ConfigureFunctionsWebApplication()"),
        )
        self.assertLess(
            program.index("builder.ConfigureFunctionsWebApplication()"),
            program.index("builder.UseMiddleware<FunctionExecutionTelemetryMiddleware>()"),
        )
        self.assertIn("builder.Logging.ClearProviders()", program)


if __name__ == "__main__":
    unittest.main()
