# Typed-command model

The first executable slice proves the command and replay shape before Android,
HTTP, Cosmos, or membership work exists. It is deliberately a small reference
model, not an application layer to preserve indefinitely.

The model owns only typed creation, title/description edits, conflict recovery,
and receipt-aware replay. Its source and behavior tests are the authority for
its current types and cases. Later adapters must preserve the relevant
behavioural contract, not copy this in-memory implementation.

It intentionally excludes transport, authentication, disk durability, snapshots,
AI, recurrence, lifecycle, graphs, and full workspace limits. Add those rules in
the contract that owns them and prove them at the adapter boundary.
