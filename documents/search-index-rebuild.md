# Search Index Rebuild

Rebuilds the full-text search index (Lucene) of every node from the
repository content **while the system stays fully operational**: search
and content updates keep working throughout, and the finished index
replaces the old one atomically. Administrators run it from the Webtop
Tasks application; each node rebuilds its own node-local index in the
background with live, per-node progress.

## When to rebuild

- After changing the search configuration under `<workspace>/etc/search`
  (analyzers, `mapping.txt`, dictionaries, stop words) — existing
  documents were indexed with the old configuration.
- When the index is suspected to have drifted from the content (e.g.
  after restoring content from a backup that predates the index, or
  after an interrupted bulk operation left stale hits).
- When directed by upgrade notes.

There is no need to rebuild after normal operation: every content
transaction updates the index incrementally, a node whose index
directory is missing rebuilds it at startup, and a clustered node that
was offline longer than the journal retention discards and rebuilds its
index automatically.

## Running a rebuild (Webtop)

1. Open the **Tasks** app and switch to **Start a process**.
2. Select **Search Index Rebuild**. The start form describes the
   operation; the start button is shown to administrators only (and the
   server enforces the role again at dispatch — see
   [Authorization](#authorization)).
3. Start the rebuild. A **Search Index Rebuild Progress** task appears
   in your task list, assigned to you.
4. Open the progress task at any time. It shows one row per cluster
   node — status, phase, a progress bar (`indexed / total`), the item
   currently being indexed, and any error — updated live. Closing the
   window does not affect the rebuild; the task stays in your list and
   shows the current state whenever you reopen it.

The phases a node moves through:

| Phase | Meaning |
|-------|---------|
| Counting items | Counting the indexable files for the progress total |
| Indexing | Building the complete replacement index in a staging area |
| Catching up on changes | Re-applying what changed while the traversal ran |
| Activating the new index | Swapping the staged index in as the live index |

Controls on the progress form:

- **Abort** (per node) — signals that node's job to stop at its next
  safe point. The live index is untouched; the staging area is
  discarded. Abort works from any node (see
  `documents/clustering.md`, "Built-in background jobs").
- **Re-run failed nodes** — shown once every node has finished and at
  least one failed or was aborted. Dispatches new jobs for exactly
  those nodes; the completed nodes' results stay visible.
- **Complete task** — shown once every node has finished; ends the
  process. (Simply closing the window keeps the task open instead.)

Run **one rebuild at a time**. A node runs a single staged rebuild at
once; a second concurrent request fails on the busy nodes with
"A rebuild session is already in progress."

## Reliability

The rebuild is **staged**: the replacement index is built next to the
live one (`<index>/rebuild/`), and the live index keeps serving queries
and incremental updates until the staged index is complete and caught
up. The swap is a short exclusive window (queries block for its
duration, typically well under a second); in-flight query results keep
reading from the superseded index for a grace period.

Consequences:

- **Abort or crash never damages the index.** Until the swap, the live
  index is untouched; an interrupted swap itself is rolled forward the
  next time the index opens (`swap.state` marker).
- **A node restart resumes automatically.** Rebuild jobs that died with
  the node are re-queued at startup and run again from scratch (a
  rebuild is idempotent). The progress form reflects the restarted run.
- **Changes during the rebuild are not lost.** Content committed while
  the traversal runs is recorded and re-applied to the staged index in
  catch-up rounds; the final round runs with the commit pipeline briefly
  paused so nothing can fall between it and the swap.

## Cluster behaviour

The search index is node-local (see `documents/clustering.md`), so one
rebuild request creates **one background job per alive cluster node**,
persisted under `/var/jobs` with a shared `jobRebuildId`. Persisting the
dispatch makes it durable:

- A running node picks its job up within the cluster journal's poll
  latency (the job records replicate like any content and surface as
  local node events).
- A node that was **down at dispatch time** finds its QUEUED job in a
  startup scan when it returns.
- A node that **joins the cluster after** the dispatch is listed on the
  progress form as "not targeted" — it builds its index at startup
  anyway, so no action is needed.

Progress, abort and re-run work from any node: state lives in content,
and aborting a job running elsewhere marks it ABORTING for that node's
worker to observe (about every 5 seconds).

## Authorization

- **Starting** requires the `administrator` role. The start form hides
  the button from everyone else, and the dispatch script independently
  validates the initiator's role server-side — a process started around
  the form ends as "Rejected" without creating any job.
- The dispatch script runs as the non-interactive service account
  `searchindex-service-user` (BPMN `runAs`), which holds only `jcr:read`
  on the script folder. Both the account and the ACL are provisioned
  declaratively (`etc/jcr/provisioning/searchindex.yml`).
- The BPMN process, the dispatch script and the forms live under
  `/etc/bpm/`, writable by administrators only — deploying a form or a
  process is equivalent to deploying an application.

## Automation

- **Scripts** (Groovy, EIP routes): `SearchIndexAPI.requestRebuild(
  rebuildId [, nodeIds])` creates the per-node jobs directly and returns
  a `nodeId → jobId` map. The API performs no authorization of its own —
  the caller is responsible, exactly like the BPMN dispatch.
- **GraphQL**: `jobProgress(jobId)` (query and subscription) follows a
  job; `abortSearchIndexRebuild(input: {jobId})` aborts one node's job.
- **Java**: adapt a session to
  `org.mintjams.jcr.search.SearchIndexRebuilder`
  (`countIndexableItems()` / `rebuild(monitor)`); the staged build and
  swap live behind `SearchIndex.createRebuildSession()` in the
  `org.mintjams.searchindex` API.

## Configuration

| Setting | Where | Default | Meaning |
|---------|-------|---------|---------|
| `org.mintjams.jcr.searchindex.rebuildThreads` | framework property | one per processor, capped below the session pool | Worker threads of the traversal; `1` forces a single-threaded rebuild |
| `org.mintjams.searchindex.ramBufferSizeMB` | framework property | Lucene default ×16 | Index writer RAM buffer; larger values reduce segment flushes during bulk indexing |
| `org.mintjams.searchindex.maxContentLength` | framework property | 1,000,000 chars | Bound on extracted text per document |
| `search.indexPath` | `<workspace>/etc/jcr/jcr.yml` | `var/search` (standalone), `var/search/nodes/<nodeId>` (clustered) | Location of the node-local index |

On-disk layout of an index during and after a rebuild (all inside the
index directory; everything except `documents/` and `suggestions/` is
transient):

| Entry | Meaning |
|-------|---------|
| `documents/`, `suggestions/` | The live index |
| `rebuild/` | Staging area of a running rebuild; discarded on abort, crash, or the next open |
| `swap.state` | Present only while a swap is in flight; its presence at open rolls the swap forward |
| `documents.old/`, `suggestions.old/` | The previous index, parked during a swap and deleted after the new one opens |
| `.rebuild-incomplete` | Startup-rebuild marker (predates the staged rebuild; kept for compatibility) |

## Implementation map

| Layer | Where |
|-------|-------|
| Staged index + swap (`RebuildSession`) | `org.mintjams.searchindex` (API), `org.mintjams.rt.searchindex` (`SearchIndexImpl`, `RebuildSessionImpl`) |
| Traversal, catch-up, pipeline pause | `org.mintjams.rt.jcr` — `JournalObserver`, exposed as `org.mintjams.jcr.search.SearchIndexRebuilder` |
| Background job + per-node pickup | `org.mintjams.rt.cms` — `job/searchindex/SearchIndexRebuildJob`, `searchindex/SearchIndexRebuildService`, script API `SearchIndexAPI` |
| Process, script, forms | `/etc/bpm/{processes,scripts,forms}/system/searchindex/` (workspace `system`) |
| i18n | `/etc/i18n/searchindex-forms.{en,ja}.json` |

## Troubleshooting

- **A node shows FAILED** — open the row's error message. The staged
  rebuild left the live index untouched; fix the cause and use
  **Re-run failed nodes**. "The node restarted before the job finished"
  normally never surfaces here: such jobs are re-queued automatically at
  startup.
- **A node shows "node down"** — its job stays QUEUED and runs when the
  node returns (startup scan). No re-dispatch is needed.
- **The process ended as "Rejected"** — the initiator does not hold the
  `administrator` role; no jobs were created.
- **Leftover `rebuild/` or `*.old` directories** — harmless; they are
  removed the next time the index opens.
