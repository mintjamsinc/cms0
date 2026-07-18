import { ApplicationInstance } from "../../services/webtop-service.js";
import { type Node, type JobStatus } from "../../graphql/types.js";
import { deleteContentItems, type DeleteJobHandle, type DeleteJobProgress } from "../../services/content-delete.js";
import { downloadContentAsZip, importContentArchive, type ArchiveJobHandle, type ArchiveJobProgress, type ImportArchiveProgress } from "../../services/content-archive.js";
import { IdpServiceGraphQL } from "../../services/idp-service-graphql.js";
import { BUILD_VERSION } from "../../utils/build-version.js";
import { Dates } from "../../utils/dates.js";
import { nodeToInspectorTarget, type InspectorTarget } from "../../lib/inspector-target.js";
import { readArchiveManifest } from "./archive-manifest.js";
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
} from "../../composables/use-localization.js";
// Display helpers used by the file list. The remaining inspector-only
// display methods moved into wt-inspector along with the rest of the panel.
import {
	displaySize as displaySizeUtil,
	displayType as displayTypeUtil,
	displayVersion as displayVersionUtil,
	getFileIcon as getFileIconUtil,
	getFileIconClass as getFileIconClassUtil,
} from "../../lib/inspector-utils.js";

// Side-effect import: registers the <wt-inspector> custom element.
import "../../components/wt-inspector.js";

// Fetch and inject the wt-inspector <template> tag. Mirrors the shell's
// loadComponent() helper but scoped to this app since the inspector template
// is not part of the shell's default load list.
async function loadInspectorTemplate(): Promise<void> {
	const res = await fetch(`../../components/wt-inspector.html?v=${BUILD_VERSION}`);
	const html = await res.text();
	const doc = new DOMParser().parseFromString(html, 'text/html');
	for (const tmpl of Array.from(doc.querySelectorAll('template'))) {
		document.body.appendChild(tmpl);
	}
}

// CodeMirror, marked, validation helpers and inline editor popup handles
// moved to wt-inspector along with the Inspector UI itself.

// Helper to convert GraphQL Node to CmsItem-compatible object. The Inspector
// target fields come from the shared nodeToInspectorTarget(); the list view adds
// a few extras on top (existence, children, derived display attributes).
function nodeToContentItem(node: Node): ContentItem {
	return {
		...nodeToInspectorTarget(node),
		exists: true,
		hasChildren: node.hasChildren || false,
		attributes: {},
	};
}

interface ContentItem extends InspectorTarget {
	exists: boolean;
	hasChildren: boolean;
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

// Delay before the folder-navigation spinner becomes visible. A folder load
// clears the list and fetches the destination's children; most loads finish
// well within this window, so the list simply repaints with no spinner. Only a
// genuinely slow load keeps running past the delay and reveals the spinner —
// avoiding the eye-strain flicker of an indicator that appears and vanishes
// within a few frames.
const NAV_SPINNER_DELAY_MS = 300;

export const App = {
	data() {
		return {
			instance: null as ApplicationInstance | null,
			idp: null as IdpServiceGraphQL | null,
			currentPath: '/content',
			items: [] as any[],
			// Folder-navigation busy state. While a folder list is being
			// (re)loaded, `isNavigating` drives a transparent shield that
			// swallows clicks / right-clicks so nothing can be operated
			// mid-move — without the opaque full-screen overlay that used to
			// flicker on fast loads. `navSpinnerVisible` is revealed only once
			// the load has run past NAV_SPINNER_DELAY_MS, so a subtle spinner
			// appears for genuinely slow loads but never flashes on quick ones.
			isNavigating: false,
			navSpinnerVisible: false,
			// Inline message shown in the list area when a folder fails to load.
			loadError: '',
			// Monotonic load id + delayed-spinner timer handle, so overlapping
			// loads never tear down each other's busy state.
			_navSeq: 0,
			_navSpinnerTimer: null as number | null,
			sortColumn: 'name' as string,
			sortDirection: 'asc' as 'asc' | 'desc',
			uploadMonitor: null as any,
			messageListener: null,
			// Reactive Localization snapshot (effective locale + IANA time
			// zone). Date displays read this and repaint on
			// `localization-changed`. See `composables/use-localization.ts`.
			localization: createLocalizationSnapshot(),
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
				itemsDeleted: number;
				currentPath: string;
				status: JobStatus;
				errorMessage: string;
				isFinished: boolean;
				isAborting: boolean;
				targetPath: string;
			},
			archiveMonitor: null as null | {
				jobId: string;
				handle: ArchiveJobHandle;
				filename: string;
				itemsTotal: number;
				itemsProcessed: number;
				itemsArchived: number;
				currentFile: string;
				status: JobStatus;
				errorMessage: string;
				isFinished: boolean;
				isAborting: boolean;
			},
			// Import-from-archive: options dialog for a chosen CMS Archive file.
			importDialog: {
				visible: false,
				file: null as File | null,
				fileName: '',
				// Reference info read from the archive's manifest (the path(s) the
				// content was exported from). Empty until/unless the manifest is
				// readable; shown read-only with a copy button.
				exportSource: '',
				destinationPath: '',
				// Identifier behaviour (uuidBehavior): 0 throw on collision, 1 new on
				// collision, 2 always new. Path behaviour (pathBehavior): 0 throw on
				// conflict, 1 skip, 2 overwrite. Integer codes of ImportContentHandler.
				uuidBehavior: 0 as 0 | 1 | 2,
				pathBehavior: 0 as 0 | 1 | 2,
				importAcl: false,
				// Carry over each node's original jcr:created/jcr:lastModified.
				// On by default — copying/migrating content normally means to keep
				// its history; jcr:createdBy/jcr:lastModifiedBy still become the
				// importing user either way.
				preserveTimestamps: true,
				dryRun: true,
				isLoading: false,
				errorMessage: '',
			},
			// Import-from-archive: live progress of the running import job.
			importMonitor: null as null | {
				jobId: string;
				handle: ArchiveJobHandle;
				dryRun: boolean;
				itemsTotal: number;
				itemsImported: number;
				// Per-file outcome counts (the four sum to itemsTotal).
				itemsNew: number;
				itemsOverwritten: number;
				itemsSkipped: number;
				itemsError: number;
				// First errors (up to 20) for display; full set in the CSV report.
				errorSamples: Array<{ path: string; message: string }>;
				// Set on the terminal event when a downloadable CSV report exists.
				downloadUrl: string;
				currentPath: string;
				status: JobStatus;
				phase: string;
				errorMessage: string;
				isFinished: boolean;
				isAborting: boolean;
				// Dry-run verdict, filled from the terminal event of a rehearsal.
				dryRunHasErrors: boolean;
				dryRunNodeCount: number;
				dryRunBinaryCount: number;
				dryRunDetail: string;
				// The chosen archive File and the options it ran with, retained after a
				// dry run so the verdict screen can run it for real or reopen the
				// settings. Cleared once consumed or closed.
				file: File | null;
				fileName: string;
				options: {
					destinationPath: string;
					uuidBehavior: 0 | 1 | 2;
					pathBehavior: 0 | 1 | 2;
					importAcl: boolean;
					preserveTimestamps: boolean;
				};
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
			// versionHistoryDialog / propTypeOptions moved to wt-inspector.
			fileTypeOptions: [
				{ id: 'text', label: 'Text Document', labelKey: 'text', extension: '.txt', mimeType: 'text/plain' },
				{ id: 'html', label: 'HTML Document', labelKey: 'html', extension: '.html', mimeType: 'text/html' },
				{ id: 'css', label: 'CSS Stylesheet', labelKey: 'css', extension: '.css', mimeType: 'text/css' },
				{ id: 'javascript', label: 'JavaScript', labelKey: 'javascript', extension: '.js', mimeType: 'application/javascript' },
				{ id: 'json', label: 'JSON', labelKey: 'json', extension: '.json', mimeType: 'application/json' },
				{ id: 'xml', label: 'XML Document', labelKey: 'xml', extension: '.xml', mimeType: 'application/xml' },
				{ id: 'markdown', label: 'Markdown', labelKey: 'markdown', extension: '.md', mimeType: 'text/markdown' },
				{ id: 'csv', label: 'CSV', labelKey: 'csv', extension: '.csv', mimeType: 'text/csv' },
				{ id: 'yml', label: 'YAML Document', labelKey: 'yml', extension: '.yml', mimeType: 'application/yaml' },
				{ id: 'bpmn', label: 'BPMN Document', labelKey: 'bpmn', extension: '.bpmn', mimeType: 'application/bpmn+xml' },
				{ id: 'eip.xml', label: 'EIP/Route Document', labelKey: 'eipxml', extension: '.eip.xml', mimeType: 'application/vnd.webtop.eip+xml' },
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
			// aclDialog / aclSearchDebounceTimer moved to wt-inspector.
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
			// API surface handed to <wt-inspector>. Built once (marked raw) in
			// appLaunch; null until then. See appLaunch for why it must be raw.
			inspectorApi: null as any,
			// Imperative command channel to <wt-inspector> (open an overlay,
			// etc.). The component watches this prop; the nonce makes repeated
			// identical actions re-trigger.
			inspectorCommand: null as { action: string; nonce: number } | null,
			_inspectorCmdSeq: 0,
			// Mirrors whether the inspector currently has an overlay open, so the
			// host can suppress its own global keyboard shortcuts. Updated via the
			// component's overlay-changed event.
			inspectorOverlayOpen: false,
			detailPanelMinWidth: 200,
			detailPanelMaxWidth: 500,
			detailPanelResizing: false,
			detailResizeStartX: 0,
			detailResizeStartWidth: 0,
			_boundResizeMove: null as ((e: MouseEvent) => void) | null,
			_boundResizeUp: null as (() => void) | null,
			// Detail panel: schema cache. Kept here because the XPath search
			// feature (see xpathSchemaProperties / openXpathSchemaDropdown) also
			// reads it. wt-inspector loads its own copy independently.
			availableSchemas: [] as { key: string; label: string; properties: { key: string; label: string; displayFormat?: string; choices?: { value: string; label: string }[]; readOnly?: boolean; multiple?: boolean; editorType?: string; rows?: number; query?: { xpath: string; labelKey: string } }[] }[],
			// Inspector-only state — properties / ACL / property-editor / version-history /
			// CodeMirror / MIME / encoding editors all moved to wt-inspector.
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
			_pendingNodeEvents: [] as { eventType: string; path?: string; identifier?: string; sourcePath?: string }[],
			// Identifier of the current folder (when referenceable), so the parent watch can
			// recognise a path-free DELETED drop signal for the folder we are viewing.
			_currentFolderId: null as string | null,
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
			// Date filter. Boundaries are resolved in the user's preference
			// time zone (OS zone when unset), never the UTC calendar date.
			if (this.filterDateMode !== 'none') {
				const tz = this.localization.timeZone || undefined;
				if (this.filterDateMode === 'today') {
					const start = Dates.startOfDay(tz);
					result = result.filter(item => item.lastModified && item.lastModified >= start);
				} else if (this.filterDateMode === 'pastN') {
					const start = Dates.startOfDay(tz, this.filterDatePastN);
					result = result.filter(item => item.lastModified && item.lastModified >= start);
				} else if (this.filterDateMode === 'range') {
					const from = Dates.fromZonedInputValue(this.filterDateFrom, tz);
					if (from) {
						result = result.filter(item => {
							if (!item.lastModified) return false;
							return this.filterDateFromInclusive
								? item.lastModified >= from
								: item.lastModified > from;
						});
					}
					const to = Dates.fromZonedInputValue(this.filterDateTo, tz);
					if (to) {
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
		// True while either of the two writers of `items` is in flight. The two
		// are mutually exclusive, so this is what a refresh has to wait on.
		listLoading(): boolean {
			return (this.isNavigating as boolean) || (this.xpathSearchLoading as boolean);
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
					// Day boundaries and datetime-local values are resolved in
					// the user's preference time zone (OS zone when unset) and
					// sent as the equivalent UTC instant; the server compares
					// absolute instants, so the boundary must be the user's
					// midnight, not UTC midnight.
					const tz = this.localization.timeZone || undefined;
					if (cond.dateMode === 'today') {
						const start = Dates.startOfDay(tz);
						conditions.push(`@${cond.propertyKey} >= xs:dateTime('${start.toISOString()}')`);
					} else if (cond.dateMode === 'pastN') {
						const start = Dates.startOfDay(tz, cond.datePastN);
						conditions.push(`@${cond.propertyKey} >= xs:dateTime('${start.toISOString()}')`);
					} else if (cond.dateMode === 'range') {
						const from = Dates.fromZonedInputValue(cond.dateFrom, tz);
						if (from) {
							const op = cond.dateFromInclusive ? '>=' : '>';
							conditions.push(`@${cond.propertyKey} ${op} xs:dateTime('${from.toISOString()}')`);
						}
						const to = Dates.fromZonedInputValue(cond.dateTo, tz);
						if (to) {
							const op = cond.dateToInclusive ? '<=' : '<';
							conditions.push(`@${cond.propertyKey} ${op} xs:dateTime('${to.toISOString()}')`);
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
		groupedHistory(): { label: string; labelKey: string; items: { path: string; timestamp: number }[] }[] {
			const now = new Date();
			const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
			const yesterdayStart = todayStart - 86400000;
			const weekStart = todayStart - 6 * 86400000;

			const buckets: { key: string; fallback: string; items: { path: string; timestamp: number }[] }[] = [
				{ key: 'today', fallback: 'Today', items: [] },
				{ key: 'yesterday', fallback: 'Yesterday', items: [] },
				{ key: 'thisWeek', fallback: 'This Week', items: [] },
				{ key: 'older', fallback: 'Older', items: [] },
			];

			for (const entry of this.pathHistory) {
				if (entry.timestamp >= todayStart) {
					buckets[0].items.push(entry);
				} else if (entry.timestamp >= yesterdayStart) {
					buckets[1].items.push(entry);
				} else if (entry.timestamp >= weekStart) {
					buckets[2].items.push(entry);
				} else {
					buckets[3].items.push(entry);
				}
			}

			return buckets
				.filter(b => b.items.length > 0)
				.map(b => ({
					label: this.t('app.content-browser.dategroup.' + b.key, undefined, b.fallback),
					labelKey: b.key,
					items: b.items,
				}));
		},
		// Filtered history for overlay panel search
		filteredGroupedHistory(): { label: string; labelKey: string; items: { path: string; timestamp: number }[] }[] {
			const keyword = this.historySearchKeyword.trim().toLowerCase();
			if (!keyword) return this.groupedHistory;
			return this.groupedHistory
				.map((group: any) => ({
					label: group.label,
					labelKey: group.labelKey,
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
		// Resolved target handed to <wt-inspector>: null / single item / array.
		// The component branches internally on Array.isArray for multi-selection.
		inspectorTarget(): any {
			const sel = this.selectedItems as string[];
			if (sel.length === 0) return null;
			if (sel.length === 1) {
				return (this.items as any[]).find((i: any) => i.id === sel[0]) || null;
			}
			return sel
				.map((id: string) => (this.items as any[]).find((i: any) => i.id === id))
				.filter(Boolean);
		},
		// Status bar text: total items, how many of them are selected, and —
		// when a filter hides some — how many are actually on screen.
		// The total is `items`, not `filteredItems`, so the selected count is
		// always a subset of it: a filter narrows what is shown without
		// dropping the selection (see selectItem, which indexes `items`).
		// Blank while there is no list to describe, leaving the bar as a plain
		// drag strip.
		listStatusText(): string {
			if (this.listLoading || this.loadError || this.currentFolderDeleted) return '';
			const total = (this.items as any[]).length;
			const shown = (this.filteredItems as any[]).length;
			const selected = (this.selectedItems as string[]).length;
			if (selected > 0) {
				if (this.filterActive) {
					return this.t('app.content-browser.status.selectedFiltered', { selected, total, shown },
						`${selected} of ${total} items selected, ${shown} shown`);
				}
				return this.t('app.content-browser.status.selected', { selected, total },
					`${selected} of ${total} items selected`);
			}
			if (this.filterActive) {
				return this.t('app.content-browser.status.itemsFiltered', { total, shown },
					`${total} items, ${shown} shown`);
			}
			return this.t('app.content-browser.status.items', { count: total }, `${total} items`);
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
		/**
		 * Reactive i18n lookup. Reads the localization snapshot so every
		 * `{{ t(...) }}` binding repaints the moment the user switches language
		 * or an i18n bundle is hot-reloaded. See composables/use-localization.ts.
		 */
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.instance, messageId, params, fallback);
		},
		async onMounted() {
			const vm = this;

			// Inject the wt-inspector <template> into <body> so the custom
			// element can resolve `template: '#wt-inspector'` once it gets
			// mounted via the v-if guard below. Must run before any state
			// path can flip `detailPanelVisible` to true.
			try {
				await loadInspectorTemplate();
			} catch (e) {
				console.warn('[ContentBrowser] Failed to load wt-inspector template:', e);
			}

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
				if (handleLocalizationMessage(type, vm.localization, vm.instance)) {
					// Re-stamp the precomputed friendly date on every item so
					// the list cells repaint with the new locale / time zone.
					for (const item of vm.items) {
						if (item?.attributes) {
							item.attributes.displayDate = vm.displayDate(item);
						}
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
				// Build the <wt-inspector> api surface once, marked raw so the
				// reactive system never Proxy-wraps it. content/eventHub/popup
				// all carry private fields (#client, etc.) that throw "Cannot
				// read private member" when invoked through a Proxy.
				vm.inspectorApi = this.$markRaw({
					content: instance.api.content,
					eventHub: instance.api.eventHub,
					popup: instance.popup,
				});
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

				// snapshot the effective Localization preference
				refreshLocalization(vm.localization, vm.instance);

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
			vm.loadError = '';
			vm.currentFolderDeleted = false;
			// This fills the list with a folder's children, so whatever else it
			// held, it is no longer showing search results. xpathSearchActive says
			// exactly that about the list, and everything keyed off it — the search
			// bar, the folder-targeted menu entries — is wrong the moment it
			// outlives the results it describes.
			vm.xpathSearchActive = false;
			vm.xpathSearchTotalCount = 0;
			vm.xpathSearchError = '';
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

			// Enter the navigating state. A transparent shield (rendered while
			// isNavigating is true) now blocks clicks / right-clicks on the
			// list, breadcrumb and sidebar; we deliberately do NOT show an
			// opaque overlay, so the cleared list stays visible and fast loads
			// repaint without any flicker. The spinner is revealed only if this
			// load is still running after NAV_SPINNER_DELAY_MS.
			const seq = ++vm._navSeq;
			vm.isNavigating = true;
			vm.navSpinnerVisible = false;
			if (vm._navSpinnerTimer !== null) clearTimeout(vm._navSpinnerTimer);
			vm._navSpinnerTimer = window.setTimeout(() => {
				vm._navSpinnerTimer = null;
				if (vm._navSeq === seq && vm.isNavigating) vm.navSpinnerVisible = true;
			}, NAV_SPINNER_DELAY_MS);

			try {
				// Use GraphQL API to fetch content
				const contentService = vm.instance.api.content;

				// Verify the parent node exists
				const parentNode = await contentService.getNode(path);
				if (!parentNode) {
					throw new Error(`The item with path "${path}" does not exist.`);
				}
				// Remember the folder's stable identifier (present for every node) so the
				// parent watch can recognise a path-free DELETED drop signal for this folder
				// even when the folder is not mix:referenceable.
				vm._currentFolderId = (parentNode as any).id || (parentNode as any).uuid || null;

				// Fetch all children using auto-pagination. 200 per page: the
				// per-page overhead (round trip, cursor positioning) dominates on
				// large folders, and a 200-row payload is still small.
				const items: ContentItem[] = [];
				for await (const batch of contentService.listAllChildren(path, 200)) {
					for (const node of batch) {
						const item = nodeToContentItem(node);
						item.attributes.displayDate = vm.displayDate(item);
						items.push(item);
					}
				}

				vm.items = items;
				vm.sortItems(vm.sortColumn, vm.sortDirection);
			} catch (error: any) {
				const message = error?.message || String(error) || vm.t('app.content-browser.error.fetchFailed', undefined, 'Failed to fetch data');
				vm.errorMessage = message;
				// Surface the failure inline in the (now empty) list area; the
				// old overlay that used to show it is gone.
				vm.loadError = message;
			} finally {
				// Only the most recent load clears the shared busy state, so a
				// slow earlier load resolving late cannot unblock the UI while a
				// newer navigation is still in flight.
				if (vm._navSeq === seq) {
					if (vm._navSpinnerTimer !== null) {
						clearTimeout(vm._navSpinnerTimer);
						vm._navSpinnerTimer = null;
					}
					vm.isNavigating = false;
					vm.navSpinnerVisible = false;
				}
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
					// Accumulate events during debounce window. identifier is the drop key
					// for a path-free DELETED (a node that became unreadable / was removed).
					vm._pendingNodeEvents.push({
						eventType: event.eventType,
						path: event.path,
						identifier: event.identifier,
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
						// The folder we are viewing may be identified by path (a delete, or a
						// move whose vacated path the server still discloses) or — when the
						// folder's read access is revoked in place — only by its identifier.
						const isWatchedFolder = event.path === watchedPath
							|| (!!event.identifier && event.identifier === vm._currentFolderId);
						if (!isWatchedFolder) return;
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
		async _processNodeEvents(events: { eventType: string; path?: string; identifier?: string; sourcePath?: string }[]) {
			const vm = this;
			const contentService = vm.instance?.api?.content;
			if (!contentService) return;

			// Deduplicate by a stable key (identifier when present, else path) — keep the
			// latest event per node. A path-free DELETED drop signal (a node that became
			// unreadable / was removed) dedups by identifier, so it correctly supersedes an
			// earlier CREATED/MODIFIED for the same node.
			const deduplicated: { eventType: string; path?: string; identifier?: string; sourcePath?: string }[] = [];
			const seen = new Map<string, number>();
			for (let i = 0; i < events.length; i++) {
				const e = events[i];
				const key = e.identifier || e.path || '';
				const prevIdx = seen.get(key);
				if (prevIdx !== undefined) {
					deduplicated[prevIdx] = e;
				} else {
					seen.set(key, deduplicated.length);
					deduplicated.push(e);
				}
			}

			const flashIds: string[] = [];

			for (const evt of deduplicated) {
				const path = evt.path;
				const eventType = evt.eventType;
				const sourcePath = evt.sourcePath;
				const identifier = evt.identifier;

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
						// Remove the item. A DELETED is either a real delete (carries path) or a
						// path-free drop signal for a node that became unreadable (identifier
						// only, path withheld by the server) — match on either key.
						const idx = vm.items.findIndex((i: any) =>
							(identifier && i.id === identifier) || (path && i.path === path));
						if (idx !== -1) {
							const removedId = vm.items[idx].id;
							vm.items.splice(idx, 1);
							vm.selectedItems = vm.selectedItems.filter((id: string) => id !== removedId);
						}
					} else if (path) {
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
					if (path) {
						const idx = vm.items.findIndex((i: any) => i.path === path);
						if (idx !== -1) {
							vm.items.splice(idx, 1);
						}
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

			// Detail panel refresh is owned by <wt-inspector>: it subscribes to
			// its own target.path through eventHub.watchNode and reloads
			// properties / ACL / version listings when MODIFIED events arrive.
			// The host no longer needs to relay anything.
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
		// wt-inspector event handlers — thin wrappers that delegate to the
		// existing host methods. The inspector only emits a target / path; it
		// has no opinion on how the host opens or navigates.
		onInspectorOpenItem(target: any) {
			if (!target) return;
			this.openItem(target);
		},
		onInspectorNavigatePath(path: string) {
			if (!path) return;
			this.navigateToPath(path);
		},
		// Open the folder that contains the given item and select the item
		// within the freshly loaded list. Used by the inspector's
		// "Open Containing Folder" action — handy when the item is a search
		// result whose parent differs from the current folder.
		async onInspectorRevealItem(target: any) {
			const path = target?.path;
			if (!path) return;
			const idx = path.lastIndexOf('/');
			const parent = idx > 0 ? path.substring(0, idx) : '/';
			// While search results are showing, the list holds matches that may
			// span folders, so always reload the real folder listing even when the
			// parent is already the current path. The load clears the search state.
			if (this.xpathSearchActive || parent !== this.currentPath) {
				await this.navigateToPath(parent);
			}
			const item = (this.items as any[]).find((i: any) => i.path === path);
			if (!item) return;
			this.selectedItems = [item.id];
			this.lastSelectedIndex = (this.items as any[]).findIndex((i: any) => i.id === item.id);
			this.$nextTick(() => {
				const row = document.querySelector('.content-list tbody tr.item-selected');
				row?.scrollIntoView({ block: 'nearest' });
			});
		},
		onInspectorOverlayChanged(open: boolean) {
			this.inspectorOverlayOpen = !!open;
		},
		// Open the panel (if needed) and ask the inspector to show an overlay.
		commandInspector(action: string) {
			const vm = this;
			if (!vm.detailPanelVisible) {
				vm.detailPanelVisible = true;
				vm.persistDetailPanelState();
			}
			vm.inspectorCommand = { action, nonce: ++vm._inspectorCmdSeq };
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
			return displaySizeUtil(item);
		},
		displayDate(item: any) {
			const options = {
				format: 'friendly',
				locale: this.localization.locale || undefined,
				timeZone: this.localization.timeZone || undefined,
			};
			if (item.isCollection) {
				return this.instance.util.dates.format(item.created, options);
			}
			return this.instance.util.dates.format(item.lastModified, options);
		},
		displayType(item: any) {
			return displayTypeUtil(item);
		},
		// MIME / Encoding inline editors moved into wt-inspector.
		displayVersion(item: any) {
			return displayVersionUtil(item);
		},
		// Lock owner shown as a friendly display name, falling back to the raw
		// identifier when the principal has no display name (or could not be resolved).
		lockOwnerName(item: any): string {
			const info = item?.lockInfo;
			if (!info) return '';
			return info.lockOwnerDisplayName || info.lockOwner || '';
		},
		getFileIcon(item: any): string {
			return getFileIconUtil(item);
		},
		getFileIconClass(item: any): string {
			return getFileIconClassUtil(item);
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
					av = (a.lockInfo?.lockOwnerDisplayName || a.lockInfo?.lockOwner || '').toLowerCase();
					bv = (b.lockInfo?.lockOwnerDisplayName || b.lockInfo?.lockOwner || '').toLowerCase();
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
			// Accept save-as drops from other apps
			if (event.dataTransfer.types.includes('application/x-webtop-save')) {
				event.dataTransfer.dropEffect = 'copy';
				return;
			}
			// OS file upload
			event.dataTransfer.dropEffect = 'copy';
		},
		// ── Upload from PC (native file picker) ──────────────────────────────
		// Open the OS file chooser. The hidden <input> lives in index.html; its
		// change event is handled by onUploadInputChange.
		triggerFileUpload() {
			if (this.currentFolderDeleted) return;
			const el = this.$refs.uploadFileInput as HTMLInputElement | undefined;
			el?.click();
		},
		triggerFolderUpload() {
			if (this.currentFolderDeleted) return;
			const el = this.$refs.uploadFolderInput as HTMLInputElement | undefined;
			el?.click();
		},
		async onUploadInputChange(event: Event) {
			const vm = this;
			const input = event.target as HTMLInputElement;
			const fileList = input.files;
			if (!fileList || fileList.length === 0) { input.value = ''; return; }
			// For a folder pick, webkitRelativePath carries the nested path so the
			// directory tree is recreated on the server; flat picks use the name.
			const files = Array.from(fileList).map((f: File) => ({
				file: f,
				path: (f as any).webkitRelativePath || f.name,
			}));
			// Reset so picking the same file(s) again still fires change.
			input.value = '';
			await vm._performUpload(files);
		},
		// Shared upload runner: drives the progress monitor, performs the
		// multipart upload, and reloads the directory. Used by the PC upload
		// picker; the drag-and-drop path (onDrop) runs the same monitor inline
		// because it needs cancellation active during async entry collection.
		async _performUpload(files: { file: File; path: string }[]) {
			const vm = this;
			if (!files || files.length === 0) return;
			vm.errorMessage = '';
			vm.uploadMonitor = {
				isCanceled: false,
				target: { currentFile: '', progressPercent: 0 },
				cancel() { this.isCanceled = true; },
			};
			try {
				await vm.uploadFiles(files);
				await vm.load(vm.currentPath);
			} catch (error: any) {
				if (!vm.uploadMonitor?.isCanceled) {
					vm.errorMessage = error?.message || String(error) || vm.t('app.content-browser.error.uploadFailed', undefined, 'Upload failed');
				}
			} finally {
				if (!vm.errorMessage) {
					vm.uploadMonitor = null;
				}
			}
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
					vm.errorMessage = error?.message || vm.t('app.content-browser.error.saveFailed', undefined, 'Save failed');
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
					vm.errorMessage = error?.message || String(error) || vm.t('app.content-browser.error.uploadFailed', undefined, 'Upload failed');
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
		},
		selectItem(item: any, event?: MouseEvent) {
			const vm = this;
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
			// The Inspector reacts to the resulting `inspectorTarget` change
			// via its own `target` watch; no host action needed here.
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

			// New Folder/File, the upload actions and Import all write into the
			// folder being browsed. A search result list has no such folder — its
			// items come from anywhere beneath the search scope — so these are
			// offered only while browsing. They are otherwise independent of the
			// selection, reachable from both an item and empty space.
			if (!vm.xpathSearchActive) {
				menuItems.push({ id: 'new-folder', label: vm.t('app.content-browser.menu.newFolder', undefined, 'New Folder'), icon: 'bi-folder-plus' });
				menuItems.push({ id: 'new-file', label: vm.t('app.content-browser.menu.newFile', undefined, 'New File'), icon: 'bi-file-earmark-plus' });
				menuItems.push({ id: 'upload-files', label: vm.t('app.content-browser.menu.uploadFiles', undefined, 'Upload Files'), icon: 'bi-upload' });
				menuItems.push({ id: 'upload-folder', label: vm.t('app.content-browser.menu.uploadFolder', undefined, 'Upload Folder'), icon: 'bi-folder-symlink' });
				menuItems.push({ id: 'import-archive', label: vm.t('app.content-browser.menu.import', undefined, 'Import…'), icon: 'bi-box-arrow-in-down' });
				menuItems.push({ type: 'separator', id: '', label: '' });
			}

			if (isSingleSelection && hasFolder) {
				menuItems.push({ id: 'open', label: vm.t('app.content-browser.menu.open', undefined, 'Open'), icon: 'bi-folder2-open' });
			}
			// Download is available for any selection: a single plain file streams
			// directly, while folders and multi-selections are bundled into a ZIP.
			// Export sits beside it as the counterpart of "Import…": it always
			// produces a ZIP carrying the restorable metadata sidecar, whereas a
			// plain Download is just the files.
			if (hasFile || hasFolder) {
				menuItems.push({ id: 'download', label: vm.t('app.content-browser.menu.download', undefined, 'Download'), icon: 'bi-download' });
				menuItems.push({ id: 'export', label: vm.t('app.content-browser.menu.export', undefined, 'Export'), icon: 'bi-box-arrow-up' });
			}
			menuItems.push({ type: 'separator', id: '', label: '' });
			menuItems.push({ id: 'copy', label: vm.t('app.content-browser.menu.copy', undefined, 'Copy'), icon: 'bi-clipboard' });
			menuItems.push({ id: 'cut', label: vm.t('app.content-browser.menu.cut', undefined, 'Cut'), icon: 'bi-scissors' });
			if (vm.clipboardHasItems()) {
				menuItems.push({ id: 'paste', label: vm.t('app.content-browser.menu.paste', undefined, 'Paste'), icon: 'bi-clipboard-check' });
			}
			menuItems.push({ type: 'separator', id: '', label: '' });
			if (isSingleSelection) {
				menuItems.push({ id: 'rename', label: vm.t('app.content-browser.menu.rename', undefined, 'Rename'), icon: 'bi-pencil' });
			}
			// Lock/Unlock options
			if (hasUnlocked) {
				menuItems.push({ id: 'lock', label: vm.t('app.content-browser.menu.lock', undefined, 'Lock'), icon: 'bi-lock' });
			}
			if (hasLocked) {
				menuItems.push({ id: 'unlock', label: vm.t('app.content-browser.menu.unlock', undefined, 'Unlock'), icon: 'bi-unlock' });
			}
			// Referenceable options (only for files, not folders; exclude versionable from disable)
			if (hasNonReferenceableFile) {
				menuItems.push({ id: 'enable-referenceable', label: vm.t('app.content-browser.menu.enableReferenceable', undefined, 'Enable Referenceable'), icon: 'bi-link-45deg' });
			}
			if (hasReferenceableNonVersionable) {
				menuItems.push({ id: 'disable-referenceable', label: vm.t('app.content-browser.menu.disableReferenceable', undefined, 'Disable Referenceable'), icon: 'bi-link' });
			}
			// Version control options (files only, not folders)
			if (hasNonVersionableFile) {
				menuItems.push({ id: 'enable-version-control', label: vm.t('app.content-browser.menu.enableVersionControl', undefined, 'Enable Version Control'), icon: 'bi-clock-history' });
			}
			if (hasCheckedInFile) {
				menuItems.push({ id: 'checkout', label: vm.t('app.content-browser.menu.checkout', undefined, 'Checkout'), icon: 'bi-box-arrow-up-right' });
			}
			if (hasCheckedOutFile) {
				menuItems.push({ id: 'checkin', label: vm.t('app.content-browser.menu.checkin', undefined, 'Checkin'), icon: 'bi-box-arrow-in-down-left' });
				menuItems.push({ id: 'checkpoint', label: vm.t('app.content-browser.menu.checkpoint', undefined, 'Checkpoint'), icon: 'bi-save' });
			}
			if (hasCheckedOutNonRootFile) {
				menuItems.push({ id: 'uncheckout', label: vm.t('app.content-browser.menu.cancelCheckout', undefined, 'Cancel Checkout'), icon: 'bi-x-circle' });
			}
			// Version History: show for versionable files (single selection only)
			const hasVersionableFile = selectedItemObjects.some((i: any) => i.isVersionable && !i.isCollection);
			if (isSingleSelection && hasVersionableFile) {
				menuItems.push({ id: 'version-history', label: vm.t('app.content-browser.menu.versionHistory', undefined, 'Version History'), icon: 'bi-clock-history' });
			}
			// Permissions (single selection only)
			if (isSingleSelection) {
				menuItems.push({ id: 'permissions', label: vm.t('app.content-browser.menu.permissions', undefined, 'Permissions'), icon: 'bi-shield-lock' });
			}
			menuItems.push({ type: 'separator', id: '', label: '' });
			menuItems.push({ id: 'delete', label: vm.t('app.content-browser.menu.delete', undefined, 'Delete'), icon: 'bi-trash', danger: true });

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

			// Build context menu with New Folder / New File / Upload / Paste.
			// Every entry here targets the folder being browsed, so a search
			// result list leaves nothing to offer.
			if (vm.xpathSearchActive) {
				return;
			}
			const menuItems: { id: string; label: string; icon?: string; danger?: boolean; type?: string }[] = [];
			menuItems.push({ id: 'new-folder', label: vm.t('app.content-browser.menu.newFolder', undefined, 'New Folder'), icon: 'bi-folder-plus' });
			menuItems.push({ id: 'new-file', label: vm.t('app.content-browser.menu.newFile', undefined, 'New File'), icon: 'bi-file-earmark-plus' });
			menuItems.push({ id: 'upload-files', label: vm.t('app.content-browser.menu.uploadFiles', undefined, 'Upload Files'), icon: 'bi-upload' });
			menuItems.push({ id: 'upload-folder', label: vm.t('app.content-browser.menu.uploadFolder', undefined, 'Upload Folder'), icon: 'bi-folder-symlink' });
			menuItems.push({ id: 'import-archive', label: vm.t('app.content-browser.menu.import', undefined, 'Import…'), icon: 'bi-box-arrow-in-down' });
			if (vm.clipboardHasItems()) {
				menuItems.push({ type: 'separator', id: '', label: '' });
				menuItems.push({ id: 'paste', label: vm.t('app.content-browser.menu.paste', undefined, 'Paste'), icon: 'bi-clipboard-check' });
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
				case 'upload-files':
					vm.triggerFileUpload();
					break;
				case 'upload-folder':
					vm.triggerFolderUpload();
					break;
				case 'import-archive':
					vm.openImportPicker();
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
					// Plain download: no `.cms-archive/` metadata sidecar, so the
					// result is exactly the user's files. A single plain file streams
					// directly; folders and any multi-selection are bundled into a ZIP.
					// A node with no stream URL (not an nt:file) also takes the ZIP
					// route, which needs only its path — never a silent no-op.
					if (selectedItemObjects.length === 1 && !selectedItemObjects[0].isCollection && selectedItemObjects[0].downloadURL) {
						const only = selectedItemObjects[0];
						vm.downloadFile(only.downloadURL, only.name);
					} else if (selectedItemObjects.length > 0) {
						vm.startZipDownload(selectedItemObjects, { includeMetadata: false });
					}
					break;
				case 'export':
					// Export = full-fidelity archive: always a ZIP carrying the
					// restorable `.cms-archive/` metadata sidecar *including* node
					// ACLs, so an import can faithfully reinstate permissions (the
					// counterpart of "Import…"). Even a single file is archived
					// rather than streamed directly, so its metadata travels with it.
					if (selectedItemObjects.length > 0) {
						vm.startZipDownload(selectedItemObjects, { includeMetadata: true, includeAcl: true });
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
						vm.commandInspector('version-history');
					}
					break;
				case 'permissions':
					if (selectedItemObjects.length === 1) {
						vm.commandInspector('permissions');
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
				vm.errorMessage = error?.message || vm.t('app.content-browser.error.lockFailed', undefined, 'Failed to lock');
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
				vm.errorMessage = error?.message || vm.t('app.content-browser.error.unlockFailed', undefined, 'Failed to unlock');
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
					vm.errorMessage = error?.message || String(error) || vm.t('app.content-browser.error.pasteFailed', undefined, 'Paste failed');
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

			// Replace the browser's default ghost (which can capture neighbouring,
			// unselected rows) with one that shows exactly what is being dragged.
			vm._setDragImage(event, dragItems);

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
		 * Build a custom drag image showing only the dragged items so the ghost
		 * never includes unselected rows. The element is rendered off-screen just
		 * long enough for the browser to snapshot it, then removed.
		 */
		_setDragImage(event: DragEvent, dragItems: ContentItem[]) {
			try {
				if (!event.dataTransfer || dragItems.length === 0) return;
				const count = dragItems.length;
				const ghost = document.createElement('div');
				ghost.style.cssText = [
					'position:fixed', 'top:-1000px', 'left:-1000px',
					'display:inline-flex', 'align-items:center', 'gap:8px',
					'max-width:320px', 'padding:6px 12px', 'border-radius:6px',
					'background:var(--bs-primary,#0d6efd)', 'color:#fff',
					'font:13px/1.2 system-ui,-apple-system,sans-serif',
					'box-shadow:0 2px 8px rgba(0,0,0,.3)', 'white-space:nowrap',
					'pointer-events:none', 'z-index:2147483647',
				].join(';');

				const icon = document.createElement('i');
				icon.className = count === 1 ? this.getFileIcon(dragItems[0]) : 'bi bi-files';
				ghost.appendChild(icon);

				const label = document.createElement('span');
				label.style.cssText = 'overflow:hidden;text-overflow:ellipsis';
				label.textContent = count === 1 ? (dragItems[0].name || '') : `${count} items`;
				ghost.appendChild(label);

				document.body.appendChild(ghost);
				event.dataTransfer.setDragImage(ghost, 12, 12);
				setTimeout(() => { try { ghost.remove(); } catch (_) { /* ignore */ } }, 0);
			} catch (_) { /* setDragImage is best-effort */ }
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
					vm.errorMessage = error?.message || String(error) || vm.t('app.content-browser.error.operationFailed', undefined, 'Operation failed');
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
				vm.errorMessage = error?.message || vm.t('app.content-browser.error.enableReferenceableFailed', undefined, 'Failed to enable referenceable');
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
				vm.errorMessage = error?.message || vm.t('app.content-browser.error.disableReferenceableFailed', undefined, 'Failed to disable referenceable');
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
				vm.errorMessage = error?.message || vm.t('app.content-browser.error.enableVersionControlFailed', undefined, 'Failed to enable version control');
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
				vm.errorMessage = error?.message || vm.t('app.content-browser.error.checkoutFailed', undefined, 'Failed to checkout');
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
			} catch (error: any) {
				vm.errorMessage = error?.message || vm.t('app.content-browser.error.checkinFailed', undefined, 'Failed to checkin');
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
			} catch (error: any) {
				vm.errorMessage = error?.message || vm.t('app.content-browser.error.createCheckpointFailed', undefined, 'Failed to create checkpoint');
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
				vm.errorMessage = error?.message || vm.t('app.content-browser.error.cancelCheckoutFailed', undefined, 'Failed to cancel checkout');
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
				const input = vm.$refs.renameInput as HTMLInputElement | undefined;
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
				vm.renameDialog.errorMessage = vm.t('app.content-browser.error.nameEmpty', undefined, 'Name cannot be empty');
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
				vm.renameDialog.errorMessage = error?.message || vm.t('app.content-browser.error.renameFailed', undefined, 'Failed to rename');
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
								itemsDeleted: 0,
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
					vm.deleteMonitor.errorMessage = error?.message || vm.t('app.content-browser.error.startDeleteFailed', undefined, 'Failed to start delete');
					vm.deleteMonitor.isFinished = true;
				} else {
					vm.deleteDialog.errorMessage = error?.message || vm.t('app.content-browser.error.deleteFailed', undefined, 'Failed to delete');
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
			m.itemsDeleted = progress.itemsDeleted;
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
		// ZIP-archive download: bundle a folder or multi-selection server-side
		// and download the resulting ZIP once it's ready.
		async startZipDownload(items: ContentItem[], archiveOptions: { includeMetadata?: boolean; includeAcl?: boolean } = {}) {
			const vm = this;
			if (!items || items.length === 0) return;

			const contentService = vm.instance.api.content;
			const eventHub = vm.instance?.api?.eventHub;
			const filename = vm._zipFilenameFor(items[0]);
			// Root the archive at the folder in view — the browsed folder, or the
			// scope a search ran in (search never leaves currentPath). A selection
			// made from search results can span folders below that scope; anchoring
			// here keeps the archive rooted at the search folder rather than at
			// whatever deeper folder the hits happen to share. While browsing, every
			// item is a direct child of currentPath, so this matches the folder the
			// server would infer anyway.
			const basePath = vm.currentPath;

			try {
				await downloadContentAsZip(
					contentService,
					eventHub,
					items.map((it: ContentItem) => ({ path: it.path })),
					filename,
					{
						includeMetadata: archiveOptions.includeMetadata,
						includeAcl: archiveOptions.includeAcl,
						basePath,
						onStart: (handle: ArchiveJobHandle) => {
							vm.archiveMonitor = {
								jobId: handle.jobId,
								handle,
								filename,
								itemsTotal: items.length,
								itemsProcessed: 0,
								itemsArchived: 0,
								currentFile: '',
								status: 'init' as JobStatus,
								errorMessage: '',
								isFinished: false,
								isAborting: false,
							};
						},
						onProgress: (progress: ArchiveJobProgress) => {
							vm.handleArchiveProgress(progress);
						},
					},
				);
			} catch (error: any) {
				if (vm.archiveMonitor) {
					vm.archiveMonitor.errorMessage = error?.message || vm.t('app.content-browser.error.createArchiveFailed', undefined, 'Failed to create archive');
					vm.archiveMonitor.isFinished = true;
				}
			}
		},
		handleArchiveProgress(progress: ArchiveJobProgress) {
			const vm = this;
			const m = vm.archiveMonitor;
			if (!m || m.jobId !== progress.jobId) return;
			m.status = progress.status;
			m.itemsTotal = progress.itemsTotal;
			m.itemsProcessed = progress.itemsProcessed;
			m.itemsArchived = progress.itemsArchived;
			if (progress.currentPath) {
				const idx = progress.currentPath.lastIndexOf('/');
				m.currentFile = idx >= 0 ? progress.currentPath.slice(idx + 1) : progress.currentPath;
			}
			if (progress.errorMessage) m.errorMessage = progress.errorMessage;
			if (progress.status === 'aborting') m.isAborting = true;

			if (progress.status === 'completed' || progress.status === 'aborted' || progress.status === 'failed') {
				m.isFinished = true;
				m.isAborting = false;
				if (progress.status === 'completed' && progress.downloadUrl) {
					vm.downloadFile(progress.downloadUrl, m.filename);
				}
				// Auto-close on success/abort so the monitor doesn't linger.
				// Failures stay open so the user has time to read errorMessage.
				if (progress.status !== 'failed') {
					const closingJobId = m.jobId;
					setTimeout(() => {
						if (vm.archiveMonitor && vm.archiveMonitor.jobId === closingJobId) {
							vm.closeArchiveMonitor();
						}
					}, 1500);
				}
			}
		},
		requestArchiveAbort() {
			const vm = this;
			const m = vm.archiveMonitor;
			if (!m || m.isFinished || m.isAborting) return;
			m.isAborting = true;
			m.handle.abort();
		},
		closeArchiveMonitor() {
			const vm = this;
			if (vm.archiveMonitor) {
				try { vm.archiveMonitor.handle.release(); } catch { /* noop */ }
			}
			vm.archiveMonitor = null;
		},
		// Import from a CMS Archive: open the OS file chooser. The hidden
		// <input accept=".zip"> lives in index.html.
		openImportPicker() {
			const el = this.$refs.importArchiveInput as HTMLInputElement | undefined;
			if (el) el.click();
		},
		onImportInputChange(event: Event) {
			const vm = this;
			const input = event.target as HTMLInputElement;
			const file = input.files && input.files[0];
			// Reset so the same file can be re-picked consecutively.
			input.value = '';
			if (!file) return;
			vm.importDialog.file = file;
			vm.importDialog.fileName = file.name;
			vm.importDialog.exportSource = '';
			vm.importDialog.destinationPath = vm.currentPath;
			vm.importDialog.uuidBehavior = 0;
			vm.importDialog.pathBehavior = 0;
			vm.importDialog.importAcl = false;
			vm.importDialog.preserveTimestamps = true;
			vm.importDialog.dryRun = true;
			vm.importDialog.errorMessage = '';
			vm.importDialog.isLoading = false;
			vm.importDialog.visible = true;
			// Read the archive's manifest (client-side, no upload) to show where
			// the content was exported from. Purely informational: if it can't be
			// read, the field stays hidden. Guard against a newer pick racing in.
			void readArchiveManifest(file).then((info) => {
				if (!info || vm.importDialog.file !== file) return;
				vm.importDialog.exportSource = info.roots.join('\n');
			}).catch(() => { /* informational only */ });
		},
		closeImportDialog() {
			const vm = this;
			if (vm.importDialog.isLoading) return;
			vm.importDialog.visible = false;
			vm.importDialog.file = null;
		},
		// Upload the chosen archive to a transient staging file and run the import
		// (or dry-run) job. The server relocates the upload next to the job record,
		// so a dry-run verdict re-uploads the retained File to run for real.
		async confirmImport() {
			const vm = this;
			const dlg = vm.importDialog;
			if (dlg.isLoading || !dlg.file) return;
			const file = dlg.file;
			const dryRun = dlg.dryRun;
			const fileName = dlg.fileName;
			const options = {
				destinationPath: dlg.destinationPath || vm.currentPath,
				uuidBehavior: dlg.uuidBehavior,
				pathBehavior: dlg.pathBehavior,
				importAcl: dlg.importAcl,
				preserveTimestamps: dlg.preserveTimestamps,
			};
			dlg.visible = false;
			dlg.isLoading = false;
			await vm._runImportJob(file, options, dryRun, fileName);
		},
		// Shared import runner: uploads the chosen archive to a transient staging
		// file, then starts the import (or dry-run) job. The monitor retains the
		// File and options so a dry-run verdict can run it for real (re-uploading)
		// or reopen the settings.
		async _runImportJob(
			file: File,
			options: { destinationPath: string; uuidBehavior: 0 | 1 | 2; pathBehavior: 0 | 1 | 2; importAcl: boolean; preserveTimestamps: boolean },
			dryRun: boolean,
			fileName: string,
		) {
			const vm = this;
			const contentService = vm.instance.api.content;
			const eventHub = vm.instance?.api?.eventHub;
			let stagingPath = '';
			try {
				// Stage the upload alongside the current folder under a hidden name;
				// the server moves it next to the job record on start.
				const stagingName = '.cms-import-' + Date.now() + '.zip';
				stagingPath = await vm.uploadArchiveFile(file, vm.currentPath, stagingName);
			} catch (error: any) {
				vm.importMonitor = vm._newImportMonitor(null, dryRun, fileName, file, options);
				vm.importMonitor.errorMessage = error?.message || vm.t('app.content-browser.error.uploadFailed', undefined, 'Upload failed');
				vm.importMonitor.isFinished = true;
				vm.importMonitor.status = 'failed' as JobStatus;
				return;
			}
			try {
				await importContentArchive(
					contentService,
					eventHub,
					{ archivePath: stagingPath, filename: fileName, ...options, dryRun },
					{
						onStart: (handle: ArchiveJobHandle) => {
							vm.importMonitor = vm._newImportMonitor(handle, dryRun, fileName, file, options);
						},
						onProgress: (progress: ImportArchiveProgress) => {
							vm.handleImportProgress(progress);
						},
					},
				);
			} catch (error: any) {
				if (vm.importMonitor) {
					vm.importMonitor.errorMessage = error?.message || vm.t('app.content-browser.error.importFailed', undefined, 'Failed to import archive');
					vm.importMonitor.isFinished = true;
					vm.importMonitor.status = 'failed' as JobStatus;
				}
				// The job never started, so the server never relocated the upload;
				// don't leave it orphaned in the content tree.
				await vm._deleteImportStaging(stagingPath);
			}
		},
		// Build a fresh import monitor. handle is null only for an upload that
		// failed before the job started (so the failure has somewhere to show).
		_newImportMonitor(
			handle: ArchiveJobHandle | null,
			dryRun: boolean,
			fileName: string,
			file: File | null,
			options: { destinationPath: string; uuidBehavior: 0 | 1 | 2; pathBehavior: 0 | 1 | 2; importAcl: boolean; preserveTimestamps: boolean },
		) {
			return {
				jobId: handle ? handle.jobId : '',
				handle: handle as ArchiveJobHandle,
				dryRun,
				itemsTotal: 0,
				itemsImported: 0,
				itemsNew: 0,
				itemsOverwritten: 0,
				itemsSkipped: 0,
				itemsError: 0,
				errorSamples: [] as Array<{ path: string; message: string }>,
				downloadUrl: '',
				currentPath: '',
				status: 'init' as JobStatus,
				phase: '',
				errorMessage: '',
				isFinished: false,
				isAborting: false,
				dryRunHasErrors: false,
				dryRunNodeCount: 0,
				dryRunBinaryCount: 0,
				dryRunDetail: '',
				file,
				fileName,
				options,
			};
		},
		handleImportProgress(progress: ImportArchiveProgress) {
			const vm = this;
			const m = vm.importMonitor;
			if (!m || m.jobId !== progress.jobId) return;
			m.status = progress.status;
			m.itemsTotal = progress.itemsTotal;
			m.itemsImported = progress.itemsImported;
			if (typeof progress.itemsNew === 'number') m.itemsNew = progress.itemsNew;
			if (typeof progress.itemsOverwritten === 'number') m.itemsOverwritten = progress.itemsOverwritten;
			if (typeof progress.itemsSkipped === 'number') m.itemsSkipped = progress.itemsSkipped;
			if (typeof progress.itemsError === 'number') m.itemsError = progress.itemsError;
			if (progress.errorSamples) {
				m.errorSamples = progress.errorSamples.map((s) => {
					const tab = s.indexOf('\t');
					return tab >= 0 ? { path: s.slice(0, tab), message: s.slice(tab + 1) } : { path: '', message: s };
				});
			}
			if (progress.downloadUrl) m.downloadUrl = progress.downloadUrl;
			if (progress.currentPath) m.currentPath = progress.currentPath;
			if (progress.errorMessage) m.errorMessage = progress.errorMessage;
			if (progress.status === 'aborting') m.isAborting = true;
			// The dry-run verdict rides the terminal event; capture it so the
			// monitor can switch from "validating" to the rehearsal summary.
			if (typeof progress.dryRunHasErrors === 'boolean') m.dryRunHasErrors = progress.dryRunHasErrors;
			if (typeof progress.dryRunNodeCount === 'number') m.dryRunNodeCount = progress.dryRunNodeCount;
			if (typeof progress.dryRunBinaryCount === 'number') m.dryRunBinaryCount = progress.dryRunBinaryCount;
			if (progress.dryRunDetail) m.dryRunDetail = progress.dryRunDetail;

			if (progress.status === 'completed' || progress.status === 'aborted' || progress.status === 'failed') {
				m.isFinished = true;
				m.isAborting = false;
				// On a real success, refresh the tree to show the imported content.
				if (!m.dryRun && progress.status === 'completed') {
					try { void vm.load(vm.currentPath); } catch { /* noop */ }
				}
			}
		},
		// Run the just-rehearsed archive for real, re-uploading the retained File
		// with the same options. Offered from a clean dry-run verdict.
		async confirmRealImport() {
			const vm = this;
			const m = vm.importMonitor;
			if (!m || !m.file || m.dryRunHasErrors) return;
			await vm._runImportJob(m.file, m.options, false, m.fileName);
		},
		// Reopen the options dialog to change settings after a dry run found a
		// problem, keeping the chosen File so it can be re-uploaded on confirm.
		reopenImportSettings() {
			const vm = this;
			const m = vm.importMonitor;
			if (!m || !m.file) return;
			const dlg = vm.importDialog;
			dlg.file = m.file;
			dlg.fileName = m.fileName;
			dlg.destinationPath = m.options.destinationPath;
			dlg.uuidBehavior = m.options.uuidBehavior;
			dlg.pathBehavior = m.options.pathBehavior;
			dlg.importAcl = m.options.importAcl;
			dlg.preserveTimestamps = m.options.preserveTimestamps;
			dlg.dryRun = true;
			dlg.errorMessage = '';
			dlg.isLoading = false;
			dlg.visible = true;
			vm.importMonitor = null;
		},
		requestImportAbort() {
			const vm = this;
			const m = vm.importMonitor;
			if (!m || m.isFinished || m.isAborting) return;
			m.isAborting = true;
			m.handle.abort();
		},
		async _deleteImportStaging(stagingPath: string) {
			const vm = this;
			if (!stagingPath) return;
			try { await vm.instance.api.content.deleteNode(stagingPath); } catch { /* best effort */ }
		},
		closeImportMonitor() {
			const vm = this;
			const m = vm.importMonitor;
			if (m && m.handle) {
				try { m.handle.release(); } catch { /* noop */ }
			}
			vm.importMonitor = null;
		},
		// Download the CSV outcome report for the finished import/dry-run job.
		downloadImportReport() {
			const vm = this;
			const m = vm.importMonitor;
			if (!m || !m.downloadUrl) return;
			try {
				const a = document.createElement('a');
				a.href = m.downloadUrl;
				a.rel = 'noopener';
				document.body.appendChild(a);
				a.click();
				document.body.removeChild(a);
			} catch { /* best effort */ }
		},
		// Chunked multipart upload of a single File to parentPath/name; returns
		// the created node's absolute path. Mirrors the PC-upload runner.
		async uploadArchiveFile(file: File, parentPath: string, name: string): Promise<string> {
			const vm = this;
			const contentService = vm.instance.api.content;
			// Match the PC-upload chunk size (see uploadFiles). 512KB raw becomes
			// ~700KB once Base64-encoded and wrapped in the GraphQL mutation body,
			// staying under NGINX's default client_max_body_size of 1MB. A larger
			// chunk (e.g. 1MB -> ~1.37MB encoded) overflows that limit and the
			// append request is rejected with HTTP 413.
			const chunkSize = 524288; // 512KB chunks (Base64 encoded ~700KB)
			const uploadInfo = await contentService.initiateMultipartUpload();
			const uploadId = uploadInfo.uploadId;
			try {
				const totalSize = file.size;
				let offset = 0;
				while (offset < totalSize) {
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
				}
				await contentService.completeMultipartUpload(uploadId, parentPath, name, 'application/zip', true);
			} catch (error) {
				try { await contentService.abortMultipartUpload(uploadId); } catch { /* ignore */ }
				throw error;
			}
			const sep = parentPath.endsWith('/') ? '' : '/';
			return parentPath + sep + name;
		},
		// Derive the ZIP file name from the first selected item's name, as
		// requested: a folder keeps its name ("docs" -> "docs.zip"); a file
		// drops its extension ("report.pdf" -> "report.zip").
		_zipFilenameFor(item: ContentItem): string {
			let base = (item?.name || 'archive').trim();
			if (item && !item.isCollection) {
				const dot = base.lastIndexOf('.');
				if (dot > 0) base = base.slice(0, dot);
			}
			if (!base) base = 'archive';
			return base + '.zip';
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
				const input = vm.$refs.newFolderInput as HTMLInputElement | undefined;
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
				vm.newFolderDialog.errorMessage = vm.t('app.content-browser.error.folderNameEmpty', undefined, 'Folder name cannot be empty');
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
				vm.newFolderDialog.errorMessage = error?.message || vm.t('app.content-browser.error.createFolderFailed', undefined, 'Failed to create folder');
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
				const input = vm.$refs.newFileInput as HTMLInputElement | undefined;
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
				vm.newFileDialog.errorMessage = vm.t('app.content-browser.error.fileNameEmpty', undefined, 'File name cannot be empty');
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
				vm.newFileDialog.errorMessage = error?.message || vm.t('app.content-browser.error.createFileFailed', undefined, 'Failed to create file');
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
		// =====================================================================
		// ACL (Permissions) Dialog
		// =====================================================================
		// Extract current item's own entries into pendingEntries/originalEntries
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
				const input = this.$refs.navPathInput as HTMLInputElement | undefined;
				if (input) {
					input.focus();
					input.select();
				}
			});
		},
		exitEditMode() {
			this.navEditMode = false;
		},
		// Normalize a user-typed path: ensure a leading slash, collapse repeated
		// slashes, and strip any trailing slash. Without this a value such as
		// "/content/public/blog/" leaks a trailing slash into the XPath search
		// scope (".../jcr:root/content/public/blog///element(*, nt:file)").
		normalizePath(input: string): string {
			let p = (input || '').trim();
			if (!p) return '/';
			if (!p.startsWith('/')) p = '/' + p;
			p = p.replace(/\/{2,}/g, '/');
			if (p.length > 1) p = p.replace(/\/$/, '');
			return p;
		},
		async confirmEditPath() {
			const path = this.normalizePath(this.editPathValue);
			this.editPathValue = path;
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
					label: vm.t('app.content-browser.history.pinnedLocations', undefined, 'Pinned Locations'),
					icon: 'bi bi-pin-angle',
					info: vm.t('app.content-browser.history.pinnedCount', { count: vm.pinnedPaths.length }, vm.pinnedPaths.length + ' Pinned'),
					emptyMessage: vm.t('app.content-browser.history.noPinnedLocations', undefined, 'No pinned locations'),
					items: vm.pinnedPaths.map((pin: any) => ({
						id: pin.path,
						label: pin.name,
						description: pin.path,
						icon: 'bi bi-folder-symlink',
						actions: [
							{ id: 'unpin', icon: 'bi bi-pin-angle-fill', title: vm.t('app.content-browser.sidebar.unpin', undefined, 'Unpin') },
						],
					})),
				});
				const recent: any = {
					label: vm.t('app.content-browser.history.recentHistory', undefined, 'Recent History'),
					icon: 'bi bi-clock-history',
					emptyMessage: vm.t('app.content-browser.history.noRecentHistory', undefined, 'No recent history'),
					items: vm.pathHistory.map((entry: any) => ({
						id: entry.path,
						label: vm.pathDisplayName(entry.path),
						description: entry.path + ' · ' + vm.formatHistoryTimestamp(entry.timestamp),
						icon: 'bi bi-folder',
						actions: vm.isPathPinned(entry.path)
							? [{ id: 'remove', icon: 'bi bi-x-lg', title: vm.t('app.content-browser.sidebar.remove', undefined, 'Remove'), danger: true, showOnHover: true }]
							: [
								{ id: 'pin', icon: 'bi bi-pin-angle', title: vm.t('app.content-browser.sidebar.pin', undefined, 'Pin'), showOnHover: true },
								{ id: 'remove', icon: 'bi bi-x-lg', title: vm.t('app.content-browser.sidebar.remove', undefined, 'Remove'), danger: true, showOnHover: true },
							],
					})),
				};
				if (vm.pathHistory.length > 0) {
					recent.headerAction = { id: 'clear-all', icon: 'bi bi-trash', label: vm.t('app.content-browser.history.clearAll', undefined, 'Clear All'), title: vm.t('app.content-browser.history.clearAllHistory', undefined, 'Clear all history'), danger: true };
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
					label: vm.t('app.content-browser.history.history', undefined, 'History'),
					items: [{ id: '__no-history', label: vm.t('app.content-browser.history.noHistory', undefined, 'No history'), disabled: true }],
				});
			}
			// Trailing action — open the full history panel
			items.push({
				label: '',
				items: [{ id: '__show-full-history', label: vm.t('app.content-browser.history.showFullHistory', undefined, 'Show full history...'), icon: 'bi bi-clock-history' }],
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
			if (minutes < 1) return this.t('app.content-browser.dategroup.justNow', undefined, 'Just now');
			if (minutes < 60) return this.t('app.content-browser.dategroup.minAgo', { count: minutes }, minutes + ' min ago');
			if (hours < 24) return this.t('app.content-browser.dategroup.hourAgo', { count: hours }, hours + (hours === 1 ? ' hour ago' : ' hours ago'));
			if (days < 7) return this.t('app.content-browser.dategroup.dayAgo', { count: days }, days + (days === 1 ? ' day ago' : ' days ago'));
			const d = new Date(timestamp);
			return d.toLocaleDateString(this.localization.locale || undefined, {
				timeZone: this.localization.timeZone || undefined,
			});
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
		// Localized label for a date-filter mode id ('none'|'today'|'pastN'|'range').
		// Shared by the filter and per-condition date dropdowns + their labels.
		dateModeLabel(id: string): string {
			const map: Record<string, { key: string; fallback: string }> = {
				none: { key: 'anyTime', fallback: 'Any time' },
				today: { key: 'today', fallback: 'Today' },
				pastN: { key: 'pastNDays', fallback: 'Past N days' },
				range: { key: 'dateRange', fallback: 'Date range' },
			};
			const m = map[id] || map.none;
			return this.t('app.content-browser.search.' + m.key, undefined, m.fallback);
		},
		async openFilterDateDropdown(event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !vm.instance) return;
			const rect = trigger.getBoundingClientRect();
			const items = ['none', 'today', 'pastN', 'range'].map(id => ({
				id,
				label: vm.dateModeLabel(id),
				selected: vm.filterDateMode === id,
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
			return this.dateModeLabel(this.filterDateMode);
		},
		async openXpathSchemaDropdown(event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !vm.instance) return;
			const rect = trigger.getBoundingClientRect();
			const items: any[] = [{
				id: '',
				label: vm.t('app.content-browser.search.selectSchema', undefined, 'Select schema...'),
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
		// Shared picker for the detail / property-editor schema dropdowns.
		// Returns the chosen schema key ('' for "No schema"), or undefined
		// when the popup was dismissed without a selection.
		// Per-condition dropdowns (Boolean / Date)
		async openCondBoolDropdown(cond: any, event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !vm.instance) return;
			const rect = trigger.getBoundingClientRect();
			const options: { id: string; key: string; fallback: string }[] = [
				{ id: '', key: 'any', fallback: 'Any' },
				{ id: 'true', key: 'true', fallback: 'True' },
				{ id: 'false', key: 'false', fallback: 'False' },
			];
			const items = options.map(o => ({
				id: o.id || '__any__',
				label: vm.t('app.content-browser.search.' + o.key, undefined, o.fallback),
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
			const map: Record<string, { key: string; fallback: string }> = {
				'': { key: 'any', fallback: 'Any' },
				'true': { key: 'true', fallback: 'True' },
				'false': { key: 'false', fallback: 'False' },
			};
			const m = map[cond.booleanValue] || map[''];
			return this.t('app.content-browser.search.' + m.key, undefined, m.fallback);
		},
		async openCondDateDropdown(cond: any, event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !vm.instance) return;
			const rect = trigger.getBoundingClientRect();
			const items = ['none', 'today', 'pastN', 'range'].map(id => ({
				id,
				label: vm.dateModeLabel(id),
				selected: cond.dateMode === id,
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
			return this.dateModeLabel(cond.dateMode);
		},
		// Property editor dropdowns (shell-rendered popups replacing native <select>)
		async openNewFileTypeDropdown(event: MouseEvent) {
			const vm = this;
			const trigger = event.currentTarget as HTMLElement;
			if (!trigger || !vm.instance) return;
			if (vm.newFileDialog.isLoading) return;
			const rect = trigger.getBoundingClientRect();
			const items = (vm.fileTypeOptions as any[]).map((o: any) => ({
				id: o.id,
				label: `${vm.fileTypeLabel(o)} (${o.extension})`,
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
		// Localized display label for a file-type option.
		fileTypeLabel(o: any): string {
			return this.t('app.content-browser.filetype.' + o.labelKey, undefined, o.label);
		},
		newFileTypeLabel(): string {
			const o = (this.fileTypeOptions as any[]).find((x: any) => x.id === this.newFileDialog.fileType);
			return o ? `${this.fileTypeLabel(o)} (${o.extension})` : '';
		},
		// Copy the archive's export-source path(s) to the clipboard, with the same
		// icon-swap feedback the inspector's path-copy uses.
		async copyExportSource(event?: MouseEvent) {
			const vm = this;
			const text = vm.importDialog.exportSource;
			if (!text) return;
			try {
				await navigator.clipboard.writeText(text);
			} catch {
				const textarea = document.createElement('textarea');
				textarea.value = text;
				document.body.appendChild(textarea);
				textarea.select();
				document.execCommand('copy');
				document.body.removeChild(textarea);
			}
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
		// Re-run whatever produced the current list. Two different queries back
		// the list, and xpathSearchActive is what says which one is showing.
		async refreshList() {
			if (this.xpathSearchActive) {
				await this.xpathExecuteSearch();
				return;
			}
			await this.load(this.currentPath, true);
		},
		async xpathClearSearch() {
			// Reloading the folder is what ends the search: load() clears the
			// search state as part of replacing the list.
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
			} catch {
				// Non-critical: schema loading failure should not block main UI
			}
		},
		// Evaluate calculated default value formula. The formula uses the same
		// ctx API as displayFormat (`ctx.get`, `ctx.getString`, etc.). Returns
		// a string or null.
		// Add all missing properties from the selected schema. Static defaults
		// are applied immediately. Calculated defaults are then evaluated in
		// dependency order based on which property names they reference.
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

			// Inspector overlays close themselves on Escape (the component owns
			// that state and handles the key in capture phase).

			// Ctrl+I: toggle detail panel (works regardless of dialog state)
			if ((event.ctrlKey || event.metaKey) && event.key === 'i') {
				event.preventDefault();
				vm.toggleDetailPanel();
				return;
			}

			// Ignore keydown events when a dialog or nav edit mode is open, when
			// the inspector has an overlay open (tracked via its overlay-changed
			// event), or while a folder is loading — the navigating shield
			// blocks pointer input, so action keys (paste / back / forward) are
			// suppressed here too for parity.
			if (vm.navEditMode || vm.fullHistoryPanelOpen || vm.renameDialog.visible || vm.deleteDialog.visible ||
				vm.newFolderDialog.visible || vm.newFileDialog.visible || vm.conflictDialog.visible ||
				vm.inspectorOverlayOpen || vm.isNavigating) {
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
