# Webtop Markdown editor (`ui/wt-markdown.esm.js`)

A rich-text editor whose document is Markdown, for apps that edit a Markdown
file without showing the person the syntax. The first app that uses it is
the Site package's page editor; any app, or a BPMN form, can import it the
same way.

It is Tiptap (ProseMirror) with the official Markdown extension
(`@tiptap/markdown`), the starter kit (headings, bold, italic, strike, code,
links, lists, quotes, code blocks, rules), images and tables, in one ESM
bundle built from `webtop/src/webtop/ui/markdown-editor.ts` by the target
`webtop-markdown` (`npm run build:webtop-markdown`). It is separate from
`ui/wt-ui.esm.js`, which every app loads, so that only the apps that edit
carry it (about 500 KB minified). Its stylesheet `ui/wt-markdown.css`
styles the document under `.wt-markdown`; the app supplies the frame, the
toolbar and the theme tokens.

## Use

```js
const { createMarkdownEditor, initMarkdownEditorCss } =
    await import(new URL('../../ui/wt-markdown.esm.js', import.meta.url).href);
await initMarkdownEditorCss();

const handle = createMarkdownEditor({
    element: document.querySelector('#editor'),
    content: markdownText,
    placeholder: 'Write here.',
    // The Markdown names pictures by a relative name; the editor shows them from an address.
    resolveImageSrc: (src) => /^[a-z]+:|^\//i.test(src) ? src : downloadPrefix + encodeURIComponent(src),
    serializeImageSrc: (src) => src.startsWith(downloadPrefix) ? decodeURIComponent(src.slice(downloadPrefix.length)) : src,
    // A dropped or pasted picture: store it and return the address to show it from, or null.
    uploadImage: async (file) => { ...; return downloadPrefix + encodeURIComponent(name); },
    onUpdate: () => { dirty = true; },
    onSelectionUpdate: () => { /* repaint the toolbar's state */ },
});

handle.getMarkdown();                 // the document as Markdown
handle.setMarkdown(text);             // replace the document
handle.insertImage(src, alt);
handle.editor.chain().focus().toggleBold().run();   // the Tiptap editor itself, for a toolbar
handle.editor.isActive('heading', { level: 2 });
handle.destroy();
```

`resolveImageSrc` and `serializeImageSrc` are applied to every image node on
the way in and out, so the Markdown keeps the names the site serves pictures
by while the editor shows them from wherever the app can load them (the
download servlet, for a repository file). Without `uploadImage`, dropped and
pasted pictures are ignored.

## What the Markdown looks like

The Markdown extension writes CommonMark with GFM tables and strikethrough;
headings as `#`, lists with `-` and `1.`, code blocks fenced. Underline is
turned off, since Markdown has none. A document that came in as Markdown
goes out as Markdown again; formatting the extension has no syntax for is
not offered by the editor.

## Where things are

| Path | What |
| --- | --- |
| `webtop/src/webtop/ui/markdown-editor.ts` | the module: `createMarkdownEditor`, `initMarkdownEditorCss`, `Editor` |
| `webtop/src/webtop/ui/wt-markdown.css` | the document's stylesheet |
| `webtop/rollup.config.js` (`webtop-markdown`) | the bundle target: `dist/webtop/ui/wt-markdown.esm.js` and the stylesheet beside it |
