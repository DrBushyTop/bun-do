# Use personal Microsoft accounts with MSAL

On September 13, 2026, the owner chose MSAL Android with Microsoft-hosted
sign-in using personal Microsoft accounts directly. The existing `huuhka.net`
workforce tenant owns the application registrations; users do not need an
account or guest invitation in that tenant. This replaces the External ID
customer tenant and native email OTP decision. The household app does not need
a separate customer directory or a Better Auth service.

Use a dedicated Android public client and delegated Bun Do API with the personal
Microsoft account audience. Work or school accounts are outside this selected
sign-in flow. Bun Do household invitations and owner approval control shared
data access independently of Microsoft authentication. External registration
identifiers and verification steps live in [development sign-in](../identity-development.md).

Keep exact validated issuer/subject identity, API signature/audience/scope/expiry
checks, account-isolated offline data and same-account recovery. Use the API's
validated subject for local ownership because tokens for different app
registrations can have different subjects. No customer tenant was provisioned;
only deployment previews ran. The initial single-tenant registration settings
were changed before any Bun Do user sign-in.
