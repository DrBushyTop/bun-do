# Bun Do architecture adversarial review

Reviewed 2026-09-12 against all 46 sections of the original `shared-task-manager-architecture-v1.md`. Findings describe that input. The architecture now incorporates the name, visual requirements, corrected date example, provider-schema guidance, explicit conditional transaction, runtime pin, and early feasibility stage. The remaining design decisions stay open. This is a design review; there is no application implementation to test. The [wayfinder map](https://github.com/DrBushyTop/bun-do/issues/1) owns the decision workflow.

The architecture has a sound core: Room drives the UI, local writes include an outbox, the server accepts semantic commands, and cloud AI cannot own task state. Keep those choices. It is not implementation-ready. Several safety guarantees are stated without enough protocol or state to enforce them, and some examples would fail or misinterpret input.

The owner requires the full v1 scope. Recurrence, dependencies, nested tasks, AI splitting and shared statistics stay. My recommendation is to prove the risky parts early and define their limits before building dependent features. The target phones are vivo X300 Ultra and OnePlus 13. Bun Do means "the way of the bun," with a martial-arts bunny identity. UI work uses Impeccable, with the owner's code-first preference saved in its configuration.

## Findings by priority

P0 means potential lost work or a broken core invariant. P1 means a feature or integration cannot be implemented reliably as written. P2 means an avoidable usability or maintenance burden. Findings distinguish confirmed documentation errors from design risks awaiting a decision.

### P0: pending edits have no defined rebase or rejection model

Sections 18-22 promise immediate local writes, field-level conflict handling, and rebasing. The task schema has one server version and no field versions or canonical base. It does not explain how a device distinguishes its optimistic edits from the server state when a sync response arrives.

Failure example: a user edits a title while an earlier create request is in flight. Applying the returned task snapshot can overwrite the new title, although its mutation is still pending. A rejected parent creation can leave later edits, children, notes or claims referring to something that never became canonical. Multiple sync triggers can also replay overlapping batches unless one local worker owns synchronization.

Specify a canonical base plus replay of pending semantic operations, or an equivalent model with explicit invariants. Decide field-version/base-value comparison, dependent-operation failure, operation ordering, and durable conflict storage. Apply canonical changes, receipt acknowledgements, cursor advancement, and reconstruction of the local projection atomically at a documented boundary. Test response loss, process death and an edit made during sync.

Decision: [Define lossless offline sync and recovery](https://github.com/DrBushyTop/bun-do/issues/5).

### P0: full resync and idempotency retention do not yet prevent resurrection

Sections 15, 19 and 37.19 mention 30-90 day tombstones and full resync, but do not define a minimum valid cursor, a stable snapshot boundary, receipt lifetime, or how unsynced operations survive snapshot replacement.

Failure example: a create commits, the response is lost, another device deletes the task, and both the tombstone and receipt expire. The returning phone retries its pending create. Without an expired-device/operation rule, the server can treat it as new and recreate deleted work. Keeping operation receipts forever avoids that particular failure but creates a separate retention and storage commitment.

Choose coordinated cursor, tombstone and receipt retention; device epochs or a durable alternative; and a full-resync protocol that preserves pending work separately. Define explicit restore, never an edit-based upsert. Snapshot replacement must not discard unsynced text or replay obsolete operations blindly. Bootstrap and pagination need a stable boundary under concurrent writes.

Decision: [Define lossless offline sync and recovery](https://github.com/DrBushyTop/bun-do/issues/5).

### P0: workspace revisions need conditional writes and complete changes

Sections 21 and 25 propose a revision counter, but the transaction algorithm must explicitly condition the sync-state write on its `_etag`. Every writer must use it, including AI, recurrence, membership changes and repairs. A process-local lock cannot serialize separate Functions instances. Read consistency also matters when later requests reach another instance. [Cosmos concurrency](https://learn.microsoft.com/en-us/azure/cosmos-db/database-transactions-optimistic-concurrency), [session-token handling](https://learn.microsoft.com/en-us/azure/cosmos-db/how-to-manage-consistency#utilize-session-tokens).

A split changes the parent, children and possibly ancestors. The example change record identifies one entity. The protocol must deliver every effect before advancing beyond that revision. Referencing the latest mutable entity also needs a version rule so that a page cannot mix unrelated versions or silently skip a deletion.

Choose consistent reads, recompute commands after conditional-write conflicts, and return complete bounded revision groups or specify an intra-revision cursor. Never advance to the server's latest counter when only an earlier page has been applied. Compare both reconnect orders for invariants and convergence; server-order conflict policy does not imply identical winners across different arrival orders.

Decision: [Define lossless offline sync and recovery](https://github.com/DrBushyTop/bun-do/issues/5).

### P1: transaction limits conflict with unbounded task structures

Cosmos atomic batches allow at most 100 operations, 2 MB and five seconds in one logical partition. The proposed three visible hierarchy levels and eight AI children do not bound manual children, total descendants, notes, cascade deletion, ancestor updates or rank rebalancing. A large subtree can exceed an atomic command's budget. [Cosmos transactional batches](https://learn.microsoft.com/en-us/azure/cosmos-db/transactional-batch).

Set server-enforced structural and byte limits and count state writes, events, changes, receipts and revision metadata together. Decide which large operations reject safely and which have an explicitly resumable protocol. Splitting an operation into several transactions does not preserve an atomicity promise. Keep a single workspace container unless evidence shows a need to change it.

Decision: [Bound task hierarchy, dependencies, and ordering](https://github.com/DrBushyTop/bun-do/issues/6).

### P1: the extraction schema is not a valid deployment artifact

Section 28 uses `maxLength` and `maxItems`, which the reviewed Azure strict-output documentation lists as unsupported. Its `$ref` entries also point to missing `$defs`. Copying this example can fail before any inference occurs. A retry or fallback model does not fix an invalid schema. [Azure schema limitations](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/structured-outputs#json-schema-support-and-limitations).

Maintain a complete provider-compatible schema and a stricter server validator. Pin the endpoint, API and request wrapper; handle refusal and incomplete output separately from valid JSON. The research confirms public Luna/Terra documentation, so the names are not themselves a demonstrated defect. The actual subscription, deployment, quota and supported request still need a live check. [Azure research](../research/azure-platform-constraints.md).

Decision: [Define durable AI work and safe patch application](https://github.com/DrBushyTop/bun-do/issues/7).

### P1: cloud AI has no durable execution owner

The design stores pending AI work on the phone and discusses asynchronous Functions processing. It never defines the durable server handoff. If a Function acknowledges a request and dies before recording or dispatching work, nothing guarantees that it runs later. If an AI result updates a task outside normal revisioned commands, other devices may never receive it.

Specify a persistent request/job record, transactional dispatch intent, bounded retries, leases, terminal failures, cancellation and a result command. Keep the model call outside a Cosmos transaction. Distinguish duplicate domain effects from duplicate paid model calls after an uncertain response. Add per-workspace usage bounds and backpressure. Use persisted field versions or an equivalent base comparison; a task-wide version alone cannot protect individual fields. Apply the same stale-intent rule to delayed queue placement and recurrence extraction.

Decision: [Define durable AI work and safe patch application](https://github.com/DrBushyTop/bun-do/issues/7).

### P1: offline date extraction can assign the wrong day or deadline

Section 5.4 passes processing-time "current" date to AI. Monday's "tomorrow" becomes Friday when the phone reconnects on Thursday. The restaurant example also treats the reservation's evening time as the booking task's deadline and chooses the following Saturday without an ambiguity.

Preserve capture time, timezone, source language and clock uncertainty. Give processing time a separate field. Keep an event time in the description unless the user actually specifies a deadline for doing the task. Do not turn "sometime next week" into an arbitrary precise day. Update examples and bilingual evaluation cases.

The capture-time rule and warning on the restaurant example are corrected in the architecture. Recurrence and accounting choices remain in [Define offline date capture, recurrence, and progress accounting](https://github.com/DrBushyTop/bun-do/issues/8).

### P1: hierarchy and dependency graphs can deadlock together

An acyclic dependency graph is insufficient when container completion also depends on descendants. A child that depends on its own parent cannot complete: the parent waits for the child and the child waits for the parent. Separate cycle checks on the tree and dependency graph can both pass.

Validate the combined completion prerequisites or forbid the relationships that produce such cycles. Define parent deletion/restoration, restored children beneath completed parents, all children deleted, and cancelled ancestors after a descendant reopens. Decide whether `IN_PROGRESS` is derived from claim or independent; the current schema can represent contradictory combinations. Specify completion of another person's claim and all actor permissions.

Decision: [Bound task hierarchy, dependencies, and ordering](https://github.com/DrBushyTop/bun-do/issues/6).

### P1: recurrence keys do not define schedule-edit behavior

`templateId + scheduledLocalOccurrence` deduplicates one stable schedule. It does not decide what happens when one phone predicts an old schedule while another edits or deletes the template. Local times can also be repeated or absent at DST boundaries. Monthly day 31 and long offline catch-up need rules.

Specify template generation/version, an exact cross-language key encoding, DST gap/overlap policy, month-end behavior, generation horizon and backlog limit. Decide whether changing a template affects already-created or completed occurrences. For shared statistics, define reopen/recomplete counting and late offline events crossing a week boundary. Root-only counting prevents split inflation but does not by itself prevent repeated completion credit.

Decision: [Define offline date capture, recurrence, and progress accounting](https://github.com/DrBushyTop/bun-do/issues/8).

### P1: voice feasibility and installation need evidence on both phones

Section 4 assumes satisfactory Parakeet operation. Research found a credible Android runtime and int8 artifact, but neither model availability nor phone specifications establish Finnish accuracy, latency, peak native memory or thermal behavior. The compressed artifact is about 487 MB and extracted model files about 640 MiB. Installation and replacement therefore need their own storage and recovery behavior. [Speech research and primary sources](../research/android-offline-speech.md).

Move the experiment ahead of dependent implementation. Measure cold/warm behavior and language fidelity on vivo X300 Ultra and OnePlus 13, including 120-second capture, repeated use, background pressure and airplane mode. Use a versioned manifest, integrity verification and atomic activation. Persist the recording state so process death between stop and transcript does not lose the user's capture. Specify bounded failed-audio retention. Offline voice stays required if the first candidate needs adjustment.

Research: [Verify Finnish and English offline speech on Android](https://github.com/DrBushyTop/bun-do/issues/3). [Measure offline speech on vivo X300 Ultra and OnePlus 13](https://github.com/DrBushyTop/bun-do/issues/11) was subsequently skipped.
Owner disposition after review: the separate experiment is skipped. Proceed on the assumption that local Parakeet works on both target phones. The preceding recommendation remains review history; it is no longer a planning blocker.

### P1: identity, backup and upgrade boundaries are underspecified

Email matching alone is not an invitation protocol. Sections 26 and 34 need verified invitation redemption, expiry, replay protection, owner/member actions and isolation by authenticated identity. A user must not sign into a second account and upload the first account's outbox. Device backup/restore can also copy device identity and replay state.

Define local cache ownership, logout and removal behavior, backup exclusions, device registration reset, and recovery of unsynced text. Membership checks must remain valid at commit if removal races a mutation. Document that losing a phone can lose work that never synchronized; local-first does not provide backup by itself. Choose backend restore and retention policy, protocol compatibility and Room migration tests before release. A schemaVersion field is not a migration strategy.

Decisions: [Define workspace onboarding and account-safe local data](https://github.com/DrBushyTop/bun-do/issues/9) and [Define reminders, upgrades, and recovery operations](https://github.com/DrBushyTop/bun-do/issues/13).

### P2: ordering, notifications and project structure need firmer boundaries

The move fallback in section 8 needs the client's historical neighbor snapshot, but the payload contains only two anchors. Either send a bounded neighbor list or use a fallback the server can compute. Define reversed anchors, ties, order among siblings versus roots, and claimed/completed task placement. Bound rebalancing under the transaction limits.

Due notifications need a local-versus-server owner, deduplication policy and behavior when permissions or background delivery are unavailable. FCM as a nonessential sync hint is a good choice; it is not a reminder-delivery guarantee. Include pending, failed and recoverable states in the Impeccable study, along with long Finnish text and nested tasks.

Avoid implementing every suggested REST action endpoint alongside `/sync` before a caller needs it. If both exist, route them through the same commands. The five backend projects are a suggestion, not a reason to add pass-through layers. Start with clear module boundaries and extract projects when they buy useful isolation. Pin a supported Functions runtime instead of the open-ended ".NET 8+". [Functions runtime evidence](../research/azure-platform-constraints.md).

Decisions: [Bound task hierarchy, dependencies, and ordering](https://github.com/DrBushyTop/bun-do/issues/6) and [Choose how Bun Do expresses the way of the bun](https://github.com/DrBushyTop/bun-do/issues/10).

## Recommended order

1. Run device voice and Azure integration checks early, while defining the sync invariants.
2. Set hierarchy/transaction limits, identity boundaries and the durable AI contract.
3. Resolve recurrence, dates, accounting, reminders, upgrades and recovery.
4. Use Impeccable to test the bunny identity against real queue, task, recording and conflict states.
5. Convert resolved decisions into implementation tickets covering the entire v1 scope.

The map records what remains open. Documentation research can finish now; actual phone and tenant measurements cannot be inferred from it.
