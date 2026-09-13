# Household UI study

The owner selected this direction for native V1 on September 13, 2026. The
production repository owns the behavioral contracts in
`docs/design/household-visual-reference.md`, `DESIGN.md` and the architecture
contracts. This browser study is a visual reference, not an Android implementation.

## Run

From this directory, run `python3 -m http.server 63757 --bind 127.0.0.1`, then
open the loopback server in a browser. No package installation or build is needed.

## Try

- Järjestä exposes drag handles, up/down buttons and arrow-key reordering.
  Escape or pointer cancellation discards a pending drag.
- Ordinary typed/voice samples join the end. Explicit urgency or a date through
  tomorrow places the new task after the leading urgent/soon-due tasks. The
  save area explains placement; later edits preserve it.
- Task detail shows compact creation/change attribution. Unchanged tasks show
  one line. Exact timestamps carry time-zone information in their title.
- Claim a task to see its working pose. Complete and Undo leaf tasks to cycle
  bow, hop and stamp feedback. Saving a new task uses a filing effect.
- Settings disables decorative motion. Reduced-motion CSS and action logic
  retain static feedback. Claimed avatars do not run idle animation loops.
- AI split and dictated steps have editable, selectable sample suggestions.

## Simulation limits

All data is in memory and resets on refresh. AI, microphone and transcription
are samples. Category icons use title-keyword matching; categories are not a
V1 data model. There is no account service, persistent metadata or sync.
Charts combine fixtures and local completions, not server acceptance history.
Checklist completion/claims, confirmation of another member's completion,
recovery, repeats, reminders and full date editing need the real domain flows.
The root action directs users to its steps, but the study does not model the
complete checklist lifecycle or derived completion statistics.

Fixed profile illustrations are in V1; profile editing and combo effects stay
in V2. Browser checks do not validate native accessibility, physical touch
behavior, Android font scaling or device performance.

## Selected later refinements

The illustration direction is light-only. Task headers use a continuous fade,
not separate white text bands. Bun is now a calm royal martial-arts master in a
gi, belt and restrained crown; the app logo is unchanged. Category words are
removed from queue rows, and the filter is one compact selector.

Append `?journey=1` to try the optional post-core/V3 world. Its background fills
the whole top section, without route name, location caption or expansion UI.
The illustrated quest entry remains at the bottom. Settings has sample mood
controls and world visibility. Quests are canned groups referring to real demo
tasks; accepting one does not change task order. No real quest AI or animated
character rig is implemented. The supplied scenes are stills with small
transitions. See `art/ARTWORK.md` for generation provenance and prompts.
