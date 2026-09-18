# Reusable household lists

Lists is a household destination between Tasks and Together. Activity remains
last. A list that has been started is an ordinary root task with direct checklist
items. Task details, list views and adventures all reference those same task IDs.
There is no second set of checkboxes or claims.

## Saved content and fresh copies

A saved list contains a name, notes, ordered item text and item notes. It has no
completion, claimant, dates or journey credit. A selected preview creates new
IDs with unchecked, unclaimed items and no copied deadlines. Editing a copy does
not edit its saved source. Deleting a saved source does not delete its copies.
Practical starters are editable suggestions, never automatically created work.

Saved definitions belong to the household. In the first release, saving,
editing, pinning and deleting definitions requires connectivity. The app caches
them per account, household, epoch and registration. Cached content can start a
new list offline. Definition writes compare the library version and retain the
exact request for ambiguous retries. A conflicting edit requires a fresh review,
not an automatic overwrite. Deletion is immediate for a saved definition;
old writes cannot recreate it using an obsolete version.

Creating a list records its root and item commands in one local transaction.
The ordinary dependency journal retains the items if delivery stops after root
creation. The server accepts each command under existing task guards. Rejected
commands remain visible through existing recovery controls.

## Standing lists and completion

A finite list follows ordinary checklist completion. It contributes one normal
first-root completion, not a credit per item or an extra adventure credit.

A standing grocery list remains open when all eligible items are checked.
Checking its items does not earn root-completion credit. Unchecking a previous
purchase puts it back on the same list, preserving quantities and preferences.
Item claims follow the existing checklist rules. Pins affect list discovery,
not task urgency or responsibility.

Started lists keep existing checklist bounds, guarded edits, offline replay,
delete/Undo and task-retention rules. Increasing those bounds is separate from
introducing reusable content.

## Adventures are permissive

An adventure can include either kind of list as an existing root. Users may
explicitly finish an adventure even with unfinished tasks or unavailable items.
The confirmation explains that remaining work stays open. Finishing neither
checks items nor grants task-completion credit. It never discards groceries.
The exact active-adventure version prevents a stale confirmation from closing a
different or newly edited adventure.

Starting a list does not start or replace an adventure. The adventure editor can
link it as existing work. Voice matching of saved names, saved content inside AI
planning and saving entire adventures remain follow-up work.
