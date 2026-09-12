# Issue tracker

Use GitHub repository `DrBushyTop/bun-do` through the authenticated `gh` CLI.

## Wayfinding operations

The completed architecture map is "Find the route to Bun Do's first usable release". The execution parent is "Build the complete Bun Do v1 Android release". Query their native sub-issues and blocked-by edges rather than treating a copied checklist as status.

```sh
gh api repos/DrBushyTop/bun-do/issues/14/sub_issues
gh api repos/DrBushyTop/bun-do/issues/NUMBER/dependencies/blocked_by
```

Assign DrBushyTop when starting work. Only take open slices with closed blockers. Implementation tickets use `implementation` and `ready-for-agent`; readiness still requires checking native dependencies. Record test and fresh adversarial-review evidence before closure. Use `bug` for concrete deferred findings and link them from the slice.

Refer to issues by their linked names in prose. Never claim a skipped experiment passed. When opening or working on a pull request, register it with the thread's `link_pull_request` tool.
