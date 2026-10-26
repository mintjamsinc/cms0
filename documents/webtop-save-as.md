# Save As in the editors

Memo, the Text Editor, the BPMN Modeler and the EIP Modeler save a file
under a new name, or in a new place, the same way: *Save As* (the second
floppy in the toolbar) opens a small dialog with the name, the format and a
**chip**, and the chip is dragged to where the file should go. There is no
folder picker: the place is wherever the chip lands.

## Where the chip can go

| Where | What happens |
| --- | --- |
| A folder of the repository, in the Content Browser (the folder being browsed, or a folder row) | The file is written there. A name the folder already has is asked about. The editor switches to the new file: the title, the tab and the next *Save* are the new file's. |
| The Desktop (its background, or a folder icon on it) | The same, in the Desktop folder or in that folder. |
| A folder of Google Drive or OneDrive, in the Content Browser (see `webtop-cloud-storage.md`) | The file is written to the drive. The editor keeps the file it has open, and a note at the bottom of its window says the copy was saved, with a link that opens it at the provider. (The editors open and write cloud files in a later step; until then a copy in a drive is a copy.) |

Once the chip has been dropped somewhere that took it, the dialog closes
and the file appears where it was dropped. A drop that was refused (the
cursor said no) leaves the dialog open for another try. Dropping the chip
back on the editor does nothing.

## Formats

The format list names what the editor can write; the extension of the name
follows the format chosen (`notes.memo` → `notes.html` → `notes`), and the
name can still be edited by hand.

| Editor | Formats |
| --- | --- |
| Memo | **Memo** (the memo itself, `.memo`); **HTML**; **Google document** (the HTML, converted by Google Drive); **Plain text** (the text without its formatting, `.txt`) |
| Text Editor | **As named**: the type follows the extension typed (`.md` is Markdown, `.json` is JSON, and so on; an unknown extension keeps the open file's type); **Google document** (the text, or the HTML of an HTML file, converted by Google Drive) |
| BPMN Modeler | BPMN XML |
| EIP Modeler | EIP XML (`application/vnd.webtop.eip+xml`; route files saved earlier as `application/x-camel-route` still open) |

A **Google document** is not a file format but a conversion Google Drive
performs, so that format can only be dropped into Google Drive: the dialog
says so, and over the repository, the Desktop or OneDrive the cursor says
no. A Google document has no extension; the name is the document's title.

The editor switches to the new file only when the file is something it can
keep editing: a memo or an HTML file for Memo, any file for the Text Editor
and the modelers. A memo saved as plain text, like a file saved in a drive,
is a copy: the memo stays open, and the note says the copy was saved.

## For app authors

The dialog is the shared component `<wt-save-as>` (`webtop/src/webtop/ui/`):

```html
<wt-save-as v-model:open="saveAsDialog.visible" :file-name="currentFile.name"
    :formats="saveAsFormats" default-format="memo" :labels="saveAsLabels"
    @saved="onSaveAsSaved($event.detail)"></wt-save-as>
```

- `formats` is a list of `{ id, label, extension, mimeType, convert?,
  targets?, produce(name) }`; `produce` makes the content the moment the
  chip is picked up. `mimeTypeOf(name)` lets the type follow the name.
  `convert: 'google-document'` with `targets: ['cloud:google']` is the
  Google document format.
- The chip carries `application/x-webtop-save` (`{name, mimeType, content,
  saveAsToken, convert?}`) and sets `__webtopDragData.save` on the top window
  (`{name, mimeType, convert, targets}`) for the receivers' dragover; the
  receivers answer on the `webtop-save-as` channel (`save-as-complete` with
  `path`, `name`, `mimeType`, the token and, for a drive, `cloud`).
- `saved` fires with `{path, location, name, mimeType, format, cloud?,
  adopted}`. An app that switches to the saved file sets `adopted = true` on
  the detail; otherwise the component shows the "saved" note itself.
- The labels come from `saveAsLabels(t)` in `lib/save-as.ts` (global bundle
  `/etc/i18n/wt-save-as.<locale>.json`, keys `webtop.saveAs.*`), and
  `saveAsFormatLabel(t, key)` names the shared formats.
