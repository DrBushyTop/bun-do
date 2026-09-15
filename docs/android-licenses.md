# Android dependency licenses

Each signed release includes resolved runtime coordinates, artifact SHA-256
hashes and publisher POMs in its inventory archive. POM license declarations
are publisher metadata, not a substitute for the full license text.

The app uses these dependency families:

| Dependency family | License / upstream notice |
| --- | --- |
| AndroidX, Compose, Room and WorkManager | Apache-2.0, Android Open Source Project |
| Kotlin and kotlinx | Apache-2.0, JetBrains |
| Microsoft MSAL and Identity Common | MIT, Microsoft |
| Microsoft dual-screen layout | MIT, Microsoft |
| Gson and Guava | Apache-2.0, Google |
| OkHttp and Okio | Apache-2.0, Square |
| Apache Commons Compress, IO, Codec and Lang | Apache-2.0, Apache Software Foundation |
| SQLCipher Android | BSD-style SQLCipher license, Zetetic. Consult the artifact and upstream SQLCipher notices for native dependencies. |
| sherpa-onnx | Apache-2.0, k2-fsa. The verified AAR includes ONNX Runtime. |
| ONNX Runtime | MIT, Microsoft, with bundled third-party notices |
| SLF4J | MIT, QOS.ch |
| Nimbus JOSE JWT, Yubico YubiKit, OpenTelemetry, JSpecify and JCIP annotations | Apache-2.0, respective publishers |
| Google Play Services and Google Identity | Android Software Development Kit License, Google. These are not Apache-licensed AndroidX libraries. |

The exact release inventory, including transitive components not listed above,
is authoritative for what was resolved. The app also ships the speech runtime
and model notices shown by its model installation flow. Model weights are
downloaded separately and are not part of the APK. Review their license before
installation.

Upstream license sources:

- https://github.com/AzureAD/microsoft-authentication-library-for-android/blob/dev/LICENSE
- https://github.com/sqlcipher/sqlcipher-android/blob/master/LICENSE.md
- https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE
- https://github.com/microsoft/onnxruntime/blob/main/LICENSE
- https://www.apache.org/licenses/LICENSE-2.0

Illustration and font provenance lives in `assets/illustrations/ARTWORK.md` and
the bundled font license files. No quest or AI-generated adventure service is
included in this release.
