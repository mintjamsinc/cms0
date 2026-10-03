/**
 * Renders the Markdown of a chat message to HTML.
 *
 * A message is written by somebody else, so nothing in it is trusted: the text
 * is escaped first and only the markup below is turned back into tags. Raw
 * HTML in a message shows as text.
 *
 *   **bold**  *italic*  ~~strike~~  `code`  ```code block```
 *   [label](https://…)  bare https://… links  > quote  - list item
 */

const ESCAPES: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

function escapeHtml(text: string): string {
	return text.replace(/[&<>"']/g, (c) => ESCAPES[c]);
}

function link(url: string, label: string): string {
	return `<a href="${url}" target="_blank" rel="noopener noreferrer">${label}</a>`;
}

export interface ChatMarkdownOptions {
	/** The user ids the message names as @name; those are marked up. */
	mentions?: string[];
	/** The reader's user id: a mention of the reader is marked up more. */
	me?: string;
}

/** Inline markup of one escaped line. */
function renderInline(escaped: string, options: ChatMarkdownOptions): string {
	// Code spans and links are set aside first, so the markup inside them is
	// left alone and a URL is not cut by an emphasis mark.
	const held: string[] = [];
	const hold = (html: string): string => `\u0000${held.push(html) - 1}\u0000`;

	let text = escaped.replace(/`([^`\n]+)`/g, (_m, code) => hold(`<code>${code}</code>`));
	text = text.replace(/\[([^\]\n]+)\]\((https?:\/\/[^\s)]+|mailto:[^\s)]+)\)/g, (_m, label, url) => hold(link(url, label)));
	text = text.replace(/https?:\/\/[^\s<]+/g, (url) => {
		// Punctuation that ends the sentence is not part of the address.
		const trailing = /(?:&quot;|&#39;|&gt;|[.,;:!?)\]])+$/.exec(url)?.[0] ?? '';
		const address = trailing ? url.slice(0, -trailing.length) : url;
		return hold(link(address, address)) + trailing;
	});

	text = text
		.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
		.replace(/(^|[^*\w])\*([^*\s][^*\n]*?)\*(?![*\w])/g, '$1<em>$2</em>')
		.replace(/~~([^~\n]+)~~/g, '<del>$1</del>');

	// Only a name the server resolved to a user is a mention; "@" in an address
	// or before an unknown name stays text.
	const mentions = options.mentions || [];
	if (mentions.length) {
		text = text.replace(/(^|[^\w@])@([A-Za-z0-9][A-Za-z0-9_.-]{0,63})/g, (whole, before, name) => {
			const id = name.replace(/[.-]+$/, '');
			if (!mentions.includes(id)) return whole;
			const tail = name.slice(id.length);
			const cls = id === options.me ? 'wt-chat-mention wt-chat-mention-me' : 'wt-chat-mention';
			return `${before}<span class="${cls}">@${id}</span>${tail}`;
		});
	}

	return text.replace(/\u0000(\d+)\u0000/g, (_m, i) => held[Number(i)]);
}

/** The lines outside code blocks: quotes, list items and plain lines. */
function renderLines(source: string, options: ChatMarkdownOptions): string {
	const out: string[] = [];
	let list: string[] = [];
	let quote: string[] = [];
	const flush = () => {
		if (list.length) {
			out.push(`<ul>${list.map((item) => `<li>${item}</li>`).join('')}</ul>`);
			list = [];
		}
		if (quote.length) {
			out.push(`<blockquote>${quote.join('<br>')}</blockquote>`);
			quote = [];
		}
	};
	let lines: string[] = [];
	const flushLines = () => {
		if (lines.length) {
			out.push(`<p>${lines.join('<br>')}</p>`);
			lines = [];
		}
	};

	for (const raw of source.split('\n')) {
		const item = /^\s*[-*]\s+(.*)$/.exec(raw);
		const quoted = /^\s*>\s?(.*)$/.exec(raw);
		if (item) {
			flushLines();
			if (quote.length) flush();
			list.push(renderInline(escapeHtml(item[1]), options));
		} else if (quoted) {
			flushLines();
			if (list.length) flush();
			quote.push(renderInline(escapeHtml(quoted[1]), options));
		} else if (!raw.trim()) {
			flushLines();
			flush();
		} else {
			flush();
			lines.push(renderInline(escapeHtml(raw), options));
		}
	}
	flushLines();
	flush();
	return out.join('');
}

export function renderChatMarkdown(source: string, options: ChatMarkdownOptions = {}): string {
	const text = (source || '').replace(/\r\n?/g, '\n').replace(/\u0000/g, '');
	const out: string[] = [];
	const fence = /```[^\n]*\n([\s\S]*?)(?:\n```|$)/g;
	let last = 0;
	let match: RegExpExecArray | null;
	while ((match = fence.exec(text)) !== null) {
		out.push(renderLines(text.slice(last, match.index), options));
		out.push(`<pre><code>${escapeHtml(match[1])}</code></pre>`);
		last = match.index + match[0].length;
	}
	out.push(renderLines(text.slice(last), options));
	return out.join('');
}
