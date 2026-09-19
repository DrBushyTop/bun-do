# Task editing study

Throwaway interaction study, not an Android implementation or web client.
Compare one complete editor, a three-phase editor, and editable read-first sections.
Preserve the Bun Do visual identity while testing the placement of editing capabilities.

Run from the repository root:

```sh
python3 src/BunDo.Android/prototype-user-journeys/serve.py --bind 100.92.80.68 --port 8768
```

The bind address is this Mac's Tailscale IP. Use `127.0.0.1` for local-only work.
The server exposes only this study directory, never the repository or backend.
Stop with Ctrl-C, or send SIGTERM to the PID recorded by the caller.

Use `?variant=A`, `?variant=B`, or `?variant=C`. Add `&screen=editor` to open
editing directly. The bottom arrows change variants without resetting the draft.
Keyboard arrows do the same outside editable fields.

State is in memory and resets on reload. AI, voice, household identity, future
repeat generation and synchronization are simulated. Dates use a fixed sample
scenario of September 19, 2026. The offline switch is in Settings.
A current task and future repeat title remain distinct. An offline repeat plan
stays in its draft when ordinary task changes are saved.

The nearby native app owns production behavior. This draft-save model would need
proper native draft recovery, child identity and conflict handling before shipping.
Do not merge this code into the application. Record the selected behavior in the
owning GitHub issue and implement it separately.

Artwork is copied unchanged from the approved task-detail study. The brand SVG
is the original Bun Do salute. Existing artwork provenance remains in that study
and in `assets/illustrations/ARTWORK.md`.

`findings.html` and `evidence/` are local review output, not committed reports.
GitHub owns findings and verification evidence.
