# Bun Do

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

The architecture proposes Kotlin, Jetpack Compose, Room, WorkManager, and an Azure backend. The owner has delegated architecture to the agent. No application code exists yet. Runtime and deployment feasibility remain under review.

## Users

Two people sharing household tasks are the initial audience. The workspace model supports more members. The app should help them capture work and see what they are getting done together.

## Product purpose

Bun Do is a shared task queue with local voice capture and durable offline task actions. A task exists locally before cloud AI processes it. Typing is a first-class alternative.

## Operating context

Users dictate or type tasks, choose any available task, claim and complete work, reorder the queue, and synchronize after reconnecting. UI and task content support Finnish and English independently. Finnish is the fallback language.

## Capabilities and constraints

The owner confirmed the full v1 scope on 2026-09-12, including offline collaboration, local speech, AI enrichment/split/clarify, nested subtasks, dependencies, recurrence, notes, areas, due dates, snooze, deletion/recovery, activity, and shared statistics. Build stages do not authorize feature removal. The detailed scope lives in [the architecture](shared-task-manager-architecture-v1.md).

Azure is the proposed backend platform. Raw audio stays on the phone in the normal voice path. Cloud AI receives text. The target phones are the vivo X300 Ultra and OnePlus 13, confirmed by the owner. OS builds, RAM variants, runtime budgets, and several concurrency rules remain open in [the decision map](https://github.com/DrBushyTop/bun-do/issues/1).

The owner chose to skip the separate speech feasibility experiment and proceed on the assumption that local Parakeet works on both phones. This is an accepted planning assumption, not measured evidence. Normal implementation testing still covers offline recording, transcription and recovery.

## Brand commitments

The name is Bun Do, "the way of the bun." Bun means bunny. The owner supplied xkcd's King Bun as a reference and requested a martial-arts bunny logo direction. Preserve the small rabbit's dignity and dry humor. UI work must use Impeccable with native Android guidance.

## Evidence on hand

The repository contains the architecture proposal and review. The owner supplied an image of [xkcd's Bun comic](https://xkcd.com/1682/). This is a reference, not a completed app logo. There is no existing app UI, approved palette, or measured device benchmark.

## Product principles

- Commit task actions locally before waiting for the network.
- Preserve user intent when devices disagree or AI finishes late.
- Reward shared progress without comparing members.
- Keep task content in its original language.

## Accessibility and inclusion

Typing must work without microphone access. Finnish and English need complete UI coverage and accessibility labels. The visual review must cover text scaling, contrast, touch targets, reduced motion, and understandable conflict/recovery states.
