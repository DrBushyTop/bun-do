# Manual recovery

This is an operator procedure, not an automated restore feature. A complete
cloud restore rehearsal belongs to V2. Do not claim a recovery time or loss
window until a rehearsal measures it.

## Before an update

Install an APK signed by the same release key over the existing installation.
Do not uninstall, clear storage or enable destructive Room migration to work
around a failed update. Those actions can lose unsynchronized work.

Ask each person to check pending work and export unsynchronized text through
Settings, Account, Saved work and recovery. Export retained audio separately.
Exports contain household text. Store them privately. Android backup and device
transfer deliberately exclude the app's local databases, credentials and audio.
A server backup cannot contain work that never synchronized.

If migration fails, keep the installation and files intact. Record the source
and target APK versions and the error without household text. Fix the migration
and ship another same-key APK. Do not downgrade a database to an older schema.

## Confirm backup availability

Read the resource group and Cosmos account from the selected Bicep deployment.
Use Azure's account readback to inspect `backupPolicy`, then inspect the
restorable account timestamps and locations. Bicep owns the backup settings;
do not copy them into this runbook.

Before an incident, verify operator access to the backup and restore actions.
Keep the deployment commit, source account resource ID, selected restore time,
and approval in a private incident record. Never include keys or account text.

Microsoft documents [continuous backup](https://learn.microsoft.com/azure/cosmos-db/continuous-backup-restore-introduction)
and [restore to a separate account](https://learn.microsoft.com/azure/cosmos-db/restore-account-continuous-backup).
Use a separate account for recovery, not an in-place replacement of live data.

## Isolate and inspect

1. Stop API traffic and all writers, including cleanup, split, repeats,
   maintenance and background jobs. Verify that they are stopped. Ask clients
   to remain offline and retain their local text. An old APK must not be able
   to reach the recovering service.
2. Restore into an isolated account. Do not grant the live backend access,
   switch connection settings or start workers. Retain the original account
   and the restore unchanged as evidence.
3. With a scoped, temporary operator identity, inspect a working copy. Check
   schema versions, workspace membership, task/checklist references, queue
   order, completion credit, receipt/change-group continuity, tombstones and
   repeat state. Compare representative tasks and dates with the owner.
   Cosmos document envelopes and the deployed domain model define the schema.
   Stop if a version is unknown or integrity checks disagree.

## Replace restored authority before reconnecting

Do not resume directly from the backup. It can resurrect invitations,
registrations, memberships and already-dispatched AI work.

1. Generate a new random workspace epoch outside the restored database. Store
   the old-to-new mapping and restore identity in the private incident record,
   outside the backup's rollback boundary. Never recover the new epoch from
   the old backup.
2. Have the owner review the exact active member identities and ownership.
   Remove unapproved members. Cancel every outstanding invitation and pending
   join. Old invitation secrets must no longer redeem.
3. Retire restored installation registrations, including their durable
   retirement records. Invalidate workspace device sequences, cursor secrets,
   snapshot pins/artifacts and old receipt/outcome authority. A restored
   registration must not become active simply because the live retirement
   happened after the backup.
4. Cancel restored cleanup/split requests and leases, including embedded task
   request state. Do not re-dispatch ambiguous paid calls. Review repeat
   schedules and their next occurrence before re-enabling the repeat worker.
5. Build a consistent baseline under the fresh epoch. Preserve approved task
   content, hierarchy, order and historical first-completion credit, but never
   relabel old executable commands as commands in the new epoch. The ordinary
   commit path intentionally forbids changing epochs. This requires a reviewed,
   incident-specific offline transformation, not a direct edit to `StateEpoch`
   alone. Validate it against the deployed schema on an expendable copy first.
   V1 has no generic restore command. If the transformation cannot be proved
   safe, leave traffic stopped and use the text-only alternative below.
6. Before changing deployment configuration, demonstrate on the isolated copy
   that old epochs, registrations, cursors, invitations and worker callbacks
   fail without writes. Verify that fresh registrations and reviewed members
   can read the baseline. Keep audit evidence outside the restored account.

Only then use the scoped Bicep plan/review/deploy procedure to select the
recovered store. Resume API traffic before scheduled writers, observing errors
and rejected stale requests. Do not silently fall back to the old account.

## Reconcile each client

Keep existing installations intact. Each person signs in as the same identity,
obtains a fresh registration and compares retained local text with the restored
household. Use the recovery preview and explicitly select text to import.
Imports create new IDs and commands. Never upload old outbox envelopes,
credentials, registration IDs or sequence counters.

Check the first client's result before reconnecting the second. Repeat for
every device. Resolve duplicates and conflicting text with the owner, not by
guessing which timestamp wins. Export failure must leave the source untouched.

If restoring server authority is unsafe, create a new household under fresh
identity registration, invite and approve reviewed members, and explicitly
import permitted text exports. Explain that this does not reconstruct shared
history, completion statistics, schedules or unsaved audio. Keep the old
installation available until its owner confirms reconciliation.
