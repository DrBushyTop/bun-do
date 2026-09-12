# Local backend development

Bun Do starts its backend through Aspire. Do not run the AppHost with `dotnet run`.

## What runs locally

The `container-azurite` profile starts:

- `functions`, a .NET 10 isolated Azure Functions host.
- `host-storage`, an Azurite container used only for Functions host storage.

The AppHost always calls `RunAsEmulator()` for storage and passes that resource through `WithHostStorage`. It has no Azure subscription, credential, Bicep, Cosmos, Entra, or Foundry resource in its local graph. Bicep remains the deployment path. The AppHost rejects publish mode rather than generating a second cloud deployment.

The only HTTP endpoint today is `GET /api/health`. It returns `{"status":"ok"}`. It is a host check, not a business API.

## Start and inspect

Use .NET SDK 10.0.400, Aspire CLI 13.5.3, Azure Functions Core Tools 4.14.0 or later, and a running Podman VM. Core Tools 4.11.0 binds to every network interface. Version 4.14.0 supports the explicit `--address 127.0.0.1` argument in our Function launch profile.

The existing Podman VM is working. Inspect it before starting Aspire; do not replace, reset or reconfigure it. There is no process-only Azurite workaround or cloud fallback.

Aspire injects the Function's local settings. It does not require a `local.settings.json` file. The checked-in `local.settings.example.json` contains emulator defaults only. Personal `local.settings.json` files are ignored; never put cloud credentials in the example.

```bash
podman machine list
podman system connection list
podman info

export ASPIRE_CONTAINER_RUNTIME=podman
export ASPIRE_ENVIRONMENT=Local
aspire start --apphost src/BunDo.AppHost/BunDo.AppHost.csproj --non-interactive
aspire wait functions --non-interactive
aspire describe --non-interactive
```

`aspire start` prints the dashboard and resource endpoints. Use its dashboard login URL locally, but do not copy its token into tickets or committed files. `aspire describe` can also expose environment credentials and OTLP keys. Keep raw output private.

Use `aspire describe` or `aspire ps` to get the `functions` HTTP endpoint, then request `/api/health` once the resource is ready. The checked-in Function launch profile uses port 7275.

```bash
curl http://127.0.0.1:<functions-port>/api/health
aspire otel logs functions --non-interactive
aspire otel traces functions --non-interactive
```

The Function, dashboard, OTLP receiver and resource service bind to loopback. Azurite listens on its container interfaces, but Podman publishes those ports on host loopback only. The local graph does not mark the Function endpoint external. Verify the actual listener after tool or profile changes rather than trusting the displayed URL:

```bash
lsof -nP -iTCP:7275 -sTCP:LISTEN
podman ps --filter name=host-storage --format '{{.Names}} {{.Ports}}'
```

The Function listener must show `127.0.0.1:7275`, not `*:7275`. Published container ports must use `127.0.0.1`. Before rebuilding the solution or changing the AppHost, stop it through Aspire. Stop it when finished too:

```bash
aspire stop --non-interactive
```

## Verified local run

On September 12, 2026, Aspire started the Function and Azurite 3.35.0 through the existing ARM64 Podman VM. Both resources became healthy. `GET /api/health` returned HTTP 200 with `{"status":"ok"}`. Aspire received the request's host and worker spans and structured request logs. Socket inspection confirmed loopback-only host listeners and Podman port publication.

The first socket check failed with Core Tools 4.11.0. Updating that tool to 4.14.0 and supplying the explicit bind address fixed it. The Podman VM needed no changes.

The domain suite and a focused health-handler test also run without containers. That handler test checks the response value, not HTTP hosting. The running-host verification above is separate from those tests.

## Local profile and other test boundaries

| profile | command | purpose | does not prove |
| --- | --- | --- | --- |
| `container-azurite` | `ASPIRE_ENVIRONMENT=Local aspire start ...` | Functions health endpoint plus explicit Azurite host storage | Azure Storage behavior, Cosmos transactions, identity, or deployment |
| in-memory tests, not an AppHost profile | `dotnet test BunDo.sln --configuration Release` | deterministic domain and protocol model tests without containers | HTTP hosting or Azure adapters |
| Cosmos preview, not implemented | not available yet | future opt-in adapter smoke only, after the Cosmos slice proves the selected ARM64 runtime | Strong-consistency cloud behavior, throughput, indexing, or recovery |
| cloud gates | not available through this AppHost | future Bicep-managed deployment checks | local emulator output |

Do not add a cloud fallback to the local profile. The future Cosmos preview profile must remain separate from `container-azurite`, and must never become evidence for the real Strong-consistency contract. Entra External ID and Foundry have no local emulators in this repository. Their tests stay opt-in cloud gates when those slices exist.

## Android emulator access

Android emulators reach a host-loopback service at `10.0.2.2`, not `localhost`. After Aspire reports the Functions port, configure the Android development client with:

```text
http://10.0.2.2:<functions-port>/api/health
```

Use this route after confirming the host endpoint's loopback binding. Android-emulator connectivity has not yet been exercised. A physical device needs its own explicitly approved development route. Do not expose the local backend on the LAN as a shortcut.
