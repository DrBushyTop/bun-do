# Bun Do

Bun Do is implementing the shared typed command model for task creation and editing. The model keeps command validation, deterministic state changes, and sync behavior testable without Android UI, network calls, or Azure services.

The implementation follows the repository's [implementation plan](docs/implementation-plan.md) and the contracts in [docs/architecture](docs/architecture/README.md). This phase covers the model and its tests only. Android screens, cloud deployment, and production readiness are separate work.

## Local verification

The repository pins the .NET SDK in `global.json`. Restore the locked package graph, then build and test in Release mode:

```sh
dotnet restore BunDo.sln --locked-mode
dotnet build BunDo.sln --configuration Release --no-restore
dotnet test BunDo.sln --configuration Release --no-build --no-restore
```

## Local stack

Aspire CLI 13.5.3 starts a .NET 10 Functions health endpoint and Azurite through Podman. Local readiness, HTTP health, loopback-only listeners and request telemetry have been verified. Azure Functions Core Tools 4.14.0 or later is required. See [local development](docs/local-development.md) for startup and stop commands and the [build plan](docs/implementation-plan.md) for remaining slices. Bicep owns Azure deployment; local startup does not create cloud resources.
