# Typed-command model review

Reviewed 2026-09-12 against the first build slice, "Accept and retry typed task commands in an executable sync model". Initial implementation commit: `9034ad0`. The architecture baseline was `ade42b3`.

Two fresh independent subagents reviewed the slice at medium reasoning. The spec reviewer used Terra; the standards reviewer used Luna. This review did not reuse the planning agents' contexts.

## Spec

The reviewer found three material gaps and one related follow-up defect.

- A malformed accepted receipt or contiguous empty change group could settle local intent before its canonical task effect existed. Fixed by checking receipt identity, versions and requested values, then requiring a canonical effect before settling, including when the receipt arrives after its page.
- Local description capture/edit/recovery was missing even though the server accepted descriptions. Added that path, nullable description clearing and a two-replica test.
- Protocol and command versions were absent from the model envelope. Added version 1 to the fingerprint and rejected unsupported versions before receipt lookup or sequence consumption.
- During fix verification, the reviewer found that a forged snapshot with a field version newer than its containing revision bypassed the effect check. Added version validation before staging and regression cases for future, zero and inconsistent stamps.

Each behavior fix began with a failing regression test. The reviewer reran the final suite and returned "resolved" for these findings. The final Release suite has 44 passing tests.

## Standards

The reviewer identified one contract wording error and three readability suggestions.

- Corrected the transport note to name frozen UTF-8 envelope bytes, excluding transport credentials.
- Renamed `FieldVersion.Field` to `FieldVersion.Server`.
- Kept the two tests' fixture setup separate. One models two members; the other models two installations for one member. Sharing the setup would conceal that distinction.
- Kept nested `Assert.Single` where the assertion intentionally checks one complete revision with one task. This is direct public-output verification, not a domain coupling defect.

No concrete bug was deferred from this review. The last two items are documented style judgments, not untracked correctness findings.

## Verification limits

Locked restore, Release tests, formatter verification and whitespace checks ran locally. The model uses immutable in-memory state and an explicit store transaction interface. It does not establish Room persistence, Cosmos behavior, HTTP authentication, device expiry or production readiness. Those remain separate build slices.
