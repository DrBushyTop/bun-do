# Bun Do

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

The selected stack is Kotlin, Jetpack Compose, Room, WorkManager and an Azure .NET 10 backend, with Aspire for local backend composition and Bicep for deployment. The owner delegated architecture to the agent. A tested .NET typed-command and replica model now exists. The local Aspire/Functions health host runs through Podman with Azurite, loopback-only listeners and request telemetry. The native Android shell implements anonymous offline typed and voice capture, with task editing. Azure deployment is a separate slice. Resolved contracts live in docs/architecture; live deployment checks are implementation gates.

## Users

Two people sharing household tasks are the initial audience. The workspace model supports more members. The app should help them capture work and see what they are getting done together.

## Product purpose

Bun Do is a shared task queue with local voice capture and durable offline task actions. A task exists locally before cloud AI processes it. Typing is a first-class alternative.

## Operating context

Users dictate or type tasks, choose any available task, claim and complete work, reorder the queue, and synchronize after reconnecting. UI and task content support Finnish and English independently. Finnish is the fallback language.

## Capabilities and constraints

The owner confirmed the full v1 scope on 2026-09-12, including offline collaboration, local speech, AI enrichment/split/clarify, nested subtasks, dependencies, recurrence, notes, areas, due dates, snooze, deletion/recovery, activity, and shared statistics. Build stages do not authorize feature removal. The detailed scope lives in [the architecture](shared-task-manager-architecture-v1.md).

Azure is the selected backend platform. New resources go into a dedicated Bun Do resource group through Bicep. The owner assumes Foundry model availability. Raw audio stays on the phone in the normal voice path. Cloud AI receives text. The target phones are the vivo X300 Ultra and OnePlus 13, confirmed by the owner. Device-specific performance remains unmeasured. Concurrency and recovery rules are specified in the [implementation contract](docs/architecture/README.md). Agents test functional behavior on two Android emulator profiles on this Mac.

The owner chose to skip the separate speech feasibility experiment and proceed on the assumption that local Parakeet works on both phones. This is an accepted planning assumption, not measured evidence. Normal implementation testing still covers offline recording, transcription and recovery.

The Android voice path installs and verifies the local Parakeet model, then records and transcribes without a network connection. Stopping a recording transcribes it and commits the task locally before opening its detail. Interrupted, canceled or unsuccessful recordings remain available for retry, explicit export or deletion. The app displays retention limits and expiry dates, explains microphone denial and silence, and keeps typing available without microphone permission or the model. Export uses the user's chosen destination and warns that it may be a cloud drive.

MAI is selected for a separate online speech path, but that path is not implemented in the app. [Prefer MAI online transcription with recoverable offline fallback](https://github.com/DrBushyTop/bun-do/issues/38) tracks it and requires authentication. It does not change the current local-only voice behavior.

## Brand commitments

The name is Bun Do, "the way of the bun." Bun means bunny. The owner supplied xkcd's King Bun as a reference and approved the refined martial-arts rabbit salute in `assets/brand/`, retaining its defined nose and the same drawing at small sizes. Preserve the small rabbit's dignity and dry humor. UI work must use Impeccable with native Android guidance.

## Evidence on hand

The repository contains the architecture proposal. The owner supplied an image of [xkcd's Bun comic](https://xkcd.com/1682/). This is a reference, not a completed app logo. The anonymous Android shell now has a Room-backed queue, typed capture/edit, local voice capture with recording recovery, task detail and bilingual appearance/language settings. It has no sign-in, online speech, shared commands or sync yet. No measured physical-device benchmark, tablet validation or owner approval of the rendered app is claimed.

## Product principles

- Commit task actions locally before waiting for the network.
- Preserve user intent when devices disagree or AI finishes late.
- Reward shared progress without comparing members.
- Keep task content in its original language.

## Accessibility and inclusion

Typing must work without microphone access. Finnish and English need complete UI coverage and accessibility labels. The visual review must cover text scaling, contrast, touch targets, reduced motion, and understandable conflict/recovery states.
