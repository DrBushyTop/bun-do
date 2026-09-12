# Bun Do visual brief

Status: the owner approved the refined rabbit salute on 2026-09-12. Current vectors, exports and provenance live in `assets/brand/`. Android screen review remains open. The broader study is tracked in [Choose how Bun Do expresses the way of the bun](https://github.com/DrBushyTop/bun-do/issues/10).

The app is Bun Do, "the way of the bun." The owner means a bunny and supplied [xkcd's Bun comic](https://xkcd.com/1682/) as the reference. Its tiny King Bun has an amusing amount of authority. The requested direction is a martial-arts bunny logo.

Use Impeccable for UI planning, design, implementation, and review. Apply its native Android guidance to Compose. The logo is approved; this brief does not claim a finished Android design system or device validation.

The target phones are vivo X300 Ultra and OnePlus 13. Follow native Material 3 navigation, system Back, window insets, scalable text, and touch targets. Verify screenshots on an emulator or device and performance on the actual phones. The selected build workflow is stored in `.impeccable/config.json`.

## Job and audience

Two people need to capture household tasks, choose work, and keep a shared queue usable offline. The queue is an operational screen. Readability, fast capture, and clear local-versus-shared status take precedence within the controls.

## Identity direction

Create an original small rabbit with a composed martial-arts bearing. Ears and silhouette should remain recognizable at launcher-icon size. A restrained belt, stance, or bow can express the theme. Keep the humor dry and affectionate.

Use the identity in the launcher icon and wordmark, first-run introduction, microphone idle/recording transition, empty queue, and shared completion feedback. A brief bunny bow is a possible completion gesture, subject to the visual study and reduced-motion behavior. Do not put belt rankings on members or turn missed tasks into failure messages.

## The first visual study

Show the mark at launcher and in-app sizes, a realistic Finnish queue, an English version with longer content, task detail with subtasks and blockers, voice recording, and an offline conflict awaiting recovery. Include light and dark appearances. The owner should be able to judge the identity in ordinary daily use, not only on a splash screen.

Keep task actions literal: claim, complete, split, snooze, and edit. Reserve thematic language for optional brand moments. The microphone must show its recording state without relying on the mascot or color alone. Preserve immediate feedback for local writes and distinguish pending sync from a failed operation.

## Open choices

- Final rabbit drawing, palette, type, line weight, and motion.
- How much character fits in the queue without crowding task content.
- Final layout for nested tasks, blocked work, claims, and recovery messages.
- Device-specific text scaling, touch-target, and accessibility verification.

Do not publish a final DESIGN.md contract until the visual direction has been reviewed with the owner. PRODUCT.md holds confirmed product facts; the GitHub ticket holds the eventual design resolution.

## Resolved planning direction

The owner delegated remaining choices on 2026-09-12. Use [DESIGN.md](../../DESIGN.md) and [the screen contracts](screen-contracts.md) for the selected evergreen Material 3 direction and original, tiny martial-arts rabbit. Earlier open choices in this brief are historical prompts, not unresolved blockers. The supplied King Bun comic informs composure and dry humor, not copied artwork or member ranks. Rendered review belongs to implementation.
