/**
 * A message box that shows a mention by the user's name while the message
 * says the user's id.
 *
 * The box is a contenteditable element. Text is text; a mention is a chip, an
 * element that cannot be edited into (contenteditable="false") and is removed
 * whole by Backspace:
 *
 *   <span class="wt-chat-mention" contenteditable="false" data-user-id="id">@Name</span>
 *
 * What is sent is the text of the box (getText), in which a chip is written
 * as `@id`: the form the server finds a mention in and the form a message is
 * stored in. The other way round (setText) puts a message's text back into
 * the box, with each `@id` whose name is known shown as a chip again.
 *
 * The browser's own editing is kept for what it does well (typing, selecting,
 * undo, IME); the box only intercepts what the message needs: a line break is
 * a <br> (the host decides what Enter does), a paste is plain text, and the
 * chips. The editing commands go through execCommand where the browser has
 * it, so that they take part in the undo history, with a plain DOM fallback.
 */

export interface MentionUser {
	id: string;
	displayName: string;
}

const CHIP_ATTR = 'data-user-id';
const CHIP_CLASS = 'wt-chat-mention';
// The same name as the server reads (ChatMessages.mentionsIn) and the message
// renderer marks up (chat-markdown.ts).
const MENTION_RE = /(^|[^\w@])@([A-Za-z0-9][A-Za-z0-9_.-]{0,63})/g;
const BLOCK_TAGS = new Set(['DIV', 'P', 'LI', 'BLOCKQUOTE', 'PRE', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6']);

function isChip(node: Node): node is HTMLElement {
	return node.nodeType === Node.ELEMENT_NODE && (node as HTMLElement).hasAttribute(CHIP_ATTR);
}

function isBlock(node: Node): boolean {
	return node.nodeType === Node.ELEMENT_NODE && BLOCK_TAGS.has((node as HTMLElement).tagName);
}

function makeChip(user: MentionUser): HTMLSpanElement {
	const chip = document.createElement('span');
	chip.className = CHIP_CLASS;
	chip.contentEditable = 'false';
	chip.setAttribute(CHIP_ATTR, user.id);
	chip.title = `@${user.id}`;
	chip.textContent = `@${user.displayName || user.id}`;
	return chip;
}

/**
 * The text of the node's content: a chip is `@id`, a <br> a line break, a
 * block a line of its own. A <br> that ends a block (or the whole box) is the
 * browser's padding that keeps an empty last line reachable, not a line
 * break the writer typed; `padded` says the content is such a whole.
 */
function textOf(root: Node, padded: boolean): string {
	let out = '';
	// A chip written right after a word, or a word right after a chip, would
	// join the id into the word: a space keeps them apart. A dot or dash
	// after a chip is left alone: the server reads "@id." as the id and a
	// full stop (it takes the trailing dots and dashes off the name).
	let afterChip = false;
	const put = (text: string) => {
		if (!text) return;
		if (afterChip && /^[.-]*\w/.test(text)) out += ' ';
		afterChip = false;
		out += text;
	};
	const walk = (node: Node, whole: boolean) => {
		const children = Array.from(node.childNodes);
		children.forEach((child, i) => {
			if (child.nodeType === Node.TEXT_NODE) {
				put((child.nodeValue || '').replace(/ /g, ' '));
				return;
			}
			if (child.nodeType !== Node.ELEMENT_NODE) return;
			const el = child as HTMLElement;
			if (el.tagName === 'BR') {
				if (whole && i === children.length - 1) return;
				out += '\n';
				afterChip = false;
				return;
			}
			if (isChip(el)) {
				if (out && /[\w@]$/.test(out)) out += ' ';
				out += `@${el.getAttribute(CHIP_ATTR)}`;
				afterChip = true;
				return;
			}
			if (isBlock(el)) {
				if (out) {
					out += '\n';
					afterChip = false;
				}
				walk(el, true);
				return;
			}
			walk(el, false);
		});
	};
	walk(root, padded);
	return out;
}

/** The content for a text: lines joined by <br>, each `@id` with a known name a chip. */
function contentOf(text: string, names: Map<string, string>): DocumentFragment {
	const fragment = document.createDocumentFragment();
	const lines = (text || '').replace(/\r\n?/g, '\n').split('\n');
	lines.forEach((line, i) => {
		if (i > 0) fragment.appendChild(document.createElement('br'));
		let last = 0;
		MENTION_RE.lastIndex = 0;
		let m: RegExpExecArray | null;
		while ((m = MENTION_RE.exec(line)) !== null) {
			const name = m[2];
			const id = name.replace(/[.-]+$/, '');
			if (!names.has(id)) continue;
			const at = m.index + m[1].length;
			if (at > last) fragment.appendChild(document.createTextNode(line.slice(last, at)));
			fragment.appendChild(makeChip({ id, displayName: names.get(id) || id }));
			last = at + 1 + id.length;
			// The character after the name may begin the next mention.
			MENTION_RE.lastIndex = last;
		}
		if (last < line.length) fragment.appendChild(document.createTextNode(line.slice(last)));
	});
	// An empty last line needs the padding <br> to be reachable.
	if (lines.length > 1 && !lines[lines.length - 1]) fragment.appendChild(document.createElement('br'));
	return fragment;
}

/** The node before `node` in the box: its previous sibling, or an ancestor's. */
function stepBack(node: Node, root: Node): Node | null {
	let cur: Node | null = node;
	while (cur && cur !== root) {
		if (cur.previousSibling) return cur.previousSibling;
		cur = cur.parentNode;
	}
	return null;
}

/**
 * The last text node at or before `start`, with its end as the offset, or
 * null when a chip or a line break comes first: what the name typed after an
 * @ cannot reach across.
 */
function lastTextIn(start: Node | null, root: Node): { node: Text; offset: number } | null {
	let cur = start;
	while (cur) {
		if (cur.nodeType === Node.TEXT_NODE) return { node: cur as Text, offset: (cur as Text).length };
		if (isChip(cur) || (cur as HTMLElement).tagName === 'BR') return null;
		cur = cur.lastChild || stepBack(cur, root);
	}
	return null;
}

/** The text node the position is in or, between nodes, the one that ends before it. */
function textNodeBefore(node: Node, offset: number, root: Node): { node: Text; offset: number } | null {
	if (node.nodeType === Node.TEXT_NODE) return { node: node as Text, offset };
	return lastTextIn(node.childNodes[offset - 1] || stepBack(node, root), root);
}

function previousTextNode(node: Text, root: Node): Text | null {
	return lastTextIn(stepBack(node, root), root)?.node ?? null;
}

function exec(command: string, value?: string): boolean {
	try {
		return typeof document.execCommand === 'function' && document.execCommand(command, false, value);
	} catch {
		return false;
	}
}

export class MentionEditor {
	readonly el: HTMLElement;

	constructor(el: HTMLElement) {
		this.el = el;
		el.contentEditable = 'true';
		el.spellcheck = true;
	}

	/** The message as text: a chip is `@id`. */
	getText(): string {
		return textOf(this.el, true);
	}

	/** Puts a message's text in the box; the `@id`s named in `users` become chips. */
	setText(text: string, users: Iterable<MentionUser> = []): void {
		const names = new Map<string, string>();
		for (const u of users) names.set(u.id, u.displayName || u.id);
		this.el.replaceChildren(contentOf(text, names));
	}

	clear(): void {
		this.el.replaceChildren();
	}

	isEmpty(): boolean {
		return !this.getText().trim();
	}

	/**
	 * Takes out what the browser leaves of an emptied box (a <br>, an empty
	 * element), so that the box is :empty and shows its placeholder.
	 */
	tidy(): void {
		const el = this.el;
		if (el.childNodes.length === 0) return;
		if (el.querySelector(`[${CHIP_ATTR}]`)) return;
		if ((el.textContent || '').replace(/ /g, ' ').length) return;
		if (el.querySelectorAll('br').length > 1) return;
		el.replaceChildren();
	}

	/** Whether the selection is a caret inside the box. */
	hasCaret(): boolean {
		const sel = document.getSelection();
		if (!sel || !sel.rangeCount || !sel.isCollapsed) return false;
		return this.el.contains(sel.getRangeAt(0).startContainer);
	}

	/** The text from the start of the box to the caret, or null when the caret is elsewhere. */
	textBeforeCaret(): string | null {
		if (!this.hasCaret()) return null;
		const caret = document.getSelection()!.getRangeAt(0);
		const range = document.createRange();
		range.selectNodeContents(this.el);
		range.setEnd(caret.startContainer, caret.startOffset);
		return textOf(range.cloneContents(), false);
	}

	/**
	 * Puts a chip for the user in place of the `count` characters before the
	 * caret (the @ and the name typed so far), followed by a space, and leaves
	 * the caret after the space. False when those characters are not plain
	 * text right before the caret.
	 */
	replaceBeforeCaret(count: number, user: MentionUser): boolean {
		if (!this.hasCaret()) return false;
		const sel = document.getSelection()!;
		const caret = sel.getRangeAt(0);
		const range = document.createRange();
		range.setStart(caret.startContainer, caret.startOffset);
		range.setEnd(caret.startContainer, caret.startOffset);
		let at = textNodeBefore(caret.startContainer, caret.startOffset, this.el);
		let remaining = count;
		while (at && remaining > 0) {
			if (at.offset >= remaining) {
				range.setStart(at.node, at.offset - remaining);
				remaining = 0;
				break;
			}
			remaining -= at.offset;
			const prev = previousTextNode(at.node, this.el);
			at = prev ? { node: prev, offset: prev.length } : null;
		}
		if (remaining > 0) return false;

		sel.removeAllRanges();
		sel.addRange(range);
		const chip = makeChip(user);
		if (exec('insertHTML', `${chip.outerHTML} `)) {
			return true;
		}
		range.deleteContents();
		const space = document.createTextNode(' ');
		range.insertNode(space);
		range.insertNode(chip);
		this.caretAfter(space);
		return true;
	}

	/** A line break at the caret: a <br>, never a block. */
	insertLineBreak(): void {
		if (!this.hasCaret()) this.focus(true);
		if (exec('insertLineBreak')) return;
		const sel = document.getSelection();
		if (!sel || !sel.rangeCount) return;
		const range = sel.getRangeAt(0);
		range.deleteContents();
		const br = document.createElement('br');
		range.insertNode(br);
		// A break at the very end needs the padding <br> after it to show.
		if (!br.nextSibling) br.parentNode?.appendChild(document.createElement('br'));
		range.setStartAfter(br);
		range.collapse(true);
		sel.removeAllRanges();
		sel.addRange(range);
	}

	/** Plain text at the caret, its line breaks as <br>. */
	insertText(text: string): void {
		if (!this.hasCaret()) this.focus(true);
		const lines = (text || '').replace(/\r\n?/g, '\n').split('\n');
		lines.forEach((line, i) => {
			if (i > 0) this.insertLineBreak();
			if (!line) return;
			if (exec('insertText', line)) return;
			const sel = document.getSelection();
			if (!sel || !sel.rangeCount) return;
			const range = sel.getRangeAt(0);
			range.deleteContents();
			const node = document.createTextNode(line);
			range.insertNode(node);
			this.caretAfter(node);
		});
	}

	focus(atEnd = false): void {
		this.el.focus();
		if (!atEnd) return;
		const sel = document.getSelection();
		if (!sel) return;
		const range = document.createRange();
		range.selectNodeContents(this.el);
		range.collapse(false);
		sel.removeAllRanges();
		sel.addRange(range);
	}

	private caretAfter(node: Node): void {
		const sel = document.getSelection();
		if (!sel) return;
		const range = document.createRange();
		range.setStartAfter(node);
		range.collapse(true);
		sel.removeAllRanges();
		sel.addRange(range);
	}
}
