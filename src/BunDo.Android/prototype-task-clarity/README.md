# Task detail and artwork study

Throwaway browser prototype for issue #70, based on master `46abf1ee`.
Keep this on `prototype/task-detail-clarity`; do not merge it into the app.
No native app, release or design-contract changes are included.

## Question

Can a task be read, edited and completed without exposing every management
command? Compare three structures in the existing paper/evergreen Material
identity. The later owner request adds colored task cues and artwork for
individual tasks, in the queue and detail views.

- A: focused reading page with Edit and Complete below, other actions in a menu.
- B: inline title/description editing and a completion checkbox.
- C: a detail sheet over the task list.

The home screen uses the same per-task imagery in all options. Opening a row
shows that option's detail structure. Queue filter/reorder, capture and other
app destinations are outside this comparison and open an explicit scope note.
The AI review is a shared proposed flow rather than three competing AI flows.

## Run and share

From the worktree root:

```sh
python3 -m http.server 4881 --bind 127.0.0.1 --directory src/BunDo.Android/prototype-task-clarity
```

Hosted at `https://m1.saiga-bleak.ts.net/task-clarity/` inside the tailnet.
The existing `/ui-clarity` prototype and root T3 Code route remain untouched.
Server PID: `/tmp/bundo-task-clarity-server.pid`.
Stop only this server with `kill "$(cat /tmp/bundo-task-clarity-server.pid)"`.
Remove only its route with `tailscale serve --https=443 --set-path /task-clarity off`.
Never reset Tailscale Serve to remove a prototype.

URL controls:
- `variant=A|B|C`. The bottom arrows and keyboard arrows cycle variants.
- `screen=queue|task|checklist|review`.
- `lang=fi|en`, `text=large`, `mode=compare|focus`.

Desktop compares three views; phones show one. Text inputs keep their arrow
keys. Screen/language changes reset sample data. Task state stays in memory
while navigating within an option; reload resets everything. The optional
Prototype state disclosure and console show the current simulation state.

## Behavior and limits

The app calls no microphone, API or storage service. All commands modify only
sample data. The three voice instructions produce predetermined changes to the
current draft. Manual edits are preserved. Rejection and simulated offline/error
results keep the original draft. Accepting a revision and adding the task are
separate actions. The generated artwork is bundled preview imagery, not a
runtime task-image service. Color/icon choices are fixture mappings, not AI
classification or new task categories.

Native semantics remain the implementation target. Browser scrolling, focus,
font scaling and image rendering are not native Android accessibility evidence.
A follow-up implementation must retain per-account draft ownership, cancellation,
late-result checks, recoverable text, child-task rules and explicit acceptance.
Do not add a permanent chat transcript to the task screen for voice revision.

## Current app findings

- `HouseholdIllustrations.kt` draws TaskCue with one primary tint. Its keyword
  fallback explains why the owner's cloth task has a generic green icon.
  IllustratedTaskHeader selects bundled imagery only for bike, pet and storage.
  It does not generate a task-specific picture.
- The earlier `prototype/household-v1` uses colored category cues and selected
  task-detail art, documented in `docs/design/household-visual-reference.md`.
  That contract did not require per-task generation. The owner's new request
  explicitly explores images for each task here.
- `FoundryCleanupProvider.CaptureInstructions`, `CaptureAnalysisFunction` and
  `VoiceDraft` already support up to 16 direct checklist items when dictation
  enumerates products or explicit steps. Single actions are not padded with
  invented steps. The local-only inbox has no checklist capture.
- `VoiceController` invokes analysis only for a shared workspace when configured,
  enabled and connected. `VoiceSheet.VoiceReview` exposes editable title,
  description and items. `RecordingStore` saves only after acceptance.
- Voice-based revision of a current draft does not exist. Its proposed contract
  needs both the current edited draft and a separate revision instruction. The
  initial transcript remains recoverable; it is not overwritten by a correction.

## Artwork

`assets/cloth-royal-source.png` is a new Azure Foundry image edit generated on
September 17, 2026 through the configured `foundry-imagegen` CLI with Azure CLI
authentication, deployment `gpt-image-2-1`, requested 1536 × 1024, high quality.
The reference was `prototype/household-v1`'s `art/bike-royal.webp`, converted to PNG.
It preserves the existing royal martial-arts rabbit and replaces the scene with
microfiber cloths and eyeglasses. The exact prompt is `assets/cloth-prompt.txt`
and is embedded in the source PNG. The served WebP is 960 × 640, quality 84.

`bike-royal.webp` and `storage-royal.webp` are reused without visual edits from
`prototype/household-v1` at `c0449a18`, under `src/BunDo.Android/prototype-ui/art/`.
Their original generation prompts remain in that branch's `art/ARTWORK.md`.
`dojo-garden.webp` and `bun.svg` are existing assets copied from the current app
and approved logo. WebP provenance sidecars record each source. No logo was
regenerated. The bike and storage images are reused examples associated with
individual demo tasks, not newly generated for those tasks at runtime.
