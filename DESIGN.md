---
name: Bun Do
description: Native household task notebook
colors:
  light-primary: "#245B48"
  light-onPrimary: "#FFFFFF"
  light-primaryContainer: "#BCEBD2"
  light-onPrimaryContainer: "#082116"
  light-surface: "#F8F9F4"
  light-onSurface: "#191D19"
  light-surfaceContainerLow: "#F2F4EE"
  light-onSurfaceVariant: "#444C44"
  light-outline: "#747D73"
  light-outlineVariant: "#C4CCC1"
  light-error: "#BA1A1A"
  light-onError: "#FFFFFF"
  dark-primary: "#A1D2BA"
  dark-onPrimary: "#083827"
  dark-primaryContainer: "#28513F"
  dark-onPrimaryContainer: "#D6F8E4"
  dark-surface: "#111511"
  dark-onSurface: "#E0E4DD"
  dark-surfaceContainerLow: "#191D18"
  dark-onSurfaceVariant: "#C2CAC0"
  dark-outline: "#8C958B"
  dark-outlineVariant: "#444C44"
  dark-error: "#FFB4AB"
  dark-onError: "#690005"
typography:
  headlineLarge:
    fontFamily: Android system sans-serif
    fontSize: "32sp"
    fontWeight: 400
    lineHeight: "40sp"
  headlineSmall:
    fontFamily: Android system sans-serif
    fontSize: "24sp"
    fontWeight: 400
    lineHeight: "32sp"
  titleLarge:
    fontFamily: Android system sans-serif
    fontSize: "22sp"
    fontWeight: 500
    lineHeight: "28sp"
  titleMedium:
    fontFamily: Android system sans-serif
    fontSize: "16sp"
    fontWeight: 500
    lineHeight: "24sp"
  bodyLarge:
    fontFamily: Android system sans-serif
    fontSize: "16sp"
    fontWeight: 400
    lineHeight: "24sp"
  bodyMedium:
    fontFamily: Android system sans-serif
    fontSize: "14sp"
    fontWeight: 400
    lineHeight: "20sp"
  labelLarge:
    fontFamily: Android system sans-serif
    fontSize: "14sp"
    fontWeight: 500
    lineHeight: "20sp"
  labelMedium:
    fontFamily: Android system sans-serif
    fontSize: "12sp"
    fontWeight: 500
    lineHeight: "16sp"
spacing:
  step-4: "4dp"
  step-8: "8dp"
  step-12: "12dp"
  step-16: "16dp"
  step-24: "24dp"
  step-32: "32dp"
components:
  primary-button:
    backgroundColor: "{colors.light-primary}"
    textColor: "{colors.light-onPrimary}"
    typography: "{typography.labelLarge}"
  task-row:
    backgroundColor: "{colors.light-surface}"
    textColor: "{colors.light-onSurface}"
    typography: "{typography.titleMedium}"
---
# Bun Do design direction

Status: the direction below applies to the reduced v1 scope in shared-task-manager-architecture-v1.md; owning issues track implemented flows. On September 13, 2026, the owner selected the [household visual study](docs/design/household-visual-reference.md) for native v1 adoption. This approves a direction, not a completed Android rendering. The owner-approved salute and its vector assets remain unchanged in `assets/brand/`. The frontmatter extracts the shell's Compose tokens; `.impeccable/design.json` records their extensions.

## Identity

Bun Do means "the way of the bun." Bun is a bunny. Choose an orderly household notebook expressed through native Material 3 lists, with evergreen controls and a small, solemn martial-arts rabbit. People use this in kitchens, on errands, and in bed. The owner chose light-only presentation for illustrated V1 on September 13, 2026. Do not expose a theme switch in this direction. Preserve existing dark-theme implementation and the target palette below for later adaptation.

The palette is restrained. Brand appears in the launcher, welcome, compact app bar, empty queue and optional action feedback. The selected study adds fixed koala/bunny claimant illustrations and task-icon accents, without redundant category names. Keep ordinary task actions literal. No belts, scores, or rankings for members.

Use the approved refined salute in `assets/brand/bun-do-evergreen.svg`. The small upright rabbit faces right, with one outward-curved ear, a defined nose, a calm eye, joined forepaws, planted feet and a tied belt. The owner preferred this refined drawing over the compact variant and found it recognizable at 24 pixels. Preserve the same drawing, including nose, eye and knot, at every size. The SVG has a nominal 48-pixel size and a 1024-unit view box.

Use exact evergreen `#245B48` on light backgrounds and `assets/brand/bun-do-paper.svg`, `#F8F9F4`, on dark or evergreen backgrounds. Both files share one compound path with opaque fill and transparent cutouts. Do not add gradients, texture or translucent body shading. Keep the silhouette within the adaptive icon safe zone when creating Android launcher layers; browser crop previews are not device validation. No crown, copied comic linework, weapons, lettering inside the icon or bread imagery. The supplied King Bun reference informs the dignity and humor only. Rejected drafts and the compact variant have been removed.

## Tokens

Use named Material color roles in Compose. These are static light/dark targets. Wallpaper color is off for v1 to preserve the selected identity.

| Role | Light | Dark |
| --- | --- | --- |
| primary | `#245B48` | `#A1D2BA` |
| onPrimary | `#FFFFFF` | `#083827` |
| primaryContainer | `#BCEBD2` | `#28513F` |
| onPrimaryContainer | `#082116` | `#D6F8E4` |
| secondary | `#52635A` | `#B7CBC0` |
| onSecondary | `#FFFFFF` | `#23352C` |
| secondaryContainer | `#D5E8DC` | `#394B41` |
| onSecondaryContainer | `#102018` | `#D5E8DC` |
| surface / background | `#F8F9F4` | `#111511` |
| onSurface | `#191D19` | `#E0E4DD` |
| surfaceContainerLow | `#F2F4EE` | `#191D18` |
| surfaceContainer | `#ECEFE8` | `#1D211C` |
| surfaceContainerHigh | `#E6E9E2` | `#282C26` |
| onSurfaceVariant | `#444C44` | `#C2CAC0` |
| outline | `#747D73` | `#8C958B` |
| outlineVariant | `#C4CCC1` | `#444C44` |
| error / onError | `#BA1A1A` / `#FFFFFF` | `#FFB4AB` / `#690005` |
| errorContainer / onErrorContainer | `#FFDAD6` / `#410002` | `#93000A` / `#FFDAD6` |
| warningContainer / onWarningContainer | `#FFE2A9` / `#402D00` | `#574315` / `#FFE2A9` |

The shell freezes all Material color roles explicitly in `src/BunDo.Android/app/src/main/java/fi/bundo/ui/Theme.kt`; it does not generate them at runtime. The table retains the v1 targets. Warning roles are planned and unused in the shell. Success uses primary roles; recording uses error roles plus an explicit label. Warning belongs to decisions requiring attention. Ordinary pending sync uses neutral roles.

Use Android's system sans family and Material type roles. Sizes/line heights in sp: headlineLarge 32/40, headlineSmall 24/32, titleLarge 22/28, titleMedium 16/24, bodyLarge 16/24, bodyMedium 14/20, labelLarge 14/20, labelMedium 12/16. Titles and labels use medium weight; body uses regular. Other roles retain Material defaults. No custom display font or uppercase tracking.

Spacing steps are 4, 8, 12, 16, 24, 32 dp. Screen gutters are 16 dp on compact widths, 24 dp otherwise. Flat queue rows use 12 dp vertical padding and a subtle divider. Sheets use 28 dp top corners; dialogs and cards 16 dp; chips and buttons keep Material shapes. Use tonal elevation, without custom shadows. Icons are Material outlined at 24 dp; every action has a 48 dp target and 8 dp separation.

## Behavior and quality

Rows grow with content and font scale. Queue titles allow three lines and then ellipsis; detail exposes the full title. Wrap metadata instead of shrinking it. Semantic labels name task, state, and action. Color never carries status alone. Native focus, pressed, disabled, loading, error, and selected states apply to every control. A disabled action explains its reason nearby.

Use 200 ms state transitions and 150 ms fades. The selected study extends the single completion bow to short bow, hop and checkmark-stamp variants, plus claim and filing feedback. These optional effects finish within 420 ms after the local write succeeds. A claimed-task working pose makes two pencil strokes within 900 ms, then remains static rather than looping. Settings can disable them; system reduced motion always overrides the app preference. Keep a static confirmation and immediate Undo. No celebratory screen blocks continued work.

Release review must use native emulator/device captures of the light-only V1 interface, Finnish and English, font scales 1.0, 1.3, and 2.0, TalkBack, and contrast checks. Use two Android emulator profiles on this Mac for gestures, voice fixtures, interruptions and functional tests. The vivo X300 Ultra and OnePlus 13 remain target phones, but their performance and Finnish recognition quality are unmeasured owner-accepted assumptions; the skipped benchmark is not a hidden release gate. Impeccable's HTML/CSS detector does not validate Compose. Build fully, inspect once, batch corrections, then confirm once before the skill's independent finish review. Validate the approved logo's Android integration and finalize the UI token sidecar from that evidence.

## Shell contracts

The compact app bar keeps the approved rabbit beside the Bun Do wordmark.
Settings holds language, decorative motion and the account entry. Shared work
uses Queue, Activity and Together destinations. Type and voice capture remain
adjacent below the list. Type becomes Resume draft when a new-task draft exists.

Queue rows are flat, with a visual task cue, title and useful state rather than
a description excerpt. A count and All/Unclaimed/Mine menu precede the list.
Completed, deleted, snoozed and due views remain in the queue-view menu.
Actionable recovery stays visible; import, local inbox and refresh are explicit
queue options. Recovery exports and connection diagnostics have named entries
in the account screen rather than permanent warnings.

Reorder mode shows the full open queue, including snoozed tasks. A handle drag
previews order until release; Back, Escape and pointer cancellation discard
that preview. Move buttons and keyboard arrows commit each move immediately.
Concurrent queue changes cancel a stale drag. Swipe completion is unavailable
while reordering. The same completion confirmation protects the row action,
swipe and task detail.

Details show a full title, compact creation/change attribution, description and
editing before the task controls. Selected royal Bun illustrations are local,
optional header artwork with a continuous paper fade. The approved uncrowned
logo remains a separate unchanged vector. Artwork provenance lives in
`assets/illustrations/ARTWORK.md`.

Editors and settings scroll within a 640 dp maximum width. Screen gutters are
16 dp below 600 dp and 24 dp otherwise. At 840 dp, a selected task may share the
screen with a 360 dp queue. Large text wraps and scrolls; no fixed-height text
containers substitute for that behavior.

## Components

### Voice capture and settings

The queue voice button starts recording immediately after microphone permission.
Keep the recording sheet focused on elapsed time, level, Stop and Cancel. Use a
short provider label, not a setup manual. If speech is unavailable, offer voice
settings and typing. Model downloads and history never occupy the capture sheet.

Stop transcribes, then optionally analyzes a household capture. Show progress and
allow cancellation back to the durable transcript. The review contains editable
title, description and one direct checklist item per line, with original transcript
behind an explicit action. Save accepts the final text and items. No task appears
in the queue before Save. Blank or invalid input explains why Save is unavailable.
The recording destination and account remain fixed through processing and review.

Settings has a named Voice and recordings entry. It contains Automatic versus
On device speech selection, expandable Parakeet setup, a separate online-analysis
switch, and an opt-in Keep recordings switch. Explain that local speech keeps
audio on device while optional analysis sends transcript text. Defaults do not
retain audio after transcription or failure. Drafts and recordings are collapsed
until requested; retained audio has expiry, export and deletion, and text drafts
can resume review. No recording-import entry belongs in the normal user journey.

Use native Material controls, existing color/type roles, 48 dp targets and a
scrollable 640 dp maximum-width content column. Finnish/English and large-text
layouts must keep Stop and Save reachable. Microphone permission is requested
only when recording is requested. The native rendered verification belongs in
the owning issue, not in this design contract.

## Task-first settings and secondary screens

On September 17, 2026, the owner selected option B from the UI clarity study for
all seven screens and authorized the same direction for the remaining menus.
Keep the established artwork, paper and evergreen palette, and Material type.

Use full-width, left-aligned navigation rows with optional leading icons, a
current value below the label, and a trailing chevron. One row is one accessible
target. Use filled buttons for the main action, tonal buttons for AI suggestions,
and outlined buttons for secondary actions. Quiet Back, Cancel and app-bar
buttons may remain text buttons. Do not convert the task queue into cards.

Settings groups family/account, voice, reminders and recovery before app
preferences. Language opens a choice; a switch changes one setting. Details
expand under an explicit label with an announced expanded/collapsed state.
Keep privacy destinations visible beside voice choices. Keep saved work,
shared consequences and irreversible deletion warnings at the decision.

Empty adventures lead to planning or adding tasks. Loading, missing access,
failed generation and expired suggestions are separate states, not empty lists.
The planner puts the goal before artwork and offers optional time presets plus
custom minutes. AI ideas remain optional, with a separate plan preview and
family-wide approval. Counts lead with completed work; all-zero charts and
counting rules do not occupy the first-use screen.

Recovery opens saved text for explicit selection and copying into new local
inbox tasks. Family import remains a separate decision. Originals are retained.
Backup/export warnings appear before the file picker, while connection details
and storage formats remain available on request. Sign-out, switching accounts,
local deletion and disconnecting an old device use distinct confirmations.
