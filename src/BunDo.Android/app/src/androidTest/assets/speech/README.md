# Speech fixtures

## Finnish

`fi.pcm` is the first Finnish test-set recording in Google FLEURS, selected
before running recognition, `8113444208838751521.wav`. Dataset revision
`70bb2e84b976b7e960aa89f1c648e09c59f894dd`, source
`https://huggingface.co/datasets/google/fleurs`. FLEURS by Conneau et al.,
2022, CC BY 4.0. Attribution and license:
`https://creativecommons.org/licenses/by/4.0/`. The license text also ships in
the app's `assets/speech/CC-BY-4.0.txt`.

Reference:
`Suurin osa valtiossa työskentelevistä puhuu italiaa myös arkikielenään, mutta uskonnollisissa toimituksissa käytetään usein latinaa.`

Converted from the public WAV to signed little-endian PCM16, mono, 16 kHz
using ffmpeg. No transcript or audio edits. The integration test allows at most
20% word error against the entire reference, ignoring case and punctuation.
It rejects garbled or wrong-language output without requiring perfect spelling.

An earlier eSpeak Finnish input, `Muista ostaa maitoa ja leipää huomenna.`,
failed its keyword check. The model returned `Muista maitoa, yaley pain na.`
with upstream-matching configuration. That is a recognition limitation on
that synthetic input, not a passing test. The public human fixture provides
a separate functional check, not proof of target-phone accuracy. Its initial
exact-keyword assertion also failed on `italia` versus `italiaa`. The whole
reference error check replaces those two brittle keyword assertions for both
languages; the recognized text is never rewritten to make the test pass.

## English

`en.pcm` contains synthetic speech generated with eSpeak NG 1.52.0, en-us
voice at 135 words/minute. The text was written for this test:
`Remember to buy milk and bread tomorrow.`

```sh
espeak-ng -v en-us -s 135 -w en.wav 'Remember to buy milk and bread tomorrow.'
ffmpeg -i en.wav -ar 16000 -ac 1 -f s16le en.pcm
```

Neither clip comes from a target phone or contains private household content.

`ParakeetDeviceTest` runs the real pinned JNI runtime, verifies key words in
both languages, commits each transcript and verifies audio cleanup. Its
explicit `speechModelInstalled=true` argument requires a model already
installed through the app. Default instrumentation skips this one test rather
than downloading 465 MiB implicitly. The optional `speechInstallArchive=true`
argument installs a locally supplied, checksum-verified archive from
`cache/speech-model.tar.bz2` and deletes that fixture archive afterward.
