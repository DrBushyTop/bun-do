# Lossless sync and recovery

Architect decision, 2026-09-12. Resolves [Define lossless offline sync and recovery](https://github.com/DrBushyTop/bun-do/issues/5). This is the protocol to implement and test, not a claim that a server or client already exists.

## Ownership and invariants

Room owns the visible local projection. The server owns accepted shared state. Keep three separate durable stores per account: canonical base, immutable pending intent, and recoverable rejected/quarantined intent. Rebuilding a projection never deletes the latter two.

1. A local action commits its intent, local sequence allocation and updated projection in one Room transaction before UI success.
2. Every submitted device sequence gets at most one terminal outcome. A retry cannot repeat effects, even after detailed receipts expire.
3. Every shared mutation, including maintenance, AI, recurrence and membership, commits through the same workspace revision compare-and-swap.
4. Clients apply complete revision groups. A cursor never advances past effects not durably applied.
5. Deletion is not an upsert. Only an explicit restore revives a retained deletion group. Recovery after purge creates new IDs.
6. Network errors, stale cursors, logout, removed membership and server restore never silently discard locally authored text.

These are durable-on-installation guarantees. They cannot recover data from a lost phone that never synchronized or exported.

## Local state and operation envelope

Use canonical tables, an append-only intent journal, frozen submission envelopes, receipts/conflicts, projection tables and sync metadata. UI reads projections through Flow. A capture record keeps original text and capture context independently of a task's cleaned-up title.

Each operation contains `protocolVersion`, `commandVersion`, `stateEpoch`, `workspaceId`, `deviceId`, `sequence`, `command`, `payload`, `dependencies`, `observedVersions` and `occurredAtContext`. Sequence is a decimal unsigned 64-bit value on the wire, starting at 1 per device registration and workspace. The operation ID is `deviceId:sequence`; it cannot change on retry. Hash the exact frozen UTF-8 envelope bytes with SHA-256, excluding transport credentials. The server retains that hash with its outcome.

A pending intent may refer to the output versions of an earlier local operation with `AfterOperation(operationId, versionGroup)`. Resolve those references from terminal receipts before freezing the envelope for its first submission. This permits create then edit then split without guessing server versions. Once frozen, bytes never change. A new user correction creates a new operation.

Use UUIDv5 with the device UUID as namespace and the ASCII name `task/{sequence}/{ordinal}` for new ordinary task IDs. Ordinal is decimal starting at zero for roots or split children. Other new entity types use their own name prefix. The server recomputes and checks IDs. An old task ID therefore cannot be recreated by resubmitting a create under a newer sequence. Recurrence IDs follow the separate occurrence-key contract. Never accept arbitrary IDs in a new-entity create command.

Allow at most 32 operation dependencies, all lower sequences of this device, and at most 16 KiB envelope metadata excluding the bounded command payload. Same-task causal commands automatically depend on the preceding local command that supplies their preconditions. Independent tasks do not depend on a rejected operation merely because they follow it in sequence.

Local intent states are `PENDING`, `SUBMITTED`, `ACCEPTED`, `REJECTED`, `BLOCKED_DEPENDENCY` and `QUARANTINED`. Never retry a permanent rejection with changed bytes. Show retained text and the reason, then allow an explicit new command against current state.

## Server storage and transaction

Use a single-region Cosmos NoSQL account with **Strong** consistency and one `workspace-items` container partitioned by `/workspaceId`. Strong reads avoid carrying Cosmos session tokens between Functions instances. HTTP cursors are not database consistency tokens. Availability/cost tradeoffs are accepted for this small household workload; do not silently deploy Session consistency.

Workspace metadata contains a revision, a pruning watermark, schema version, state epoch and membership. Device records contain identity binding, last terminal sequence, last acknowledgement and expiry. Changes are immutable full client-visible entity snapshots or purge markers, grouped by revision. Each group includes `partCount`, entity IDs, part indices and a digest. Do not fetch the latest mutable task to interpret an old change. A whitelist controls replication: invitation hashes, registration credentials, private AI input and provider data never appear in shared changes or snapshots.

For each command:

1. Validate token, scope, envelope version, current external state epoch, active device and membership. Strong-read workspace metadata before all dependent reads.
2. Inspect the device sequence and receipt. Exactly `lastTerminalSequence + 1` may execute. A larger sequence returns `SEQUENCE_GAP`, without consuming it. A lower/equal sequence returns its recorded outcome if retained. A changed hash returns `OPERATION_ID_REUSED`.
3. Read canonical inputs with Strong consistency, validate every precondition and calculate all effects.
4. Build one transactional batch containing entity writes, complete immutable changes, activity when applicable, receipt, updated device high-water record and conditional metadata replacement using the originally read `_etag`. Increment the revision even for terminal rejections that consume a sequence; those groups may contain no shared task changes.
5. On metadata conflict, discard the computed effects and reread/revalidate. On a receipt-create conflict, read the committed receipt. Other failures roll back the batch. Retry at most five CAS collisions per request, then return retryable busy with no asserted outcome.

Check membership in that same CAS, so removal racing an operation has a defined order. All internal writers use stable request identities or canonical entity keys and the same commit function. Internal lease bookkeeping that changes visible job state is revisioned too.

Plan at most 90 batch operations and 1.75 MiB serialized request bytes, below the provider's limits. Count snapshots, wrappers, all ancestors, order indexes, device/receipt and statistics writes. Reject oversized effects without partial changes. Split worker work into independently valid bounded commands, not partial user cascades. Transaction timeout or transport loss means outcome unknown: look up/retry the same operation, never allocate another ID.

## Rejection and ordered submission

Submit one command at a time per workspace registration. Network optimization may send up to 20 envelopes, processed sequentially with a receipt for each; it must stop on a retryable error. Do not parallelize writes from the same registration.

Permanent domain errors, including field conflict, stale lifecycle and missing entity, consume the next sequence with a durable rejection receipt. Authorization failures, expired devices, unsupported protocol, rate limiting and database outages do not consume it. A rejection receipt records machine code, relevant entity/group versions and conflict details without copying unrelated household data.

If create A rejects, dependent edit B and split C stay recoverable and cannot execute against absent A. B and C cannot yet have frozen normal envelopes because freezing waits for their dependencies' accepted output versions. At each original allocated sequence freeze a `DiscardBlockedIntent` envelope with the rejected prerequisite's operation ID and outcome, without task preconditions. The server verifies that earlier terminal outcome belongs to this registration and records `BLOCKED_DEPENDENCY`. A dependent chain may reference an earlier `BLOCKED_DEPENDENCY` receipt. Consume each allocated sequence so independent D can continue. A discard is a journal disposition, not silent deletion of its text. Already frozen envelopes never change; a later shared invalidation rejects them by normal precondition validation.

The client sends a contiguous `acknowledgedThrough` only after receipts and their effects or rejection variants are durable. Server receipts carry effect revision and output versions. Do not remove a successful intent's optimistic effect until the canonical base reaches that effect revision. Persist early receipts as awaiting-effects and continue pulling.

## Field conflicts and optimistic replay

Give each editable field or atomic field group `fieldVersion` and `humanVersion`, both server revision stamps. Human changes update both; AI updates only `fieldVersion`. The [command and version catalog](command-catalog.md) owns group names and preconditions. Independent title and description edits do not conflict.

For human text/due edits, compare the observed `humanVersion`. If unchanged, an explicit human edit can replace an intervening AI-only value. If another human changed the same group, reject that group and preserve both values. An edit command is atomic across its requested groups; no hidden partial acceptance. The conflict UI can make separate new edits.

AI must match its exact base `fieldVersion`, plus lifecycle/deletion guards. Claims, complete/reopen, split, restore and other state transitions require exact group preconditions, not the human-only exception. Moving uses explicit anchors and increments human order-intent version for a user move. AI placement must match the recorded order-intent base.

Rebuild projections from the canonical base by replaying pending intents in local sequence order. A pending human edit remains visible over an AI-only update. A remote human conflict becomes a visible recoverable variant rather than a replaced title. Unsupported replay, failed dependencies or deleted targets move intent to recovery; ordinary edits cannot revive a deleted task. Never silently revise an expected version to make a stale command succeed.

Reapplying means user confirmation against the displayed current state and a new sequence. Text and notes may be copied to new tasks after purge. Claims, completion, deletion, splits and membership actions must be chosen again; never offer an automatic bulk replay of these transitions.

## Pull, pagination and local worker

`POST /sync` accepts the envelope batch, the last fully applied cursor and receipt acknowledgements. The cursor encodes epoch, workspace and revision, authenticated by the server. Return receipts, a contiguous sequence of complete revision groups, `throughRevision`, `hasMore` and current head separately. Never set `throughRevision` to head unless every intervening group was returned.

Capture a read target revision when the pull begins. Read immutable groups through that target with Strong consistency. The server commit planner additionally caps each encoded change group at 2 MiB. HTTP pull responses allow 4 MiB uncompressed including receipts/framing, so one maximum group always fits. Add complete groups until the next will not fit, then return a continuation. An empty group still advances one revision. Never accept a write that produces an undeliverable group. Continuation pages use the same target; newer writes wait for the next pull.

The Android sync coordinator serializes foreground, FCM, periodic and connectivity triggers with one account/workspace worker and a Room-backed lease. A replacement worker has a fencing generation. Every response-apply transaction checks account generation, worker generation, expected base cursor and epoch. A delayed response from a cancelled or previous-account worker cannot apply.

In one Room transaction, validate group completeness, update canonical tables, record receipts/recovery variants, rebuild affected projections and advance the cursor. If the process dies, either all these effects committed or none did. Release the network lease when inactive; no database transaction spans a network call. FCM is only a hint. Foreground and periodic sync must work without it.

## Device lifetime, retention and resurrection

A successful authenticated registration/renewal grants 90 days from server time. Renew only before expiry and only for the same installation identity. After expiry or explicit revocation, the registration can never submit again. Retain compact retirement/high-water records for the workspace lifetime. A request at/below a retained high-water mark with an expired detailed receipt returns `OUTCOME_EXPIRED`, never re-executes.

Retain receipts, revision groups and deletion tombstones for at least 120 days. Age alone is not enough to prune a receipt for a still-valid device whose acknowledgement has not passed it. Such a device must acknowledge or expire first. Pruning is a revisioned maintenance command and atomically advances the minimum cursor to the last contiguous pruned revision. Pins held by active snapshot/recovery reads also prevent pruning.

An expired client preserves its old base and pending intents as recovery material, registers with a new ID, fetches current state and asks for explicit comparison/import. It does not reassign old sequences to a new device. A server state epoch change follows the same process, even when its old receipt or entity exists in the restored database.

Task purge is separate from receipt pruning. Retain a task deletion group for at least 120 days after its latest member deletion, until no live valid device can submit a pre-deletion command and no snapshot pin needs its tombstones. Mark the group `PURGING` in one revisioned command, making restore reject. Delete its notes/capture content in chunks of at most 32 items, then remove task documents and order entries in a bounded final command with purge markers. Persist the cleanup cursor; retries are idempotent. Already separately deleted descendants retain their own groups and cannot be purged through an active ancestor group's restore.

If a retained deleted descendant depends on an ancestor scheduled for purge, wait until all descendant deletion groups are independently eligible, then mark the whole bounded subtree purging. No live child may be orphaned. Purged IDs remain historical nonblocking prerequisite references; the new-entity ID rule prevents reuse without storing full tombstones forever. Activity retains only IDs and permitted summary metadata after content purge.

## Stable bootstrap and stale cursors

A cursor below the pruning watermark returns `SNAPSHOT_REQUIRED` before accepting submitted mutations. This is not an instruction to clear local storage. `GET /devices/self/outcomes` accepts a sequence range of at most 100 and works during recovery for an authorized still-valid registration. It returns the server high-water, epoch and each sequence's `ACCEPTED`, `REJECTED`, `NOT_SEEN` or `OUTCOME_EXPIRED` state, retained hash and effect revision. `NOT_SEEN` is possible only above high-water. It does not renew an expired device or grant old-epoch access.

The server builds a bounded-lifetime immutable snapshot artifact. Pin pruning, Strong-read start revision R and epoch, enumerate the current canonical documents with paginated Strong reads, then Strong-read metadata again. Publish only if revision and epoch are still R and every chunk has passed size/digest checks. Otherwise discard the candidate and retry, at most three times; return retryable `SNAPSHOT_BUSY` after contention. Internal writes affecting exported state also increment revision. This revision fence makes ordinary query pagination safe without claiming it provides snapshot isolation itself.

Store snapshot chunks in a private dedicated Blob container. A manifest includes workspace, epoch, revision R, schema version, document counts, ordered chunk hashes and expiry. Use authenticated API reads, not public blobs. Each chunk is at most 4 MiB, total snapshot cannot exceed the 1 GiB workspace bound, and at most two candidates per workspace may run. Expire artifacts and pruning pins after 30 minutes; clients restart if expired. No mutation or read is authorized solely by knowing a snapshot ID.

The client preflights free storage using manifest byte counts, retaining room for both old and staged bases plus 25% and a 64 MiB working reserve. On shortage, preserve pending work, show export/cleanup controls and do not erase the current base to force progress. Download chunks resumably into generation-tagged canonical tables, recording each verified chunk in a small transaction. After all chunks pass, atomically switch the active-base generation pointer, not a gigabyte of rows. Rebuild projection rows in bounded background batches behind a rebuilding indicator; retain the old projection until the new generation is ready. Atomically switch the projection pointer with its cursor. Keep local writes journaled during rebuilding and replay them before that switch. Preserve the old base as recovery material until reconciliation completes, then reclaim it in chunks. Preserve the outbox and local capture store throughout.

For a still-valid device, reconcile every previously submitted pending operation against its receipt before replay. Receipts above R wait for the corresponding delta pull. Unknown operations above the server high-water may continue only with their original identities and observed versions; the normal validator can reject them. Outcome-expired operations quarantine. After atomically installing base R, pull all changes after R and replay known-safe pending intent. A stale cursor does not by itself authorize new command identities.

Fresh installs have no old replay. Expired registration, missing installation identity or changed global epoch always uses explicit recovery, never the still-valid-device shortcut.

## Statistics and maintenance journals

Every command changing a root's membership, lifecycle or first credit writes a compact `root-state-event:{revision}:{rootId}` with before/after values and server acceptance time in the same batch. These events have 365-day retention independent of the 120-day sync log. Include their writes and public statistical changes in the transaction budget. They contain no task title or description.

Statistics workers use deterministic `period:{zone}:{kind}:{start}` and `queue-day:{zone}:{date}` IDs, a source revision cutoff and `FINALIZING`/`FINAL` states. Recompute under CAS from immutable events plus the last retained checkpoint. Process at most seven daily boundaries or one week/month per continuation. Persist the cursor. Repeated identical finalization is a no-op with no new activity; a late credit updates only its versioned aggregate. Store a compact daily checkpoint before expiring events, so unchanged roots older than the event window remain reconstructable. Do not prune source data required by an unfinished checkpoint. Milestone identity is its threshold and workspace, so duplicate workers cannot celebrate twice.

## Interface and required proof

Keep public HTTP entrypoints small: registration, sync, snapshot reads, identity/onboarding and export where needed. Action endpoints, if introduced, call the same command handler. Do not build duplicate REST mutation implementations.

Test through `LocalWorkspace.execute/observe/synchronize` and `WorkspaceServer.handle/snapshot`, with fake transport, clock and two isolated local databases. Run both reconnect orders, randomized traces and a real Cosmos adapter suite. Arrival order may choose different winners; each trace must preserve invariants and converge once its own accepted order is applied.

Required traces include create/edit during an in-flight response; create rejection with dependent split and independent later task; lost response then deletion then expiry; receipt before effect-page; process death at every Room apply boundary; two foreground/FCM workers; snapshot under concurrent writes; stale snapshot retry; expired epoch with old pending creates; same-sequence changed payload; field edit pending when AI arrives; removal racing a command; maximum subtree cascade; purge racing restore; unknown command version; and missing revision part. No implementation ticket may claim these properties from this document alone.
