# Moving the workspace databases from H2 to PostgreSQL

A workspace keeps its state in two databases: the content repository
(`jcr_*` tables) and the process engine (`ACT_*` tables). A new installation
puts both in embedded H2 files under the workspace. `cms-migrate-db` moves an
installation that already runs on those files to PostgreSQL.

| State | Before | After |
|-------|--------|-------|
| Content, properties, ACLs, locks, journal | `<workspace>/var/jcr/data/data.mv.db` | the database named by `etc/jcr/jcr.yml#datasource` |
| Process engine (deployments, instances, history) | `<workspace>/var/bpm/data.mv.db` | the database named by `etc/bpm/bpm.yml#jdbcURL` |
| Binaries (blobs) | `<workspace>/var/jcr/bin` | unchanged: still files |
| Search index | the node-local directory | unchanged: still valid, since the journal moves with the content |

## How the tool works

- **The destination comes from the configuration.** You edit `jcr.yml` and
  `bpm.yml` to name the PostgreSQL databases first, the way the server will
  read them after the move. The tool resolves them as the server does:
  `${env.NAME}`, `${env.NAME:-default}`, `NAME_FILE`, `${secrets.KEY}`,
  `ENC[...]`, `${workspace.name}`, `${workspace.home}` and
  `${repository.home}`. So the data lands in exactly the database the server
  opens.
- **The tables are the platform's own.** They are created with the
  definitions in the installed bundles: `workspace-prepare.sql` of the
  repository runtime, and the PostgreSQL scripts of the bundled process
  engine. These are the tables the server would have created itself.
- **Nothing is committed until everything succeeded.** Each database is
  filled in one transaction: creating the tables, copying the rows, checking
  the row counts and restoring the foreign keys. The transactions are
  committed only after every database of the run has been copied. If
  anything fails, all the PostgreSQL databases stay as empty as they were,
  and the tool can run again.
- **The H2 files are opened read-only** and never changed. They are your way
  back.
- **A running CMS is refused.** If the server holds the H2 files, the tool
  stops with "Stop the CMS before migrating".

What the tool checks before it copies anything, for every workspace:

- `jcr.yml` / `bpm.yml` name a `jdbc:postgresql:` URL, and every variable
  resolves
- each destination database is reachable, uses the `UTF8` encoding, has **no
  tables**, and the user may create tables in its schema
- the process engine schema in H2 is the version the installed engine
  creates. If it is older, start the CMS once on H2 with the current version
  so the engine updates its schema, then stop it and run the tool.

Rows of `jcr_cluster_nodes` and `jcr_cluster_locks` describe running nodes,
not content, so they are not copied. A workspace whose `bpm.yml` sets
`enabled: false`, or whose engine never started (no `var/bpm/data.mv.db`),
has no BPM database to move.

## Before you start

1. **Create one database per workspace and per kind**, owned by the user the
   CMS connects as, with the `UTF8` encoding. For the `system` workspace:

   ```sql
   CREATE USER cms PASSWORD '...';
   CREATE DATABASE jcr_system OWNER cms ENCODING 'UTF8' TEMPLATE template0;
   CREATE DATABASE bpm_system OWNER cms ENCODING 'UTF8' TEMPLATE template0;
   ```

   Create no tables in them. On PostgreSQL 15 and later, only the database
   owner may create tables in the `public` schema by default. That is why
   the user should own the database. Otherwise, `GRANT CREATE ON SCHEMA
   public TO cms` in each database.

   Every workspace needs its own pair, `jcr_<workspace>` and
   `bpm_<workspace>`. The tool migrates every workspace unless you name some
   with `--workspace`. A workspace you leave out keeps running on H2.

2. **Allow enough connections.** On H2 the connection pools cost nothing.
   On PostgreSQL every pooled connection is a server process counted
   against `max_connections` (default 100). Per workspace, the CMS opens:

   | Pool | Idle from the start | At most |
   |------|---------------------|---------|
   | Content repository (3 pools) | 3 × min(`maxSessions` / 2, CPU cores) | 3 × `maxSessions` |
   | Process engine | a few | 10 (the engine default) |

   `maxSessions` is `org.mintjams.jcr.workspace.maxSessions`: 128 by
   default, 32 at the least.

   The number of CPU cores sets only how many connections stay open while
   idle. Under load each pool grows to `maxSessions` on any host, and a
   connection above the idle number is closed after ten idle minutes:

   | Host | `maxSessions` | Idle from the start | At most |
   |------|---------------|---------------------|---------|
   | 6 cores | 128 (default) | 18 | 384 |
   | 6 cores | 32 | 18 | 96 |
   | 32 cores | 128 (default) | 96 | 384 |
   | 32 cores | 32 | 48 | 96 |

   On a 32-core host with the defaults the repository alone holds 96
   connections from the start. With the default `max_connections` there is
   then no slot left for the process engine, which fails with "remaining
   connection slots are reserved". A smaller host starts, and meets the same
   error later, under load. Either raise `max_connections` on the server (it
   needs a restart), or lower `maxSessions` (for the container image:
   `CMS_JAVA_OPTS: "-Dorg.mintjams.jcr.workspace.maxSessions=32"`). Leave
   room for every workspace and every node of a cluster.

   Lowering `maxSessions` removes no feature. It is also the number of
   repository sessions a workspace keeps open at once, and each open session
   holds one connection. When all are in use, the next request waits for a
   free one and fails after 10 seconds with "JCR session login timed out."
   How long a session stays open:

   | Request | Session held |
   |---------|--------------|
   | Open subscription stream (SSE) | None, however many are open |
   | GraphQL query or mutation | While it executes |
   | Web page (template, script) | Until the request ends |
   | File download | Until the transfer ends |

   With 32, a few sessions go to the workspace's background work, which
   leaves somewhat over 20 for requests. That suits ordinary use. Many
   large downloads at once, or pages that wait long on another service, are
   what fills it; prefer a higher `max_connections` there.

3. **Back up the repository and the secrets** with the CMS stopped (for the
   container image, see `docker/README.md`, *Backup and restore*).

## Steps: container image

1. **Stop the CMS:**

   ```bash
   docker compose stop cms
   ```

2. **Name the databases** in the workspace configuration, in the repository
   volume at `workspaces/<workspace>/etc/jcr/jcr.yml`:

   ```yaml
   datasource:
       jdbcURL: jdbc:postgresql://${env.CMS_DB_HOST}:${env.CMS_DB_PORT:-5432}/jcr_${workspace.name}
       username: ${env.CMS_DB_USER}
       password: ${secrets.DB_PASSWORD}
       driverClassName: org.postgresql.Driver
   ```

   and `workspaces/<workspace>/etc/bpm/bpm.yml`, keeping its other keys
   (`enabled`, `history`):

   ```yaml
   jdbcURL: jdbc:postgresql://${env.CMS_DB_HOST}:${env.CMS_DB_PORT:-5432}/bpm_${workspace.name}
   username: ${env.CMS_DB_USER}
   password: ${secrets.DB_PASSWORD}
   driverClassName: org.postgresql.Driver
   ```

   Put the password in `etc/secrets.yml` as `DB_PASSWORD: ...`. The server
   encrypts it in place on its next start (see `documents/secrets.md`). Add
   `CMS_DB_HOST` and `CMS_DB_USER` (and `CMS_DB_PORT` when it is not 5432)
   to the `environment:` of the `cms` service in the compose file.

3. **Rehearse.** `--dry-run` does the whole copy and then rolls it back, so
   it shows every problem the real run would hit and leaves the databases
   empty:

   ```bash
   docker compose run --rm cms cms-migrate-db --dry-run
   ```

   `docker compose run` starts a one-off container of the `cms` service with
   the same volumes and environment, while the CMS itself stays stopped.

4. **Migrate:**

   ```bash
   docker compose run --rm cms cms-migrate-db
   ```

   The tool prints each table with its row count, and the total per
   database.

5. **Start the CMS:**

   ```bash
   docker compose start cms
   docker compose logs -f cms
   ```

   Sign in, open content and the task list, and check that the processes
   you expect are there.

## Steps: Felix distribution run directly

Stop the server, edit `jcr.yml` / `bpm.yml` as above, then run the tool
from the runtime directory (the one holding `bundle/`). Use the same
environment variables the server gets.

```bash
java -cp "bundle/org.mintjams.cms_1.0.0.jar:bundle/snakeyaml-engine-2.3.jar" \
  -Dorg.mintjams.jcr.repository.rootdir=/path/to/repository \
  org.mintjams.cms.tools.DatabaseMigrationTool --felix-home . --dry-run
```

On Windows, separate the classpath with `;`. Without
`org.mintjams.jcr.repository.rootdir` (or `--repository DIR`), the tool uses
`./repository`, as the server does. If the secret key is not at its default
location, pass `-Dmintjams.cms.secret-key.path=...` as the server gets it,
so `ENC[...]` values and `secrets.yml` decrypt.

## Options

| Option | Meaning |
|--------|---------|
| `--workspace NAME` | Migrate this workspace; repeat for several. Default: every workspace. |
| `--dry-run` | Copy everything, then roll back. |
| `--repository DIR` | The repository directory. Default: `org.mintjams.jcr.repository.rootdir`, else `./repository`. |
| `--felix-home DIR` | The runtime directory holding `bundle/`. Default: `$FELIX_HOME`, else the current directory. |

The exit status is 0 when everything was copied (or rehearsed), and 1 on any
error. After an error the databases are empty, with one exception: if a
commit itself fails (the connection drops at the very end), the tool names
the databases it had already committed. Drop and recreate those before you
run the tool again.

## After the move

- **Backups** now have two parts: the volumes (configuration, blobs, secrets)
  and the PostgreSQL databases (`pg_dump`). Take both with the CMS stopped,
  or the dump and the blobs may disagree.
- **The H2 files** (`var/jcr/data/data.mv.db`, `var/bpm/data.mv.db`) are no
  longer read. Keep them until the CMS has run well on PostgreSQL for a
  while, then delete them.
- **Going back**: stop the CMS, remove `datasource` from `jcr.yml`, and set
  `bpm.yml#jdbcURL` back to `jdbc:h2:${workspace.home}/var/bpm/data` (removing
  `username`, `password` and `driverClassName`). The server then reads the
  H2 files as they were at the move. Anything changed since the move is not
  in them.
- **Clustering** needs the shared database this move provides. The rest of
  the setup is in `documents/clustering.md`.

## Related code

- `org.mintjams.cms.tools.DatabaseMigrationTool`: the tool
- `docker/cms-migrate-db.sh`: its wrapper in the container image
- `org.mintjams.rt.jcr.internal.JcrWorkspaceProvider#prepareInitialData`:
  the repository schema the tool recreates
