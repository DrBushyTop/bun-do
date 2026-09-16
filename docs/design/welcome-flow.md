# Welcome and family setup

The owner approved implementation on September 16, 2026.
[Welcome users to local tasks or a shared family with Bun guidance](https://github.com/DrBushyTop/bun-do/issues/56)
owns implementation and verification. This does not authorize changes to Microsoft registrations. [Consent research](../research/microsoft-consent.md)
covers the registration question.

## A short path to a useful inbox

First screen: "Welcome to Bun Do" with a new illustration of Bun holding a small
notebook. One line of copy: "A little help with everyday things."

- "Use on this phone" opens the local inbox. No account, consent screen, model
  download or recording import is required. Explain once that these tasks stay
  on this phone and the user can add a family later.
- "With my family" opens the choice between "Create a family" and "I have an
  invitation". Do not introduce workspace, tenant or registration terminology.

For creating, explain that Microsoft sign-in connects the user's shared tasks,
then return to the family-name and display-name form. Suggest "Our home" without
silently creating it. After creation, offer "Invite someone" and "Start with a
task". Sharing an invitation is optional, not a condition of entering the inbox.

For joining, preserve an incoming invitation through sign-in. Also allow pasting
a link. Both joining and creating require a personal Microsoft account. After
requesting access, show the matching confirmation code and "Ask the person who
invited you to let you in." Give the owner a clear approve action and the joining
person a way to leave and return while waiting. No family tasks appear before
approval. Expired links offer a fresh invitation, not a technical error code.

A fresh install opened through an invitation should enter the join path directly,
not require the user to rediscover it through Welcome. Cancelled sign-in returns
to the selected path, with "Use on this phone" still available.

## Bun does the guiding

Use the new illustrations in the established Bun style, keeping the approved
logo unchanged:

- Welcome: Bun with a small notebook, ready to help.
- Create or join: Bun opening a garden gate.
- Ready: Bun and a friend carrying a grocery basket together.

Use one illustration per step, with no text baked into the bitmap. Avoid a
carousel, technical setup diagrams or a long tutorial. The artwork is supporting
content; it shrinks or disappears at large font sizes before controls lose space.
Keep the evergreen/paper palette, native buttons and quiet humor. Static artwork
must work without motion. The generated assets and exact prompt are recorded in
[illustration provenance](../../assets/illustrations/ARTWORK.md).

## Keep setup out of the way

Ask for microphone permission only on the first recording action, and notification
permission only when reminders are enabled. Speech configuration stays in Settings.
Local-only use means offline transcription needs the optional Parakeet download;
typing works without it. Do not call this local option cloud backup or synced use.

Do not show recording import, retention policy, recovery exports or developer
settings in Welcome. Existing local task text is never uploaded automatically on
joining a family. Offer explicit selection later, outside the first-run steps.

Existing installations should not be blocked by a new welcome wizard after an
upgrade. Detect existing work without modifying it and offer setup from Settings.
Persist new users' selected path so interruption does not restart the introduction.
Setup lives in an encrypted, atomically written file under Android no-backup storage.
The app owns ordered writes, so dismissal and rotation do not cancel them. The
create request ID is saved before contacting the backend; a lost reply can be
recovered by listing the signed-in account's families. Invitation links leave the
Activity intent immediately and never enter saved-instance state. Switching
accounts clears private setup fields; anonymous text never moves automatically.

Use existing-installation evidence before opening the account database to avoid
showing first-run Welcome after an upgrade. Keep invitation secrets out of analytics
and logs. Native and live verification evidence belongs in the owning issue.
