# Issue tracker

Use GitHub repository `DrBushyTop/bun-do` through the authenticated `gh` CLI.

## Wayfinding operations

The completed architecture map is "Find the route to Bun Do's first usable release". The execution parent is "Build the Bun Do v1 household release". [V2](https://github.com/DrBushyTop/bun-do/issues/42) holds deferred work. AI usage-policy review starts only after V2 and belongs to neither release parent. Query their native sub-issues and blocked-by edges rather than treating a copied checklist as status.

```sh
gh api repos/DrBushyTop/bun-do/issues/14/sub_issues
gh api repos/DrBushyTop/bun-do/issues/NUMBER/dependencies/blocked_by
```

Assign DrBushyTop when starting work. Only take open slices with closed blockers. Implementation tickets use `implementation` and `ready-for-agent`; readiness still requires checking native dependencies.

## Completion review

Run the required fresh adversarial subagent review only after implementation of
the whole slice is complete and its planned verification has run. This is one
end-of-slice review, not a review after each file, commit, partial increment or
intermediate test run. While implementation is incomplete, keep working and
running the relevant checks instead of launching completion reviewers.

Fix material findings and rerun affected checks before closing the slice.
Request focused confirmation of review fixes when needed, rather than restarting
a general adversarial review after every edit. An explicitly requested early
review is still allowed; it does not replace the required end-of-slice review.

Before closure, add the test results and review conclusion to the slice issue.
Use `bug` for concrete deferred findings and link them from the slice. Do not
commit standalone review reports; record durable decisions and operating
procedures in their owning documentation instead.

Refer to issues by their linked names in prose. Never claim a skipped experiment passed. When opening or working on a pull request, register it with the thread's `link_pull_request` tool.
