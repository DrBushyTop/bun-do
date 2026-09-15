# Development sign-in

Bun Do uses personal Microsoft accounts with Microsoft-hosted sign-in through
MSAL Android. The existing `huuhka.net` workforce tenant owns the registrations.
[ADR 0003](adr/0003-use-existing-workforce-tenant-for-sign-in.md) records why.

## Application registrations

These are non-secret identifiers for the dedicated development applications.
They are external configuration, not a substitute for the checked-in Android and
backend settings.

| Setting | Value |
| --- | --- |
| Registration owner tenant | `7135bcf1-5a12-4e82-ad41-c263afa243e8` |
| Android client | `dd1dff63-d345-423f-95a4-eac3937e8992` |
| API audience | `d33b7867-41ed-45c3-805a-b5e841323d20` |
| Requested scope | `api://d33b7867-41ed-45c3-805a-b5e841323d20/access_as_user` |
| Authority | `https://login.microsoftonline.com/consumers` |
| Expected v2 issuer | `https://login.microsoftonline.com/9188040d-6c67-4c5b-b112-36a304b66dad/v2.0` |

Both applications use `PersonalMicrosoftAccount`. The API exposes only the
Bun Do delegated scope and preauthorizes the dedicated Android client. Neither
registration has client secrets, certificate credentials, or Microsoft Graph
permissions. Never embed a client secret in the APK.

The registered local debug redirect is
`msauth://fi.bundo/JMd5dw0RAj%2FKIKcQy1gU4vBukKc%3D`. A different developer or
release signing certificate requires its own redirect registration and matching
Android configuration. Do not copy a private keystore to make redirects match.

Microsoft sign-in and Bun Do household approval are separate. A successful
Microsoft login must never grant household access by itself.

## Verify registrations

Read both registrations and service principals with Microsoft Graph. Verify the
owner tenant, personal-account audience, API v2 token setting, delegated scope,
Android redirect, and API preauthorization. This proves setup only. It does not
prove browser consent, refresh, API validation, or account isolation. Record
those live results in [the sign-in slice](https://github.com/DrBushyTop/bun-do/issues/18).

## Microsoft live check

Start the local backend with the API audience, then build and install debug on a
dedicated emulator. Complete the browser flow personally. Verify refresh through
the Account screen. Do not log or copy tokens.

```sh
ASPIRE_CONTAINER_RUNTIME=podman ASPIRE_ENVIRONMENT=Local \
  BunDoIdentity__Audience=d33b7867-41ed-45c3-805a-b5e841323d20 \
  aspire start --apphost src/BunDo.AppHost/BunDo.AppHost.csproj --non-interactive
aspire wait functions --non-interactive

cd src/BunDo.Android
./gradlew --no-daemon assembleDebug
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

For an opt-in check against the dedicated development API, use the approved
HTTPS build property from `app/build.gradle.kts`. Release signing needs its own
registered redirect. A release assembly alone does not prove release setup.

## Local fictional accounts

The `local` build exists for unattended development. It has Alice/Bob choices
and no Microsoft browser flow or credentials. It is useful for account isolation
and recovery checks, not for proving Microsoft integration.

```sh
aspire stop --non-interactive
ASPIRE_CONTAINER_RUNTIME=podman ASPIRE_ENVIRONMENT=Local \
  BunDoIdentity__Mode=Local \
  aspire start --apphost src/BunDo.AppHost/BunDo.AppHost.csproj --non-interactive
aspire wait functions --non-interactive
python3 tools/local-identity-smoke.py

cd src/BunDo.Android
./gradlew --no-daemon assembleLocal testLocalUnitTest lintLocal
adb -s emulator-5556 install -r app/build/outputs/apk/local/app-local.apk
python3 tools/android-local-identity-smoke.py emulator-5556
```

Run the smoke on each dedicated emulator profile. It uses synthetic data and
does not establish browser consent, production signing, or real token refresh.
Stop Aspire after testing.

## Household links

Owners share invitation links themselves. Secrets live in the URI fragment, not
the request path or query. If a share response is lost, cancel the unused
invitation and create another. The server cannot recover its secret.

Android App Links require the signing certificate to match the website
association. Set `androidSigningCertificates` in the Bicep parameters to the
colon-separated SHA-256 fingerprint reported by `keytool -list -v`. Separate
multiple accepted certificates with semicolons. Publish the backend and verify
that its `/.well-known/assetlinks.json` endpoint serves the intended app and
certificate, without a redirect. The development parameters explicitly trust
the development and release certificates. A signing-key change must update
that trust alongside the MSAL redirect setup. See [Android releases](android-release.md).

Install the corresponding APK, then run `adb -s SERIAL shell pm verify-app-links
--re-verify fi.bundo`. After verification completes, inspect `pm get-app-links
fi.bundo` and open a synthetic HTTPS invitation. It must open the authenticated
join screen without exposing shared data before approval. When the app is not
installed, the link page explains how to paste the full link into the app.
The Local build uses app-scheme links and does not prove HTTPS association.

Do not put real invitation links, codes, bearer tokens, or private UI dumps into
test evidence. Record results in the household issue.
