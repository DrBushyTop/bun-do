# Keep Cosmos behind the workspace storage interface

On September 12, 2026, the owner chose to keep Cosmos DB after considering
[Convex](../research/convex-vs-cosmos.md), primarily because of European deployment
costs. Keep the task rules, sync protocol and Android offline storage independent
of the server database. A later switch should replace the storage adapter and
deployment, not the screens or conflict rules.

The existing `IWorkspaceStore` is the in-memory model's transaction interface.
Do not implement it in production by loading or replacing an entire workspace.
During the Cosmos sync slice, evolve it to bounded asynchronous reads and
atomic commits using the concrete operations that slice needs. Keep Cosmos SDK
types, SQL, ETags, partition mechanics and retry classification inside
`src/BunDo.Functions/Storage/Cosmos/`. The composition root may register that
adapter without taking a dependency on its SDK types.

The replacement must still prove atomic effects plus receipts, conditional
workspace revisions, consistent reads, complete ordered change groups, safe
retry after an unknown outcome, and recovery across epochs. Provider-specific
query tokens stay inside the adapter; they are not client sync cursors.
Run the same behavioral contract against the in-memory and live adapters.

Keep Room behind `InboxRepository` on Android and add network sync inside the
local workspace module, never in Compose. Android talks to Bun Do's versioned
protocol, not a Cosmos or Convex SDK. Do not add a generic CRUD repository,
database query language, provider switch, or second production adapter now.

A future migration still needs data export/import, ID and sequence preservation,
authentication integration and a restore drill. These interfaces limit the code
that changes; they do not make migration automatic or weaken the current
consistency and backup requirements.
