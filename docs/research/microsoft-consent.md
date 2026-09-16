# Microsoft consent and a shared app registration

Research date: September 16, 2026. Analysis only. No registration, permissions,
credentials, authentication code or infrastructure settings changed.

## What we checked

Read-only `az ad app show` against the two IDs in
[development sign-in](../identity-development.md) confirmed:

| Setting | Android registration | API registration |
| --- | --- | --- |
| Display name | Bun Do development Android | Bun Do development API |
| Sign-in audience | PersonalMicrosoftAccount | PersonalMicrosoftAccount |
| Requested access token version | 2 | 2 |
| Required API permissions | Bun Do `access_as_user` | None |
| Known client applications | None | None |
| Preauthorized applications | None | Android, for `access_as_user` |
| Password/certificate credentials | None | None |

The Android registration contains debug and release Android redirects and a
localhost callback. The API has no public-client redirects. Neither registration
requests Microsoft Graph permissions. These are configuration observations,
not proof of which screens the user saw.

[The Android provider](../../src/BunDo.Android/app/src/microsoft/java/fi/bundo/identity/Provider.kt)
requests the explicit Bun Do API scope with `Prompt.SELECT_ACCOUNT`, not
`Prompt.CONSENT`. Its silent refresh uses the same scope and `consumers`
authority. There is no second explicit interactive sign-in call in that provider.

## Why there can be two prompts

The registrations represent the phone app and the API it calls. Preauthorization
and consent bundling are different settings:

- `preAuthorizedApplications` grants a specified client access to specified
  delegated scopes without separate user consent for those scopes.
- `knownClientApplications` groups a client and API registered in the same
  tenant for consent and service-principal provisioning.

These definitions come from Microsoft's [API application reference][api].
The second setting is absent in the current configuration. That is a candidate
for testing, not a confirmed explanation for the user's exact screens.

Microsoft's [bundled-consent troubleshooting guide][bundle] explicitly says
personal Microsoft accounts can require separate client and API consent prompts.
It describes `knownClientApplications` and a custom API `.default` request, but
still warns about two prompts for consumer accounts. Do not promise that adding
the setting will turn this into one screen. Its consumer-specific warning is
more relevant here than the generic reference's single-consent description.

We did not capture or reproduce the user's consent session. Distinguishing
account selection, initial consent and a second resource consent still requires
a fresh personal-account walkthrough.

## Is one registration reasonable?

Yes, it is worth testing for this first-party app. Microsoft's
[personal-account native-client and API sample][sample] explicitly registers
both parts under the same application ID. It adds the native platform and
exposes an API on that registration. The sample uses a desktop client and a
Graph-calling backend, so it demonstrates the registration pattern, not a
verified Android migration recipe or guaranteed screen count.

Bun Do does not need the sample's Graph permission or backend client secret.
It only validates incoming API tokens. The Android app must remain a public
client with no embedded secret; Microsoft's [client-type guidance][clients]
explains why installed clients cannot protect one.

My recommendation is to evaluate a shared registration while keeping the
existing API registration and audience. Add Android platform configuration to
that registration in an approved experiment, then change the experimental
client ID. Keep the old client working until upgrade and rollback tests pass.
Do not delete either live registration just to try this.

This trades separate client/API registration lifecycles for one consent identity.
That is a reasonable trade for one first-party Android app, but less attractive
if unrelated clients later use the API. It does not remove API token validation,
household membership checks or owner approval.

## Identity and upgrade risks

Microsoft documents `sub` as application-specific and the v2 access-token
`aud` as the API application ID. Changing the resource registration can therefore
change the identity used by the application. See the
[access-token claims reference][claims].

Bun Do intentionally keys ownership by the exact validated API issuer and
subject, not by email or the Android ID token.
[ADR 0003](../adr/0003-use-existing-workforce-tenant-for-sign-in.md) and
[the identity contract](../architecture/identity-and-operations.md) prohibit
automatic account linking. A changed API subject could strand household
membership and quarantined local work.

Retaining the API registration is therefore the lower-risk direction. Subject
continuity remains a test requirement, not an assumption. Changing the Android
client ID also warrants treating cached sign-in as unavailable until tested.
Plan for reauthentication without clearing tasks, pending writes or drafts.

Before approving a migration:

1. Test fresh and returning personal accounts with the signed Android build.
   Record screen count and permission wording without credentials or tokens.
2. Check the authenticated API returns exactly the same issuer/subject before
   and after the client change. Record equality only, not the identifiers.
3. Exercise refresh, sign-out, switching accounts, invitation approval, old-client
   coexistence, upgrade with pending work, and rollback.
4. Keep issuer, signature, audience, expiry and scope checks strict. A shared
   registration makes rejecting ID tokens without the required API scope
   especially important.

## Welcome-flow implications

Suggested choices are "Just this phone", "Create a family" and "Join a family".
The first should open the inbox without sign-in. Both shared paths need a
personal Microsoft account and connectivity under the existing contract.
Joining is not an anonymous alternative to creating.

Explain Microsoft sign-in in one short step, then return to the selected create
or join flow. Preserve invitation context across sign-in and cancellation without
logging its secret. An accepted invitation still waits for the owner's approval.
No shared tasks appear before that approval. Existing local tasks move only
through explicit selection, not automatically during welcome setup. These
boundaries come from the [identity contract](../architecture/identity-and-operations.md);
the welcome screens can use friendly language and artwork without exposing the
registration terminology.

[api]: https://learn.microsoft.com/en-us/graph/api/resources/apiapplication?view=graph-rest-1.0
[bundle]: https://learn.microsoft.com/en-us/troubleshoot/entra/entra-id/app-integration/bundle-consent-application-registrations
[sample]: https://learn.microsoft.com/en-us/samples/azure-samples/active-directory-dotnet-native-aspnetcore-v2/3-web-api-call-microsoft-graph-for-personal-accounts/
[clients]: https://learn.microsoft.com/en-us/entra/identity-platform/msal-client-applications
[claims]: https://learn.microsoft.com/en-us/entra/identity-platform/access-token-claims-reference
