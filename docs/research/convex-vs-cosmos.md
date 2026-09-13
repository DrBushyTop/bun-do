# Convex versus Cosmos DB

Reviewed September 12, 2026. This note records the decision rationale, not a
migration plan.

## Decision

Stay with Cosmos DB. The owner reported that Convex's free plan does not cover
European deployments while Cosmos DB is available to them without cost. Those
are owner-supplied billing inputs, not generally verified pricing guarantees.

The owner cancelled the local Convex proof before implementation. No proof
files, containers, volumes, or cloud resources were created. Do not reopen the
comparison unless a future migration has an identified benefit and an approved
proof plan.

Any replacement must preserve Bun Do's offline-first Android model, atomic
workspace effects and receipts, ordered replay, recovery semantics, and backup
requirements. The Cosmos storage decision owns that requirement:
[ADR 0001](../adr/0001-cosmos-with-replaceable-storage.md).
