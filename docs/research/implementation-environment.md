# Implementation environment and first deployment gates

Reviewed 2026-09-12 for [#12, Prove the Azure deployment and Android sign-in path](https://github.com/DrBushyTop/bun-do/issues/12). This is a read-only inventory and an implementation plan. No Azure resource was created, changed, deployed to, or invoked. No model request was sent. No credential, key, token, or tenant identifier is recorded here.

This inventory describes the planning pass, not the current implementation.
The [Azure development guide](../azure-development.md),
[Android development guide](../android-development.md) and
[local development guide](../local-development.md) record the later builds and
live checks. The Cosmos foundation is now deployed; the full cloud and identity
gates below remain incomplete.

The project can start implementation now. It cannot yet claim that the cloud path works. The first infrastructure slice must prove that path in a new, dedicated Bun Do resource group before application features depend on it.

## What exists on this Mac and in Azure

The Azure CLI has an authenticated session in an enabled Microsoft Azure Sponsorship subscription. The CLI is version 2.89.1. This proves that the machine can make Azure control-plane reads with the current login. It does not prove permission to create the Bun Do resources, spending eligibility, data-plane access, model invocation, or Entra External ID configuration.

The installed backend toolchain is usable for the first server project:

- .NET SDK 10.0.400 on Apple Silicon, with .NET 8, 9, and 10 runtimes.
- Azure Functions Core Tools 4.11.0.
- Native Bicep CLI 0.43.8. `az bicep version` does not find a CLI because Azure CLI does not use the separately installed native executable. Use `bicep` directly in local validation, or install Azure CLI's managed Bicep later if the deployment script requires `az bicep`.

No Bun Do application, Bicep source, Function project, Android project, or dedicated Bun Do Azure resource group exists in this repository or subscription inventory. Existing Azure resources belong to other work and are not candidates for Bun Do reuse.

The Mac has Homebrew's `adb` 37.0.1, and no connected device. It has no Android SDK directory, `emulator`, `sdkmanager`, `avdmanager`, Android Studio installation, Gradle executable, or selected Java runtime. An Android emulator cannot be created or run yet. The installed JDK package is not enough until `JAVA_HOME` points at it or Android Studio supplies its bundled runtime.

## Dedicated Azure target

The owner has authorized a new dedicated Bun Do resource group and supplied this planning assumption: Foundry will make the required models available there. Treat that as a deployment input, not live proof. Do not reuse the OpenAI or Foundry accounts found during the read-only inventory.

Use Sweden Central (`swedencentral`) for the first development deployment. Keep `location` parameterized for later environments, but do not choose a second dev region. Keep Functions, Cosmos DB, Application Insights, Storage, and the Azure AI Foundry resource in that region unless a service forces a documented exception. Record the selected region in the deployment evidence.

Create a resource group named `rg-bun-do-dev-<location-short>` first. Keep production separate. The first deployment is development only, even if the owner uses it daily during testing.

The required model names and versions are configuration, not code constants:

| Role | Required deployment name | Required model and version | Status today |
| --- | --- | --- | --- |
| Extraction and clarification | `bun-do-luna` | `gpt-5.6-luna`, `2026-07-09` | Not deployed or invoked for Bun Do. Owner-supplied availability assumption. |
| Optional fallback | `bun-do-terra` | `gpt-5.6-terra`, `2026-07-09` | Not deployed or invoked for Bun Do. Owner-supplied availability assumption. |

A read-only `az cognitiveservices account list-models` call against an unrelated existing Swedish Central account lists both model/version pairs and compatible SKUs. That is useful corroboration of subscription catalog visibility, but it does not prove capacity, quota, deployment success, or endpoint access in the new resource group. The model catalog and version must be captured again from the new Bun Do account after deployment. Azure's current model documentation and the Responses/structured-output constraints remain in [Azure platform constraints](azure-platform-constraints.md).

## Bicep plan

The module-name sketch below is historical. During implementation the owner
requested modules grouped by application responsibility, not one-resource
wrappers. The current [Azure development guide](../azure-development.md) and
`infra/` source supersede this sketch. The workspace store is implemented as
`workspace-store.bicep`; backend hosting will own its runtime storage and
identity grants, rather than separate storage-account and authorization wrappers.

Put infrastructure under `infra/`. Use a subscription-scope entry point only to create the dedicated resource group, then deploy resource-group modules. The deployment must take names, location, environment, model capacities, and optional features as parameters. It must never contain a key, connection string, FCM credential, or app-registration secret.

```text
infra/
  main.bicep                         subscription scope, resource group only
  modules/
    observability.bicep              Log Analytics and Application Insights
    storage.bicep                    Function host storage
    snapshots.bicep                  private Blob storage/container for sync snapshots
    functions-flex.bicep             Flex Consumption plan, Function App, system MI
    cosmos.bicep                     NoSQL account, database, workspace-items container
    foundry-openai.bicep             Azure OpenAI/Foundry account and Luna/Terra deployments
    authorization.bicep              Azure RBAC assignments for the Function identity
  dev.bicepparam                     non-secret development values
```

`functions-flex.bicep` should follow Microsoft's current Flex Consumption Bicep shape, including the Function App's `functionAppConfig` and deployment storage settings. Pin the Function project to .NET 10 isolated and compatible Worker packages. Flex Consumption and Bicep configuration change over time, so validate the generated template against Microsoft's current guide before the first deployment. [Flex Consumption plan](https://learn.microsoft.com/en-us/azure/azure-functions/flex-consumption-plan), [Function App Bicep deployment](https://learn.microsoft.com/en-us/azure/azure-functions/functions-create-first-function-bicep), [isolated worker support](https://learn.microsoft.com/en-us/azure/azure-functions/dotnet-isolated-process-guide#supported-versions).

`cosmos.bicep` should create a single-region NoSQL account with **Strong** consistency, a SQL database, and exactly one `workspace-items` container with `/workspaceId` as the partition key. The sync protocol deliberately rejects Session consistency and does not carry session tokens between Functions instances. `snapshots.bicep` should create a separate private Blob container for authenticated sync snapshot artifacts. Cosmos resource provisioning does not prove the transactional batch or sync protocol. [Cosmos DB Bicep quickstart](https://learn.microsoft.com/en-us/azure/cosmos-db/nosql/quickstart-bicep), [transactions and optimistic concurrency](https://learn.microsoft.com/en-us/azure/cosmos-db/database-transactions-optimistic-concurrency).

`foundry-openai.bicep` should create the Bun Do AI account and deployments named `bun-do-luna` and `bun-do-terra`, using model name, version, SKU, and capacity parameters. Luna is required. Terra remains deployed only if the owner wants the fallback available from day one. The Function App uses its system-assigned managed identity with the narrow Azure AI inference role needed by the chosen endpoint. Do not use account keys in Function settings. The implementation must send the deployment name in the Responses `model` field, not the catalog model name. [Azure OpenAI Bicep deployment](https://learn.microsoft.com/en-us/azure/ai-foundry/openai/how-to/create-resource-bicep), [Responses API](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/responses).

`authorization.bicep` should give the Function identity the least Azure data-plane access needed for Cosmos and AI inference. Assign role definitions by ID through parameters or documented constants. Record each final principal ID and role assignment in a private deployment record, not this repository. Application configuration should use managed identity and Key Vault references only where a secret is unavoidable, such as an FCM server credential.

Do not place customer authentication configuration in the same Bicep deployment by default. Entra External ID belongs to its own tenant and application-registration lifecycle. Create the Android public client and API application registration, delegated API scope, redirect URI, and native-authentication setup through the approved tenant process. Microsoft's app-registration quickstart documents the registration baseline, but the External ID native-authentication setup still needs a tenant-level live check. [Register an application](https://learn.microsoft.com/en-us/entra/identity-platform/quickstart-register-app), [Android native authentication](https://learn.microsoft.com/en-us/entra/identity-platform/concept-native-authentication).

## Android emulator route

Install Android Studio for Apple Silicon, then use its SDK Manager to install:

1. an Android SDK Platform matching the project's compile SDK;
2. Android SDK Build-Tools and Platform-Tools;
3. Android Emulator and Android SDK Command-line Tools;
4. an ARM64 system image, preferably a Google APIs image, for the chosen API level.

Set `ANDROID_HOME` to the installed SDK path and add `platform-tools`, `emulator`, and `cmdline-tools/latest/bin` to `PATH`. Create one ARM64 Pixel-class AVD. Boot it with `emulator -avd <name>`, confirm it appears in `adb devices -l`, then run the Compose app through Gradle. Do not treat emulator sign-in as a substitute for the two target physical phones. It is the route for UI, Room, WorkManager, offline, API, and token-error tests while phones are unavailable.

## Early implementation gates

Use these gates for the replacement build/readiness work. Each gate blocks the next dependency rather than the entire application. Each gate produces a short private evidence record with command/version, region, deployment names, response status, and redacted timing. Never attach tokens, keys, household task content, or full JWTs.

| Gate | Do after | Pass condition | Unlocks |
| --- | --- | --- | --- |
| G0: template validation | First Bicep source | `bicep build` succeeds and `az deployment sub what-if` is reviewed against the dedicated Bun Do resource group. | Permission to create the dev resources. |
| G1: infrastructure deployment | G0 | Dedicated dev resource group contains the Function App, storage, observability, Cosmos container, AI account/deployments, and required RBAC. Record region, SKUs, capacities, identity roles, and actual deployment versions. | Real server integration. |
| G2: model contract | G1 | The Function identity completes one redacted Luna Responses request with `store: false` and each complete strict schema. Record accepted schema, deployment/version, HTTP result, latency, refusal/incomplete handling, and throttling behavior. Run Terra only if deployed. | AI enrichment, clarify, and split adapters. |
| G3: Cosmos sync contract | G1 | Two independently running Function instances process concurrent mutations. Prove conditional sync-state write, unique revisions, transactional rollback at a forced batch failure, duplicate operation receipt replay, and cursor correctness. | Sync endpoint implementation. |
| G4: External ID Android contract | Android shell plus tenant setup | Two emulator users complete email OTP sign-up/sign-in and obtain the Bun Do API scope. The API accepts a valid token and rejects expired, wrong-audience, wrong-scope, and removed-membership cases. | Authenticated sync UI. |
| G5: end-to-end offline replay | G3, G4 | Two emulator profiles create and mutate tasks offline, reconnect, receive semantic conflict results, and converge through Room. Include recurrence and nested-task mutations in the fixture. | Feature implementation on the full v1 scope. |

G1 needs the first approved resource creation and may incur normal Azure charges. G2 intentionally sends model requests and incurs model usage. Those actions are authorized for the new dedicated resource group by the owner, but neither action happened during this planning pass.

## Feasible route and remaining gates

Start three tracks in order: scaffold the Android app and server contracts, write and validate Bicep without deployment, then provision the dedicated dev resource group and execute G1 through G4 before binding feature work to cloud assumptions. The local machine is ready for .NET and Bicep work today. Android work starts after Android Studio and an ARM64 AVD are installed.

The only cloud facts proved in this pass are an enabled Azure CLI login, existing local tool versions, and catalog visibility from an unrelated account. The dedicated resource group, model deployments, quota/capacity, managed-identity authorization, Functions deployment, External ID tenant configuration, model behavior, Cosmos behavior, and Android token flow remain live validation gates.
