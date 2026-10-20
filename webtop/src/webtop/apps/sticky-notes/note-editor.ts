/**
 * Sticky notes: the document a note is written in.
 *
 * The same Tiptap editor as the Memo app, cut down to what a note needs:
 * lists, checkboxes, bold, strike-through, one heading level, a quotation.
 * The widget (widget.ts) edits with these extensions; the list window
 * (app.ts) renders a note's document to HTML with the same set, so a card
 * shows what the note shows. The document's styles are in assets/css/note.css,
 * under .sn-doc, loaded by both pages.
 */

import { generateHTML, type Extensions } from '@tiptap/core';
import StarterKit from '@tiptap/starter-kit';
import { TaskList } from '@tiptap/extension-task-list';
import { TaskItem } from '@tiptap/extension-task-item';
import { noteContent, type Note } from './notes.js';

export function noteExtensions(): Extensions {
	return [
		StarterKit.configure({
			heading: { levels: [1] },
			codeBlock: false,
			horizontalRule: false,
			// A URL is shown as a link; a click puts the cursor there, as
			// everywhere else in the note.
			link: {
				openOnClick: false,
				autolink: true,
				HTMLAttributes: { rel: 'noopener noreferrer nofollow', target: '_blank' },
			},
		}),
		TaskList,
		TaskItem.configure({ nested: true }),
	];
}

let renderExtensions: Extensions | null = null;

/** The note's document as HTML, as the widget would show it. */
export function noteHtml(note: Note | null): string {
	if (!renderExtensions) renderExtensions = noteExtensions();
	try {
		return generateHTML(noteContent(note) as any, renderExtensions);
	} catch (e) {
		console.warn('[Sticky Notes] A note could not be rendered:', e);
		return '';
	}
}
