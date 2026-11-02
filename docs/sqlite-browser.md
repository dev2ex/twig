# SQLite browsing and extension points

SQLite databases expand inside the existing tree. Their tables are dedicated UI
nodes, not filesystem directories: copying or deleting a table must never become a
file operation. Only one table per database is expanded at a time, matching directory
accordion behavior; switching tables cancels the previous table's page reads and drops
its data cache. Database files retain their ordinary file actions. Recognized suffixes
are `.db`, `.db3`, `.sqlite`, `.sqlite3` and `.s3db`; unknown file types have an explicit
**Open as SQLite** action. Database browsing leases are temporary and are not restored
as directory paths after process death.

## Responsibilities

- `sqlite/SqliteSession.kt` owns a source lease and implements schema, count, page and
  cell reads. It returns immutable schema and typed, bounded cell previews. No Android
  views, cursors or database handles escape this layer.
- `ui/SqliteTree.kt` owns jobs, expanded tables, property tabs, pagination and viewport
  positions. Tree nodes contain result snapshots. Removing the database from the visible
  tree cancels its jobs and releases its lease. Refresh replaces schema/counts/pages
  together and preserves the surviving tables' expansion and viewport state.
- `ui/SqliteGridView.kt` renders only visible cells, fixes the header and locks each
  gesture to one axis. Data and property cards occupy the pane width independently of
  tree indentation. Vertically scrollable grids retain gestures at either scroll
  boundary. When all rows fit, vertical drags return to the outer tree; horizontal
  drags still belong to the grid. Virtual accessibility nodes expose visible cells.
- `ui/SqliteInfoView.kt` provides Columns and DDL content inside the common
  `item_info_card` shell. `InfoCardTabs` renders both ordinary and SQLite property tabs,
  including their typography, close action and tree indentation. SQLite content keeps
  horizontal drags for grid scrolling; tab clicks use the common page transition.
  `FileAdapter` and `PaneFragment` connect these views to the tree and menus.

## Reading and resource limits

Directly accessible local sources use Android's SQLite implementation with
`OPEN_READONLY | NO_LOCALIZED_COLLATORS`. The corruption callback never deletes the
source or its journals. Each operation owns and closes its connection/cursor; a long
browsing session does not retain a read transaction that could indefinitely pin a WAL.
Consequently, separate count/page/detail reads can observe separate committed states.
Refresh when another application changes a database.

Other sources, including opaque SAF documents, are materialized on explicit expansion.
The tree supplies their actual containing directory; never derive a SAF parent by
splitting its document URI. Sibling enumeration must succeed before deciding whether
there is a WAL. Copy a present `-wal` beside the database, reject nonempty rollback
journals, and reject detected size/time/WAL/journal changes during copying. These checks
are best effort, not an atomic backup of a live remote database: export a database copy
from its writer when it is changing or metadata is unreliable. Large or unknown-size
nonlocal sources use the existing materialization confirmation flow.

Android 7/8 cannot reliably open read-only WAL databases. Local WAL databases on those
versions also use a disposable snapshot. A private snapshot may be opened read/write
for WAL recovery/checkpointing, with `query_only` enabled before browsing queries;
source files are never written back. Each snapshot has a unique directory. Active leases
protect one another, closing removes their files, and opening a new snapshot removes
orphans left by a previous process.

Rows use at most 100 entries per page, reduced for wide tables to at most 4,096 cells.
Only eight pages remain cached per open table. Preserve the furthest loaded offset even
when evicting its page, so an unknown-count table does not shrink while scrolling back.
A known end bounds the scroll range. Counts have a three-second soft deadline and may
remain unknown; failure is never a zero. Native reads use a `CancellationSignal`.

Preview SQL bounds text to 256 characters and BLOBs to 32 bytes before crossing the
CursorWindow boundary. Cell details are bounded to 65,536 text characters or 32,768
BLOB bytes and explicitly report truncation, including what copying contains. NULL,
empty text and BLOBs retain distinct types. SQL identifiers are quoted centrally.
Ordering prefers an unshadowed SQLite rowid alias, then the declared primary key.
Pagination currently uses LIMIT/OFFSET; deep-offset optimization can be added behind
this reader without changing tree or renderer contracts. Encrypted databases require a
separate future reader; no SQLCipher library is bundled.

## Database search

The database menu searches all tables with a bound LIKE pattern. Keywords containing
`%` are used as supplied; otherwise they become `%keyword%`. User `%` and `_` retain
their wildcard meaning. Eligible columns are cast to text, excluding
columns declared with a BLOB type and values whose runtime type is BLOB. A row matching
multiple columns counts once. Identifiers remain quoted and the pattern is never SQL.

A search-results branch reports the progressive total and includes only matching tables,
with matched-row and visible-column counts. Search counts are cancellable full scans,
without the ordinary background count deadline. Failed table scans produce an explicit
incomplete-results notice rather than being counted as empty. Filtered counts, pages and
cell details share the same predicate and ordering, so detail offsets refer to the result.
Matching cells use the shared selection background. The reader evaluates each cell's
match with SQLite against its full value, preserving LIKE semantics even when the
matching text lies outside the bounded preview; rendering never reimplements LIKE.

Normal and filtered tables have distinct node/cache identities but share the per-database
expansion accordion and renderer. Replacing or closing a search cancels its counts, pages
and details. Refresh reruns the current search; normal table expansion is preserved while
filtered expansion resets. Search text and results remain session-only.

## Future editing

Do not add mutations to `FileSystem`, or turn virtual tables into writable files. Add
an explicit writable database capability beside the reader, with its own connection
and transaction ownership. The first supported target can be a directly writable local
source; snapshot and remote leases stay read-only until a deliberate write-back design
exists. Replacing a live database through ordinary file copy is not a transaction.

`SqliteIdentity` already records rowid/primary-key candidates. Before an editor enables
Save, fetch the complete typed row plus its actual identity, validate that identity is
unique (nullable primary keys are insufficient), and retain original values for conflict
detection. Preview strings are display data, not update payloads. An update/delete must
use a bound identity and expected original values in one transaction and require exactly
one affected row. Never update by displayed row offset, especially after sorting or
concurrent changes. Tables without a reliable identity can still support explicit row
insertion, but must not offer ambiguous row update/delete. Generated/hidden columns,
defaults and primary-key ordinal information are retained in schema metadata already.

After commit, invalidate affected pages and counts and re-read schema when appropriate.
Refresh/cancellation/rotation must not implicitly commit a draft.

## Future SQL queries

Add a separate query controller that returns projection metadata and bounded typed pages
to the same grid renderer. It should own query text, bindings, cancellation and result
leases rather than embedding SQL execution in tree nodes. Read-only sessions should
remain enforced by the database connection; checking whether text starts with SELECT is
not a permission boundary. Writable query execution should be an explicit capability,
with transactions and affected-row reporting, independent of the initial browse UI.
