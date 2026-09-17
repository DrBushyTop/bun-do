---
version: 1
slug: "p-src-main-java-fi-bundo-ui-voicesheet-kt-f1c280f2"
primary_target: "src/BunDo.Android/app/src/main/java/fi/bundo/ui/VoiceSheet.kt"
related_targets: ["src/BunDo.Android/app/src/main/java/fi/bundo/ui/InboxApp.kt"]
---

# Voice capture

Mode: Operate. Preserve the native evergreen notebook and Material controls.

The September 16 owner revision moves model setup and recording history into
Settings. The queue button starts recording after permission; Stop leads to a
read-first task review with manual editing and optional AI revision before Save.
Keep the recording surface short. Setup, optional audio retention and draft
recovery stay out of its primary path. No normal-user recording import.

Follow DESIGN.md for the current capture/settings contract. Preserve account and
destination isolation and durable transcript drafts. Native phone captures cover
Finnish and English, including large text. Physical-phone performance remains
unmeasured. Implementation evidence and completion review belong in the slice issue.

## Direction contract

The owner selected option A on September 17. This extends the evergreen notebook,
not a new visual theme. Read the task before deciding whether to edit or save it.
Avoid the old form-first page and the shallow image strip.

Use a full-width 220dp adventure scene with a bottom fade, followed by the title
and plain task content. Keep the queue icon-led without image thumbnails. Reuse
cached account artwork; a bundled scene covers missing art without a network call.

One content scroller owns the image and task text. The review header keeps Close,
manual Edit and its menu outside that scroller. Save and revision actions stay
in the persistent footer. During preview, Accept changes and Keep current replace
those footer actions. Keep the direct steps readable after adding several in one
revision, and return accepted content to the top instead of retaining a stale
offset. The native task view uses the same reading composition with persistent
Edit and the applicable Complete, Reopen or Restore action. Checklist roots
derive completion from their children.

Review caps at 840dp, with a 720dp reading column. Below 480dp available height
and at least 600dp width, place reading content beside a 160dp high scene. A
narrower short window uses a 96dp scene above the text. Short-height review
actions sit side by side; taller windows stack them. Keep the normal 220dp scene
for task detail.

AI revisions start from current manual edits. Show changed title, description
and all direct steps before acceptance, with localized Current, Proposed and
Removed labels in addition to strikethrough and color. Accept changes updates
the draft only; Save creates the task. Preserve the current draft and original
transcript on cancellation, rejection or failure.

Task management lives in grouped menus; original text and attribution come last.
The review menu places original transcript after Discard draft. Preserve native
Material controls, Finnish and English copy, large text and account-safe draft
recovery. Cached art and the bundled fallback do not change task data.
