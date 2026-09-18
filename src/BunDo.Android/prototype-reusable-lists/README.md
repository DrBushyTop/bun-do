# Reusable household lists prototype

Throwaway interaction study for issue #76. No Android or backend implementation.

From the repository root:

```sh
python3 -m http.server 4176 --directory src/BunDo.Android/prototype-reusable-lists
```

Open `http://localhost:4176/?variant=A`. Options A, B and C compare pinned lists beside tasks, a Lists destination and activity-first guidance. Use the bottom arrows or left/right keyboard arrows outside fields. Changes stay in memory and reset on refresh. All household data is invented.

Try groceries, add a recent purchase again, then start a cottage checklist with selected items. Save a checklist for reuse, edit one trip, and start another copy. First use and larger text are under the study controls. The state inspector exposes the lists and saved copies.

This study proposes item-level self-claims and keeps a standing grocery list open after every item is checked. Fresh checklists finish when their items are checked. These are interaction hypotheses, not new product contracts. Counts, retention, concurrent edits, deletion policy, actual offline sync, speech and Finnish translation need decisions or native verification before implementation. Saved copies carry text, notes and order, never old checks, claims or dates. Cancel leaves the source untouched.

Do not merge this prototype into the application. Record selection and review evidence in issue #76, then create implementation slices from the approved direction.
