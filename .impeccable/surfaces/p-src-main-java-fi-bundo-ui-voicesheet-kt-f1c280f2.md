---
version: 1
slug: "p-src-main-java-fi-bundo-ui-voicesheet-kt-f1c280f2"
primary_target: "src/BunDo.Android/app/src/main/java/fi/bundo/ui/VoiceSheet.kt"
related_targets: ["src/BunDo.Android/app/src/main/java/fi/bundo/ui/InboxApp.kt"]
---

# Offline speech

Mode: Operate. Extend the native inbox without changing its visual system.
The screen contract specifies a voice FAB beside Type and a local capture sheet.

## Direction contract

THESIS: Capture a task without cloud access; installation and recognition failure
must never trap the user away from typing.

OWN-WORLD: Keep the existing evergreen Material roles, native bottom sheet,
system typography and flat recovery list. Use recording error color with text,
not color alone. No new brand assets or custom animation.

STORY: Explain the one-time download, request microphone access only on Record,
show recording and local transcription, then open the committed task. Recovery
names expiry and offers retry, explicit export and delete.

FIRST VIEWPORT: The queue keeps Type at the bottom, beside one microphone FAB.
The sheet starts with its title and privacy text, followed by the current action.
Progress, Stop and Type remain reachable as text scales; the sheet scrolls.

FORM: Existing-surface extension of the approved native capture-sheet contract.
No concept seed or replacement visual world applies.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

No generated raster ships. Verify English light and Finnish dark phone captures,
including 2.0 font scale, recording, recovery and permission denial. Native
screenshots, not an HTML detector, are the visual evidence. Physical-phone
performance and recognition quality are explicitly unmeasured.

## Completion evidence

Native captures cover English light, Finnish dark at 2.0 font scale, landscape,
recording, silence, denial, cancellation, recovery and saved task detail.
The finish review found no rendered contradiction and required documentation
to reflect the shipped voice controls. PRODUCT.md, DESIGN.md and the filled
recording-action sidecar preview now match; the verdict scored that fix resolved
and returned ship. The slice issue records the review scope and limits.
