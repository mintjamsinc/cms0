# Datasets

A dataset is a folder whose direct child files are rows, with the typed
properties (columns) those rows carry declared once, in the folder. The
declaration lives in the repository next to the data, so the same rows are
seen by every memo that embeds the folder, by the Content Browser, by the
Inspector and by any XPath statement; nothing is copied into a document.

This note covers the descriptor, how the server exposes it, how the Inspector
and the Memo app use it, and the constraints that come from the search index.

## The descriptor (`.dataset.yml`)

A folder becomes a dataset by carrying a file named `.dataset.yml`:

```yaml
id: dmg4k2x9a          # required: the prefix of every stored property name
label: Equipment loans
description: Who has what, and until when.
properties:
  - key: status        # stored on each row as "<id>_<key>"
    label: Status
    type: STRING       # STRING | LONG | DOUBLE | DECIMAL | BOOLEAN | DATE
    required: true
    choices:
      - value: available
        label: Available
        color: sage        # a swatch of the shared palette
      - value: loaned
        label: On loan
  - key: due
    label: Due
    type: DATE
  - key: tags
    type: STRING
    multiple: true
    print: false           # left out when the rows are printed
```

| Field | Meaning |
| --- | --- |
| `id` | A letter followed by letters, digits or underscores. Generated once when the dataset is created and never changed: every row's values are stored under names derived from it. |
| `label`, `description` | Shown to users. `label` falls back to the id. |
| `properties[].key` | Same shape as the id. The value is stored on the row's `jcr:content` under the property name `<id>_<key>`. |
| `properties[].type` | One of `STRING`, `LONG`, `DOUBLE`, `DECIMAL`, `BOOLEAN`, `DATE`; defaults to `STRING`. |
| `properties[].multiple` | Multi-valued property. |
| `properties[].required` | Reported to the Inspector as a required property. |
| `properties[].print` | `false` leaves the column out when the rows are printed (the Memo app hides it under `@media print`); absent means printed. |
| `properties[].choices` | Allowed values, each `{ value, label, color }` or a bare scalar. `color` names a swatch of the shared palette (`webtop/src/webtop/lib/color-palette.ts`: `tomato`, `tangerine`, `banana`, `basil`, `sage`, `peacock`, `blueberry`, `lavender`, `grape`, `flamingo`, `graphite`); the server carries it, the client tints the value's chip with it, and an unknown name shows as no color. |

The descriptor is parsed by `Datasets.java` (`org.mintjams.rt.cms.internal.dataset`)
with the same rules as `.web.yml`: a descriptor that is missing, unreadable or
malformed means "no dataset" and never breaks a read. A column with an invalid
key or an unknown type is skipped rather than defaulted, so a typo cannot
write values under a name with the wrong type. Duplicate keys keep the first.

Like `.web.yml`, the descriptor is configuration, never served over the web
(`CheckProtectedAction` answers 404 for the reserved name).

### Why the id prefix

The search index types a field by its name across the whole repository: the
first document that carries `due` as a Date makes `due` a date field, and a
later `due` written as a String breaks that field's index. Two datasets that
both name a column `due` with different types would collide. The id gives each
dataset a namespace of its own while the key stays readable in the descriptor
and in queries:

```
/jcr:root/home/users/alice/Desktop/Loans/element(*, nt:file)[@dmg4k2x9a_status = 'loaned']
  facet accumulate @dmg4k2x9a_status
```

The joined name needs no registered namespace (there is no colon), is a valid
JCR name and reads as one token in an XPath predicate.

### The Memo app rewrites the descriptor as JSON

When a column is added, edited or removed from a memo, the block writes the
descriptor back as JSON. YAML reads JSON, so the file stays a valid
`.dataset.yml` and the server parses it as before. A descriptor that is
already JSON is edited in place and keeps any keys the block does not know; a
hand-written YAML descriptor is rebuilt from the server's parsed view on its
first rewrite, which drops comments and anything the server ignored.

## GraphQL: `node.dataset`

The server resolves the dataset a node belongs to, the way it resolves
`webRender`, because the declaration lives in the folder and a client looking
at one row cannot see it:

```graphql
query {
  node(path: "/home/users/alice/Desktop/Loans/Projector.memo") {
    dataset {
      id
      path          # the dataset folder
      label
      description
      properties {
        key         # as written in the descriptor
        name        # the stored property name: "<id>_<key>"
        label
        type        # STRING | LONG | DOUBLE | DECIMAL | BOOLEAN | DATE
        multiple
        required
        print       # false only when the descriptor says so
        choices { value label color }
      }
    }
  }
}
```

| Node | `dataset` |
| --- | --- |
| A folder with a descriptor | Its own dataset. |
| A file directly inside such a folder | The parent folder's dataset. |
| The descriptor file itself | `null`: it is not a row. |
| Anything else | `null`. |

Only the direct children are rows; a descriptor does not apply to
subfolders. `GetNode` (the webtop's `contentService.getNode`) selects the field;
the listing and search queries do not, since every row of one folder shares
the folder's dataset.

## The Inspector

`wt-inspector` turns `node.dataset` into a metadata schema of the same shape
as the ones from `/etc/metadata/schemas` (key `dataset:<id>`, columns keyed
by their stored property name) and offers it first in the schema pickers of
the detail view and the property editor. The first time a row is shown, the
dataset schema is selected on its own, so the row's columns appear with their
labels, choices and types, and the property editor's "add all missing
properties" creates the columns the row does not carry yet. A user who
switches to "No schema" keeps that choice through the row's next refresh.

A folder is the dataset, not a row, so the Inspector gives it no schema.

### A host's own pane

`wt-inspector` also lets its host take the panel over: while the host sets
`viewOptions.pane` (`{ title? }`), the Inspector keeps its frame and header
(showing `pane.title` when given) but renders its `pane` slot instead of the
node sections and overlays, and goes back to the node the moment `pane` is
cleared. The host keeps `target` bound throughout. The slot content is
compiled in the host's scope, as ichigojs slots are, so the host binds and
mounts whatever it likes there. The Memo app uses this for the dataset block
(below); any app with a selection that is not a node can use it the same
way, without touching the Inspector's internals.

## The Memo app: the dataset block

`/dataset` inserts a block (Tiptap node `datasetView`, see
`webtop/src/webtop/apps/memo/dataset-view.ts`). The memo stores only how the
block shows the folder; the rows and their values stay in the repository.

| Attribute | Stored in the memo |
| --- | --- |
| `path` | The dataset folder. `''` while the block is being set up. |
| `view` | `table`, `board` or `calendar`. |
| `hidden` | Keys of the columns the block hides (table columns, board card fields). |
| `sort` | `{ key, dir }`; an empty key sorts by name. Applies to every view. |
| `group` | Board: key of the single-valued `STRING` column with choices whose values are the lanes. |
| `date` | Calendar: key of the `DATE` column rows are placed by. |
| `widths` | Table: column widths in pixels by key (`$name` for the name column); a column not listed has its default width. |
| `order` | Table: keys in display order; columns not listed follow in descriptor order. The name column is always first. |
| `wide` | Stretch the table or board to the editor's width instead of the memo's text column (a calendar keeps the text column). |

In the HTML round-trip form the block is `<div data-dataset-view data-path=…
data-view=… data-hidden=… data-sort=… data-group=… data-date=…
data-widths=… data-order=… data-wide>`.

**Active block.** The block has two faces. In the document it shows the
rows; its header — the view dropdown (table / board / calendar), the view's
own controls (the board's group column; the calendar's month, "Today" and
date column) and the "⋯" block menu — appears only while the block is
*active*: the cursor is in it. The block is moved like any other block, with
the editor's drag handle in the left gutter (`apps/memo/drag-handle.ts`). A block becomes active on a
click, a right click, focus moving into one of its inputs, or a ProseMirror
node selection, and stops being active when the cursor moves elsewhere in
the document (a mousedown in the editor outside the block, a caret move
elsewhere), when the tab changes, or when the block is destroyed. Clicks in
the Inspector, the toolbars or the shell's menus leave it active.

While a block is active, the Memo app hands its *pane* to the Inspector
(`viewOptions.pane` and the `pane` slot, see above): the dataset's name and
path, the row count (and how many rows were left out), and the column list.
Columns are added, edited and deleted there; the table's column menu still
offers "Edit column…" and "Delete column…", which open the same forms in the
pane. The forms and confirmations (column, rename, delete) open in the pane
and, when the Inspector is closed, open it; a form left open when the block
loses the cursor is discarded. Switching to a board or a calendar picks the
first usable column when none is set, and the header's picker changes it. The
month a calendar shows is not stored: a reader lands on the current month.

**Table.** Columns have a fixed width each (`widths`, dragged at the header
cell's right edge; the empty last column takes what is left of the block).
Dragging a header cell onto another reorders the columns (`order`; dropped on
the left half it goes before that column, on the right half after). A choice
column shows its values as chips tinted with the choice's `color`. Columns
with `print: false` carry `is-noprint`, which the memo's print stylesheet
hides together with the block's controls.

**Board.** The group column must be a single-valued `STRING` column with
choices, so every lane is a known value. One lane per choice, in declared
order, tinted with the choice's color, plus a lane for each value in use that
is not a choice, and a "No value" lane when rows lack the value. A card shows
the row's name and up to three other visible columns. Dragging a card to
another lane writes that lane's value to the row's property; the lane's
"+ New" creates a row with the value set.

**Calendar.** A month grid in the user's preference time zone, one chip per
date a row carries (a multi-valued date column places the row several times),
rows without a date in a tray below. Dragging a chip to another day keeps the
row's time of day and changes the date; the "+" of a day creates a row dated
at midnight of that day. Chips of a multi-valued date column cannot be
dragged, since one chip does not say which of the dates to move.

**Setup.** A new block offers to create a folder next to the memo (a folder
plus an empty descriptor with a generated id), or takes a folder dropped from
the Content Browser. An unsaved memo has no "next to", so it asks to be saved
first; a dropped folder works either way.

**Rows.** Read through the search index with
`/jcr:root<path>/element(*, nt:file)` (direct children only, up to 500; the
status line says how many were left out) and filtered of the descriptor.
The name column is the file name without `.memo` and opens the row in a tab.
"+ New row" creates an empty memo in the folder; the row's context menu
deletes it.

**Cells.** A click edits the cell with an editor chosen by the column: a
select for choices, `datetime-local` for `DATE` (stored as an ISO instant in
the user's preference time zone, as the Inspector does), a number input for
the numeric types, a checkbox for `BOOLEAN`, a text input otherwise, with
comma-separated values for a multi-valued column. Each commit is one
`setProperties` call on the row; an empty value deletes the property.

**Columns.** The pane's "+" button, its column list and the table's column
menu add, edit and delete columns by rewriting the descriptor. The key and
the type are fixed once a column exists (see "Why the id prefix"); label,
required, print and the choices (value, label, color) can change. Deleting a
column removes it from the descriptor only: the values already stored on the
rows are kept, and a column added back with the same key and type shows them
again. The block menu's "Remove block and delete dataset…" deletes the folder
with every row in it, and then the block; since that destroys data beyond
the memo, it asks in the app's own dialog (the same kind as the
unsaved-changes one), not in the pane.

**Live updates.** The block watches the folder (`nodeChanged`, deep) and
reloads after a change, so edits from another memo, the Inspector or the
Content Browser appear without a refresh.

## Constraints to keep in mind

- A column's key and type cannot change after creation, and a dataset's id
  never changes: both are part of the stored property names.
- Multi-valued numeric and date properties indexed before the
  `SortedNumericDocValues` change carry only their first value in statistics
  (see `memos/xpath-facet-statistics.md`); a rebuild of the index fixes it.
- Rows are files; a subfolder inside a dataset folder is neither a row nor a
  dataset of its own unless it carries its own descriptor.
