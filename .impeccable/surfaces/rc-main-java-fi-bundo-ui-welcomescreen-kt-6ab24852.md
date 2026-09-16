---
version: 1
slug: "rc-main-java-fi-bundo-ui-welcomescreen-kt-6ab24852"
primary_target: "src/BunDo.Android/app/src/main/java/fi/bundo/ui/WelcomeScreen.kt"
related_targets: ["src/BunDo.Android/app/src/authCheck/java/fi/bundo/ui/WelcomeRoute.kt"]
---

# Welcome and family setup

Mode: Operate. Inherit the evergreen paper notebook, Material controls and existing Bun character. The September 16 owner request and `docs/design/welcome-flow.md` settle the flow, not a new visual identity.

Get a new user to a usable inbox, either local or shared. One illustrated screen at a time: notebook for the local/family choice, gate for sign-in and create/join, shared grocery basket when family tasks are ready. Artwork never contains labels and disappears at large font sizes before controls lose space.

Local use needs no account. Family setup explains Microsoft sign-in without registration terminology. Show only the fields needed for the selected action. A pending join shows the matching code and a route back to tasks, never shared tasks before approval. Owner approval remains explicit. Setup stays available in Settings without blocking existing installs.

Use a scrolling 640 dp maximum-width column, existing Material type and color roles, and 48 dp buttons. Support Finnish and English, Back, IME and system insets. No carousel, setup quiz, permission tutorial, audio import or automatic local-text upload. Persist private setup under device encryption, outside backups and saved-instance state. Review and verification evidence belong in the owning GitHub issue.
