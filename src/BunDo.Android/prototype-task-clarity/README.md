# Task detail and artwork study

Throwaway browser prototype for issue #70, based on master `46abf1ee`.
Keep this on `prototype/task-detail-clarity`; do not merge it into the app.
No native app, release or design-contract changes are included.

## Current question, September 17, 2026

The owner retained A's read-first task controls but found the 96px faded header
too shallow. This round compares only artwork placement, not those task controls:

- A, faded scene: a 220px edge-to-edge picture above the title, with a clear center.
- B, title first: the complete 3:2 scene below the title, with no crop or overlaid text.
- C, picture alongside: a 230px portrait crop beside the title. Large text stacks
  the title above a 190px landscape crop instead of squeezing the text.

Every option reuses the same bundled adventure scene for a fair comparison.
The picture scrolls with the task content; the primary footer stays available.
Queue rows retain colored icons, without task pictures. Original details remain
last in the action menus. These are browser options, not native app changes.

One AI revision can add several direct checklist steps. The selected voice sample
explicitly asks for three steps in one instruction. All three appear in one change
review and enter the draft together on acceptance. Existing steps and manual edits
remain; repeating the same sample does not duplicate its additions. Speech and AI
responses are still simulated, not live services or arbitrary text interpretation.

The earlier structural A/B/C comparison remains at `a4d938e39fae2e8eb915da1f8b00f1f2227f4637`.
The selected A with the rejected 96px header remains at `27c8bba1c7a6af2813c3b53d8bb0b5a2faa66e97`.
The A/B/C labels now refer to image layouts within the selected task design.

## Reusing stored adventure artwork

`ArtworkCatalog` already owns five generic reusable themes: home, storage,
garden, kitchen and cleaning. The Android `ArtworkCache` is account-scoped.
Task text is not needed in the shared image catalog or an image-generation
prompt. Prefer a suitable ready catalog image, retaining a bundled fallback
when offline or missing. Do not generate an image when opening a task.

The current `AdventureArtworkClient` and `/adventure-artwork` endpoint authorize
reads through a current adventure choice, batch, workspace and epoch. They are
not a general task-image endpoint. A native follow-up must add an authorized
catalog-read path or share an already available account-scoped cached image,
without inventing adventure IDs or weakening ownership checks.

This browser refinement reuses bundled `dojo-garden.webp` to demonstrate the
image treatments. It has not fetched production catalog entries and does not
claim those five images are currently generated or ready.

## Original question

Can a task be read, edited and completed without exposing every management
command? Compare three structures in the existing paper/evergreen Material
identity. The later owner request adds colored task cues and artwork for
individual tasks, in the queue and detail views.

- A: focused reading page with Edit and Complete below, other actions in a menu.
- B: inline title/description editing and a completion checkbox.
- C: a detail sheet over the task list.

The initial comparison used per-task queue thumbnails. The selected refinement
removes them. Opening a row shows the selected artwork layout. Queue
filter/reorder, capture and other app destinations are outside this comparison and open an explicit scope note.
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
- `variant=A|B|C` selects an image layout. Desktop defaults to side-by-side.
- `mode=focus` shows just the selected layout. The floating arrows switch layouts.
- `screen=queue|task|checklist|review`.
- `lang=fi|en`, `text=large`.

Desktop can compare all three layouts; phones show the selected one. The
floating arrows and keyboard left/right switch layouts. Text inputs keep their arrow keys. Screen/language changes reset sample data. Task state stays in memory
while navigating within an option; reload resets everything. The optional
Prototype state disclosure and console show the current simulation state.

## Behavior and limits

The app calls no microphone, API or storage service. All commands modify only
sample data. The three voice samples produce predetermined changes to the
current draft. The add sample adds three steps in a single revision. Manual edits are preserved. Rejection and simulated offline/error
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
regenerated. The bike, storage and cloth images are retained historical comparison assets.
This round displays the shared dojo scene, not those per-task examples.
