# Local backend development

Bun Do uses Aspire only for local backend composition and diagnostics. Bicep
owns Azure deployment. Local startup must not create cloud resources or expose
the backend on the LAN.

## Start and inspect

Use the .NET SDK, Aspire CLI, and Functions Core Tools versions selected by
`global.json`, project configuration, and CI, plus a working Podman VM. Inspect the existing VM before use. Do not reset
or reconfigure it for this repository.

```sh
podman machine list
podman system connection list
podman info

export ASPIRE_CONTAINER_RUNTIME=podman
export ASPIRE_ENVIRONMENT=Local
aspire start --apphost src/BunDo.AppHost/BunDo.AppHost.csproj --non-interactive
aspire wait functions --non-interactive
aspire describe --non-interactive
```

Use `aspire describe` or `aspire ps` to find the Function endpoint, then request
`/api/health` after the resource is ready:

```sh
curl http://127.0.0.1:<functions-port>/api/health
aspire otel logs functions --non-interactive
aspire otel traces functions --non-interactive
```

The dashboard URL, `aspire describe`, and OpenTelemetry output can contain
credentials. Keep raw output private.

Verify the actual listener after AppHost, Core Tools, or launch-profile changes:

```sh
lsof -nP -iTCP:7275 -sTCP:LISTEN
podman ps --filter name=host-storage --format '{{.Names}} {{.Ports}}'
```

The Function must bind to `127.0.0.1`, and published container ports must use
host loopback. Stop Aspire before rebuilding the solution or changing the
AppHost, and when finished:

```sh
aspire stop --non-interactive
```

## Identity modes

Ordinary startup does not contact Microsoft. Use Microsoft mode only for an
opt-in live sign-in check. Use `BunDoIdentity__Mode=Local` only for the
unattended Alice/Bob development path described in
[development sign-in](identity-development.md). Local identity is not a cloud
substitute and must never be deployed.

## Test boundaries

The local graph proves host startup, local Functions behavior, host storage, and
telemetry plumbing. It does not prove Azure Storage, Cosmos transactions, real
Microsoft sign-in, Foundry, deployment, or production networking. Those remain
separate slice gates.

## Android emulator access

Android emulators reach host loopback at `10.0.2.2`, not `localhost`:

```text
http://10.0.2.2:<functions-port>/api/health
```

Confirm the loopback listener before using this route. A physical device needs
an explicitly approved development route. Do not expose the backend on the LAN
as a shortcut.
