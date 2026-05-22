import { ApplicationInstance } from "../../services/webtop-service.js";
import { isFolderNode, type Node, type LockInfo, type JobStatus } from "../../graphql/types.js";
import { deleteContentItems, type DeleteJobHandle, type DeleteJobProgress } from "../../services/content-delete.js";
import { IdpServiceGraphQL } from "../../services/idp-service-graphql.js";

// CodeMirror imports for structured text editors (JSON/XML/HTML/Markdown)
import { EditorState, Compartment } from "@codemirror/state";
import { EditorView, keymap, lineNumbers, highlightActiveLine, drawSelection } from "@codemirror/view";
import { defaultKeymap, history, historyKeymap } from "@codemirror/commands";
import { syntaxHighlighting, HighlightStyle, bracketMatching, indentOnInput } from "@codemirror/language";
import { tags as t } from "@lezer/highlight";
import { search, findNext, findPrevious, replaceNext, replaceAll, setSearchQuery, SearchQuery } from "@codemirror/search";
import { json, jsonParseLinter } from "@codemirror/lang-json";
import { html } from "@codemirror/lang-html";
import { xml } from "@codemirror/lang-xml";
import { markdown } from "@codemirror/lang-markdown";
import { lintGutter, linter, type Diagnostic } from "@codemirror/lint";
import { marked } from "marked";

// Module-scope handles for popup-driven autocompletes.
// Stored outside ichigo.js's reactive `data()` to keep the underlying
// PopupHandle (and its result Promise) un-Proxied — calling .then on a
// Proxy-wrapped Promise throws "incompatible receiver".
let mimeTypePopupHandle: any = null;
let encodingPopupHandle: any = null;
let principalPopupHandle: any = null;

// CodeMirror light / dark themes (matches text-editor)
// Single editor theme — colors come from CSS variables so the editor
// automatically follows the app's light/dark theme switch (no compartment
// reconfigure needed). Tune the variables in style.css to adjust colors.
const cmTheme = EditorView.theme({
	"&": { backgroundColor: "var(--body-bg)", color: "var(--body-color)" },
	".cm-content": { caretColor: "var(--body-color)" },
	".cm-cursor, .cm-dropCursor": { borderLeftColor: "var(--body-color)" },
	".cm-scroller .cm-layer.cm-selectionLayer .cm-selectionBackground, ::selection": { backgroundColor: "var(--item-selected-bg)" },
	".cm-gutters": { backgroundColor: "var(--list-header-bg)", color: "var(--text-soft-color)", border: "none" },
	".cm-activeLineGutter": { backgroundColor: "var(--btn-icon-hover-bg)" },
	".cm-activeLine": { backgroundColor: "var(--btn-icon-hover-bg)" },
});

// Syntax highlight — color values are CSS variables, so the highlight
// follows the app's light/dark theme automatically (variables are defined in
// style.css under :root[data-theme="light|dark"]).
const cmHighlight = HighlightStyle.define([
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

// Simple XML linter using DOMParser
function xmlLinter(view: EditorView): Diagnostic[] {
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

// Simple HTML linter using DOMParser (text/html is lenient, so also try XHTML mode for stricter checks)
function htmlLinter(view: EditorView): Diagnostic[] {
	const diagnostics: Diagnostic[] = [];
	const content = view.state.doc.toString();
	if (!content.trim()) return diagnostics;
	try {
		// Try parsing as XHTML for stricter validation
		const wrapped = `<root xmlns="http://www.w3.org/1999/xhtml">${content}</root>`;
		const parser = new DOMParser();
		const doc = parser.parseFromString(wrapped, 'application/xhtml+xml');
		const errorNode = doc.querySelector('parsererror');
		if (errorNode) {
			diagnostics.push({
				from: 0,
				to: Math.min(content.length, 1),
				severity: 'warning',
				message: (errorNode.textContent || 'HTML parse warning').replace(/xmlns="[^"]*"/g, '').trim(),
			});
		}
	} catch (e: any) {
		diagnostics.push({ from: 0, to: 0, severity: 'warning', message: e.message || 'HTML parse warning' });
	}
	return diagnostics;
}

function getLanguageExtensionForEditorType(editorType: string) {
	switch (editorType) {
		case 'JSON': return [json(), linter(jsonParseLinter())];
		case 'HTML': return [html(), linter(htmlLinter)];
		case 'XML': return [xml(), linter(xmlLinter)];
		case 'Markdown': return [markdown()];
		default: return [];
	}
}

function formatStructuredText(content: string, editorType: string): string {
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
				// Reconstruct <body> with its original attributes
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

// Validate values against a schema-enriched property. Returns error message or ''.
// Execute a user-provided validation script. The script receives a ctx
// object with currentValue/propertyName plus the shared get/getString/etc.
// API. It must return an object of the form:
//   { valid: boolean, errors?: Array<{ messageId, severity, params, ruleId, fallbackMessage }> }
function executeValidationScript(
	script: string,
	propertyName: string,
	currentValue: any,
	currentValues: any[],
	isArray: boolean,
	allPropsRaw: Map<string, { value: any; values: any[] }>,
): { valid: boolean; errors?: any[] } | null {
	try {
		const ctx = {
			propertyName,
			currentValue: isArray ? null : currentValue,
			currentValues: isArray ? currentValues : [currentValue],
			isArray,
			get(propName: string, defaultValue?: any): any {
				const entry = allPropsRaw.get(propName);
				if (!entry || entry.value == null) return defaultValue ?? null;
				return entry.value;
			},
			getString(propName: string, defaultValue?: string): string {
				const entry = allPropsRaw.get(propName);
				if (!entry || entry.value == null) return defaultValue ?? '';
				return String(entry.value);
			},
			getNumber(propName: string, defaultValue?: number): number {
				const entry = allPropsRaw.get(propName);
				if (!entry || entry.value == null) return defaultValue ?? 0;
				const n = Number(entry.value);
				return isNaN(n) ? (defaultValue ?? 0) : n;
			},
			getValues(propName: string, defaultValue?: any[]): any[] {
				const entry = allPropsRaw.get(propName);
				if (!entry || !entry.values) return defaultValue ?? [];
				return entry.values;
			},
		};
		const fn = new Function('ctx', script);
		const result = fn(ctx);
		if (result && typeof result === 'object' && 'valid' in result) {
			return result as { valid: boolean; errors?: any[] };
		}
		return null;
	} catch {
		return null;
	}
}

function validatePropertyValues(
	prop: any,
	values: string[],
	allPropsRaw?: Map<string, { value: any; values: any[] }>,
	i18nFormat?: (err: any) => string,
): string {
	if (!prop || !prop.schemaType) return '';
	const type = prop.schemaType as string;
	if (type === 'BINARY' || type === 'REFERENCE' || type === 'WEAKREFERENCE') return '';
	const nonEmpty = values.filter((v: string) => v != null && String(v).trim() !== '');
	if (prop.schemaRequired && nonEmpty.length === 0) {
		return 'This property is required';
	}
	for (const raw of nonEmpty) {
		const v = String(raw);
		if (type === 'STRING' || type === 'NAME' || type === 'PATH' || type === 'URI') {
			if (prop.schemaMinLength != null && v.length < prop.schemaMinLength) {
				return `Min length is ${prop.schemaMinLength}`;
			}
			if (prop.schemaMaxLength != null && v.length > prop.schemaMaxLength) {
				return `Max length is ${prop.schemaMaxLength}`;
			}
		}
		if (type === 'STRING' && prop.schemaPattern) {
			try {
				const re = new RegExp(prop.schemaPattern);
				if (!re.test(v)) return 'Value does not match pattern';
			} catch {
				// Invalid regex in schema — ignore
			}
		}
		if (type === 'STRING' && prop.editorType === 'Email') {
			if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v)) return 'Invalid email format';
		}
		if (type === 'STRING' && prop.editorType === 'URL') {
			try { new URL(v); } catch { return 'Invalid URL format'; }
		}
		if (type === 'STRING' && prop.editorType === 'Tel') {
			if (!/^\+?[0-9\s\-()]{3,}$/.test(v)) return 'Invalid phone number format';
		}
		if (type === 'NAME') {
			if (!/^([A-Za-z_][A-Za-z0-9_\-.]*:)?[A-Za-z_][A-Za-z0-9_\-.]*$/.test(v)) {
				return 'Invalid NAME format';
			}
		}
		if (type === 'PATH') {
			if (!/^\/([^\/]+(\/[^\/]+)*)?$/.test(v)) return 'Invalid PATH format';
		}
		if (type === 'URI') {
			try { new URL(v, 'http://a'); } catch { return 'Invalid URI format'; }
		}
		if (type === 'LONG' || type === 'DOUBLE' || type === 'DECIMAL') {
			const n = Number(v);
			if (isNaN(n)) return 'Invalid number';
			if (type === 'LONG' && !Number.isInteger(n)) return 'Must be an integer';
			if (prop.schemaMinValue != null && prop.schemaMinValue !== '' && n < Number(prop.schemaMinValue)) {
				return `Min value is ${prop.schemaMinValue}`;
			}
			if (prop.schemaMaxValue != null && prop.schemaMaxValue !== '' && n > Number(prop.schemaMaxValue)) {
				return `Max value is ${prop.schemaMaxValue}`;
			}
		}
		if (type === 'BOOLEAN') {
			if (v !== 'true' && v !== 'false') return 'Must be true or false';
		}
		if (type === 'DATE') {
			if (isNaN(new Date(v).getTime())) return 'Invalid date';
		}
	}
	// Custom validation script (runs only if the schema defines one and
	// the caller passed the dependency lookup map).
	if (prop.schemaValidation && allPropsRaw) {
		const isArray = !!prop.isArray;
		const currentValue = isArray ? '' : (values[0] ?? '');
		const result = executeValidationScript(
			prop.schemaValidation,
			prop.name || '',
			currentValue,
			values,
			isArray,
			allPropsRaw,
		);
		if (result && result.valid === false && result.errors && result.errors.length > 0) {
			// Only escalate errors (severity === 'error' or unset) to block edit.
			const errors = result.errors.filter((e: any) =>
				!e.severity || e.severity === 'error'
			);
			if (errors.length > 0) {
				const first = errors[0];
				if (i18nFormat) return i18nFormat(first);
				return first.fallbackMessage || first.messageId || 'Validation failed';
			}
		}
	}
	return '';
}

function prettyIndentXml(xmlStr: string): string {
	const PADDING = '  ';
	// Tokenize into tags and text nodes without losing content
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
			// Text node — keep only if non-empty when trimmed
			const trimmed = tok.trim();
			if (trimmed) lines.push(PADDING.repeat(pad) + trimmed);
		}
	}
	return lines.join('\n');
}


// Helper to convert GraphQL Node to CmsItem-compatible object
function nodeToContentItem(node: Node): ContentItem {
	return {
		id: node.uuid || node.path,
		name: node.name,
		path: node.path,
		exists: true,
		isCollection: isFolderNode(node),
		downloadURL: node.downloadUrl ? node.downloadUrl + (node.downloadUrl.includes('?') ? '&' : '?') + 'attachment' : null,
		created: node.created ? new Date(node.created) : null,
		createdBy: node.createdBy,
		createdByDisplayName: node.createdByDisplayName ?? null,
		lastModified: node.modified ? new Date(node.modified) : null,
		lastModifiedBy: node.modifiedBy,
		lastModifiedByDisplayName: node.modifiedByDisplayName ?? null,
		contentLength: node.size || 0,
		mimeType: node.mimeType || '',
		encoding: node.encoding || '',
		hasChildren: node.hasChildren || false,
		isLocked: node.isLocked || false,
		lockInfo: node.lockInfo || null,
		isReferenceable: !!node.uuid,
		isVersionable: node.isVersionable || false,
		isCheckedOut: node.isCheckedOut || false,
		baseVersionName: node.baseVersionName || null,
		attributes: {},
	};
}

interface ContentItem {
	id: string;
	name: string;
	path: string;
	exists: boolean;
	isCollection: boolean;
	downloadURL: string | null;
	created: Date | null;
	createdBy: string;
	createdByDisplayName: string | null;
	lastModified: Date | null;
	lastModifiedBy: string;
	lastModifiedByDisplayName: string | null;
	contentLength: number;
	mimeType: string;
	encoding: string;
	hasChildren: boolean;
	isLocked: boolean;
	lockInfo: LockInfo | null;
	isReferenceable: boolean;
	isVersionable: boolean;
	isCheckedOut: boolean;
	baseVersionName: string | null;
	attributes: Record<string, any>;
}

// ---------------------------------------------------------------------------
// Full-text search query parser.
//
// Compiles a user-friendly search expression into an XPath predicate fragment
// built from multiple jcr:contains(., '...') clauses combined with XPath
// boolean operators (and / or / not()). The existing JcrXPathQuery layer
// then translates those into Lucene queries.
//
// Supported syntax:
//   foo bar          -- implicit AND (space-separated)
//   foo AND bar      -- explicit AND
//   foo OR bar       -- OR (lower precedence than AND)
//   foo -bar         -- NOT (exclude bar)
//   foo NOT bar      -- NOT keyword
//   "Web design"     -- phrase (treated as one jcr:contains argument)
//   (foo OR bar) baz -- grouping with parentheses
//
// Grammar (recursive descent):
//   expr    := orExpr
//   orExpr  := andExpr ('OR' andExpr)*
//   andExpr := unary ('AND'? unary)*
//   unary   := ('NOT' | '-') unary | primary
//   primary := '(' expr ')' | PHRASE | WORD
// ---------------------------------------------------------------------------

type FullTextToken =
	| { type: 'LPAREN' }
	| { type: 'RPAREN' }
	| { type: 'AND' }
	| { type: 'OR' }
	| { type: 'NOT' }
	| { type: 'MINUS' }
	| { type: 'WORD'; value: string }
	| { type: 'PHRASE'; value: string };

function tokenizeFullText(s: string): FullTextToken[] {
	const tokens: FullTextToken[] = [];
	const n = s.length;
	let i = 0;
	// True only when the *previous character* (without an intervening space)
	// was the end of an operand — used to distinguish `foo-bar` (hyphen inside
	// a bareword) from `foo -bar` (NOT bar). Whitespace resets it.
	let prevWasOperandOrClose = false;
	while (i < n) {
		const c = s[i];
		if (c === ' ' || c === '\t' || c === '\n' || c === '\r') { i++; prevWasOperandOrClose = false; continue; }
		if (c === '(') { tokens.push({ type: 'LPAREN' }); i++; prevWasOperandOrClose = false; continue; }
		if (c === ')') { tokens.push({ type: 'RPAREN' }); i++; prevWasOperandOrClose = true; continue; }
		if (c === '"') {
			let v = '';
			let j = i + 1;
			while (j < n && s[j] !== '"') {
				if (s[j] === '\\' && j + 1 < n) { v += s[j + 1]; j += 2; }
				else { v += s[j]; j++; }
			}
			if (j >= n) throw new Error('Unterminated phrase: missing closing "');
			tokens.push({ type: 'PHRASE', value: v });
			i = j + 1;
			prevWasOperandOrClose = true;
			continue;
		}
		if (c === '-' && !prevWasOperandOrClose) {
			const next = s[i + 1];
			if (next && next !== ' ' && next !== '\t' && next !== '\n' && next !== '\r'
					&& next !== ')' && next !== '-') {
				tokens.push({ type: 'MINUS' });
				i++;
				continue;
			}
		}
		// Bareword: read until whitespace, (, ), or "
		let j = i;
		let v = '';
		while (j < n) {
			const ch = s[j];
			if (ch === ' ' || ch === '\t' || ch === '\n' || ch === '\r' ||
				ch === '(' || ch === ')' || ch === '"') break;
			v += ch;
			j++;
		}
		const upper = v.toUpperCase();
		if (upper === 'AND') { tokens.push({ type: 'AND' }); prevWasOperandOrClose = false; }
		else if (upper === 'OR') { tokens.push({ type: 'OR' }); prevWasOperandOrClose = false; }
		else if (upper === 'NOT') { tokens.push({ type: 'NOT' }); prevWasOperandOrClose = false; }
		else { tokens.push({ type: 'WORD', value: v }); prevWasOperandOrClose = true; }
		i = j;
	}
	return tokens;
}

// Escape a string for embedding in a single-quoted XPath literal, matching the
// existing convention in QueryExecutor / JcrXPathQuery (single quote -> \').
function escapeXPathLiteral(s: string): string {
	return s.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
}

class FullTextParser {
	private pos = 0;
	constructor(private readonly tokens: FullTextToken[]) {}

	private peek(): FullTextToken | undefined { return this.tokens[this.pos]; }
	private atEnd(): boolean { return this.pos >= this.tokens.length; }
	private consume(): FullTextToken { return this.tokens[this.pos++]; }
	private match(type: FullTextToken['type']): boolean {
		const t = this.peek();
		if (t && t.type === type) { this.consume(); return true; }
		return false;
	}

	parse(): string {
		const result = this.parseOr();
		if (!this.atEnd()) {
			const t = this.peek()!;
			throw new Error('Unexpected token: ' + (('value' in t) ? t.value : t.type));
		}
		return result;
	}

	private parseOr(): string {
		const parts = [this.parseAnd()];
		while (this.match('OR')) parts.push(this.parseAnd());
		if (parts.length === 1) return parts[0];
		return '(' + parts.join(' or ') + ')';
	}

	private parseAnd(): string {
		const parts = [this.parseUnary()];
		while (!this.atEnd()) {
			const t = this.peek()!;
			if (t.type === 'OR' || t.type === 'RPAREN') break;
			this.match('AND'); // optional explicit AND
			parts.push(this.parseUnary());
		}
		if (parts.length === 1) return parts[0];
		return parts.join(' and ');
	}

	private parseUnary(): string {
		if (this.match('NOT') || this.match('MINUS')) {
			return 'not(' + this.parseUnary() + ')';
		}
		return this.parsePrimary();
	}

	private parsePrimary(): string {
		if (this.atEnd()) throw new Error('Unexpected end of expression');
		const t = this.consume();
		if (t.type === 'LPAREN') {
			const inner = this.parseOr();
			if (!this.match('RPAREN')) throw new Error('Expected )');
			// inner is already a complete OR-expression; wrap to be safe when ANDed.
			return inner.startsWith('(') ? inner : '(' + inner + ')';
		}
		if (t.type === 'WORD' || t.type === 'PHRASE') {
			if (!t.value) throw new Error('Empty term');
			return "jcr:contains(., '" + escapeXPathLiteral(t.value) + "')";
		}
		throw new Error('Unexpected token: ' + t.type);
	}
}

/**
 * Parse a user-supplied full-text search expression into an XPath predicate
 * fragment, or return an error message.
 *
 * @returns predicate (without surrounding []), or null when input is empty.
 */
function compileFullTextSearch(input: string): { predicate: string | null; error: string | null } {
	const trimmed = input.trim();
	if (!trimmed) return { predicate: null, error: null };
	try {
		const tokens = tokenizeFullText(trimmed);
		if (tokens.length === 0) return { predicate: null, error: null };
		const parser = new FullTextParser(tokens);
		const predicate = parser.parse();
		return { predicate, error: null };
	} catch (ex: any) {
		return { predicate: null, error: ex?.message || String(ex) };
	}
}

export const App = {
	data() {
		return {
			instance: null as ApplicationInstance | null,
			idp: null as IdpServiceGraphQL | null,
			currentPath: '/content',
			items: [] as any[],
			loadingMonitor: null,
			sortColumn: 'name' as string,
			sortDirection: 'asc' as 'asc' | 'desc',
			uploadMonitor: null as any,
			messageListener: null,
			errorMessage: '',
			conflictDialog: {
				visible: false,
				resolve: null as null | ((action: string) => void),
			},
			renameDialog: {
				visible: false,
				item: null as ContentItem | null,
				newName: '',
				isLoading: false,
				errorMessage: '',
			},
			deleteDialog: {
				visible: false,
				items: [] as ContentItem[],
				isLoading: false,
				errorMessage: '',
			},
			deleteMonitor: null as null | {
				jobId: string;
				handle: DeleteJobHandle;
				itemsTotal: number;
				itemsProcessed: number;
				nodesDeleted: number;
				currentPath: string;
				status: JobStatus;
				errorMessage: string;
				isFinished: boolean;
				isAborting: boolean;
				targetPath: string;
			},
			newFolderDialog: {
				visible: false,
				name: '',
				isLoading: false,
				errorMessage: '',
			},
			newFileDialog: {
				visible: false,
				name: '',
				fileType: 'text',
				isLoading: false,
				errorMessage: '',
			},
			versionHistoryDialog: {
				visible: false,
				item: null as ContentItem | null,
				versions: [] as { name: string; created: string; predecessors: string[]; successors: string[] }[],
				baseVersionName: '',
				isLoading: false,
				errorMessage: '',
			},
			propTypeOptions: [
				{ id: 'STRING', label: 'String' },
				{ id: 'LONG', label: 'Long' },
				{ id: 'DOUBLE', label: 'Double' },
				{ id: 'DECIMAL', label: 'Decimal' },
				{ id: 'BOOLEAN', label: 'Boolean' },
				{ id: 'DATE', label: 'Date' },
				{ id: 'NAME', label: 'Name' },
				{ id: 'PATH', label: 'Path' },
				{ id: 'URI', label: 'URI' },
				{ id: 'BINARY', label: 'Binary' },
				{ id: 'REFERENCE', label: 'Reference' },
				{ id: 'WEAKREFERENCE', label: 'WeakReference' },
			],
			fileTypeOptions: [
				{ id: 'text', label: 'Text Document', extension: '.txt', mimeType: 'text/plain' },
				{ id: 'html', label: 'HTML Document', extension: '.html', mimeType: 'text/html' },
				{ id: 'css', label: 'CSS Stylesheet', extension: '.css', mimeType: 'text/css' },
				{ id: 'javascript', label: 'JavaScript', extension: '.js', mimeType: 'application/javascript' },
				{ id: 'json', label: 'JSON', extension: '.json', mimeType: 'application/json' },
				{ id: 'xml', label: 'XML Document', extension: '.xml', mimeType: 'application/xml' },
				{ id: 'markdown', label: 'Markdown', extension: '.md', mimeType: 'text/markdown' },
				{ id: 'csv', label: 'CSV', extension: '.csv', mimeType: 'text/csv' },
				{ id: 'yml', label: 'YAML Document', extension: '.yml', mimeType: 'application/yaml' },
				{ id: 'bpmn', label: 'BPMN Document', extension: '.bpmn', mimeType: 'application/bpmn+xml' },
				{ id: 'eip.yml', label: 'EIP/Route Document', extension: '.eip.yml', mimeType: 'application/x-camel-route' },
			],
			// Selection state
			selectedItems: [] as string[],
			lastSelectedIndex: -1,
			dragSelection: {
				active: false,
				startX: 0,
				startY: 0,
				currentX: 0,
				currentY: 0,
			},
			boundMouseMove: null as ((e: MouseEvent) => void) | null,
			boundMouseUp: null as ((e: MouseEvent) => void) | null,
			keydownListener: null as ((e: KeyboardEvent) => void) | null,
			// Checkout dialog for versioned file uploads
			checkoutDialog: {
				visible: false,
				fileName: '',
				baseVersionName: '',
				resolve: null as null | ((action: string) => void),
			},
			// Track skip-all for checkout during upload
			skipAllCheckout: false,
			// ACL dialog
			aclDialog: {
				visible: false,
				item: null as any,
				effectivePolicies: [] as { path: string; entries: { principal: any; privileges: string[]; allow: boolean }[] }[],
				isLoading: false,
				isSaving: false,
				errorMessage: '',
				// Pending entries for the current item (working copy)
				pendingEntries: [] as { principal: any; privileges: string[]; allow: boolean }[],
				// Original entries loaded from server (for change detection)
				originalEntries: [] as { principal: any; privileges: string[]; allow: boolean }[],
				addEntry: {
					visible: false,
					allow: true,
					principal: '',
					principalIsGroup: false,
					principalDisplayName: '' as string,
					privileges: [] as string[],
					searchKeyword: '',
					searchResults: [] as { identifier: string; isGroup: boolean; isService?: boolean; displayName?: string | null }[],
					isSearching: false,
					errorMessage: '',
				},
			},
			aclSearchDebounceTimer: null as ReturnType<typeof setTimeout> | null,
			// Navigation bar state
			navEditMode: false,
			editPathValue: '',
			ellipsisDropdownOpen: false,
			// Path history and pinned locations (persisted to IndexedDB)
			pathHistory: [] as { path: string; timestamp: number }[],
			pinnedPaths: [] as { path: string; name: string }[],
			MAX_HISTORY_ITEMS: 50,
			navClickOutsideListener: null as ((e: MouseEvent) => void) | null,
			// Back/forward navigation stack (session-only)
			navStack: [] as string[],
			navStackIndex: -1,
			backDropdownOpen: false,
			// Full history overlay panel
			fullHistoryPanelOpen: false,
			historySearchKeyword: '',
			// Sidebar panel (left pane: filter, favorites, smart folders, XPath search)
			sidebarPanelVisible: false,
			sidebarPanelWidth: 260,
			sidebarPanelMinWidth: 200,
			sidebarPanelMaxWidth: 400,
			sidebarPanelResizing: false,
			sidebarResizeStartX: 0,
			sidebarResizeStartWidth: 0,
			_boundSidebarResizeMove: null as ((e: MouseEvent) => void) | null,
			_boundSidebarResizeUp: null as (() => void) | null,
			// Client-side filter state
			filterText: '',
			filterDateMode: 'none' as 'none' | 'today' | 'pastN' | 'range',
			filterDatePastN: 7,
			filterDateFrom: '' as string,
			filterDateTo: '' as string,
			filterDateFromInclusive: true,
			filterDateToInclusive: true,
			// Custom dropdowns (wt-select) open state
			// XPath search state
			xpathSelectedSchema: '' as string,
			xpathConditions: [] as {
				id: number;
				propertyKey: string;
				type: string;
				// String
				stringValue: string;
				// Number (Long/Double)
				numberFrom: string;
				numberTo: string;
				numberFromInclusive: boolean;
				numberToInclusive: boolean;
				// Boolean
				booleanValue: '' | 'true' | 'false';
				// Date
				dateMode: 'none' | 'today' | 'pastN' | 'range';
				datePastN: number;
				dateFrom: string;
				dateTo: string;
				dateFromInclusive: boolean;
				dateToInclusive: boolean;
				// Choices (OR selection)
				selectedChoices: string[];
			}[],
			xpathNextConditionID: 1,
			xpathSearchActive: false,
			xpathSearchLoading: false,
			xpathSearchError: '' as string,
			xpathSearchTotalCount: 0,
			// Full-text search keyword (combined with schema conditions in the Search section)
			fullTextKeyword: '' as string,
			// Smart folders (saved XPath queries)
			smartFolders: [] as {
				id: number;
				name: string;
				path: string;
				schemaKey: string;
				conditions: any[];
				fullTextKeyword?: string;
			}[],
			smartFolderNextID: 1,
			smartFolderEditing: null as number | null,
			smartFolderEditName: '' as string,
			_suppressServerSync: false,
			// Sidebar collapsible sections (true = expanded)
			sidebarSectionExpanded: {
				filter: true,
				favorites: false,
				smartFolders: false,
				xpathSearch: false,
			} as Record<string, boolean>,
			// Detail preview panel
			detailPanelVisible: false,
			detailPanelWidth: 280,
			detailPanelMinWidth: 200,
			detailPanelMaxWidth: 500,
			detailPanelResizing: false,
			detailResizeStartX: 0,
			detailResizeStartWidth: 0,
			_boundResizeMove: null as ((e: MouseEvent) => void) | null,
			_boundResizeUp: null as (() => void) | null,
			previewImageError: false,
			// Detail panel: properties section
			detailProperties: [] as { name: string; value: string; items: string[]; type: string; isArray: boolean }[],
			detailPropertiesLoading: false,
			detailPropertiesError: '',
			// Detail panel: schema support for properties
			availableSchemas: [] as { key: string; label: string; properties: { key: string; label: string; displayFormat?: string; choices?: { value: string; label: string }[]; readOnly?: boolean; multiple?: boolean; editorType?: string; rows?: number; query?: { xpath: string; labelKey: string } }[] }[],
			selectedSchemaKey: '' as string,
			detailPropertiesFilter: '' as string,
			propEditorFilter: '' as string,
			propEditorErrorFilter: '' as '' | 'mismatch' | 'validation',
			// Bumped when i18n bundles are reloaded, so validation computeds re-run
			_i18nTick: 0,
			// CodeMirror structured-text editor state (JSON/XML/HTML/Markdown)
			cmEditor: null as EditorView | null,
			cmLanguageCompartment: null as Compartment | null,
			cmThemeCompartment: null as Compartment | null,
			cmEditorType: '' as string,
			cmExpanded: false,
			cmPreview: false,
			cmPreviewHtml: '' as string,
			cmEscHandler: null as ((e: KeyboardEvent) => void) | null,
			// Custom search/replace UI (expanded mode only)
			cmSearchVisible: false,
			cmSearchTerm: '' as string,
			cmReplaceTerm: '' as string,
			cmSearchCaseSensitive: false,
			cmSearchRegex: false,
			cmSearchWholeWord: false,
			cmSearchNotFound: false,
			// Detail panel: ACL summary section
			detailACL: [] as { path: string; entries: { principal: string; privileges: string[]; allow: boolean }[] }[],
			detailACLLoading: false,
			detailACLError: '',
			// Detail panel: overlays
			detailVersionHistoryVisible: false,
			detailACLEditorVisible: false,
			// Property editor overlay state
			detailPropertyEditorVisible: false,
			propEditorSchemaKey: '' as string,
			propEditorItems: [] as any[],
			propEditorLoading: false,
			propEditorError: '',
			propEditorEditingName: null as string | null,
			propEditorEditType: 'STRING',
			propEditorEditIsArray: false,
			propEditorEditIsArrayLocked: false,
			propEditorEditInput: '',
			propEditorEditValues: [] as string[],
			propEditorEditNewChip: '',
			propEditorAddingNew: false,
			propEditorNewName: '',
			propEditorNewType: 'STRING',
			propEditorNewIsArray: false,
			propEditorNewValue: '',
			propEditorNewValues: [] as string[],
			propEditorNewChip: '',
			propEditorSaving: false,
			propEditorSaveError: '',
			// Query-based reference selection
			propEditorQueryItems: [] as { value: string; label: string }[],
			propEditorQueryLoading: false,
			propEditorQueryConfig: null as { xpath: string; labelKey: string } | null,
			propEditorQueryCache: {} as Record<string, { value: string; label: string }[]>,
			// Reference property edit: display paths alongside UUID values
			propEditorEditDisplayValue: '' as string,
			propEditorEditDisplayValues: [] as string[],
			// Reference property drop zone tracking
			refDragOverProp: null as string | null,
			// Binary property inline editing state
			binaryEditIsUploading: false,
			binaryEditProgress: 0,
			binaryEditFileName: '',
			binaryEditFileSizeFormatted: '',
			binaryEditFileNames: [] as string[],
			binaryEditFileSizesFormatted: [] as string[],
			binaryEditServerItems: [] as { mimeType: string; sizeFormatted: string; downloadURL: string }[],
			binaryEditPreviewURL: '',
			binaryEditPreviewURLs: [] as string[],
			binaryEditFileMimeTypes: [] as string[],
			binaryEditDragOver: false,
			binaryEditArrayDragOver: null as number | null,
			// MIME type inline editor state
			mimeTypeEditing: false,
			mimeTypeInput: '',
			mimeTypeSuggestions: [] as { mimeType: string; description: string }[],
			mimeTypeSaving: false,
			mimeTypeHighlightIndex: -1,
			// Encoding inline editor state
			encodingEditing: false,
			encodingInput: '',
			encodingSuggestions: [] as { name: string; description: string }[],
			encodingSaving: false,
			encodingHighlightIndex: -1,
			// Clipboard state for copy/cut/paste
			clipboard: {
				mode: null as 'copy' | 'cut' | null,
				items: [] as { path: string; name: string; isCollection: boolean }[],
			},
			// Drag-and-drop folder target tracking
			dragOverFolderID: null as string | null,
			// Node watch subscription for real-time updates
			_nodeWatchUnsubscribe: null as (() => void) | null,
			_parentWatchUnsubscribe: null as (() => void) | null,
			_pendingNodeEvents: [] as { eventType: string; path: string; sourcePath?: string }[],
			flashingItems: [] as string[],
			// Set when the currently displayed folder has been deleted out from
			// under us (by another user/process). The list area shows a notice
			// and the user can navigate elsewhere to continue working.
			currentFolderDeleted: false,
		};
	},
	computed: {
		filteredItems(): ContentItem[] {
			let result = this.items as ContentItem[];
			// Text filter (name partial match, case-insensitive)
			const text = this.filterText.trim().toLowerCase();
			if (text) {
				result = result.filter(item => item.name.toLowerCase().includes(text));
			}
			// Date filter
			if (this.filterDateMode !== 'none') {
				const now = new Date();
				if (this.filterDateMode === 'today') {
					const start = new Date(now.getFullYear(), now.getMonth(), now.getDate());
					result = result.filter(item => item.lastModified && item.lastModified >= start);
				} else if (this.filterDateMode === 'pastN') {
					const start = new Date(now);
					start.setDate(start.getDate() - this.filterDatePastN);
					start.setHours(0, 0, 0, 0);
					result = result.filter(item => item.lastModified && item.lastModified >= start);
				} else if (this.filterDateMode === 'range') {
					if (this.filterDateFrom) {
						const from = new Date(this.filterDateFrom);
						result = result.filter(item => {
							if (!item.lastModified) return false;
							return this.filterDateFromInclusive
								? item.lastModified >= from
								: item.lastModified > from;
						});
					}
					if (this.filterDateTo) {
						const to = new Date(this.filterDateTo);
						result = result.filter(item => {
							if (!item.lastModified) return false;
							return this.filterDateToInclusive
								? item.lastModified <= to
								: item.lastModified < to;
						});
					}
				}
			}
			return result;
		},
		filterActive(): boolean {
			return this.filterText.trim() !== '' || this.filterDateMode !== 'none';
		},
		// XPath search computed properties
		xpathSchemaProperties(): { key: string; label: string; type: string; choices?: { value: string; label: string }[]; multiple?: boolean }[] {
			if (!this.xpathSelectedSchema) return [];
			const schema = (this.availableSchemas as any[]).find(s => s.key === this.xpathSelectedSchema);
			if (!schema) return [];
			return schema.properties.map((p: any) => ({
				key: p.key,
				label: p.label || p.key,
				type: p.type || 'STRING',
				choices: p.choices,
				multiple: p.multiple,
			}));
		},
		xpathAvailableProperties(): { key: string; label: string; type: string; choices?: { value: string; label: string }[]; multiple?: boolean }[] {
			const usedKeys = (this.xpathConditions as any[]).map(c => c.propertyKey);
			return (this.xpathSchemaProperties as any[]).filter(p => !usedKeys.includes(p.key));
		},
		// Result of compiling fullTextKeyword: predicate fragment (or null) and parse error.
		fullTextCompiled(): { predicate: string | null; error: string | null } {
			return compileFullTextSearch(this.fullTextKeyword as string);
		},
		// True when the Search section has anything to search by (full-text or schema condition).
		hasSearchInput(): boolean {
			const ft = (this.fullTextKeyword as string).trim();
			return ft.length > 0 || (this.xpathConditions as any[]).length > 0;
		},
		xpathBuiltQuery(): string {
			const path = this.currentPath === '/' ? '//' : `/jcr:root${this.currentPath}//`;
			const conditions: string[] = [];
			const ft = (this.fullTextCompiled as any) as { predicate: string | null; error: string | null };
			if (ft.predicate) {
				conditions.push(ft.predicate);
			}
			for (const cond of this.xpathConditions as any[]) {
				if (!cond.propertyKey) continue;
				const prop = (this.xpathSchemaProperties as any[]).find(p => p.key === cond.propertyKey);
				if (!prop) continue;

				// Choices (OR)
				if (prop.choices && prop.choices.length > 0 && cond.selectedChoices.length > 0) {
					const parts = cond.selectedChoices.map((v: string) => `@${cond.propertyKey}='${v}'`);
					conditions.push(parts.length === 1 ? parts[0] : `(${parts.join(' or ')})`);
					continue;
				}

				const baseType = prop.type.toUpperCase();
				if (baseType === 'STRING' || baseType === 'NAME' || baseType === 'PATH' || baseType === 'URI') {
					if (cond.stringValue.trim()) {
						conditions.push(`jcr:contains(@${cond.propertyKey}, '${cond.stringValue.trim()}')`);
					}
				} else if (baseType === 'LONG' || baseType === 'DOUBLE' || baseType === 'DECIMAL') {
					if (cond.numberFrom !== '') {
						const op = cond.numberFromInclusive ? '>=' : '>';
						conditions.push(`@${cond.propertyKey} ${op} ${cond.numberFrom}`);
					}
					if (cond.numberTo !== '') {
						const op = cond.numberToInclusive ? '<=' : '<';
						conditions.push(`@${cond.propertyKey} ${op} ${cond.numberTo}`);
					}
				} else if (baseType === 'BOOLEAN') {
					if (cond.booleanValue === 'true' || cond.booleanValue === 'false') {
						conditions.push(`@${cond.propertyKey}='${cond.booleanValue}'`);
					}
				} else if (baseType === 'DATE') {
					if (cond.dateMode === 'today') {
						const today = new Date();
						const iso = today.toISOString().slice(0, 10);
						conditions.push(`@${cond.propertyKey} >= xs:dateTime('${iso}T00:00:00.000Z')`);
					} else if (cond.dateMode === 'pastN') {
						const d = new Date();
						d.setDate(d.getDate() - cond.datePastN);
						const iso = d.toISOString().slice(0, 10);
						conditions.push(`@${cond.propertyKey} >= xs:dateTime('${iso}T00:00:00.000Z')`);
					} else if (cond.dateMode === 'range') {
						if (cond.dateFrom) {
							const op = cond.dateFromInclusive ? '>=' : '>';
							const dt = new Date(cond.dateFrom);
							conditions.push(`@${cond.propertyKey} ${op} xs:dateTime('${dt.toISOString()}')`);
						}
						if (cond.dateTo) {
							const op = cond.dateToInclusive ? '<=' : '<';
							const dt = new Date(cond.dateTo);
							conditions.push(`@${cond.propertyKey} ${op} xs:dateTime('${dt.toISOString()}')`);
						}
					}
				}
			}
			const predicate = conditions.length > 0 ? `[${conditions.join(' and ')}]` : '';
			return `${path}element(*, nt:file)${predicate}`;
		},
		breadcrumb(): { name: string; path: string }[] {
			const parts = this.currentPath.split('/').filter(p => p);
			const result = [] as { name: string; path: string }[];
			let prefix = '';
			result.push({ name: '', path: '/' });
			for (const p of parts) {
				prefix += '/' + p;
				result.push({ name: p, path: prefix });
			}
			if (result.length === 0) {
				result.push({ name: '', path: '/' });
			}
			return result;
		},
		// Smart breadcrumb with ellipsis collapsing for deep paths
		smartBreadcrumb(): {
			home: { name: string; path: string } | null;
			collapsed: { name: string; path: string }[];
			visible: { name: string; path: string }[];
			current: { name: string; path: string } | null;
		} {
			const all = this.breadcrumb;
			// Root: single entry. Show it as `current` (no separate home).
			if (all.length === 1) {
				return {
					home: null,
					collapsed: [],
					visible: [],
					current: all[0],
				};
			}
			if (all.length <= 4) {
				return {
					home: all[0],
					collapsed: [],
					visible: all.slice(1, -1),
					current: all[all.length - 1],
				};
			}
			return {
				home: all[0],
				collapsed: all.slice(1, -2),
				visible: [all[all.length - 2]],
				current: all[all.length - 1],
			};
		},
		// Back/forward navigation
		canGoBack(): boolean {
			return this.navStackIndex > 0;
		},
		canGoForward(): boolean {
			return this.navStackIndex < this.navStack.length - 1;
		},
		// Back dropdown shows recent entries from navStack (behind current position)
		backStackEntries(): { path: string; index: number }[] {
			const entries = [] as { path: string; index: number }[];
			for (let i = this.navStackIndex - 1; i >= 0 && entries.length < 10; i--) {
				entries.push({ path: this.navStack[i], index: i });
			}
			return entries;
		},
		// Group pathHistory by time category
		groupedHistory(): { label: string; items: { path: string; timestamp: number }[] }[] {
			const now = new Date();
			const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
			const yesterdayStart = todayStart - 86400000;
			const weekStart = todayStart - 6 * 86400000;

			const groups: Record<string, { path: string; timestamp: number }[]> = {
				'Today': [],
				'Yesterday': [],
				'This Week': [],
				'Older': [],
			};

			for (const entry of this.pathHistory) {
				if (entry.timestamp >= todayStart) {
					groups['Today'].push(entry);
				} else if (entry.timestamp >= yesterdayStart) {
					groups['Yesterday'].push(entry);
				} else if (entry.timestamp >= weekStart) {
					groups['This Week'].push(entry);
				} else {
					groups['Older'].push(entry);
				}
			}

			return Object.entries(groups)
				.filter(([, items]) => items.length > 0)
				.map(([label, items]) => ({ label, items }));
		},
		// Filtered history for overlay panel search
		filteredGroupedHistory(): { label: string; items: { path: string; timestamp: number }[] }[] {
			const keyword = this.historySearchKeyword.trim().toLowerCase();
			if (!keyword) return this.groupedHistory;
			return this.groupedHistory
				.map((group: any) => ({
					label: group.label,
					items: group.items.filter((item: any) => item.path.toLowerCase().includes(keyword)),
				}))
				.filter((group: any) => group.items.length > 0);
		},
		selectionRect() {
			return {
				left: Math.min(this.dragSelection.startX, this.dragSelection.currentX),
				top: Math.min(this.dragSelection.startY, this.dragSelection.currentY),
				right: Math.max(this.dragSelection.startX, this.dragSelection.currentX),
				bottom: Math.max(this.dragSelection.startY, this.dragSelection.currentY),
				width: Math.abs(this.dragSelection.currentX - this.dragSelection.startX),
				height: Math.abs(this.dragSelection.currentY - this.dragSelection.startY),
			};
		},
		// Check if folder name already exists
		newFolderNameExists(): boolean {
			const name = this.newFolderDialog.name.trim();
			if (!name) return false;
			return this.items.some((item: any) => item.name.toLowerCase() === name.toLowerCase());
		},
		// Check if file name already exists (including auto-added extension)
		newFileNameExists(): boolean {
			const name = this.newFileDialog.name.trim();
			if (!name) return false;
			const fileType = this.fileTypeOptions.find((opt: any) => opt.id === this.newFileDialog.fileType) || this.fileTypeOptions[0];
			let fullName = name;
			if (!name.toLowerCase().endsWith(fileType.extension.toLowerCase())) {
				fullName = name + fileType.extension;
			}
			return this.items.some((item: any) => item.name.toLowerCase() === fullName.toLowerCase());
		},
		// Detail panel: currently selected single item
		selectedItem(): any {
			if (this.selectedItems.length !== 1) return null;
			return this.items.find((i: any) => i.id === this.selectedItems[0]) || null;
		},
		// Detail panel: check if selected item is a previewable image
		isSelectedItemImage(): boolean {
			const item = this.selectedItem;
			if (!item || item.isCollection || this.previewImageError) return false;
			const mimeType = item.mimeType || '';
			return mimeType.startsWith('image/');
		},
		// Detail panel: preview URL without attachment parameter
		selectedItemPreviewURL(): string {
			const item = this.selectedItem;
			if (!item || !item.downloadURL) return '';
			return item.downloadURL.replace(/[?&]attachment$/, '');
		},
		// Detail panel: count of selected folders
		selectedFolderCount(): number {
			return this.items.filter((i: any) => this.selectedItems.includes(i.id) && i.isCollection).length;
		},
		// Detail panel: count of selected files
		selectedFileCount(): number {
			return this.items.filter((i: any) => this.selectedItems.includes(i.id) && !i.isCollection).length;
		},
		// Detail panel: total size of selected files
		selectedTotalSize(): number {
			return this.items
				.filter((i: any) => this.selectedItems.includes(i.id) && !i.isCollection)
				.reduce((sum: number, i: any) => sum + (i.contentLength || 0), 0);
		},
		// Detail panel: formatted total size
		displayTotalSize(): string {
			if (this.selectedTotalSize === 0) return '';
			return this.instance?.util?.bytes?.format(this.selectedTotalSize, { short: true }) || '';
		},
		// Property editor: count of modified/new/deleted properties
		propEditorModifiedCount(): number {
			return (this.propEditorItems as any[]).filter((p: any) => p.isModified || p.isDeleted || p.isNew).length;
		},
		// Property editor: items reordered by schema (schema items first, then extras)
		propEditorDisplayItems(): any[] {
			if (!this.propEditorSchemaKey) return this.propEditorItems;
			const schema = this.availableSchemas.find((s: any) => s.key === this.propEditorSchemaKey);
			if (!schema) return this.propEditorItems;

			const propMap = new Map<string, any>();
			for (const p of this.propEditorItems as any[]) {
				propMap.set(p.name, p);
			}

			// Build property lookup for displayFormat ctx
			const allPropsRaw = new Map<string, { value: any; values: any[] }>();
			for (const p of this.propEditorItems as any[]) {
				const val = p.isArray ? (p.currentValues.length > 0 ? p.currentValues[0] : null) : (p.currentValue || null);
				const vals = p.isArray ? [...p.currentValues] : [p.currentValue];
				allPropsRaw.set(p.name, { value: val, values: vals });
			}

			// Build schema property lookup
			const schemaPropsMap = new Map<string, any>();
			for (const sp of schema.properties) {
				schemaPropsMap.set(sp.key, sp);
			}

			const enrichItem = (item: any, schemaProp: any): any => {
				let schemaLabel: string | null = schemaProp.label || schemaProp.key;
				let formattedValue: string | null = null;
				let formatError: string | null = null;
				let choiceLabel: string | null = null;
				let choiceLabels: string[] | null = null;

				// Display format (highest priority)
				// Adapt propEditor item to the shape executeDisplayFormat expects
				if (schemaProp.displayFormat) {
					const compatProp = {
						isArray: item.isArray,
						items: item.currentValues || [],
						value: item.currentValue || '',
					};
					try {
						formattedValue = this.executeDisplayFormat(
							schemaProp.displayFormat, compatProp, allPropsRaw
						);
					} catch (e: any) {
						formatError = e.message || String(e);
					}
				}

				// Choice labels (used when no displayFormat)
				if (!formattedValue && !formatError && schemaProp.choices && schemaProp.choices.length > 0) {
					const choiceMap = new Map<string, string>();
					for (const c of schemaProp.choices) {
						choiceMap.set(c.value, c.label || c.value);
					}
					if (item.isArray) {
						choiceLabels = (item.currentValues as string[]).map(
							(v: string) => choiceMap.get(v) ?? v
						);
					} else {
						choiceLabel = choiceMap.get(item.currentValue) ?? null;
					}
				}

				const readOnly = schemaProp.readOnly ?? false;
				const schemaMultiple = schemaProp.multiple;
				const editorType = schemaProp.editorType;
				const editorRows = schemaProp.rows;
				const queryConfig = schemaProp.query;

				// Resolve display values from cached query results
				let enrichedItem = { ...item };
				if (queryConfig && (item.type === 'REFERENCE' || item.type === 'WEAKREFERENCE')) {
					const cached = this.propEditorQueryCache[queryConfig.xpath];
					if (cached) {
						const labelMap = new Map<string, string>();
						for (const qi of cached) {
							labelMap.set(qi.value, qi.label);
						}
						if (item.isArray) {
							enrichedItem.displayValues = (item.currentValues as string[]).map(
								(v: string) => labelMap.get(v) ?? item.displayValues?.[item.currentValues.indexOf(v)] ?? v
							);
						} else {
							enrichedItem.displayValue = labelMap.get(item.currentValue) ?? item.displayValue ?? item.currentValue;
						}
					}
				}

				const schemaChoices = schemaProp.choices;
				const schemaType = schemaProp.type;
				const schemaRequired = schemaProp.required ?? false;
				const schemaMinLength = schemaProp.minLength;
				const schemaMaxLength = schemaProp.maxLength;
				const schemaPattern = schemaProp.pattern;
				const schemaMinValue = schemaProp.minValue;
				const schemaMaxValue = schemaProp.maxValue;
				const schemaValidation = schemaProp.validation;
				const enrichedWithMeta: any = {
					...enrichedItem,
					schemaLabel, formattedValue, formatError, choiceLabel, choiceLabels,
					readOnly, schemaMultiple, editorType, editorRows, queryConfig, schemaChoices,
					schemaType, schemaRequired, schemaMinLength, schemaMaxLength, schemaPattern,
					schemaMinValue, schemaMaxValue, schemaValidation,
				};
				// Type mismatch detection (skip BINARY/REFERENCE/WEAKREFERENCE — those
				// aren't user-editable type conversions anyway)
				if (schemaType && item.type && item.type !== schemaType
					&& schemaType !== 'BINARY' && schemaType !== 'REFERENCE' && schemaType !== 'WEAKREFERENCE'
					&& item.type !== 'BINARY' && item.type !== 'REFERENCE' && item.type !== 'WEAKREFERENCE') {
					enrichedWithMeta.typeMismatch = true;
					enrichedWithMeta.mismatchMessage = `Type mismatch: stored as ${item.type}, schema expects ${schemaType}. Editing and saving will convert it to ${schemaType}.`;
				}
				// Validation error on the currently stored values (not the edit draft).
				// Depends on `_i18nTick` so the computed re-runs when i18n bundles change.
				void (this as any)._i18nTick;
				const currentValues = item.isArray ? (item.currentValues || []) : [item.currentValue ?? ''];
				const i18nFormat = (err: any): string => {
					const i18n = (window.parent as any)?.Webtop?.i18n;
					if (i18n && typeof i18n.formatValidationError === 'function') {
						return i18n.formatValidationError(err);
					}
					return err.fallbackMessage || err.messageId || 'Validation failed';
				};
				const validationError = validatePropertyValues(enrichedWithMeta, currentValues, allPropsRaw, i18nFormat);
				if (validationError) {
					enrichedWithMeta.validationError = validationError;
				}
				return enrichedWithMeta;
			};

			const ordered: any[] = [];
			const matched = new Set<string>();
			for (const schemaProp of schema.properties) {
				const item = propMap.get(schemaProp.key);
				if (item) {
					ordered.push(enrichItem(item, schemaProp));
					matched.add(schemaProp.key);
				}
			}
			for (const p of this.propEditorItems as any[]) {
				if (!matched.has(p.name)) {
					ordered.push(p);
				}
			}
			return ordered;
		},
		localTZ(): string {
			return new Intl.DateTimeFormat('en', { timeZoneName: 'short' })
				.formatToParts(new Date()).find(p => p.type === 'timeZoneName')?.value || '';
		},
		// Inherited ACL policies (excludes the current item's own policy)
		aclInheritedPolicies(): any[] {
			const item = this.aclDialog.item;
			const policies = this.aclDialog.effectivePolicies;
			if (!item || !policies || policies.length === 0) return [];
			if (policies[0].path === item.path) {
				return policies.slice(1);
			}
			return policies;
		},
		// Whether pending ACL entries differ from original
		aclHasChanges(): boolean {
			const pending = this.aclDialog.pendingEntries;
			const original = this.aclDialog.originalEntries;
			if (pending.length !== original.length) return true;
			for (let i = 0; i < pending.length; i++) {
				const p = pending[i];
				const o = original[i];
				if (!o) return true;
				const pID = typeof p.principal === 'object' ? p.principal.id : p.principal;
				const oID = typeof o.principal === 'object' ? o.principal.id : o.principal;
				if (pID !== oID || p.allow !== o.allow) return true;
				if (p.privileges.length !== o.privileges.length) return true;
				const sortedP = [...p.privileges].sort();
				const sortedO = [...o.privileges].sort();
				if (sortedP.some((v, idx) => v !== sortedO[idx])) return true;
			}
			return false;
		},
		// Properties reordered by selected schema
		schemaDisplayProperties(): { schemaProps: any[]; extraProps: any[] } {
			if (!this.selectedSchemaKey || this.detailProperties.length === 0) {
				return { schemaProps: [], extraProps: [] };
			}
			const schema = this.availableSchemas.find((s: any) => s.key === this.selectedSchemaKey);
			if (!schema) {
				return { schemaProps: [], extraProps: [] };
			}

			const propMap = new Map<string, any>();
			for (const p of this.detailProperties) {
				propMap.set(p.name, p);
			}

			// Build property lookup for ctx API
			const allPropsRaw = new Map<string, { value: any; values: any[] }>();
			for (const p of this.detailProperties) {
				const val = p.isArray ? (p.items.length > 0 ? p.items[0] : null) : (p.value || null);
				const vals = p.isArray ? [...p.items] : [p.value];
				allPropsRaw.set(p.name, { value: val, values: vals });
			}

			const schemaProps: any[] = [];
			const matched = new Set<string>();

			for (const schemaProp of schema.properties) {
				const detailProp = propMap.get(schemaProp.key);
				if (!detailProp) continue;

				matched.add(schemaProp.key);
				let formattedValue: string | null = null;
				let formatError: string | null = null;

				if (schemaProp.displayFormat) {
					try {
						formattedValue = this.executeDisplayFormat(
							schemaProp.displayFormat, detailProp, allPropsRaw
						);
					} catch (e: any) {
						formatError = e.message || String(e);
					}
				}

				// Resolve choice labels if choices are defined
				let choiceLabel: string | null = null;
				let choiceLabels: string[] | null = null;
				if (schemaProp.choices && schemaProp.choices.length > 0) {
					const choiceMap = new Map<string, string>();
					for (const c of schemaProp.choices) {
						choiceMap.set(c.value, c.label || c.value);
					}
					if (detailProp.isArray) {
						choiceLabels = (detailProp.items as string[]).map(
							(v: string) => choiceMap.get(v) ?? v
						);
					} else {
						choiceLabel = choiceMap.get(detailProp.value) ?? null;
					}
				}

				// Resolve REFERENCE/WEAKREFERENCE display values from query cache
				let resolvedProp = detailProp;
				if (schemaProp.query && (detailProp.type === 'REFERENCE' || detailProp.type === 'WEAKREFERENCE')) {
					const cached = this.propEditorQueryCache[schemaProp.query.xpath];
					if (cached) {
						const labelMap = new Map<string, string>();
						for (const qi of cached) {
							labelMap.set(qi.value, qi.label);
						}
						resolvedProp = { ...detailProp };
						if (detailProp.isArray) {
							resolvedProp.items = (detailProp.uuids as string[]).map(
								(v: string) => labelMap.get(v) ?? detailProp.items[(detailProp.uuids as string[]).indexOf(v)] ?? v
							);
						} else {
							resolvedProp.value = labelMap.get(detailProp.uuid) ?? detailProp.value;
						}
					}
				}

				schemaProps.push({
					...resolvedProp,
					schemaLabel: schemaProp.label || schemaProp.key,
					formattedValue,
					formatError,
					choiceLabel,
					choiceLabels,
				});
			}

			const extraProps = this.detailProperties.filter((p: any) => !matched.has(p.name));
			return { schemaProps, extraProps };
		},
		filteredDetailProperties(): any[] {
			const q = (this.detailPropertiesFilter || '').trim().toLowerCase();
			if (!q) return this.detailProperties;
			return (this.detailProperties as any[]).filter((p: any) =>
				p.name.toLowerCase().includes(q)
			);
		},
		filteredSchemaDisplayProperties(): { schemaProps: any[]; extraProps: any[] } {
			const q = (this.detailPropertiesFilter || '').trim().toLowerCase();
			const { schemaProps, extraProps } = this.schemaDisplayProperties;
			if (!q) return { schemaProps, extraProps };
			const match = (p: any) =>
				p.name.toLowerCase().includes(q) ||
				(p.schemaLabel && String(p.schemaLabel).toLowerCase().includes(q));
			return {
				schemaProps: schemaProps.filter(match),
				extraProps: extraProps.filter(match),
			};
		},
		filteredPropEditorDisplayItems(): any[] {
			const q = (this.propEditorFilter || '').trim().toLowerCase();
			const errorMode = this.propEditorErrorFilter;
			let items = this.propEditorDisplayItems as any[];
			if (errorMode === 'mismatch') {
				items = items.filter((p: any) => p.typeMismatch);
			} else if (errorMode === 'validation') {
				items = items.filter((p: any) => p.validationError);
			}
			if (!q) return items;
			return items.filter((p: any) =>
				p.name.toLowerCase().includes(q) ||
				(p.schemaLabel && String(p.schemaLabel).toLowerCase().includes(q))
			);
		},
		// Validation error for the currently edited property value (schema-based)
		propEditorEditError(): string {
			void (this as any)._i18nTick;
			if (!this.propEditorEditingName) return '';
			const prop = (this.propEditorDisplayItems as any[]).find((p: any) => p.name === this.propEditorEditingName);
			if (!prop) return '';
			const isArray = this.propEditorEditIsArray as boolean;
			const values: string[] = isArray
				? [...(this.propEditorEditValues as string[])]
				: [this.propEditorEditInput as string];
			// Build a lookup map that reflects the *draft* values for the
			// property being edited, so cross-property validation scripts
			// see the latest edit in progress.
			const allPropsRaw = new Map<string, { value: any; values: any[] }>();
			const editingName = this.propEditorEditingName;
			for (const p of this.propEditorItems as any[]) {
				if (p.isDeleted) continue;
				if (p.name === editingName) {
					const val = isArray ? (values.length > 0 ? values[0] : null) : (values[0] || null);
					allPropsRaw.set(p.name, { value: val, values: [...values] });
				} else {
					const val = p.isArray ? (p.currentValues.length > 0 ? p.currentValues[0] : null) : (p.currentValue || null);
					const vals = p.isArray ? [...p.currentValues] : [p.currentValue];
					allPropsRaw.set(p.name, { value: val, values: vals });
				}
			}
			const i18nFormat = (err: any): string => {
				const i18n = (window.parent as any)?.Webtop?.i18n;
				if (i18n && typeof i18n.formatValidationError === 'function') {
					return i18n.formatValidationError(err);
				}
				return err.fallbackMessage || err.messageId || 'Validation failed';
			};
			return validatePropertyValues({ ...prop, isArray }, values, allPropsRaw, i18nFormat);
		},
		// Missing properties (defined in schema but not present on the item).
		// Read-only properties are excluded — they can't be added via the editor,
		// so they shouldn't be counted as "missing" / shouldn't appear in the
		// "Add all missing properties" summary.
		propEditorMissingProperties(): { required: any[]; optional: any[] } {
			const required: any[] = [];
			const optional: any[] = [];
			if (!this.propEditorSchemaKey) return { required, optional };
			const schema = (this.availableSchemas as any[]).find((s: any) => s.key === this.propEditorSchemaKey);
			if (!schema) return { required, optional };
			const existingNames = new Set<string>();
			for (const p of this.propEditorItems as any[]) {
				if (!p.isDeleted) existingNames.add(p.name);
			}
			for (const sp of schema.properties) {
				if (existingNames.has(sp.key)) continue;
				if (sp.readOnly) continue;
				if (sp.required) required.push(sp);
				else optional.push(sp);
			}
			return { required, optional };
		},
		// Issue counts for the property editor summary bar
		propEditorIssueCounts(): { mismatch: number; validation: number; modified: number } {
			const items = this.propEditorDisplayItems as any[];
			let mismatch = 0;
			let validation = 0;
			let modified = 0;
			for (const p of items) {
				if (p.typeMismatch) mismatch++;
				if (p.validationError) validation++;
				if (p.isModified || p.isNew || p.isDeleted) modified++;
			}
			return { mismatch, validation, modified };
		},
	},
	watch: {
		// Push the current folder path to the shell so the Dock hover preview
		// always reflects the latest location. The shell stores this in its
		// own reactive state — see ApplicationInstance.setDisplayInfo().
		currentPath(val: string) {
			(this as any).instance?.setDisplayInfo({ subtitle: val || '' });
		},
	},
	methods: {
		async onMounted() {
			const vm = this;

			// Register click-outside listener for dropdowns
			vm.navClickOutsideListener = (e: MouseEvent) => vm.onNavClickOutside(e);
			document.addEventListener('click', vm.navClickOutsideListener);

			// register message listener
			vm.messageListener = async (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const { type, ...payload } = event.data || {};
				if (type === 'theme-changed') {
					document.documentElement.dataset.theme = payload.theme;
					return;
				}
				if (type === 'theme-tick') {
					for (const item of vm.items) {
						item.attributes.displayDate = vm.displayDate(item);
					}
					return;
				}
				if (type === 'context-menu-action') {
					vm.handleContextMenuAction(payload.action);
					return;
				}
				if (type === 'metadata-definitions-updated') {
					vm.loadAvailableSchemas();
					return;
				}
				if (type === 'i18n-bundles-updated') {
					// Bump the reactive tick so validation computeds re-run
					vm._i18nTick = (vm._i18nTick || 0) + 1;
					return;
				}
				if (type === 'content-browser-preferences-changed') {
					const data = payload.data || {};
					if (data.pinnedPaths) {
						vm.pinnedPaths = typeof data.pinnedPaths === 'string'
							? JSON.parse(data.pinnedPaths) : data.pinnedPaths;
					}
					if (data.smartFolders) {
						vm.smartFolders = typeof data.smartFolders === 'string'
							? JSON.parse(data.smartFolders) : data.smartFolders;
					}
					if (data.smartFolderNextID) {
						vm.smartFolderNextID = data.smartFolderNextID;
					}
					// Also update IndexedDB
					const db = vm.instance?.api?.db;
					const userID = vm.instance?.currentUser?.id || '*';
					if (db) {
						db.setUserSetting(userID, 'content-browser', 'pinnedPaths', vm.pinnedPaths).catch(() => {});
						db.setUserSetting(userID, 'content-browser', 'smartFolders', {
							folders: vm.smartFolders,
							nextID: vm.smartFolderNextID,
						}).catch(() => {});
					}
					return;
				}
			};
			window.addEventListener('message', vm.messageListener);

			// register appLaunch
			window.appLaunch = async (instance: ApplicationInstance, options?: { initialPath?: string; [key: string]: any }) => {
				// Mark instance as raw (non-reactive) to prevent Proxy wrapping
				// This is necessary because ApplicationInstance has private fields (#id, etc.)
				vm.instance = this.$markRaw(instance);
				vm.idp = this.$markRaw(new IdpServiceGraphQL());
				instance.appState = () => ({ initialPath: vm.currentPath });
				instance.setDisplayInfo({ subtitle: vm.currentPath || '' });

				// Suppress server sync during initialization
				vm._suppressServerSync = true;

				// Load persisted user settings from IndexedDB
				await vm.loadPersistedNavData();
				await vm.loadDetailPanelState();
				await vm.loadSidebarPanelState();
				await vm.loadSmartFolders();
				await vm.loadServerPreferences();

				// apply theme
				const theme = vm.instance.api.theme.currentTheme || 'light';
				document.documentElement.dataset.theme = theme;

				// Use initialPath from launch options if provided (e.g. opened for reference browsing)
				if (options?.initialPath) {
					vm.currentPath = options.initialPath;
				}

				// Load available schemas (non-blocking)
				vm.loadAvailableSchemas();

				await vm.load(vm.currentPath);

				// Allow server sync after initialization
				vm._suppressServerSync = false;
				this.$nextTick(() => {
					// Notify that the app has launched
					// This properly handles iframe/parent window communication
					instance.notifyLaunched();
				});
			};

			// Register keydown listener for keyboard shortcuts
			vm.keydownListener = (e: KeyboardEvent) => {
				vm.handleKeydown(e);
			};
			document.addEventListener('keydown', vm.keydownListener);
		},
		async onUnmount() {
			const vm = this;
			// unregister message listener
			window.removeEventListener('message', vm.messageListener);
			// unregister keydown listener
			if (vm.keydownListener) {
				document.removeEventListener('keydown', vm.keydownListener);
				vm.keydownListener = null;
			}
			// unregister click-outside listener
			if (vm.navClickOutsideListener) {
				document.removeEventListener('click', vm.navClickOutsideListener);
				vm.navClickOutsideListener = null;
			}
			// cleanup detail panel resize listeners
			if (vm._boundResizeMove) {
				document.removeEventListener('mousemove', vm._boundResizeMove);
				vm._boundResizeMove = null;
			}
			if (vm._boundResizeUp) {
				document.removeEventListener('mouseup', vm._boundResizeUp);
				vm._boundResizeUp = null;
			}
			vm.detailPanelResizing = false;
			// Unsubscribe node watch
			if (vm._nodeWatchUnsubscribe) {
				vm._nodeWatchUnsubscribe();
				vm._nodeWatchUnsubscribe = null;
			}
			if (vm._parentWatchUnsubscribe) {
				vm._parentWatchUnsubscribe();
				vm._parentWatchUnsubscribe = null;
			}
		},
		async load(path: string, skipStack: boolean = false) {
			const vm = this;
			vm.errorMessage = '';
			vm.currentFolderDeleted = false;
			vm.currentPath = path;
			vm.addToPathHistory(path);
			// Update browser-style navigation stack
			if (!skipStack) {
				// Skip when this load is just a refresh of the current path
				// (e.g. after paste/delete/upload). Avoids duplicate stack entries
				// that would otherwise require pressing Back twice.
				const currentTop = vm.navStack[vm.navStackIndex];
				if (currentTop !== path) {
					// Truncate forward history and push new path
					vm.navStack = vm.navStack.slice(0, vm.navStackIndex + 1);
					vm.navStack.push(path);
					vm.navStackIndex = vm.navStack.length - 1;
				}
			}
			vm.clearSelection();
			vm.items = [];
			vm.loadingMonitor = { loading: true };

			try {
				// Use GraphQL API to fetch content
				const contentService = vm.instance.api.content;

				// Verify the parent node exists
				const parentNode = await contentService.getNode(path);
				if (!parentNode) {
					throw new Error(`The item with path "${path}" does not exist.`);
				}

				// Fetch all children using auto-pagination
				const items: ContentItem[] = [];
				for await (const batch of contentService.listAllChildren(path, 50)) {
					for (const node of batch) {
						const item = nodeToContentItem(node);
						item.attributes.displayDate = vm.displayDate(item);
						items.push(item);
					}
				}

				vm.items = items;
				vm.sortItems(vm.sortColumn, vm.sortDirection);
			} catch (error: any) {
				vm.errorMessage = error?.message || String(error) || 'Failed to fetch data';
			} finally {
				vm.loadingMonitor = null;
			}

			// Update node watch subscription for new path
			vm._setupNodeWatch();

		},
		/**
		 * Set up or update node watch subscription for real-time content updates.
		 * Watches the current directory for child node changes via EventHub SSE.
		 */
		_setupNodeWatch() {
			const vm = this;
			// Unsubscribe previous watches
			if (vm._nodeWatchUnsubscribe) {
				vm._nodeWatchUnsubscribe();
				vm._nodeWatchUnsubscribe = null;
			}
			if (vm._parentWatchUnsubscribe) {
				vm._parentWatchUnsubscribe();
				vm._parentWatchUnsubscribe = null;
			}

			if (!vm.instance?.api?.eventHub) return;

			const eventHub = vm.instance.api.eventHub;
			let debounceTimer: number | null = null;

			vm._nodeWatchUnsubscribe = eventHub.watchNode(
				vm.currentPath,
				(event: any) => {
					// Accumulate events during debounce window
					vm._pendingNodeEvents.push({
						eventType: event.eventType,
						path: event.path,
						sourcePath: event.sourcePath,
					});
					if (debounceTimer) clearTimeout(debounceTimer);
					debounceTimer = window.setTimeout(() => {
						debounceTimer = null;
						const events = [...vm._pendingNodeEvents];
						vm._pendingNodeEvents = [];
						vm._processNodeEvents(events);
					}, 500);
				},
				false // shallow — only direct children
			);

			// Watch the parent folder so we can detect when the current folder
			// itself is deleted (or moved away). The shallow child watch above
			// will not fire for the current folder, so without this the UI
			// would be stuck displaying stale entries that no longer exist.
			const lastSlash = vm.currentPath.lastIndexOf('/');
			if (lastSlash > 0) {
				const parentPath = vm.currentPath.substring(0, lastSlash);
				const watchedPath = vm.currentPath;
				vm._parentWatchUnsubscribe = eventHub.watchNode(
					parentPath,
					(event: any) => {
						if (event.path !== watchedPath) return;
						if (event.eventType === 'DELETED'
							|| (event.eventType === 'MOVED' && event.sourcePath === watchedPath)) {
							vm._handleCurrentFolderRemoved();
						}
					},
					false // shallow — only direct children of the parent
				);
			}
		},
		/**
		 * React to the current folder being removed (deleted or moved) so that
		 * the user is not left with stale, unresponsive list entries. Clears
		 * the list and surfaces a notice; the user can navigate elsewhere
		 * (breadcrumb, sidebar, back, path edit) to keep working.
		 */
		_handleCurrentFolderRemoved() {
			const vm = this;
			if (vm.currentFolderDeleted) return;
			vm.currentFolderDeleted = true;
			vm.items = [];
			vm.clearSelection();
			vm._pendingNodeEvents = [];
			// The shallow child watch on a now-deleted path is useless; drop it.
			if (vm._nodeWatchUnsubscribe) {
				vm._nodeWatchUnsubscribe();
				vm._nodeWatchUnsubscribe = null;
			}
		},
		/**
		 * Navigate to the nearest existing ancestor of the current path.
		 * Used when the displayed folder has been deleted.
		 */
		async navigateToNearestExistingAncestor() {
			const vm = this;
			const contentService = vm.instance?.api?.content;
			if (!contentService) return;
			let path = vm.currentPath;
			while (path && path !== '/') {
				const idx = path.lastIndexOf('/');
				if (idx <= 0) {
					path = '/';
					break;
				}
				path = path.substring(0, idx);
				try {
					const node = await contentService.getNode(path);
					if (node) {
						await vm.navigateToPath(path);
						return;
					}
				} catch {
					// keep walking up
				}
			}
			await vm.navigateToPath('/content');
		},
		/**
		 * Process accumulated node change events incrementally.
		 * Fetches only changed nodes instead of reloading the entire list.
		 */
		async _processNodeEvents(events: { eventType: string; path: string; sourcePath?: string }[]) {
			const vm = this;
			const contentService = vm.instance?.api?.content;
			if (!contentService) return;

			// Deduplicate by path — keep the latest event for each path
			const deduplicated: { eventType: string; path: string; sourcePath?: string }[] = [];
			const seen = new Map<string, number>();
			for (let i = 0; i < events.length; i++) {
				const e = events[i];
				const prevIdx = seen.get(e.path);
				if (prevIdx !== undefined) {
					deduplicated[prevIdx] = e;
				} else {
					seen.set(e.path, deduplicated.length);
					deduplicated.push(e);
				}
			}

			const flashIds: string[] = [];

			for (const evt of deduplicated) {
				const path = evt.path;
				const eventType = evt.eventType;
				const sourcePath = evt.sourcePath;

				try {
					// For MOVED events, remove the source item if it is in the current directory
					if (eventType === 'MOVED' && sourcePath) {
						const sourceIdx = vm.items.findIndex((i: any) => i.path === sourcePath);
						if (sourceIdx !== -1) {
							const removedId = vm.items[sourceIdx].id;
							vm.items.splice(sourceIdx, 1);
							vm.selectedItems = vm.selectedItems.filter((id: string) => id !== removedId);
						}
					}

					if (eventType === 'DELETED') {
						// Remove item from list
						const idx = vm.items.findIndex((i: any) => i.path === path);
						if (idx !== -1) {
							const removedId = vm.items[idx].id;
							vm.items.splice(idx, 1);
							vm.selectedItems = vm.selectedItems.filter((id: string) => id !== removedId);
						}
					} else {
						// For CREATED, MODIFIED, MOVED, etc. — check if path is a direct child
						const parentPath = path.substring(0, path.lastIndexOf('/'));
						if (parentPath !== vm.currentPath) {
							// Not a direct child of the current directory — skip adding
							continue;
						}

						const node = await contentService.getNode(path);
						if (node) {
							const item = nodeToContentItem(node);
							item.attributes.displayDate = vm.displayDate(item);
							const existingIdx = vm.items.findIndex((i: any) => i.path === path);
							if (existingIdx !== -1) {
								// Update existing item in-place
								vm.items.splice(existingIdx, 1, item);
							} else {
								// New item — add and re-sort
								vm.items.push(item);
								vm.sortItems(vm.sortColumn, vm.sortDirection);
							}
							flashIds.push(item.id);
						} else {
							// Node no longer exists — remove it
							const idx = vm.items.findIndex((i: any) => i.path === path);
							if (idx !== -1) {
								vm.items.splice(idx, 1);
							}
						}
					}
				} catch (error) {
					// Node fetch failed (e.g., deleted between event and fetch) — remove it
					const idx = vm.items.findIndex((i: any) => i.path === path);
					if (idx !== -1) {
						vm.items.splice(idx, 1);
					}
				}
			}

			// Trigger flash animation for changed items
			if (flashIds.length > 0) {
				vm.flashingItems = [...flashIds];
				window.setTimeout(() => {
					vm.flashingItems = [];
				}, 1000);
			}

			// Refresh detail panel if selected item was updated
			if (vm.selectedItems.length > 0) {
				const selectedPaths = vm.selectedItems.map((id: string) =>
					vm.items.find((i: any) => i.id === id)?.path
				).filter(Boolean);
				const updatedPaths = deduplicated.map(e => e.path);
				if (selectedPaths.some((p: string) => updatedPaths.includes(p))) {
					vm.loadDetailData();
				}
			}
		},
		async closeLoading() {
			this.loadingMonitor = null;
		},
		async openItem(item: any) {
			if (item.isCollection) {
				await this.load(item.path);
				return;
			}
			const editorApp = this.findEditorForMimeType(item.mimeType);
			if (editorApp) {
				window.parent.postMessage({
					type: 'open-file-with-app',
					appId: editorApp.id,
					filePath: item.path,
					mimeType: item.mimeType,
				}, window.location.origin);
			}
		},
		findEditorForMimeType(mimeType: string): any {
			// Access apps from parent window's Webtop context
			const apps = window.parent?.Webtop?.apps || [];

			for (const app of apps) {
				// Check if app is an editor
				if (!app.editor) continue;

				// Check if app supports this MIME type
				const contentTypes = app.contentTypes || [];
				for (const pattern of contentTypes) {
					// Handle wildcard patterns like "text/*"
					if (pattern.endsWith('/*')) {
						const prefix = pattern.slice(0, -1); // "text/"
						if (mimeType.startsWith(prefix)) {
							return app;
						}
					} else if (pattern === mimeType) {
						return app;
					}
				}
			}

			return null;
		},
		downloadFile(url: string, filename?: string) {
			const a = document.createElement('a');
			a.href = url;
			a.download = filename || '';
			document.body.appendChild(a);
			a.click();
			document.body.removeChild(a);
		},
		displaySize(item: any) {
			if (item.isCollection) {
				return '';
			}
			return this.instance.util.bytes.format(item.contentLength, { short: true });
		},
		displayDate(item: any) {
			const options = { format: 'friendly' };
			if (item.isCollection) {
				return this.instance.util.dates.format(item.created, options);
			}
			return this.instance.util.dates.format(item.lastModified, options);
		},
		displayType(item: any) {
			if (item.isCollection) {
				return 'Folder';
			}
			const desc = this.instance.util.mimeTypes.description(item.mimeType);
			if (!desc) {
				return item.mimeType;
			}
			return desc;
		},
		// MIME type inline editor methods
		startMimeTypeEdit() {
			const vm = this;
			const item = vm.selectedItem;
			if (!item || item.isCollection) return;
			vm.mimeTypeEditing = true;
			vm.mimeTypeInput = item.mimeType || '';
			vm.mimeTypeSaving = false;
			vm.mimeTypeHighlightIndex = -1;
			vm.$nextTick(() => {
				const input = document.querySelector('.mime-type-editor-input') as HTMLInputElement | null;
				if (input) {
					input.focus();
					input.select();
				}
				vm.refreshMimeTypeSuggestionsPopup();
			});
		},
		cancelMimeTypeEdit() {
			this.mimeTypeEditing = false;
			this.mimeTypeInput = '';
			this.mimeTypeSuggestions = [];
			this.mimeTypeHighlightIndex = -1;
			this.closeMimeTypeSuggestionsPopup();
		},
		updateMimeTypeSuggestions() {
			const vm = this;
			const query = vm.mimeTypeInput.trim();
			if (!query) {
				vm.mimeTypeSuggestions = [];
				vm.mimeTypeHighlightIndex = -1;
				vm.closeMimeTypeSuggestionsPopup();
				return;
			}
			const mimeTypes = vm.instance.util.mimeTypes;
			const filtered = mimeTypes.filterMimeTypes(query).slice(0, 20);
			vm.mimeTypeSuggestions = filtered.map((mt: string) => ({
				mimeType: mt,
				description: mimeTypes.description(mt) || mt,
			}));
			vm.mimeTypeHighlightIndex = -1;
			vm.refreshMimeTypeSuggestionsPopup();
		},
		buildMimeTypeSuggestionItems() {
			const vm = this;
			return vm.mimeTypeSuggestions.map((s: any, i: number) => ({
				id: s.mimeType,
				label: s.mimeType,
				description: s.description,
				highlighted: i === vm.mimeTypeHighlightIndex,
			}));
		},
		refreshMimeTypeSuggestionsPopup() {
			const vm = this;
			if (vm.mimeTypeSuggestions.length === 0) {
				vm.closeMimeTypeSuggestionsPopup();
				return;
			}
			const items = vm.buildMimeTypeSuggestionItems();
			if (mimeTypePopupHandle) {
				mimeTypePopupHandle.update(items);
				return;
			}
			const input = document.querySelector('.mime-type-editor-input') as HTMLInputElement | null;
			if (!input || !vm.instance) return;
			const rect = input.getBoundingClientRect();
			mimeTypePopupHandle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				maxHeight: 360,
				items,
			});
			mimeTypePopupHandle.result.then((picked: any) => {
				mimeTypePopupHandle = null;
				if (picked == null) return;
				vm.mimeTypeInput = String(picked);
				vm.mimeTypeSuggestions = [];
				vm.mimeTypeHighlightIndex = -1;
				const inp = document.querySelector('.mime-type-editor-input') as HTMLInputElement | null;
				inp?.focus();
			});
		},
		closeMimeTypeSuggestionsPopup() {
			if (mimeTypePopupHandle) {
				mimeTypePopupHandle.close();
				mimeTypePopupHandle = null;
			}
		},
		handleMimeTypeKeydown(e: KeyboardEvent) {
			const vm = this;
			if (e.key === 'Escape') {
				vm.cancelMimeTypeEdit();
				return;
			}
			if (e.key === 'ArrowDown') {
				e.preventDefault();
				if (vm.mimeTypeSuggestions.length > 0) {
					vm.mimeTypeHighlightIndex = Math.min(
						vm.mimeTypeHighlightIndex + 1,
						vm.mimeTypeSuggestions.length - 1
					);
					vm.refreshMimeTypeSuggestionsPopup();
				}
				return;
			}
			if (e.key === 'ArrowUp') {
				e.preventDefault();
				if (vm.mimeTypeHighlightIndex > 0) {
					vm.mimeTypeHighlightIndex--;
					vm.refreshMimeTypeSuggestionsPopup();
				}
				return;
			}
			if (e.key === 'Enter') {
				e.preventDefault();
				if (vm.mimeTypeHighlightIndex >= 0 && vm.mimeTypeHighlightIndex < vm.mimeTypeSuggestions.length) {
					const picked = vm.mimeTypeSuggestions[vm.mimeTypeHighlightIndex];
					vm.mimeTypeInput = picked.mimeType;
					vm.mimeTypeSuggestions = [];
					vm.mimeTypeHighlightIndex = -1;
					vm.closeMimeTypeSuggestionsPopup();
				} else {
					vm.confirmMimeTypeEdit();
				}
				return;
			}
		},
		async confirmMimeTypeEdit() {
			const item = this.selectedItem;
			const newMimeType = this.mimeTypeInput.trim();
			if (!item || !newMimeType || newMimeType === item.mimeType) {
				this.cancelMimeTypeEdit();
				return;
			}
			const vm = this;
			vm.mimeTypeSaving = true;
			try {
				const contentService = vm.instance.api.content;
				await contentService.updateMimeType(item.path, newMimeType);
				// Update local item data
				item.mimeType = newMimeType;
				vm.cancelMimeTypeEdit();
			} catch (err: any) {
				console.error('Failed to update MIME type:', err);
				vm.mimeTypeSaving = false;
			}
		},
		// Encoding inline editor methods
		displayEncoding(item: any) {
			if (!item || item.isCollection) return '';
			if (!item.encoding) return '';
			const desc = this.instance.util.encodings.description(item.encoding);
			if (!desc) {
				return item.encoding;
			}
			return desc;
		},
		startEncodingEdit() {
			const vm = this;
			const item = vm.selectedItem;
			if (!item || item.isCollection) return;
			vm.encodingEditing = true;
			vm.encodingInput = item.encoding || '';
			vm.encodingSaving = false;
			vm.encodingHighlightIndex = -1;
			vm.$nextTick(() => {
				const input = document.querySelector('.encoding-editor-input') as HTMLInputElement | null;
				if (input) {
					input.focus();
					input.select();
				}
				vm.updateEncodingSuggestions();
			});
		},
		cancelEncodingEdit() {
			this.encodingEditing = false;
			this.encodingInput = '';
			this.encodingSuggestions = [];
			this.encodingHighlightIndex = -1;
			this.closeEncodingSuggestionsPopup();
		},
		updateEncodingSuggestions() {
			const vm = this;
			const query = vm.encodingInput.trim();
			const encodings = vm.instance.util.encodings;
			const filtered = encodings.filterEncodings(query).slice(0, 20);
			vm.encodingSuggestions = filtered.map((name: string) => ({
				name,
				description: encodings.description(name) || name,
			}));
			vm.encodingHighlightIndex = -1;
			vm.refreshEncodingSuggestionsPopup();
		},
		buildEncodingSuggestionItems() {
			const vm = this;
			return vm.encodingSuggestions.map((s: any, i: number) => ({
				id: s.name,
				label: s.name,
				description: s.description,
				highlighted: i === vm.encodingHighlightIndex,
			}));
		},
		refreshEncodingSuggestionsPopup() {
			const vm = this;
			if (vm.encodingSuggestions.length === 0) {
				vm.closeEncodingSuggestionsPopup();
				return;
			}
			const items = vm.buildEncodingSuggestionItems();
			if (encodingPopupHandle) {
				encodingPopupHandle.update(items);
				return;
			}
			const input = document.querySelector('.encoding-editor-input') as HTMLInputElement | null;
			if (!input || !vm.instance) return;
			const rect = input.getBoundingClientRect();
			encodingPopupHandle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				maxHeight: 360,
				items,
			});
			encodingPopupHandle.result.then((picked: any) => {
				encodingPopupHandle = null;
				if (picked == null) return;
				vm.encodingInput = String(picked);
				vm.encodingSuggestions = [];
				vm.encodingHighlightIndex = -1;
				const inp = document.querySelector('.encoding-editor-input') as HTMLInputElement | null;
				inp?.focus();
			});
		},
		closeEncodingSuggestionsPopup() {
			if (encodingPopupHandle) {
				encodingPopupHandle.close();
				encodingPopupHandle = null;
			}
		},
		handleEncodingKeydown(e: KeyboardEvent) {
			const vm = this;
			if (e.key === 'Escape') {
				vm.cancelEncodingEdit();
				return;
			}
			if (e.key === 'ArrowDown') {
				e.preventDefault();
				if (vm.encodingSuggestions.length === 0) {
					vm.updateEncodingSuggestions();
					return;
				}
				vm.encodingHighlightIndex = Math.min(
					vm.encodingHighlightIndex + 1,
					vm.encodingSuggestions.length - 1
				);
				vm.refreshEncodingSuggestionsPopup();
				return;
			}
			if (e.key === 'ArrowUp') {
				e.preventDefault();
				if (vm.encodingHighlightIndex > 0) {
					vm.encodingHighlightIndex--;
					vm.refreshEncodingSuggestionsPopup();
				}
				return;
			}
			if (e.key === 'Enter') {
				e.preventDefault();
				if (vm.encodingHighlightIndex >= 0 && vm.encodingHighlightIndex < vm.encodingSuggestions.length) {
					const picked = vm.encodingSuggestions[vm.encodingHighlightIndex];
					vm.encodingInput = picked.name;
					vm.encodingSuggestions = [];
					vm.encodingHighlightIndex = -1;
					vm.closeEncodingSuggestionsPopup();
				} else {
					vm.confirmEncodingEdit();
				}
				return;
			}
		},
		async confirmEncodingEdit() {
			const item = this.selectedItem;
			const newEncoding = this.encodingInput.trim();
			if (!item) {
				this.cancelEncodingEdit();
				return;
			}
			if (newEncoding === (item.encoding || '')) {
				this.cancelEncodingEdit();
				return;
			}
			const vm = this;
			vm.encodingSaving = true;
			try {
				const contentService = vm.instance.api.content;
				await contentService.updateEncoding(item.path, newEncoding);
				item.encoding = newEncoding;
				vm.cancelEncodingEdit();
			} catch (err: any) {
				console.error('Failed to update encoding:', err);
				vm.encodingSaving = false;
			}
		},
		displayVersion(item: any) {
			if (!item.isVersionable) {
				return '';
			}
			const version = item.baseVersionName || '';
			if (item.isCheckedOut) {
				return version + ' \u270E'; // Pencil character for editing
			}
			return version;
		},
		getFileIcon(item: any): string {
			if (item.isCollection) {
				return 'bi bi-folder-fill';
			}

			const mimeType = item.mimeType || '';
			const [type, subtype] = mimeType.split('/');

			// Image files
			if (type === 'image') {
				return 'bi bi-image';
			}

			// Video files
			if (type === 'video') {
				return 'bi bi-file-earmark-play';
			}

			// Audio files
			if (type === 'audio') {
				return 'bi bi-file-earmark-music';
			}

			// Text and code files
			if (type === 'text') {
				if (subtype === 'html' || subtype === 'css' || subtype === 'javascript' || subtype === 'xml') {
					return 'bi bi-file-earmark-code';
				}
				if (subtype === 'csv') {
					return 'bi bi-file-earmark-spreadsheet';
				}
				return 'bi bi-file-earmark-text';
			}

			// Application types
			if (type === 'application') {
				// PDF
				if (subtype === 'pdf') {
					return 'bi bi-file-earmark-pdf';
				}

				// Archives
				if (subtype === 'zip' || subtype === 'x-rar-compressed' || subtype === 'x-7z-compressed' ||
					subtype === 'x-tar' || subtype === 'gzip' || subtype === 'x-bzip2') {
					return 'bi bi-file-earmark-zip';
				}

				// Microsoft Office / OpenDocument
				if (subtype === 'msword' || subtype === 'vnd.openxmlformats-officedocument.wordprocessingml.document' ||
					subtype === 'vnd.oasis.opendocument.text') {
					return 'bi bi-file-earmark-word';
				}
				if (subtype === 'vnd.ms-excel' || subtype === 'vnd.openxmlformats-officedocument.spreadsheetml.sheet' ||
					subtype === 'vnd.oasis.opendocument.spreadsheet') {
					return 'bi bi-file-earmark-spreadsheet';
				}
				if (subtype === 'vnd.ms-powerpoint' || subtype === 'vnd.openxmlformats-officedocument.presentationml.presentation' ||
					subtype === 'vnd.oasis.opendocument.presentation') {
					return 'bi bi-file-earmark-slides';
				}

				// Code/data files
				if (subtype === 'json' || subtype === 'xml' || subtype === 'javascript' || subtype === 'x-javascript' ||
					subtype === 'typescript' || subtype === 'x-httpd-php' || subtype === 'x-python-code' ||
					subtype === 'x-sh' || subtype === 'x-yaml' || subtype === 'sql') {
					return 'bi bi-file-earmark-code';
				}
			}

			// Default file icon
			return 'bi bi-file-earmark';
		},
		getFileIconClass(item: any): string {
			if (item.isCollection) {
				return 'icon-folder';
			}

			const mimeType = item.mimeType || '';
			const [type, subtype] = mimeType.split('/');

			// Image files
			if (type === 'image') {
				return 'icon-image';
			}

			// Video files
			if (type === 'video') {
				return 'icon-video';
			}

			// Audio files
			if (type === 'audio') {
				return 'icon-audio';
			}

			// Text files
			if (type === 'text') {
				if (subtype === 'html' || subtype === 'css' || subtype === 'javascript' || subtype === 'xml') {
					return 'icon-code';
				}
				if (subtype === 'csv') {
					return 'icon-spreadsheet';
				}
				return 'icon-text';
			}

			// Application types
			if (type === 'application') {
				// PDF
				if (subtype === 'pdf') {
					return 'icon-pdf';
				}

				// Archives
				if (subtype === 'zip' || subtype === 'x-rar-compressed' || subtype === 'x-7z-compressed' ||
					subtype === 'x-tar' || subtype === 'gzip' || subtype === 'x-bzip2') {
					return 'icon-archive';
				}

				// Microsoft Office / OpenDocument
				if (subtype === 'msword' || subtype === 'vnd.openxmlformats-officedocument.wordprocessingml.document' ||
					subtype === 'vnd.oasis.opendocument.text') {
					return 'icon-document';
				}
				if (subtype === 'vnd.ms-excel' || subtype === 'vnd.openxmlformats-officedocument.spreadsheetml.sheet' ||
					subtype === 'vnd.oasis.opendocument.spreadsheet') {
					return 'icon-spreadsheet';
				}
				if (subtype === 'vnd.ms-powerpoint' || subtype === 'vnd.openxmlformats-officedocument.presentationml.presentation' ||
					subtype === 'vnd.oasis.opendocument.presentation') {
					return 'icon-presentation';
				}

				// Code/data files
				if (subtype === 'json' || subtype === 'xml' || subtype === 'javascript' || subtype === 'x-javascript' ||
					subtype === 'typescript' || subtype === 'x-httpd-php' || subtype === 'x-python-code' ||
					subtype === 'x-sh' || subtype === 'x-yaml' || subtype === 'sql') {
					return 'icon-code';
				}
			}

			// Default
			return 'icon-default';
		},
		sortItems(column: string, direction: string) {
			if (direction) {
				this.sortColumn = column;
				this.sortDirection = direction;
			} else if (this.sortColumn === column) {
				this.sortDirection = this.sortDirection === 'asc' ? 'desc' : 'asc';
			} else {
				this.sortColumn = column;
				this.sortDirection = 'asc';
			}

			const dir = this.sortDirection === 'asc' ? 1 : -1;
			this.items.sort((a, b) => {
				if (a.isCollection !== b.isCollection) {
					return a.isCollection ? -1 : 1;
				}

				let av: any;
				let bv: any;
				if (column == 'name') {
					av = a.name?.toLowerCase() ?? '';
					bv = b.name?.toLowerCase() ?? '';
				} else if (column == 'date') {
					av = a.lastModified?.getTime?.() ?? 0;
					bv = b.lastModified?.getTime?.() ?? 0;
				} else if (column == 'modifiedBy') {
					av = (a.lastModifiedByDisplayName || a.lastModifiedBy || '').toLowerCase();
					bv = (b.lastModifiedByDisplayName || b.lastModifiedBy || '').toLowerCase();
				} else if (column == 'lockOwner') {
					av = a.lockInfo?.lockOwner?.toLowerCase() ?? '';
					bv = b.lockInfo?.lockOwner?.toLowerCase() ?? '';
				} else if (column == 'version') {
					av = a.baseVersionName?.toLowerCase() ?? '';
					bv = b.baseVersionName?.toLowerCase() ?? '';
				} else if (column == 'kind') {
					const ad = this.instance.util.mimeTypes.description(a.mimeType) || a.mimeType || '';
					const bd = this.instance.util.mimeTypes.description(b.mimeType) || b.mimeType || '';
					av = ad.toLowerCase();
					bv = bd.toLowerCase();
				} else if (column == 'size') {
					av = a.contentLength ?? 0;
					bv = b.contentLength ?? 0;
				}
				if (av < bv) return -1 * dir;
				if (av > bv) return 1 * dir;
				return 0;
			});
		},
		/**
		 * Handle drag start on file items for cross-app drag-and-drop
		 */
		onFileDragStart(item: ContentItem, event: DragEvent) {
			// Delegate to internal drag handler (supports both files and folders)
			this.onInternalDragStart(item, event);
		},
		// App root (outside center pane): reject all drops with a forbidden cursor
		// so the browser does not navigate to a dropped file.
		onAppDragOver(event: DragEvent) {
			if (event.dataTransfer) event.dataTransfer.dropEffect = 'none';
		},
		// Side panes (sidebar / detail) reject all drops. Inner property-editor
		// drop zones (REFERENCE / WEAKREFERENCE / BINARY) use their own
		// @dragover.stop + @drop.stop, so this handler does not fire for them.
		onForbiddenDragOver(event: DragEvent) {
			if (event.dataTransfer) event.dataTransfer.dropEffect = 'none';
		},
		// Center pane: accept internal drags, save-as chips, and OS file uploads.
		onDragOver(event: DragEvent) {
			// Internal webtop file drags (same-iframe or cross-iframe)
			if (this._hasInternalDragData(event)) {
				if (event.dataTransfer) {
					if (this._isDragForbidden(event)) {
						event.dataTransfer.dropEffect = 'none';
					} else {
						event.dataTransfer.dropEffect = event.ctrlKey ? 'copy' : 'move';
					}
				}
				return;
			}
			if (!event.dataTransfer) return;
			// Block upload while a property is being edited
			if (this.propEditorEditingName !== null) {
				event.dataTransfer.dropEffect = 'none';
				return;
			}
			// Accept save-as drops from other apps
			if (event.dataTransfer.types.includes('application/x-webtop-save')) {
				event.dataTransfer.dropEffect = 'copy';
				return;
			}
			// OS file upload
			event.dataTransfer.dropEffect = 'copy';
		},
		async onDrop(event: DragEvent) {
			const vm = this;
			vm.errorMessage = '';

			// Handle internal webtop file drags (same-iframe per-row handled by onItemDrop;
			// cross-iframe or background drops handled here → move/copy to current directory)
			const internalItems = vm._getInternalDragItems(event);
			if (internalItems && internalItems.length > 0) {
				if (vm._isDragForbidden(event)) return;
				await vm._executeInternalDrop(internalItems, vm.currentPath, event.ctrlKey);
				return;
			}

			// Handle Save-As drops from other apps
			const saveData = event.dataTransfer?.getData('application/x-webtop-save');
			if (saveData) {
				try {
					const { name, mimeType, content, saveAsToken } = JSON.parse(saveData);
					await vm.saveDroppedContent(name, mimeType, content, saveAsToken);
					await vm.load(vm.currentPath);
				} catch (error: any) {
					vm.errorMessage = error?.message || 'Save failed';
				}
				return;
			}

			const itemList = event.dataTransfer?.items;
			if (!itemList || itemList.length === 0) return;

			// Initialize upload monitor state
			vm.uploadMonitor = {
				isCanceled: false,
				target: {
					currentFile: '',
					progressPercent: 0,
				},
				cancel() {
					this.isCanceled = true;
				},
			};

			try {
				// Collect all file entries from drag event
				const items = Array.from(itemList);
				const entries = items.map(i => (i as any).webkitGetAsEntry?.()).filter(e => e);

				if (entries.length === 0) return;

				const files: { file: File; path: string }[] = [];
				for (const entry of entries) {
					await vm.collectEntry(entry, '', files);
				}

				// Upload all collected files
				await vm.uploadFiles(files);

				// Reload directory after upload
				await vm.load(vm.currentPath);
			} catch (error: any) {
				if (!vm.uploadMonitor?.isCanceled) {
					vm.errorMessage = error?.message || String(error) || 'Upload failed';
				}
			} finally {
				if (!vm.errorMessage) {
					vm.uploadMonitor = null;
				}
			}
		},
		async collectEntry(entry: any, prefix: string, out: { file: File; path: string }[]) {
			const vm = this;
			if (vm.uploadMonitor?.isCanceled) return;

			const full = prefix ? `${prefix}/${entry.name}` : entry.name;
			if (entry.isFile) {
				await new Promise<void>((resolve, reject) => {
					entry.file((f: File) => { out.push({ file: f, path: full }); resolve(); }, reject);
				});
			} else if (entry.isDirectory) {
				const reader = entry.createReader();
				await new Promise<void>((resolve, reject) => {
					const read = () => reader.readEntries(async (ents: any[]) => {
						if (!ents.length) { resolve(); return; }
						for (const e of ents) {
							await vm.collectEntry(e, full, out);
						}
						read();
					}, reject);
					read();
				});
			}
		},
		async uploadFiles(files: { file: File; path: string }[]) {
			const vm = this;
			const contentService = vm.instance.api.content;
			let overwriteAll = false;
			let skipAll = false;
			vm.skipAllCheckout = false;
			const chunkSize = 524288; // 512KB chunks (Base64 encoded ~700KB)

			for (const info of files) {
				if (vm.uploadMonitor?.isCanceled) break;

				// Update current file display
				vm.uploadMonitor.target.currentFile = info.path;
				vm.uploadMonitor.target.progressPercent = 0;

				const parts = info.path.split('/');
				const filename = parts.pop() as string;
				const dir = parts.join('/');

				// Ensure parent folder exists
				const parentPath = vm.currentPath + (dir ? '/' + dir : '');
				await vm.ensureFolder(parentPath);

				// Check if file already exists
				const destPath = vm.currentPath + '/' + info.path;
				const existingNode = await contentService.getNode(destPath);

				if (existingNode) {
					let action = overwriteAll ? 'overwrite' : skipAll ? 'skip' : await vm.askConflict();
					if (action === 'overwriteAll') { overwriteAll = true; action = 'overwrite'; }
					if (action === 'skipAll') { skipAll = true; action = 'skip'; }
					if (action === 'cancel') { vm.uploadMonitor.cancel(); break; }
					if (action === 'skip') { continue; }
				}

				// Handle versioned file checkout
				let checkinAfterUpload = false;
				if (existingNode && existingNode.isVersionable && !existingNode.isCheckedOut) {
					if (vm.skipAllCheckout) {
						// Skip this file
						continue;
					}

					const checkoutAction = await vm.askCheckout(filename, existingNode.baseVersionName || '');
					if (checkoutAction === 'skip') {
						continue;
					}
					if (checkoutAction === 'skipAll') {
						vm.skipAllCheckout = true;
						continue;
					}
					if (checkoutAction === 'checkoutCheckin') {
						checkinAfterUpload = true;
					}

					// Perform checkout
					await contentService.checkout(destPath);
				}

				// Perform multipart upload
				const uploadInfo = await contentService.initiateMultipartUpload();
				const uploadId = uploadInfo.uploadId;

				try {
					const file = info.file;
					const totalSize = file.size;
					let offset = 0;

					while (offset < totalSize && !vm.uploadMonitor?.isCanceled) {
						const blob = file.slice(offset, offset + chunkSize);
						const encoded = await new Promise<string>((resolve) => {
							const reader = new FileReader();
							reader.onloadend = (e) => {
								const result = (e.target as FileReader).result as string;
								resolve(result.substring(result.indexOf(';base64,') + 8));
							};
							reader.readAsDataURL(blob);
						});

						await contentService.appendMultipartUploadChunk(uploadId, encoded);
						offset += chunkSize;

						// Update progress
						vm.uploadMonitor.target.progressPercent = Math.min(100, Math.floor((offset / totalSize) * 100));
					}

					if (vm.uploadMonitor?.isCanceled) {
						await contentService.abortMultipartUpload(uploadId);
						break;
					}

					// Complete the upload
					const mimeType = file.type || 'application/octet-stream';
					await contentService.completeMultipartUpload(
						uploadId,
						parentPath,
						filename,
						mimeType,
						existingNode ? true : false
					);

					// Checkin after upload if requested
					if (checkinAfterUpload) {
						await contentService.checkin(destPath);
					}
				} catch (error) {
					// Try to abort upload on error
					try {
						await contentService.abortMultipartUpload(uploadId);
					} catch { /* ignore abort errors */ }
					throw error;
				}
			}
		},
		async saveDroppedContent(name: string, mimeType: string, content: string, saveAsToken?: string) {
			const vm = this;
			const contentService = vm.instance.api.content;

			// Check if file already exists
			const destPath = vm.currentPath + '/' + name;
			const existingNode = await contentService.getNode(destPath);
			if (existingNode) {
				// Set temporary uploadMonitor so conflict dialog can render
				vm.uploadMonitor = { isCanceled: false, target: { currentFile: name, progressPercent: 0 }, cancel() { this.isCanceled = true; } };
				const action = await vm.askConflict();
				vm.uploadMonitor = null;
				if (action === 'skip' || action === 'skipAll' || action === 'cancel') return;
			}

			// Multipart upload
			const encoder = new TextEncoder();
			const bytes = encoder.encode(content);
			const base64Content = btoa(String.fromCharCode(...bytes));

			const uploadInfo = await contentService.initiateMultipartUpload();
			const uploadId = uploadInfo.uploadId;
			try {
				await contentService.appendMultipartUploadChunk(uploadId, base64Content);
				await contentService.completeMultipartUpload(
					uploadId, vm.currentPath, name, mimeType, existingNode ? true : false
				);

				// Notify source app of saved path via BroadcastChannel
				if (saveAsToken) {
					const channel = new BroadcastChannel('webtop-save-as');
					channel.postMessage({
						type: 'save-as-complete',
						path: destPath,
						name: name,
						mimeType: mimeType,
						saveAsToken: saveAsToken,
					});
					channel.close();
				}
			} catch (error) {
				try { await contentService.abortMultipartUpload(uploadId); } catch { /* ignore */ }
				throw error;
			}
		},
		async ensureFolder(path: string) {
			const vm = this;
			if (!path || path === '/' || path === vm.currentPath) return;

			const contentService = vm.instance.api.content;
			const node = await contentService.getNode(path);
			if (node) return;

			// Ensure parent folder exists first
			const idx = path.lastIndexOf('/');
			const parent = idx <= 0 ? '/' : path.substring(0, idx);
			await vm.ensureFolder(parent);

			// Create this folder
			const folderName = path.substring(idx + 1);
			await contentService.createFolder(parent, folderName);
		},
		async askConflict(): Promise<string> {
			const vm = this;
			vm.conflictDialog.visible = true;
			return await new Promise((resolve) => {
				vm.conflictDialog.resolve = resolve;
			});
		},
		async closeUpload() {
			const hadError = !!this.errorMessage;
			this.uploadMonitor = null;
			this.errorMessage = '';
			// Reload to clear stale items after an error
			if (hadError) {
				await this.load(this.currentPath);
			}
		},
		onConflictDialogAction(action: string) {
			if (this.conflictDialog.resolve) {
				this.conflictDialog.resolve(action);
			}
			this.conflictDialog.visible = false;
			this.conflictDialog.resolve = null;
		},
		// Checkout dialog for versioned file uploads
		async askCheckout(fileName: string, baseVersionName: string): Promise<string> {
			const vm = this;
			vm.checkoutDialog.fileName = fileName;
			vm.checkoutDialog.baseVersionName = baseVersionName;
			vm.checkoutDialog.visible = true;
			return await new Promise((resolve) => {
				vm.checkoutDialog.resolve = resolve;
			});
		},
		onCheckoutDialogAction(action: string) {
			if (this.checkoutDialog.resolve) {
				this.checkoutDialog.resolve(action);
			}
			this.checkoutDialog.visible = false;
			this.checkoutDialog.resolve = null;
		},
		// Selection methods
		isSelected(item: any): boolean {
			return this.selectedItems.includes(item.id);
		},
		clearSelection() {
			this.selectedItems = [];
			this.lastSelectedIndex = -1;
			this.previewImageError = false;
			this.loadDetailData();
		},
		selectItem(item: any, event?: MouseEvent) {
			const vm = this;
			vm.previewImageError = false;
			const index = vm.items.findIndex((i: any) => i.id === item.id);
			const e = event || (window.event as MouseEvent);
			const isCtrlOrCmd = e?.ctrlKey || e?.metaKey || false;
			const isShift = e?.shiftKey || false;

			if (isShift && vm.lastSelectedIndex >= 0) {
				// Shift+click: range selection
				const start = Math.min(vm.lastSelectedIndex, index);
				const end = Math.max(vm.lastSelectedIndex, index);
				if (!isCtrlOrCmd) {
					vm.selectedItems = [];
				}
				for (let i = start; i <= end; i++) {
					const id = vm.items[i].id;
					if (!vm.selectedItems.includes(id)) {
						vm.selectedItems.push(id);
					}
				}
			} else if (isCtrlOrCmd) {
				// Ctrl/Cmd+click: toggle selection
				const idx = vm.selectedItems.indexOf(item.id);
				if (idx >= 0) {
					vm.selectedItems.splice(idx, 1);
				} else {
					vm.selectedItems.push(item.id);
				}
				vm.lastSelectedIndex = index;
			} else {
				// Normal click: single selection
				vm.selectedItems = [item.id];
				vm.lastSelectedIndex = index;
			}
			vm.loadDetailData();
		},
		// Drag selection methods
		onContentMouseDown(event: MouseEvent) {
			const vm = this;
			// Only start drag selection if clicking on empty area (not on a row)
			const target = event.target as HTMLElement;
			const row = target.closest('tr.item');
			if (row) {
				// Clicked on a row, don't start drag selection
				return;
			}

			// Check if we're in the content-list area
			const contentList = target.closest('.content-list');
			if (!contentList) return;

			// Start drag selection
			const rect = contentList.getBoundingClientRect();
			vm.dragSelection.active = true;
			vm.dragSelection.startX = event.clientX - rect.left + contentList.scrollLeft;
			vm.dragSelection.startY = event.clientY - rect.top + contentList.scrollTop;
			vm.dragSelection.currentX = vm.dragSelection.startX;
			vm.dragSelection.currentY = vm.dragSelection.startY;

			// Clear selection if not holding Ctrl/Cmd
			if (!event.ctrlKey && !event.metaKey) {
				vm.clearSelection();
			}

			// Bind and add global mouse event listeners
			vm.boundMouseMove = vm.onContentMouseMove.bind(vm);
			vm.boundMouseUp = vm.onContentMouseUp.bind(vm);
			document.addEventListener('mousemove', vm.boundMouseMove);
			document.addEventListener('mouseup', vm.boundMouseUp);
		},
		onContentMouseMove(event: MouseEvent) {
			const vm = this;
			if (!vm.dragSelection.active) return;

			const contentList = document.querySelector('.content-list') as HTMLElement;
			if (!contentList) return;

			const rect = contentList.getBoundingClientRect();
			vm.dragSelection.currentX = event.clientX - rect.left + contentList.scrollLeft;
			vm.dragSelection.currentY = event.clientY - rect.top + contentList.scrollTop;

			// Calculate selection rectangle
			const selRect = vm.selectionRect;

			// Find items that intersect with selection rectangle
			const rows = contentList.querySelectorAll('tr.item');
			const newSelection: string[] = [];

			rows.forEach((row: Element, index: number) => {
				const rowRect = row.getBoundingClientRect();
				const rowTop = rowRect.top - rect.top + contentList.scrollTop;
				const rowBottom = rowTop + rowRect.height;
				const rowLeft = rowRect.left - rect.left + contentList.scrollLeft;
				const rowRight = rowLeft + rowRect.width;

				// Check intersection
				if (!(selRect.right < rowLeft || selRect.left > rowRight ||
					selRect.bottom < rowTop || selRect.top > rowBottom)) {
					const item = vm.items[index];
					if (item) {
						newSelection.push(item.id);
					}
				}
			});

			// Update selection (add to existing if Ctrl/Cmd was held)
			if (event.ctrlKey || event.metaKey) {
				// Merge with existing selection
				const merged = [...vm.selectedItems];
				for (const id of newSelection) {
					if (!merged.includes(id)) {
						merged.push(id);
					}
				}
				vm.selectedItems = merged;
			} else {
				vm.selectedItems = newSelection;
			}
		},
		onContentMouseUp(event: MouseEvent) {
			const vm = this;
			vm.dragSelection.active = false;

			// Remove global mouse event listeners
			if (vm.boundMouseMove) {
				document.removeEventListener('mousemove', vm.boundMouseMove);
				vm.boundMouseMove = null;
			}
			if (vm.boundMouseUp) {
				document.removeEventListener('mouseup', vm.boundMouseUp);
				vm.boundMouseUp = null;
			}
		},
		// Context menu methods
		onContextMenu(item: any, event: MouseEvent) {
			const vm = this;
			event.preventDefault();

			// 未選択のアイテムを右クリックした場合、そのアイテムを単一選択
			if (!vm.selectedItems.includes(item.id)) {
				vm.selectedItems = [item.id];
				vm.lastSelectedIndex = vm.items.findIndex((i: any) => i.id === item.id);
			}

			// 選択されたアイテムの情報を取得
			const selectedItemObjects = vm.items.filter((i: any) => vm.selectedItems.includes(i.id));
			const hasFolder = selectedItemObjects.some((i: any) => i.isCollection);
			const hasFile = selectedItemObjects.some((i: any) => !i.isCollection);
			const isSingleSelection = vm.selectedItems.length === 1;
			const hasLocked = selectedItemObjects.some((i: any) => i.isLocked);
			const hasUnlocked = selectedItemObjects.some((i: any) => !i.isLocked);
			// Referenceable: exclude versionable nodes from disable (mix:versionable requires mix:referenceable)
			const hasReferenceableNonVersionable = selectedItemObjects.some((i: any) => i.isReferenceable && !i.isVersionable);
			const hasNonReferenceableFile = selectedItemObjects.some((i: any) => !i.isReferenceable && !i.isCollection);
			// Version control: files only (not folders)
			const hasNonVersionableFile = selectedItemObjects.some((i: any) => !i.isVersionable && !i.isCollection);
			const hasCheckedOutFile = selectedItemObjects.some((i: any) => i.isCheckedOut && !i.isCollection);
			const hasCheckedInFile = selectedItemObjects.some((i: any) => i.isVersionable && !i.isCheckedOut && !i.isCollection);
			// Cancel Checkout is only available when not at root version (jcr:rootVersion)
			const hasCheckedOutNonRootFile = selectedItemObjects.some((i: any) => i.isCheckedOut && !i.isCollection && i.baseVersionName !== 'jcr:rootVersion');

			// コンテキストメニューのアイテムを構築
			const menuItems: { id: string; label: string; icon?: string; danger?: boolean; type?: string }[] = [];

			// New Folder/File options (always available)
			menuItems.push({ id: 'new-folder', label: 'New Folder', icon: 'bi-folder-plus' });
			menuItems.push({ id: 'new-file', label: 'New File', icon: 'bi-file-earmark-plus' });
			menuItems.push({ type: 'separator', id: '', label: '' });

			if (isSingleSelection && hasFolder) {
				menuItems.push({ id: 'open', label: 'Open', icon: 'bi-folder2-open' });
			}
			if (hasFile) {
				menuItems.push({ id: 'download', label: 'Download', icon: 'bi-download' });
			}
			menuItems.push({ type: 'separator', id: '', label: '' });
			menuItems.push({ id: 'copy', label: 'Copy', icon: 'bi-clipboard' });
			menuItems.push({ id: 'cut', label: 'Cut', icon: 'bi-scissors' });
			if (vm.clipboardHasItems()) {
				menuItems.push({ id: 'paste', label: 'Paste', icon: 'bi-clipboard-check' });
			}
			menuItems.push({ type: 'separator', id: '', label: '' });
			if (isSingleSelection) {
				menuItems.push({ id: 'rename', label: 'Rename', icon: 'bi-pencil' });
			}
			// Lock/Unlock options
			if (hasUnlocked) {
				menuItems.push({ id: 'lock', label: 'Lock', icon: 'bi-lock' });
			}
			if (hasLocked) {
				menuItems.push({ id: 'unlock', label: 'Unlock', icon: 'bi-unlock' });
			}
			// Referenceable options (only for files, not folders; exclude versionable from disable)
			if (hasNonReferenceableFile) {
				menuItems.push({ id: 'enable-referenceable', label: 'Enable Referenceable', icon: 'bi-link-45deg' });
			}
			if (hasReferenceableNonVersionable) {
				menuItems.push({ id: 'disable-referenceable', label: 'Disable Referenceable', icon: 'bi-link' });
			}
			// Version control options (files only, not folders)
			if (hasNonVersionableFile) {
				menuItems.push({ id: 'enable-version-control', label: 'Enable Version Control', icon: 'bi-clock-history' });
			}
			if (hasCheckedInFile) {
				menuItems.push({ id: 'checkout', label: 'Checkout', icon: 'bi-box-arrow-up-right' });
			}
			if (hasCheckedOutFile) {
				menuItems.push({ id: 'checkin', label: 'Checkin', icon: 'bi-box-arrow-in-down-left' });
				menuItems.push({ id: 'checkpoint', label: 'Checkpoint', icon: 'bi-save' });
			}
			if (hasCheckedOutNonRootFile) {
				menuItems.push({ id: 'uncheckout', label: 'Cancel Checkout', icon: 'bi-x-circle' });
			}
			// Version History: show for versionable files (single selection only)
			const hasVersionableFile = selectedItemObjects.some((i: any) => i.isVersionable && !i.isCollection);
			if (isSingleSelection && hasVersionableFile) {
				menuItems.push({ id: 'version-history', label: 'Version History', icon: 'bi-clock-history' });
			}
			// Permissions (single selection only)
			if (isSingleSelection) {
				menuItems.push({ id: 'permissions', label: 'Permissions', icon: 'bi-shield-lock' });
			}
			menuItems.push({ type: 'separator', id: '', label: '' });
			menuItems.push({ id: 'delete', label: 'Delete', icon: 'bi-trash', danger: true });

			// iframeの位置を計算してスクリーン座標に変換
			const iframeRect = document.body.getBoundingClientRect();
			const screenX = event.clientX;
			const screenY = event.clientY;

			// 親ウィンドウにコンテキストメニュー表示をリクエスト
			window.parent.postMessage({
				type: 'show-context-menu',
				x: screenX,
				y: screenY,
				items: menuItems,
				sourceAppId: vm.instance?.id
			}, window.location.origin);
		},
		// Context menu for empty area (background)
		onBackgroundContextMenu(event: MouseEvent) {
			const vm = this;
			// Only handle if click was on empty area (not on item row)
			const target = event.target as HTMLElement;
			if (target.closest('tr.item')) {
				return;
			}
			event.preventDefault();

			// Clear selection when right-clicking on background
			vm.clearSelection();

			// Build context menu with "New Folder", "New File", and "Paste" options
			const menuItems: { id: string; label: string; icon?: string; danger?: boolean; type?: string }[] = [];
			menuItems.push({ id: 'new-folder', label: 'New Folder', icon: 'bi-folder-plus' });
			menuItems.push({ id: 'new-file', label: 'New File', icon: 'bi-file-earmark-plus' });
			if (vm.clipboardHasItems()) {
				menuItems.push({ type: 'separator', id: '', label: '' });
				menuItems.push({ id: 'paste', label: 'Paste', icon: 'bi-clipboard-check' });
			}

			const screenX = event.clientX;
			const screenY = event.clientY;

			window.parent.postMessage({
				type: 'show-context-menu',
				x: screenX,
				y: screenY,
				items: menuItems,
				sourceAppId: vm.instance?.id
			}, window.location.origin);
		},
		handleContextMenuAction(action: string) {
			const vm = this;
			const selectedItemObjects = vm.items.filter((i: any) => vm.selectedItems.includes(i.id));

			switch (action) {
				case 'new-folder':
					vm.showNewFolderDialog();
					break;
				case 'new-file':
					vm.showNewFileDialog();
					break;
				case 'copy':
					vm.clipboardCopy();
					break;
				case 'cut':
					vm.clipboardCut();
					break;
				case 'paste':
					vm.clipboardPaste();
					break;
				case 'open':
					if (selectedItemObjects.length === 1 && selectedItemObjects[0].isCollection) {
						vm.load(selectedItemObjects[0].path);
					}
					break;
				case 'download':
					for (const item of selectedItemObjects) {
						if (!item.isCollection && item.downloadURL) {
							vm.downloadFile(item.downloadURL, item.name);
						}
					}
					break;
				case 'rename':
					if (selectedItemObjects.length === 1) {
						vm.showRenameDialog(selectedItemObjects[0]);
					}
					break;
				case 'lock':
					vm.lockItems(selectedItemObjects.filter((i: any) => !i.isLocked));
					break;
				case 'unlock':
					vm.unlockItems(selectedItemObjects.filter((i: any) => i.isLocked));
					break;
				case 'enable-referenceable':
					vm.enableReferenceable(selectedItemObjects.filter((i: any) => !i.isReferenceable && !i.isCollection));
					break;
				case 'disable-referenceable':
					vm.disableReferenceable(selectedItemObjects.filter((i: any) => i.isReferenceable && !i.isVersionable));
					break;
				case 'enable-version-control':
					vm.enableVersionControl(selectedItemObjects.filter((i: any) => !i.isVersionable && !i.isCollection));
					break;
				case 'checkout':
					vm.checkoutItems(selectedItemObjects.filter((i: any) => i.isVersionable && !i.isCheckedOut && !i.isCollection));
					break;
				case 'checkin':
					vm.checkinItems(selectedItemObjects.filter((i: any) => i.isCheckedOut && !i.isCollection));
					break;
				case 'checkpoint':
					vm.checkpointItems(selectedItemObjects.filter((i: any) => i.isCheckedOut && !i.isCollection));
					break;
				case 'uncheckout':
					vm.uncheckoutItems(selectedItemObjects.filter((i: any) => i.isCheckedOut && !i.isCollection));
					break;
				case 'version-history':
					if (selectedItemObjects.length === 1 && selectedItemObjects[0].isVersionable) {
						if (!vm.detailPanelVisible) {
							vm.detailPanelVisible = true;
							vm.persistDetailPanelState();
						}
						vm.showDetailVersionHistory();
					}
					break;
				case 'permissions':
					if (selectedItemObjects.length === 1) {
						if (!vm.detailPanelVisible) {
							vm.detailPanelVisible = true;
							vm.persistDetailPanelState();
						}
						vm.showDetailACLEditor();
					}
					break;
				case 'delete':
					if (selectedItemObjects.length > 0) {
						vm.showDeleteDialog(selectedItemObjects);
					}
					break;
			}
		},
		async lockItems(items: ContentItem[]) {
			const vm = this;
			if (items.length === 0) return;

			vm.errorMessage = '';

			try {
				const contentService = vm.instance.api.content;
				for (const item of items) {
					await contentService.lockNode(item.path);
				}
				// Reload the current directory to reflect the changes
				await vm.load(vm.currentPath);
			} catch (error: any) {
				vm.errorMessage = error?.message || 'Failed to lock';
			}
		},
		async unlockItems(items: ContentItem[]) {
			const vm = this;
			if (items.length === 0) return;

			vm.errorMessage = '';

			try {
				const contentService = vm.instance.api.content;
				for (const item of items) {
					await contentService.unlockNode(item.path);
				}
				// Reload the current directory to reflect the changes
				await vm.load(vm.currentPath);
			} catch (error: any) {
				vm.errorMessage = error?.message || 'Failed to unlock';
			}
		},
		// =====================================================================
		// Clipboard operations (copy / cut / paste)
		// =====================================================================
		_setSharedClipboard(mode: 'copy' | 'cut', items: { path: string; name: string; isCollection: boolean }[]) {
			const data = { mode, items };
			this.clipboard = data;
			try {
				(window.parent as any).__webtopClipboard = data;
			} catch (_) { /* cross-origin safety */ }
		},
		_getSharedClipboard(): { mode: 'copy' | 'cut' | null; items: { path: string; name: string; isCollection: boolean }[] } {
			// Prefer parent window clipboard (shared across Content Browsers)
			try {
				const shared = (window.parent as any).__webtopClipboard;
				if (shared?.mode && shared?.items?.length > 0) return shared;
			} catch (_) { /* cross-origin safety */ }
			return this.clipboard;
		},
		_clearSharedClipboard() {
			this.clipboard = { mode: null, items: [] };
			try {
				(window.parent as any).__webtopClipboard = null;
			} catch (_) { /* cross-origin safety */ }
		},
		clipboardCopy() {
			const vm = this;
			const selected = vm.items.filter((i: any) => vm.selectedItems.includes(i.id));
			if (selected.length === 0) return;
			vm._setSharedClipboard('copy', selected.map((i: any) => ({ path: i.path, name: i.name, isCollection: i.isCollection })));
		},
		clipboardCut() {
			const vm = this;
			const selected = vm.items.filter((i: any) => vm.selectedItems.includes(i.id));
			if (selected.length === 0) return;
			vm._setSharedClipboard('cut', selected.map((i: any) => ({ path: i.path, name: i.name, isCollection: i.isCollection })));
		},
		clipboardHasItems(): boolean {
			const cb = this._getSharedClipboard();
			return cb.mode !== null && cb.items.length > 0;
		},
		isClipboardCutItem(path: string): boolean {
			const cb = this._getSharedClipboard();
			return cb.mode === 'cut' && cb.items.some((i: any) => i.path === path);
		},
		async clipboardPaste() {
			const vm = this;
			const cb = vm._getSharedClipboard();
			if (!cb.mode || cb.items.length === 0) return;

			const contentService = vm.instance.api.content;
			const mode = cb.mode;
			const items = [...cb.items];
			vm.errorMessage = '';

			// Set up uploadMonitor so the conflict dialog can be shown
			vm.uploadMonitor = {
				isCanceled: false,
				target: { currentFile: '', progressPercent: 0 },
				cancel() { this.isCanceled = true; },
			};

			let overwriteAll = false;
			let skipAll = false;

			try {
				for (const item of items) {
					if (vm.uploadMonitor.isCanceled) break;
					vm.uploadMonitor.target.currentFile = item.name;
					vm.uploadMonitor.target.progressPercent = 0;

					const destPath = vm.currentPath;
					const sourceParent = item.path.substring(0, item.path.lastIndexOf('/'));

					// Prevent pasting into itself or a descendant (for move)
					if (mode === 'cut') {
						if (destPath === item.path || destPath.startsWith(item.path + '/')) {
							vm.errorMessage = `Cannot move "${item.name}" into itself`;
							continue;
						}
						// Skip if source parent is the same as dest (no-op move)
						if (sourceParent === destPath) continue;
					}

					// Copy into the same directory → duplicate with auto-generated name
					if (mode === 'copy' && sourceParent === destPath) {
						const copyName = await vm._generateCopyName(item.name, destPath, contentService);
						await contentService.copyNode(item.path, destPath, copyName);
						continue;
					}

					// Check for name collision
					const targetNodePath = destPath + '/' + item.name;
					const existing = await contentService.getNode(targetNodePath).catch(() => null);

					if (existing) {
						let action = overwriteAll ? 'overwrite' : skipAll ? 'skip' : await vm.askConflict();
						if (action === 'overwriteAll') { overwriteAll = true; action = 'overwrite'; }
						if (action === 'skipAll') { skipAll = true; action = 'skip'; }
						if (action === 'cancel') { vm.uploadMonitor.cancel(); break; }
						if (action === 'skip') continue;
						// Overwrite: delete existing first
						await contentService.deleteNode(targetNodePath);
					}

					if (mode === 'copy') {
						await contentService.copyNode(item.path, destPath);
					} else {
						await contentService.moveNode(item.path, destPath);
					}
				}

				// Clear clipboard after cut-paste (but not after copy-paste)
				if (mode === 'cut') {
					vm._clearSharedClipboard();
				}

				await vm.load(vm.currentPath);
			} catch (error: any) {
				if (!vm.uploadMonitor?.isCanceled) {
					vm.errorMessage = error?.message || String(error) || 'Paste failed';
				}
			} finally {
				if (!vm.errorMessage) {
					vm.uploadMonitor = null;
				}
			}
		},
		// =====================================================================
		// Internal drag-and-drop (move/copy between folders)
		// =====================================================================
		onInternalDragStart(item: ContentItem, event: DragEvent) {
			const vm = this;
			if (!event.dataTransfer) return;
			event.dataTransfer.effectAllowed = 'copyMove';

			// If the dragged item is selected, include all selected items
			let dragItems: ContentItem[];
			if (vm.selectedItems.includes(item.id)) {
				dragItems = vm.items.filter((i: any) => vm.selectedItems.includes(i.id));
			} else {
				dragItems = [item];
			}

			const payload = dragItems.map((i: any) => ({
				path: i.path,
				name: i.name,
				isCollection: i.isCollection,
				mimeType: i.mimeType,
				uuid: i.isReferenceable ? i.id : undefined,
				isReferenceable: i.isReferenceable,
				downloadURL: i.downloadURL ? i.downloadURL.replace(/[?&]attachment$/, '') : undefined,
			}));

			event.dataTransfer.setData('application/x-webtop-files', JSON.stringify(payload));
			// Keep single-file compatibility for cross-app drag
			if (payload.length === 1 && !payload[0].isCollection) {
				event.dataTransfer.setData('application/x-webtop-file', JSON.stringify(payload[0]));
			}
			event.dataTransfer.setData('text/plain', dragItems.map((i: any) => i.path).join('\n'));

			// Store drag data on parent window for cross-iframe drag-and-drop
			try {
				(window.parent as any).__webtopDragData = {
					items: payload,
					sourceAppID: vm.instance?.id,
					sourceFolderPath: vm.currentPath,
				};
			} catch (_) { /* cross-origin safety */ }
		},
		onInternalDragEnd() {
			// Clear parent window drag data
			try {
				(window.parent as any).__webtopDragData = null;
			} catch (_) { /* cross-origin safety */ }
			this.dragOverFolderID = null;
		},
		/**
		 * Generate a unique copy name like "file - Copy.txt", "file - Copy (2).txt", etc.
		 */
		async _generateCopyName(
			originalName: string,
			destPath: string,
			contentService: any,
		): Promise<string> {
			const dotIdx = originalName.lastIndexOf('.');
			const baseName = dotIdx > 0 ? originalName.substring(0, dotIdx) : originalName;
			const ext = dotIdx > 0 ? originalName.substring(dotIdx) : '';

			// Try "name - Copy.ext" first
			let candidate = `${baseName} - Copy${ext}`;
			let existing = await contentService.getNode(`${destPath}/${candidate}`).catch(() => null);
			if (!existing) return candidate;

			// Try "name - Copy (2).ext", "name - Copy (3).ext", ...
			for (let i = 2; i <= 100; i++) {
				candidate = `${baseName} - Copy (${i})${ext}`;
				existing = await contentService.getNode(`${destPath}/${candidate}`).catch(() => null);
				if (!existing) return candidate;
			}

			// Fallback: timestamp-based name
			return `${baseName} - Copy (${Date.now()})${ext}`;
		},
		_hasInternalDragData(event: DragEvent): boolean {
			if (event.dataTransfer?.types.includes('application/x-webtop-files')) return true;
			// Cross-iframe: check parent window drag data
			try {
				if ((window.parent as any).__webtopDragData) return true;
			} catch (_) { /* cross-origin safety */ }
			return false;
		},
		/**
		 * Check if the current drag should be forbidden for the given destination.
		 * Forbidden when:
		 *   - it's a move (no Ctrl) and the destination is the same folder the
		 *     items already live in (no-op);
		 *   - the destination equals one of the dragged folders or any of their
		 *     descendants (would create a cycle).
		 * destPath defaults to the current folder, which is the implicit
		 * destination for background drops.
		 */
		_isDragForbidden(event: DragEvent, destPath?: string): boolean {
			try {
				const parentData = (window.parent as any).__webtopDragData;
				if (!parentData) return false;
				const dest = destPath ?? this.currentPath;
				// Move into the source folder is a no-op
				if (!event.ctrlKey && parentData.sourceFolderPath === dest) {
					return true;
				}
				// Drop into self or a descendant of a dragged folder would cycle
				if (Array.isArray(parentData.items)) {
					for (const it of parentData.items) {
						if (it && it.isCollection && it.path
							&& (dest === it.path || dest.startsWith(it.path + '/'))) {
							return true;
						}
					}
				}
			} catch (_) { /* cross-origin safety */ }
			return false;
		},
		_getInternalDragItems(event: DragEvent): { path: string; name: string; isCollection: boolean }[] | null {
			// Try DataTransfer first (same-iframe)
			const raw = event.dataTransfer?.getData('application/x-webtop-files');
			if (raw) {
				try { return JSON.parse(raw); } catch (_) {}
			}
			// Fall back to parent window data (cross-iframe)
			try {
				const parentData = (window.parent as any).__webtopDragData;
				if (parentData?.items) return parentData.items;
			} catch (_) { /* cross-origin safety */ }
			return null;
		},
		onItemDragOver(item: ContentItem, event: DragEvent) {
			// File rows have no drop semantics of their own — let the event
			// bubble so the parent's onDragOver treats it as a background drop.
			if (!item.isCollection) return;
			if (!this._hasInternalDragData(event)) return;
			// Folder rows own this dragover. Stop propagation so the parent's
			// onDragOver does not re-evaluate forbidden against currentPath
			// and overwrite our dropEffect.
			event.stopPropagation();
			if (event.dataTransfer) {
				if (this._isDragForbidden(event, item.path)) {
					event.dataTransfer.dropEffect = 'none';
					if (this.dragOverFolderID === item.id) this.dragOverFolderID = null;
					return;
				}
				event.dataTransfer.dropEffect = event.ctrlKey ? 'copy' : 'move';
			}
			this.dragOverFolderID = item.id;
		},
		onItemDragLeave(item: ContentItem) {
			if (this.dragOverFolderID === item.id) {
				this.dragOverFolderID = null;
			}
		},
		async onItemDrop(item: ContentItem, event: DragEvent) {
			const vm = this;
			vm.dragOverFolderID = null;
			if (!item.isCollection) return;

			const dragItems = vm._getInternalDragItems(event);
			if (!dragItems || dragItems.length === 0) return;
			if (vm._isDragForbidden(event, item.path)) return;
			// Stop propagation to prevent parent onDrop from interfering
			event.stopPropagation();

			await vm._executeInternalDrop(dragItems, item.path, event.ctrlKey);
		},
		/**
		 * Execute internal move/copy of drag items into destPath.
		 * Shared by onItemDrop (folder row) and onDrop (background / cross-iframe).
		 */
		async _executeInternalDrop(
			dragItems: { path: string; name: string; isCollection: boolean }[],
			destPath: string,
			isCopy: boolean,
		) {
			const vm = this;
			const contentService = vm.instance.api.content;
			vm.errorMessage = '';

			// Set up uploadMonitor for conflict dialog
			vm.uploadMonitor = {
				isCanceled: false,
				target: { currentFile: '', progressPercent: 0 },
				cancel() { this.isCanceled = true; },
			};

			let overwriteAll = false;
			let skipAll = false;

			try {
				for (const dragItem of dragItems) {
					if (vm.uploadMonitor.isCanceled) break;
					vm.uploadMonitor.target.currentFile = dragItem.name;

					const sourceParent = dragItem.path.substring(0, dragItem.path.lastIndexOf('/'));

					// Cyclic prevention: cannot drop folder into itself or descendant
					if (dragItem.isCollection && (destPath === dragItem.path || destPath.startsWith(dragItem.path + '/'))) {
						continue;
					}
					// Skip no-op: source parent is same as dest folder (move only)
					if (!isCopy && sourceParent === destPath) continue;

					// Copy into same directory → duplicate with auto-generated name
					if (isCopy && sourceParent === destPath) {
						const copyName = await vm._generateCopyName(dragItem.name, destPath, contentService);
						await contentService.copyNode(dragItem.path, destPath, copyName);
						continue;
					}

					// Check collision
					const targetNodePath = destPath + '/' + dragItem.name;
					const existing = await contentService.getNode(targetNodePath).catch(() => null);
					if (existing) {
						let action = overwriteAll ? 'overwrite' : skipAll ? 'skip' : await vm.askConflict();
						if (action === 'overwriteAll') { overwriteAll = true; action = 'overwrite'; }
						if (action === 'skipAll') { skipAll = true; action = 'skip'; }
						if (action === 'cancel') { vm.uploadMonitor.cancel(); break; }
						if (action === 'skip') continue;
						await contentService.deleteNode(targetNodePath);
					}

					if (isCopy) {
						await contentService.copyNode(dragItem.path, destPath);
					} else {
						await contentService.moveNode(dragItem.path, destPath);
					}
				}

				await vm.load(vm.currentPath);
			} catch (error: any) {
				if (!vm.uploadMonitor?.isCanceled) {
					vm.errorMessage = error?.message || String(error) || 'Operation failed';
				}
			} finally {
				if (!vm.errorMessage) {
					vm.uploadMonitor = null;
				}
			}

			// Clear parent drag data after drop completes
			try {
				(window.parent as any).__webtopDragData = null;
			} catch (_) { /* cross-origin safety */ }
		},
		async enableReferenceable(items: ContentItem[]) {
			const vm = this;
			if (items.length === 0) return;

			vm.errorMessage = '';

			try {
				const contentService = vm.instance.api.content;
				for (const item of items) {
					await contentService.addMixin(item.path, 'mix:referenceable');
				}
				// Reload the current directory to reflect the changes
				await vm.load(vm.currentPath);
			} catch (error: any) {
				vm.errorMessage = error?.message || 'Failed to enable referenceable';
			}
		},
		async disableReferenceable(items: ContentItem[]) {
			const vm = this;
			if (items.length === 0) return;

			vm.errorMessage = '';

			try {
				const contentService = vm.instance.api.content;
				for (const item of items) {
					await contentService.deleteMixin(item.path, 'mix:referenceable');
				}
				// Reload the current directory to reflect the changes
				await vm.load(vm.currentPath);
			} catch (error: any) {
				vm.errorMessage = error?.message || 'Failed to disable referenceable';
			}
		},
		async enableVersionControl(items: ContentItem[]) {
			const vm = this;
			if (items.length === 0) return;

			vm.errorMessage = '';

			try {
				const contentService = vm.instance.api.content;
				for (const item of items) {
					await contentService.addVersionControl(item.path);
				}
				// Reload the current directory to reflect the changes
				await vm.load(vm.currentPath);
			} catch (error: any) {
				vm.errorMessage = error?.message || 'Failed to enable version control';
			}
		},
		async checkoutItems(items: ContentItem[]) {
			const vm = this;
			if (items.length === 0) return;

			vm.errorMessage = '';

			try {
				const contentService = vm.instance.api.content;
				for (const item of items) {
					await contentService.checkout(item.path);
				}
				// Reload the current directory to reflect the changes
				await vm.load(vm.currentPath);
			} catch (error: any) {
				vm.errorMessage = error?.message || 'Failed to checkout';
			}
		},
		async checkinItems(items: ContentItem[]) {
			const vm = this;
			if (items.length === 0) return;

			vm.errorMessage = '';

			try {
				const contentService = vm.instance.api.content;
				for (const item of items) {
					await contentService.checkin(item.path);
				}
				await vm.load(vm.currentPath);
				if (vm.detailVersionHistoryVisible) {
					await vm.refreshDetailVersionHistory();
				}
			} catch (error: any) {
				vm.errorMessage = error?.message || 'Failed to checkin';
			}
		},
		async checkpointItems(items: ContentItem[]) {
			const vm = this;
			if (items.length === 0) return;

			vm.errorMessage = '';

			try {
				const contentService = vm.instance.api.content;
				for (const item of items) {
					await contentService.checkpoint(item.path);
				}
				await vm.load(vm.currentPath);
				if (vm.detailVersionHistoryVisible) {
					await vm.refreshDetailVersionHistory();
				}
			} catch (error: any) {
				vm.errorMessage = error?.message || 'Failed to create checkpoint';
			}
		},
		async uncheckoutItems(items: ContentItem[]) {
			const vm = this;
			if (items.length === 0) return;

			vm.errorMessage = '';

			try {
				const contentService = vm.instance.api.content;
				for (const item of items) {
					await contentService.uncheckout(item.path);
				}
				// Reload the current directory to reflect the changes
				await vm.load(vm.currentPath);
			} catch (error: any) {
				vm.errorMessage = error?.message || 'Failed to cancel checkout';
			}
		},
		// Rename dialog methods
		showRenameDialog(item: ContentItem) {
			const vm = this;
			vm.renameDialog.item = item;
			vm.renameDialog.newName = item.name;
			vm.renameDialog.errorMessage = '';
			vm.renameDialog.isLoading = false;
			vm.renameDialog.visible = true;
			// Focus input after dialog is shown
			vm.$nextTick(() => {
				const input = document.querySelector('.rename-dialog input') as HTMLInputElement;
				if (input) {
					input.focus();
					// Select filename without extension
					const lastDot = item.name.lastIndexOf('.');
					if (lastDot > 0 && !item.isCollection) {
						input.setSelectionRange(0, lastDot);
					} else {
						input.select();
					}
				}
			});
		},
		closeRenameDialog() {
			const vm = this;
			vm.renameDialog.visible = false;
			vm.renameDialog.item = null;
			vm.renameDialog.newName = '';
			vm.renameDialog.errorMessage = '';
			vm.renameDialog.isLoading = false;
		},
		async submitRename() {
			const vm = this;
			const item = vm.renameDialog.item;
			if (!item) return;

			const newName = vm.renameDialog.newName.trim();
			if (!newName) {
				vm.renameDialog.errorMessage = 'Name cannot be empty';
				return;
			}
			if (newName === item.name) {
				vm.closeRenameDialog();
				return;
			}

			vm.renameDialog.isLoading = true;
			vm.renameDialog.errorMessage = '';

			try {
				// Use GraphQL API to rename the node
				const contentService = vm.instance.api.content;
				await contentService.renameNode(item.path, newName);
				vm.closeRenameDialog();
				// Reload the current directory to reflect the change
				await vm.load(vm.currentPath);
			} catch (error: any) {
				vm.renameDialog.errorMessage = error?.message || 'Failed to rename';
			} finally {
				vm.renameDialog.isLoading = false;
			}
		},
		onRenameKeydown(event: KeyboardEvent) {
			const vm = this;
			if (event.key === 'Enter') {
				event.preventDefault();
				vm.submitRename();
			} else if (event.key === 'Escape') {
				event.preventDefault();
				vm.closeRenameDialog();
			}
		},
		// Delete dialog methods
		showDeleteDialog(items: ContentItem[]) {
			if (items.length === 0) return;
			const vm = this;
			vm.deleteDialog.items = items;
			vm.deleteDialog.errorMessage = '';
			vm.deleteDialog.isLoading = false;
			vm.deleteDialog.visible = true;
		},
		closeDeleteDialog() {
			const vm = this;
			vm.deleteDialog.visible = false;
			vm.deleteDialog.items = [];
			vm.deleteDialog.errorMessage = '';
			vm.deleteDialog.isLoading = false;
		},
		async submitDelete() {
			const vm = this;
			const items = vm.deleteDialog.items;
			if (items.length === 0) return;

			const contentService = vm.instance.api.content;
			const eventHub = vm.instance?.api?.eventHub;

			vm.deleteDialog.isLoading = true;
			vm.deleteDialog.errorMessage = '';

			try {
				const result = await deleteContentItems(
					contentService,
					eventHub,
					items.map((it: ContentItem) => ({
						path: it.path,
						isCollection: it.isCollection,
						hasChildren: it.hasChildren,
					})),
					{
						onStart: (handle: DeleteJobHandle) => {
							vm.deleteMonitor = {
								jobId: handle.jobId,
								handle,
								itemsTotal: items.length,
								itemsProcessed: 0,
								nodesDeleted: 0,
								currentPath: '',
								status: 'init' as JobStatus,
								errorMessage: '',
								isFinished: false,
								isAborting: false,
								targetPath: vm.currentPath,
							};
							vm.closeDeleteDialog();
						},
						onProgress: (progress: DeleteJobProgress) => {
							vm.handleJobProgress(progress);
						},
					},
				);

				if (result.sync) {
					vm.closeDeleteDialog();
					await vm.load(vm.currentPath);
				}
			} catch (error: any) {
				if (vm.deleteMonitor) {
					vm.deleteMonitor.errorMessage = error?.message || 'Failed to start delete';
					vm.deleteMonitor.isFinished = true;
				} else {
					vm.deleteDialog.errorMessage = error?.message || 'Failed to delete';
				}
			} finally {
				vm.deleteDialog.isLoading = false;
			}
		},
		handleJobProgress(progress: DeleteJobProgress) {
			const vm = this;
			const m = vm.deleteMonitor;
			if (!m || m.jobId !== progress.jobId) return;
			m.status = progress.status;
			m.itemsTotal = progress.itemsTotal;
			m.itemsProcessed = progress.itemsProcessed;
			m.nodesDeleted = progress.nodesDeleted;
			if (progress.currentPath) m.currentPath = progress.currentPath;
			if (progress.errorMessage) m.errorMessage = progress.errorMessage;
			if (progress.status === 'aborting') m.isAborting = true;

			if (progress.status === 'completed' || progress.status === 'aborted' || progress.status === 'failed') {
				m.isFinished = true;
				m.isAborting = false;
				// Refresh the directory listing to reflect the deletions
				vm.load(vm.currentPath);
				// Auto-close on success/abort so the monitor doesn't linger.
				// Failures stay open so the user has time to read errorMessage.
				if (progress.status !== 'failed') {
					const closingJobId = m.jobId;
					setTimeout(() => {
						if (vm.deleteMonitor && vm.deleteMonitor.jobId === closingJobId) {
							vm.closeDeleteMonitor();
						}
					}, 1500);
				}
			}
		},
		requestDeleteAbort() {
			const vm = this;
			const m = vm.deleteMonitor;
			if (!m || m.isFinished || m.isAborting) return;
			m.isAborting = true;
			m.handle.abort();
		},
		closeDeleteMonitor() {
			const vm = this;
			if (vm.deleteMonitor) {
				try { vm.deleteMonitor.handle.release(); } catch { /* noop */ }
			}
			vm.deleteMonitor = null;
		},
		onDeleteKeydown(event: KeyboardEvent) {
			const vm = this;
			if (event.key === 'Enter') {
				event.preventDefault();
				vm.submitDelete();
			} else if (event.key === 'Escape') {
				event.preventDefault();
				vm.closeDeleteDialog();
			}
		},
		// New Folder dialog methods
		showNewFolderDialog() {
			const vm = this;
			vm.newFolderDialog.name = '';
			vm.newFolderDialog.errorMessage = '';
			vm.newFolderDialog.isLoading = false;
			vm.newFolderDialog.visible = true;
			// Focus input after dialog is shown
			vm.$nextTick(() => {
				const input = document.querySelector('.new-folder-dialog input') as HTMLInputElement;
				if (input) {
					input.focus();
				}
			});
		},
		closeNewFolderDialog() {
			const vm = this;
			vm.newFolderDialog.visible = false;
			vm.newFolderDialog.name = '';
			vm.newFolderDialog.errorMessage = '';
			vm.newFolderDialog.isLoading = false;
		},
		async submitNewFolder() {
			const vm = this;
			const name = vm.newFolderDialog.name.trim();

			if (!name) {
				vm.newFolderDialog.errorMessage = 'Folder name cannot be empty';
				return;
			}

			vm.newFolderDialog.isLoading = true;
			vm.newFolderDialog.errorMessage = '';

			try {
				const contentService = vm.instance.api.content;
				await contentService.createFolder(vm.currentPath, name);
				vm.closeNewFolderDialog();
				// Reload the current directory to show the new folder
				await vm.load(vm.currentPath);
			} catch (error: any) {
				vm.newFolderDialog.errorMessage = error?.message || 'Failed to create folder';
			} finally {
				vm.newFolderDialog.isLoading = false;
			}
		},
		onNewFolderKeydown(event: KeyboardEvent) {
			const vm = this;
			if (event.key === 'Enter') {
				event.preventDefault();
				// Only submit if name is valid and doesn't exist
				if (vm.newFolderDialog.name.trim() && !vm.newFolderNameExists) {
					vm.submitNewFolder();
				}
			} else if (event.key === 'Escape') {
				event.preventDefault();
				vm.closeNewFolderDialog();
			}
		},
		// New File dialog methods
		showNewFileDialog() {
			const vm = this;
			vm.newFileDialog.name = '';
			vm.newFileDialog.fileType = 'text';
			vm.newFileDialog.errorMessage = '';
			vm.newFileDialog.isLoading = false;
			vm.newFileDialog.visible = true;
			// Focus input after dialog is shown
			vm.$nextTick(() => {
				const input = document.querySelector('.new-file-dialog input[type="text"]') as HTMLInputElement;
				if (input) {
					input.focus();
				}
			});
		},
		closeNewFileDialog() {
			const vm = this;
			vm.newFileDialog.visible = false;
			vm.newFileDialog.name = '';
			vm.newFileDialog.fileType = 'text';
			vm.newFileDialog.errorMessage = '';
			vm.newFileDialog.isLoading = false;
		},
		getSelectedFileType() {
			const vm = this;
			return vm.fileTypeOptions.find((opt: any) => opt.id === vm.newFileDialog.fileType) || vm.fileTypeOptions[0];
		},
		async submitNewFile() {
			const vm = this;
			let name = vm.newFileDialog.name.trim();

			if (!name) {
				vm.newFileDialog.errorMessage = 'File name cannot be empty';
				return;
			}

			const fileType = vm.getSelectedFileType();

			// Add extension if not present
			if (!name.toLowerCase().endsWith(fileType.extension.toLowerCase())) {
				name += fileType.extension;
			}

			vm.newFileDialog.isLoading = true;
			vm.newFileDialog.errorMessage = '';

			try {
				const contentService = vm.instance.api.content;
				// Create empty file with specified MIME type (empty content as base64)
				await contentService.createFile(vm.currentPath, name, fileType.mimeType, '');
				vm.closeNewFileDialog();
				// Reload the current directory to show the new file
				await vm.load(vm.currentPath);
			} catch (error: any) {
				vm.newFileDialog.errorMessage = error?.message || 'Failed to create file';
			} finally {
				vm.newFileDialog.isLoading = false;
			}
		},
		onNewFileKeydown(event: KeyboardEvent) {
			const vm = this;
			if (event.key === 'Enter') {
				event.preventDefault();
				// Only submit if name is valid and doesn't exist
				if (vm.newFileDialog.name.trim() && !vm.newFileNameExists) {
					vm.submitNewFile();
				}
			} else if (event.key === 'Escape') {
				event.preventDefault();
				vm.closeNewFileDialog();
			}
		},
		// Version History dialog methods
		async showVersionHistoryDialog(item: ContentItem) {
			const vm = this;
			vm.versionHistoryDialog.item = item;
			vm.versionHistoryDialog.versions = [];
			vm.versionHistoryDialog.baseVersionName = '';
			vm.versionHistoryDialog.errorMessage = '';
			vm.versionHistoryDialog.isLoading = true;
			vm.versionHistoryDialog.visible = true;

			try {
				const contentService = vm.instance.api.content;
				const history = await contentService.getVersionHistory(item.path);
				if (history) {
					// Extract versions from edges format and reverse to show newest first
					vm.versionHistoryDialog.versions = (history.edges || []).map((edge: any) => edge.node).reverse();
					vm.versionHistoryDialog.baseVersionName = history.baseVersion?.name || '';
				}
			} catch (error: any) {
				vm.versionHistoryDialog.errorMessage = error?.message || 'Failed to load version history';
			} finally {
				vm.versionHistoryDialog.isLoading = false;
			}
		},
		closeVersionHistoryDialog() {
			const vm = this;
			vm.versionHistoryDialog.visible = false;
			vm.versionHistoryDialog.item = null;
			vm.versionHistoryDialog.versions = [];
			vm.versionHistoryDialog.baseVersionName = '';
			vm.versionHistoryDialog.errorMessage = '';
			vm.versionHistoryDialog.isLoading = false;
		},
		formatVersionDate(dateString: string) {
			const vm = this;
			if (!dateString) return '';
			const date = new Date(dateString);
			return vm.instance.util.dates.format(date, { format: 'friendly' });
		},
		isCurrentVersion(versionName: string) {
			const vm = this;
			return versionName === vm.versionHistoryDialog.baseVersionName;
		},
		async restoreVersion(versionName: string) {
			const vm = this;
			const item = vm.versionHistoryDialog.item;
			if (!item || !versionName) return;

			vm.versionHistoryDialog.isLoading = true;
			vm.versionHistoryDialog.errorMessage = '';

			try {
				const contentService = vm.instance.api.content;
				await contentService.restoreVersion(item.path, versionName);
				await vm.load(vm.currentPath);
				// Refresh version history to show updated state
				if (vm.detailVersionHistoryVisible) {
					await vm.refreshDetailVersionHistory();
				} else {
					vm.closeVersionHistoryDialog();
				}
			} catch (error: any) {
				vm.versionHistoryDialog.errorMessage = error?.message || 'Failed to restore version';
				vm.versionHistoryDialog.isLoading = false;
			}
		},
		onVersionHistoryKeydown(event: KeyboardEvent) {
			const vm = this;
			if (event.key === 'Escape') {
				event.preventDefault();
				vm.closeVersionHistoryDialog();
			}
		},
		// =====================================================================
		// ACL (Permissions) Dialog
		// =====================================================================
		async showAclDialog(item: any) {
			const vm = this;
			vm.aclDialog.item = item;
			vm.aclDialog.visible = true;
			vm.aclDialog.isLoading = true;
			vm.aclDialog.errorMessage = '';
			vm.aclDialog.effectivePolicies = [];
			vm.aclDialog.pendingEntries = [];
			vm.aclDialog.originalEntries = [];

			try {
				const contentService = vm.instance.api.content;
				vm.aclDialog.effectivePolicies = await contentService.getEffectiveAccessControl(item.path);
				vm._syncAclPendingEntries();
			} catch (error: any) {
				vm.aclDialog.errorMessage = error?.message || 'Failed to load access control';
			} finally {
				vm.aclDialog.isLoading = false;
			}
		},
		closeAclDialog() {
			const vm = this;
			vm.aclDialog.visible = false;
			vm.aclDialog.item = null;
			vm.aclDialog.effectivePolicies = [];
			vm.aclDialog.pendingEntries = [];
			vm.aclDialog.originalEntries = [];
			vm.aclDialog.isLoading = false;
			vm.aclDialog.isSaving = false;
			vm.aclDialog.errorMessage = '';
			vm.closeAddAclEntryDialog();
			if (vm.aclSearchDebounceTimer) {
				clearTimeout(vm.aclSearchDebounceTimer);
				vm.aclSearchDebounceTimer = null;
			}
		},
		async refreshAclDialog() {
			const vm = this;
			if (!vm.aclDialog.item) return;
			vm.aclDialog.isLoading = true;
			vm.aclDialog.errorMessage = '';
			try {
				const contentService = vm.instance.api.content;
				vm.aclDialog.effectivePolicies = await contentService.getEffectiveAccessControl(vm.aclDialog.item.path);
				vm._syncAclPendingEntries();
			} catch (error: any) {
				vm.aclDialog.errorMessage = error?.message || 'Failed to refresh access control';
			} finally {
				vm.aclDialog.isLoading = false;
			}
		},
		// Extract current item's own entries into pendingEntries/originalEntries
		_syncAclPendingEntries() {
			const vm = this;
			const item = vm.aclDialog.item;
			const policies = vm.aclDialog.effectivePolicies;
			let ownEntries: any[] = [];
			if (item && policies.length > 0 && policies[0].path === item.path) {
				ownEntries = policies[0].entries || [];
			}
			// Deep copy for both pending (working copy) and original (reference)
			const clone = (entries: any[]) => entries.map(e => ({
				principal: e.principal,
				privileges: [...e.privileges],
				allow: e.allow,
			}));
			vm.aclDialog.pendingEntries = clone(ownEntries);
			vm.aclDialog.originalEntries = clone(ownEntries);
		},
		showAddAclEntryDialog() {
			const vm = this;
			vm.aclDialog.addEntry.visible = true;
			vm.aclDialog.addEntry.allow = true;
			vm.aclDialog.addEntry.principal = '';
			vm.aclDialog.addEntry.principalIsGroup = false;
			vm.aclDialog.addEntry.principalDisplayName = '';
			vm.aclDialog.addEntry.privileges = [];
			vm.aclDialog.addEntry.searchKeyword = '';
			vm.aclDialog.addEntry.searchResults = [];
			vm.aclDialog.addEntry.isSearching = false;
			vm.aclDialog.addEntry.errorMessage = '';
		},
		closeAddAclEntryDialog() {
			const vm = this;
			vm.aclDialog.addEntry.visible = false;
			vm.aclDialog.addEntry.principal = '';
			vm.aclDialog.addEntry.principalDisplayName = '';
			vm.aclDialog.addEntry.privileges = [];
			vm.aclDialog.addEntry.searchKeyword = '';
			vm.aclDialog.addEntry.searchResults = [];
			vm.aclDialog.addEntry.errorMessage = '';
			vm.closePrincipalSuggestionsPopup();
		},
		onAclSearchInput() {
			const vm = this;
			if (vm.aclSearchDebounceTimer) {
				clearTimeout(vm.aclSearchDebounceTimer);
			}
			vm.aclSearchDebounceTimer = setTimeout(() => {
				vm.searchPrincipals();
			}, 300);
		},
		onAclSearchFocus() {
			const vm = this;
			if (vm.aclDialog.addEntry.searchResults.length > 0) {
				vm.refreshPrincipalSuggestionsPopup();
			}
		},
		async searchPrincipals() {
			const vm = this;
			const keyword = vm.aclDialog.addEntry.searchKeyword.trim();
			if (!keyword) {
				vm.aclDialog.addEntry.searchResults = [];
				vm.closePrincipalSuggestionsPopup();
				return;
			}

			vm.aclDialog.addEntry.isSearching = true;
			try {
				const contentService = vm.instance.api.content;
				vm.aclDialog.addEntry.searchResults = await contentService.searchPrincipals(keyword, 0, 20);
				vm.refreshPrincipalSuggestionsPopup();
			} catch (error: any) {
				vm.aclDialog.addEntry.searchResults = [];
				vm.closePrincipalSuggestionsPopup();
			} finally {
				vm.aclDialog.addEntry.isSearching = false;
			}
		},
		buildPrincipalSuggestionItems() {
			const vm = this;
			return (vm.aclDialog.addEntry.searchResults as any[]).map(r => {
				const kindLabel = r.isService
					? (r.isGroup ? 'Service Group' : 'Service User')
					: (r.isGroup ? 'Group' : 'User');
				const description = r.displayName
					? `${kindLabel} · ${r.identifier}`
					: kindLabel;
				return {
					id: r.identifier,
					label: r.displayName || r.identifier,
					description,
					icon: r.isGroup ? 'bi bi-people' : 'bi bi-person',
				};
			});
		},
		refreshPrincipalSuggestionsPopup() {
			const vm = this;
			const results = vm.aclDialog.addEntry.searchResults as any[];
			if (results.length === 0) {
				vm.closePrincipalSuggestionsPopup();
				return;
			}
			const items = vm.buildPrincipalSuggestionItems();
			if (principalPopupHandle) {
				principalPopupHandle.update(items);
				return;
			}
			const input = document.querySelector('.acl-principal-input') as HTMLInputElement | null;
			if (!input || !vm.instance) return;
			const rect = input.getBoundingClientRect();
			principalPopupHandle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				maxHeight: 360,
				items,
			});
			principalPopupHandle.result.then((picked: any) => {
				principalPopupHandle = null;
				if (picked == null) return;
				const match = (vm.aclDialog.addEntry.searchResults as any[])
					.find(r => r.identifier === picked);
				if (match) vm.selectPrincipal(match);
			});
		},
		closePrincipalSuggestionsPopup() {
			if (principalPopupHandle) {
				principalPopupHandle.close();
				principalPopupHandle = null;
			}
		},
		selectPrincipal(principal: { identifier: string; isGroup: boolean; isService?: boolean; displayName?: string | null }) {
			const vm = this;
			vm.aclDialog.addEntry.principal = principal.identifier;
			vm.aclDialog.addEntry.principalIsGroup = principal.isGroup;
			vm.aclDialog.addEntry.principalDisplayName = principal.displayName || '';
			vm.aclDialog.addEntry.searchKeyword = principal.identifier;
			vm.aclDialog.addEntry.searchResults = [];
			vm.closePrincipalSuggestionsPopup();
		},
		togglePrivilege(privilege: string) {
			const vm = this;
			const idx = vm.aclDialog.addEntry.privileges.indexOf(privilege);
			if (idx >= 0) {
				vm.aclDialog.addEntry.privileges.splice(idx, 1);
			} else {
				vm.aclDialog.addEntry.privileges.push(privilege);
			}
		},
		submitAclEntry() {
			const vm = this;
			const { principal, privileges, allow, principalDisplayName } = vm.aclDialog.addEntry;

			if (!principal.trim()) {
				vm.aclDialog.addEntry.errorMessage = 'Principal is required';
				return;
			}
			if (privileges.length === 0) {
				vm.aclDialog.addEntry.errorMessage = 'At least one privilege is required';
				return;
			}

			const id = principal.trim();
			// Add to pending entries locally
			vm.aclDialog.pendingEntries.push({
				principal: { id, displayName: principalDisplayName || null, isGroup: vm.aclDialog.addEntry.principalIsGroup },
				privileges: [...privileges],
				allow,
			});
			vm.closeAddAclEntryDialog();
		},
		deleteAclEntry(principalID: string) {
			const vm = this;
			const idx = vm.aclDialog.pendingEntries.findIndex((e: any) => {
				const id = typeof e.principal === 'object' ? e.principal.id : e.principal;
				return id === principalID;
			});
			if (idx !== -1) {
				vm.aclDialog.pendingEntries.splice(idx, 1);
			}
		},
		async saveAclChanges() {
			const vm = this;
			vm.aclDialog.isSaving = true;
			vm.aclDialog.errorMessage = '';

			try {
				const contentService = vm.instance.api.content;
				const entries = vm.aclDialog.pendingEntries.map((e: any) => ({
					principal: typeof e.principal === 'object' ? e.principal.id : e.principal,
					privileges: e.privileges,
					allow: e.allow,
				}));
				await contentService.setAccessControl(vm.aclDialog.item.path, { entries });
				await vm.refreshAclDialog();
			} catch (error: any) {
				vm.aclDialog.errorMessage = error?.message || 'Failed to save access control';
			} finally {
				vm.aclDialog.isSaving = false;
			}
		},
		discardAclChanges() {
			const vm = this;
			vm.aclDialog.pendingEntries = vm.aclDialog.originalEntries.map((e: any) => ({
				principal: e.principal,
				privileges: [...e.privileges],
				allow: e.allow,
			}));
			vm.aclDialog.errorMessage = '';
		},
		onAclKeydown(event: KeyboardEvent) {
			const vm = this;
			if (event.key === 'Escape') {
				event.preventDefault();
				if (vm.aclDialog.addEntry.visible) {
					vm.closeAddAclEntryDialog();
				} else {
					vm.closeAclDialog();
				}
			}
		},
		// Navigation bar methods
		async loadPersistedNavData() {
			const vm = this;
			const db = vm.instance.api.db;
			const userID = vm.instance.currentUser?.id || '*';
			try {
				let history = await db.getUserSetting(userID, 'content-browser', 'pathHistory');
				let pinned = await db.getUserSetting(userID, 'content-browser', 'pinnedPaths');

				// Migrate from localStorage if IndexedDB has no data
				if (history == null) {
					const legacyHistory = localStorage.getItem('content-browser/pathHistory');
					if (legacyHistory) {
						history = JSON.parse(legacyHistory);
						localStorage.removeItem('content-browser/pathHistory');
					}
				}
				if (pinned == null) {
					const legacyPinned = localStorage.getItem('content-browser/pinnedPaths');
					if (legacyPinned) {
						pinned = JSON.parse(legacyPinned);
						localStorage.removeItem('content-browser/pinnedPaths');
					}
				}

				if (history) vm.pathHistory = history;
				if (pinned) vm.pinnedPaths = pinned;

				// Persist migrated data to IndexedDB
				if (history || pinned) {
					await vm.persistNavData();
				}
			} catch (e) {
				// Silently ignore errors
			}
		},
		async persistNavData() {
			const vm = this;
			const db = vm.instance?.api?.db;
			const userID = vm.instance?.currentUser?.id || '*';
			if (!db) return;
			try {
				await db.setUserSetting(userID, 'content-browser', 'pathHistory', vm.pathHistory);
				await db.setUserSetting(userID, 'content-browser', 'pinnedPaths', vm.pinnedPaths);
			} catch (e) {
				// Silently ignore errors
			}
		},
		addToPathHistory(path: string) {
			this.pathHistory = this.pathHistory.filter((h: any) => h.path !== path);
			this.pathHistory.unshift({ path, timestamp: Date.now() });
			if (this.pathHistory.length > this.MAX_HISTORY_ITEMS) {
				this.pathHistory = this.pathHistory.slice(0, this.MAX_HISTORY_ITEMS);
			}
			this.persistNavData();
		},
		enterEditMode() {
			this.editPathValue = this.currentPath;
			this.navEditMode = true;
			this.ellipsisDropdownOpen = false;
			this.$nextTick(() => {
				const input = document.querySelector('.nav-path-input') as HTMLInputElement;
				if (input) {
					input.focus();
					input.select();
				}
			});
		},
		exitEditMode() {
			this.navEditMode = false;
		},
		async confirmEditPath() {
			const path = this.editPathValue.trim();
			if (path && path !== this.currentPath) {
				await this.load(path);
			}
			this.exitEditMode();
		},
		onEditPathKeydown(event: KeyboardEvent) {
			if (event.key === 'Enter') {
				event.preventDefault();
				this.confirmEditPath();
			} else if (event.key === 'Escape') {
				event.preventDefault();
				this.exitEditMode();
			}
		},
		async copyToClipboard(text: string, event?: MouseEvent) {
			try {
				await navigator.clipboard.writeText(text);
			} catch (e) {
				const textarea = document.createElement('textarea');
				textarea.value = text;
				document.body.appendChild(textarea);
				textarea.select();
				document.execCommand('copy');
				document.body.removeChild(textarea);
			}
			// Visual feedback: swap icon to checkmark briefly
			if (event) {
				const btn = (event.target as HTMLElement).closest('button');
				const icon = btn?.querySelector('i');
				if (btn && icon) {
					const original = icon.className;
					icon.className = 'bi bi-check-lg';
					btn.classList.add('copied');
					setTimeout(() => {
						icon.className = original;
						btn.classList.remove('copied');
					}, 1500);
				}
			}
		},
		async copyPathToClipboard() {
			await this.copyToClipboard(this.editPathValue || this.currentPath);
		},
		async toggleHistoryDropdown(event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !vm.instance) return;
			const rect = trigger.getBoundingClientRect();

			const buildItems = () => {
				const groups: any[] = [];
				groups.push({
					label: 'Pinned Locations',
					icon: 'bi bi-pin-angle',
					info: vm.pinnedPaths.length + ' Pinned',
					emptyMessage: 'No pinned locations',
					items: vm.pinnedPaths.map((pin: any) => ({
						id: pin.path,
						label: pin.name,
						description: pin.path,
						icon: 'bi bi-folder-symlink',
						actions: [
							{ id: 'unpin', icon: 'bi bi-pin-angle-fill', title: 'Unpin' },
						],
					})),
				});
				const recent: any = {
					label: 'Recent History',
					icon: 'bi bi-clock-history',
					emptyMessage: 'No recent history',
					items: vm.pathHistory.map((entry: any) => ({
						id: entry.path,
						label: vm.pathDisplayName(entry.path),
						description: entry.path + ' · ' + vm.formatHistoryTimestamp(entry.timestamp),
						icon: 'bi bi-folder',
						actions: vm.isPathPinned(entry.path)
							? [{ id: 'remove', icon: 'bi bi-x-lg', title: 'Remove', danger: true, showOnHover: true }]
							: [
								{ id: 'pin', icon: 'bi bi-pin-angle', title: 'Pin', showOnHover: true },
								{ id: 'remove', icon: 'bi bi-x-lg', title: 'Remove', danger: true, showOnHover: true },
							],
					})),
				};
				if (vm.pathHistory.length > 0) {
					recent.headerAction = { id: 'clear-all', icon: 'bi bi-trash', label: 'Clear All', title: 'Clear all history', danger: true };
				}
				groups.push(recent);
				return groups;
			};

			const handle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-end',
				minWidth: 320,
				maxHeight: 480,
				items: buildItems(),
				onAction: (itemId: string, actionId: string) => {
					if (actionId === 'unpin') vm.unpinPath(itemId);
					else if (actionId === 'pin') vm.pinPath(itemId);
					else if (actionId === 'remove') vm.removeHistoryItem(itemId);
					handle.update(buildItems());
				},
				onGroupAction: (_groupLabel: string, actionId: string) => {
					if (actionId === 'clear-all') vm.clearAllHistory();
					handle.update(buildItems());
				},
			});

			const result = await handle.result;
			if (result == null) return;
			vm.navigateToPath(String(result));
		},
		toggleEllipsisDropdown() {
			this.ellipsisDropdownOpen = !this.ellipsisDropdownOpen;
			if (this.ellipsisDropdownOpen) {
				this.backDropdownOpen = false;
			}
		},
		// Back/forward navigation methods
		async goBack() {
			if (!this.canGoBack) return;
			this.navStackIndex--;
			await this.load(this.navStack[this.navStackIndex], true);
		},
		async goForward() {
			if (!this.canGoForward) return;
			this.navStackIndex++;
			await this.load(this.navStack[this.navStackIndex], true);
		},
		async goToStackIndex(index: number) {
			if (index < 0 || index >= this.navStack.length) return;
			this.navStackIndex = index;
			this.backDropdownOpen = false;
			await this.load(this.navStack[index], true);
		},
		async toggleBackDropdown(event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !vm.instance) return;
			const rect = trigger.getBoundingClientRect();

			// Build grouped items from the time-bucketed nav history
			const items: any[] = [];
			for (const group of vm.groupedHistory as any[]) {
				items.push({
					label: group.label,
					items: group.items.map((entry: any) => ({
						id: entry.path,
						label: vm.pathDisplayName(entry.path),
						description: entry.path,
						icon: 'bi bi-folder',
					})),
				});
			}
			if (items.length === 0) {
				items.push({
					label: 'History',
					items: [{ id: '__no-history', label: 'No history', disabled: true }],
				});
			}
			// Trailing action — open the full history panel
			items.push({
				label: '',
				items: [{ id: '__show-full-history', label: 'Show full history...', icon: 'bi bi-clock-history' }],
			});

			const handle = vm.instance.popup.open({
				anchor: rect,
				items,
				placement: 'bottom-start',
				minWidth: 260,
			});
			const result = await handle.result;
			if (result == null || result === '__no-history') return;
			if (result === '__show-full-history') {
				vm.toggleFullHistoryPanel();
				return;
			}
			vm.navigateToPath(String(result));
		},
		toggleFullHistoryPanel() {
			this.backDropdownOpen = false;
			this.fullHistoryPanelOpen = !this.fullHistoryPanelOpen;
			this.historySearchKeyword = '';
		},
		closeFullHistoryPanel() {
			this.fullHistoryPanelOpen = false;
			this.historySearchKeyword = '';
		},
		// Extract display name from path
		pathDisplayName(path: string): string {
			const parts = path.split('/').filter((p: string) => p);
			return parts.length > 0 ? parts[parts.length - 1] : 'root';
		},
		async navigateFromHistoryPanel(path: string) {
			this.closeFullHistoryPanel();
			await this.navigateToPath(path);
		},
		async navigateToPath(path: string) {
			this.ellipsisDropdownOpen = false;
			this.backDropdownOpen = false;
			this.navEditMode = false;
			await this.load(path);
		},
		pinPath(path: string) {
			if (this.pinnedPaths.some((p: any) => p.path === path)) return;
			const parts = path.split('/').filter((p: string) => p);
			const name = parts.length > 0 ? parts[parts.length - 1] : 'root';
			this.pinnedPaths.push({ path, name });
			this.persistNavData();
			this.syncContentBrowserPreferences();
		},
		unpinPath(path: string) {
			this.pinnedPaths = this.pinnedPaths.filter((p: any) => p.path !== path);
			this.persistNavData();
			this.syncContentBrowserPreferences();
		},
		isPathPinned(path: string): boolean {
			return this.pinnedPaths.some((p: any) => p.path === path);
		},
		removeHistoryItem(path: string) {
			this.pathHistory = this.pathHistory.filter((h: any) => h.path !== path);
			this.persistNavData();
		},
		clearAllHistory() {
			this.pathHistory = [];
			this.persistNavData();
		},
		formatHistoryTimestamp(timestamp: number): string {
			const diff = Date.now() - timestamp;
			const minutes = Math.floor(diff / 60000);
			const hours = Math.floor(diff / 3600000);
			const days = Math.floor(diff / 86400000);
			if (minutes < 1) return 'Just now';
			if (minutes < 60) return minutes + ' min ago';
			if (hours < 24) return hours + (hours === 1 ? ' hour ago' : ' hours ago');
			if (days < 7) return days + (days === 1 ? ' day ago' : ' days ago');
			return new Date(timestamp).toLocaleDateString();
		},
		onNavClickOutside(event: MouseEvent) {
			const target = event.target as HTMLElement;
			if (this.ellipsisDropdownOpen && !target.closest('.nav-ellipsis-wrapper')) {
				this.ellipsisDropdownOpen = false;
			}
			if (this.backDropdownOpen && !target.closest('.nav-back-wrapper')) {
				this.backDropdownOpen = false;
			}
		},
		async openFilterDateDropdown(event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !vm.instance) return;
			const rect = trigger.getBoundingClientRect();
			const modes: { id: string; label: string }[] = [
				{ id: 'none', label: 'Any time' },
				{ id: 'today', label: 'Today' },
				{ id: 'pastN', label: 'Past N days' },
				{ id: 'range', label: 'Date range' },
			];
			const items = modes.map(m => ({
				id: m.id,
				label: m.label,
				selected: vm.filterDateMode === m.id,
			}));
			const handle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			vm.filterDateMode = String(result);
		},
		filterDateModeLabel(): string {
			const map: Record<string, string> = {
				none: 'Any time',
				today: 'Today',
				pastN: 'Past N days',
				range: 'Date range',
			};
			return map[this.filterDateMode] || 'Any time';
		},
		async openXpathSchemaDropdown(event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !vm.instance) return;
			const rect = trigger.getBoundingClientRect();
			const items: any[] = [{
				id: '',
				label: 'Select schema...',
				selected: !vm.xpathSelectedSchema,
			}];
			for (const s of vm.availableSchemas as any[]) {
				items.push({
					id: s.key,
					label: s.label,
					selected: s.key === vm.xpathSelectedSchema,
				});
			}
			const handle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			vm.xpathSelectedSchema = String(result);
		},
		xpathSelectedSchemaLabel(): string {
			const s = (this.availableSchemas as any[]).find(x => x.key === this.xpathSelectedSchema);
			return s ? s.label : '';
		},
		async openXpathAddConditionDropdown(event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !vm.instance) return;
			const rect = trigger.getBoundingClientRect();
			const items = (vm.xpathAvailableProperties as any[]).map(p => ({
				id: p.key,
				label: p.label,
			}));
			const handle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			vm.xpathAddCondition(String(result));
		},
		async openDetailSchemaDropdown(event: MouseEvent) {
			const value = await this.openSchemaPicker(event, this.selectedSchemaKey);
			if (value === undefined) return;
			this.selectedSchemaKey = value;
			if (value) this.loadQueriesForSchema(value);
		},
		detailSchemaLabel(): string {
			const s = (this.availableSchemas as any[]).find(x => x.key === this.selectedSchemaKey);
			return s ? s.label : '';
		},
		async openPropEditorSchemaDropdown(event: MouseEvent) {
			const value = await this.openSchemaPicker(event, this.propEditorSchemaKey);
			if (value === undefined) return;
			this.propEditorSchemaKey = value;
			if (value) this.loadQueriesForSchema(value);
		},
		propEditorSchemaLabel(): string {
			const s = (this.availableSchemas as any[]).find(x => x.key === this.propEditorSchemaKey);
			return s ? s.label : '';
		},
		// Shared picker for the detail / property-editor schema dropdowns.
		// Returns the chosen schema key ('' for "No schema"), or undefined
		// when the popup was dismissed without a selection.
		async openSchemaPicker(event: MouseEvent, currentKey: string): Promise<string | undefined> {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !vm.instance) return undefined;
			const rect = trigger.getBoundingClientRect();
			const NONE = '__none__';
			const items: any[] = [{
				id: NONE,
				label: '— No schema —',
				selected: !currentKey,
			}];
			for (const s of vm.availableSchemas as any[]) {
				items.push({
					id: s.key,
					label: s.label,
					selected: s.key === currentKey,
				});
			}
			const handle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return undefined;
			return result === NONE ? '' : String(result);
		},
		// Per-condition dropdowns (Boolean / Date)
		async openCondBoolDropdown(cond: any, event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !vm.instance) return;
			const rect = trigger.getBoundingClientRect();
			const options: { id: string; label: string }[] = [
				{ id: '', label: 'Any' },
				{ id: 'true', label: 'True' },
				{ id: 'false', label: 'False' },
			];
			const items = options.map(o => ({
				id: o.id || '__any__',
				label: o.label,
				selected: cond.booleanValue === o.id,
			}));
			const handle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			cond.booleanValue = result === '__any__' ? '' : String(result);
		},
		condBoolLabel(cond: any): string {
			const map: Record<string, string> = { '': 'Any', 'true': 'True', 'false': 'False' };
			return map[cond.booleanValue] || 'Any';
		},
		async openCondDateDropdown(cond: any, event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !vm.instance) return;
			const rect = trigger.getBoundingClientRect();
			const modes: { id: string; label: string }[] = [
				{ id: 'none', label: 'Any time' },
				{ id: 'today', label: 'Today' },
				{ id: 'pastN', label: 'Past N days' },
				{ id: 'range', label: 'Date range' },
			];
			const items = modes.map(m => ({
				id: m.id,
				label: m.label,
				selected: cond.dateMode === m.id,
			}));
			const handle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			cond.dateMode = String(result);
		},
		condDateModeLabel(cond: any): string {
			const map: Record<string, string> = {
				none: 'Any time',
				today: 'Today',
				pastN: 'Past N days',
				range: 'Date range',
			};
			return map[cond.dateMode] || 'Any time';
		},
		// Property editor dropdowns (shell-rendered popups replacing native <select>)
		async openPropEditChoicesMultiDropdown(prop: any, event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !vm.instance) return;
			const rect = trigger.getBoundingClientRect();
			const build = () => (prop.schemaChoices as any[]).map((ci: any) => {
				const selected = (vm.propEditorEditValues as string[]).includes(ci.value);
				return {
					id: ci.value,
					label: ci.label || ci.value,
					icon: selected ? 'bi bi-check-square-fill' : 'bi bi-square',
					selected,
				};
			});
			// Re-open after each toggle so the user can select multiple items.
			// Exits when the user dismisses (Esc / click outside).
			while (true) {
				const handle = vm.instance.popup.open({
					anchor: rect,
					placement: 'bottom-start',
					minWidth: rect.width,
					items: build(),
				});
				const result = await handle.result;
				if (result == null) break;
				const value = String(result);
				const choice = (prop.schemaChoices as any[]).find((c: any) => c.value === value);
				const values = vm.propEditorEditValues as string[];
				const labels = vm.propEditorEditDisplayValues as string[];
				const idx = values.indexOf(value);
				if (idx >= 0) {
					values.splice(idx, 1);
					labels.splice(idx, 1);
				} else {
					values.push(value);
					labels.push(choice ? (choice.label || choice.value) : value);
				}
			}
		},
		propEditChoicesMultiLabel(prop: any): string {
			const values = this.propEditorEditValues as string[];
			if (!values.length) return '— Select —';
			const choices = prop.schemaChoices as any[];
			return values
				.map(v => {
					const c = choices.find(x => x.value === v);
					return c ? (c.label || c.value) : v;
				})
				.join(', ');
		},
		async openPropEditChoicesSingleDropdown(prop: any, event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !vm.instance) return;
			const rect = trigger.getBoundingClientRect();
			const EMPTY = '__empty__';
			const items: any[] = [{
				id: EMPTY,
				label: '— Select —',
				selected: !vm.propEditorEditInput,
			}];
			for (const ci of prop.schemaChoices as any[]) {
				items.push({
					id: ci.value,
					label: ci.label || ci.value,
					selected: ci.value === vm.propEditorEditInput,
				});
			}
			const handle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			vm.propEditorEditInput = result === EMPTY ? '' : String(result);
		},
		propEditChoicesSingleLabel(prop: any): string {
			const val = this.propEditorEditInput as string;
			if (!val) return '— Select —';
			const c = (prop.schemaChoices as any[]).find((x: any) => x.value === val);
			return c ? (c.label || c.value) : val;
		},
		async openPropEditBooleanDropdown(event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !vm.instance) return;
			const rect = trigger.getBoundingClientRect();
			const items = [
				{ id: 'true', label: 'true', selected: vm.propEditorEditInput === 'true' },
				{ id: 'false', label: 'false', selected: vm.propEditorEditInput === 'false' },
			];
			const handle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			vm.propEditorEditInput = String(result);
		},
		async openPropEditChipBooleanDropdown(idx: number, event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !vm.instance) return;
			const rect = trigger.getBoundingClientRect();
			const current = (vm.propEditorEditValues as string[])[idx];
			const items = [
				{ id: 'true', label: 'true', selected: current === 'true' },
				{ id: 'false', label: 'false', selected: current === 'false' },
			];
			const handle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			vm.updateEditChip(idx, String(result));
		},
		async openPropEditNewTypeDropdown(event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !vm.instance) return;
			const rect = trigger.getBoundingClientRect();
			const items = (vm.propTypeOptions as any[]).map((o: any) => ({
				id: o.id,
				label: o.label,
				selected: o.id === vm.propEditorNewType,
			}));
			const handle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			vm.propEditorNewType = String(result);
		},
		propEditNewTypeLabel(): string {
			const o = (this.propTypeOptions as any[]).find((x: any) => x.id === this.propEditorNewType);
			return o ? o.label : String(this.propEditorNewType);
		},
		async openNewFileTypeDropdown(event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !vm.instance) return;
			if (vm.newFileDialog.isLoading) return;
			const rect = trigger.getBoundingClientRect();
			const items = (vm.fileTypeOptions as any[]).map((o: any) => ({
				id: o.id,
				label: `${o.label} (${o.extension})`,
				selected: o.id === vm.newFileDialog.fileType,
			}));
			const handle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			vm.newFileDialog.fileType = String(result);
		},
		newFileTypeLabel(): string {
			const o = (this.fileTypeOptions as any[]).find((x: any) => x.id === this.newFileDialog.fileType);
			return o ? `${o.label} (${o.extension})` : '';
		},
		// Client-side filter methods
		clearAllFilters() {
			this.filterText = '';
			this.filterDateMode = 'none';
		},
		// XPath search methods
		xpathOnAddCondition(event: Event) {
			const select = event.target as HTMLSelectElement;
			const propertyKey = select.value;
			if (propertyKey) {
				this.xpathAddCondition(propertyKey);
				select.value = '';
			}
		},
		xpathAddCondition(propertyKey: string) {
			const prop = (this.xpathSchemaProperties as any[]).find(p => p.key === propertyKey);
			if (!prop) return;
			this.xpathConditions.push({
				id: this.xpathNextConditionID++,
				propertyKey,
				type: prop.type || 'STRING',
				stringValue: '',
				numberFrom: '',
				numberTo: '',
				numberFromInclusive: true,
				numberToInclusive: true,
				booleanValue: '',
				dateMode: 'none',
				datePastN: 7,
				dateFrom: '',
				dateTo: '',
				dateFromInclusive: true,
				dateToInclusive: true,
				selectedChoices: [],
			});
		},
		xpathRemoveCondition(id: number) {
			this.xpathConditions = this.xpathConditions.filter((c: any) => c.id !== id);
		},
		xpathToggleChoice(condition: any, value: string) {
			const idx = condition.selectedChoices.indexOf(value);
			if (idx >= 0) {
				condition.selectedChoices.splice(idx, 1);
			} else {
				condition.selectedChoices.push(value);
			}
		},
		async xpathExecuteSearch() {
			const vm = this;
			// Surface a parse error from the full-text expression before kicking off the request.
			const ft = vm.fullTextCompiled as any as { predicate: string | null; error: string | null };
			if (ft.error) {
				vm.xpathSearchError = `Full-text query: ${ft.error}`;
				vm.xpathSearchActive = true;
				vm.items = [];
				return;
			}
			if (!vm.hasSearchInput) {
				return;
			}
			vm.items = [];
			vm.xpathSearchLoading = true;
			vm.xpathSearchError = '';
			vm.xpathSearchActive = true;
			try {
				const contentService = vm.instance.api.content;
				const query = vm.xpathBuiltQuery;
				const items: ContentItem[] = [];
				let after: string | undefined;
				let totalCount = 0;
				// Paginate through all results
				while (true) {
					const result = await contentService.xpath(query, { first: 50, after });
					totalCount = result.totalCount;
					for (const edge of result.edges) {
						const item = nodeToContentItem(edge.node);
						item.attributes.displayDate = vm.displayDate(item);
						items.push(item);
					}
					if (!result.pageInfo.hasNextPage) break;
					after = result.pageInfo.endCursor;
				}
				vm.items = items;
				vm.xpathSearchTotalCount = totalCount;
				vm.sortItems(vm.sortColumn, vm.sortDirection);
			} catch (error: any) {
				vm.xpathSearchError = error?.message || String(error);
			} finally {
				vm.xpathSearchLoading = false;
			}
		},
		async xpathClearSearch() {
			this.xpathSearchActive = false;
			this.xpathSearchTotalCount = 0;
			this.xpathSearchError = '';
			await this.load(this.currentPath, true);
		},
		xpathClearAllConditions() {
			this.xpathConditions = [];
			this.xpathSelectedSchema = '';
			this.fullTextKeyword = '';
			this.xpathSearchError = '';
		},
		// Smart folder methods
		smartFolderSaveCurrent() {
			const hasSchemaCondition = this.xpathSelectedSchema && this.xpathConditions.length > 0;
			const fullText = (this.fullTextKeyword as string).trim();
			if (!hasSchemaCondition && !fullText) return;
			let displayName: string;
			if (hasSchemaCondition) {
				const schema = (this.availableSchemas as any[]).find(s => s.key === this.xpathSelectedSchema);
				displayName = schema ? schema.label : this.xpathSelectedSchema;
			} else {
				displayName = fullText.length > 32 ? fullText.slice(0, 32) + '…' : fullText;
			}
			const folder = {
				id: this.smartFolderNextID++,
				name: `${displayName} — ${this.currentPath}`,
				path: this.currentPath,
				schemaKey: this.xpathSelectedSchema,
				conditions: JSON.parse(JSON.stringify(this.xpathConditions)),
				fullTextKeyword: fullText,
			};
			this.smartFolders.push(folder);
			this.persistSmartFolders();
		},
		smartFolderDelete(id: number) {
			this.smartFolders = this.smartFolders.filter((f: any) => f.id !== id);
			this.persistSmartFolders();
		},
		smartFolderStartRename(id: number) {
			const folder = this.smartFolders.find((f: any) => f.id === id);
			if (!folder) return;
			this.smartFolderEditing = id;
			this.smartFolderEditName = folder.name;
		},
		smartFolderConfirmRename(id: number) {
			const folder = this.smartFolders.find((f: any) => f.id === id);
			if (folder && this.smartFolderEditName.trim()) {
				folder.name = this.smartFolderEditName.trim();
				this.persistSmartFolders();
			}
			this.smartFolderEditing = null;
			this.smartFolderEditName = '';
		},
		smartFolderCancelRename() {
			this.smartFolderEditing = null;
			this.smartFolderEditName = '';
		},
		smartFolderRenameKeydown(event: KeyboardEvent, id: number) {
			if (event.key === 'Enter') {
				event.preventDefault();
				this.smartFolderConfirmRename(id);
			} else if (event.key === 'Escape') {
				event.preventDefault();
				this.smartFolderCancelRename();
			}
		},
		async smartFolderExecute(id: number) {
			const folder = this.smartFolders.find((f: any) => f.id === id);
			if (!folder) return;
			// Navigate to the saved path if different
			if (this.currentPath !== folder.path) {
				await this.load(folder.path);
			}
			// Restore search conditions
			this.xpathSelectedSchema = folder.schemaKey || '';
			this.xpathConditions = JSON.parse(JSON.stringify(folder.conditions || []));
			this.xpathNextConditionID = (folder.conditions && folder.conditions.length > 0)
				? Math.max(...folder.conditions.map((c: any) => c.id), 0) + 1
				: 1;
			// Restore full-text keyword (older saved folders may not have this field)
			this.fullTextKeyword = folder.fullTextKeyword || '';
			// Open XPath section
			this.sidebarSectionExpanded.xpathSearch = true;
			this.persistSidebarPanelState();
			// Execute
			await this.xpathExecuteSearch();
		},
		async persistSmartFolders() {
			const vm = this;
			const db = vm.instance?.api?.db;
			const userID = vm.instance?.currentUser?.id || '*';
			if (!db) return;
			try {
				await db.setUserSetting(userID, 'content-browser', 'smartFolders', {
					folders: vm.smartFolders,
					nextID: vm.smartFolderNextID,
				});
			} catch (e) {
				// Silently ignore errors
			}
			// Sync smart folders to server
			vm.syncContentBrowserPreferences();
		},
		async loadSmartFolders() {
			const vm = this;
			const db = vm.instance.api.db;
			const userID = vm.instance.currentUser?.id || '*';
			try {
				const state = await db.getUserSetting(userID, 'content-browser', 'smartFolders');
				if (state) {
					vm.smartFolders = state.folders || [];
					vm.smartFolderNextID = state.nextID || 1;
				}
			} catch (e) {
				// Silently ignore errors
			}
		},
		// Load favorites and smart folders from server (cross-device initial sync)
		async loadServerPreferences() {
			const vm = this;
			const userID = vm.instance?.currentUser?.id;
			if (!userID) return;
			try {
				const systemContent = vm.instance.api.systemContent;
				const prefPath = `/home/users/${userID}/preferences/content-browser`;
				const node = await systemContent.getNode(prefPath);
				if (!node || !node.properties) return;
				const props: Record<string, any> = {};
				for (const prop of node.properties) {
					const tv = prop.propertyValue?.__typename;
					if (tv === 'StringPropertyValue' || tv === 'DoublePropertyValue' ||
						tv === 'LongPropertyValue' || tv === 'DecimalPropertyValue') {
						props[prop.name] = prop.propertyValue.value;
					}
				}
				// Apply server data (server wins over local for cross-device sync)
				if (props.pinnedPaths) {
					try {
						vm.pinnedPaths = JSON.parse(props.pinnedPaths);
						const db = vm.instance?.api?.db;
						if (db) await db.setUserSetting(userID, 'content-browser', 'pinnedPaths', vm.pinnedPaths);
					} catch { /* invalid JSON, keep local */ }
				}
				if (props.smartFolders) {
					try {
						vm.smartFolders = JSON.parse(props.smartFolders);
						if (props.smartFolderNextID != null) {
							vm.smartFolderNextID = props.smartFolderNextID;
						}
						const db = vm.instance?.api?.db;
						if (db) await db.setUserSetting(userID, 'content-browser', 'smartFolders', {
							folders: vm.smartFolders,
							nextID: vm.smartFolderNextID,
						});
					} catch { /* invalid JSON, keep local */ }
				}
			} catch (e) {
				// Server preferences not available — use local data
			}
		},
		// Sync favorites and smart folders to server for cross-device sync
		async syncContentBrowserPreferences() {
			const vm = this;
			if (vm._suppressServerSync) return;
			const username = vm.instance?.currentUser?.id;
			if (!username || !vm.idp) return;
			try {
				await vm.idp.updatePreferences({
					username,
					category: 'content-browser',
					data: {
						pinnedPaths: JSON.stringify(vm.pinnedPaths),
						smartFolders: JSON.stringify(vm.smartFolders),
						smartFolderNextID: vm.smartFolderNextID,
					},
				});
			} catch (e) {
				console.warn('[ContentBrowser] Failed to sync preferences to server:', e);
			}
		},
		// Sidebar panel methods
		toggleSidebarPanel() {
			this.sidebarPanelVisible = !this.sidebarPanelVisible;
			this.persistSidebarPanelState();
		},
		toggleSidebarSection(name: 'filter' | 'favorites' | 'smartFolders' | 'xpathSearch') {
			this.sidebarSectionExpanded[name] = !this.sidebarSectionExpanded[name];
			this.persistSidebarPanelState();
		},
		onSidebarResizeStart(event: MouseEvent) {
			const vm = this;
			event.preventDefault();
			vm.sidebarPanelResizing = true;
			vm.sidebarResizeStartX = event.clientX;
			vm.sidebarResizeStartWidth = vm.sidebarPanelWidth;
			vm._boundSidebarResizeMove = (e: MouseEvent) => {
				const delta = e.clientX - vm.sidebarResizeStartX;
				let newWidth = vm.sidebarResizeStartWidth + delta;
				newWidth = Math.max(vm.sidebarPanelMinWidth, Math.min(vm.sidebarPanelMaxWidth, newWidth));
				vm.sidebarPanelWidth = newWidth;
			};
			vm._boundSidebarResizeUp = () => {
				vm.sidebarPanelResizing = false;
				document.removeEventListener('mousemove', vm._boundSidebarResizeMove);
				document.removeEventListener('mouseup', vm._boundSidebarResizeUp);
				vm._boundSidebarResizeMove = null;
				vm._boundSidebarResizeUp = null;
				vm.persistSidebarPanelState();
			};
			document.addEventListener('mousemove', vm._boundSidebarResizeMove);
			document.addEventListener('mouseup', vm._boundSidebarResizeUp);
		},
		async persistSidebarPanelState() {
			const vm = this;
			const db = vm.instance?.api?.db;
			const userID = vm.instance?.currentUser?.id || '*';
			if (!db) return;
			try {
				// Deep-clone via JSON to strip the reactive Proxy from
				// sidebarSectionExpanded — IndexedDB's structured clone
				// rejects Proxy objects and would silently fail otherwise.
				await db.setUserSetting(userID, 'content-browser', 'sidebarPanel', JSON.parse(JSON.stringify({
					visible: vm.sidebarPanelVisible,
					width: vm.sidebarPanelWidth,
					sectionExpanded: vm.sidebarSectionExpanded,
				})));
			} catch (e) {
				// Silently ignore errors
			}
		},
		// Detail preview panel methods
		toggleDetailPanel() {
			this.detailPanelVisible = !this.detailPanelVisible;
			this.persistDetailPanelState();
		},
		onMinimizeWindow() {
			this.instance?.minimize();
		},
		onToggleMaximizeWindow() {
			this.instance?.toggleMaximize();
		},
		onCloseWindow() {
			this.instance?.requestClose();
		},
		formatDetailDate(date: Date | null): string {
			if (!date) return '\u2014';
			return this.instance.util.dates.format(date, { format: 'friendly' });
		},
		onPreviewImageError() {
			this.previewImageError = true;
		},
		onDetailResizeStart(event: MouseEvent) {
			const vm = this;
			event.preventDefault();
			vm.detailPanelResizing = true;
			vm.detailResizeStartX = event.clientX;
			vm.detailResizeStartWidth = vm.detailPanelWidth;
			vm._boundResizeMove = (e: MouseEvent) => {
				const delta = vm.detailResizeStartX - e.clientX;
				let newWidth = vm.detailResizeStartWidth + delta;
				newWidth = Math.max(vm.detailPanelMinWidth, Math.min(vm.detailPanelMaxWidth, newWidth));
				vm.detailPanelWidth = newWidth;
			};
			vm._boundResizeUp = () => {
				vm.detailPanelResizing = false;
				document.removeEventListener('mousemove', vm._boundResizeMove);
				document.removeEventListener('mouseup', vm._boundResizeUp);
				vm._boundResizeMove = null;
				vm._boundResizeUp = null;
				vm.persistDetailPanelState();
			};
			document.addEventListener('mousemove', vm._boundResizeMove);
			document.addEventListener('mouseup', vm._boundResizeUp);
		},
		async persistDetailPanelState() {
			const vm = this;
			const db = vm.instance?.api?.db;
			const userID = vm.instance?.currentUser?.id || '*';
			if (!db) return;
			try {
				await db.setUserSetting(userID, 'content-browser', 'detailPanel', {
					visible: vm.detailPanelVisible,
					width: vm.detailPanelWidth,
				});
			} catch (e) {
				// Silently ignore errors
			}
		},
		loadDetailData() {
			const vm = this;
			// Cancel any in-progress MIME type editing
			vm.cancelMimeTypeEdit();
			// Resolve selected item directly from data (not computed) to avoid stale cache
			let item = null;
			if (vm.selectedItems.length === 1) {
				item = vm.items.find((i: any) => i.id === vm.selectedItems[0]) || null;
			}
			if (!item) {
				vm.detailProperties = [];
				vm.detailPropertiesLoading = false;
				vm.detailACL = [];
				vm.detailACLLoading = false;
				// Update overlays for empty/multi selection
				if (vm.detailVersionHistoryVisible) {
					vm.versionHistoryDialog.item = null;
					vm.versionHistoryDialog.versions = [];
					vm.versionHistoryDialog.isLoading = false;
				}
				if (vm.detailACLEditorVisible) {
					vm.aclDialog.item = null;
					vm.aclDialog.effectivePolicies = [];
					vm.aclDialog.pendingEntries = [];
					vm.aclDialog.originalEntries = [];
					vm.aclDialog.isLoading = false;
				}
				return;
			}
			// Load properties and ACL in parallel
			vm.loadDetailProperties(item);
			vm.loadDetailACL(item);
			// Refresh overlays if open
			if (vm.detailVersionHistoryVisible) {
				vm.versionHistoryDialog.item = item;
				if (item.isVersionable) {
					vm.refreshDetailVersionHistory();
				} else {
					vm.versionHistoryDialog.versions = [];
					vm.versionHistoryDialog.isLoading = false;
					vm.versionHistoryDialog.errorMessage = '';
				}
			}
			if (vm.detailACLEditorVisible) {
				vm.aclDialog.item = item;
				vm.refreshDetailACLEditor();
			}
			if (vm.detailPropertyEditorVisible) {
				vm.loadPropEditorData(item);
			}
		},
		async loadDetailProperties(item: any) {
			const vm = this;
			vm.detailPropertiesLoading = true;
			vm.detailPropertiesError = '';
			vm.detailProperties = [];

			try {
				const contentService = vm.instance.api.content;
				const node = await contentService.getNode(item.path);
				if (!node || !node.properties) {
					vm.detailProperties = [];
					return;
				}

				// Filter out JCR system properties already shown in Basic Info
				const systemPrefixes = ['jcr:', 'rep:', 'oak:'];
				const filtered = node.properties.filter((p: any) =>
					!systemPrefixes.some(prefix => p.name.startsWith(prefix))
				);

				vm.detailProperties = filtered.map((p: any) => {
					const pv = p.propertyValue;
					const type = (pv.type || 'STRING').toUpperCase();
					const isArray = 'values' in pv && Array.isArray(pv.values);
					let value = '';
					let items: string[] = [];

					// Reference properties: show resolved path instead of raw UUID
					if (type === 'REFERENCE' || type === 'WEAKREFERENCE') {
						if (isArray) {
							const uuids: string[] = pv.values || [];
							const paths: (string | null)[] = pv.paths || [];
							items = uuids.map((uuid: string, i: number) => paths[i] || uuid);
							value = items.join(', ');
							return { name: p.name, type, isArray, value, items, uuids };
						} else {
							const uuid = String(pv.value ?? '');
							value = pv.path || uuid;
							return { name: p.name, type, isArray, value, items, uuid };
						}
					}

					// Binary properties: show metadata instead of raw value
					if (type === 'BINARY') {
						if (pv.__typename === 'BinaryPropertyValueArray') {
							const mimeTypeList: string[] = pv.mimeTypes || [];
							const sizeList: number[] = pv.sizes || [];
							const sizesFormatted: string[] = sizeList.map((s: number) => vm.instance.util.bytes.format(s, { short: true }));
							const isImages: boolean[] = mimeTypeList.map((m: string) => m.startsWith('image/'));
							const propertyDownloadURLs: string[] = [];
							if (node.downloadUrl) {
								const sep = node.downloadUrl.includes('?') ? '&' : '?';
								const baseURL = node.downloadUrl + sep + 'property=' + encodeURIComponent(p.name);
								for (let i = 0; i < mimeTypeList.length; i++) {
									propertyDownloadURLs.push(baseURL + '&index=' + i);
								}
							}
							return { name: p.name, type, isArray: true, value: '', items: [], mimeTypes: mimeTypeList, sizes: sizeList, sizesFormatted, isImages, propertyDownloadURLs };
						}
						const mimeType = pv.mimeType || 'application/octet-stream';
						const size = pv.size ?? 0;
						const sizeFormatted = vm.instance.util.bytes.format(size, { short: true });
						const isImage = mimeType.startsWith('image/');
						let propertyDownloadURL = '';
						if (node.downloadUrl) {
							const sep = node.downloadUrl.includes('?') ? '&' : '?';
							propertyDownloadURL = node.downloadUrl + sep + 'property=' + encodeURIComponent(p.name);
						}
						return { name: p.name, type, isArray: false, value: '', items: [], mimeType, size, sizeFormatted, isImage, propertyDownloadURL };
					}

					if (isArray) {
						items = (pv.values || []).map((v: string) => {
							if (type === 'DATE') { try { return new Date(v).toLocaleString(); } catch { return v; } }
							return String(v);
						});
						value = items.join(', ');
					} else if ('value' in pv) {
						if (type === 'DATE' && pv.value) {
							try { value = new Date(pv.value).toLocaleString(); }
							catch { value = String(pv.value); }
						} else {
							value = String(pv.value ?? '');
						}
					}
					return { name: p.name, type, isArray, value, items };
				});
			} catch (error: any) {
				vm.detailPropertiesError = error?.message || 'Failed to load properties';
			} finally {
				vm.detailPropertiesLoading = false;
			}
		},
		loadAvailableSchemas() {
			const vm = this;
			try {
				const cache = (window.parent as any)?.Webtop?.metadataDefinitions;
				if (!cache || !cache.loaded) return;

				const allDefs = cache.getAllDefinitions();
				const schemas = allDefs.map((def: any) => ({
					key: def.key || '',
					label: def.label || def.key || '',
					properties: (def.properties || []).map((p: any) => ({
						key: p.key || '',
						label: p.label || p.key || '',
						type: p.type || 'STRING',
						required: p.constraints?.required ?? false,
						minLength: p.constraints?.minLength,
						maxLength: p.constraints?.maxLength,
						pattern: p.constraints?.pattern,
						minValue: p.constraints?.minValue,
						maxValue: p.constraints?.maxValue,
						displayFormat: p.uiHint?.displayFormat || undefined,
						choices: (p.constraints?.choices || []).length > 0
							? p.constraints.choices.map((c: any) => ({ value: c.value || '', label: c.label || '' }))
							: undefined,
						readOnly: p.uiHint?.readOnly ?? false,
						multiple: p.constraints?.multiple ?? undefined,
						editorType: p.uiHint?.editorType || undefined,
						rows: p.uiHint?.rows || undefined,
						query: p.constraints?.query?.xpath
							? { xpath: p.constraints.query.xpath, labelKey: p.constraints.query.labelKey || '' }
							: undefined,
						defaultValue: p.behavior?.defaultValue?.value
							? {
								type: p.behavior.defaultValue.type || 'STATIC',
								value: p.behavior.defaultValue.value,
							}
							: undefined,
						validation: p.uiHint?.validation || undefined,
					})),
				}));
				schemas.sort((a: any, b: any) => a.label.localeCompare(b.label));
				vm.availableSchemas = schemas;
				vm.propEditorQueryCache = {};
			} catch {
				// Non-critical: schema loading failure should not block main UI
			}
		},
		executeDisplayFormat(script: string, prop: any, allPropsRaw: Map<string, { value: any; values: any[] }>): string {
			// Build ctx object
			const ctx = {
				get(propName: string, defaultValue?: any): any {
					const entry = allPropsRaw.get(propName);
					if (!entry || entry.value == null) return defaultValue ?? null;
					return entry.value;
				},
				getString(propName: string, defaultValue?: string): string {
					const entry = allPropsRaw.get(propName);
					if (!entry || entry.value == null) return defaultValue ?? '';
					return String(entry.value);
				},
				getNumber(propName: string, defaultValue?: number): number {
					const entry = allPropsRaw.get(propName);
					if (!entry || entry.value == null) return defaultValue ?? 0;
					const n = Number(entry.value);
					return isNaN(n) ? (defaultValue ?? 0) : n;
				},
				getValues(propName: string, defaultValue?: any[]): any[] {
					const entry = allPropsRaw.get(propName);
					if (!entry || !entry.values) return defaultValue ?? [];
					return entry.values;
				},
				formatCurrency(amount: number, currencyCode?: string): string {
					try {
						const code = currencyCode || 'USD';
						return new Intl.NumberFormat(undefined, {
							style: 'currency',
							currency: code,
						}).format(amount);
					} catch {
						return String(amount);
					}
				},
				formatDate(date: string | number | Date, pattern?: string): string {
					try {
						const d = date instanceof Date ? date : new Date(date);
						if (isNaN(d.getTime())) return String(date);
						if (!pattern) return d.toLocaleString();
						// Simple pattern replacement
						const pad = (n: number) => String(n).padStart(2, '0');
						return pattern
							.replace('YYYY', String(d.getFullYear()))
							.replace('MM', pad(d.getMonth() + 1))
							.replace('DD', pad(d.getDate()))
							.replace('HH', pad(d.getHours()))
							.replace('mm', pad(d.getMinutes()))
							.replace('ss', pad(d.getSeconds()));
					} catch {
						return String(date);
					}
				},
			};

			// Determine value and values for the current property
			const value = prop.isArray
				? (prop.items.length > 0 ? prop.items[0] : null)
				: (prop.value || null);
			const values = prop.isArray ? [...prop.items] : [prop.value];

			// Execute the script in a sandboxed function
			const fn = new Function('value', 'values', 'ctx', script);
			const result = fn(value, values, ctx);
			return result == null ? '' : String(result);
		},
		async loadDetailACL(item: any) {
			const vm = this;
			vm.detailACLLoading = true;
			vm.detailACLError = '';
			vm.detailACL = [];

			try {
				const contentService = vm.instance.api.content;
				vm.detailACL = await contentService.getEffectiveAccessControl(item.path);
			} catch (error: any) {
				vm.detailACLError = error?.message || 'Failed to load access control';
			} finally {
				vm.detailACLLoading = false;
			}
		},
		async showDetailVersionHistory() {
			const vm = this;
			const item = vm.selectedItem;
			if (!item) return;
			// Close other overlay first
			if (vm.detailACLEditorVisible) {
				vm.detailACLEditorVisible = false;
			}
			// Reset scroll so overlay covers visible area from top
			const panel = document.querySelector('.detail-panel') as HTMLElement;
			if (panel) panel.scrollTop = 0;
			vm.detailVersionHistoryVisible = true;
			vm.versionHistoryDialog.item = item;
			await vm.refreshDetailVersionHistory();
		},
		async refreshDetailVersionHistory() {
			const vm = this;
			const item = vm.versionHistoryDialog.item;
			if (!item) return;
			vm.versionHistoryDialog.versions = [];
			vm.versionHistoryDialog.baseVersionName = '';
			vm.versionHistoryDialog.errorMessage = '';
			vm.versionHistoryDialog.isLoading = true;

			try {
				const contentService = vm.instance.api.content;
				const history = await contentService.getVersionHistory(item.path);
				if (history) {
					vm.versionHistoryDialog.versions = (history.edges || []).map((edge: any) => edge.node).reverse();
					vm.versionHistoryDialog.baseVersionName = history.baseVersion?.name || '';
				}
			} catch (error: any) {
				vm.versionHistoryDialog.errorMessage = error?.message || 'Failed to load version history';
			} finally {
				vm.versionHistoryDialog.isLoading = false;
			}
		},
		closeDetailVersionHistory() {
			const vm = this;
			vm.detailVersionHistoryVisible = false;
			vm.versionHistoryDialog.item = null;
			vm.versionHistoryDialog.versions = [];
			vm.versionHistoryDialog.baseVersionName = '';
			vm.versionHistoryDialog.errorMessage = '';
			vm.versionHistoryDialog.isLoading = false;
		},
		async showDetailACLEditor() {
			const vm = this;
			const item = vm.selectedItem;
			if (!item) return;
			// Close other overlay first
			if (vm.detailVersionHistoryVisible) {
				vm.detailVersionHistoryVisible = false;
			}
			// Reset scroll so overlay covers visible area from top
			const panel = document.querySelector('.detail-panel') as HTMLElement;
			if (panel) panel.scrollTop = 0;
			vm.detailACLEditorVisible = true;
			vm.aclDialog.item = item;
			await vm.refreshDetailACLEditor();
		},
		async refreshDetailACLEditor() {
			const vm = this;
			const item = vm.aclDialog.item;
			if (!item) return;
			vm.aclDialog.isLoading = true;
			vm.aclDialog.errorMessage = '';
			vm.aclDialog.effectivePolicies = [];
			vm.aclDialog.pendingEntries = [];
			vm.aclDialog.originalEntries = [];

			try {
				const contentService = vm.instance.api.content;
				vm.aclDialog.effectivePolicies = await contentService.getEffectiveAccessControl(item.path);
				vm._syncAclPendingEntries();
			} catch (error: any) {
				vm.aclDialog.errorMessage = error?.message || 'Failed to load access control';
			} finally {
				vm.aclDialog.isLoading = false;
			}
		},
		closeDetailACLEditor() {
			const vm = this;
			vm.detailACLEditorVisible = false;
			vm.aclDialog.item = null;
			vm.aclDialog.effectivePolicies = [];
			vm.aclDialog.pendingEntries = [];
			vm.aclDialog.originalEntries = [];
			vm.aclDialog.isLoading = false;
			vm.aclDialog.isSaving = false;
			vm.aclDialog.errorMessage = '';
			vm.closeAddAclEntryDialog();
			if (vm.aclSearchDebounceTimer) {
				clearTimeout(vm.aclSearchDebounceTimer);
				vm.aclSearchDebounceTimer = null;
			}
			// Refresh ACL summary after editing
			const selectedItem = vm.selectedItem;
			if (selectedItem) {
				vm.loadDetailACL(selectedItem);
			}
		},
		async showDetailPropertyEditor() {
			const vm = this;
			const item = vm.selectedItem;
			if (!item) return;
			// Close other overlays first
			if (vm.detailVersionHistoryVisible) {
				vm.detailVersionHistoryVisible = false;
				vm.versionHistoryDialog.item = null;
				vm.versionHistoryDialog.versions = [];
			}
			if (vm.detailACLEditorVisible) {
				vm.detailACLEditorVisible = false;
			}
			const panel = document.querySelector('.detail-panel') as HTMLElement;
			if (panel) panel.scrollTop = 0;
			vm.detailPropertyEditorVisible = true;
			vm.propEditorSchemaKey = vm.selectedSchemaKey || '';
			vm.propEditorQueryCache = {};
			await vm.loadPropEditorData(item);
			if (vm.propEditorSchemaKey) {
				vm.loadQueriesForSchema(vm.propEditorSchemaKey);
			}
		},
		closeDetailPropertyEditor() {
			const vm = this;
			vm.detailPropertyEditorVisible = false;
			vm.propEditorItems = [];
			vm.propEditorLoading = false;
			vm.propEditorError = '';
			vm.propEditorEditingName = null;
			vm.propEditorAddingNew = false;
			vm.propEditorSaving = false;
			vm.propEditorSaveError = '';
		},
		async loadPropEditorData(item: any) {
			const vm = this;
			vm.propEditorEditingName = null;
			vm.propEditorAddingNew = false;
			vm.propEditorSaveError = '';

			if (!item || item.isCollection) {
				vm.propEditorItems = [];
				vm.propEditorLoading = false;
				return;
			}

			vm.propEditorLoading = true;
			vm.propEditorError = '';
			// Revoke any Object URLs from pending binary edits
			for (const prop of (vm.propEditorItems as any[])) {
				if (prop.binaryPreviewURL) {
					URL.revokeObjectURL(prop.binaryPreviewURL);
				}
				if (prop.binaryPreviewURLs) {
					for (const u of prop.binaryPreviewURLs) { if (u) URL.revokeObjectURL(u); }
				}
			}
			vm.propEditorItems = [];

			try {
				const contentService = vm.instance.api.content;
				const node = await contentService.getNode(item.path);
				if (!node || !node.properties) {
					vm.propEditorItems = [];
					return;
				}
				const systemPrefixes = ['jcr:', 'rep:', 'oak:'];
				const filtered = node.properties.filter((p: any) =>
					!systemPrefixes.some((prefix: string) => p.name.startsWith(prefix))
				);
				vm.propEditorItems = filtered.map((p: any) => {
					const pv = p.propertyValue;
					const isArray = 'values' in pv && Array.isArray(pv.values);
					const type = (pv.type || 'STRING').toUpperCase();
					const origVal = isArray ? '' : String(pv.value ?? '');
					const origVals = isArray ? (pv.values || []).map(String) : [];
					// Binary properties: add preview metadata
					if (type === 'BINARY') {
						if (pv.__typename === 'BinaryPropertyValueArray') {
							const mimeTypeList: string[] = pv.mimeTypes || [];
							const sizeList: number[] = pv.sizes || [];
							const propertyDownloadURLs: string[] = [];
							const sizesFormatted: string[] = sizeList.map((s: number) => vm.instance.util.bytes.format(s, { short: true }));
							if (node.downloadUrl) {
								const sep = node.downloadUrl.includes('?') ? '&' : '?';
								const baseURL = node.downloadUrl + sep + 'property=' + encodeURIComponent(p.name);
								for (let i = 0; i < mimeTypeList.length; i++) {
									propertyDownloadURLs.push(baseURL + '&index=' + i);
								}
							}
							return {
								name: p.name, type, isArray: true,
								originalValue: '', originalValues: [],
								currentValue: '', currentValues: [],
								displayValue: '', displayValues: [],
								isModified: false, isDeleted: false, isNew: false,
								mimeTypes: mimeTypeList,
								sizes: sizeList,
								sizesFormatted,
								propertyDownloadURLs,
							};
						}
						const mimeType = pv.mimeType || 'application/octet-stream';
						const size = pv.size ?? 0;
						const sizeFormatted = vm.instance.util.bytes.format(size, { short: true });
						const isImage = mimeType.startsWith('image/');
						let propertyDownloadURL = '';
						if (node.downloadUrl) {
							const sep = node.downloadUrl.includes('?') ? '&' : '?';
							propertyDownloadURL = node.downloadUrl + sep + 'property=' + encodeURIComponent(p.name);
						}
						return {
							name: p.name, type, isArray: false,
							originalValue: '', originalValues: [],
							currentValue: '', currentValues: [],
							displayValue: '', displayValues: [],
							isModified: false, isDeleted: false, isNew: false,
							mimeType, size, sizeFormatted, isImage, propertyDownloadURL,
						};
					}
					// For reference types, compute display values using resolved paths
					let displayValue = origVal;
					let displayValues = [...origVals];
					if (type === 'REFERENCE' || type === 'WEAKREFERENCE') {
						if (isArray) {
							const paths: (string | null)[] = pv.paths || [];
							displayValues = origVals.map((uuid: string, i: number) => paths[i] || uuid);
						} else {
							displayValue = pv.path || origVal;
						}
					}
					return {
						name: p.name,
						type,
						isArray,
						originalValue: origVal,
						originalValues: origVals,
						currentValue: origVal,
						currentValues: [...origVals],
						displayValue,
						displayValues,
						isModified: false,
						isDeleted: false,
						isNew: false,
					};
				});
			} catch (error: any) {
				vm.propEditorError = error?.message || 'Failed to load properties';
			} finally {
				vm.propEditorLoading = false;
			}
		},
		startEditingProperty(prop: any) {
			const vm = this;
			if (prop.isDeleted) return;
			const hasSchemaMultiple = prop.schemaMultiple != null;
			const effectiveIsArray = hasSchemaMultiple ? prop.schemaMultiple : prop.isArray;
			vm.propEditorEditIsArrayLocked = hasSchemaMultiple;

			if (prop.type === 'BINARY') {
				vm.propEditorEditingName = prop.name;
				vm.propEditorEditType = 'BINARY';
				vm.propEditorEditIsArray = effectiveIsArray;
				vm.binaryEditIsUploading = false;
				vm.binaryEditProgress = 0;
				if (prop.isArray && (prop.currentValues as string[]).length > 0) {
					// Restore previously confirmed array state
					vm.binaryEditServerItems = [];
					vm.propEditorEditValues = [...(prop.currentValues as string[])];
					vm.propEditorEditInput = '';
					vm.binaryEditFileName = '';
					vm.binaryEditFileSizeFormatted = '';
					vm.binaryEditFileNames = [...((prop.binaryFileNames as string[]) || [])];
					vm.binaryEditFileSizesFormatted = [...((prop.binaryFileSizesFormatted as string[]) || [])];
					vm.binaryEditPreviewURLs = [...((prop.binaryPreviewURLs as string[]) || [])];
					vm.binaryEditFileMimeTypes = [...((prop.binaryFileMimeTypes as string[]) || [])];
				} else if (prop.isArray && ((prop.mimeTypes as string[]) || []).length > 0) {
					// Server-loaded BinaryPropertyValueArray: populate unified edit list
					const mimeTypes: string[] = prop.mimeTypes || [];
					const sizesFormatted: string[] = prop.sizesFormatted || [];
					const dlURLs: string[] = prop.propertyDownloadURLs || [];
					vm.binaryEditServerItems = [];
					vm.propEditorEditValues = mimeTypes.map((_mt: string, i: number) => '__keep:' + i);
					vm.propEditorEditInput = '';
					vm.binaryEditFileName = '';
					vm.binaryEditFileSizeFormatted = '';
					vm.binaryEditFileNames = mimeTypes.map((mt: string) => mt);
					vm.binaryEditFileSizesFormatted = sizesFormatted.map((s: string) => s);
					vm.binaryEditPreviewURLs = dlURLs.map((url: string) => url);
					vm.binaryEditFileMimeTypes = mimeTypes.map((mt: string) => mt);
				} else if (!prop.isArray && prop.currentValue) {
					// Restore previously confirmed single state
					vm.binaryEditServerItems = [];
					vm.propEditorEditInput = String(prop.currentValue);
					vm.binaryEditFileName = String((prop.binaryFileName as string) || '');
					vm.binaryEditFileSizeFormatted = String((prop.binaryFileSizeFormatted as string) || '');
					vm.binaryEditPreviewURL = String((prop.binaryPreviewURL as string) || '');
					vm.propEditorEditValues = [];
					vm.binaryEditFileNames = [];
					vm.binaryEditFileSizesFormatted = [];
					vm.binaryEditPreviewURLs = [];
					vm.binaryEditFileMimeTypes = [(prop.binaryFileMimeType as string) || ''];
				} else if (!prop.isArray && prop.mimeType) {
					// Server-loaded single BinaryPropertyValue: populate edit state
					vm.binaryEditServerItems = [];
					vm.propEditorEditInput = '__keep:0';
					vm.binaryEditFileName = prop.mimeType || '';
					vm.binaryEditFileSizeFormatted = prop.sizeFormatted || '';
					vm.binaryEditPreviewURL = prop.propertyDownloadURL || '';
					vm.propEditorEditValues = [];
					vm.binaryEditFileNames = [];
					vm.binaryEditFileSizesFormatted = [];
					vm.binaryEditPreviewURLs = [];
					vm.binaryEditFileMimeTypes = [prop.mimeType || ''];
				} else {
					// Fresh edit
					vm.binaryEditServerItems = [];
					vm.propEditorEditInput = '';
					vm.propEditorEditValues = [];
					vm.binaryEditFileName = '';
					vm.binaryEditFileSizeFormatted = '';
					vm.binaryEditFileNames = [];
					vm.binaryEditFileSizesFormatted = [];
					vm.binaryEditPreviewURLs = [];
					vm.binaryEditFileMimeTypes = [];
				}
				return;
			}
			vm.propEditorEditingName = prop.name;
			vm.propEditorEditType = prop.type;
			vm.propEditorEditIsArray = effectiveIsArray;
			if (effectiveIsArray) {
				vm.propEditorEditValues = [...prop.currentValues];
				vm.propEditorEditInput = '';
			} else {
				if (prop.type === 'DATE' && prop.currentValue) {
					vm.propEditorEditInput = vm.toLocalDatetimeInput(String(prop.currentValue));
				} else {
					vm.propEditorEditInput = String(prop.currentValue ?? '');
				}
				vm.propEditorEditValues = [];
			}
			vm.propEditorEditNewChip = '';
			// For REFERENCE/WEAKREFERENCE, initialize display paths
			if (prop.type === 'REFERENCE' || prop.type === 'WEAKREFERENCE') {
				if (prop.isArray) {
					vm.propEditorEditDisplayValues = [...(prop.displayValues || [])];
					vm.propEditorEditDisplayValue = '';
				} else {
					vm.propEditorEditDisplayValue = prop.displayValue || '';
					vm.propEditorEditDisplayValues = [];
				}
			} else if (prop.schemaChoices && prop.schemaChoices.length > 0) {
				// For properties with schema choices, resolve labels
				const choiceMap = new Map<string, string>();
				for (const c of prop.schemaChoices) {
					choiceMap.set(c.value, c.label || c.value);
				}
				if (effectiveIsArray) {
					vm.propEditorEditDisplayValues = (prop.currentValues as string[]).map(
						(v: string) => choiceMap.get(v) ?? v
					);
					vm.propEditorEditDisplayValue = '';
				} else {
					vm.propEditorEditDisplayValue = choiceMap.get(prop.currentValue) ?? prop.currentValue ?? '';
					vm.propEditorEditDisplayValues = [];
				}
			} else {
				vm.propEditorEditDisplayValue = '';
				vm.propEditorEditDisplayValues = [];
			}
			// Execute query for REFERENCE/WEAKREFERENCE with queryConfig
			if ((prop.type === 'REFERENCE' || prop.type === 'WEAKREFERENCE') && prop.queryConfig) {
				vm.executeQueryForRef(prop.queryConfig);
			} else {
				vm.propEditorQueryItems = [];
				vm.propEditorQueryConfig = null;
			}
			// Initialize CodeMirror for STRING + JSON/XML/HTML/Markdown
			const isCmType = prop.type === 'STRING' && !effectiveIsArray &&
				(prop.editorType === 'Text Editor' || prop.editorType === 'JSON' || prop.editorType === 'XML' || prop.editorType === 'HTML' || prop.editorType === 'Markdown');
			if (isCmType) {
				vm.cmExpanded = false;
				vm.cmPreview = false;
				vm.cmEditorType = prop.editorType;
				const initWhenReady = (attempts = 0) => {
					const container = document.getElementById('cm-editor-inline');
					if (container) {
						vm.initCodeMirrorEditor('cm-editor-inline', prop.editorType, vm.propEditorEditInput as string);
					} else if (attempts < 20) {
						requestAnimationFrame(() => initWhenReady(attempts + 1));
					}
				};
				vm.$nextTick(() => initWhenReady());
			} else {
				vm.destroyCodeMirrorEditor();
				vm.cmEditorType = '';
				vm.cmExpanded = false;
				vm.cmPreview = false;
			}
		},
		confirmPropertyEdit() {
			const vm = this;
			if (vm.propEditorEditError) return;
			const name = vm.propEditorEditingName;
			if (!name) return;
			const idx = (vm.propEditorItems as any[]).findIndex((p: any) => p.name === name);
			if (idx === -1) return;
			const prop = vm.propEditorItems[idx];

			// If a schema defines the type, override the current type
			const enriched = (vm.propEditorDisplayItems as any[]).find((p: any) => p.name === name);
			const schemaType = enriched?.schemaType as string | undefined;
			let typeChanged = false;
			if (schemaType && schemaType !== prop.type) {
				prop.type = schemaType;
				typeChanged = true;
			}

			const isRef = prop.type === 'REFERENCE' || prop.type === 'WEAKREFERENCE';
			if (vm.propEditorEditIsArray) {
				// Add any pending chip value before confirming (non-REFERENCE types only)
				if (!isRef) {
					const pendingChip = (vm.propEditorEditNewChip as string).trim();
					if (pendingChip) {
						const stored = prop.type === 'DATE'
							? new Date(pendingChip).toISOString()
							: pendingChip;
						(vm.propEditorEditValues as string[]).push(stored);
						vm.propEditorEditNewChip = '';
					}
				}
				const newValues = [...(vm.propEditorEditValues as string[])];
				const changed = typeChanged || JSON.stringify(newValues) !== JSON.stringify(prop.originalValues) || !prop.isArray;
				const update: any = {
					...prop,
					isArray: true,
					currentValues: newValues,
					currentValue: '',
					isModified: changed || prop.isNew,
				};
				if (isRef) {
					update.displayValues = [...(vm.propEditorEditDisplayValues as string[])];
				}
				if (prop.type === 'BINARY') {
					update.binaryPreviewURLs = [...(vm.binaryEditPreviewURLs as string[])];
					update.binaryFileNames = [...(vm.binaryEditFileNames as string[])];
					update.binaryFileSizesFormatted = [...(vm.binaryEditFileSizesFormatted as string[])];
					update.binaryFileMimeTypes = [...(vm.binaryEditFileMimeTypes as string[])];
					vm.binaryEditPreviewURLs = [];
					vm.binaryEditFileMimeTypes = [];
				}
				(vm.propEditorItems as any[]).splice(idx, 1, update);
			} else {
				let newValue = isRef ? (vm.propEditorEditInput as string) : (vm.propEditorEditInput as string);
				if (prop.type === 'DATE' && newValue) {
					newValue = new Date(newValue).toISOString();
				}
				// For binary __keep: values, no actual change was made
				const isKeptBinary = prop.type === 'BINARY' && typeof newValue === 'string' && newValue.startsWith('__keep:');
				const changed = isKeptBinary ? false : (typeChanged || newValue !== prop.originalValue || prop.isArray);
				const update: any = {
					...prop,
					isArray: false,
					currentValue: newValue,
					currentValues: [],
					isModified: changed || prop.isNew,
				};
				if (isRef) {
					update.displayValue = vm.propEditorEditDisplayValue as string;
				}
				if (prop.type === 'BINARY' && newValue) {
					update.binaryPreviewURL = vm.binaryEditPreviewURL as string;
					update.binaryFileName = vm.binaryEditFileName as string;
					update.binaryFileSizeFormatted = vm.binaryEditFileSizeFormatted as string;
					update.binaryFileMimeType = (vm.binaryEditFileMimeTypes as string[])[0] || '';
					vm.binaryEditPreviewURL = '';
				}
				(vm.propEditorItems as any[]).splice(idx, 1, update);
			}
			vm.propEditorEditingName = null;
			vm.destroyCodeMirrorEditor();
			if (vm.cmEscHandler) {
				document.removeEventListener('keydown', vm.cmEscHandler, true);
				vm.cmEscHandler = null;
			}
			vm.cmExpanded = false;
			vm.cmPreview = false;
		},
		cancelPropertyEdit() {
			const vm = this as any;
			vm.propEditorEditingName = null;
			vm.propEditorEditNewChip = '';
			vm.destroyCodeMirrorEditor();
			if (vm.cmEscHandler) {
				document.removeEventListener('keydown', vm.cmEscHandler, true);
				vm.cmEscHandler = null;
			}
			vm.cmExpanded = false;
			vm.cmPreview = false;
		},
		toggleEditArray() {
			const vm = this;
			const isRef = (vm.propEditorEditType as string) === 'REFERENCE' || (vm.propEditorEditType as string) === 'WEAKREFERENCE';
			const isBinary = (vm.propEditorEditType as string) === 'BINARY';
			vm.propEditorEditIsArray = !vm.propEditorEditIsArray;
			if (isBinary) {
				vm.binaryEditServerItems = [];
				if (vm.propEditorEditIsArray) {
					// single → array: carry the single value as the first array item
					const singleId = vm.propEditorEditInput as string;
					const singleName = vm.binaryEditFileName as string;
					const singleSize = vm.binaryEditFileSizeFormatted as string;
					const singlePreview = vm.binaryEditPreviewURL as string;
					const singleMime = (vm.binaryEditFileMimeTypes as string[])[0] || '';
					vm.propEditorEditInput = '';
					vm.binaryEditPreviewURL = '';
					vm.binaryEditFileName = '';
					vm.binaryEditFileSizeFormatted = '';
					if (singleId) {
						vm.propEditorEditValues = [singleId];
						vm.binaryEditFileNames = [singleName];
						vm.binaryEditFileSizesFormatted = [singleSize];
						vm.binaryEditPreviewURLs = [singlePreview];
						vm.binaryEditFileMimeTypes = [singleMime];
					} else {
						vm.propEditorEditValues = [];
						vm.binaryEditFileNames = [];
						vm.binaryEditFileSizesFormatted = [];
						vm.binaryEditPreviewURLs = [];
						vm.binaryEditFileMimeTypes = [];
					}
				} else {
					// array → single: take the first item as the single value
					const firstId = (vm.propEditorEditValues as string[])[0] || '';
					const firstName = (vm.binaryEditFileNames as string[])[0] || '';
					const firstSize = (vm.binaryEditFileSizesFormatted as string[])[0] || '';
					const firstPreview = (vm.binaryEditPreviewURLs as string[])[0] || '';
					const firstMime = (vm.binaryEditFileMimeTypes as string[])[0] || '';
					// Revoke Object URLs for discarded items (index > 0)
					for (let i = 1; i < (vm.binaryEditPreviewURLs as string[]).length; i++) {
						const u = (vm.binaryEditPreviewURLs as string[])[i];
						const id = (vm.propEditorEditValues as string[])[i];
						if (u && id && !id.startsWith('__keep:')) URL.revokeObjectURL(u);
					}
					vm.propEditorEditInput = firstId;
					vm.binaryEditFileName = firstName;
					vm.binaryEditFileSizeFormatted = firstSize;
					vm.binaryEditPreviewURL = firstPreview;
					vm.propEditorEditValues = [];
					vm.binaryEditFileNames = [];
					vm.binaryEditFileSizesFormatted = [];
					vm.binaryEditPreviewURLs = [];
					vm.binaryEditFileMimeTypes = [firstMime];
				}
				return;
			}
			if (vm.propEditorEditIsArray) {
				// single → array
				if (isRef) {
					const uuid = vm.propEditorEditInput as string;
					const disp = vm.propEditorEditDisplayValue as string;
					vm.propEditorEditValues = uuid ? [uuid] : [];
					vm.propEditorEditDisplayValues = disp ? [disp] : [];
					vm.propEditorEditInput = '';
					vm.propEditorEditDisplayValue = '';
				} else {
					// convert datetime-local input to ISO before storing
					const singleVal = vm.propEditorEditInput as string;
					let storedVal = singleVal;
					if ((vm.propEditorEditType as string) === 'DATE' && singleVal) {
						storedVal = new Date(singleVal).toISOString();
					}
					vm.propEditorEditValues = storedVal ? [storedVal] : [];
					vm.propEditorEditInput = '';
				}
			} else {
				// array → single
				if (isRef) {
					const firstUUID = (vm.propEditorEditValues as string[]).length > 0 ? (vm.propEditorEditValues as string[])[0] : '';
					const firstDisp = (vm.propEditorEditDisplayValues as string[]).length > 0 ? (vm.propEditorEditDisplayValues as string[])[0] : '';
					vm.propEditorEditInput = firstUUID;
					vm.propEditorEditDisplayValue = firstDisp;
					vm.propEditorEditValues = [];
					vm.propEditorEditDisplayValues = [];
				} else {
					// convert ISO to datetime-local format
					const firstVal = (vm.propEditorEditValues as string[]).length > 0 ? (vm.propEditorEditValues as string[])[0] : '';
					if ((vm.propEditorEditType as string) === 'DATE' && firstVal) {
						vm.propEditorEditInput = vm.toLocalDatetimeInput(firstVal);
					} else {
						vm.propEditorEditInput = firstVal;
					}
					vm.propEditorEditValues = [];
				}
			}
		},
		addEditChip() {
			const type = this.propEditorEditType as string;
			const defaultVal = type === 'BOOLEAN' ? 'true' : '';
			(this.propEditorEditValues as string[]).push(defaultVal);
		},
		updateEditChip(idx: number, val: string) {
			(this.propEditorEditValues as string[]).splice(idx, 1, val);
		},
		updateEditChipDate(idx: number, localVal: string) {
			const stored = localVal ? new Date(localVal).toISOString() : '';
			(this.propEditorEditValues as string[]).splice(idx, 1, stored);
		},
		removeEditChip(idx: number) {
			(this.propEditorEditValues as string[]).splice(idx, 1);
		},
		formatPropChipValue(val: string, type: string): string {
			if (type === 'DATE' && val) {
				try { return new Date(val).toLocaleString(); }
				catch { return val; }
			}
			return val;
		},
		formatDateLocal(val: string): string {
			try { return new Date(val).toLocaleString(); }
			catch { return val; }
		},
		moveEditChip(idx: number, dir: number) {
			const arr = this.propEditorEditValues as string[];
			const target = idx + dir;
			if (target < 0 || target >= arr.length) return;
			const tmp = arr[idx];
			arr.splice(idx, 1);
			arr.splice(target, 0, tmp);
		},
		// REFERENCE / WEAKREFERENCE drop zone methods
		async loadQueriesForSchema(schemaKey: string) {
			const vm = this;
			if (!schemaKey) return;
			const schema = vm.availableSchemas.find((s: any) => s.key === schemaKey);
			if (!schema) return;
			const promises: Promise<void>[] = [];
			for (const sp of schema.properties) {
				if (sp.query && !vm.propEditorQueryCache[sp.query.xpath]) {
					promises.push(vm.executeQueryForRef(sp.query).then(() => {}));
				}
			}
			if (promises.length > 0) {
				await Promise.all(promises);
			} else {
				// Trigger reactivity even when all queries are cached
				vm.propEditorQueryCache = { ...vm.propEditorQueryCache };
			}
		},
		async executeQueryForRef(queryConfig: { xpath: string; labelKey: string }) {
			const vm = this;
			vm.propEditorQueryConfig = queryConfig;
			// Use cache if available
			const cached = vm.propEditorQueryCache[queryConfig.xpath];
			if (cached) {
				vm.propEditorQueryItems = cached;
				vm.propEditorQueryLoading = false;
				return;
			}
			vm.propEditorQueryLoading = true;
			vm.propEditorQueryItems = [];
			try {
				const contentService = vm.instance.api.content;
				const result = await contentService.xpathWithProperties(queryConfig.xpath, { first: 100 });
				const items: { value: string; label: string }[] = [];
				for (const edge of result.edges) {
					const node = edge.node;
					// Value is always the node's uuid
					const value = node.uuid || '';
					if (!value) continue;
					// Resolve label
					let label = '';
					if (queryConfig.labelKey === 'jcr:name') {
						label = node.name;
					} else if (queryConfig.labelKey === 'jcr:path' || queryConfig.labelKey === 'path') {
						label = node.path;
					} else {
						const prop = node.properties.find((p: any) => p.name === queryConfig.labelKey);
						if (prop?.propertyValue) {
							label = String((prop.propertyValue as any).value ?? '');
						}
					}
					items.push({ value, label: label || node.name });
				}
				vm.propEditorQueryItems = items;
				vm.propEditorQueryCache = { ...vm.propEditorQueryCache, [queryConfig.xpath]: items };
			} catch (e: any) {
				console.error('Failed to execute query for reference:', e);
			} finally {
				vm.propEditorQueryLoading = false;
			}
		},
		async refreshQueryItems() {
			const vm = this;
			if (!vm.propEditorQueryConfig) return;
			// Clear cache for this query and re-fetch
			const { [vm.propEditorQueryConfig.xpath]: _, ...rest } = vm.propEditorQueryCache;
			vm.propEditorQueryCache = rest;
			await vm.executeQueryForRef(vm.propEditorQueryConfig);
		},
		// Initialize CodeMirror editor for structured text (JSON/XML/HTML/Markdown)
		initCodeMirrorEditor(containerId: string, editorType: string, initialValue: string) {
			const vm = this;
			const container = document.getElementById(containerId);
			if (!container) return;

			// Destroy existing editor if any
			vm.destroyCodeMirrorEditor();

			const languageCompartment = new Compartment();
			const themeCompartment = new Compartment();
			vm.cmLanguageCompartment = languageCompartment;
			vm.cmThemeCompartment = themeCompartment;
			vm.cmEditorType = editorType;

			// Single CSS-variable-based theme — automatically follows light/dark switch.
			const editorTheme = cmTheme;

			const updateListener = EditorView.updateListener.of((update) => {
				if (update.docChanged) {
					const content = update.state.doc.toString();
					vm.propEditorEditInput = content;
					if (vm.cmPreview && (editorType === 'Markdown' || editorType === 'HTML')) {
						vm.updateCmPreview(content);
					}
				}
			});

			// Custom Find/Replace UI is only available in expanded mode.
			// The built-in panel is suppressed (createPanel returns an empty DOM).
			const searchKeyBindings = vm.cmExpanded ? [
				{ key: "Mod-f", run: () => { vm.openCmSearch(); return true; } },
				{ key: "Mod-h", run: () => { vm.openCmSearch(); return true; } },
				{ key: "F3", run: (view: EditorView) => findNext(view) },
				{ key: "Shift-F3", run: (view: EditorView) => findPrevious(view) },
			] : [];
			const searchExtension = vm.cmExpanded ? [
				search({ top: true, createPanel: () => ({ dom: document.createElement('div') }) }),
			] : [];

			const state = EditorState.create({
				doc: initialValue,
				extensions: [
					lineNumbers(),
					highlightActiveLine(),
					history(),
					drawSelection(),
					bracketMatching(),
					indentOnInput(),
					syntaxHighlighting(cmHighlight, { fallback: true }),
					...searchExtension,
					keymap.of([...searchKeyBindings, ...defaultKeymap, ...historyKeymap]),
					themeCompartment.of(editorTheme),
					languageCompartment.of(getLanguageExtensionForEditorType(editorType)),
					vm.cmExpanded ? lintGutter() : [],
					updateListener,
				],
			});

			vm.cmEditor = (vm as any).$markRaw ? (vm as any).$markRaw(new EditorView({ state, parent: container })) : new EditorView({ state, parent: container });

			if (vm.cmPreview && (editorType === 'Markdown' || editorType === 'HTML')) {
				vm.updateCmPreview(initialValue);
			}
		},
		destroyCodeMirrorEditor() {
			const vm = this;
			if (vm.cmEditor) {
				vm.cmEditor.destroy();
				vm.cmEditor = null;
			}
			vm.cmLanguageCompartment = null;
			vm.cmThemeCompartment = null;
		},
		toggleCmExpanded() {
			const vm = this;
			const editorType = vm.cmEditorType;
			const currentValue = vm.cmEditor ? vm.cmEditor.state.doc.toString() : (vm.propEditorEditInput as string);
			vm.cmExpanded = !vm.cmExpanded;
			if (!vm.cmExpanded) {
				vm.cmPreview = false;
			}
			const containerId = vm.cmExpanded ? 'cm-editor-expanded' : 'cm-editor-inline';
			const initWhenReady = (attempts = 0) => {
				if (document.getElementById(containerId)) {
					vm.initCodeMirrorEditor(containerId, editorType, currentValue);
				} else if (attempts < 20) {
					requestAnimationFrame(() => initWhenReady(attempts + 1));
				}
			};
			vm.$nextTick(() => initWhenReady());
			// Manage Esc key handler
			if (vm.cmExpanded) {
				vm.cmEscHandler = (e: KeyboardEvent) => {
					if (e.key === 'Escape') {
						// If the custom search bar is open, close it first
						if (vm.cmSearchVisible) {
							e.preventDefault();
							e.stopPropagation();
							e.stopImmediatePropagation();
							vm.closeCmSearch();
							return;
						}
						e.preventDefault();
						e.stopPropagation();
						e.stopImmediatePropagation();
						if (vm.cmExpanded) vm.toggleCmExpanded();
					}
				};
				document.addEventListener('keydown', vm.cmEscHandler, true);
			} else if (vm.cmEscHandler) {
				document.removeEventListener('keydown', vm.cmEscHandler, true);
				vm.cmEscHandler = null;
			}
		},
		openCmSearch() {
			const vm = this;
			if (!vm.cmEditor) return;
			vm.cmSearchVisible = true;
			vm.cmSearchNotFound = false;
			vm.applyCmSearchQuery();
			vm.$nextTick(() => {
				const el = document.getElementById('cm-search-input') as HTMLInputElement | null;
				if (el) { el.focus(); el.select(); }
			});
		},
		closeCmSearch() {
			const vm = this;
			vm.cmSearchVisible = false;
			vm.cmSearchNotFound = false;
			if (vm.cmEditor) vm.cmEditor.focus();
		},
		// Push the current search settings to CodeMirror
		applyCmSearchQuery() {
			const vm = this;
			if (!vm.cmEditor) return;
			const query = new SearchQuery({
				search: vm.cmSearchTerm,
				caseSensitive: vm.cmSearchCaseSensitive,
				regexp: vm.cmSearchRegex,
				wholeWord: vm.cmSearchWholeWord,
				replace: vm.cmReplaceTerm,
			});
			vm.cmEditor.dispatch({ effects: setSearchQuery.of(query) });
		},
		onCmSearchInput() {
			this.cmSearchNotFound = false;
			this.applyCmSearchQuery();
		},
		toggleCmSearchCaseSensitive() {
			this.cmSearchCaseSensitive = !this.cmSearchCaseSensitive;
			this.applyCmSearchQuery();
		},
		toggleCmSearchRegex() {
			this.cmSearchRegex = !this.cmSearchRegex;
			this.applyCmSearchQuery();
		},
		toggleCmSearchWholeWord() {
			this.cmSearchWholeWord = !this.cmSearchWholeWord;
			this.applyCmSearchQuery();
		},
		cmFindNext() {
			const vm = this;
			if (!vm.cmEditor || !vm.cmSearchTerm) return;
			vm.applyCmSearchQuery();
			vm.cmSearchNotFound = !findNext(vm.cmEditor);
		},
		cmFindPrev() {
			const vm = this;
			if (!vm.cmEditor || !vm.cmSearchTerm) return;
			vm.applyCmSearchQuery();
			vm.cmSearchNotFound = !findPrevious(vm.cmEditor);
		},
		cmReplaceNext() {
			const vm = this;
			if (!vm.cmEditor || !vm.cmSearchTerm) return;
			vm.applyCmSearchQuery();
			vm.cmSearchNotFound = !replaceNext(vm.cmEditor);
		},
		cmReplaceAll() {
			const vm = this;
			if (!vm.cmEditor || !vm.cmSearchTerm) return;
			vm.applyCmSearchQuery();
			vm.cmSearchNotFound = !replaceAll(vm.cmEditor);
		},
		onCmSearchKeydown(event: KeyboardEvent) {
			if (event.key === 'Enter') {
				event.preventDefault();
				if (event.shiftKey) this.cmFindPrev(); else this.cmFindNext();
			} else if (event.key === 'Escape') {
				event.preventDefault();
				this.closeCmSearch();
			}
		},
		onCmReplaceKeydown(event: KeyboardEvent) {
			if (event.key === 'Enter') {
				event.preventDefault();
				if (event.ctrlKey || event.metaKey) this.cmReplaceAll(); else this.cmReplaceNext();
			} else if (event.key === 'Escape') {
				event.preventDefault();
				this.closeCmSearch();
			}
		},
		formatCmContent() {
			const vm = this;
			if (!vm.cmEditor) return;
			const current = vm.cmEditor.state.doc.toString();
			const formatted = formatStructuredText(current, vm.cmEditorType);
			if (formatted === current) return;
			vm.cmEditor.dispatch({
				changes: { from: 0, to: current.length, insert: formatted },
			});
		},
		toggleCmPreview() {
			const vm = this;
			vm.cmPreview = !vm.cmPreview;
			if (vm.cmPreview && vm.cmEditor) {
				vm.updateCmPreview(vm.cmEditor.state.doc.toString());
			}
		},
		updateCmPreview(content: string) {
			const vm = this;
			try {
				if (vm.cmEditorType === 'Markdown') {
					marked.setOptions({ breaks: true, gfm: true });
					vm.cmPreviewHtml = marked.parse(content) as string;
				} else if (vm.cmEditorType === 'HTML') {
					vm.cmPreviewHtml = content;
				}
			} catch {
				vm.cmPreviewHtml = '<p style="color:var(--error-color)">Preview error</p>';
			}
		},
		selectQueryItem(item: { value: string; label: string }) {
			const vm = this;
			if (vm.propEditorEditIsArray) {
				// Multiple: toggle
				const values = vm.propEditorEditValues as string[];
				const idx = values.indexOf(item.value);
				if (idx >= 0) {
					values.splice(idx, 1);
					(vm.propEditorEditDisplayValues as string[]).splice(idx, 1);
				} else {
					values.push(item.value);
					(vm.propEditorEditDisplayValues as string[]).push(item.label);
				}
			} else {
				// Single: set or clear
				if (vm.propEditorEditInput === item.value) {
					vm.propEditorEditInput = '';
					vm.propEditorEditDisplayValue = '';
				} else {
					vm.propEditorEditInput = item.value;
					vm.propEditorEditDisplayValue = item.label;
				}
			}
		},
		isQueryItemSelected(value: string): boolean {
			if (this.propEditorEditIsArray) {
				return (this.propEditorEditValues as string[]).includes(value);
			}
			return this.propEditorEditInput === value;
		},
		openRefBrowser(currentDisplayPath: string) {
			let initialPath = '/';
			if (currentDisplayPath) {
				const lastSlash = currentDisplayPath.lastIndexOf('/');
				if (lastSlash > 0) {
					initialPath = currentDisplayPath.substring(0, lastSlash);
				} else if (lastSlash === 0) {
					initialPath = '/';
				}
			}
			window.parent.postMessage({
				type: 'open-app',
				appId: '2468cf47-1a30-4053-b80a-9c5486954b08',
				options: { initialPath },
			}, window.location.origin);
		},
		onRefDragOver(slotKey: string, event: DragEvent) {
			if (!event.dataTransfer?.types.includes('application/x-webtop-file')) return;
			event.preventDefault();
			event.dataTransfer.dropEffect = 'copy';
			this.refDragOverProp = slotKey;
		},
		onRefDragLeave(slotKey: string, _event: DragEvent) {
			if (this.refDragOverProp === slotKey) {
				this.refDragOverProp = null;
			}
		},
		_parseWebtopFileDrop(event: DragEvent): { path: string; name: string; mimeType?: string; uuid?: string; isReferenceable?: boolean; isCollection?: boolean; downloadURL?: string } | null {
			const raw = event.dataTransfer?.getData('application/x-webtop-file');
			if (!raw) return null;
			try { return JSON.parse(raw); }
			catch { return null; }
		},
		onRefPropDropSingle(event: DragEvent) {
			const vm = this;
			event.preventDefault();
			vm.refDragOverProp = null;
			const file = vm._parseWebtopFileDrop(event);
			if (!file || !file.isReferenceable || !file.uuid) return;
			if (file.path === vm.selectedItem?.path) return;
			vm.propEditorEditInput = file.uuid;
			vm.propEditorEditDisplayValue = file.path;
		},
		onRefPropDropArrayItem(idx: number, event: DragEvent) {
			const vm = this;
			event.preventDefault();
			vm.refDragOverProp = null;
			const file = vm._parseWebtopFileDrop(event);
			if (!file || !file.isReferenceable || !file.uuid) return;
			if (file.path === vm.selectedItem?.path) return;
			const values = vm.propEditorEditValues as string[];
			const dispValues = vm.propEditorEditDisplayValues as string[];
			values.splice(idx, 1, file.uuid);
			dispValues.splice(idx, 1, file.path);
		},
		onRefPropDropArrayAdd(event: DragEvent) {
			const vm = this;
			event.preventDefault();
			vm.refDragOverProp = null;
			const file = vm._parseWebtopFileDrop(event);
			if (!file || !file.isReferenceable || !file.uuid) return;
			if (file.path === vm.selectedItem?.path) return;
			(vm.propEditorEditValues as string[]).push(file.uuid);
			(vm.propEditorEditDisplayValues as string[]).push(file.path);
		},
		clearRefSingleItem() {
			this.propEditorEditInput = '';
			this.propEditorEditDisplayValue = '';
		},
		removeRefEditChip(idx: number) {
			(this.propEditorEditValues as string[]).splice(idx, 1);
			(this.propEditorEditDisplayValues as string[]).splice(idx, 1);
		},
		moveRefEditChip(idx: number, dir: number) {
			const values = this.propEditorEditValues as string[];
			const dispValues = this.propEditorEditDisplayValues as string[];
			const target = idx + dir;
			if (target < 0 || target >= values.length) return;
			const tmpVal = values[idx];
			const tmpDisp = dispValues[idx];
			values.splice(idx, 1);
			values.splice(target, 0, tmpVal);
			dispValues.splice(idx, 1);
			dispValues.splice(target, 0, tmpDisp);
		},
		// ── Binary property inline editing ───────────────────────────────────────
		async _resolveDropToFile(event: DragEvent): Promise<File | null> {
			const dt = event.dataTransfer;
			if (!dt) return null;
			// Webtop internal file: fetch content from CMS
			if (dt.types.includes('application/x-webtop-file')) {
				const raw = dt.getData('application/x-webtop-file');
				try {
					const info = JSON.parse(raw);
					if (info.isCollection) return null;
					if (!info.downloadURL) return null;
					const response = await fetch(info.downloadURL, { credentials: 'same-origin' });
					if (!response.ok) return null;
					const blob = await response.blob();
					return new File([blob], info.name, { type: blob.type || info.mimeType || 'application/octet-stream' });
				} catch { return null; }
			}
			// OS file
			if (dt.files && dt.files.length > 0) return dt.files[0];
			return null;
		},
		async _uploadFileToBinaryProp(file: File): Promise<{ uploadId: string; fileName: string; sizeFormatted: string; previewURL: string; mimeType: string }> {
			const vm = this;
			const contentService = vm.instance.api.content;
			const CHUNK = 524288;
			const uploadInfo = await contentService.initiateMultipartUpload();
			const uploadId = uploadInfo.uploadId;
			try {
				const totalSize = file.size;
				let offset = 0;
				while (offset < totalSize) {
					const blob = file.slice(offset, offset + CHUNK);
					const encoded = await new Promise<string>((resolve) => {
						const reader = new FileReader();
						reader.onloadend = (e) => {
							const result = (e.target as FileReader).result as string;
							resolve(result.split(',')[1] || '');
						};
						reader.readAsDataURL(blob);
					});
					await contentService.appendMultipartUploadChunk(uploadId, encoded);
					offset += CHUNK;
					vm.binaryEditProgress = Math.min(99, Math.floor((offset / totalSize) * 100));
				}
				vm.binaryEditProgress = 100;
				const previewURL = URL.createObjectURL(file);
				return { uploadId, fileName: file.name, sizeFormatted: vm.instance.util.bytes.format(file.size, { short: true }), previewURL, mimeType: file.type };
			} catch (err) {
				try { await contentService.abortMultipartUpload(uploadId); } catch {}
				throw err;
			}
		},
		onBinaryDragOver(event: DragEvent) {
			if (this.binaryEditIsUploading) return;
			const dt = event.dataTransfer;
			if (!dt) return;
			if (!dt.types.includes('application/x-webtop-file') && !dt.types.includes('Files')) return;
			event.preventDefault();
			event.stopPropagation();
			dt.dropEffect = 'copy';
			this.binaryEditDragOver = true;
		},
		onBinaryDragLeave(_event: DragEvent) {
			this.binaryEditDragOver = false;
		},
		onBinaryArrayDragOver(key: number, event: DragEvent) {
			if (this.binaryEditIsUploading) return;
			const dt = event.dataTransfer;
			if (!dt) return;
			if (!dt.types.includes('application/x-webtop-file') && !dt.types.includes('Files')) return;
			event.preventDefault();
			event.stopPropagation();
			dt.dropEffect = 'copy';
			this.binaryEditArrayDragOver = key;
		},
		onBinaryArrayDragLeave(key: number, _event: DragEvent) {
			if (this.binaryEditArrayDragOver === key) this.binaryEditArrayDragOver = null;
		},
		async onBinaryDropSingle(event: DragEvent) {
			const vm = this;
			event.preventDefault();
			event.stopPropagation();
			vm.binaryEditDragOver = false;
			if (vm.binaryEditIsUploading) return;
			try {
				vm.binaryEditIsUploading = true;
				vm.binaryEditProgress = 0;
				const file = await vm._resolveDropToFile(event);
				if (!file) return;
				const { uploadId, fileName, sizeFormatted, previewURL, mimeType } = await vm._uploadFileToBinaryProp(file);
				const prevId = vm.propEditorEditInput as string;
				if (vm.binaryEditPreviewURL && !prevId.startsWith('__keep:')) URL.revokeObjectURL(vm.binaryEditPreviewURL as string);
				vm.propEditorEditInput = uploadId;
				vm.binaryEditFileName = fileName;
				vm.binaryEditFileSizeFormatted = sizeFormatted;
				vm.binaryEditPreviewURL = previewURL;
				vm.binaryEditFileMimeTypes = [mimeType];
			} catch {
				vm.propEditorEditInput = '';
				vm.binaryEditFileName = '';
				vm.binaryEditFileSizeFormatted = '';
			} finally {
				vm.binaryEditIsUploading = false;
			}
		},
		async onBinaryDropArrayItem(idx: number, event: DragEvent) {
			const vm = this;
			event.preventDefault();
			event.stopPropagation();
			vm.binaryEditArrayDragOver = null;
			if (vm.binaryEditIsUploading) return;
			try {
				vm.binaryEditIsUploading = true;
				vm.binaryEditProgress = 0;
				const file = await vm._resolveDropToFile(event);
				if (!file) return;
				const { uploadId, fileName, sizeFormatted, previewURL, mimeType } = await vm._uploadFileToBinaryProp(file);
				const prevItemId = (vm.propEditorEditValues as string[])[idx];
				const prevURL = (vm.binaryEditPreviewURLs as string[])[idx];
				if (prevURL && !prevItemId.startsWith('__keep:')) URL.revokeObjectURL(prevURL);
				(vm.propEditorEditValues as string[]).splice(idx, 1, uploadId);
				(vm.binaryEditFileNames as string[]).splice(idx, 1, fileName);
				(vm.binaryEditFileSizesFormatted as string[]).splice(idx, 1, sizeFormatted);
				(vm.binaryEditPreviewURLs as string[]).splice(idx, 1, previewURL);
				(vm.binaryEditFileMimeTypes as string[]).splice(idx, 1, mimeType);
			} catch {} finally {
				vm.binaryEditIsUploading = false;
			}
		},
		async onBinaryDropArrayAdd(event: DragEvent) {
			const vm = this;
			event.preventDefault();
			event.stopPropagation();
			vm.binaryEditArrayDragOver = null;
			if (vm.binaryEditIsUploading) return;
			try {
				vm.binaryEditIsUploading = true;
				vm.binaryEditProgress = 0;
				const file = await vm._resolveDropToFile(event);
				if (!file) return;
				const { uploadId, fileName, sizeFormatted, previewURL, mimeType } = await vm._uploadFileToBinaryProp(file);
				(vm.propEditorEditValues as string[]).push(uploadId);
				(vm.binaryEditFileNames as string[]).push(fileName);
				(vm.binaryEditFileSizesFormatted as string[]).push(sizeFormatted);
				(vm.binaryEditPreviewURLs as string[]).push(previewURL);
				(vm.binaryEditFileMimeTypes as string[]).push(mimeType);
			} catch {} finally {
				vm.binaryEditIsUploading = false;
			}
		},
		clearBinarySingleItem() {
			const vm = this;
			const itemId = vm.propEditorEditInput as string;
			const url = vm.binaryEditPreviewURL as string;
			if (url && !itemId.startsWith('__keep:')) URL.revokeObjectURL(url);
			vm.propEditorEditInput = '';
			vm.binaryEditPreviewURL = '';
			vm.binaryEditFileName = '';
			vm.binaryEditFileSizeFormatted = '';
			vm.binaryEditFileMimeTypes = [];
		},
		removeBinaryEditItem(idx: number) {
			const itemId = (this.propEditorEditValues as string[])[idx];
			const url = (this.binaryEditPreviewURLs as string[])[idx];
			// Only revoke blob URLs (not server download URLs for __keep: items)
			if (url && !itemId.startsWith('__keep:')) URL.revokeObjectURL(url);
			(this.propEditorEditValues as string[]).splice(idx, 1);
			(this.binaryEditFileNames as string[]).splice(idx, 1);
			(this.binaryEditFileSizesFormatted as string[]).splice(idx, 1);
			(this.binaryEditPreviewURLs as string[]).splice(idx, 1);
			(this.binaryEditFileMimeTypes as string[]).splice(idx, 1);
		},
		moveBinaryEditItem(idx: number, dir: number) {
			const uploadIds = this.propEditorEditValues as string[];
			const fileNames = this.binaryEditFileNames as string[];
			const sizes = this.binaryEditFileSizesFormatted as string[];
			const target = idx + dir;
			if (target < 0 || target >= uploadIds.length) return;
			const previewURLs = this.binaryEditPreviewURLs as string[];
			const mimeTypes = this.binaryEditFileMimeTypes as string[];
			[uploadIds[idx], uploadIds[target]] = [uploadIds[target], uploadIds[idx]];
			[fileNames[idx], fileNames[target]] = [fileNames[target], fileNames[idx]];
			[sizes[idx], sizes[target]] = [sizes[target], sizes[idx]];
			[previewURLs[idx], previewURLs[target]] = [previewURLs[target], previewURLs[idx]];
			[mimeTypes[idx], mimeTypes[target]] = [mimeTypes[target], mimeTypes[idx]];
		},
		toLocalDatetimeInput(isoStr: string): string {
			try {
				const d = new Date(isoStr);
				const pad = (n: number) => String(n).padStart(2, '0');
				return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
			} catch {
				return isoStr.substring(0, 16);
			}
		},
		moveNewPropChip(idx: number, dir: number) {
			const arr = this.propEditorNewValues as string[];
			const target = idx + dir;
			if (target < 0 || target >= arr.length) return;
			const tmp = arr[idx];
			arr.splice(idx, 1);
			arr.splice(target, 0, tmp);
		},
		markPropertyDeleted(prop: any) {
			const vm = this;
			if (vm.propEditorEditingName === prop.name) {
				vm.propEditorEditingName = null;
			}
			const idx = (vm.propEditorItems as any[]).findIndex((p: any) => p.name === prop.name);
			if (idx === -1) return;
			if (vm.propEditorItems[idx].isNew) {
				(vm.propEditorItems as any[]).splice(idx, 1);
			} else {
				(vm.propEditorItems as any[]).splice(idx, 1, { ...vm.propEditorItems[idx], isDeleted: true, isModified: false });
			}
		},
		unmarkPropertyDeleted(prop: any) {
			const vm = this;
			const idx = (vm.propEditorItems as any[]).findIndex((p: any) => p.name === prop.name);
			if (idx === -1) return;
			const p = vm.propEditorItems[idx];
			const isModified = p.isArray
				? JSON.stringify(p.currentValues) !== JSON.stringify(p.originalValues)
				: p.currentValue !== p.originalValue;
			(vm.propEditorItems as any[]).splice(idx, 1, { ...p, isDeleted: false, isModified });
		},
		startAddingProperty() {
			const vm = this;
			vm.propEditorAddingNew = true;
			vm.propEditorNewName = '';
			vm.propEditorNewType = 'STRING';
			vm.propEditorNewIsArray = false;
			vm.propEditorNewValue = '';
			vm.propEditorNewValues = [];
			vm.propEditorNewChip = '';
		},
		cancelAddProperty() {
			this.propEditorAddingNew = false;
		},
		// Build a new property item from a schema property definition.
		// Applies the static default value if available.
		buildNewPropItemFromSchema(schemaProp: any): any {
			const isArray = typeof schemaProp.multiple === 'boolean' ? schemaProp.multiple : false;
			let initialValue = '';
			let initialValues: string[] = [];
			if (schemaProp.defaultValue && schemaProp.defaultValue.type === 'STATIC' && schemaProp.defaultValue.value != null) {
				if (isArray) initialValues = [String(schemaProp.defaultValue.value)];
				else initialValue = String(schemaProp.defaultValue.value);
			}
			return {
				name: schemaProp.key,
				type: schemaProp.type || 'STRING',
				isArray,
				originalValue: '',
				originalValues: [],
				currentValue: initialValue,
				currentValues: initialValues,
				isModified: true,
				isDeleted: false,
				isNew: true,
			};
		},
		// Evaluate calculated default value formula. The formula uses the same
		// ctx API as displayFormat (`ctx.get`, `ctx.getString`, etc.). Returns
		// a string or null.
		evaluateCalculatedDefault(formula: string, allPropsRaw: Map<string, { value: any; values: any[] }>): string | null {
			try {
				const ctx = {
					get(propName: string, defaultValue?: any): any {
						const entry = allPropsRaw.get(propName);
						if (!entry || entry.value == null) return defaultValue ?? null;
						return entry.value;
					},
					getString(propName: string, defaultValue?: string): string {
						const entry = allPropsRaw.get(propName);
						if (!entry || entry.value == null) return defaultValue ?? '';
						return String(entry.value);
					},
					getNumber(propName: string, defaultValue?: number): number {
						const entry = allPropsRaw.get(propName);
						if (!entry || entry.value == null) return defaultValue ?? 0;
						const n = Number(entry.value);
						return isNaN(n) ? (defaultValue ?? 0) : n;
					},
					getValues(propName: string, defaultValue?: any[]): any[] {
						const entry = allPropsRaw.get(propName);
						if (!entry || !entry.values) return defaultValue ?? [];
						return entry.values;
					},
				};
				const fn = new Function('ctx', formula);
				const result = fn(ctx);
				return result == null ? null : String(result);
			} catch {
				return null;
			}
		},
		// Add all missing properties from the selected schema. Static defaults
		// are applied immediately. Calculated defaults are then evaluated in
		// dependency order based on which property names they reference.
		addAllMissingProperties() {
			const vm = this;
			const missing = vm.propEditorMissingProperties;
			const all = [...missing.required, ...missing.optional];
			if (all.length === 0) return;

			// Step 1: add all missing properties with static defaults applied
			const newItems: any[] = [];
			for (const sp of all) {
				const item = vm.buildNewPropItemFromSchema(sp);
				(vm.propEditorItems as any[]).push(item);
				newItems.push({ schemaProp: sp, item });
			}

			// Step 2: collect calculated-default properties and resolve in
			// dependency order. The formula text is scanned for ctx.get('name'),
			// ctx.getString('name'), etc. — any referenced name that is also a
			// calculated-default item is treated as a dependency.
			const calcItems = newItems.filter((ni: any) =>
				ni.schemaProp.defaultValue
				&& ni.schemaProp.defaultValue.type === 'CALCULATED'
				&& ni.schemaProp.defaultValue.value
			);
			const calcKeys = new Set<string>(calcItems.map((ci: any) => ci.schemaProp.key));
			const depMap = new Map<string, Set<string>>();
			const calcRefRe = /ctx\.\w+\s*\(\s*['"]([^'"]+)['"]/g;
			for (const ci of calcItems) {
				const deps = new Set<string>();
				const formula = ci.schemaProp.defaultValue.value as string;
				let m: RegExpExecArray | null;
				calcRefRe.lastIndex = 0;
				while ((m = calcRefRe.exec(formula)) != null) {
					const ref = m[1];
					if (calcKeys.has(ref) && ref !== ci.schemaProp.key) {
						deps.add(ref);
					}
				}
				depMap.set(ci.schemaProp.key, deps);
			}
			// Topological sort (Kahn's algorithm)
			const ordered: any[] = [];
			const remaining = new Map<string, Set<string>>();
			for (const [k, deps] of depMap) remaining.set(k, new Set(deps));
			while (remaining.size > 0) {
				const ready: string[] = [];
				for (const [k, deps] of remaining) {
					if (deps.size === 0) ready.push(k);
				}
				if (ready.length === 0) {
					// Cyclic — process the rest in arbitrary order
					for (const k of remaining.keys()) ready.push(k);
				}
				for (const k of ready) {
					const ci = calcItems.find((c: any) => c.schemaProp.key === k);
					if (ci) ordered.push(ci);
					remaining.delete(k);
					for (const deps of remaining.values()) deps.delete(k);
				}
			}

			// Step 3: evaluate calculated defaults in order. After each
			// evaluation the property's value is set so subsequent formulas
			// can reference it through the ctx lookup.
			const buildLookup = () => {
				const map = new Map<string, { value: any; values: any[] }>();
				for (const p of vm.propEditorItems as any[]) {
					if (p.isDeleted) continue;
					const val = p.isArray ? (p.currentValues.length > 0 ? p.currentValues[0] : null) : (p.currentValue || null);
					const vals = p.isArray ? [...p.currentValues] : [p.currentValue];
					map.set(p.name, { value: val, values: vals });
				}
				return map;
			};
			for (const ci of ordered) {
				const lookup = buildLookup();
				const result = vm.evaluateCalculatedDefault(ci.schemaProp.defaultValue.value, lookup);
				if (result != null) {
					if (ci.item.isArray) ci.item.currentValues = [result];
					else ci.item.currentValue = result;
				}
			}
		},
		confirmAddProperty() {
			const vm = this;
			const name = (vm.propEditorNewName as string).trim();
			if (!name) return;
			if ((vm.propEditorItems as any[]).some((p: any) => p.name === name)) return;

			// If a schema is selected and defines this property, use the schema
			// definition (type/multiple/default value).
			let schemaProp: any = null;
			if (vm.propEditorSchemaKey) {
				const schema = (vm.availableSchemas as any[]).find((s: any) => s.key === vm.propEditorSchemaKey);
				schemaProp = schema?.properties?.find((p: any) => p.key === name);
			}

			let newProp: any;
			if (schemaProp) {
				newProp = vm.buildNewPropItemFromSchema(schemaProp);
				// If the schema has a calculated default and other properties
				// are already present, evaluate it now.
				if (schemaProp.defaultValue && schemaProp.defaultValue.type === 'CALCULATED' && schemaProp.defaultValue.value) {
					const lookup = new Map<string, { value: any; values: any[] }>();
					for (const p of vm.propEditorItems as any[]) {
						if (p.isDeleted) continue;
						const val = p.isArray ? (p.currentValues.length > 0 ? p.currentValues[0] : null) : (p.currentValue || null);
						const vals = p.isArray ? [...p.currentValues] : [p.currentValue];
						lookup.set(p.name, { value: val, values: vals });
					}
					const result = vm.evaluateCalculatedDefault(schemaProp.defaultValue.value, lookup);
					if (result != null) {
						if (newProp.isArray) newProp.currentValues = [result];
						else newProp.currentValue = result;
					}
				}
			} else {
				newProp = {
					name,
					type: vm.propEditorNewType,
					isArray: vm.propEditorNewIsArray,
					originalValue: '',
					originalValues: [],
					currentValue: '',
					currentValues: [],
					isModified: true,
					isDeleted: false,
					isNew: true,
				};
			}
			(vm.propEditorItems as any[]).push(newProp);
			vm.propEditorAddingNew = false;
			// Immediately open inline editing for the new property — use the
			// enriched version from the display items computed so that schema
			// metadata (editorType, rows, choices, etc.) is available.
			vm.$nextTick(() => {
				const enriched = (vm.propEditorDisplayItems as any[]).find((p: any) => p.name === name);
				vm.startEditingProperty(enriched || newProp);
			});
		},
		addNewPropChip() {
			const val = (this.propEditorNewChip as string).trim();
			if (val) {
				const stored = (this.propEditorNewType as string) === 'DATE'
					? new Date(val).toISOString()
					: val;
				(this.propEditorNewValues as string[]).push(stored);
				this.propEditorNewChip = '';
			}
		},
		removeNewPropChip(idx: number) {
			(this.propEditorNewValues as string[]).splice(idx, 1);
		},
		displayPropValue(prop: any): string {
			if (prop.isDeleted) return '(deleted)';
			if (prop.type === 'REFERENCE' || prop.type === 'WEAKREFERENCE') {
				if (prop.isArray) {
					return (prop.displayValues as string[]).join(', ') || null;
				}
				return (prop.displayValue as string) || null;
			}
			if (prop.isArray) {
				return (prop.currentValues as string[]).join(', ') || null;
			}
			const val = String(prop.currentValue ?? '');
			return val !== '' ? val : null;
		},
		buildPropertyValueInput(prop: any): any {
			const type = prop.type as string;
			if (prop.isArray) {
				const values = prop.currentValues as string[];
				switch (type) {
					case 'LONG': return { longArrayValue: values.map(Number) };
					case 'DOUBLE': return { doubleArrayValue: values.map(Number) };
					case 'DECIMAL': return { decimalArrayValue: values };
					case 'BOOLEAN': return { booleanArrayValue: values.map((v: string) => v === 'true') };
					case 'DATE': return { dateArrayValue: values };
					case 'NAME': return { nameArrayValue: values };
					case 'PATH': return { pathArrayValue: values };
					case 'URI': return { uriArrayValue: values };
					case 'REFERENCE': return { referenceArrayValue: values };
					case 'WEAKREFERENCE': return { weakReferenceArrayValue: values };
					case 'BINARY': {
						const hasServerItems = values.some((v: string) => v.startsWith('__keep:'));
						if (hasServerItems) {
							return { binaryArrayItems: values.filter(Boolean).map((v: string) => {
								if (v.startsWith('__keep:')) {
									return { keepIndex: parseInt(v.substring(7), 10) };
								}
								return { uploadId: v };
							}) };
						}
						return { binaryArrayUploadIds: values.filter(Boolean) };
					}
					default: return { stringArrayValue: values };
				}
			} else {
				const value = prop.currentValue as string;
				switch (type) {
					case 'LONG': return { longValue: Number(value) };
					case 'DOUBLE': return { doubleValue: Number(value) };
					case 'DECIMAL': return { decimalValue: value };
					case 'BOOLEAN': return { booleanValue: value === 'true' };
					case 'DATE': return { dateValue: value };
					case 'NAME': return { nameValue: value };
					case 'PATH': return { pathValue: value };
					case 'URI': return { uriValue: value };
					case 'REFERENCE': return { referenceValue: value };
					case 'WEAKREFERENCE': return { weakReferenceValue: value };
					case 'BINARY': return { binaryUploadId: value };
					default: return { stringValue: value };
				}
			}
		},
		async saveAllProperties() {
			const vm = this;
			const item = vm.selectedItem;
			if (!item) return;

			const changedItems = (vm.propEditorItems as any[]).filter((p: any) =>
				p.isModified || p.isDeleted || p.isNew
			);
			if (changedItems.length === 0) return;

			vm.propEditorSaving = true;
			vm.propEditorSaveError = '';

			try {
				const contentService = vm.instance.api.content;
				const properties = changedItems.map((p: any) => {
					if (p.isDeleted) {
						return { name: p.name, value: null as any };
					}
					return { name: p.name, value: vm.buildPropertyValueInput(p) };
				});

				const result = await contentService.setProperties(item.path, properties);
				if (result?.errors?.length > 0) {
					vm.propEditorSaveError = result.errors.map((e: any) => e.message).join(', ');
					return;
				}

				// Reload from server
				await vm.loadPropEditorData(item);
				// Refresh the detail properties summary in the main panel
				vm.loadDetailProperties(item);
			} catch (error: any) {
				vm.propEditorSaveError = error?.message || 'Failed to save properties';
			} finally {
				vm.propEditorSaving = false;
			}
		},
		async revertAllProperties() {
			const vm = this;
			const item = vm.selectedItem;
			if (!item) return;
			await vm.loadPropEditorData(item);
		},
		async loadSidebarPanelState() {
			const vm = this;
			const db = vm.instance.api.db;
			const userID = vm.instance.currentUser?.id || '*';
			try {
				const state = await db.getUserSetting(userID, 'content-browser', 'sidebarPanel');
				if (state) {
					vm.sidebarPanelVisible = state.visible ?? false;
					vm.sidebarPanelWidth = state.width ?? 260;
					if (state.sectionExpanded) {
						Object.assign(vm.sidebarSectionExpanded, state.sectionExpanded);
					}
				}
			} catch (e) {
				// Silently ignore errors
			}
		},
		async loadDetailPanelState() {
			const vm = this;
			const db = vm.instance.api.db;
			const userID = vm.instance.currentUser?.id || '*';
			try {
				let state = await db.getUserSetting(userID, 'content-browser', 'detailPanel');

				// Migrate from localStorage if IndexedDB has no data
				if (state == null) {
					const legacyJSON = localStorage.getItem('content-browser/detailPanel');
					if (legacyJSON) {
						state = JSON.parse(legacyJSON);
						localStorage.removeItem('content-browser/detailPanel');
						await db.setUserSetting(userID, 'content-browser', 'detailPanel', state);
					}
				}

				if (state) {
					vm.detailPanelVisible = state.visible ?? false;
					vm.detailPanelWidth = state.width ?? 280;
				}
			} catch (e) {
				// Silently ignore errors
			}
		},
		// Global keyboard shortcut handler
		handleKeydown(event: KeyboardEvent) {
			const vm = this;

			// Close full history panel on Escape
			if (event.key === 'Escape' && vm.fullHistoryPanelOpen) {
				event.preventDefault();
				vm.closeFullHistoryPanel();
				return;
			}

			// Close detail panel overlays on Escape
			if (event.key === 'Escape' && vm.detailPropertyEditorVisible) {
				event.preventDefault();
				vm.closeDetailPropertyEditor();
				return;
			}
			if (event.key === 'Escape' && vm.detailVersionHistoryVisible) {
				event.preventDefault();
				vm.closeDetailVersionHistory();
				return;
			}
			if (event.key === 'Escape' && vm.detailACLEditorVisible) {
				event.preventDefault();
				if (vm.aclDialog.addEntry.visible) {
					vm.closeAddAclEntryDialog();
				} else {
					vm.closeDetailACLEditor();
				}
				return;
			}

			// Ctrl+I: toggle detail panel (works regardless of dialog state)
			if ((event.ctrlKey || event.metaKey) && event.key === 'i') {
				event.preventDefault();
				vm.toggleDetailPanel();
				return;
			}

			// Ignore keydown events when a dialog or nav edit mode is open
			if (vm.navEditMode || vm.fullHistoryPanelOpen || vm.renameDialog.visible || vm.deleteDialog.visible ||
				vm.newFolderDialog.visible || vm.newFileDialog.visible || vm.conflictDialog.visible ||
				vm.detailVersionHistoryVisible || vm.detailACLEditorVisible || vm.detailPropertyEditorVisible) {
				return;
			}

			// Ignore keydown events when focus is on an input element
			const target = event.target as HTMLElement;
			if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT') {
				return;
			}

			// Alt+Left: go back, Alt+Right: go forward
			if (event.altKey && event.key === 'ArrowLeft') {
				event.preventDefault();
				vm.goBack();
				return;
			}
			if (event.altKey && event.key === 'ArrowRight') {
				event.preventDefault();
				vm.goForward();
				return;
			}

			// Ctrl+C: copy selected items
			if ((event.ctrlKey || event.metaKey) && event.key === 'c') {
				if (vm.selectedItems.length > 0) {
					event.preventDefault();
					vm.clipboardCopy();
				}
				return;
			}
			// Ctrl+X: cut selected items
			if ((event.ctrlKey || event.metaKey) && event.key === 'x') {
				if (vm.selectedItems.length > 0) {
					event.preventDefault();
					vm.clipboardCut();
				}
				return;
			}
			// Ctrl+V: paste clipboard items
			if ((event.ctrlKey || event.metaKey) && event.key === 'v') {
				if (vm.clipboardHasItems()) {
					event.preventDefault();
					vm.clipboardPaste();
				}
				return;
			}

			// Delete key: delete selected items
			if (event.key === 'Delete') {
				if (vm.selectedItems.length > 0) {
					event.preventDefault();
					const selectedItemObjects = vm.items.filter((i: any) => vm.selectedItems.includes(i.id));
					vm.showDeleteDialog(selectedItemObjects);
				}
				return;
			}

			// F2 key: rename selected item (single selection only)
			if (event.key === 'F2') {
				if (vm.selectedItems.length === 1) {
					event.preventDefault();
					const selectedItem = vm.items.find((i: any) => i.id === vm.selectedItems[0]);
					if (selectedItem) {
						vm.showRenameDialog(selectedItem);
					}
				}
				return;
			}
		},
	},
};

// Mount the app
import { VDOM } from '@mintjamsinc/ichigojs';
VDOM.createApp(App).mount('#app');
