# Bun Do

Bun Do is a native Android household task queue. Capture work by typing or speaking, share it, and finish it together. Everyday task edits work offline and synchronize when connected. Microsoft sign-in, account-isolated storage and recovery protect retained work.

The [implementation plan](docs/implementation-plan.md) links the live release gates. [Architecture contracts](docs/architecture/README.md) define behavior. See [Android development](docs/android-development.md), [development sign-in](docs/identity-development.md) and [Azure development](docs/azure-development.md) for setup. The separate Local build supplies fictional Alice and Bob accounts for unattended development; it is not the installable cloud release.

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
commands, costs and remaining live checks. Release verification must still exercise the installed Android artifact against the configured services.
