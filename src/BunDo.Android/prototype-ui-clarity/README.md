# Bun Do UI clarity study

Throwaway browser proposals for issue #67. Not a web client or native implementation.
The production base is `68ecedb8`; retain this study on `prototype/ui-clarity-batch-1`.

## Run

From the repository root:

```sh
python3 -m http.server 4877 --bind 127.0.0.1 --directory src/BunDo.Android/prototype-ui-clarity
```

Open `http://127.0.0.1:4877/`. No dependencies or build step.

The owner's m1 preview is served at `https://m1.saiga-bleak.ts.net/ui-clarity/`.
The background server PID is recorded in `/tmp/bundo-ui-clarity-server.pid`.
Stop that process with `kill "$(cat /tmp/bundo-ui-clarity-server.pid)"`.
Remove only this Tailscale route with
`tailscale serve --https=443 --set-path /ui-clarity off`.
Do not reset Serve or remove its root route, which serves T3 Code.

## Compare

- `screen=adventure|planner|settings|voice|account|recovery|family`
- `screen=audit|components` opens the source survey or proposed component vocabulary.
- `variant=A|B|C`, also available through the bottom arrows and keyboard arrows.
- `lang=fi|en`, `mode=compare|focus`, `state=normal|offline|error`, `text=large`.
- Desktop compares three proposals. Phones show one with the same switcher.
- URL state survives reload. Form and simulated action state stays in memory only.

A keeps the familiar structure and fixes controls. B changes hierarchy and reveals
less common detail on request. C tries guided decisions. The notebook identity,
static light palette, system type and approved art stay fixed. The first viewport
must expose the relevant task or decision, not a provider manual. Navigation rows
open pages, outlined and filled buttons perform actions, and help uses explicit
disclosures. Compare those patterns across screens before choosing a direction.

## Simulation limits

No API, microphone, storage, account operations or real downloads. AI plans and
recovered tasks are sample data. Follow-up screens illustrate the choice, not the
whole production state machine. The scenario selector affects relevant online
operations; local appearance settings do not acquire artificial failure banners.
Design notes are in English, while preview UI supports Finnish and English.

The audit reviewed all UI source files and resources. Live native inspection
covered local queue, Settings, voice settings and Account; retained captures
provided additional adventure evidence. This is not a complete native runtime,
large-text, TalkBack or release verification. Browser large text is illustrative.

The study proposes navigation changes and new presentation patterns, not approved
product contracts. Keep DESIGN.md and the Compose code unchanged until selection.
Reimplement selected patterns natively rather than merging the prototype into master.

## Existing artwork

No new raster artwork was generated. `assets/bun.svg` is copied unchanged from
`assets/brand/bun-do-evergreen.svg`. The dojo and welcome WebP files are copied
unchanged from `app/src/main/res/drawable-nodpi/`; their existing provenance is in
`assets/illustrations/ARTWORK.md` and the welcome artwork directory in the repository.

`current-settings.png` and `current-voice.png` were captured from the existing
Android emulator on September 17, 2026. `prior-planner.png` comes from the retained
adventure follow-up captures under `/private/tmp/bundo-64-reviewed-captures/`.
That capture predates the current AI-ideas controls; current source owns behavior.
