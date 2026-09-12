# Local hosting review

Reviewed September 12, 2026 against "Run the backend locally with Aspire and explicit emulator profiles". Baseline commit: `ad336f4`.

A fresh Terra subagent reviewed the slice at medium reasoning. It checked the implementation against the issue and local/cloud boundary.

## Findings and fixes

- The original documentation claimed loopback-only access without checking sockets. Removed `WithExternalHttpEndpoints` and tested the listeners. Core Tools 4.11.0 still listened on `*:7275`. Upgraded Core Tools to the released 4.14.0 and added `--address 127.0.0.1` to the Function launch arguments. The same socket check then showed only `127.0.0.1:7275`.
- Health coverage was missing. Added a focused handler test and an Aspire HTTP health check. Then verified readiness and the route through the running Functions host.
- The first run had no runtime evidence while Podman was unavailable. After the owner started the VM, Aspire launched Azurite directly. No replacement VM, process-only workaround or cloud fallback remains.
- The documentation blurred future Cosmos tests with implemented profiles. It now distinguishes the one AppHost profile, in-memory tests and future opt-in cloud gates.
- The AppHost could have become a second deployment path. It now rejects publish mode. Bicep remains authoritative for cloud resources.

The reviewer also reported generated Functions metadata warnings during a build. A separate clean, sequential restore/build produced zero warnings and errors. Concurrent builds against generated WorkerExtensions may have caused the earlier warnings; that cause has not been proved. Verification runs do not share a build with another agent.

The reviewer checked the final fixes and returned approval with no new material defect. No concrete bug was deferred. Locked restore, Release build, all 45 tests, formatter verification and whitespace checks passed after stopping Aspire.

## Runtime evidence

- Aspire CLI 13.5.3, .NET SDK 10.0.400, Core Tools 4.14.0, Podman 6.1.1, ARM64 Linux VM.
- `aspire start` launched the AppHost with `ASPIRE_CONTAINER_RUNTIME=podman` and `ASPIRE_ENVIRONMENT=Local`.
- `aspire wait functions` passed. Both `functions` and `host-storage` reported `Running` and `Healthy`.
- `GET http://127.0.0.1:7275/api/health` returned HTTP 200 and exactly `{"status":"ok"}`.
- `aspire otel traces functions` contained successful `GET api/health` host and worker spans. Structured request logs contained the health route and HTTP status.
- `lsof` confirmed loopback listeners for Functions, dashboard, OTLP, resource service and the Azurite proxies. `podman ps` confirmed Azurite 3.35.0's three published ports use `127.0.0.1`.
- Raw Aspire environment and telemetry output stays outside git. Dashboard login tokens and OTLP keys are not evidence to publish.
- Repository hygiene follow-up moved local settings to an ignored personal file plus a checked-in emulator example. Aspire readiness and loopback HTTP health passed again with no `local.settings.json` present.

## Boundaries

The health-handler unit test does not prove HTTP hosting. The separate live checks above do. Neither proves Cosmos Strong consistency, cloud storage, Entra sign-in, managed identity or Foundry inference.

Android's `10.0.2.2` route is documented but not yet exercised. Cosmos preview is not implemented. These are planned dependent slices, not passed gates.
