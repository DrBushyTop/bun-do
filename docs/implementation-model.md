# Typed-command model

The first executable slice tests create, title/description edit and rejected-dependent disposition. It is a reference model for the later Android and Cosmos adapters, not a running app.

## Interfaces under test

`WorkspaceServer.Handle` validates a supplied authenticated member against seeded device ownership, evaluates the command and commits through `IWorkspaceStore.TryCommit`. Repeated compare-and-swap failure returns busy after five attempts. `Pull` exposes immutable revision groups for the scoped model workspace. It is an internal model read, not an unauthenticated HTTP endpoint.

`LocalReplica` captures local typed intent, freezes one submission at a time, records receipts, applies contiguous complete changes, and replays pending titles over the canonical base. It retains rejected text as recovery variants. The model is single-threaded and in memory; Room transactions, process recovery and worker fencing belong to the Android/sync slices.

The store adapter shares immutable state between server instances and commits atomically under a lock. Tests use a fault-injecting adapter at that store interface, not mocks of domain internals.

## Deliberate limits

This slice does not implement HTTP/JSON parsing, JWTs, membership changes, device expiry, disk durability, receipt pruning, snapshots, AI, recurrence, lifecycle, graphs or full workspace admission limits. The frozen operation fingerprint is calculated over the model serializer's fixed envelope; the production transport must hash exact received bytes and enforce its own versioned schema.

`TaskSnapshot` currently models title and description only. It is not the complete wire DTO. IDs use literal UUIDv5 fixtures independently generated with Python. Unsupported commands fail explicitly rather than masquerading as a completed feature.

## Test evidence

Behavior tests cover response loss, immutable retries, altered envelopes, device/member/epoch mismatch, sequence gaps, terminal rejections, field conflicts and atomic multi-field edits, byte/text validation, paginated empty revisions, compare-and-swap contention, two reconnect orders, an edit made during create response loss, malformed pages and preserved rejected-dependent text.

Run the locked Release commands in the repository README. Fresh adversarial review and its fixes are recorded in the slice's GitHub discussion.
