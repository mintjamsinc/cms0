// Shared, stateless CodeMirror building blocks used by both the Inspector
// (wt-inspector) property "Text Editor" and the Schema Manager property editor.
// Extracting them here keeps a single source of truth for the editor theme,
// syntax-highlight palette, linters, structured-text formatter and language
// resolution, so the two editors look and behave identically.
//
// Everything here is pure / module-scoped state-free: no component (`vm`)
// coupling. The stateful pieces (EditorView lifecycle, maximize overlay,
// find/replace) stay in each host so they can bind to their own model.
import { EditorView } from '@codemirror/view';
import { HighlightStyle } from '@codemirror/language';
import { tags as t } from '@lezer/highlight';
import { linter, type Diagnostic } from '@codemirror/lint';
import { json, jsonParseLinter } from '@codemirror/lang-json';
import { html } from '@codemirror/lang-html';
import { xml } from '@codemirror/lang-xml';
import { markdown } from '@codemirror/lang-markdown';
import { javascript } from '@codemirror/lang-javascript';

// CodeMirror theme (CSS-variable driven so light/dark theme switches are
// automatic). The host app must define the referenced --cm-* / --*-bg tokens.
export const cmTheme = EditorView.theme({
	"&": { backgroundColor: "var(--body-bg)", color: "var(--body-color)" },
	".cm-content": { caretColor: "var(--body-color)" },
	".cm-cursor, .cm-dropCursor": { borderLeftColor: "var(--body-color)" },
	".cm-scroller .cm-layer.cm-selectionLayer .cm-selectionBackground, ::selection": { backgroundColor: "var(--item-selected-bg)" },
	".cm-gutters": { backgroundColor: "var(--list-header-bg)", color: "var(--text-soft-color)", border: "none" },
	".cm-activeLineGutter": { backgroundColor: "var(--btn-icon-hover-bg)" },
	".cm-activeLine": { backgroundColor: "var(--btn-icon-hover-bg)" },
});

export const cmHighlight = HighlightStyle.define([
	{ tag: [t.keyword, t.controlKeyword, t.modifier, t.operatorKeyword, t.self], color: "var(--cm-keyword)" },
	{ tag: [t.string, t.special(t.string), t.regexp, t.attributeValue], color: "var(--cm-string)" },
	{ tag: [t.comment, t.lineComment, t.blockComment, t.docComment], color: "var(--cm-comment)", fontStyle: "italic" },
	{ tag: [t.number, t.bool, t.null, t.atom, t.literal], color: "var(--cm-number)" },
	{ tag: [t.function(t.variableName), t.function(t.propertyName), t.macroName, t.labelName], color: "var(--cm-function)" },
	{ tag: [t.className, t.typeName, t.namespace, t.definition(t.typeName)], color: "var(--cm-class)" },
	{ tag: [t.propertyName, t.attributeName], color: "var(--cm-property)" },
	{ tag: [t.tagName, t.angleBracket], color: "var(--cm-tag)" },
	{ tag: [t.operator, t.derefOperator, t.compareOperator, t.arithmeticOperator, t.logicOperator, t.bitwiseOperator, t.updateOperator], color: "var(--cm-operator)" },
	{ tag: [t.punctuation, t.bracket, t.brace, t.paren, t.separator], color: "var(--cm-punctuation)" },
	{ tag: [t.meta, t.processingInstruction, t.definition(t.variableName)], color: "var(--cm-meta)" },
	{ tag: t.heading, color: "var(--cm-keyword)", fontWeight: "bold" },
	{ tag: [t.link, t.url], color: "var(--cm-property)", textDecoration: "underline" },
	{ tag: t.emphasis, fontStyle: "italic" },
	{ tag: t.strong, fontWeight: "bold" },
	{ tag: t.strikethrough, textDecoration: "line-through" },
	{ tag: t.invalid, color: "var(--cm-invalid)" },
]);

export function xmlLinter(view: EditorView): Diagnostic[] {
	const diagnostics: Diagnostic[] = [];
	const content = view.state.doc.toString();
	if (!content.trim()) return diagnostics;
	try {
		const parser = new DOMParser();
		const doc = parser.parseFromString(content, 'application/xml');
		const errorNode = doc.querySelector('parsererror');
		if (errorNode) {
			diagnostics.push({
				from: 0,
				to: Math.min(content.length, 1),
				severity: 'error',
				message: errorNode.textContent || 'XML parse error',
			});
		}
	} catch (e: any) {
		diagnostics.push({ from: 0, to: 0, severity: 'error', message: e.message || 'XML parse error' });
	}
	return diagnostics;
}

export function htmlLinter(view: EditorView): Diagnostic[] {
	const diagnostics: Diagnostic[] = [];
	const content = view.state.doc.toString();
	if (!content.trim()) return diagnostics;
	try {
		const parser = new DOMParser();
		const doc = parser.parseFromString(content, 'text/html');
		void doc;
	} catch (e: any) {
		diagnostics.push({ from: 0, to: 0, severity: 'error', message: e.message || 'HTML parse error' });
	}
	return diagnostics;
}

function prettyIndentXml(xmlStr: string): string {
	const PADDING = '  ';
	const tokens: string[] = [];
	let i = 0;
	while (i < xmlStr.length) {
		if (xmlStr[i] === '<') {
			const end = xmlStr.indexOf('>', i);
			if (end === -1) {
				tokens.push(xmlStr.slice(i));
				break;
			}
			tokens.push(xmlStr.slice(i, end + 1));
			i = end + 1;
		} else {
			const next = xmlStr.indexOf('<', i);
			const text = xmlStr.slice(i, next === -1 ? xmlStr.length : next);
			if (text.length) tokens.push(text);
			if (next === -1) break;
			i = next;
		}
	}
	let pad = 0;
	const lines: string[] = [];
	for (const tok of tokens) {
		if (tok.startsWith('<!') || tok.startsWith('<?')) {
			lines.push(PADDING.repeat(pad) + tok);
		} else if (tok.startsWith('</')) {
			pad = Math.max(pad - 1, 0);
			lines.push(PADDING.repeat(pad) + tok);
		} else if (tok.startsWith('<')) {
			const selfClosing = /\/>$/.test(tok);
			lines.push(PADDING.repeat(pad) + tok);
			if (!selfClosing) pad++;
		} else {
			const trimmed = tok.trim();
			if (trimmed) lines.push(PADDING.repeat(pad) + trimmed);
		}
	}
	return lines.join('\n');
}

export function formatStructuredText(content: string, editorType: string): string {
	if (!content.trim()) return content;
	try {
		if (editorType === 'JSON') {
			return JSON.stringify(JSON.parse(content), null, 2);
		}
		if (editorType === 'XML') {
			const parser = new DOMParser();
			const doc = parser.parseFromString(content, 'application/xml');
			if (doc.querySelector('parsererror')) return content;
			const raw = new XMLSerializer().serializeToString(doc);
			const formatted = prettyIndentXml(raw);
			return formatted.trim() ? formatted : content;
		}
		if (editorType === 'HTML') {
			const parser = new DOMParser();
			const doc = parser.parseFromString(content, 'text/html');
			const hasHtml = /<html[\s>]/i.test(content);
			const hasBody = /<body[\s>]/i.test(content);
			const hasHead = /<head[\s>]/i.test(content);
			let raw = '';
			if (hasHtml) {
				raw = doc.documentElement ? doc.documentElement.outerHTML : '';
			} else if (hasBody && doc.body) {
				raw = doc.body.outerHTML;
				if (hasHead && doc.head && doc.head.innerHTML.trim()) {
					raw = doc.head.outerHTML + raw;
				}
			} else if (hasHead && doc.head) {
				raw = doc.head.outerHTML;
			} else {
				raw = (doc.body && doc.body.innerHTML) || '';
			}
			if (!raw.trim()) return content;
			const formatted = prettyIndentXml(raw);
			return formatted.trim() ? formatted : content;
		}
	} catch {
		// Return original if formatting fails
	}
	return content;
}

// Resolve the CodeMirror language extension(s) for an editor type. JSON/XML/HTML
// additionally wire in their linter. 'ECMAScript' enables JavaScript highlighting
// for schema scripts (validation, display format, calculated default value).
// Anything else (Text Editor, Text, '', …) gets no language (plain text).
export function getLanguageExtensionForEditorType(editorType: string) {
	switch (editorType) {
		case 'JSON': return [json(), linter(jsonParseLinter())];
		case 'HTML': return [html(), linter(htmlLinter)];
		case 'XML': return [xml(), linter(xmlLinter)];
		case 'Markdown': return [markdown()];
		case 'ECMAScript': return [javascript()];
		default: return [];
	}
}
