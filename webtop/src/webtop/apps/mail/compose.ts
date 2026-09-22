/**
 * Compose helpers: what a reply or a forward starts with, and the conversions
 * between the text and the HTML body.
 */

import type { ComposeFormat, ComposeMode, MailAddress, MailDraftInput, MailMessage } from './api.js';

export function escapeHtml(s: string): string {
	return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

export function formatAddress(a: MailAddress): string {
	if (!a.name) return a.address;
	// Quoted when the name holds characters that separate addresses.
	const name = /[,;<>"@()]/.test(a.name) ? `"${a.name.replace(/["\\]/g, '\\$&')}"` : a.name;
	return `${name} <${a.address}>`;
}

export function formatAddresses(list: MailAddress[]): string {
	return list.map(formatAddress).join(', ');
}

/** Paragraphs at blank lines, line breaks within them. */
export function textToHtml(text: string): string {
	const paragraphs = text.replace(/\r\n?/g, '\n').split(/\n{2,}/);
	return paragraphs.map((p) => `<p>${escapeHtml(p).replace(/\n/g, '<br>')}</p>`).join('');
}

const BLOCKS = new Set(['P', 'DIV', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'UL', 'OL', 'TABLE', 'TR', 'PRE']);

/**
 * The text alternative of an HTML body: paragraphs separated by blank lines,
 * list items with a bullet, quotations with "> ".
 */
export function htmlToText(html: string): string {
	const doc = new DOMParser().parseFromString(html, 'text/html');
	return tidy(textOf(doc.body)) + '\n';
}

function tidy(s: string): string {
	return s.replace(/[ \t]+\n/g, '\n').replace(/\n[ \t]+/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
}

function textOf(node: Node): string {
	let s = '';
	const paragraph = () => {
		if (s && !/\n\n$/.test(s)) s += /\n$/.test(s) ? '\n' : '\n\n';
	};
	for (const child of Array.from(node.childNodes)) {
		if (child.nodeType === Node.TEXT_NODE) {
			s += (child.textContent || '').replace(/\s+/g, ' ');
			continue;
		}
		if (child.nodeType !== Node.ELEMENT_NODE) continue;
		const el = child as HTMLElement;
		const tag = el.tagName;
		if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'HEAD') continue;
		if (tag === 'BR') {
			s += '\n';
		} else if (tag === 'HR') {
			paragraph();
			s += '----';
			paragraph();
		} else if (tag === 'BLOCKQUOTE') {
			paragraph();
			s += quoteText(tidy(textOf(el)));
			paragraph();
		} else if (tag === 'LI') {
			if (s && !s.endsWith('\n')) s += '\n';
			const n = Array.from(el.parentElement?.children || []).filter((c) => c.tagName === 'LI').indexOf(el);
			s += (el.parentElement?.tagName === 'OL' ? `${n + 1}. ` : '- ') + tidy(textOf(el)).replace(/\n/g, '\n  ');
			s += '\n';
		} else if (tag === 'A') {
			const href = el.getAttribute('href') || '';
			const text = textOf(el);
			s += text;
			if (/^https?:/i.test(href) && href !== text.trim()) s += ` <${href}>`;
		} else if (BLOCKS.has(tag)) {
			paragraph();
			s += textOf(el);
			paragraph();
		} else {
			s += textOf(el);
		}
	}
	return s;
}

export function quoteText(text: string): string {
	return text.replace(/\r\n?/g, '\n').replace(/\n+$/, '').split('\n').map((l) => (l.startsWith('>') ? '>' : '> ') + l).join('\n');
}

function prefixed(subject: string, prefix: string, pattern: RegExp): string {
	return pattern.test(subject) ? subject : `${prefix} ${subject}`.trim();
}

export interface ComposeLabels {
	/** "On {date}, {name} wrote:" */
	wrote: (date: string, name: string) => string;
	forwarded: string;
	from: string;
	to: string;
	cc: string;
	date: string;
	subject: string;
}

/**
 * The draft a reply, a reply to all or a forward of `m` starts with. The body
 * follows the original: HTML when it has HTML, text otherwise.
 */
export function prefill(m: MailMessage, mode: Exclude<ComposeMode, 'new'>, own: Set<string>, date: string, labels: ComposeLabels): MailDraftInput {
	const format: ComposeFormat = m.html != null ? 'html' : 'text';
	const input: MailDraftInput = { accountId: m.accountId, mode, originalId: m.id, format, to: '', cc: '', bcc: '' };
	const sender = m.from[0] ? (m.from[0].name || m.from[0].address) : '';

	if (mode === 'forward') {
		input.subject = prefixed(m.subject || '', 'Fwd:', /^\s*(fwd?|転送)\s*[:：]/i);
		const head: [string, string][] = [
			[labels.from, formatAddresses(m.from)],
			[labels.date, date],
			[labels.subject, m.subject || ''],
			[labels.to, formatAddresses(m.to)],
		];
		if (m.cc.length) head.push([labels.cc, formatAddresses(m.cc)]);
		const headText = `---------- ${labels.forwarded} ----------\n` + head.map(([k, v]) => `${k}: ${v}`).join('\n');
		if (format === 'html') {
			input.html = '<p></p><p>' + headText.split('\n').map(escapeHtml).join('<br>') + '</p>' + (m.html || '');
		} else {
			input.text = '\n\n' + headText + '\n\n' + (m.text || '');
		}
		input.attachments = m.attachments.filter((a) => !a.inline).map((a) => ({
			name: a.name, mimeType: a.mimeType, messageId: m.id, index: a.index,
		}));
		return input;
	}

	input.subject = prefixed(m.subject || '', 'Re:', /^\s*(re|返信)\s*[:：]/i);
	const isOwn = (a: MailAddress) => own.has(a.address.toLowerCase());
	// A reply to one's own sent message goes to its recipients again.
	const primary = m.role === 'sent' ? m.to : (m.replyTo.length ? m.replyTo : m.from);
	let to = [...primary];
	let cc: MailAddress[] = [];
	if (mode === 'replyAll') {
		to = unique([...to, ...(m.role === 'sent' ? [] : m.to)].filter((a) => !isOwn(a) || primary.includes(a)));
		cc = unique(m.cc.filter((a) => !isOwn(a))).filter((a) => !to.some((b) => b.address.toLowerCase() === a.address.toLowerCase()));
	}
	input.to = formatAddresses(to);
	input.cc = formatAddresses(cc);
	const ids = m.messageId ? [m.messageId] : [];
	input.inReplyTo = ids;
	input.references = [...(m.references || []).filter((r) => !ids.includes(r)), ...ids];

	const wrote = labels.wrote(date, sender);
	if (format === 'html') {
		input.html = `<p></p><p>${escapeHtml(wrote)}</p><blockquote>${m.html || ''}</blockquote>`;
	} else {
		input.text = `\n\n${wrote}\n${quoteText(m.text || '')}\n`;
	}
	return input;
}

function unique(list: MailAddress[]): MailAddress[] {
	const seen = new Set<string>();
	return list.filter((a) => {
		const key = a.address.toLowerCase();
		if (seen.has(key)) return false;
		seen.add(key);
		return true;
	});
}

/** The address being typed: what follows the last separator. */
export function currentToken(value: string): { head: string; token: string } {
	const m = /^(.*[,;]\s*)?([^,;]*)$/.exec(value);
	return { head: m?.[1] || '', token: (m?.[2] || '').trim() };
}
