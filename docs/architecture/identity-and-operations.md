# Identity, reminders and recovery

The September 13, 2026 scope revision narrows operations while preserving the full in-progress stale-client recovery slice. The earlier decisions resolve [onboarding and account isolation](https://github.com/DrBushyTop/bun-do/issues/9) and [reminders, upgrades and recovery](https://github.com/DrBushyTop/bun-do/issues/13). They are implementation requirements. Owning implementation issues hold current live verification results.

## Identity and joining

Use MSAL Android with Microsoft-hosted sign-in for personal Microsoft accounts
through the `consumers` authority. The existing `huuhka.net` workforce tenant
owns the Android and API registrations, but users need no membership or guest
invitation there. The owner's
[replacement decision](../adr/0003-use-existing-workforce-tenant-for-sign-in.md)
supersedes customer-tenant native OTP and the interim single-tenant setup.
Microsoft authentication does not grant
household membership. The API validates signature, configured issuer, audience,
expiry and scope. Internal user identity derives from the exact validated
issuer and subject pair. Android obtains that pair from the authenticated API,
not its client ID token, whose subject can differ. Email and display name are
mutable presentation data and never authorization keys. A changed subject is a
new account; v1 has no automatic account linking.

First sign-in and workspace creation/joining require connectivity. Before sign-in, a separate local inbox supports typed capture and installed local speech. After authenticated workspace selection, the user explicitly imports selected inbox drafts as new task commands. Never attach anonymous drafts to an account automatically. Model download is optional during onboarding; typing works immediately. Installing the model requires connectivity, storage preflight, checksum validation and atomic activation.

Every workspace has exactly one owner and at most ten active members, including the owner. An authenticated creator becomes its owner. Owners issue invitations through the app and share the link themselves. The backend does not send invitation email in v1.

An invitation contains a random 256-bit secret, expires after 24 hours, and permits one joining identity. Store only its cryptographic hash. Redemption requires authentication and atomically binds the invitation to that subject. It creates a pending join, not membership. The candidate and owner see the same random confirmation code; the owner checks it with the intended person and approves that candidate in the app. This explicit confirmation avoids relying on an unspecified verified-email token claim. Redemptions by another identity fail; same-identity retries return the pending/result state. Expiry, cancellation and approval all serialize with workspace changes. Never log links or codes. Limit outstanding invitations to ten and redemption attempts to ten per identity per hour.

Members can read all shared data and perform all v1 task, simple-repeat and AI actions. Claiming is for oneself; members release their own claims. Any member may complete another member's claimed task, with explicit confirmation and recorded actor attribution. Owners can release another member's claim, invite/remove members, change workspace settings and transfer ownership. Transfer requires an existing active member and atomically replaces the owner. The owner cannot leave or be removed before transferring ownership. Self-service workspace deletion and undelete are V2 work. V1 keeps task deletion, account sign-out and local-data recovery.

Membership checks use current workspace membership inside the same conditional revision transaction as each command. Removal and a racing task action therefore have a definite order. AI result application also checks the requesting member is still active. Membership removal records a revocation; subsequent device requests fail, including reads. Claims held by removed members become available by derived eligibility, without an unbounded cascade of task writes. History retains their internal member attribution with a "Former member" label.

## Local account boundaries

Each authenticated identity owns a separate Room database, audio directory, work names, notification namespace and device registration. An active account-generation token guards all callbacks. Before writing a response, a worker verifies both identity and generation; cancellation alone does not prevent a late callback. Requests use only the matching account's access token and workspace membership. Never substitute another logged-in account when token refresh fails.

Offline sign-out immediately stops workers, clears credentials, cancels notifications and locks the account's local data. Pending commands and captures remain quarantined until the same identity signs in again. Show their count and offer explicit export or deletion before signing out. Retention has no automatic expiry for unsynced text. Account switching cannot display, import or upload another account's quarantine. An expired access token pauses networking but leaves the currently signed-in account's offline work usable.

When the device learns of removal, cancel that workspace's work and reminders, erase the canonical shared cache, and quarantine only locally authored unsent text and conflict variants for same-account export. Do not retry them against the removed workspace. Offline devices cannot learn revocation immediately; already cached content remains readable until contact. The product must state this limitation when removing a member.

Offer a user-initiated UTF-8 JSON and plain-text recovery export through Android's document picker. Include local draft text and unresolved variants, with workspace labels and capture times. Exclude credentials, invitation secrets and reusable device identity. Import is an explicit preview that creates new task IDs; it never replays exported commands. Warn that exported files contain household text. Raw audio export is a separate explicit action. Successful transcription commits text before deleting audio; failed recordings remain for seven days with retry/export/delete controls and a visible expiry. Bound failed audio to 20 recordings or 256 MiB; when full, require cleanup before another recording and retain typing.

Pre-identity anonymous recordings remain recoverable after installation-identity reset. Only the known anonymous layout is eligible, never encrypted account directories. The account screen offers an explicit copy into the current local inbox's recording store, separate WAV export and confirmed deletion. A copy preserves the original retention deadline and removes the source only after the destination is saved. Failed copies and exports retain the source. Expiry cleanup leaves a visible acknowledgement in the account screen. Account changes cancel pending recovery actions; neither recovery nor transcription automatically adds a task to a household.

## Devices and backup

Exclude databases, outbox, credentials, registrations, audio and model downloads from both Android cloud backup and device transfer rules. Apply `allowBackup=false` as well as explicit exclusions. Keep an installation secret in Android Keystore. Missing or mismatched installation identity quarantines unexpected restored files and requires sign-in and a new server registration. A reinstall, device restore, cleared app data or replacement phone must never reuse an old device ID or operation sequence. Model files can be downloaded again.

Register each installation online under its authenticated user. Use the sync contract's durable device sequence and 90-day validity period. Limit active registrations to five per member; registration offers revocation of an older device when full. Revoked/expired registrations cannot submit mutations. Durable compact retirement records prevent old registration IDs from becoming valid again. Re-registration does not authorize automatic replay of old pending commands.

Loss of a phone can lose everything that never synchronized or was explicitly exported. A successful local write is durable on that installation, not a cloud backup. Show last successful sync and pending work in settings and before reset/sign-out. A device-reset action explains this loss, offers export, then removes the account's local data and registration credentials.

## Reminder delivery

Android owns reminders; the backend never independently sends a due notification. FCM sync hints are deferred to V2. Foreground and periodic sync remain sufficient for convergence. Each enabled device may notify, including multiple devices belonging to one member. There is no cross-device exactly-once claim.

Default reminders cover unclaimed tasks and tasks claimed by the current member. A user may select all tasks or disable reminders. Completed, cancelled, deleted and snoozed tasks produce none; a snoozed checklist root suppresses its items too. Date-time tasks remind at their due instant; date-only tasks default to 09:00 in the due value's pinned `dateOnlyReminderZoneId`, with a per-member setting. Snooze suppresses a reminder until its expiry. Time resolution follows the recurrence/date contract.

Use one persistent scheduler per account, inexact AlarmManager alarms and WorkManager reconciliation. Reconcile on local projection changes, sync, boot, time/zone changes, permission changes and foreground entry. Re-read the current projection before displaying. Notification identity is account, workspace, task/occurrence, due revision, snooze revision and reminder setting revision. Stable notification IDs replace retries; a persisted delivery ledger suppresses later duplicate callbacks. Android posting and Room commit are not atomic, so absolute exactly-once delivery is not promised. Use `onlyAlertOnce` for replacement posts.

Request notification permission when the user enables reminders. Denial leaves a visible in-app due list and settings link; do not repeatedly prompt. Do not request exact-alarm access in v1. Android may delay inexact alarms and background work, so the app describes reminders as approximate. After delays, deliver at most one summary of eligible missed reminders from the last 24 hours; older items remain in the due list. A disconnected device may notify for remotely completed work until it synchronizes. [Android alarm behavior](https://developer.android.com/develop/background-work/services/alarms).

## Compatibility and manual recovery

Keep separate wire, command, Room and server schema versions. Reject unsupported versions before accepting writes or applying unknown required changes. Preserve offline reading, pending text and export. Support migrations from builds that hold actual user data, with no destructive fallback. Do not require fixtures for unused experimental builds or promise a fixed dual-protocol compatibility window.

Keep the deployed backup configuration and verify it as part of the [v1 operations slice](https://github.com/DrBushyTop/bun-do/issues/50). Document a manual procedure for isolated restore with traffic/workers stopped, integrity checks, a fresh external epoch, invalidated old registrations/invitations, owner membership review and explicit client comparison/import before resuming. Restored AI work cannot run automatically under stale authority. Old commands never replay automatically across the epoch change.

The [larger operations slice](https://github.com/DrBushyTop/bun-do/issues/33) moves to V2: self-service workspace delete/undelete and resumable final purge, a complete cloud restore drill, measured recovery objectives and extended compatibility automation. V1 does not claim an unrun restore drill passed or promise fixed RPO/RTO targets.

The client-side snapshot, expiry, outcome lookup and epoch-recovery behavior in [Issue 21](https://github.com/DrBushyTop/bun-do/issues/21) remains v1. Manual operations must use that recovery boundary safely.

## Bounds and release evidence

Retain receipts, revision groups, tombstones, retired-registration records and snapshot bounds as specified in the retained [sync/recovery contract](sync-protocol.md). Keep existing technical task/batch/storage bounds. Automated capacity warnings and storage-reserve accounting are deferred; reaching a technical bound must preserve local drafts and leave cleanup/recovery possible. Simple shared statistics retain only the metadata needed for their documented counting behavior.

Collect only timings, counts, protocol/build versions, result codes, RU and token usage, and random correlation IDs. Exclude request bodies, household text, email, audio, prompts, access tokens and invitation codes from logs, crash breadcrumbs and SDK HTTP tracing. The owner selected full useful OpenTelemetry traces for the low-traffic deployment on September 12, 2026, replacing the earlier exception-only choice. Emit one context-rich completion span per operation, including success, failure and cancellation. Enrich it with actual operation context as execution proceeds. Include code-defined function names, service/build version, duration, outcome, execution stage and HTTP status where available. Unexpected failures include exception type and bounded code-only stack frames, without messages, source paths, inner exception content or incoming trace baggage. Do not deliberately sample normal completions or rate-drop failures at this traffic level. Do not add a collector solely for sampling, routine log export or automatic performance metrics. Retain diagnostics for 30 days and configure the 100 MiB/day workspace ingestion cap. Bounded queues, network failures and ingestion limits can drop diagnostics; these controls are not a strict spending ceiling. Disable exporter disk buffers and keep any future local diagnostic buffers below 10 MiB. See [telemetry implementation and queries](../../infra/observability.md). The AI contract imposes no product quotas or token budgets; usage-policy review waits until after V2. The owner explicitly skipped all deployment budget alerts on September 12, 2026, including the EUR 25 and EUR 50 thresholds. Do not provision budget alerts. A manual AI kill switch pauses expensive calls while preserving queued jobs.

Release evidence covers current household authorization, account isolation, safe offline/reconnect behavior, reminders, real AI integration and the retained stale-client recovery slice. Migrations cover data actually in use. Full cloud restore drills and V2 features are not v1 gates. Use two emulator profiles; do not claim physical-phone performance.

## Destructive actions and recovery details

Workspace delete/undelete countdowns and resumable workspace purge are deferred to [V2 operations](https://github.com/DrBushyTop/bun-do/issues/33). Task deletion and its retained recovery contract remain v1.

There is no hidden owner-bypass or email-based account takeover. An owner unable to sign in first uses Entra's recovery process. If that cannot restore the same issuer/subject, a documented operator recovery requires proof outside the app and membership review; it is not an automated v1 capability. Otherwise members can export permitted local work and create a new workspace. No promise of automatic ownership recovery is made.

The invitation link is shared through Android's Sharesheet. Pending-join screens show expiry, cancellation and the matching confirmation code. An owner approval consumes the invitation; races and retries use its version and canonical pending identity. App and verified HTTPS links open the authenticated join flow; an unauthenticated link does not disclose household tasks. Removal is delivered as a sync/access failure and optional opaque FCM hint, not a content-bearing push.

Recovery export uses `formatVersion: 1`, UTF-8, capture timestamps, source workspace labels, local text and explicit conflict variants. It never exports executable envelopes or canonical caches wholesale. Stream selected records through Android's document picker, splitting exports above 50 MiB. Show plaintext sensitivity before sharing. The app does not invent its own export encryption scheme; users choose their destination. Export failure leaves all source records untouched. Import previews text, validates bounds and creates new IDs; it cannot restore old credentials, registrations or operation sequences. Same-account reauthentication is required to unlock retained quarantine after sign-out. Android Keystore-backed account keys protect the locked local files.

On notification permission revocation, cancel pending alarms and visible notifications for that account. Re-enabling triggers reconciliation, not delivery of the entire history. A delayed summary contains eligible tasks ordered by due instant then opaque ID, capped at five titles with an additional-count label. Persist a single summary identity per reconciliation window. Task delete or restore invalidates the queued reminder by its deletion version before posting. Lockscreen notifications default to private with generic public content.
