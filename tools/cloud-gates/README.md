# Opt-in foundation gates

These are one-off commissioning experiments. Prefer
`python3 tools/azure-verify-storage.py --subscription SUBSCRIPTION_UUID` for
repeatable read-only storage policy checks. Follow the
[invariant test policy](../../docs/agents/invariants.md#adding-or-changing-steering-checks)
before extending tests. A probe pass does not prove production adapter behavior.

These sources never compile into the normal Function package. Hosting tests
link `FoundationGateChecks.cs` to exercise classifiers, request construction and
cleanup without Azure access.

After stopping any local Aspire run:

```sh
dotnet restore BunDo.sln --locked-mode
python3 tools/azure-gates.py build
```

The build temporarily copies the two C# files into the Function project, then
removes them and cleans generated metadata before building the normal package.
Review the gate code, tests and `.azure/foundation-gates/artifacts.json` with
a fresh adversarial subagent before running:

```sh
python3 tools/azure-gates.py run
```

Run verifies exact endpoints and identity against management-plane resources in
`rg-bun-do-dev-swc`. Naming-pattern checks in C# are an additional check, not proof
of resource-group scope. The gate has function-key authorization and accepts no
caller-provided content, endpoints or IDs. Keys remain in memory, never URLs.

The storage gate creates one synthetic random-partition Cosmos document and one
synthetic blob, reads both, checks that account-level Blob listing is forbidden,
then deletes its artifacts and verifies 404. It never upserts or writes to
another existing item. A lost create response still triggers cleanup. Inspect
cleanup results; a failed cleanup can leave the reported synthetic ID behind.

The AI gates send an intentionally invalid strict schema and one benign request
with a 16-token output ceiling. Each sends once. Schema rejection and token-limit
incompletion need specific allowlisted provider evidence, not just HTTP 400 or
an incomplete status. Refusal and throttling use deterministic fixtures rather
than unsafe prompts or a quota-exhausting request flood.

A dispatch marker prevents rerunning paid or ambiguous requests. Do not erase it
to retry. Diagnose the result and review any new experiment separately. The run
restores the normal package in `finally`, then verifies Health, absent gate
routes and the live function list. If restoration fails, run only:

```sh
python3 tools/azure-gates.py restore
```

This restores the already-built normal artifact without repeating gates or
requiring the disposable gate ZIP. It is not rollback to the previously deployed
artifact. Restoration still checks the target configuration and can fail if it
has drifted; inspect and correct that mismatch, never rerun paid gates to recover.
Keep this workflow limited to reviewed commissioning where replacing the dev
package is acceptable. New application integration tests must exercise their
production adapters rather than copy them into more temporary functions.

These checks prove basic data-plane identity and provider responses, not the production
Cosmos adapter, revisioned sync, snapshot expiry or durable AI retries.

REST references checked September 12, 2026:

- [Cosmos authorization](https://learn.microsoft.com/en-us/rest/api/cosmos-db/access-control-on-cosmosdb-resources)
- [Create a document](https://learn.microsoft.com/en-us/rest/api/cosmos-db/create-a-document)
- [Put Blob](https://learn.microsoft.com/en-us/rest/api/storageservices/put-blob)
