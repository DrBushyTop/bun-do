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

The selected local host is .NET Aspire. Aspire CLI 13.5.3 and six project-local workflow skills are installed. The AppHost has not been implemented yet. See the [setup and runtime record](docs/research/aspire-local-development.md) and the local-orchestration slice in the [build plan](docs/implementation-plan.md). Bicep remains responsible for Azure resource deployment; local startup must not create cloud resources.
