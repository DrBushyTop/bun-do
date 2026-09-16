---
version: 1
slug: "activity-feed"
primary_target: "src/BunDo.Android/app/src/authCheck/java/fi/bundo/ui/SharedActivityScreen.kt"
related_targets: ["src/BunDo.Android/app/src/authCheck/java/fi/bundo/ui/SharedProgressScreen.kt"]
---

# Activity feed

Mode: Operate. Preserve the native notebook palette and Material type roles.
The feed is a compact record of household changes, not a stack of event cards.

At ordinary text sizes, each event occupies one row: action icon, task title,
actor and time. Group dates in the household statistics time zone rather than
repeating a full timestamp for every event. Refresh sits beside the heading;
the snapshot timestamp remains visible so cached activity is not mistaken for
live data.

Task titles open the existing task view. The action icon expands the full event
description, including the untruncated task title, actor and accepted timestamp.
Its accessible name includes that same information. Unavailable tasks retain
their event details but have no task link. Expansion is local presentation state.

Large text may grow the row and move the actor below the title. Never shrink text
or touch targets to maintain density. Preserve chronological order, empty and
unavailable states, Finnish and English, and light and dark themes. No animation
or new backend state is needed here.
