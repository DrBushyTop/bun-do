# Repository map

- Read [the implementation plan](docs/implementation-plan.md) and use the
  [issue tracker workflow](docs/agents/issue-tracker.md) to choose ready slices.
- [Architecture contracts](docs/architecture/README.md) define product behavior.
  Keep domain decisions in `BunDo.Domain`, Android persistence in `data/`, and
  Android screens in `ui/`.
- Keep documentation for decisions, behavioral contracts, external-system setup,
  and manual procedures. Code, build files, Bicep, and tests own implementation
  details, pinned values, and test inventories. Record slice-specific evidence
  and review conclusions in the owning GitHub issue.
- Group Bicep modules by application responsibility, such as workspace storage,
  backend hosting or observability. Avoid one-resource wrapper modules.
- Run `python3 tools/check_invariants.py` and
  `python3 -m unittest discover -s tools -p 'test_*.py'` after changes.
  Before adding steering checks, read [invariant test policy](docs/agents/invariants.md#adding-or-changing-steering-checks).
  Protect costly mistakes, not naming preferences or today's file layout.
  Do not duplicate Azure configuration in tests or policy-readback tools.
  Bicep owns those settings; defer real-environment integration/e2e tests until needed.
- Use [local development](docs/local-development.md) for Aspire lifecycle and
  [README](README.md) for .NET checks. Android builds use `src/BunDo.Android/gradlew`.
  Use [Azure development](docs/azure-development.md) for scoped Bicep planning,
  deployment and live verification.
- Run the required fresh adversarial subagent review only after the whole slice
  is implemented and its planned verification has run, not after individual
  edits, partial increments or intermediate test runs. Follow the
  [completion-review workflow](docs/agents/issue-tracker.md#completion-review).
  Fix material findings, file concrete deferred bugs, then continue to the next ready slice.
- Record the review conclusion and test evidence in the slice's GitHub issue.
  Keep only durable decisions, operational procedures and deferred bugs in the
  repository; do not commit standalone review reports.
- Keep implementation with the main agent. Use subagents primarily for review.
- Load the `unslop` skill once per conversation and apply it to writing.
- Use [.agents/skills/otel-wide-events](.agents/skills/otel-wide-events/SKILL.md)
  when adding or reviewing backend telemetry.
