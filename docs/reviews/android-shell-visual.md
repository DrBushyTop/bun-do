# Android shell visual evidence

Date: September 12, 2026. Scope: the anonymous, local-only typed capture/edit shell.
The shared v1 queue, voice, sign-in, completion and sync are later slices.

## Native inspection

All captures came from `adb -s SERIAL exec-out screencap -p`, not a browser.
The profiles were `bun-do-a` on `emulator-5554` and `bun-do-b` on
`emulator-5556`, both Pixel 7 API 36 Google APIs ARM64 emulators. The T3 device
panel was unavailable. No physical phone or tablet was tested.

The inspection covered both profiles:

- Queue in English and Finnish, light and dark appearance, font scales 1.0, 1.3
  and 2.0.
- Empty queue in English/light and Finnish/dark.
- Task detail, editor and settings in both languages and themes at font scale 2.0.
- An open software keyboard and landscape layout at font scale 2.0, Finnish/dark
  on profile A and English/light on profile B.

The review used 58 captures in two bounded batches. Representative originals and
contact sheets remain in `.impeccable/review/`; `captures.json` records their
sizes and SHA-256 hashes. Contact sheets combine native captures without changing
the app layout. They are review aids, not replacement screenshots or app assets.

The data is synthetic. It includes "Järjestä varasto", an English multiline title
and Finnish descriptions. Fixtures were seeded into the dedicated emulator's
anonymous Room database after force-stop, with a local backup of the prior
synthetic database. Later opening the editor created real saved-draft indicators.
The fixtures do not prove sync or command delivery. Appearance was restored to
system light, app language to English and font scale to 1.0 after capture.

## Contrast and brand

The explicit Compose color values yield these contrast ratios. The calculation
uses sRGB relative luminance, not screenshot antialiasing pixels.

| Foreground / background | Light | Dark |
| --- | --- | --- |
| onSurface / surface | 16.12:1 | 14.32:1 |
| onSurfaceVariant / surface | 8.40:1 | 10.98:1 |
| onSurfaceVariant / surfaceContainerLow | 8.02:1 | 10.16:1 |
| onPrimary / primary | 7.88:1 | 7.75:1 |
| primary / surface | 7.45:1 | 10.92:1 |
| error / surface | 6.11:1 | 10.86:1 |

The `bun_do.xml` VectorDrawable path matches the approved SVG path byte for byte.
Its empty-state appearance was inspected in both themes. Exact evergreen and
paper tints preserve the owner's approved mark. There are no shipping raster
assets in this shell. The approved source-raster provenance remains in
`assets/brand/source/provenance.json`.

## Functional evidence and limits

The debug build, lint and three JVM tests passed. Eleven instrumentation tests passed
on each profile. The separate restart smoke exercised committed task and draft
durability across a real force-stop with connectivity off on both profiles.
[The adversarial review](android-shell-adversarial.md) records its material
findings and fixes. Repository invariants and all 38 Python tests passed again
after the design-document update.

No Compose detector ran. The Impeccable HTML/CSS detector does not validate native
Compose. TalkBack traversal, hardware keyboard traversal, physical-device
performance and full v1 accessibility remain unverified. The final native
interface/accessibility slice and release verification retain those checks.

## Independent finish review

Initial disposition: `fix`. The reviewer found two material issues. The landscape
queue reserved an empty detail pane before task selection, and PRODUCT.md still
said Android had not started. Both are fixed. The queue now fills available width
until a task is selected. Below 480 dp height, its notice keeps the local-only
label; Settings still exposes the full explanation. An orientation regression
test checks queue width, local-only status and capture reachability.

Post-fix disposition: `ship`. The verdict covers the two scored fixes, not the
full v1 interface. Both corrected landscape captures show the named language,
full-width queue, compact local-only notice, visible first task and reachable
capture action at font scale 2.0. No recapture regression was reported. The first
post-fix capture attempt was rejected because locale/orientation had not settled;
those files were replaced after explicit language, font-scale and image-dimension
checks. The [verbatim final verdict](../../.impeccable/review/finish-verdict.md)
records the reviewer's scope. No material shell finding remains deferred. The fresh reviewer received representative originals, contact sheets,
product/design contracts, Compose source and native craft guidance. There is no
approved UI comp, concept seed or quality-bar card for this incumbent,
architect-selected implementation. That missing design provenance must not be
reported as owner approval.
