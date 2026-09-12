# Aspire for local whole-stack development

Research and setup record, September 12, 2026.

This file records the initial setup. The subsequent AppHost run succeeded.
Core Tools was updated to 4.14.0, and the owner's Podman 6.1.1 VM runs
Azurite directly. See [local development](../local-development.md) for
current commands and verified behavior. No Podman workaround remains.

## What changed on this Mac

Installed the official `Aspire.Cli` .NET global tool at version
`13.5.3+b5f143315ffb6968ea939a9978797a5b20e4c688`. NuGet's latest stable
package was `13.5.3`, matching the latest `dotnet/aspire` GitHub release,
`v13.5.3`, published August 25, 2026.

`~/.dotnet/tools` was already on `PATH`. This setup did not modify shell
profiles or `PATH`.

The documented Homebrew command is `brew install --cask
microsoft/aspire/aspire`. Homebrew 6.0.22 rejected the current official tap
as invalid syntax before installation. The .NET global-tool command is also
an official installation path, uses the already installed .NET 10 SDK, and
avoids a second runtime manager.

Installed project-local Aspire agent guidance with:

```bash
aspire agent init \
  --workspace-root . \
  --skill-locations standard \
  --skills aspire,aspire-init,aspire-orchestration,aspire-monitoring,aspire-deployment,aspireify \
  --non-interactive
```

This created six skill directories under `.agents/skills/`, including
`aspireify`. The top-level `aspire` skill routes existing-codebase AppHost
wiring to `aspireify`, so leaving it out would leave the installed router with
a broken handoff. Installing the guidance does not create an AppHost.

The CLI did not add or alter `.codex` configuration, so the existing hooks
remain unchanged. Companion tools were omitted.

The initial disposable inspection command reported that the CLI also placed
the selected standard skills in `~/.agents/skills`. The project invocation
only reported `.agents/skills`.

Sources:

- [Install Aspire CLI](https://aspire.dev/get-started/install-cli/)
- [Aspire skills](https://aspire.dev/get-started/aspire-skills/)
- [`aspire agent init` reference](https://aspire.dev/reference/cli/commands/aspire-agent-init/)
- [Aspire 13.5.3 release](https://github.com/dotnet/aspire/releases/tag/v13.5.3)

## Local runtime facts

This Mac is Apple silicon (`arm64`) and has .NET SDK `10.0.400`. It has Azure
Functions Core Tools `4.11.0` at `/opt/homebrew/bin/func` and Podman `6.1.0`
at `/opt/homebrew/bin/podman`. `podman machine list` reports the configured
`podman-machine-default` libkrun VM. `podman system connection list` reports
its root connection as the default. This record does not claim that the
machine is currently running or that containers can start.

Aspire documents Docker Desktop as its recommended local runtime and Podman
as a supported alternative. Set `ASPIRE_CONTAINER_RUNTIME=podman` before
starting an AppHost that runs container resources. Azurite and Cosmos
emulators need that container runtime. Process-only resources do not.

For a .NET isolated Azure Functions project, Aspire uses
`AddAzureFunctionsProject`, not `AddProject`. The Functions integration
requires Azure Functions Core Tools for local execution. It supports .NET 8
or later Functions projects and requires the isolated worker model. AppHost
injected settings override matching `local.settings.json` settings, so the
Function can still run independently with `func start`.

The default Functions host-storage connection uses an Azure Storage emulator
for local runs. It provisions a storage account only when the application is
deployed. Do not rely on that default for this repository until deployment
ownership is decided.

Sources:

- [Aspire prerequisites](https://aspire.dev/get-started/prerequisites/)
- [Azure Functions with Aspire](https://learn.microsoft.com/en-us/azure/azure-functions/aspire-integration)

## Azurite

Use Azurite for local Function host storage, Blob, Queue, or Table binding
development. It is cross-platform and implements Blob, Queue, and Table
storage only. Azure Files and Data Lake Storage Gen2 are absent. Its Table
support remains preview, and it has no performance guarantee, so it is not a
cloud-equivalence test.

When an AppHost explicitly uses
`AddAzureStorage(...).RunAsEmulator()`, Aspire starts an Azurite container.
It needs no Azure subscription or credentials. Aspire assigns dynamic host
ports by default. Leave them dynamic unless an independently run process
needs stable endpoints. Start without a persisted volume for ordinary tests
so stale queue and table data cannot hide failures.

Sources:

- [Azurite limitations](https://learn.microsoft.com/en-us/azure/storage/common/storage-use-azurite)
- [Aspire Azure Storage emulator setup](https://aspire.dev/integrations/cloud/azure/azure-storage-tables/azure-storage-tables-host/)

## Cosmos DB on Apple silicon

The existing Linux-based Cosmos DB emulator does not support Apple silicon or
Microsoft ARM developer machines. Do not make
`RunAsEmulator()` a required local path on this Mac without first proving the
container runs acceptably under its chosen runtime.

Microsoft's vNext Linux emulator supports a wide range of processors and
operating systems, but Aspire exposes it as `RunAsPreviewEmulator()` and
requires an explicit diagnostic suppression. It only supports the NoSQL API
in gateway mode and has feature gaps. Use it for a focused local
repository/transaction smoke test after Docker Desktop is installed. Keep
tests that need cloud throughput, indexing, identity, or production
consistency behavior behind a separately approved Azure integration gate.

Sources:

- [Cosmos DB emulator limits](https://learn.microsoft.com/en-us/azure/cosmos-db/emulator)
- [vNext Linux Cosmos DB emulator](https://learn.microsoft.com/en-us/azure/cosmos-db/emulator-linux)
- [Aspire Cosmos DB emulator setup](https://aspire.dev/integrations/cloud/azure/azure-cosmos-db/azure-cosmos-db-host/)

## Deployment boundary

Aspire is a local composition and diagnostics tool here. It does not replace
the repository's Bicep-owned Azure deployment. In particular,
`AddAzureStorage` and `AddAzureCosmosDB` implicitly enable Azure
provisioning support. Calling either method without an emulator path can
require a subscription and location. Do not add credentials, `azd` state,
Azure provisioning configuration, or startup-time resource creation to local
development.

When an AppHost is added, every local Azure resource must explicitly select
an emulator or a local substitute. Bicep remains the only planned creator of
the dedicated Azure resource group and cloud resources.

## Recommended next build slice

This setup did not start or change the Podman machine. In the next slice, run
`podman info`, then a disposable ARM64 container pull and run. If that works,
set `ASPIRE_CONTAINER_RUNTIME=podman` for the development shell.

Then add a small .NET isolated `BunDo.Functions` HTTP health endpoint and a
`BunDo.AppHost`. Use `AddAzureFunctionsProject` and the default local
Functions host-storage emulator only. Verify that `aspire start`, `aspire
wait`, `aspire describe`, and the dashboard show the Function and Azurite.
Do not add Cosmos, cloud credentials, Bicep changes, or business commands in
that slice.
