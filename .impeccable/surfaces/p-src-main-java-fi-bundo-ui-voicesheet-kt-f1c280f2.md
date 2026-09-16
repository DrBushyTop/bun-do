---
version: 1
slug: "p-src-main-java-fi-bundo-ui-voicesheet-kt-f1c280f2"
primary_target: "src/BunDo.Android/app/src/main/java/fi/bundo/ui/VoiceSheet.kt"
related_targets: ["src/BunDo.Android/app/src/main/java/fi/bundo/ui/InboxApp.kt"]
---

# Voice capture

Mode: Operate. Preserve the native evergreen notebook and Material controls.

The September 16 owner revision moves model setup and recording history into
Settings. The queue button starts recording after permission; Stop leads to an
editable title, description and optional checklist preview before Save. Keep the
recording surface short. Setup, optional audio retention and draft recovery stay
out of its primary path. No normal-user recording import.

Follow DESIGN.md for the current capture/settings contract. Preserve account and
destination isolation and durable transcript drafts. Native phone captures cover
Finnish and English, including large text. Physical-phone performance remains
unmeasured. Implementation evidence and completion review belong in the slice issue.
