# Install an Android prerelease

Download the `bun-do-...-arm64.apk` file from the GitHub release, not a source
archive. It targets ARM64 Android phones running Android 8 or newer. Allow
installation from your browser or file manager when Android asks, then turn
that permission off afterward.

This is a development-backend prerelease, not a Play Store production release.
Physical-phone performance and OEM background-reminder behavior still need
device testing. Typing works without sign-in. Household features require a
personal Microsoft account and connectivity for first sign-in. Local speech
requires the optional model download.

Future release APKs use the same signing key and install as updates. The
development debug APK uses a different key. Android cannot update it with this
release APK. Export pending text and retained audio first, then make an explicit
decision about removing the debug app. Never uninstall it merely to make a
build check pass.

The release includes `SHA256SUMS`, the public signing certificate report and a
dependency inventory. On a computer, `apksigner verify --verbose --print-certs`
checks the APK signature. The expected release certificate SHA-256 is:

```text
C3:EC:25:D5:D7:8F:92:B3:F7:DE:39:8C:92:20:6A:FB:41:6F:7D:51:C5:E4:AC:77:1F:C9:B9:C5:7F:C7:6B:CD
```

## Build and publish

Follow `docs/android-development.md` for JDK, SDK and wrapper setup. Build from
the release's `COMMIT.txt`. The GitHub Android release workflow runs only on
`master`, by explicit dispatch. It publishes a new prerelease tag and refuses
to replace an existing tag. Its run number supplies an increasing Android
version code. Fork pull requests have no signing step or signing secrets.

The workflow saves `android-release-package` after signature and checksum
verification, before uploading GitHub release assets. This artifact remains
available for 14 days if publication fails. Download it from that workflow run,
check `SHA256SUMS` and the certificate above, and confirm `COMMIT.txt` matches the
run's commit before retrying publication. Inspect the existing release and tag
first. Do not overwrite a published package or rebuild under an existing tag.
The artifact contains the signed APK and public verification files, never the
keystore or passwords.

Configure these repository secrets from a private key store:

- `BUNDO_KEYSTORE_BASE64`: base64-encoded release keystore.
- `BUNDO_STORE_PASSWORD`: keystore password.
- `BUNDO_KEY_PASSWORD`: signing-key password.
- `BUNDO_KEY_ALIAS`: signing-key alias.

For a local release build, load the same passwords and alias into the
environment and set `BUNDO_KEYSTORE_PATH` to the private keystore file:

```sh
src/BunDo.Android/gradlew -p src/BunDo.Android \
  -PbundoVersionName=0.1.0-rc.1 -PbundoVersionCode=101 \
  assembleRelease testReleaseUnitTest lintRelease releaseInventory
```

Missing signing inputs fail the release build. Do not put passwords in Gradle
properties, command-line arguments, repository files or build logs. Never use
`--debug` logging for signed builds. The workflow deletes its temporary
keystore even when a build fails.

Keep an encrypted, access-controlled backup of the keystore and its passwords
outside the repository and outside this workstation. Verify the backup can
open the key and matches the public certificate before relying on it.
GitHub secrets are not a recoverable key backup. Losing the signing key prevents
ordinary updates to installed APKs.

The Microsoft redirect configuration is signing-certificate-specific. Debug
and release resources and manifests intentionally differ. A new key also
requires registering its redirect with Microsoft and updating the verified
App Links certificate configuration through Bicep. Do not change the key just
to fix a build.

The inventory records resolved runtime coordinates, artifact hashes and
publisher POMs. It supports dependency inspection; it is not a claim that two
different SDK installations produce byte-identical APKs.
