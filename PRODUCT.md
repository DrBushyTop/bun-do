# Bun Do

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Kotlin, Jetpack Compose, Room and WorkManager on Android. Azure .NET backend with Cosmos, Aspire for local development and Bicep for deployment. [Architecture contracts](docs/architecture/README.md) define boundaries. Current implementation and test evidence live in the owning GitHub issues.

## Users

Two people sharing household tasks. Capture work quickly, choose what to do, and see progress together without member rankings.

## Product purpose

Speak or type a task, make it actionable, share it, and finish it. Tasks and everyday edits commit locally before waiting for sync or AI. Typing is always available.

## Operating context

Users can claim, complete, reopen and reorder tasks offline. Finnish is the default UI language; English is supported throughout. UI language never translates task content. MAI is the selected online transcription path and installed Parakeet is the offline fallback. Transcription and later AI cleanup are separate steps.

## Capabilities and constraints

The owner reduced v1 on September 13, 2026. Keep shared task work, description, original text, due/snooze, delete/restore, cleanup and one-level manual/AI checklist split, simple daily/weekly repeats, reminders, activity, weekly/monthly completion counts, lifetime milestones and a weekly streak. The full in-progress stale-client recovery slice remains v1.

Deeper task graphs, notes/areas, AI clarify, advanced recurrence, exact historical statistics, FCM and expanded workspace operations move to [V2](https://github.com/DrBushyTop/bun-do/issues/42). [Product scope](shared-task-manager-architecture-v1.md) owns the release boundary.

AI product quotas are absent initially. Review usage policy only after V2 using measured household usage. Technical payload, memory, output and timeout bounds remain.

Simple repeat generation and schedule changes need connectivity. Existing tasks remain usable offline. Shared statistics use server acceptance dates; a late offline completion counts when synced. The weekly streak has no penalties or neutral-week accounting.

Audio sent online goes through the authenticated backend and stays out of logs and public storage. Preserve local recording recovery until text commits. Account switching never exposes another account's work. Recovery explains what happened and the available action, with technical diagnostics behind explicit details.

## Brand commitments

Bun Do means "the way of the bun." Preserve the owner-approved original rabbit salute in `assets/brand/`, including its defined nose and the same drawing at small sizes. Its humor is quiet and dignified. Use Impeccable with native Android guidance for UI work.

## Evidence on hand

GitHub contains slice completion and rendered verification evidence. The owner approved the rabbit artwork, not every future rendered screen. The target phones are vivo X300 Ultra and OnePlus 13; agents verify functional behavior on two Android emulator profiles. The separate phone benchmark was skipped and physical-device performance remains unmeasured.

## Product principles

- Commit everyday task actions locally before waiting for the network.
- Preserve human text when devices disagree or AI finishes late.
- Keep capture and completion easy to reach.
- Show shared progress without comparing members.
- Show warnings when the user needs to act, not as routine technical status.

## Accessibility and inclusion

Keep typing available without microphone permission or a speech model. Provide complete Finnish/English copy and accessibility labels, large-text layouts, sufficient contrast, accessible reorder controls and reduced motion. Review actual Android screens for each implemented flow.
