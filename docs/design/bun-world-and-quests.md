# Bun world and household quests

This records the owner's September 13, 2026 post-core/V3 exploration. It is not
a V1 release requirement. The first candidates are a shared Bun journey and
optional generated quests. The larger assistant/agent ideas remain uncommitted.

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

## Quests refer to tasks

A quest supplies a title, optional flavor text and references to existing tasks.
Use "Seikkailu" in Finnish and "Adventure" in English rather than trip or retki.
It neither copies tasks nor changes their ownership, due dates, order or
lifecycle. People preview and accept a proposed group, skip it or leave it
without consequences. Do not regenerate an accepted quest in the background.

Start with deterministic sample groups to evaluate the interaction. Later, AI
can suggest a title and a small set of existing task IDs. Validate every ID,
current household access and lifecycle when accepting. Keep raw task text out
of diagnostics. No access to calendar/location, inferred plans, purchases or
bookings is implied.

Completion and reopening come from the task model. A removed task becomes
unavailable rather than silently complete; a quest cannot restore it. Checklist
items must not multiply the household journey count. The quest page may show
its checklist progress separately.

Give adventures named phases, keeping the actual task or checklist text visible
under each name. Show completed/total phases and a progress bar both in the
adventure and its header entry. Progress reflects current task state, including
reopening. Ordinary renders must not replay a completion celebration. When the
last phase finishes, Bun gives a short bow in the adventure; the header sign
gives a brief seal animation when it next becomes visible. Motion-off settings
retain the completed label and filled bar.

Advisory difficulty of one to three stars and approximate minutes are allowed.
They describe estimated work, not personal skill or rewards. Explain uncertainty
and let people edit both values. The study labels canned values as examples of
future AI suggestions. These estimates do not weight shared journey progress.

## Small implementation after the core works

1. Build a pure presentation reducer from the current actionable queue, overdue
   count and canonical first-completion facts to a scene and journey stop.
   Do not duplicate command processing or introduce a game engine.
2. Reuse accepted root-completion identities for shared progress. Deduplicate
   retries and devices; reopening/recompletion must not farm progress. Preserve
   existing acceptance-date statistics. Pending offline celebration is local
   feedback, not accepted household progress.
3. Keep route/episode progress durable so app restarts and period boundaries do
   not erase a journey or punish a quiet week. Prototype daily/weekly framing
   before choosing a reset rule. No task-size weighting or XP is needed.
4. Store a small quest record with household, title, stable source task/checklist
   references, phase names, editable estimates and acceptance state. Normal task
   projections provide current progress. Do not use checklist positions as
   durable identities.
5. Bundle approved compressed scenes for offline use. Animate separate character
   layers or a short authored sequence in Compose, not by moving an entire
   bitmap forever. Limit animation to the visible scene; use a static
   reduced-motion alternative.
6. Add AI proposal generation only after the static grouping flow is useful.
   Use the existing durable AI request and stale-preview conventions rather
   than giving the mascot authority to mutate arbitrary tasks.

The prototype uses six overdue tasks as the paper-scene threshold. This is a
test value, not calibrated behavior or a production limit. Its in-memory
checklist references use positions and are not a durable quest data model.

## Explicitly not adopted

XP, difficulty-based progress weights, boss health bars, percentage "peacefulness", momentum
decay, cosmetic economies, procedurally expanding dojos and autonomous agents
are not implied by this study. Existing V2 profile/combo ideas remain separate.
Do not replace the simple V1 weekly streak with a new game metric.
