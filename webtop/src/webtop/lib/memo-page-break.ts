// Page break block: where the printed memo moves on to the next page.
//
// On screen it is a dashed line with a label; on paper it is nothing but the
// break. The break is the element's own inline style, so it holds wherever the
// HTML goes: the editor's print (the print stylesheet only hides the line and
// the label), the HTML that "Save As" writes, and the Google document Drive
// converts that HTML into.
//
// The memo stores
//
//   <div data-page-break style="page-break-before: always; break-before: page"></div>
//
// and a paste or an opened HTML file brings in the page breaks other editors
// write: an <hr> or a <br> carrying page-break-before: always (Google Docs,
// Word). An element with text in it is never taken for one, so no text is lost.
//
// Ctrl+Enter (Cmd+Enter) inserts one, as in Word; it used to be a line break,
// which Shift+Enter still is. In a code block Ctrl+Enter still leaves the
// block, and in a table, where the printer cannot break, it is still a line
// break.
import { Node, canInsertNode, isNodeSelection } from '@tiptap/core';
import type { EditorState } from '@tiptap/pm/state';
import { NodeSelection, TextSelection } from '@tiptap/pm/state';

export const PAGE_BREAK_NODE = 'pageBreak';

const BREAK_STYLE = 'page-break-before: always; break-before: page';

declare module '@tiptap/core' {
	interface Commands<ReturnType> {
		pageBreak: {
			/** Inserts a page break at the cursor. */
			setPageBreak: () => ReturnType;
		};
	}
}

export interface PageBreakHost {
	/** The label the line carries on screen. */
	label(): string;
}

// Whether an element written by another editor is a page break: it asks for a
// break before itself and holds no text.
function isForeignPageBreak(el: HTMLElement): boolean {
	const style = (el.getAttribute('style') || '').toLowerCase().replace(/\s+/g, '');
	if (!/(page-break-before:always|break-before:page)/.test(style)) return false;
	return !(el.textContent || '').trim();
}

// A table cell cannot be broken across pages, and a code block holds text
// only: neither takes a page break.
function canBreakHere(state: EditorState): boolean {
	const { $from } = state.selection;
	for (let depth = $from.depth; depth > 0; depth--) {
		const node = $from.node(depth);
		if (node.type.spec.tableRole || node.type.spec.code) return false;
	}
	return canInsertNode(state, state.schema.nodes[PAGE_BREAK_NODE]);
}

export function createPageBreakExtension(host: PageBreakHost) {
	return Node.create({
		name: PAGE_BREAK_NODE,
		group: 'block',
		atom: true,
		selectable: true,
		// Moved with the editor's drag handle, like the other blocks.
		draggable: false,
		// Ahead of HardBreak, which binds Ctrl+Enter too.
		priority: 1000,

		parseHTML() {
			return [
				{ tag: 'div[data-page-break]' },
				// Before the horizontal rule and the line break, which take
				// every <hr> and <br>.
				{ tag: 'hr', priority: 60, getAttrs: (el) => isForeignPageBreak(el as HTMLElement) ? null : false },
				{ tag: 'br', priority: 60, getAttrs: (el) => isForeignPageBreak(el as HTMLElement) ? null : false },
			];
		},

		// An element rather than ['div', { style }]: ProseMirror writes a style
		// through style.cssText, where the browser folds the legacy
		// page-break-before into break-before, and the importers of other
		// editors know the legacy one best.
		renderHTML() {
			const dom = document.createElement('div');
			dom.setAttribute('data-page-break', '');
			dom.setAttribute('style', BREAK_STYLE);
			return dom;
		},

		// The editor's own element adds the label, which the stored HTML
		// leaves out.
		addNodeView() {
			return () => {
				const dom = document.createElement('div');
				dom.setAttribute('data-page-break', '');
				dom.setAttribute('style', BREAK_STYLE);
				dom.className = 'memo-page-break';
				const label = document.createElement('span');
				label.className = 'memo-page-break-label';
				label.textContent = host.label();
				dom.appendChild(label);
				return { dom };
			};
		},

		addCommands() {
			return {
				// As the horizontal rule does: the block goes in at the cursor
				// (splitting the paragraph) or after a selected block, and the
				// cursor lands on what follows, a new paragraph at the end.
				setPageBreak: () => ({ chain, state }) => {
					if (!canBreakHere(state)) return false;
					const { selection } = state;
					const current = chain();
					if (isNodeSelection(selection)) current.insertContentAt(selection.$to.pos, { type: this.name });
					else current.insertContent({ type: this.name });
					return current.command(({ tr, dispatch }) => {
						if (dispatch) {
							const { $to } = tr.selection;
							if ($to.nodeAfter) {
								if ($to.nodeAfter.isTextblock) tr.setSelection(TextSelection.create(tr.doc, $to.pos + 1));
								else if ($to.nodeAfter.isBlock) tr.setSelection(NodeSelection.create(tr.doc, $to.pos));
								else tr.setSelection(TextSelection.create(tr.doc, $to.pos));
							} else {
								const posAfter = $to.end();
								const paragraph = tr.doc.type.schema.nodes.paragraph.create();
								tr.insert(posAfter, paragraph);
								tr.setSelection(TextSelection.create(tr.doc, posAfter + 1));
							}
							tr.scrollIntoView();
						}
						return true;
					}).run();
				},
			};
		},

		addKeyboardShortcuts() {
			return {
				'Mod-Enter': () => this.editor.commands.setPageBreak(),
			};
		},
	});
}
