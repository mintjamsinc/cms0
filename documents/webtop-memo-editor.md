# Webtop Memo editor (`ui/wt-memo.esm.js`)

The Memo app's document and formatting toolbar, for a page that edits a memo
file (`application/vnd.mintjams.cms.memo+json`) outside the Memo app. The
first user is the Commerce package's shipping task form, which edits the memo
of a ledger row; any app or BPMN form can import it the same way.

What is written with it reads the same in the Memo app and the other way
round, because both use one definition of the document:

- **`webtop/src/webtop/lib/memo-document.ts`** — the Tiptap extensions that
  make a memo (headings 1–3, bold, italic, underline, strike, code, text and
  highlight colours, links, alignment, bullet, numbered and task lists,
  quotes, code blocks, rules, tables, images, page breaks), the file format
  (`{ version: 1, type: 'tiptap', doc }`; a bare `{ type: 'doc' }` is read
  too) and its MIME type. The Memo app builds its editor from it and adds
  what only the app does: the slash menu, dataset and conversation blocks,
  the drag handle, find & replace, dropping files.
- **`webtop/src/webtop/ui/wt-memo.css`** — the formatting toolbar and the
  document's look. The Memo app links it; the bundle appends it. The
  document rules apply under an element with the class `wt-memo`, scoped
  with `:where()` so each keeps the weight it had as a plain
  `.ProseMirror …` rule in the Memo app's own style sheet.

The bundle is built from `webtop/src/webtop/ui/memo-editor.ts` by the target
`webtop-memo` (`npm run build:webtop-memo`). Like the Markdown editor it is
separate from `ui/wt-ui.esm.js`, so only the pages that edit memos carry it
(about 470 KB minified).

## Use

```js
const { createMemoEditor, initMemoEditorCss } =
    await import(new URL('ui/wt-memo.esm.js', host.webtopBaseUrl).href);
await initMemoEditorCss();

const memo = createMemoEditor({
    element: document.querySelector('#memo'),  // the box; toolbar and document are built inside
    content: await host.readNodeText(path),    // a memo file's text ('' for an empty memo)
    editable: true,
    locale: 'ja',                              // the toolbar's labels: 'ja' or English
    placeholder: 'Write here.',
    onUpdate: (m) => { dirty = m.getContent() !== saved; },
});

memo.getContent();        // the memo file's text, in the Memo app's envelope
memo.getDocument();       // the document as Tiptap JSON
memo.setContent(text);    // replace the document (no onUpdate)
memo.setEditable(false);  // read-only: the toolbar greys out, the box gets is-readonly
memo.isEmpty();
memo.destroy();
memo.editor;              // the Tiptap Editor, for anything else
```

| Option | Meaning |
| --- | --- |
| `element` | The box. It gets the classes `wt-memo wt-memo-embedded`; its children are replaced. |
| `content` | A memo file's text. Anything that is not a memo becomes an empty memo. |
| `editable` | Default `true`. |
| `toolbar` | Default `true`: the Memo app's formatting toolbar above the document. |
| `placeholder` | What an empty memo shows. |
| `locale` | The language of the toolbar's labels when no `t` is given: `ja`, or English. |
| `t(key, fallback)` | The toolbar's labels, keyed as in the Memo app (`app.memo.format.bold`, …). |
| `onUpdate(handle)` | After every change the person makes. |

Compare `getContent()` with the text as last saved to tell whether there are
changes: the editor writes the envelope, so a file stored as a bare document
reads as changed until it is written once. Take the baseline from
`getContent()` right after loading, as the shipping form does.

## In the box

- **The toolbar** is the Memo app's: block type, inline marks, the colour
  and highlight swatches (the shared palette, `lib/color-palette.ts`), link,
  lists, quote, code block, alignment, table, rule, page break, and the
  table tools while the cursor is in a table. The colour and link pickers
  are small panels of the editor's own; the Memo app raises the shell's
  menus instead, which a page outside the shell cannot.
- **Keyboard and Markdown shortcuts** are Tiptap's, as in the Memo app
  (`Ctrl+B`, `# ` for a heading, `- ` for a list, `Ctrl+Enter` for a page
  break, …).
- **Blocks only the Memo app draws** — a dataset or a conversation — are
  shown as a note ("shown in the Memo app") and kept with all their settings,
  so an edit here never loses them.
- **Images** the memo holds are shown and can be resized; placing new ones
  (by dropping files) is the Memo app's.
- In a box the document uses the box's width with small margins
  (`.wt-memo-embedded`); a long memo scrolls inside `.wt-memo-body`, whose
  height the page may limit.

## Saving

The bundle edits; the page saves, since only the page knows where the memo
belongs and who may write it. A form with write access writes the text back
through `host.cms`; the shipping form, whose users may read the ledger but
not write it, calls a mutation of its package that checks the task and
writes as the process's account. To follow changes made elsewhere, watch the
node (`host.watchNode`, `documents/bpm-task-forms.md`) and show the new text
when there are no unsaved changes.
