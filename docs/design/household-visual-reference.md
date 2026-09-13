# Household visual reference for v1

On September 13, 2026, the owner selected the interactive household study as the
direction for the native app. This extends the existing evergreen Material
direction; it does not replace the Android stack with a web client.

The runnable study is retained on the `prototype/household-v1` branch at
`src/BunDo.Android/prototype-ui/`. Run its documented local server command from
that branch. Keep prototype code out of the production branch.
[Native interface and accessibility](https://github.com/DrBushyTop/bun-do/issues/34)
owns the handoff and records the exact reference commit and verification.

## Keep in native v1

- Compact rabbit wordmark and Settings entry, with Queue, Activity and Together
  destinations. Voice capture stays beside Type within thumb reach. A compact
  All/Unclaimed/Mine selector beside the task count replaces a full filter row.
- Flat task rows with illustrated task icons. Do not repeat category names such
  as "Bike" beside an already descriptive icon and title. Categories are visual
  cues, not a new areas, tags or filtering model.
- Reserve row metadata for urgency, due/snooze, checklist progress and claimant.
  Keep the person's name beside the koala or bunny. Fixed profile illustrations
  are in scope; a profile editor is not.
- An explicit reorder mode with drag handles and accessible move actions.
  Reorder the full queue, not an ambiguous filtered subset. Dragging must not
  trigger swipe completion. Announce the new position and support cancellation.
- Show new-task placement before Save and confirm it afterward. Normal captures
  append. Explicit urgency and soon-due capture are exceptions, not a reason to
  re-sort existing work. The study treats overdue, today and tomorrow as soon
  due. This threshold is a proposed default for the next walkthrough.
- Detail shows creator and creation time, then last modifier and modification
  time as compact inline label/name/date rows. Omit the duplicate modification
  row until a change exists. Format exact dates in the selected language and
  expose time-zone detail without a separate explanatory paragraph. Creation
  attribution survives later edits, claims and ordering.
- Description and one-level steps stay editable. Keep manual add/edit/remove,
  editable AI split previews and dictated steps with a text fallback. AI cleanup
  remains separate from speech transcription. Canned output in the study is not
  a shipped AI integration.
- Together keeps household counts, a simple weekly/monthly chart, milestones
  and the weekly streak. Chart buckets use the same first-completion and server
  acceptance rules as the totals. No member ranking or historical queue
  reconstruction is implied.

## Motion

Completion alternates a short rabbit bow, hop and checkmark stamp. Claiming adds
a small ownership stamp. Filing means adding a task, not archiving it; a brief
paper-into-tray motion acknowledges the save.

A claimed task's koala or bunny makes two small pencil strokes when its claim
first appears, then rests. Do not replay it on every recomposition or run idle
loops across the queue. The name and static working mark carry the state.

Commit the local action before animation. Keep Undo immediately usable and never
wait for an effect before accepting the next action. Routine effects finish
within 420 ms; the quieter two-stroke working pose can take up to 900 ms.
Settings can disable decorative motion. System reduced motion overrides the app
preference, retaining static confirmation. No sound, combos, streak penalties
or full-screen celebration is required.

## Native handoff, not HTML parity

Use the approved rabbit vectors and native Material color, type, spacing and
interaction roles. The study's web shadows, exact pixels and provisional
pastels do not override `DESIGN.md`. The owner chose light-only illustrated V1;
remove the theme switch from this direction, but retain existing native dark
code for later adaptation. Implement accent colors as named, contrast-checked
roles. Do not copy keyword-based classification as
an AI capability or add category persistence just to reproduce the study.

Scene illustrations depict a calm royal martial-arts master in a simple gi and
tied belt, with a restrained crown. Everyday work is the Way of the Bun, not a
nursery-rabbit theme. This illustration direction does not change the approved
uncrowned app logo.

The study includes generated task-detail backgrounds for storage, bicycle
maintenance and pet care. These are examples for visual approval, not a
requirement to generate images per task. Keep artwork to the header's right
side with one continuous fade behind text. Do not put separate white bands
behind metadata or crop off the subject to fill the header. Bundle any selected
assets locally, retain prompt/provenance, and show the ordinary readable header
when an illustration is unavailable. The queue stays plain.

Real task rules win over shortcuts in the study. Checklist roots derive
completion from their items; claims belong to actionable leaves. Completion of
another person's task needs confirmation. Sync conflicts, account isolation,
retained recovery and pending edits remain required.

The remaining walkthrough must cover completed/reopen, delete/restore,
due/snooze/repeat/reminder setup, real AI pending/failure/stale previews,
account/join flows and offline reconciliation. These already belong to v1;
their absence from the browser study does not defer them.

The optional [Bun world and quests study](bun-world-and-quests.md) is post-core/V3
exploration. It does not add a release blocker, XP system or autonomous agent
to V1.

## Prototype feature ownership and source

The pinned reference is commit `c0449a18b1eacc4c9878dc2b8478e23c7ef22b73` on
`prototype/household-v1`. These links open that exact source, not a moving branch.
Start with the [run instructions and simulation limits](https://github.com/DrBushyTop/bun-do/blob/c0449a18b1eacc4c9878dc2b8478e23c7ef22b73/src/BunDo.Android/prototype-ui/design-notes.md).
The demo loads `index.html`, then `revision.js`, `world.js` and `interactions.js`;
later function definitions override earlier ones. Read the final definitions.
Use the table to find the owning ticket, not to infer implementation readiness.
GitHub dependencies and ticket state remain authoritative.

| Prototype behavior | Owning ticket | Source entry points |
| --- | --- | --- |
| App shell, compact queue/filter, Settings, navigation, typed capture and light-only layout | [#34](https://github.com/DrBushyTop/bun-do/issues/34) | [revision.js](https://github.com/DrBushyTop/bun-do/blob/c0449a18b1eacc4c9878dc2b8478e23c7ef22b73/src/BunDo.Android/prototype-ui/revision.js): render, editDraft, showEditor, settings; revision.css and index.html |
| Claim/release, fixed koala/bunny avatars and one-shot working pose | [#34](https://github.com/DrBushyTop/bun-do/issues/34) | [revision.js](https://github.com/DrBushyTop/bun-do/blob/c0449a18b1eacc4c9878dc2b8478e23c7ef22b73/src/BunDo.Android/prototype-ui/revision.js): person, claim; revision.css |
| Full-queue drag, move buttons, keyboard actions, cancellation and announcements | [#34](https://github.com/DrBushyTop/bun-do/issues/34) | [interactions.js](https://github.com/DrBushyTop/bun-do/blob/c0449a18b1eacc4c9878dc2b8478e23c7ef22b73/src/BunDo.Android/prototype-ui/interactions.js): toggleReorder, bindReorder, finishDrag, moveTask |
| Completion swipe/button, Undo, bow/hop/stamp, claim and filing effects, reduced motion | [#34](https://github.com/DrBushyTop/bun-do/issues/34) | [revision.js](https://github.com/DrBushyTop/bun-do/blob/c0449a18b1eacc4c9878dc2b8478e23c7ef22b73/src/BunDo.Android/prototype-ui/revision.js): complete, bindSwipes; interactions.js notify/motionAllowed and interactions.css |
| Description/date/urgency editing, append-by-default placement and compact creator/change history | [#24](https://github.com/DrBushyTop/bun-do/issues/24) | [interactions.js](https://github.com/DrBushyTop/bun-do/blob/c0449a18b1eacc4c9878dc2b8478e23c7ef22b73/src/BunDo.Android/prototype-ui/interactions.js): initialPosition, expedited, placementHint, taskHistory; revision.js showEditor/save/detail |
| Manual checklist add/edit/remove and visible checklist progress | [#23](https://github.com/DrBushyTop/bun-do/issues/23) | [revision.js](https://github.com/DrBushyTop/bun-do/blob/c0449a18b1eacc4c9878dc2b8478e23c7ef22b73/src/BunDo.Android/prototype-ui/revision.js): renderSteps, addStep, detail; interactions.js toggleItem |
| Voice capture, stop, correction and typed fallback | [#38](https://github.com/DrBushyTop/bun-do/issues/38) | [revision.js](https://github.com/DrBushyTop/bun-do/blob/c0449a18b1eacc4c9878dc2b8478e23c7ef22b73/src/BunDo.Android/prototype-ui/revision.js): record, stopRecording, editDraft; simulated recording, reuse completed speech slice #26 |
| AI cleanup retained separately from transcription | [#27](https://github.com/DrBushyTop/bun-do/issues/27) | [revision.js](https://github.com/DrBushyTop/bun-do/blob/c0449a18b1eacc4c9878dc2b8478e23c7ef22b73/src/BunDo.Android/prototype-ui/revision.js): stopRecording/editDraft show corrected sample text; no cleanup request state machine implemented |
| Editable/selectable AI splits, dictated steps, accept/cancel preview | [#28](https://github.com/DrBushyTop/bun-do/issues/28) | [revision.js](https://github.com/DrBushyTop/bun-do/blob/c0449a18b1eacc4c9878dc2b8478e23c7ef22b73/src/BunDo.Android/prototype-ui/revision.js): suggestSplit, acceptSplit, recordSteps, showEditor, detail |
| Activity, shared weekly/monthly chart, lifetime milestones and weekly streak | [#32](https://github.com/DrBushyTop/bun-do/issues/32) | [revision.js](https://github.com/DrBushyTop/bun-do/blob/c0449a18b1eacc4c9878dc2b8478e23c7ef22b73/src/BunDo.Android/prototype-ui/revision.js): render, renderStats; sample counts are not canonical statistics |
| Royal martial-arts Bun task-header artwork, continuous fade and fallback | [#34](https://github.com/DrBushyTop/bun-do/issues/34) | [interactions.js](https://github.com/DrBushyTop/bun-do/blob/c0449a18b1eacc4c9878dc2b8478e23c7ef22b73/src/BunDo.Android/prototype-ui/interactions.js): sceneForTask; revision.js detail; interactions.css; art/ARTWORK.md |
| Integrated dojo header, hammock/paperwork/joy moods, world visibility and reorder hiding | [#54](https://github.com/DrBushyTop/bun-do/issues/54) | [world.js](https://github.com/DrBushyTop/bun-do/blob/c0449a18b1eacc4c9878dc2b8478e23c7ef22b73/src/BunDo.Android/prototype-ui/world.js): worldMood, updateWorldHeader, toggleWorld, openWorld; world.css |
| Adventure preview/accept/leave, named phases and source task references | [#54](https://github.com/DrBushyTop/bun-do/issues/54) | [world.js](https://github.com/DrBushyTop/bun-do/blob/c0449a18b1eacc4c9878dc2b8478e23c7ef22b73/src/BunDo.Android/prototype-ui/world.js): questOptions, questPhases, openQuests, showAdventure, acceptQuest, leaveQuest |
| Editable advisory stars/time, detail/header progress, reopening and completion celebrations | [#54](https://github.com/DrBushyTop/bun-do/issues/54) | [world.js](https://github.com/DrBushyTop/bun-do/blob/c0449a18b1eacc4c9878dc2b8478e23c7ef22b73/src/BunDo.Android/prototype-ui/world.js): difficultyMarkup, saveQuestEstimate, questProgress, observeQuestProgress, toggleQuestPhase; world.css |

[Artwork and generation prompts](https://github.com/DrBushyTop/bun-do/blob/c0449a18b1eacc4c9878dc2b8478e23c7ef22b73/src/BunDo.Android/prototype-ui/art/ARTWORK.md) travel with the
reference. Bundle approved assets rather than calling an image generator at
runtime. The fixtures, keyword categories, mood chooser and simulated recording
controls are demonstration tools, not additional production features.

The native walkthrough and release verification tickets, [#40](https://github.com/DrBushyTop/bun-do/issues/40)
and [#35](https://github.com/DrBushyTop/bun-do/issues/35), verify this handoff.
Completed capture, speech and claim/order foundations remain closed. Their
remaining visual adoption belongs to the open tickets above. Profile editing
and combo animations remain separate V2 ideas, not implemented prototype flows.
