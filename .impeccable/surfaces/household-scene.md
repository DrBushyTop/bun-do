---
version: 1
slug: "household-scene"
primary_target: "src/BunDo.Android/app/src/authCheck/java/fi/bundo/ui/HouseholdScene.kt"
related_targets: ["src/BunDo.Android/app/src/main/java/fi/bundo/ui/InboxApp.kt", "src/BunDo.Android/app/src/authCheck/java/fi/bundo/ui/SharedQueue.kt"]
---

# Household scene

Mode: Operate. This is the pinned prototype's integrated native header, extending
the notebook/Material 3 system. Code-led, with no replacement identity or new comp.

## First viewport

One illustration continues behind the unchanged Bun Do wordmark. The adventure
signpost sits at its foot, showing current main-task progress. Task count, compact
filters and the list follow. Capture remains fixed and reachable. No location
caption, route heading or collapse control competes with the work.

## Form and materials

Reuse the approved dojo, hammock, paperwork and joy artwork. Keep warm paper,
ink-green controls and the existing Material type roles. The signpost is a native
clickable warm-paper signpost inset from the scene edges, with a small scroll
in a sage-colored seal. This follows the prototype's signpost rather than a
full-width settings row. Keep its existing native type and touch target.

## Content and hierarchy

The task list remains the primary work area. The signpost opens the existing
shared adventure and creator flows. Actual task names and checklist contents stay
in those flows, while the entry reports completed/total main tasks.

## Signature interaction

A completed adventure keeps a filled progress bar and completed label. A short
seal pulse is acknowledged durably. Bun's brief joy scene follows accepted
completion events, not pending commands. No bitmap loops or animation gates actions.

On an active adventure, the approved Bun silhouette marks the end of the
completed trail. It moves only when task progress changes. An occasional amber
reflection crosses the completed portion, with a small glow at Bun's feet.
The uncompleted trail never fills on its own. Completed, compact, world-hidden
and reduced-motion states keep a static trail. Backgrounding stops the effect.

## Adaptation and access

Hide only decorative art during reorder, on short screens, at large font scales
and when world-off is selected. Preserve the signpost, ordinary task controls and
capture. Motion follows foreground lifecycle and app/system preferences, with
static equivalents. Finnish and English text must wrap without hiding controls.

## Verification boundary

Inspect two Android profiles, large Finnish text, world-off, reorder, completed
and empty states. Test cached artwork and the existing creator integration.
The scene never owns task state, navigation persistence or the command worker.
