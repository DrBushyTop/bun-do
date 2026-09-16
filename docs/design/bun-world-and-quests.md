# Bun world and household quests

The owner confirmed this design on September 16, 2026, extending the
September 13 browser study. V1 is released according to the owner. This work
can ship independently from now on; it does not wait for V2 or V3.
[The Bun world issue](https://github.com/DrBushyTop/bun-do/issues/54) owns
implementation status and evidence.

Preserve the prototype's interaction and visual direction. The static dojo
garden was already adopted for V1 on September 15. This design adds a shared
journey, AI-generated adventures, a guided adventure creator and reusable
artwork. It is not evidence that those additions are implemented.

## Bun has a life, not a score

The household shares one Bun. A clear queue lets Bun sleep in a hammock.
Several overdue tasks can show Bun thoughtfully sorting papers, perhaps holding
one upside down. A completed task prompts a brief happy run before Bun settles.
No miserable face, guilt message, decay, penalty or personal ranking.

These are interpretations, not alarms. The actual overdue count remains visible
through normal task UI. A failed sync must not make Bun appear worried or
pretend a shared task was accepted. Motion is optional, pauses offscreen and
never delays capture or Undo.

## Protect the task list

The owner preferred a continuous scene behind the whole top section, including
the wordmark, instead of a separate expandable card. Remove the route heading,
location caption and expand/collapse control. Put a visual quest signpost at the
bottom of this scene. Keep filters in a compact selector beside the task count.
Hide the scene during reordering and keep an optional world-off setting. Cap
header height on short screens so capture and the list remain reachable.

Bun is a calm royal martial-arts master, with a simple gi, tied belt and small
restrained crown in scene illustrations. The approved app logo stays unchanged.
No weapons or combat are needed; household chores are the Way of the Bun.
Use a Japanese-inspired timber dojo, stone courtyard, moss and restrained
ink-green and warm-paper colors. The calmness is secular. A crown added to a
generic cottage scene does not establish the Bun Do setting.

The study offers a dojo courtyard and sample rest, paperwork and joy scenes.
Its scene chooser is a prototype control, not a permanent mood-settings panel.
The study uses generated still backgrounds with small CSS transitions; it is
not frame-by-frame character animation or a moving five-stop journey yet.

## One shared adventure

Use "Seikkailu" in Finnish and "Adventure" in English rather than trip or retki.
An adventure gives a recognizable outcome or useful work session a title,
optional flavor text and references to main tasks. A main task is a root task,
including a root with a checklist. Playful phase names never hide the actual
task or checklist text.

Keep the prototype's two-proposal chooser and one accepted adventure per
household. Either member can accept it, contribute without joining separately,
and edit phase names or advisory estimates. Acceptance does not claim tasks.
An accepted adventure neither copies existing tasks nor changes their
ownership, due dates, order or lifecycle.

People preview, accept, skip or leave without penalties. Leaving requires
confirmation that the adventure ends for both members but leaves the tasks
intact. Keep a completed adventure visible until either member dismisses it
and returns to suggestions. Do not silently replace an accepted adventure
because another member accepted something concurrently.

Starting, replacing or editing the shared adventure requires connectivity.
After acceptance, normal task work remains offline-capable, with cached
artwork and locally projected progress. Validate current household membership,
source identities and lifecycle when accepting or changing references. Never
use a checklist position as a durable identity.

## AI suggestions and expiration

AI generation replaces the prototype's canned proposals. The automatic set
groups existing tasks only, around a shared outcome or useful work session.
Do not invent tasks to fill the chooser. An empty queue offers the guided
creator instead of manufacturing obligations.

Both members see the same suggestion set. Unaccepted suggestions have a
24-hour lifetime. Once expired, generate a replacement during the next online
app visit, not on a timer while nobody is using the app. Expired suggestions
cannot start a new adventure; without connectivity or after generation failure,
explain that fresh suggestions are unavailable and allow retry when online.
Generation must not affect normal task work.

Accepted adventures never expire or regenerate in the background. Refreshing
the suggestion set changes neither an accepted adventure nor its source tasks.
An accepted adventure remains until completed and dismissed or explicitly left.
Coordinate concurrent requests so two devices cannot publish competing sets.

## Help me plan an adventure

Provide an explicit creator alongside the rotating suggestions. Ask
"What would you like to get done?", with optional available time and starter
prompts for people who need guidance. For example, "Make the balcony ready for
summer" produces an editable adventure draft with suggested work.

Clearly distinguish references to existing tasks from proposed new tasks.
Nothing enters the household queue until the user confirms
"Add tasks and start adventure". Create only the approved work through ordinary
task commands and link it to the adventure; never duplicate referenced tasks.
Do not report a successful start until its required tasks and references are
accepted. Interrupted acceptance must remain recoverable without duplicate task
creation, not silently claim all-or-nothing behavior from unrelated commands.

The explicit creator may propose new tasks. Scheduled suggestion replacement
may not. Neither flow has calendar/location access or authority to make
purchases, bookings or inferred plans outside the user's request.

## Main-task progress, live checklists

Adventure progress counts distinct main tasks, not phases or checklist items.
Show completed/total main tasks and a progress bar in both the adventure and its
header entry. An adventure with three main tasks shows 1/3 complete regardless
of their checklist sizes. Do not freeze checklist contents at acceptance.

Follow source text and current completion state. Checklist additions and
removals do not change the adventure's denominator. They affect its numerator
only if the main task changes completion state. If unfinished checklist work
reopens a completed main task, adventure progress can fall from 2/3 to 1/3.
Bun's permanent journey credit does not fall.

A deleted or cancelled main task becomes unavailable, never silently complete.
Either member can explicitly remove it from the adventure or choose a
replacement. Removing it recalculates adventure progress but grants no
completion credit. The adventure cannot restore deleted work. With no
referenced main tasks remaining, do not treat an empty adventure as completed.

Ordinary renders must not replay a completion celebration. When all referenced
main tasks finish, Bun gives a short bow in the adventure; the header sign
gives a brief seal animation when it next becomes visible. Motion-off settings
retain the completed label and filled bar. Preserve acknowledgements across
sync and app restarts rather than replaying feedback on every visit.

Advisory difficulty of one to three stars and approximate minutes are allowed.
They describe estimated work, not personal skill or rewards. Explain uncertainty
and let people edit both values. These estimates weight neither adventure
completion nor shared journey progress.

## A persistent journey

Bun travels through authored locations, with the dojo as home. The initial
pacing is five locations per journey and five new first root-task completions
per location. This is a tunable presentation choice, not XP or task-size
weighting. Completions outside adventures count too.

Start from credits accepted after the household enables the journey, not its
historical total. Reuse canonical first-completion identities and server
acceptance semantics. Checklist items, retrying on another device and
reopening/recompleting a task never grant extra credit. Pending offline feedback
is not accepted household progress.

Finishing a route preserves it in a simple journey history and starts the next
authored route. When available routes are exhausted, Bun rests at the destination
and normal completion counts continue. Do not generate an endless map.
Calendar boundaries, app restarts, a hidden scene and quiet weeks never erase
journey progress. Bun's current activity is separate from journey progression.
Keep existing statistics and the V1 weekly streak unchanged.

## Artwork on every adventure

Every proposal and accepted adventure has artwork. Illustrate a generic theme,
not exact task text. For example, organizing storage can show royal Bun sorting
scrolls in a dojo storeroom. Use the approved Bun appearance and reference art;
do not change the logo.

Search a shared cross-household catalog using generic theme, setting, Bun's
activity and style version. Start with tags and descriptions, not a separate
visual-similarity system. Reuse a suitable image before generating a new one
through Foundry with approved character references.

Build image prompts from generic visual briefs, not raw task text. Names,
addresses and other household details must not enter those prompts. Shared
catalog metadata contains no household IDs, task links or original requests.
Private adventure records retain their own image references; catalog access
must not expose another household's use of an image.

Store generated images in private Blob Storage with separate catalog metadata.
Cross-household reuse does not require public blobs. Use authenticated delivery
and cache downloaded artwork on Android. Expiration of a suggestion set does
not delete reusable images. Do not reuse transient snapshot storage or its
cleanup policy for this library.

Artwork generation never blocks a proposal or an accepted adventure. Show a
bundled, on-theme illustration while generating; replace it when ready. On
failure or refusal, retain the fallback and offer Retry. Once assigned, keep
the selected generated or catalog artwork stable for an accepted adventure.
The initial fallback-to-generated transition is allowed after acceptance.

## Implementation boundaries

Keep domain rules in `BunDo.Domain`, Android persistence in `data/` and screens
in `ui/`. Derive scene activity and journey position without duplicating command
processing or introducing a game engine. Use canonical task projections for
adventure progress and stable root identities for references.

Reuse durable AI request, stale-result and authorization conventions, but give
household proposal batches their own identity rather than pretending they are
cleanup requests for a source task. Keep task text, prompts and generated
descriptions out of diagnostics. Late or repeated results must not replace
accepted choices, duplicate approved work or cross account boundaries.

Extend the existing Bun Do inference account through
`infra/modules/ai-inference.bicep` for the image model. Bicep owns its pinned
model/version/SKU/capacity and identity grants. Preserve managed-identity
authentication and explicit version upgrades. Keep initial Azure capacity low,
without adding product AI quotas. The owner permits a scoped reduction to the
OpenCode image deployment's allocation if needed to free regional quota.
Recheck availability before any change; do not fold OpenCode resources into
the Bun Do deployment template or bypass its resource-group safeguards.

Bundle approved compressed fallback scenes for offline use. Animate separate
character layers or a short authored sequence in Compose, not an entire bitmap
moving forever. Limit animation to the visible scene and retain static
reduced-motion feedback. Finnish/English, large text, task access and Undo
remain required.

The prototype uses six overdue tasks as the paper-scene threshold. This remains
a test value, not calibrated behavior or a production limit. Its two proposals,
artwork and estimates are canned; acceptance lives only in browser memory.
Neither that state model nor its positional checklist references are production
persistence, real AI, synchronization or native accessibility evidence.

## Explicitly not adopted

XP, difficulty-based progress weights, boss health bars, percentage "peacefulness", momentum
decay, cosmetic economies, procedurally expanding dojos and autonomous agents
are not implied by this study. Existing V2 profile/combo ideas remain separate.
Do not replace the simple V1 weekly streak with a new game metric.
