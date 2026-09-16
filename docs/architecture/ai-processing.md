# AI cleanup and checklist split

V1 uses one configured model for capture analysis, cleanup and split. [Clarify](https://github.com/DrBushyTop/bun-do/issues/29) and [optional placement, escalation and worker recovery](https://github.com/DrBushyTop/bun-do/issues/49) are V2 work. This replaces the earlier full AI job/accounting contract.

## Voice capture review

The September 16, 2026 owner request adds analysis before saving a new voice
capture. Transcription remains a separate stage. Persist its text locally before
analysis, and remove temporary audio unless the user opted to keep recordings.
An authenticated, cancellable request may suggest a title, description and direct
checklist items. It does not create or mutate shared tasks. A shopping list uses
product names with quantities and qualifiers, not a repeated "buy" action.
Single actions need no invented steps. Preserve excess or ambiguous content in
the description instead of dropping it.

Show an editable preview and original transcript. Saving explicitly accepts the
edited result. Create the original capture, accepted text edit and any checklist
split in one local transaction through the existing command machinery. Each
command retains its ordinary sync preconditions and dependency handling. Do not
promise all-or-nothing server acceptance across those commands. Offline or failed
analysis leaves the transcript editable; do not silently repeat a paid request.
The same account lease and fixed recording destination fence delayed results.
Settings can disable automatic analysis independently of local-only speech.

Existing-task cleanup and split retain the durable workflow below.

## Boundary and useful result

For existing tasks, Android submits authenticated AI intent through the normal command path. The backend owns prompts, provider credentials, validation and canonical writes. A task and its original text exist before cleanup succeeds. AI failure never blocks manual task work.

Cleanup may propose or update title, description and content language using the task's original language. Explicit deadlines use capture time; ambiguous deadlines require confirmation. V1 does not extract areas, reorder tasks, create repeat schedules or change claims, completion, membership, IDs or hierarchy. The date/details slice adds explicit due extraction after initial text cleanup. [Recurrence extraction](https://github.com/DrBushyTop/bun-do/issues/44) and [explicitly requested queue placement](https://github.com/DrBushyTop/bun-do/issues/49) are deferred to V2, with human confirmation for schedules and stale-result guards for moves.

Use the chosen provider's structured-output contract and server-side semantic validation. Code and provider schemas own request shape and technical bounds. Trim deferred fields from active prompts/schemas when implementing this slice; existing unused schema files are not a requirement to expose V2 features. Refusal, invalid output and provider failure retain the task and offer retry or manual editing. No schema failure escalates to another model.

## Durable requests and safe application

Persist accepted AI intent with the task's visible pending state and normal operation receipt. Keep request identity, requester, input snapshot, expected task versions and enough status to resume or offer Retry after interruption. A worker must not lose accepted work or apply a result twice. Choose the smallest worker implementation that proves those behaviors; a generic job framework is unnecessary.

Cleanup runs during authenticated foreground or periodic sync, one request per
sync from that requester. Pending intent remains durable between syncs. An
interrupted running request becomes a visible retry after its lease expires;
resumption does not automatically repeat a paid call. Other household members
can compare a retained proposal or explicitly request fresh cleanup.

A request may be pending, running, ready, applied, failed or superseded. Use conditional ownership and task-version checks when applying results. A late worker, cancelled request, deleted task, removed member or changed account/epoch cannot mutate the current task. A restarted worker can leave uncertain work retryable. Bounded retries may incur a duplicate provider charge; v1 promises one visible result application, not exactly-once billing.

Keep input/output text out of telemetry and discard private AI input when no longer needed for active work or user recovery. Record operational timings, status and usage counts without prompt text. No token reservations, daily budgets, fairness quotas, durable SENT ledger or named UNKNOWN_OUTCOME state is required for v1. V2 may revisit worker handling using observed failures.

## Human edits and suggestions

Preserve the existing field/human-version distinction. Automatic patches require the exact base field version plus relevant lifecycle, hierarchy and deletion guards. A pending human edit can override an AI-only change while a concurrent human edit remains a recoverable conflict. Never discard original text or silently replace a human correction.

When a result is stale or ambiguous, keep the useful proposal available for explicit comparison/application while its task or local recovery record is retained. It cannot mutate the task automatically. V1 does not require a separate timed suggestion-archive service.

## Split

Cleanup and split generation share one active AI request per task. An explicit new request supersedes the previous one; it never blocks manual editing. Dictated split instructions stay in the checklist draft through interruption and retry.

Split produces an editable preview for direct checklist items and no immediate task mutation. Acceptance carries the final child list and relevant parent text/lifecycle/hierarchy/deletion versions. AI preview acceptance checks the exact source text versions, including AI-only changes. The normal manual split command validates the bounded list and atomically converts the root and creates children. A stale acceptance makes no partial change and preserves the draft for a fresh action. Model output cannot authorize a split itself.

## Usage policy

The owner wants no product AI quotas initially. [Usage-policy review](https://github.com/DrBushyTop/bun-do/issues/37) starts only after V2 and may conclude that no limits are needed. Do not introduce product recording-duration caps, per-day jobs, monthly tokens, account fairness limits or budget reservations in V1 or V2.

Keep authentication, provider constraints, bounded request/output sizes, memory limits and timeouts. These are technical failure controls, not household consumption policy. Preserve input when a technical bound prevents processing and explain the available action. Operational worker concurrency may protect the runtime without imposing a product usage allowance.

## Verification

Exercise representative Finnish, English and mixed-language tasks on the actual configured endpoint, including refusal, malformed output, ambiguity, human edits during inference, cancellation, duplicate delivery and interrupted requests. Use results to refine prompts; a fixed synthetic corpus count or an unused model deployment is not a release gate.
