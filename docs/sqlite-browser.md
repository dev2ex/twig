# SQLite browsing and extension points

SQLite databases expand inside the existing tree. Their tables are dedicated UI
nodes, not filesystem directories: copying or deleting a table must never become a
file operation. Only one table per database is expanded at a time, matching directory
accordion behavior; switching tables cancels the previous table's page reads and drops
its data cache. Database files retain their ordinary file actions. Recognized suffixes
are `.db`, `.db3`, `.sqlite`, `.sqlite3` and `.s3db`; unknown file types have an explicit
**Open as SQLite** action inside **Open with**. Database browsing leases are temporary and are not restored
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
  `item_info_card` shell. Columns use the grid; DDL uses the common selectable text
  body with wrapping, without a grid header or cell borders. `InfoCardTabs` renders both ordinary and SQLite property tabs,
  including their typography, close action and tree indentation. SQLite content keeps
  horizontal drags for grid scrolling; tab clicks use the common page transition.
  `FileAdapter` and `PaneFragment` connect these views to the tree and menus.

## Reading and resource limits

Opening first reads only the 16-byte `SQLite format 3\0` signature. A mismatch stops
before SQLite is opened or a remote snapshot is downloaded; schema reading remains the
final validation for action-bar search availability.

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
BLOB bytes and explicitly report truncation, including what copying contains. Clicking
a BLOB opens `SqliteBlobDialog` with the shared `HexPane`, not a truncated text dump.
`HexByteSource` separates row rendering from file access; `ChunkedHexSource` requests
64 KiB ranges through SQLite `substr()` and retains at most eight chunks (512 KiB).
Filtered row offsets use the same predicate/order as the grid. A reader captures its
database/search job, so refresh, replacement or closure cannot silently retarget later
chunks. Dismissal and screen destruction cancel pending reads. Type/length changes
reject a range; as with other browsing reads, concurrent same-size value changes are
not an atomic snapshot. Reopen from a stable database copy for a consistent inspection.
Save as uses the shared `SaveAsDialog` and the same reader to stream raw bytes to a
filename and writable directory chosen through Twig's picker, with foreground progress
and cancellation. It never
exports a hex dump or just the cached preview. Existing names are rejected; cancellation
or an incomplete read removes the new partial output. Leaving the source screen cancels
an active export, and pending picker drafts remain in memory only.

The text editor uses the same Save as controller with a complete encoded text snapshot.
It preserves charset/BOM, requires consent for UTF-8 conversion, and adopts the new file
only after successful writing. Later saves retain the selected parent and actual
replacement document URI, including opaque SAF destinations.

NULL, empty text and BLOBs retain distinct types. SQL identifiers are quoted centrally.
Ordering prefers an unshadowed SQLite rowid alias, then the declared primary key.
Pagination currently uses LIMIT/OFFSET; deep-offset optimization can be added behind
this reader without changing tree or renderer contracts. Encrypted databases require a
separate future reader; no SQLCipher library is bundled.

Data-grid taps select a single whole row. Selection lives with its viewport, survives
paging/rebinding and uses a softer tint so matched search cells stay distinct. Long
pressing a cell opens its text/copy or BLOB dialog. Accessibility exposes selection and
long-click actions independently. Sorting, collapsing or refreshing clears selection;
property-grid cells retain their existing click behavior.

## Table ordering

Tapping a data header sorts the complete table or current result set in ascending
SQLite value order; tapping it again reverses direction. Another column starts in
ascending order. The active header shows an arrow and exposes its direction and click
action to accessibility. Property-grid headers remain static.

`SqliteSort` is part of table state. Sorting cancels pending page reads, clears the
page cache and resets vertical scrolling while preserving horizontal position. SQL
quotes the selected column and appends rowid/primary-key tie breakers. Pages, cell
reads, BLOB ranges and CSV export all use the captured ordering, including readers
opened before a later sort change. Normal and searched tables keep separate ordering.
Database refresh retains normal-table ordering while its column still exists.

## CSV export

Table menus export all rows; result-table menus and scoped search headers export only
matching rows. The column chooser defaults to all visible columns, supports select
all/none and requires at least one column. Output keeps schema order regardless of
selection order. Filtering still considers every searchable column.

`SqliteCsv` writes UTF-8 with a BOM, a column-name header and CRLF records. Text/header
fields are quoted with doubled embedded quotes; NULL is an empty field, empty text is
`""`, and BLOBs are uppercase `0x` hex (including `0x` for empty BLOBs). Complete values
are read in bounded byte chunks rather than through grid previews. Very large cells
use additional LIMIT/OFFSET range queries with the grid's ordering. Like other browsing
reads, export requires a stable source when concurrent writers may change its contents.

`SaveAsDialog.offerStream` shares the filename/picker, progress, existing-name checks
and partial-file cleanup with text/BLOB Save as. CSV size is unknown in advance, so
progress displays bytes written. The writer captures its database/search job; closing,
refreshing or replacing that source cancels it. Cancellation joins the reader before
closing/removing the destination. No temporary CSV or full-table byte array is needed.

## Database search

The database menu and action-bar search (when a database, table or result branch is
selected) search all tables with a bound LIKE pattern. Action-bar availability requires
a successful schema read, not just a recognized suffix. Confirmation survives collapse,
but reopening clears it until validation succeeds again. Removing the file from the visible tree also invalidates confirmation. Keywords containing
`%` are used as supplied; otherwise they become `%keyword%`. User `%` and `_` retain
their wildcard meaning. Eligible columns are cast to text, excluding
columns declared with a BLOB type and values whose runtime type is BLOB. A row matching
multiple columns counts once. Identifiers remain quoted and the pattern is never SQL.

The table menu searches only that table. Action-bar search follows the selected table
or an existing result branch's scope; selecting the database searches all tables.
Single-table results attach beneath their original table: a search summary followed by
the filtered grid directly, without a duplicate table node. The first page opens
automatically; tapping the original table closes the search. Database-wide results keep
the list of matching tables. Retry/refresh retains the scope. Filtering, pagination,
cell details and highlighting reuse the same reader.

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
database-wide result expansion resets; a single-table result grid opens automatically
with fresh pages. Search text and results remain session-only.

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
