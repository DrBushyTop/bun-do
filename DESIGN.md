# Bun Do design direction

Status: architect-selected planning contract, 2026-09-12. The owner delegated remaining design decisions. This resolves the planning choices in "Choose how Bun Do expresses the way of the bun." No rendered UI, final logo, device testing, or owner approval of those artifacts is claimed. Implement with Impeccable and its Android guidance, then reconcile this document and generate `.impeccable/design.json` from the reviewed Compose implementation.

## Identity

Bun Do means "the way of the bun." Bun is a bunny. Choose an orderly household notebook expressed through native Material 3 lists, with evergreen controls and a small, solemn martial-arts rabbit. People use this in kitchens, on errands, and in bed. Follow system light/dark appearance, with a settings override.

The palette is restrained. Brand appears in the launcher, welcome, empty queue, and optional completion bow. Keep ordinary task actions literal. No belts, scores, or rankings for members.

Build an original vector mark on a 48-unit square. Use a rounded compact body, two long ears with one slightly inclined, small neutral eyes, tucked forepaws, planted feet, and a single belt knot. Use at most six simple filled shapes plus facial marks. Keep the silhouette within the adaptive icon safe zone. At 24 dp omit eyes and knot detail. At 48 dp show both. Use ink on paper and paper on ink; evergreen supplies the launcher background. No crown, copied comic linework, weapons, lettering inside the icon, gradients, or bread imagery. The supplied King Bun reference informs the dignity and humor only.

## Tokens

Use named Material color roles in Compose. These are static light/dark targets. Wallpaper color is off for v1 to preserve the selected identity.

| Role | Light | Dark |
| --- | --- | --- |
| primary | `#245B48` | `#A1D2BA` |
| onPrimary | `#FFFFFF` | `#083827` |
| primaryContainer | `#BCEBD2` | `#28513F` |
| onPrimaryContainer | `#082116` | `#D6F8E4` |
| secondary | `#52635A` | `#B7CBC0` |
| onSecondary | `#FFFFFF` | `#23352C` |
| secondaryContainer | `#D5E8DC` | `#394B41` |
| onSecondaryContainer | `#102018` | `#D5E8DC` |
| surface / background | `#F8F9F4` | `#111511` |
| onSurface | `#191D19` | `#E0E4DD` |
| surfaceContainerLow | `#F2F4EE` | `#191D18` |
| surfaceContainer | `#ECEFE8` | `#1D211C` |
| surfaceContainerHigh | `#E6E9E2` | `#282C26` |
| onSurfaceVariant | `#444C44` | `#C2CAC0` |
| outline | `#747D73` | `#8C958B` |
| outlineVariant | `#C4CCC1` | `#444C44` |
| error / onError | `#BA1A1A` / `#FFFFFF` | `#FFB4AB` / `#690005` |
| errorContainer / onErrorContainer | `#FFDAD6` / `#410002` | `#93000A` / `#FFDAD6` |
| warningContainer / onWarningContainer | `#FFE2A9` / `#402D00` | `#574315` / `#FFE2A9` |

Remaining Material roles derive from the primary seed `#245B48`; verify generated values when freezing the implementation. Success uses primary roles; recording uses error roles plus an explicit label. Warning belongs to decisions requiring attention. Ordinary pending sync uses neutral roles.

Use Android's system sans family and Material type roles. Sizes/line heights in sp: headlineLarge 32/40, headlineSmall 24/32, titleLarge 22/28, titleMedium 16/24, bodyLarge 16/24, bodyMedium 14/20, labelLarge 14/20, labelMedium 12/16. Titles and labels use medium weight; body uses regular. Other roles retain Material defaults. No custom display font or uppercase tracking.

Spacing steps are 4, 8, 12, 16, 24, 32 dp. Screen gutters are 16 dp on compact widths, 24 dp otherwise. Flat queue rows use 12 dp vertical padding and a subtle divider. Sheets use 28 dp top corners; dialogs and cards 16 dp; chips and buttons keep Material shapes. Use tonal elevation, without custom shadows. Icons are Material outlined at 24 dp; every action has a 48 dp target and 8 dp separation.

## Behavior and quality

Rows grow with content and font scale. Queue titles allow three lines and then ellipsis; detail exposes the full title. Wrap metadata instead of shrinking it. Semantic labels name task, state, and action. Color never carries status alone. Native focus, pressed, disabled, loading, error, and selected states apply to every control. A disabled action explains its reason nearby.

Use 200 ms state transitions and 150 ms fades. The completion bow is on by default, can be disabled in Settings, and lasts 240 ms once after the local write succeeds. Respect system animation settings with immediate transitions and a static mark. No celebratory screen blocks continued work.

Release review must use native emulator/device captures, both themes, Finnish and English, font scales 1.0, 1.3, and 2.0, TalkBack, and contrast checks. Use two Android emulator profiles on this Mac for gestures, voice fixtures, interruptions and functional tests. The vivo X300 Ultra and OnePlus 13 remain target phones, but their performance and Finnish recognition quality are unmeasured owner-accepted assumptions; the skipped benchmark is not a hidden release gate. Impeccable's HTML/CSS detector does not validate Compose. Build fully, inspect once, batch corrections, then confirm once before the skill's independent finish review. Finalize the original vector logo and token sidecar from that evidence.
