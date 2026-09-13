# Bun Do

Bun Do is implementing the shared typed command model for task creation and editing. The model keeps command validation, deterministic state changes, and sync behavior testable without Android UI, network calls, or Azure services.

The implementation follows the repository's [implementation plan](docs/implementation-plan.md) and the contracts in [docs/architecture](docs/architecture/README.md). Android supports offline typed and voice capture, Microsoft sign-in, and encrypted account-local storage. A separate Local build supplies Alice and Bob for unattended development. Household membership, cloud sync and production readiness remain separate gates. See [Android development](docs/android-development.md) and [development sign-in](docs/identity-development.md) for tests and limits.

## Local verification

The repository pins the .NET SDK in `global.json`. Restore the locked package graph, then build and test in Release mode:

```sh
dotnet restore BunDo.sln --locked-mode
dotnet build BunDo.sln --configuration Release --no-restore
dotnet test BunDo.sln --configuration Release --no-build --no-restore
```

## Local stack

Aspire CLI 13.5.3 starts a .NET 10 Functions health endpoint and Azurite through Podman. Local readiness, HTTP health, loopback-only listeners and request telemetry have been verified. Azure Functions Core Tools 4.14.0 or later is required. See [local development](docs/local-development.md) for startup and stop commands and the [build plan](docs/implementation-plan.md) for remaining slices. Bicep owns Azure deployment; local startup does not create cloud resources.

## Azure development

The dedicated development Cosmos store, private snapshot storage, .NET 10 Flex
health backend, full OpenTelemetry completion tracing and Luna deployment are
deployed in Sweden Central. Live checks verified correlated redacted failures,
useful request context and all three complete AI schemas under managed identity.
Controlled redeployments preserved resource identities and checked policies.
See [Azure development](docs/azure-development.md) for the scoped deployment
commands, costs and remaining live checks. This does not yet provide an
authenticated cloud backend or task sync.
