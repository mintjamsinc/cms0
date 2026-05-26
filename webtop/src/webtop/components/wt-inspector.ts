// wt-inspector custom element
//
// Reusable right-pane Inspector for browsing and editing a content item's
// metadata, properties, access control entries and version history. The
// content-browser embeds it for the currently-selected item; other editors
// (text-editor, etc.) can embed it for whichever file is active.
//
// Contract (see memos/wt-inspector-設計書.md for the full design):
//   props:
//     target  — InspectorTarget | InspectorTarget[] | null
//               null = no selection, array = multi selection,
//               single = single selection
//     api     — { content: ContentServiceGraphQL, eventHub: EventHub }
//               handed straight through from vm.instance.api
//     viewOptions — optional display flags. Currently: showOpenItem (default
//               true) toggles the "Open File/Folder" action button; hosts that
//               already have the item open (text-editor) pass false to hide it.
//               NOTE: bound as `:view-options` — the attribute name `options`
//               is reserved by ichigojs for directive options, so a plain
//               `:options` binding is silently ignored.
//     width   — optional pixel width applied to the panel root
//   emits:
//     open-item     { target }
//     navigate-path { path }
//     request-close — (reserved for the header close button)
//
// Internal data flow:
//   - The component watches its own target.path via api.eventHub so the
//     SSE-driven node update stream keeps the panel fresh without the host
//     having to relay anything (see §10.1 of the design document).
//   - Schemas are read from the shell's window.parent.Webtop.metadataDefinitions
//     cache, and refreshed when the shell broadcasts a postMessage of
//     type 'metadata-definitions-updated' (see §10.3).
import { defineComponent } from '@mintjamsinc/ichigojs';
import { MimeTypes } from '../utils/mime-types.js';
import { Encodings } from '../utils/encodings.js';
import {
	displaySize as displaySizeUtil,
	displayType as displayTypeUtil,
	displayEncoding as displayEncodingUtil,
	displayVersion as displayVersionUtil,
	formatDetailDate as formatDetailDateUtil,
	getFileIcon as getFileIconUtil,
	getFileIconClass as getFileIconClassUtil,
} from '../lib/inspector-utils.js';

// CodeMirror imports for structured text editors (JSON/XML/HTML/Markdown)
import { EditorState, Compartment } from '@codemirror/state';
import { EditorView, keymap, lineNumbers, highlightActiveLine, drawSelection } from '@codemirror/view';
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands';
import { syntaxHighlighting, HighlightStyle, bracketMatching, indentOnInput } from '@codemirror/language';
import { tags as t } from '@lezer/highlight';
import { search, findNext, findPrevious, replaceNext, replaceAll, setSearchQuery, SearchQuery } from '@codemirror/search';
import { json, jsonParseLinter } from '@codemirror/lang-json';
import { html } from '@codemirror/lang-html';
import { xml } from '@codemirror/lang-xml';
import { markdown } from '@codemirror/lang-markdown';
import { lintGutter, linter, type Diagnostic } from '@codemirror/lint';
import { marked } from 'marked';

// Module-scope popup handles for autocomplete popups, mirroring the
// content-browser implementation. PopupHandle stores an un-Proxied result
// Promise — wrapping it in ichigojs's reactive Proxy would break `.then`.
let mimeTypePopupHandle: any = null;
let encodingPopupHandle: any = null;
let principalPopupHandle: any = null;

// CodeMirror theme (CSS-variable driven so light/dark theme switches are
// automatic).
const cmTheme = EditorView.theme({
	"&": { backgroundColor: "var(--body-bg)", color: "var(--body-color)" },
	".cm-content": { caretColor: "var(--body-color)" },
	".cm-cursor, .cm-dropCursor": { borderLeftColor: "var(--body-color)" },
	".cm-scroller .cm-layer.cm-selectionLayer .cm-selectionBackground, ::selection": { backgroundColor: "var(--item-selected-bg)" },
	".cm-gutters": { backgroundColor: "var(--list-header-bg)", color: "var(--text-soft-color)", border: "none" },
	".cm-activeLineGutter": { backgroundColor: "var(--btn-icon-hover-bg)" },
	".cm-activeLine": { backgroundColor: "var(--btn-icon-hover-bg)" },
});

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

function htmlLinter(view: EditorView): Diagnostic[] {
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

function getLanguageExtensionForEditorType(editorType: string) {
	switch (editorType) {
		case 'JSON': return [json(), linter(jsonParseLinter())];
		case 'HTML': return [html(), linter(htmlLinter)];
		case 'XML': return [xml(), linter(xmlLinter)];
		case 'Markdown': return [markdown()];
		default: return [];
	}
}

// ────────────────────────────────────────────────────────────────────────
// Schema script context (ctx)
//
// A single context object shared by every schema-defined ECMAScript hook:
// property validation, display formatting and calculated default values.
// Each hook runs as `new Function('ctx', body)` and receives exactly this
// shape, so the three are interchangeable. The same contract is meant to be
// reproduced server-side (NativeEcmaScriptEngine), where the target node is
// handed in as JSON — hence `item` is a frozen, serialisable snapshot rather
// than a live InspectorTarget instance, and nothing here is passed as a
// positional function argument.
// ────────────────────────────────────────────────────────────────────────

// Read-only snapshot of the node (file/folder) the script is acting on.
export interface ScriptItem {
	id: string | null;
	name: string;
	path: string;
	isCollection: boolean;
	mimeType: string;
	contentLength: number;
	encoding: string;
	created: Date | string | null;
	createdBy: string;
	createdByDisplayName: string | null;
	lastModified: Date | string | null;
	lastModifiedBy: string;
	lastModifiedByDisplayName: string | null;
	isLocked: boolean;
	baseVersionName: string | null;
}

// Build the frozen `item` snapshot from an InspectorTarget. Returns null when
// there is no single target (e.g. a multi-selection).
function buildScriptItem(target: any): ScriptItem | null {
	if (!target) return null;
	return Object.freeze({
		id: target.id ?? null,
		name: target.name ?? '',
		path: target.path ?? '',
		isCollection: !!target.isCollection,
		mimeType: target.mimeType ?? '',
		contentLength: target.contentLength ?? 0,
		encoding: target.encoding ?? '',
		created: target.created ?? null,
		createdBy: target.createdBy ?? '',
		createdByDisplayName: target.createdByDisplayName ?? null,
		lastModified: target.lastModified ?? null,
		lastModifiedBy: target.lastModifiedBy ?? '',
		lastModifiedByDisplayName: target.lastModifiedByDisplayName ?? null,
		isLocked: !!target.isLocked,
		baseVersionName: target.baseVersionName ?? null,
	});
}

interface ScriptContextOptions {
	allPropsRaw: Map<string, { value: any; values: any[] }>;
	item: ScriptItem | null;
	propertyName: string;
	value: any;
	values: any[];
	isArray: boolean;
}

// Construct the unified ctx handed to every schema script.
function buildScriptContext(opts: ScriptContextOptions): any {
	const { allPropsRaw, item, propertyName, value, values, isArray } = opts;
	return {
		item,
		propertyName,
		value,
		values,
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
}

// Execute a user-provided validation script returning {valid, errors?}.
function executeValidationScript(
	script: string,
	propertyName: string,
	currentValue: any,
	currentValues: any[],
	isArray: boolean,
	allPropsRaw: Map<string, { value: any; values: any[] }>,
	item: ScriptItem | null,
): { valid: boolean; errors?: any[] } | null {
	try {
		const ctx = buildScriptContext({
			allPropsRaw,
			item,
			propertyName,
			value: isArray ? null : currentValue,
			values: isArray ? currentValues : [currentValue],
			isArray,
		});
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
	item?: ScriptItem | null,
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
			item ?? null,
		);
		if (result && result.valid === false && result.errors && result.errors.length > 0) {
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

defineComponent('wt-inspector', {
	template: '#wt-inspector',
	props: ['target', 'api', 'viewOptions', 'width', 'command'],
	emits: ['open-item', 'navigate-path', 'reveal-item', 'request-close', 'overlay-changed'],
	data(this: any) {
		return {
			// Non-reactive private state (subscriptions, listeners, timers).
			_: this.$markRaw({
				el: null as HTMLElement | null,
				nodeWatchUnsubscribe: null as (() => void) | null,
				messageListener: null as ((e: MessageEvent) => void) | null,
				keydownListener: null as ((e: KeyboardEvent) => void) | null,
				lastCommandNonce: 0,
				targetReloadSeq: 0,
			}),
			// Tick bumped when i18n bundles change so validation computeds re-run.
			_i18nTick: 0,
			// Available metadata schemas (loaded from the shell cache).
			availableSchemas: [] as any[],
			// Property type options for the "Add new property" form.
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
			// Preview image error state
			previewImageError: false,
			// Detail panel: properties section
			detailProperties: [] as { name: string; value: string; items: string[]; type: string; isArray: boolean }[],
			detailPropertiesLoading: false,
			detailPropertiesError: '',
			selectedSchemaKey: '' as string,
			detailPropertiesFilter: '' as string,
			propEditorFilter: '' as string,
			propEditorErrorFilter: '' as '' | 'mismatch' | 'choice' | 'validation',
			// Detail panel: ACL summary
			detailACL: [] as { path: string; entries: { principal: string; privileges: string[]; allow: boolean }[] }[],
			detailACLLoading: false,
			detailACLError: '',
			// Detail panel: overlays
			detailVersionHistoryVisible: false,
			detailACLEditorVisible: false,
			detailPropertyEditorVisible: false,
			// Version history dialog state
			versionHistoryDialog: {
				visible: false,
				item: null as any,
				versions: [] as { name: string; created: string; predecessors: string[]; successors: string[]; createdBy?: string }[],
				baseVersionName: '',
				isLoading: false,
				errorMessage: '',
			},
			// ACL dialog
			aclDialog: {
				visible: false,
				item: null as any,
				effectivePolicies: [] as { path: string; entries: { principal: any; privileges: string[]; allow: boolean }[] }[],
				isLoading: false,
				isSaving: false,
				errorMessage: '',
				pendingEntries: [] as { principal: any; privileges: string[]; allow: boolean }[],
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
			// Property editor overlay state
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
			// CodeMirror structured-text editor state
			cmEditor: null as EditorView | null,
			cmLanguageCompartment: null as Compartment | null,
			cmThemeCompartment: null as Compartment | null,
			cmEditorType: '' as string,
			cmExpanded: false,
			cmPreview: false,
			cmPreviewHtml: '' as string,
			cmEscHandler: null as ((e: KeyboardEvent) => void) | null,
			cmSearchVisible: false,
			cmSearchTerm: '' as string,
			cmReplaceTerm: '' as string,
			cmSearchCaseSensitive: false,
			cmSearchRegex: false,
			cmSearchWholeWord: false,
			cmSearchNotFound: false,
		};
	},
	computed: {
		// Resolved single target. Null when nothing is selected or when the
		// caller passed an array (multi-selection).
		singleTarget(this: any): any {
			const t = this.target;
			if (!t) return null;
			if (Array.isArray(t)) return t.length === 1 ? t[0] : null;
			return t;
		},
		multiTargets(this: any): any[] {
			const t = this.target;
			return Array.isArray(t) && t.length > 1 ? t : [];
		},
		// Frozen `item` snapshot exposed to schema scripts as ctx.item. Null when
		// there is no single target (multi-selection or empty selection).
		scriptItem(this: any): ScriptItem | null {
			return buildScriptItem(this.singleTarget);
		},
		// Whether to show the "Open File/Folder" action button. Hosts that have
		// the item open already (e.g. text-editor) pass viewOptions.showOpenItem
		// = false to hide it. Defaults to shown.
		showOpenAction(this: any): boolean {
			return this.viewOptions?.showOpenItem !== false;
		},
		isSelectedItemImage(this: any): boolean {
			const item = this.singleTarget;
			if (!item || item.isCollection || this.previewImageError) return false;
			const mimeType = item.mimeType || '';
			return mimeType.startsWith('image/');
		},
		selectedItemPreviewURL(this: any): string {
			const item = this.singleTarget;
			if (!item || !item.downloadURL) return '';
			return item.downloadURL.replace(/[?&]attachment$/, '');
		},
		selectedFolderCount(this: any): number {
			return this.multiTargets.filter((i: any) => i.isCollection).length;
		},
		selectedFileCount(this: any): number {
			return this.multiTargets.filter((i: any) => !i.isCollection).length;
		},
		selectedTotalSize(this: any): number {
			return this.multiTargets
				.filter((i: any) => !i.isCollection)
				.reduce((sum: number, i: any) => sum + (i.contentLength || 0), 0);
		},
		displayTotalSize(this: any): string {
			if (this.selectedTotalSize === 0) return '';
			// Format size using the same approach as Bytes.format short mode
			const bytes = this.selectedTotalSize;
			const units = ['B', 'KB', 'MB', 'GB', 'TB'];
			let i = 0;
			let n = bytes;
			while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
			return `${n.toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
		},
		localTZ(this: any): string {
			return new Intl.DateTimeFormat('en', { timeZoneName: 'short' })
				.formatToParts(new Date()).find(p => p.type === 'timeZoneName')?.value || '';
		},
		propEditorModifiedCount(this: any): number {
			return (this.propEditorItems as any[]).filter((p: any) => p.isModified || p.isDeleted || p.isNew).length;
		},
		propEditorDisplayItems(this: any): any[] {
			if (!this.propEditorSchemaKey) return this.propEditorItems;
			const schema = (this.availableSchemas as any[]).find((s: any) => s.key === this.propEditorSchemaKey);
			if (!schema) return this.propEditorItems;

			const propMap = new Map<string, any>();
			for (const p of this.propEditorItems as any[]) {
				propMap.set(p.name, p);
			}

			const allPropsRaw = new Map<string, { value: any; values: any[] }>();
			for (const p of this.propEditorItems as any[]) {
				const val = p.isArray ? (p.currentValues.length > 0 ? p.currentValues[0] : null) : (p.currentValue || null);
				const vals = p.isArray ? [...p.currentValues] : [p.currentValue];
				allPropsRaw.set(p.name, { value: val, values: vals });
			}

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

				if (schemaProp.displayFormat) {
					const compatProp = {
						isArray: item.isArray,
						items: item.currentValues || [],
						value: item.currentValue || '',
					};
					try {
						formattedValue = this.executeDisplayFormat(
							schemaProp.displayFormat, compatProp, allPropsRaw, schemaProp.key
						);
					} catch (e: any) {
						formatError = e.message || String(e);
					}
				}

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
				if (schemaType && item.type && item.type !== schemaType
					&& schemaType !== 'BINARY' && schemaType !== 'REFERENCE' && schemaType !== 'WEAKREFERENCE'
					&& item.type !== 'BINARY' && item.type !== 'REFERENCE' && item.type !== 'WEAKREFERENCE') {
					enrichedWithMeta.typeMismatch = true;
					enrichedWithMeta.mismatchMessage = `Type mismatch: stored as ${item.type}, schema expects ${schemaType}. Editing and saving will convert it to ${schemaType}.`;
				}
				if (schemaChoices && schemaChoices.length > 0
					&& item.type !== 'REFERENCE' && item.type !== 'WEAKREFERENCE'
					&& schemaType !== 'REFERENCE' && schemaType !== 'WEAKREFERENCE') {
					const allowedValues = new Set(schemaChoices.map((c: any) => String(c.value)));
					const valuesToCheck = item.isArray ? (item.currentValues || []) : [item.currentValue];
					const offending: string[] = [];
					for (const cv of valuesToCheck) {
						if (cv == null || String(cv).trim() === '') continue;
						if (!allowedValues.has(String(cv))) offending.push(String(cv));
					}
					if (offending.length > 0) {
						enrichedWithMeta.choiceMismatch = true;
						enrichedWithMeta.choiceMismatchMessage = offending.length === 1
							? `Value "${offending[0]}" is not one of the defined choices.`
							: `${offending.length} values are not among the defined choices: ${offending.map((v) => `"${v}"`).join(', ')}.`;
					}
				}
				void (this as any)._i18nTick;
				const currentValues = item.isArray ? (item.currentValues || []) : [item.currentValue ?? ''];
				const i18nFormat = (err: any): string => {
					const i18n = (window.parent as any)?.Webtop?.i18n;
					if (i18n && typeof i18n.formatValidationError === 'function') {
						return i18n.formatValidationError(err);
					}
					return err.fallbackMessage || err.messageId || 'Validation failed';
				};
				const validationError = validatePropertyValues(enrichedWithMeta, currentValues, allPropsRaw, i18nFormat, this.scriptItem);
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
		aclInheritedPolicies(this: any): any[] {
			const item = this.aclDialog.item;
			const policies = this.aclDialog.effectivePolicies;
			if (!item || !policies || policies.length === 0) return [];
			if (policies[0].path === item.path) {
				return policies.slice(1);
			}
			return policies;
		},
		aclHasChanges(this: any): boolean {
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
		schemaDisplayProperties(this: any): { schemaProps: any[]; extraProps: any[] } {
			if (!this.selectedSchemaKey || this.detailProperties.length === 0) {
				return { schemaProps: [], extraProps: [] };
			}
			const schema = (this.availableSchemas as any[]).find((s: any) => s.key === this.selectedSchemaKey);
			if (!schema) {
				return { schemaProps: [], extraProps: [] };
			}

			const propMap = new Map<string, any>();
			for (const p of this.detailProperties) {
				propMap.set(p.name, p);
			}

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
							schemaProp.displayFormat, detailProp, allPropsRaw, schemaProp.key
						);
					} catch (e: any) {
						formatError = e.message || String(e);
					}
				}

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
		filteredDetailProperties(this: any): any[] {
			const q = (this.detailPropertiesFilter || '').trim().toLowerCase();
			if (!q) return this.detailProperties;
			return (this.detailProperties as any[]).filter((p: any) =>
				p.name.toLowerCase().includes(q)
			);
		},
		filteredSchemaDisplayProperties(this: any): { schemaProps: any[]; extraProps: any[] } {
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
		filteredPropEditorDisplayItems(this: any): any[] {
			const q = (this.propEditorFilter || '').trim().toLowerCase();
			const errorMode = this.propEditorErrorFilter;
			let items = this.propEditorDisplayItems as any[];
			if (errorMode === 'mismatch') {
				items = items.filter((p: any) => p.typeMismatch);
			} else if (errorMode === 'choice') {
				items = items.filter((p: any) => p.choiceMismatch);
			} else if (errorMode === 'validation') {
				items = items.filter((p: any) => p.validationError);
			}
			if (!q) return items;
			return items.filter((p: any) =>
				p.name.toLowerCase().includes(q) ||
				(p.schemaLabel && String(p.schemaLabel).toLowerCase().includes(q))
			);
		},
		propEditorEditError(this: any): string {
			void (this as any)._i18nTick;
			if (!this.propEditorEditingName) return '';
			const prop = (this.propEditorDisplayItems as any[]).find((p: any) => p.name === this.propEditorEditingName);
			if (!prop) return '';
			const isArray = this.propEditorEditIsArray as boolean;
			const values: string[] = isArray
				? [...(this.propEditorEditValues as string[])]
				: [this.propEditorEditInput as string];
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
			return validatePropertyValues({ ...prop, isArray }, values, allPropsRaw, i18nFormat, this.scriptItem);
		},
		propEditorEditChoiceMismatch(this: any): string {
			if (!this.propEditorEditingName) return '';
			const prop = (this.propEditorDisplayItems as any[]).find((p: any) => p.name === this.propEditorEditingName);
			if (!prop || !prop.schemaChoices || prop.schemaChoices.length === 0) return '';
			if (prop.type === 'REFERENCE' || prop.type === 'WEAKREFERENCE') return '';
			const allowed = new Set((prop.schemaChoices as any[]).map((c: any) => String(c.value)));
			const isArray = this.propEditorEditIsArray as boolean;
			const values: string[] = isArray
				? [...(this.propEditorEditValues as string[])]
				: [this.propEditorEditInput as string];
			const offending = values.filter((v) => v != null && String(v).trim() !== '' && !allowed.has(String(v)));
			if (offending.length === 0) return '';
			return offending.length === 1
				? `Value "${offending[0]}" is not one of the defined choices.`
				: `${offending.length} values are not among the defined choices: ${offending.map((v) => `"${v}"`).join(', ')}.`;
		},
		propEditorMissingProperties(this: any): { required: any[]; optional: any[] } {
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
		propEditorIssueCounts(this: any): { mismatch: number; choiceMismatch: number; validation: number; modified: number } {
			const items = this.propEditorDisplayItems as any[];
			let mismatch = 0;
			let choiceMismatch = 0;
			let validation = 0;
			let modified = 0;
			for (const p of items) {
				if (p.typeMismatch) mismatch++;
				if (p.choiceMismatch) choiceMismatch++;
				if (p.validationError) validation++;
				if (p.isModified || p.isNew || p.isDeleted) modified++;
			}
			return { mismatch, choiceMismatch, validation, modified };
		},
	},
	watch: {
		target(this: any, _newVal: any, _oldVal: any) {
			// Reset transient per-target UI state when the target changes.
			this.cancelMimeTypeEdit?.();
			this.cancelEncodingEdit?.();
			this.selectedSchemaKey = '';
			this.previewImageError = false;
			this.resubscribeNodeWatch();
			this.loadDetailData();
		},
		// Host-issued imperative command (open an overlay, etc.). The host
		// bumps a nonce so repeated identical actions still re-trigger.
		command(this: any, newVal: any) {
			this.applyCommand(newVal);
		},
	},
	methods: {
		onMounted(this: any, $ctx: any) {
			const vm = this;
			vm._.el = $ctx?.element || null;

			vm._.messageListener = (e: MessageEvent) => {
				if (e.origin !== window.location.origin) return;
				const type = e.data?.type;
				if (type === 'metadata-definitions-updated') {
					vm.onSchemasUpdated();
				} else if (type === 'i18n-bundles-updated') {
					vm._i18nTick = (vm._i18nTick || 0) + 1;
				}
			};
			window.addEventListener('message', vm._.messageListener);

			// Close any open overlay on Escape. The component owns its overlay
			// state, so it handles its own Escape and stops propagation (capture
			// phase) to keep the host's global Escape handling from also firing.
			vm._.keydownListener = (e: KeyboardEvent) => {
				if (e.key !== 'Escape') return;
				if (vm.detailPropertyEditorVisible) {
					e.preventDefault();
					e.stopPropagation();
					vm.closeDetailPropertyEditor();
				} else if (vm.detailVersionHistoryVisible) {
					e.preventDefault();
					e.stopPropagation();
					vm.closeDetailVersionHistory();
				} else if (vm.detailACLEditorVisible) {
					e.preventDefault();
					e.stopPropagation();
					if (vm.aclDialog.addEntry.visible) {
						vm.closeAddAclEntryDialog();
					} else {
						vm.closeDetailACLEditor();
					}
				}
			};
			document.addEventListener('keydown', vm._.keydownListener, true);

			vm.loadAvailableSchemas();
			vm.resubscribeNodeWatch();
			vm.loadDetailData();
			// Act on a command that was set before the component mounted (e.g.
			// the host opened the panel and asked for the Permissions overlay
			// in the same tick).
			vm.applyCommand(vm.command);
		},
		onUnmount(this: any, _$ctx: any) {
			const vm = this;
			if (vm._.nodeWatchUnsubscribe) {
				try { vm._.nodeWatchUnsubscribe(); } catch { /* ignore */ }
				vm._.nodeWatchUnsubscribe = null;
			}
			if (vm._.messageListener) {
				window.removeEventListener('message', vm._.messageListener);
				vm._.messageListener = null;
			}
			if (vm._.keydownListener) {
				document.removeEventListener('keydown', vm._.keydownListener, true);
				vm._.keydownListener = null;
			}
			vm.destroyCodeMirrorEditor();
			if (vm.cmEscHandler) {
				document.removeEventListener('keydown', vm.cmEscHandler, true);
				vm.cmEscHandler = null;
			}
		},
		resubscribeNodeWatch(this: any) {
			const vm = this;
			if (vm._.nodeWatchUnsubscribe) {
				try { vm._.nodeWatchUnsubscribe(); } catch { /* ignore */ }
				vm._.nodeWatchUnsubscribe = null;
			}
			const target = vm.singleTarget;
			if (!target?.path) return;
			const eventHub = vm.api?.eventHub;
			if (!eventHub || typeof eventHub.watchNode !== 'function') return;
			vm._.nodeWatchUnsubscribe = eventHub.watchNode(target.path, (_event: any) => {
				vm.onTargetNodeChanged();
			}, false);
		},
		onTargetNodeChanged(this: any) {
			const vm = this;
			// When the Property Editor is open and the user has unsaved edits,
			// avoid clobbering propEditorItems. Refresh only read-only summaries.
			const editorActive = vm.detailPropertyEditorVisible &&
				(vm.propEditorEditingName !== null
					|| vm.propEditorAddingNew
					|| (vm.propEditorItems as any[]).some((p: any) => p.isModified || p.isDeleted || p.isNew));
			if (editorActive) {
				const item = vm.singleTarget;
				if (item) {
					vm.loadDetailProperties(item);
					vm.loadDetailACL(item);
				}
				return;
			}
			vm.loadDetailData();
		},
		onSchemasUpdated(this: any) {
			this.loadAvailableSchemas();
		},
		loadAvailableSchemas(this: any) {
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
				// Non-critical
			}
		},
		// Emit helpers. Child→parent communication uses ichigo's $emit, which
		// dispatches a bubbling CustomEvent from the component root; the host
		// binds with @event-name and reads the payload from $event.detail.
		_dispatch(this: any, name: string, detail?: any) {
			this.$emit(name, detail);
		},
		emitOpenItem(this: any, target: any) {
			this._dispatch('open-item', target);
		},
		emitNavigatePath(this: any, path: string) {
			this._dispatch('navigate-path', path);
		},
		// Ask the host to open the folder that contains the given item and
		// reveal (select) the item within it. The host computes the parent
		// path and handles selection.
		emitRevealItem(this: any, target: any) {
			this._dispatch('reveal-item', target);
		},
		emitRequestClose(this: any) {
			this._dispatch('request-close');
		},
		// Notify the host whenever the overlay open-state changes so it can
		// suppress its own global keyboard shortcuts while an overlay is up.
		_notifyOverlayState(this: any) {
			const open = !!(this.detailVersionHistoryVisible
				|| this.detailACLEditorVisible
				|| this.detailPropertyEditorVisible);
			this._dispatch('overlay-changed', open);
		},
		// Apply a host-issued command. Guarded by nonce so the same action can
		// be re-issued (e.g. context menu → Permissions twice in a row).
		applyCommand(this: any, cmd: any) {
			if (!cmd || typeof cmd !== 'object') return;
			if (cmd.nonce != null && cmd.nonce === this._.lastCommandNonce) return;
			this._.lastCommandNonce = cmd.nonce ?? 0;
			switch (cmd.action) {
				case 'version-history':
					this.showDetailVersionHistory();
					break;
				case 'permissions':
					this.showDetailACLEditor();
					break;
				case 'properties':
					this.showDetailPropertyEditor();
					break;
				case 'close-overlays':
					if (this.detailVersionHistoryVisible) this.closeDetailVersionHistory();
					if (this.detailACLEditorVisible) this.closeDetailACLEditor();
					if (this.detailPropertyEditorVisible) this.closeDetailPropertyEditor();
					break;
			}
		},
		// Inline clipboard helper — replaces the host's copyToClipboard().
		async copyPathToClipboard(this: any, event?: MouseEvent) {
			const path = this.singleTarget?.path;
			if (!path) return;
			try {
				await navigator.clipboard.writeText(path);
			} catch {
				const textarea = document.createElement('textarea');
				textarea.value = path;
				document.body.appendChild(textarea);
				textarea.select();
				document.execCommand('copy');
				document.body.removeChild(textarea);
			}
			// Visual feedback: swap icon to a checkmark for ~1.5s.
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
		// ────────────────────────────────────────────────────────────────────
		// Display helpers (delegated to inspector-utils)
		// ────────────────────────────────────────────────────────────────────
		displaySize(this: any, item: any) { return displaySizeUtil(item); },
		displayType(this: any, item: any) { return displayTypeUtil(item); },
		displayEncoding(this: any, item: any) { return displayEncodingUtil(item); },
		displayVersion(this: any, item: any) { return displayVersionUtil(item); },
		getFileIcon(this: any, item: any) { return getFileIconUtil(item); },
		getFileIconClass(this: any, item: any) { return getFileIconClassUtil(item); },
		formatDetailDate(this: any, date: Date | null) { return formatDetailDateUtil(date); },
		formatVersionDate(this: any, dateString: string) {
			if (!dateString) return '';
			return formatDetailDateUtil(new Date(dateString));
		},
		onPreviewImageError(this: any) {
			this.previewImageError = true;
		},
		// ────────────────────────────────────────────────────────────────────
		// Main detail loader
		// ────────────────────────────────────────────────────────────────────
		loadDetailData(this: any) {
			const vm = this;
			vm.cancelMimeTypeEdit?.();
			const item = vm.singleTarget;
			if (!item) {
				vm.detailProperties = [];
				vm.detailPropertiesLoading = false;
				vm.detailACL = [];
				vm.detailACLLoading = false;
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
			vm.loadDetailProperties(item);
			vm.loadDetailACL(item);
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
		async loadDetailProperties(this: any, item: any) {
			const vm = this;
			const seq = ++vm._.targetReloadSeq;
			vm.detailPropertiesLoading = true;
			vm.detailPropertiesError = '';
			vm.detailProperties = [];

			try {
				const contentService = vm.api.content;
				const node = await contentService.getNode(item.path);
				if (seq !== vm._.targetReloadSeq) return;
				if (!node || !node.properties) {
					vm.detailProperties = [];
					return;
				}

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

					if (type === 'BINARY') {
						if (pv.__typename === 'BinaryPropertyValueArray') {
							const mimeTypeList: string[] = pv.mimeTypes || [];
							const sizeList: number[] = pv.sizes || [];
							const sizesFormatted: string[] = sizeList.map((s: number) => vm._formatBytes(s));
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
						const sizeFormatted = vm._formatBytes(size);
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
		async loadDetailACL(this: any, item: any) {
			const vm = this;
			vm.detailACLLoading = true;
			vm.detailACLError = '';
			vm.detailACL = [];

			try {
				const contentService = vm.api.content;
				vm.detailACL = await contentService.getEffectiveAccessControl(item.path);
			} catch (error: any) {
				vm.detailACLError = error?.message || 'Failed to load access control';
			} finally {
				vm.detailACLLoading = false;
			}
		},
		// Simple byte formatter (used inside loaders; same shape as Bytes.format short)
		_formatBytes(this: any, bytes: number): string {
			if (bytes == null) return '';
			const units = ['B', 'KB', 'MB', 'GB', 'TB'];
			let i = 0;
			let n = bytes;
			while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
			return `${n.toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
		},
		executeDisplayFormat(this: any, script: string, prop: any, allPropsRaw: Map<string, { value: any; values: any[] }>, propertyName: string): string {
			const isArray = !!prop.isArray;
			const value = isArray
				? (prop.items.length > 0 ? prop.items[0] : null)
				: (prop.value || null);
			const values = isArray ? [...prop.items] : [prop.value];

			const ctx = buildScriptContext({
				allPropsRaw,
				item: this.scriptItem,
				propertyName,
				value,
				values,
				isArray,
			});
			const fn = new Function('ctx', script);
			const result = fn(ctx);
			return result == null ? '' : String(result);
		},
		// ────────────────────────────────────────────────────────────────────
		// MIME type inline editor
		// ────────────────────────────────────────────────────────────────────
		startMimeTypeEdit(this: any) {
			const vm = this;
			const item = vm.singleTarget;
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
		cancelMimeTypeEdit(this: any) {
			this.mimeTypeEditing = false;
			this.mimeTypeInput = '';
			this.mimeTypeSuggestions = [];
			this.mimeTypeHighlightIndex = -1;
			this.closeMimeTypeSuggestionsPopup();
		},
		updateMimeTypeSuggestions(this: any) {
			const vm = this;
			const query = vm.mimeTypeInput.trim();
			if (!query) {
				vm.mimeTypeSuggestions = [];
				vm.mimeTypeHighlightIndex = -1;
				vm.closeMimeTypeSuggestionsPopup();
				return;
			}
			const filtered = MimeTypes.filterMimeTypes(query).slice(0, 20);
			vm.mimeTypeSuggestions = filtered.map((mt: string) => ({
				mimeType: mt,
				description: MimeTypes.description(mt) || mt,
			}));
			vm.mimeTypeHighlightIndex = -1;
			vm.refreshMimeTypeSuggestionsPopup();
		},
		buildMimeTypeSuggestionItems(this: any) {
			const vm = this;
			return vm.mimeTypeSuggestions.map((s: any, i: number) => ({
				id: s.mimeType,
				label: s.mimeType,
				description: s.description,
				highlighted: i === vm.mimeTypeHighlightIndex,
			}));
		},
		refreshMimeTypeSuggestionsPopup(this: any) {
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
			const popup = vm.api?.popup;
			if (!input || !popup) return;
			const rect = input.getBoundingClientRect();
			mimeTypePopupHandle = popup.open({
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
		closeMimeTypeSuggestionsPopup(this: any) {
			if (mimeTypePopupHandle) {
				mimeTypePopupHandle.close();
				mimeTypePopupHandle = null;
			}
		},
		handleMimeTypeKeydown(this: any, e: KeyboardEvent) {
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
		async confirmMimeTypeEdit(this: any) {
			const item = this.singleTarget;
			const newMimeType = this.mimeTypeInput.trim();
			if (!item || !newMimeType || newMimeType === item.mimeType) {
				this.cancelMimeTypeEdit();
				return;
			}
			const vm = this;
			vm.mimeTypeSaving = true;
			try {
				const contentService = vm.api.content;
				await contentService.updateMimeType(item.path, newMimeType);
				item.mimeType = newMimeType;
				vm.cancelMimeTypeEdit();
			} catch (err: any) {
				console.error('Failed to update MIME type:', err);
				vm.mimeTypeSaving = false;
			}
		},
		// ────────────────────────────────────────────────────────────────────
		// Encoding inline editor
		// ────────────────────────────────────────────────────────────────────
		startEncodingEdit(this: any) {
			const vm = this;
			const item = vm.singleTarget;
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
		cancelEncodingEdit(this: any) {
			this.encodingEditing = false;
			this.encodingInput = '';
			this.encodingSuggestions = [];
			this.encodingHighlightIndex = -1;
			this.closeEncodingSuggestionsPopup();
		},
		updateEncodingSuggestions(this: any) {
			const vm = this;
			const query = vm.encodingInput.trim();
			const filtered = Encodings.filterEncodings(query).slice(0, 20);
			vm.encodingSuggestions = filtered.map((name: string) => ({
				name,
				description: Encodings.description(name) || name,
			}));
			vm.encodingHighlightIndex = -1;
			vm.refreshEncodingSuggestionsPopup();
		},
		buildEncodingSuggestionItems(this: any) {
			const vm = this;
			return vm.encodingSuggestions.map((s: any, i: number) => ({
				id: s.name,
				label: s.name,
				description: s.description,
				highlighted: i === vm.encodingHighlightIndex,
			}));
		},
		refreshEncodingSuggestionsPopup(this: any) {
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
			const popup = vm.api?.popup;
			if (!input || !popup) return;
			const rect = input.getBoundingClientRect();
			encodingPopupHandle = popup.open({
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
		closeEncodingSuggestionsPopup(this: any) {
			if (encodingPopupHandle) {
				encodingPopupHandle.close();
				encodingPopupHandle = null;
			}
		},
		handleEncodingKeydown(this: any, e: KeyboardEvent) {
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
		async confirmEncodingEdit(this: any) {
			const item = this.singleTarget;
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
				const contentService = vm.api.content;
				await contentService.updateEncoding(item.path, newEncoding);
				item.encoding = newEncoding;
				vm.cancelEncodingEdit();
			} catch (err: any) {
				console.error('Failed to update encoding:', err);
				vm.encodingSaving = false;
			}
		},
		// ────────────────────────────────────────────────────────────────────
		// Schema picker dropdowns
		// ────────────────────────────────────────────────────────────────────
		async openSchemaPicker(this: any, event: MouseEvent, currentKey: string): Promise<string | undefined> {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			const popup = vm.api?.popup;
			if (!trigger || !popup) return undefined;
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
			const handle = popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return undefined;
			return result === NONE ? '' : String(result);
		},
		async openDetailSchemaDropdown(this: any, event: MouseEvent) {
			const value = await this.openSchemaPicker(event, this.selectedSchemaKey);
			if (value === undefined) return;
			this.selectedSchemaKey = value;
			if (value) this.loadQueriesForSchema(value);
		},
		detailSchemaLabel(this: any): string {
			const s = (this.availableSchemas as any[]).find((x: any) => x.key === this.selectedSchemaKey);
			return s ? s.label : '';
		},
		async openPropEditorSchemaDropdown(this: any, event: MouseEvent) {
			const value = await this.openSchemaPicker(event, this.propEditorSchemaKey);
			if (value === undefined) return;
			this.propEditorSchemaKey = value;
			if (value) this.loadQueriesForSchema(value);
		},
		propEditorSchemaLabel(this: any): string {
			const s = (this.availableSchemas as any[]).find((x: any) => x.key === this.propEditorSchemaKey);
			return s ? s.label : '';
		},
		async openPropEditChoicesMultiDropdown(this: any, prop: any, event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			const popup = vm.api?.popup;
			if (!trigger || !popup) return;
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
			while (true) {
				const handle = popup.open({
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
		propEditChoicesMultiLabel(this: any, prop: any): string {
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
		async openPropEditChoicesSingleDropdown(this: any, prop: any, event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			const popup = vm.api?.popup;
			if (!trigger || !popup) return;
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
			const handle = popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			vm.propEditorEditInput = result === EMPTY ? '' : String(result);
		},
		propEditChoicesSingleLabel(this: any, prop: any): string {
			const val = this.propEditorEditInput as string;
			if (!val) return '— Select —';
			const c = (prop.schemaChoices as any[]).find((x: any) => x.value === val);
			return c ? (c.label || c.value) : val;
		},
		async openPropEditBooleanDropdown(this: any, event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			const popup = vm.api?.popup;
			if (!trigger || !popup) return;
			const rect = trigger.getBoundingClientRect();
			const items = [
				{ id: 'true', label: 'true', selected: vm.propEditorEditInput === 'true' },
				{ id: 'false', label: 'false', selected: vm.propEditorEditInput === 'false' },
			];
			const handle = popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			vm.propEditorEditInput = String(result);
		},
		async openPropEditChipBooleanDropdown(this: any, idx: number, event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			const popup = vm.api?.popup;
			if (!trigger || !popup) return;
			const rect = trigger.getBoundingClientRect();
			const current = (vm.propEditorEditValues as string[])[idx];
			const items = [
				{ id: 'true', label: 'true', selected: current === 'true' },
				{ id: 'false', label: 'false', selected: current === 'false' },
			];
			const handle = popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			vm.updateEditChip(idx, String(result));
		},
		async openPropEditNewTypeDropdown(this: any, event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			const popup = vm.api?.popup;
			if (!trigger || !popup) return;
			const rect = trigger.getBoundingClientRect();
			const items = (vm.propTypeOptions as any[]).map((o: any) => ({
				id: o.id,
				label: o.label,
				selected: o.id === vm.propEditorNewType,
			}));
			const handle = popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			vm.propEditorNewType = String(result);
		},
		propEditNewTypeLabel(this: any): string {
			const o = (this.propTypeOptions as any[]).find((x: any) => x.id === this.propEditorNewType);
			return o ? o.label : String(this.propEditorNewType);
		},
		// ────────────────────────────────────────────────────────────────────
		// Version History overlay
		// ────────────────────────────────────────────────────────────────────
		async showDetailVersionHistory(this: any) {
			const vm = this;
			const item = vm.singleTarget;
			if (!item) return;
			if (vm.detailACLEditorVisible) {
				vm.detailACLEditorVisible = false;
			}
			const panel = document.querySelector('.detail-panel') as HTMLElement;
			if (panel) panel.scrollTop = 0;
			vm.detailVersionHistoryVisible = true;
			vm.versionHistoryDialog.item = item;
			vm._notifyOverlayState();
			await vm.refreshDetailVersionHistory();
		},
		async refreshDetailVersionHistory(this: any) {
			const vm = this;
			const item = vm.versionHistoryDialog.item;
			if (!item) return;
			vm.versionHistoryDialog.versions = [];
			vm.versionHistoryDialog.baseVersionName = '';
			vm.versionHistoryDialog.errorMessage = '';
			vm.versionHistoryDialog.isLoading = true;

			try {
				const contentService = vm.api.content;
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
		closeDetailVersionHistory(this: any) {
			const vm = this;
			vm.detailVersionHistoryVisible = false;
			vm.versionHistoryDialog.item = null;
			vm.versionHistoryDialog.versions = [];
			vm.versionHistoryDialog.baseVersionName = '';
			vm.versionHistoryDialog.errorMessage = '';
			vm.versionHistoryDialog.isLoading = false;
			vm._notifyOverlayState();
		},
		isCurrentVersion(this: any, versionName: string) {
			return versionName === this.versionHistoryDialog.baseVersionName;
		},
		async restoreVersion(this: any, versionName: string) {
			const vm = this;
			const item = vm.versionHistoryDialog.item;
			if (!item || !versionName) return;
			vm.versionHistoryDialog.isLoading = true;
			vm.versionHistoryDialog.errorMessage = '';
			try {
				const contentService = vm.api.content;
				await contentService.restoreVersion(item.path, versionName);
				// Refresh both the version listing and detail data
				await vm.refreshDetailVersionHistory();
				vm.loadDetailData();
			} catch (error: any) {
				vm.versionHistoryDialog.errorMessage = error?.message || 'Failed to restore version';
				vm.versionHistoryDialog.isLoading = false;
			}
		},
		// ────────────────────────────────────────────────────────────────────
		// ACL Editor overlay
		// ────────────────────────────────────────────────────────────────────
		async showDetailACLEditor(this: any) {
			const vm = this;
			const item = vm.singleTarget;
			if (!item) return;
			if (vm.detailVersionHistoryVisible) {
				vm.detailVersionHistoryVisible = false;
			}
			const panel = document.querySelector('.detail-panel') as HTMLElement;
			if (panel) panel.scrollTop = 0;
			vm.detailACLEditorVisible = true;
			vm.aclDialog.item = item;
			vm._notifyOverlayState();
			await vm.refreshDetailACLEditor();
		},
		async refreshDetailACLEditor(this: any) {
			const vm = this;
			const item = vm.aclDialog.item;
			if (!item) return;
			vm.aclDialog.isLoading = true;
			vm.aclDialog.errorMessage = '';
			vm.aclDialog.effectivePolicies = [];
			vm.aclDialog.pendingEntries = [];
			vm.aclDialog.originalEntries = [];

			try {
				const contentService = vm.api.content;
				vm.aclDialog.effectivePolicies = await contentService.getEffectiveAccessControl(item.path);
				vm._syncAclPendingEntries();
			} catch (error: any) {
				vm.aclDialog.errorMessage = error?.message || 'Failed to load access control';
			} finally {
				vm.aclDialog.isLoading = false;
			}
		},
		closeDetailACLEditor(this: any) {
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
			const selectedItem = vm.singleTarget;
			if (selectedItem) {
				vm.loadDetailACL(selectedItem);
			}
			vm._notifyOverlayState();
		},
		_syncAclPendingEntries(this: any) {
			const vm = this;
			const item = vm.aclDialog.item;
			const policies = vm.aclDialog.effectivePolicies;
			let ownEntries: any[] = [];
			if (item && policies.length > 0 && policies[0].path === item.path) {
				ownEntries = policies[0].entries || [];
			}
			const clone = (entries: any[]) => entries.map(e => ({
				principal: e.principal,
				privileges: [...e.privileges],
				allow: e.allow,
			}));
			vm.aclDialog.pendingEntries = clone(ownEntries);
			vm.aclDialog.originalEntries = clone(ownEntries);
		},
		showAddAclEntryDialog(this: any) {
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
		closeAddAclEntryDialog(this: any) {
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
		onAclSearchInput(this: any) {
			const vm = this;
			if (vm.aclSearchDebounceTimer) {
				clearTimeout(vm.aclSearchDebounceTimer);
			}
			vm.aclSearchDebounceTimer = setTimeout(() => {
				vm.searchPrincipals();
			}, 300);
		},
		onAclSearchFocus(this: any) {
			const vm = this;
			if (vm.aclDialog.addEntry.searchResults.length > 0) {
				vm.refreshPrincipalSuggestionsPopup();
			}
		},
		async searchPrincipals(this: any) {
			const vm = this;
			const keyword = vm.aclDialog.addEntry.searchKeyword.trim();
			if (!keyword) {
				vm.aclDialog.addEntry.searchResults = [];
				vm.closePrincipalSuggestionsPopup();
				return;
			}

			vm.aclDialog.addEntry.isSearching = true;
			try {
				const contentService = vm.api.content;
				vm.aclDialog.addEntry.searchResults = await contentService.searchPrincipals(keyword, 0, 20);
				vm.refreshPrincipalSuggestionsPopup();
			} catch (error: any) {
				vm.aclDialog.addEntry.searchResults = [];
				vm.closePrincipalSuggestionsPopup();
			} finally {
				vm.aclDialog.addEntry.isSearching = false;
			}
		},
		buildPrincipalSuggestionItems(this: any) {
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
		refreshPrincipalSuggestionsPopup(this: any) {
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
			const popup = vm.api?.popup;
			if (!input || !popup) return;
			const rect = input.getBoundingClientRect();
			principalPopupHandle = popup.open({
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
		closePrincipalSuggestionsPopup(this: any) {
			if (principalPopupHandle) {
				principalPopupHandle.close();
				principalPopupHandle = null;
			}
		},
		selectPrincipal(this: any, principal: { identifier: string; isGroup: boolean; isService?: boolean; displayName?: string | null }) {
			const vm = this;
			vm.aclDialog.addEntry.principal = principal.identifier;
			vm.aclDialog.addEntry.principalIsGroup = principal.isGroup;
			vm.aclDialog.addEntry.principalDisplayName = principal.displayName || '';
			vm.aclDialog.addEntry.searchKeyword = principal.identifier;
			vm.aclDialog.addEntry.searchResults = [];
			vm.closePrincipalSuggestionsPopup();
		},
		togglePrivilege(this: any, privilege: string) {
			const vm = this;
			const idx = vm.aclDialog.addEntry.privileges.indexOf(privilege);
			if (idx >= 0) {
				vm.aclDialog.addEntry.privileges.splice(idx, 1);
			} else {
				vm.aclDialog.addEntry.privileges.push(privilege);
			}
		},
		submitAclEntry(this: any) {
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
			vm.aclDialog.pendingEntries.push({
				principal: { id, displayName: principalDisplayName || null, isGroup: vm.aclDialog.addEntry.principalIsGroup },
				privileges: [...privileges],
				allow,
			});
			vm.closeAddAclEntryDialog();
		},
		deleteAclEntry(this: any, principalID: string) {
			const vm = this;
			const idx = vm.aclDialog.pendingEntries.findIndex((e: any) => {
				const id = typeof e.principal === 'object' ? e.principal.id : e.principal;
				return id === principalID;
			});
			if (idx !== -1) {
				vm.aclDialog.pendingEntries.splice(idx, 1);
			}
		},
		async saveAclChanges(this: any) {
			const vm = this;
			vm.aclDialog.isSaving = true;
			vm.aclDialog.errorMessage = '';

			try {
				const contentService = vm.api.content;
				const entries = vm.aclDialog.pendingEntries.map((e: any) => ({
					principal: typeof e.principal === 'object' ? e.principal.id : e.principal,
					privileges: e.privileges,
					allow: e.allow,
				}));
				await contentService.setAccessControl(vm.aclDialog.item.path, { entries });
				await vm.refreshDetailACLEditor();
			} catch (error: any) {
				vm.aclDialog.errorMessage = error?.message || 'Failed to save access control';
			} finally {
				vm.aclDialog.isSaving = false;
			}
		},
		discardAclChanges(this: any) {
			const vm = this;
			vm.aclDialog.pendingEntries = vm.aclDialog.originalEntries.map((e: any) => ({
				principal: e.principal,
				privileges: [...e.privileges],
				allow: e.allow,
			}));
			vm.aclDialog.errorMessage = '';
		},
		// ────────────────────────────────────────────────────────────────────
		// Property Editor overlay
		// ────────────────────────────────────────────────────────────────────
		async showDetailPropertyEditor(this: any) {
			const vm = this;
			const item = vm.singleTarget;
			if (!item) return;
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
			vm._notifyOverlayState();
			await vm.loadPropEditorData(item);
			if (vm.propEditorSchemaKey) {
				vm.loadQueriesForSchema(vm.propEditorSchemaKey);
			}
		},
		closeDetailPropertyEditor(this: any) {
			const vm = this;
			vm.detailPropertyEditorVisible = false;
			vm.propEditorItems = [];
			vm.propEditorLoading = false;
			vm.propEditorError = '';
			vm.propEditorEditingName = null;
			vm.propEditorAddingNew = false;
			vm.propEditorSaving = false;
			vm.propEditorSaveError = '';
			vm._notifyOverlayState();
		},
		async loadPropEditorData(this: any, item: any) {
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
				const contentService = vm.api.content;
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
					if (type === 'BINARY') {
						if (pv.__typename === 'BinaryPropertyValueArray') {
							const mimeTypeList: string[] = pv.mimeTypes || [];
							const sizeList: number[] = pv.sizes || [];
							const propertyDownloadURLs: string[] = [];
							const sizesFormatted: string[] = sizeList.map((s: number) => vm._formatBytes(s));
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
						const sizeFormatted = vm._formatBytes(size);
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
		startEditingProperty(this: any, prop: any) {
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
			if (prop.type === 'REFERENCE' || prop.type === 'WEAKREFERENCE') {
				if (prop.isArray) {
					vm.propEditorEditDisplayValues = [...(prop.displayValues || [])];
					vm.propEditorEditDisplayValue = '';
				} else {
					vm.propEditorEditDisplayValue = prop.displayValue || '';
					vm.propEditorEditDisplayValues = [];
				}
			} else if (prop.schemaChoices && prop.schemaChoices.length > 0) {
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
			if ((prop.type === 'REFERENCE' || prop.type === 'WEAKREFERENCE') && prop.queryConfig) {
				vm.executeQueryForRef(prop.queryConfig);
			} else {
				vm.propEditorQueryItems = [];
				vm.propEditorQueryConfig = null;
			}
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
		confirmPropertyEdit(this: any) {
			const vm = this;
			if (vm.propEditorEditError) return;
			const name = vm.propEditorEditingName;
			if (!name) return;
			const idx = (vm.propEditorItems as any[]).findIndex((p: any) => p.name === name);
			if (idx === -1) return;
			const prop = vm.propEditorItems[idx];

			const enriched = (vm.propEditorDisplayItems as any[]).find((p: any) => p.name === name);
			const schemaType = enriched?.schemaType as string | undefined;
			let typeChanged = false;
			if (schemaType && schemaType !== prop.type) {
				prop.type = schemaType;
				typeChanged = true;
			}

			const isRef = prop.type === 'REFERENCE' || prop.type === 'WEAKREFERENCE';
			if (vm.propEditorEditIsArray) {
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
					// A kept server item carries its download URL in binaryPreviewURL;
					// surface it as propertyDownloadURL so the single read-mode shows a
					// download link (e.g. after converting BINARY[] back to BINARY).
					if (typeof newValue === 'string' && newValue.startsWith('__keep:')) {
						update.propertyDownloadURL = update.binaryPreviewURL;
					}
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
		cancelPropertyEdit(this: any) {
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
		toggleEditArray(this: any) {
			const vm = this;
			const isRef = (vm.propEditorEditType as string) === 'REFERENCE' || (vm.propEditorEditType as string) === 'WEAKREFERENCE';
			const isBinary = (vm.propEditorEditType as string) === 'BINARY';
			vm.propEditorEditIsArray = !vm.propEditorEditIsArray;
			if (isBinary) {
				vm.binaryEditServerItems = [];
				if (vm.propEditorEditIsArray) {
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
					const firstId = (vm.propEditorEditValues as string[])[0] || '';
					const firstName = (vm.binaryEditFileNames as string[])[0] || '';
					const firstSize = (vm.binaryEditFileSizesFormatted as string[])[0] || '';
					const firstPreview = (vm.binaryEditPreviewURLs as string[])[0] || '';
					const firstMime = (vm.binaryEditFileMimeTypes as string[])[0] || '';
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
				if (isRef) {
					const uuid = vm.propEditorEditInput as string;
					const disp = vm.propEditorEditDisplayValue as string;
					vm.propEditorEditValues = uuid ? [uuid] : [];
					vm.propEditorEditDisplayValues = disp ? [disp] : [];
					vm.propEditorEditInput = '';
					vm.propEditorEditDisplayValue = '';
				} else {
					const singleVal = vm.propEditorEditInput as string;
					let storedVal = singleVal;
					if ((vm.propEditorEditType as string) === 'DATE' && singleVal) {
						storedVal = new Date(singleVal).toISOString();
					}
					vm.propEditorEditValues = storedVal ? [storedVal] : [];
					vm.propEditorEditInput = '';
				}
			} else {
				if (isRef) {
					const firstUUID = (vm.propEditorEditValues as string[]).length > 0 ? (vm.propEditorEditValues as string[])[0] : '';
					const firstDisp = (vm.propEditorEditDisplayValues as string[]).length > 0 ? (vm.propEditorEditDisplayValues as string[])[0] : '';
					vm.propEditorEditInput = firstUUID;
					vm.propEditorEditDisplayValue = firstDisp;
					vm.propEditorEditValues = [];
					vm.propEditorEditDisplayValues = [];
				} else {
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
		addEditChip(this: any) {
			const type = this.propEditorEditType as string;
			const defaultVal = type === 'BOOLEAN' ? 'true' : '';
			(this.propEditorEditValues as string[]).push(defaultVal);
		},
		updateEditChip(this: any, idx: number, val: string) {
			(this.propEditorEditValues as string[]).splice(idx, 1, val);
		},
		updateEditChipDate(this: any, idx: number, localVal: string) {
			const stored = localVal ? new Date(localVal).toISOString() : '';
			(this.propEditorEditValues as string[]).splice(idx, 1, stored);
		},
		removeEditChip(this: any, idx: number) {
			(this.propEditorEditValues as string[]).splice(idx, 1);
		},
		formatDateLocal(this: any, val: string): string {
			try { return new Date(val).toLocaleString(); }
			catch { return val; }
		},
		moveEditChip(this: any, idx: number, dir: number) {
			const arr = this.propEditorEditValues as string[];
			const target = idx + dir;
			if (target < 0 || target >= arr.length) return;
			const tmp = arr[idx];
			arr.splice(idx, 1);
			arr.splice(target, 0, tmp);
		},
		async loadQueriesForSchema(this: any, schemaKey: string) {
			const vm = this;
			if (!schemaKey) return;
			const schema = (vm.availableSchemas as any[]).find((s: any) => s.key === schemaKey);
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
				vm.propEditorQueryCache = { ...vm.propEditorQueryCache };
			}
		},
		async executeQueryForRef(this: any, queryConfig: { xpath: string; labelKey: string }) {
			const vm = this;
			vm.propEditorQueryConfig = queryConfig;
			const cached = vm.propEditorQueryCache[queryConfig.xpath];
			if (cached) {
				vm.propEditorQueryItems = cached;
				vm.propEditorQueryLoading = false;
				return;
			}
			vm.propEditorQueryLoading = true;
			vm.propEditorQueryItems = [];
			try {
				const contentService = vm.api.content;
				const result = await contentService.xpathWithProperties(queryConfig.xpath, { first: 100 });
				const items: { value: string; label: string }[] = [];
				for (const edge of result.edges) {
					const node = edge.node;
					const value = node.uuid || '';
					if (!value) continue;
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
		async refreshQueryItems(this: any) {
			const vm = this;
			if (!vm.propEditorQueryConfig) return;
			const { [vm.propEditorQueryConfig.xpath]: _, ...rest } = vm.propEditorQueryCache;
			vm.propEditorQueryCache = rest;
			await vm.executeQueryForRef(vm.propEditorQueryConfig);
		},
		// ────────────────────────────────────────────────────────────────────
		// CodeMirror editor
		// ────────────────────────────────────────────────────────────────────
		initCodeMirrorEditor(this: any, containerId: string, editorType: string, initialValue: string) {
			const vm = this;
			const container = document.getElementById(containerId);
			if (!container) return;
			vm.destroyCodeMirrorEditor();

			const languageCompartment = new Compartment();
			const themeCompartment = new Compartment();
			vm.cmLanguageCompartment = languageCompartment;
			vm.cmThemeCompartment = themeCompartment;
			vm.cmEditorType = editorType;

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

			vm.cmEditor = (vm as any).$markRaw
				? (vm as any).$markRaw(new EditorView({ state, parent: container }))
				: new EditorView({ state, parent: container });

			if (vm.cmPreview && (editorType === 'Markdown' || editorType === 'HTML')) {
				vm.updateCmPreview(initialValue);
			}
		},
		destroyCodeMirrorEditor(this: any) {
			const vm = this;
			if (vm.cmEditor) {
				vm.cmEditor.destroy();
				vm.cmEditor = null;
			}
			vm.cmLanguageCompartment = null;
			vm.cmThemeCompartment = null;
		},
		toggleCmExpanded(this: any) {
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
			if (vm.cmExpanded) {
				vm.cmEscHandler = (e: KeyboardEvent) => {
					if (e.key === 'Escape') {
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
		openCmSearch(this: any) {
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
		closeCmSearch(this: any) {
			const vm = this;
			vm.cmSearchVisible = false;
			vm.cmSearchNotFound = false;
			if (vm.cmEditor) vm.cmEditor.focus();
		},
		applyCmSearchQuery(this: any) {
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
		onCmSearchInput(this: any) {
			this.cmSearchNotFound = false;
			this.applyCmSearchQuery();
		},
		cmFindNext(this: any) {
			const vm = this;
			if (!vm.cmEditor || !vm.cmSearchTerm) return;
			vm.applyCmSearchQuery();
			vm.cmSearchNotFound = !findNext(vm.cmEditor);
		},
		cmFindPrev(this: any) {
			const vm = this;
			if (!vm.cmEditor || !vm.cmSearchTerm) return;
			vm.applyCmSearchQuery();
			vm.cmSearchNotFound = !findPrevious(vm.cmEditor);
		},
		cmReplaceNext(this: any) {
			const vm = this;
			if (!vm.cmEditor || !vm.cmSearchTerm) return;
			vm.applyCmSearchQuery();
			vm.cmSearchNotFound = !replaceNext(vm.cmEditor);
		},
		cmReplaceAll(this: any) {
			const vm = this;
			if (!vm.cmEditor || !vm.cmSearchTerm) return;
			vm.applyCmSearchQuery();
			vm.cmSearchNotFound = !replaceAll(vm.cmEditor);
		},
		toggleCmSearchCaseSensitive(this: any) {
			this.cmSearchCaseSensitive = !this.cmSearchCaseSensitive;
			this.applyCmSearchQuery();
		},
		toggleCmSearchRegex(this: any) {
			this.cmSearchRegex = !this.cmSearchRegex;
			this.applyCmSearchQuery();
		},
		toggleCmSearchWholeWord(this: any) {
			this.cmSearchWholeWord = !this.cmSearchWholeWord;
			this.applyCmSearchQuery();
		},
		onCmSearchKeydown(this: any, event: KeyboardEvent) {
			if (event.key === 'Enter') {
				event.preventDefault();
				if (event.shiftKey) this.cmFindPrev(); else this.cmFindNext();
			} else if (event.key === 'Escape') {
				event.preventDefault();
				this.closeCmSearch();
			}
		},
		onCmReplaceKeydown(this: any, event: KeyboardEvent) {
			if (event.key === 'Enter') {
				event.preventDefault();
				if (event.ctrlKey || event.metaKey) this.cmReplaceAll(); else this.cmReplaceNext();
			} else if (event.key === 'Escape') {
				event.preventDefault();
				this.closeCmSearch();
			}
		},
		formatCmContent(this: any) {
			const vm = this;
			if (!vm.cmEditor) return;
			const current = vm.cmEditor.state.doc.toString();
			const formatted = formatStructuredText(current, vm.cmEditorType);
			if (formatted === current) return;
			vm.cmEditor.dispatch({
				changes: { from: 0, to: current.length, insert: formatted },
			});
		},
		toggleCmPreview(this: any) {
			const vm = this;
			vm.cmPreview = !vm.cmPreview;
			if (vm.cmPreview && vm.cmEditor) {
				vm.updateCmPreview(vm.cmEditor.state.doc.toString());
			}
		},
		updateCmPreview(this: any, content: string) {
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
		// ────────────────────────────────────────────────────────────────────
		// REFERENCE/WEAKREFERENCE selection & drop
		// ────────────────────────────────────────────────────────────────────
		selectQueryItem(this: any, item: { value: string; label: string }) {
			const vm = this;
			if (vm.propEditorEditIsArray) {
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
				if (vm.propEditorEditInput === item.value) {
					vm.propEditorEditInput = '';
					vm.propEditorEditDisplayValue = '';
				} else {
					vm.propEditorEditInput = item.value;
					vm.propEditorEditDisplayValue = item.label;
				}
			}
		},
		isQueryItemSelected(this: any, value: string): boolean {
			if (this.propEditorEditIsArray) {
				return (this.propEditorEditValues as string[]).includes(value);
			}
			return this.propEditorEditInput === value;
		},
		openRefBrowser(this: any, currentDisplayPath: string) {
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
		onRefDragOver(this: any, slotKey: string, event: DragEvent) {
			if (!event.dataTransfer?.types.includes('application/x-webtop-file')) return;
			event.preventDefault();
			event.dataTransfer.dropEffect = 'copy';
			this.refDragOverProp = slotKey;
		},
		onRefDragLeave(this: any, slotKey: string, _event: DragEvent) {
			if (this.refDragOverProp === slotKey) {
				this.refDragOverProp = null;
			}
		},
		_parseWebtopFileDrop(this: any, event: DragEvent): { path: string; name: string; mimeType?: string; uuid?: string; isReferenceable?: boolean; isCollection?: boolean; downloadURL?: string } | null {
			const raw = event.dataTransfer?.getData('application/x-webtop-file');
			if (!raw) return null;
			try { return JSON.parse(raw); }
			catch { return null; }
		},
		onRefPropDropSingle(this: any, event: DragEvent) {
			const vm = this;
			event.preventDefault();
			vm.refDragOverProp = null;
			const file = vm._parseWebtopFileDrop(event);
			if (!file || !file.isReferenceable || !file.uuid) return;
			if (file.path === vm.singleTarget?.path) return;
			vm.propEditorEditInput = file.uuid;
			vm.propEditorEditDisplayValue = file.path;
		},
		onRefPropDropArrayItem(this: any, idx: number, event: DragEvent) {
			const vm = this;
			event.preventDefault();
			vm.refDragOverProp = null;
			const file = vm._parseWebtopFileDrop(event);
			if (!file || !file.isReferenceable || !file.uuid) return;
			if (file.path === vm.singleTarget?.path) return;
			const values = vm.propEditorEditValues as string[];
			const dispValues = vm.propEditorEditDisplayValues as string[];
			values.splice(idx, 1, file.uuid);
			dispValues.splice(idx, 1, file.path);
		},
		onRefPropDropArrayAdd(this: any, event: DragEvent) {
			const vm = this;
			event.preventDefault();
			vm.refDragOverProp = null;
			const file = vm._parseWebtopFileDrop(event);
			if (!file || !file.isReferenceable || !file.uuid) return;
			if (file.path === vm.singleTarget?.path) return;
			(vm.propEditorEditValues as string[]).push(file.uuid);
			(vm.propEditorEditDisplayValues as string[]).push(file.path);
		},
		clearRefSingleItem(this: any) {
			this.propEditorEditInput = '';
			this.propEditorEditDisplayValue = '';
		},
		removeRefEditChip(this: any, idx: number) {
			(this.propEditorEditValues as string[]).splice(idx, 1);
			(this.propEditorEditDisplayValues as string[]).splice(idx, 1);
		},
		moveRefEditChip(this: any, idx: number, dir: number) {
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
		// ────────────────────────────────────────────────────────────────────
		// Binary property inline editing
		// ────────────────────────────────────────────────────────────────────
		async _resolveDropToFile(this: any, event: DragEvent): Promise<File | null> {
			const dt = event.dataTransfer;
			if (!dt) return null;
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
			if (dt.files && dt.files.length > 0) return dt.files[0];
			return null;
		},
		async _uploadFileToBinaryProp(this: any, file: File): Promise<{ uploadId: string; fileName: string; sizeFormatted: string; previewURL: string; mimeType: string }> {
			const vm = this;
			const contentService = vm.api.content;
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
				return { uploadId, fileName: file.name, sizeFormatted: vm._formatBytes(file.size), previewURL, mimeType: file.type };
			} catch (err) {
				try { await contentService.abortMultipartUpload(uploadId); } catch {}
				throw err;
			}
		},
		onBinaryDragOver(this: any, event: DragEvent) {
			if (this.binaryEditIsUploading) return;
			const dt = event.dataTransfer;
			if (!dt) return;
			if (!dt.types.includes('application/x-webtop-file') && !dt.types.includes('Files')) return;
			event.preventDefault();
			event.stopPropagation();
			dt.dropEffect = 'copy';
			this.binaryEditDragOver = true;
		},
		onBinaryDragLeave(this: any, _event: DragEvent) {
			this.binaryEditDragOver = false;
		},
		onBinaryArrayDragOver(this: any, key: number, event: DragEvent) {
			if (this.binaryEditIsUploading) return;
			const dt = event.dataTransfer;
			if (!dt) return;
			if (!dt.types.includes('application/x-webtop-file') && !dt.types.includes('Files')) return;
			event.preventDefault();
			event.stopPropagation();
			dt.dropEffect = 'copy';
			this.binaryEditArrayDragOver = key;
		},
		onBinaryArrayDragLeave(this: any, key: number, _event: DragEvent) {
			if (this.binaryEditArrayDragOver === key) this.binaryEditArrayDragOver = null;
		},
		async onBinaryDropSingle(this: any, event: DragEvent) {
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
		async onBinaryDropArrayItem(this: any, idx: number, event: DragEvent) {
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
		async onBinaryDropArrayAdd(this: any, event: DragEvent) {
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
		clearBinarySingleItem(this: any) {
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
		removeBinaryEditItem(this: any, idx: number) {
			const itemId = (this.propEditorEditValues as string[])[idx];
			const url = (this.binaryEditPreviewURLs as string[])[idx];
			if (url && !itemId.startsWith('__keep:')) URL.revokeObjectURL(url);
			(this.propEditorEditValues as string[]).splice(idx, 1);
			(this.binaryEditFileNames as string[]).splice(idx, 1);
			(this.binaryEditFileSizesFormatted as string[]).splice(idx, 1);
			(this.binaryEditPreviewURLs as string[]).splice(idx, 1);
			(this.binaryEditFileMimeTypes as string[]).splice(idx, 1);
		},
		moveBinaryEditItem(this: any, idx: number, dir: number) {
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
		toLocalDatetimeInput(this: any, isoStr: string): string {
			try {
				const d = new Date(isoStr);
				const pad = (n: number) => String(n).padStart(2, '0');
				return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
			} catch {
				return isoStr.substring(0, 16);
			}
		},
		// ────────────────────────────────────────────────────────────────────
		// Property add/delete/save
		// ────────────────────────────────────────────────────────────────────
		markPropertyDeleted(this: any, prop: any) {
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
		unmarkPropertyDeleted(this: any, prop: any) {
			const vm = this;
			const idx = (vm.propEditorItems as any[]).findIndex((p: any) => p.name === prop.name);
			if (idx === -1) return;
			const p = vm.propEditorItems[idx];
			const isModified = p.isArray
				? JSON.stringify(p.currentValues) !== JSON.stringify(p.originalValues)
				: p.currentValue !== p.originalValue;
			(vm.propEditorItems as any[]).splice(idx, 1, { ...p, isDeleted: false, isModified });
		},
		startAddingProperty(this: any) {
			const vm = this;
			vm.propEditorAddingNew = true;
			vm.propEditorNewName = '';
			vm.propEditorNewType = 'STRING';
			vm.propEditorNewIsArray = false;
			vm.propEditorNewValue = '';
			vm.propEditorNewValues = [];
			vm.propEditorNewChip = '';
		},
		cancelAddProperty(this: any) {
			this.propEditorAddingNew = false;
		},
		buildNewPropItemFromSchema(this: any, schemaProp: any): any {
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
		evaluateCalculatedDefault(this: any, formula: string, allPropsRaw: Map<string, { value: any; values: any[] }>, propertyName: string): string | null {
			try {
				const ctx = buildScriptContext({
					allPropsRaw,
					item: this.scriptItem,
					propertyName,
					value: null,
					values: [],
					isArray: false,
				});
				const fn = new Function('ctx', formula);
				const result = fn(ctx);
				return result == null ? null : String(result);
			} catch {
				return null;
			}
		},
		addAllMissingProperties(this: any) {
			const vm = this;
			const missing = vm.propEditorMissingProperties;
			const all = [...missing.required, ...missing.optional];
			if (all.length === 0) return;

			const newItems: any[] = [];
			for (const sp of all) {
				const item = vm.buildNewPropItemFromSchema(sp);
				(vm.propEditorItems as any[]).push(item);
				newItems.push({ schemaProp: sp, item });
			}

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
			const ordered: any[] = [];
			const remaining = new Map<string, Set<string>>();
			for (const [k, deps] of depMap) remaining.set(k, new Set(deps));
			while (remaining.size > 0) {
				const ready: string[] = [];
				for (const [k, deps] of remaining) {
					if (deps.size === 0) ready.push(k);
				}
				if (ready.length === 0) {
					for (const k of remaining.keys()) ready.push(k);
				}
				for (const k of ready) {
					const ci = calcItems.find((c: any) => c.schemaProp.key === k);
					if (ci) ordered.push(ci);
					remaining.delete(k);
					for (const deps of remaining.values()) deps.delete(k);
				}
			}

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
				const result = vm.evaluateCalculatedDefault(ci.schemaProp.defaultValue.value, lookup, ci.schemaProp.key);
				if (result != null) {
					if (ci.item.isArray) ci.item.currentValues = [result];
					else ci.item.currentValue = result;
				}
			}
		},
		confirmAddProperty(this: any) {
			const vm = this;
			const name = (vm.propEditorNewName as string).trim();
			if (!name) return;
			if ((vm.propEditorItems as any[]).some((p: any) => p.name === name)) return;

			let schemaProp: any = null;
			if (vm.propEditorSchemaKey) {
				const schema = (vm.availableSchemas as any[]).find((s: any) => s.key === vm.propEditorSchemaKey);
				schemaProp = schema?.properties?.find((p: any) => p.key === name);
			}

			let newProp: any;
			if (schemaProp) {
				newProp = vm.buildNewPropItemFromSchema(schemaProp);
				if (schemaProp.defaultValue && schemaProp.defaultValue.type === 'CALCULATED' && schemaProp.defaultValue.value) {
					const lookup = new Map<string, { value: any; values: any[] }>();
					for (const p of vm.propEditorItems as any[]) {
						if (p.isDeleted) continue;
						const val = p.isArray ? (p.currentValues.length > 0 ? p.currentValues[0] : null) : (p.currentValue || null);
						const vals = p.isArray ? [...p.currentValues] : [p.currentValue];
						lookup.set(p.name, { value: val, values: vals });
					}
					const result = vm.evaluateCalculatedDefault(schemaProp.defaultValue.value, lookup, schemaProp.key);
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
			vm.$nextTick(() => {
				const enriched = (vm.propEditorDisplayItems as any[]).find((p: any) => p.name === name);
				vm.startEditingProperty(enriched || newProp);
			});
		},
		displayPropValue(this: any, prop: any): string {
			if (prop.isDeleted) return '(deleted)';
			if (prop.type === 'REFERENCE' || prop.type === 'WEAKREFERENCE') {
				if (prop.isArray) {
					return (prop.displayValues as string[]).join(', ') || (null as any);
				}
				return (prop.displayValue as string) || (null as any);
			}
			if (prop.isArray) {
				return (prop.currentValues as string[]).join(', ') || (null as any);
			}
			const val = String(prop.currentValue ?? '');
			return val !== '' ? val : (null as any);
		},
		buildPropertyValueInput(this: any, prop: any): any {
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
		async saveAllProperties(this: any) {
			const vm = this;
			const item = vm.singleTarget;
			if (!item) return;

			const changedItems = (vm.propEditorItems as any[]).filter((p: any) =>
				p.isModified || p.isDeleted || p.isNew
			);
			if (changedItems.length === 0) return;

			vm.propEditorSaving = true;
			vm.propEditorSaveError = '';

			try {
				const contentService = vm.api.content;
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

				await vm.loadPropEditorData(item);
				vm.loadDetailProperties(item);
			} catch (error: any) {
				vm.propEditorSaveError = error?.message || 'Failed to save properties';
			} finally {
				vm.propEditorSaving = false;
			}
		},
		async revertAllProperties(this: any) {
			const vm = this;
			const item = vm.singleTarget;
			if (!item) return;
			await vm.loadPropEditorData(item);
		},
	},
});
