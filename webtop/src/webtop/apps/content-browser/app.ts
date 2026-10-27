import { ApplicationInstance } from "../../services/webtop-service.js";
import { type Node, type JobStatus, type ContentLengthEvent, type CloudEntry, type CloudLink, type CloudStorage, type CloudOrder, type CloudQuery, type CloudKind, type CloudFacet, CLOUD_LINK_MIME_TYPE } from "../../graphql/types.js";
import { isCloudPath, parseCloudPath, cloudPath, cloudProviderPath, CLOUD_MY_DRIVE, type CloudLocation } from "../../lib/cloud-location.js";
import { opensAtProvider, type FileRef } from "../../lib/file-handle.js";
import { CloudSource, cloudLocationText, type CloudContentItem, type CloudCrumbs, type CloudItemInfo, type CloudLabels } from "./sources/cloud-source.js";
import { RepositorySource } from "./sources/repository-source.js";
import { isConsentRequired } from "../../services/cloud-service-graphql.js";
import { GraphQLError } from "../../graphql/client.js";
import { deleteContentItems, type DeleteJobHandle, type DeleteJobProgress } from "../../services/content-delete.js";
import { downloadContentAsZip, importContentArchive, type ArchiveJobHandle, type ArchiveJobProgress, type ImportArchiveProgress } from "../../services/content-archive.js";
import { IdpServiceGraphQL } from "../../services/idp-service-graphql.js";
import { BUILD_VERSION } from "../../utils/build-version.js";
import { Dates } from "../../utils/dates.js";
import { nodeToInspectorTarget, THUMBNAIL_PROPERTY, type InspectorTarget } from "../../lib/inspector-target.js";
import { SWATCH_COLORS, SWATCH_COLOR_MAP } from "../../lib/color-palette.js";
import { NEW_FILE_TYPES, withExtension } from "../../lib/new-file-types.js";
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

// wt-inspector renders its property rows with wt-* UI components
// (wt-property-row / wt-badge), so their registrations + templates must be
// present in this document too. initUi() is awaited next to the inspector
// template injection in appLaunch, ahead of the readiness gate.
import { initUi } from "../../ui/index.js";

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

// Path helpers. currentPath is "/" at the repository root, so a naive
// `parent + '/' + name` yields "//usr" there — a path the server used to
// resolve inconsistently (created as "/usr", never matched on lookup), which
// made existence checks miss and the next create fail with ItemExistsException.
// Always build child paths through childPath() so the root is not a special case.
function childPath(parentPath: string, name: string): string {
	let parent = parentPath;
	while (parent.endsWith('/')) parent = parent.slice(0, -1);
	let child = name;
	while (child.startsWith('/')) child = child.slice(1);
	return parent + '/' + child;
}

/** Parent of an absolute path; "/" for a node directly under the root. */
function parentPathOf(path: string): string {
	const idx = path.lastIndexOf('/');
	return (idx <= 0) ? '/' : path.substring(0, idx);
}

// Helper to convert GraphQL Node to CmsItem-compatible object. The Inspector
// target fields come from the shared nodeToInspectorTarget(); the list view adds
// a few extras on top (existence, children, derived display attributes).
function nodeToContentItem(node: Node): ContentItem {
	return {
		...nodeToInspectorTarget(node),
		exists: true,
		hasChildren: node.hasChildren || false,
		folderSize: null,
		attributes: {},
		cloud: null,
	};
}

/** Where the sidebar's Workspace place opens: the workspace's web content. */
const WORKSPACE_PLACE_PATH = '/content';

/** A cloud listing is read this many entries at a time; the next page loads as the list is scrolled. */
const CLOUD_PAGE_SIZE = 200;

/** A cloud search is read 100 results at a time (the providers' own page size for a search). */
const CLOUD_SEARCH_PAGE_SIZE = 100;

/** The Kind facet of a cloud search, in display order: the kinds the providers can be asked for. */
const CLOUD_FACET_KINDS: { key: string; kind: CloudKind; icon: string }[] = [
	{ key: 'image', kind: 'IMAGE', icon: 'bi-image' },
	{ key: 'video', kind: 'VIDEO', icon: 'bi-film' },
	{ key: 'audio', kind: 'AUDIO', icon: 'bi-music-note-beamed' },
	{ key: 'document', kind: 'DOCUMENT', icon: 'bi-file-earmark-text' },
	{ key: 'folder', kind: 'FOLDER', icon: 'bi-folder' },
];

/** The Modified facet of a cloud search: a window back from today, or a range the user enters. */
const CLOUD_FACET_MODIFIED: { key: string; daysAgo: number | null }[] = [
	{ key: 'today', daysAgo: 0 },
	{ key: '7d', daysAgo: 7 },
	{ key: '30d', daysAgo: 30 },
	{ key: 'range', daysAgo: null },
];

/** What the user chose in the facets of a cloud search; nothing, at first. */
function emptyCloudFacetSelection() {
	return { kind: '' as string, modified: '' as string, from: '' as string, to: '' as string, ownedByMe: false };
}

/**
 * The folders of search results, looked up once per folder while a search
 * is shown (Google names only the parent's id; see
 * CloudSource.resolveSearchFolder). Module-wide so the promises stay out of
 * the reactive state; emptied when a search starts.
 */
const cloudSearchFolderCache = new Map<string, Promise<CloudCrumbs>>();

/**
 * A folder's total content size in the list: null until requested, 'pending'
 * while the server computes it, then the result (whose size is null when the
 * server could not determine it).
 */
type FolderSizeState = null | 'pending' | { size: number | null; fileCount: number | null };

interface ContentItem extends InspectorTarget {
	exists: boolean;
	hasChildren: boolean;
	folderSize: FolderSizeState;
	attributes: Record<string, any>;
	// Set for an entry of a cloud drive (see sources/cloud-source.ts); null
	// for a repository node.
	cloud: CloudItemInfo | null;
}

/** The server accepts at most this many paths per contentLengths subscription. */
const FOLDER_SIZE_BATCH = 1000;

/** Folder sizes arriving within this window are applied to the list together. */
const FOLDER_SIZE_FLUSH_MS = 100;

/** Search results are fetched a page at a time; the next page loads as the list is scrolled. */
const SEARCH_PAGE_SIZE = 100;

/** The next search page is requested when the list is scrolled to within this many pixels of its end. */
const SEARCH_LOAD_MORE_MARGIN = 200;

/**
 * The list columns the search index can order by, and the XPath field each one
 * orders on. A search result is sorted by the server (the `order by` clause of
 * the statement), because only the first page is loaded and a client-side sort
 * of one page would disagree with the pages that follow. The remaining columns
 * (lock owner, version) are not indexed, so they still sort what has been
 * loaded on the client.
 */
const SEARCH_SORT_FIELDS: Record<string, string> = {
	name: '@jcr:name',
	date: '@jcr:lastModified',
	modifiedBy: '@jcr:lastModifiedBy',
	kind: '@jcr:mimeType',
	size: '@jcr:contentLength',
};

// ---------------------------------------------------------------------------
// Facets of a search result.
//
// While a search result is listed, the sidebar shows facets in place of the
// favorites, smart folders and search sections: the kind of file (from the
// MIME type), the orientation of images and videos, the format of documents
// and the language of scripts, the color and the tags. Each entry carries
// the number of results it would leave. The counts come from the index's
// `facet accumulate` clause (see documents/search-facets.md), fetched with no
// node: one count-only statement with every filter applied, plus one per
// dimension that has a selection with that dimension's own filter left out,
// so an unselected value still shows what choosing it instead would give.
// Tags are the exception: selecting one narrows to files carrying all of
// them, so their counts come from the fully filtered set.
// ---------------------------------------------------------------------------

type FacetDimension = 'kind' | 'orientation' | 'format' | 'color' | 'tag';

/**
 * The Kind facet, in display order. A script is a file of one of the listed
 * script MIME types; anything that is neither a media kind nor a script is a
 * document.
 */
const FACET_KINDS = ['image', 'video', 'audio', 'script', 'document'];

/** The MIME type prefix of each media kind. */
const FACET_MEDIA_PREFIXES: Record<string, string> = { image: 'image/', video: 'video/', audio: 'audio/' };

/** A Format facet entry: a format (or language) and the MIME types that make it up. */
interface FacetFormat {
	key: string;
	mimeTypes: string[];
}

/** The Orientation facet (mi:orientation, set by the media-metadata route). */
const FACET_ORIENTATIONS = ['portrait', 'landscape', 'square', 'panorama'];

/** The Format entry for documents of no listed format. */
const FACET_FORMAT_OTHER = 'other';

/** The Format facet of documents: each format and the MIME types that make it up. */
const FACET_DOCUMENT_FORMATS: FacetFormat[] = [
	{ key: 'pdf', mimeTypes: ['application/pdf'] },
	{ key: 'text', mimeTypes: ['text/plain'] },
	{ key: 'markdown', mimeTypes: ['text/markdown', 'text/x-markdown'] },
	// A GSP template is a web page too: it is what the server renders as one.
	{ key: 'web', mimeTypes: ['text/html', 'application/xhtml+xml', 'application/x-gsp'] },
	{ key: 'memo', mimeTypes: ['application/vnd.mintjams.cms.memo+json'] },
	{ key: 'richtext', mimeTypes: [
		'application/rtf',
		'text/rtf',
		'application/msword',
		'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
		'application/vnd.oasis.opendocument.text',
	] },
	// Every other document (templates, archives, ...): no MIME type of its
	// own, it is what the listed ones leave.
	{ key: FACET_FORMAT_OTHER, mimeTypes: [] },
];

/**
 * The Format facet of scripts: each language and the MIME types that make it
 * up. Together they define the Script kind, so a file of any other MIME type
 * is never a script (and there is no "other" entry). The server names a
 * Groovy file application/x-groovy (etc/mime.types); the rest are the
 * registered and the common legacy names.
 */
const FACET_SCRIPT_FORMATS: FacetFormat[] = [
	{ key: 'groovy', mimeTypes: ['application/x-groovy', 'application/groovy', 'text/x-groovy', 'text/groovy'] },
	{ key: 'typescript', mimeTypes: ['text/typescript', 'application/typescript', 'text/x-typescript', 'application/x-typescript'] },
	{ key: 'javascript', mimeTypes: [
		'application/javascript',
		'text/javascript',
		'application/x-javascript',
		'text/x-javascript',
		'application/ecmascript',
		'text/ecmascript',
		'application/x-ecmascript',
	] },
	{ key: 'python', mimeTypes: ['text/x-python', 'application/x-python', 'text/x-python3', 'application/x-python-code'] },
	{ key: 'shell', mimeTypes: ['application/x-sh', 'application/x-shellscript', 'text/x-sh', 'text/x-shellscript', 'application/x-csh'] },
];

/** The Format facet of each kind that has one. */
const FACET_FORMATS_BY_KIND: Record<string, FacetFormat[]> = {
	document: FACET_DOCUMENT_FORMATS,
	script: FACET_SCRIPT_FORMATS,
};

/** The Tags facet lists at most this many tags (the most frequent). */
const FACET_TAG_LIMIT = 50;

/** The facet clause every count-only statement carries. */
const FACET_CLAUSE = `facet accumulate @jcr:mimeType, @mi:orientation, @mi:color, top(@mi:tags, ${FACET_TAG_LIMIT})`;

/** An XPath string literal. A value with an apostrophe is double-quoted instead. */
function xpathString(value: string): string {
	if (value.includes("'")) {
		return '"' + value.replace(/"/g, '') + '"';
	}
	return "'" + value + "'";
}

/** The kind a MIME type belongs to. */
function facetKindOf(mimeType: string): string {
	const type = (mimeType || '').toLowerCase();
	for (const kind of Object.keys(FACET_MEDIA_PREFIXES)) {
		if (type.startsWith(FACET_MEDIA_PREFIXES[kind])) {
			return kind;
		}
	}
	if (FACET_SCRIPT_FORMATS.some(f => f.mimeTypes.includes(type))) {
		return 'script';
	}
	return 'document';
}

/**
 * The format of `kind` a MIME type belongs to. A document of no listed format
 * is FACET_FORMAT_OTHER; a MIME type that is no script at all has no script
 * format (null), and a kind without formats has none either.
 */
function facetFormatOf(kind: string, mimeType: string): string | null {
	const formats = FACET_FORMATS_BY_KIND[kind];
	if (!formats) return null;
	const type = (mimeType || '').toLowerCase();
	const format = formats.find(f => f.mimeTypes.includes(type));
	if (format) return format.key;
	return kind === 'document' ? FACET_FORMAT_OTHER : null;
}

function emptyFacetSelection() {
	return { kind: '', orientation: [] as string[], format: [] as string[], color: [] as string[], tag: [] as string[] };
}

function emptyFacetCounts(): Record<FacetDimension, Record<string, number>> {
	return { kind: {}, orientation: {}, format: {}, color: {}, tag: {} };
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

/**
 * Drag type of an attachment dragged out of the Mail app: a list of
 * `{ url, name, mimeType }`. The file is read from its URL (the Mail app's
 * attachment script in the system workspace, this origin) and uploaded like a
 * file from the PC, so conflicts and versioned files are handled the same way.
 */
const MAIL_ATTACHMENT_DRAG_TYPE = 'application/x-webtop-mail-attachment';

interface MailAttachmentDrag {
	url: string;
	name: string;
	mimeType?: string;
}

export const App = {
	data() {
		return {
			// Readiness gate for the whole screen (see the <template v-if> in
			// index.html). Flipped by appLaunch() once the component templates
			// are present, so no component element is connected before its
			// <template> exists.
			isReady: false,
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
			// Cloud files (the files bundle of a linked account): the picker
			// that chooses what to import or where to export, and the outcome
			// of the last export, which has nowhere else to show.
			cloudPicker: {
				visible: false,
				provider: '',
				providerName: '',
				mode: 'files' as 'files' | 'folder',
				multiple: true,
				// 'copy': the selected repository items go to the picked cloud folder.
				action: '' as '' | 'copy',
			},
			cloudResult: {
				visible: false,
				title: '',
				errors: [] as string[],
			},
			// The repository folder picker: where selected cloud items are copied
			// to ('import'), or where a link to them is written ('link').
			folderPicker: {
				visible: false,
				action: '' as '' | 'import' | 'link',
				items: [] as ContentItem[],
			},
			// Cloud storage as a location of the list (see sources/cloud-source.ts
			// and lib/cloud-location.ts). The providers an administrator enabled,
			// with whether the user allowed the files bundle; the crumbs of the
			// cloud location being listed (null in the repository); the paging
			// of the cloud listing, read as the list is scrolled; and a sequence
			// so a page of an earlier listing is dropped when it arrives late.
			cloudStorages: [] as CloudStorage[],
			cloudCrumbs: null as CloudCrumbs | null,
			cloudCursor: null as string | null,
			cloudHasMore: false,
			cloudLoadingMore: false,
			_cloudSeq: 0,
			_cloudSource: null as CloudSource | null,
			_repoSource: null as RepositorySource | null,
			_connectionsChannel: null as BroadcastChannel | null,
			// The cloud path a consent popup was already shown for, so a grant
			// that still does not open it is not asked for again and again.
			_cloudConsentRetry: '',
			// The repository items "Copy to cloud storage…" is copying, while
			// the cloud folder picker is open.
			_cloudCopyItems: null as ContentItem[] | null,
			// The repository folder last listed: where "Copy to repository…"
			// starts its folder picker while a cloud location is shown.
			lastRepositoryPath: '/content',
			// Where the last context menu was asked for (iframe-local), for a
			// popup an action opens without an event of its own.
			_contextMenuAt: null as { x: number; y: number } | null,
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
			fileTypeOptions: NEW_FILE_TYPES,
			// Selection state. The Shift anchor and the keyboard cursor are held
			// as item ids rather than row indexes: the list is re-sorted,
			// filtered and reloaded underneath them, and a stale id resolves to
			// "no row" instead of pointing at whatever moved into that slot.
			selectedItems: [] as string[],
			// Fixed end of a Shift range — Shift+click and Shift+Arrow extend from here.
			selectionAnchorID: '',
			// Moving end of a Shift range; the row plain Arrow keys step from.
			focusedItemID: '',
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
			// A cloud favourite also keeps its location as names (`display`): the
			// path holds only ids.
			pinnedPaths: [] as { path: string; name: string; display?: string }[],
			MAX_HISTORY_ITEMS: 50,
			navClickOutsideListener: null as ((e: MouseEvent) => void) | null,
			// Back/forward navigation stack (session-only)
			navStack: [] as string[],
			navStackIndex: -1,
			backDropdownOpen: false,
			// Full history overlay panel
			fullHistoryPanelOpen: false,
			historySearchKeyword: '',
			// The file list as rows with columns, or as a grid of tiles with a
			// thumbnail. Kept per user, like the panes.
			listViewMode: 'list' as 'list' | 'grid',
			// Items whose grid picture the browser could not load; their tiles
			// show the icon instead. Emptied with the list.
			gridMediaErrors: [] as string[],
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
			// Server-side paging of the search result: the cursor of the last
			// loaded page, whether a page follows it, and whether that page is
			// being fetched (scrolling the list to its end requests it).
			xpathSearchCursor: null as string | null,
			xpathSearchHasMore: false,
			xpathSearchLoadingMore: false,
			// Monotonic search id: a page that arrives for an earlier search (or
			// after the search was closed) is dropped instead of appended.
			_xpathSearchSeq: 0,
			// The facets of the search result (see the Facets section at the
			// top): what the user selected, the counts the sidebar shows, and
			// whether the counts are being fetched. The sections' open state is
			// not persisted; they open with every search.
			facetSelection: emptyFacetSelection(),
			facetCounts: emptyFacetCounts(),
			facetLoading: false,
			_facetSeq: 0,
			facetSectionExpanded: {
				kind: true,
				orientation: true,
				format: true,
				color: true,
				tag: true,
				modified: true,
				owner: true,
			} as Record<string, boolean>,
			// The facets of a cloud search (see the Cloud search section):
			// the kind, the modification window and the owner the user chose.
			// The providers count nothing, so there are no counts.
			cloudFacetSelection: emptyCloudFacetSelection(),
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
				// The facet selection (see the Facets section); absent on folders
				// saved before facets existed, and when nothing was selected.
				facets?: ReturnType<typeof emptyFacetSelection>;
			}[],
			smartFolderNextID: 1,
			smartFolderEditing: null as number | null,
			smartFolderEditName: '' as string,
			_suppressServerSync: false,
			// Sidebar collapsible sections (true = expanded)
			sidebarSectionExpanded: {
				filter: true,
				cloud: true,
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
			// Folder size requests for the current list. Bumping the sequence
			// discards results still in flight for a list no longer shown.
			_folderSizeSeq: 0,
			_folderSizeUnsubscribes: [] as (() => void)[],
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
		// A cloud search is listed: the search state is the repository's, the
		// query and the facets are the provider's.
		cloudSearchActive(): boolean {
			return (this.xpathSearchActive as boolean) && (this.isCloudLocation as boolean);
		},
		// The provider of the cloud location being shown, as the server describes it.
		cloudStorage(): CloudStorage | null {
			const provider = this.cloudLocation?.provider;
			if (!provider) return null;
			return (this.cloudStorages as CloudStorage[]).find((s: CloudStorage) => s.provider === provider) || null;
		},
		// Whether the search field can be used here: a drive or a folder of
		// one, not the listings above the drives.
		cloudSearchAvailable(): boolean {
			return (this.isCloudLocation as boolean) && !(this.isCloudVirtualLocation as boolean);
		},
		// The facets the provider's search can be narrowed by (KIND, MODIFIED, OWNER).
		cloudFacets(): CloudFacet[] {
			return (this.cloudStorage as CloudStorage | null)?.facets || [];
		},
		cloudFacetSelectionCount(): number {
			const s = this.cloudFacetSelection;
			return (s.kind ? 1 : 0) + (s.modified ? 1 : 0) + (s.ownedByMe ? 1 : 0);
		},
		// What a search here covers, as the search bar says it: the folder
		// (Microsoft) or the whole drive (Google).
		cloudSearchScopeText(): string {
			const location = this.cloudLocation as CloudLocation | null;
			const crumbs = this.cloudCrumbs as CloudCrumbs | null;
			if (!location || location.virtual) return '';
			const labels = this.cloudLabels as CloudLabels;
			const scope = (this.cloudStorage as CloudStorage | null)?.searchScope || 'FOLDER';
			if (scope === 'DRIVE') {
				const drive = location.driveId === CLOUD_MY_DRIVE
					? labels.myDrive(location.provider)
					: this.t('app.content-browser.cloud.sharedDriveNamed', { name: crumbs?.drive?.name || '…' }, 'shared drive "{name}"');
				return this.t('app.content-browser.cloud.searchingDrive', { name: drive }, 'Searching all of {name}');
			}
			const folder = crumbs?.current?.name
				|| (location.driveId === CLOUD_MY_DRIVE ? labels.myDrive(location.provider) : (crumbs?.drive?.name || '…'));
			return this.t('app.content-browser.cloud.searchingFolder', { name: folder }, 'Searching in {name}');
		},
		// The placeholder of the search field: what a search here covers.
		searchPlaceholder(): string {
			if ((this.cloudStorage as CloudStorage | null)?.searchScope === 'DRIVE') {
				return this.t('app.content-browser.cloud.searchDrivePlaceholder', undefined, 'Search this drive…');
			}
			return this.t('app.content-browser.search.fullTextPlaceholder');
		},
		// The tooltip of the search field: why it is disabled, the parse
		// error, or how to use it.
		searchFieldTitle(): string {
			if (this.isCloudLocation) {
				if (!this.cloudSearchAvailable) {
					return this.t('app.content-browser.cloud.searchOpenDrive', undefined, 'Open a drive to search.');
				}
				return this.t('app.content-browser.cloud.searchHint', undefined, 'Searches names and content at the provider.');
			}
			return (this.fullTextCompiled as any).error || this.t('app.content-browser.search.fullTextHint');
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
		// The XPath predicates of the search inputs: the full-text expression
		// and the schema conditions of the sidebar's Search section. The facet
		// selection adds its own on top (see _xpathStatement).
		xpathBaseConditions(): string[] {
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
			return conditions;
		},
		// The statement of the list: the search inputs narrowed by every facet
		// the user selected.
		xpathBuiltQuery(): string {
			return this._xpathStatement(null);
		},
		// The facet dimensions the sidebar shows for the current selection: the
		// orientation of images and videos, the format of documents and scripts.
		facetShowsOrientation(): boolean {
			const kind = this.facetSelection.kind as string;
			return kind === 'image' || kind === 'video';
		},
		facetShowsFormat(): boolean {
			return (this.facetSelection.kind as string) in FACET_FORMATS_BY_KIND;
		},
		facetSelectionCount(): number {
			const s = this.facetSelection;
			return (s.kind ? 1 : 0) + s.orientation.length + s.format.length + s.color.length + s.tag.length;
		},
		// Whether a schema condition has a value entered (applied or not yet).
		xpathConditionsHaveValues(): boolean {
			return (this.xpathConditions as any[]).some(c =>
				c.stringValue.trim() !== '' ||
				c.numberFrom !== '' ||
				c.numberTo !== '' ||
				c.booleanValue !== '' ||
				c.dateMode !== 'none' ||
				c.selectedChoices.length > 0);
		},
		// Whether the Narrow-down "Clear" has anything to clear: a facet or a
		// schema condition value (see facetClearAll).
		facetCanClear(): boolean {
			return (this.facetSelectionCount as number) > 0 || (this.xpathConditionsHaveValues as boolean);
		},
		// The tags the Tags facet lists: the selected ones first, then the most
		// frequent among the remaining results.
		facetTagEntries(): { tag: string; count: number }[] {
			const counts = this.facetCounts.tag as Record<string, number>;
			const selected = this.facetSelection.tag as string[];
			const entries = selected.map(tag => ({ tag, count: counts[tag] || 0 }));
			const rest = Object.keys(counts).
				filter(tag => !selected.includes(tag)).
				map(tag => ({ tag, count: counts[tag] })).
				sort((a, b) => b.count - a.count || a.tag.localeCompare(b.tag));
			return entries.concat(rest);
		},
		// Whether the current sort column is one the index orders by (see
		// SEARCH_SORT_FIELDS). When it is, the search result arrives in order
		// and the client must not re-sort the loaded pages.
		xpathSortIsServerSide(): boolean {
			return !!SEARCH_SORT_FIELDS[this.sortColumn as string];
		},
		// The statement sent to the server: the built query plus the order the
		// list is showing, so every page continues the previous one.
		xpathSearchStatement(): string {
			const field = SEARCH_SORT_FIELDS[this.sortColumn as string];
			if (!field) {
				return this.xpathBuiltQuery as string;
			}
			const direction = this.sortDirection === 'desc' ? 'descending' : 'ascending';
			return `${this.xpathBuiltQuery} order by ${field} ${direction}`;
		},
		breadcrumb(): { name: string; path: string }[] {
			if (this.isCloudLocation) {
				return this.cloudBreadcrumb();
			}
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
		canGoToParent(): boolean {
			if (this.isCloudLocation) {
				return !!this.cloudParentPath();
			}
			return !this.xpathSearchActive && !!this.currentPath && this.currentPath !== '/';
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
			const fullName = withExtension(name, fileType);
			return this.items.some((item: any) => item.name.toLowerCase() === fullName.toLowerCase());
		},
		cloudPickerLabels(): Record<string, string> {
			return {
				select: this.t('app.content-browser.cloud.pick', undefined, 'Import'),
				// The picker fills {name} itself; the message formatter needs a value for it.
				selectFolder: this.t('app.content-browser.cloud.pickFolder', { name: '{name}' }, 'Export to {name}'),
				cancel: this.t('app.content-browser.dialog.cancel', undefined, 'Cancel'),
				loading: this.t('app.content-browser.cloud.loading', undefined, 'Loading…'),
				empty: this.t('app.content-browser.cloud.empty', undefined, 'This folder is empty.'),
				up: this.t('app.content-browser.toolbar.goToParent', undefined, 'Up'),
				myDrive: this.cloudLabels.myDrive(this.cloudPicker.provider),
			};
		},
		folderPickerLabels(): Record<string, string> {
			const link = this.folderPicker.action === 'link';
			return {
				select: link
					? this.t('app.content-browser.folderPicker.selectLink', undefined, 'Put the link here')
					: this.t('app.content-browser.folderPicker.select', undefined, 'Copy here'),
				cancel: this.t('app.content-browser.dialog.cancel', undefined, 'Cancel'),
				newFolder: this.t('app.content-browser.folderPicker.newFolder', undefined, 'New folder'),
				newFolderName: this.t('app.content-browser.folderPicker.newFolderName', undefined, 'Folder name'),
				create: this.t('app.content-browser.folderPicker.create', undefined, 'Create'),
				loading: this.t('app.content-browser.folderPicker.loading', undefined, 'Loading…'),
				noAccess: this.t('app.content-browser.folderPicker.noAccess', undefined, 'You cannot write here.'),
				invalidName: this.t('app.content-browser.folderPicker.invalidName', undefined, 'The name cannot contain / \\ : * ? " < > |'),
			};
		},
		// ---- Cloud storage as a location ---------------------------------------
		// The names the virtual rows and the breadcrumbs use. The provider is
		// named by what the user keeps there (Google Drive, OneDrive), the own
		// drive and the shared drives by the provider's own words.
		cloudLabels(): CloudLabels {
			const vm = this;
			return {
				providerName: (provider: string) => vm.cloudProviderLabel(provider),
				myDrive: (provider: string) => provider === 'microsoft'
					? vm.t('app.content-browser.cloud.oneDrive', undefined, 'My files')
					: vm.t('app.content-browser.cloud.myDrive', undefined, 'My Drive'),
				sharedDrives: (provider: string) => provider === 'microsoft'
					? vm.t('app.content-browser.cloud.libraries', undefined, 'Libraries')
					: vm.t('app.content-browser.cloud.sharedDrives', undefined, 'Shared drives'),
			};
		},
		isCloudLocation(): boolean {
			return isCloudPath(this.currentPath);
		},
		cloudLocation(): CloudLocation | null {
			return parseCloudPath(this.currentPath);
		},
		// The listings above the drives: the rows there open drives, take no
		// drops, and have no folder to make anything in.
		isCloudVirtualLocation(): boolean {
			return !!this.cloudLocation?.virtual;
		},
		grantedStorages(): CloudStorage[] {
			return (this.cloudStorages as CloudStorage[]).filter(s => s.granted);
		},
		// The Places row for the workspace: lit anywhere under /content.
		isWorkspaceActive(): boolean {
			const path = this.currentPath || '';
			return path === WORKSPACE_PLACE_PATH || path.startsWith(WORKSPACE_PLACE_PATH + '/');
		},
		// The current location as one line: the repository path, or the cloud
		// crumbs as names. What the Dock preview and a favourite show.
		locationText(): string {
			if (!this.isCloudLocation) return this.currentPath || '';
			const crumbs = this.cloudCrumbs as CloudCrumbs | null;
			if (!crumbs) return this.cloudProviderLabel(this.cloudLocation?.provider || '');
			return cloudLocationText(crumbs, this.cloudLabels);
		},
		// Only the columns the providers can order by; the others are inert
		// headers in cloud storage (the list is read a page at a time, so a
		// client-side sort would disagree with the pages still to come).
		cloudSortableColumns(): string[] {
			return ['name', 'date'];
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
		// True while an opaque progress / conflict panel covers the window
		// (upload, delete, archive, import — see .upload-overlay and
		// .conflict-dialog-overlay in style.css). Those panels are inset 1rem
		// from every edge, so they stop short of the status bar, which is
		// taller than that and bleeds into #app's bottom padding. The bar is
		// hidden while one is up, otherwise its lower strip peeks out below the
		// panel with the text cut in half. Same guard the Workspace Manager
		// uses while a lifecycle operation runs.
		progressOverlayVisible(): boolean {
			return !!(this.uploadMonitor || this.deleteMonitor || this.archiveMonitor || this.importMonitor);
		},
	},
	watch: {
		// Push the current folder path to the shell so the Dock hover preview
		// always reflects the latest location. The shell stores this in its
		// own reactive state — see ApplicationInstance.setDisplayInfo().
		currentPath() {
			(this as any).instance?.setDisplayInfo({ subtitle: (this as any).locationText || '' });
		},
		// A cloud location's names arrive after the path (a cold landing looks
		// them up), so the Dock follows them too.
		cloudCrumbs() {
			(this as any).instance?.setDisplayInfo({ subtitle: (this as any).locationText || '' });
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
		// Synchronous on purpose: window.appLaunch must be defined by the time
		// this returns, so the shell finds it on the first try instead of
		// polling for it (see wt-window.ts `appLaunch not found after waiting`).
		// Everything that needs a fetch — the wt-* templates, the inspector
		// template — is awaited inside appLaunch(), ahead of the readiness gate.
		onMounted() {
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
			window.appLaunch = async (instance: ApplicationInstance, options?: { initialPath?: string; select?: string; [key: string]: any }) => {
				// Mark instance as raw (non-reactive) to prevent Proxy wrapping
				// This is necessary because ApplicationInstance has private fields (#id, etc.)
				vm.instance = this.$markRaw(instance);
				// Handed to <wt-cloud-picker> / <wt-folder-picker> as props: class
				// instances with private fields, which must not be read through a
				// reactive proxy.
				this.$markRaw(instance.api.cloud);
				this.$markRaw(instance.api.content);
				vm.idp = this.$markRaw(new IdpServiceGraphQL());
				// Build the <wt-inspector> api surface once, marked raw so the
				// reactive system never Proxy-wraps it. content/eventHub/popup
				// all carry private fields (#client, etc.) that throw "Cannot
				// read private member" when invoked through a Proxy.
				vm.inspectorApi = this.$markRaw({
					content: instance.api.content,
					eventHub: instance.api.eventHub,
					popup: instance.popup,
					cloud: instance.api.cloud,
				});
				// The two sources the list reads from. Marked raw: they hold the
				// services, whose private fields a reactive proxy cannot reach.
				vm._repoSource = this.$markRaw(new RepositorySource(instance.api.content));
				vm._cloudSource = this.$markRaw(new CloudSource(instance.api.cloud, vm.cloudLabels));
				instance.appState = () => ({ initialPath: vm.currentPath });
				instance.setDisplayInfo({ subtitle: vm.locationText || '' });
				// The sidebar lists the providers whose files bundle the user
				// allowed; a grant or a revocation made in Preferences (or by an
				// app's grant popup) is announced on this channel.
				try {
					vm._connectionsChannel = new BroadcastChannel('webtop-connections');
					vm._connectionsChannel.onmessage = () => { vm.loadCloudStorages(); };
				} catch (_) { /* no BroadcastChannel */ }

				// Suppress server sync during initialization
				vm._suppressServerSync = true;

				// Load persisted user settings from IndexedDB
				await vm.loadPersistedNavData();
				await vm.loadDetailPanelState();
				await vm.loadSidebarPanelState();
				await vm.loadListViewState();
				await vm.loadSmartFolders();
				await vm.loadServerPreferences();

				// apply theme
				const theme = vm.instance.api.theme.currentTheme || 'light';
				document.documentElement.dataset.theme = theme;

				// snapshot the effective Localization preference
				refreshLocalization(vm.localization, vm.instance);

				// --- Readiness gate ---
				// Load every component template BEFORE the gated markup is
				// compiled, so each <wt-*> element finds its <template> on the
				// single connectedCallback it gets. The inspector template is
				// loaded here too: <wt-inspector> sits behind detailPanelVisible,
				// which loadDetailPanelState() above may already have set.
				try {
					await Promise.all([initUi(), loadInspectorTemplate()]);
				} catch (e) {
					console.warn('[ContentBrowser] Failed to load component templates:', e);
				}

				// Opening the gate builds the screen. The panel state, theme and
				// localization resolved above are already in place, so it appears
				// fully formed.
				vm.isReady = true;

				// Use initialPath from launch options if provided (e.g. opened for reference browsing)
				if (options?.initialPath) {
					vm.currentPath = options.initialPath;
				}

				// Load available schemas (non-blocking)
				vm.loadAvailableSchemas();
				// The cloud storage section of the sidebar (non-blocking)
				vm.loadCloudStorages();

				await vm.load(vm.currentPath);

				// `select` names an item to open the browser on: its folder is shown
				// (whatever initialPath says) with the item selected.
				if (options?.select) {
					await vm.onInspectorRevealItem({ path: options.select });
				}

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
			vm._cancelFolderSizes();
			if (vm._connectionsChannel) {
				try { vm._connectionsChannel.close(); } catch (_) { /* noop */ }
				vm._connectionsChannel = null;
			}
		},
		/**
		 * Fills the list with a location: a repository folder, or a cloud
		 * location (lib/cloud-location.ts). `options.crumbs` carries the names
		 * above a cloud folder from the listing it was clicked in, so no
		 * request is spent on them; without it (a favourite, a restored
		 * session, the history) they are looked up after the list is shown.
		 */
		async load(path: string, skipStack: boolean = false, options: { crumbs?: CloudCrumbs | null } = {}) {
			const vm = this;
			vm.errorMessage = '';
			vm.loadError = '';
			vm.currentFolderDeleted = false;
			vm.cloudCursor = null;
			vm.cloudHasMore = false;
			vm.cloudLoadingMore = false;
			vm._cloudSeq++;
			if (!isCloudPath(path)) {
				vm.cloudCrumbs = null;
				vm.lastRepositoryPath = path;
			}
			// This fills the list with a folder's children, so whatever else it
			// held, it is no longer showing search results. xpathSearchActive says
			// exactly that about the list, and everything keyed off it — the search
			// bar, the folder-targeted menu entries — is wrong the moment it
			// outlives the results it describes.
			vm.xpathSearchActive = false;
			vm.xpathSearchTotalCount = 0;
			vm.xpathSearchError = '';
			// A search page still in flight must not land on this folder's list.
			vm._xpathSearchSeq++;
			vm.xpathSearchCursor = null;
			vm.xpathSearchHasMore = false;
			vm.xpathSearchLoadingMore = false;
			vm._facetReset();
			vm._cloudFacetReset();
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
			vm._cancelFolderSizes();
			vm.items = [];
			vm.gridMediaErrors = [];

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

			// Where a cloud load wants the list to go instead (the account was not
			// allowed, the folder is gone): followed once this load has settled.
			let redirect: string | null = null;
			try {
				if (isCloudPath(path)) {
					redirect = await vm._loadCloud(path, seq, options.crumbs ?? null);
				} else {
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
				// Sizes follow the list: it is shown first, and each folder's
				// total fills in as the server computes it.
				if (vm._navSeq === seq) {
					vm._requestFolderSizes(items.filter(i => i.isCollection).map(i => i.path));
				}
				}
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

			if (redirect && vm._navSeq === seq) {
				await vm.load(redirect, true);
				return;
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
			// A cloud listing is read when the user looks and after what the
			// user does to it; the providers send no events.
			if (isCloudPath(vm.currentPath)) return;

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
			vm._cancelFolderSizes();
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
		 * Ask the server for the total content size of the given folders in the
		 * list. The server computes them one at a time and streams each result
		 * over the contentLengths subscription; results are applied to the list in
		 * small batches so a large folder listing does not re-render per row.
		 */
		_requestFolderSizes(paths: string[]) {
			const vm = this;
			const eventHub = vm.instance?.api?.eventHub;
			if (!eventHub || paths.length === 0) return;

			const seq = vm._folderSizeSeq;
			const requested = new Set(paths);
			for (const item of vm.items as ContentItem[]) {
				if (item.isCollection && requested.has(item.path)) {
					item.folderSize = 'pending';
				}
			}

			const received = new Map<string, FolderSizeState>();
			let flushTimer: number | null = null;
			const flush = () => {
				flushTimer = null;
				if (seq !== vm._folderSizeSeq) return;
				for (const item of vm.items as ContentItem[]) {
					const value = received.get(item.path);
					if (value !== undefined && item.isCollection) {
						item.folderSize = value;
					}
				}
				received.clear();
			};
			const accept = (path: string, value: FolderSizeState) => {
				if (seq !== vm._folderSizeSeq) return;
				received.set(path, value);
				if (flushTimer === null) {
					flushTimer = window.setTimeout(flush, FOLDER_SIZE_FLUSH_MS);
				}
			};

			for (let i = 0; i < paths.length; i += FOLDER_SIZE_BATCH) {
				const batch = paths.slice(i, i + FOLDER_SIZE_BATCH);
				const answered = new Set<string>();
				vm._folderSizeUnsubscribes.push(eventHub.watchContentLengths(
					batch,
					(event: ContentLengthEvent) => {
						answered.add(event.path);
						accept(event.path, { size: event.size, fileCount: event.fileCount });
					},
					() => {
						// The server ended the request (done, or refused when busy):
						// whatever it did not answer stays unknown rather than pending.
						for (const path of batch) {
							if (!answered.has(path)) {
								accept(path, { size: null, fileCount: null });
							}
						}
					},
				));
			}
		},
		/** Stop the folder size requests of the current list and ignore their late results. */
		_cancelFolderSizes() {
			const vm = this;
			vm._folderSizeSeq++;
			const unsubscribes = vm._folderSizeUnsubscribes;
			vm._folderSizeUnsubscribes = [];
			for (const unsubscribe of unsubscribes) {
				try { unsubscribe(); } catch { /* noop */ }
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
			// Folders added or replaced below; a replaced item has lost its size.
			const changedFolderPaths: string[] = [];

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
						const parentPath = parentPathOf(path);
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
								// Update existing item in-place. Its picture may have
								// changed (a thumbnail made, content replaced), so a
								// tile that had given up on it tries again.
								vm.items.splice(existingIdx, 1, item);
								vm.gridMediaErrors = vm.gridMediaErrors.filter((id: string) => id !== item.id);
							} else {
								// New item — add and re-sort
								vm.items.push(item);
								vm.sortItems(vm.sortColumn, vm.sortDirection);
							}
							flashIds.push(item.id);
							if (item.isCollection) {
								changedFolderPaths.push(item.path);
							}
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

			if (changedFolderPaths.length > 0) {
				vm._requestFolderSizes(changedFolderPaths);
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
			if (item.cloud) {
				await this.openCloudItem(item);
				return;
			}
			if (item.isCollection) {
				await this.load(item.path);
				return;
			}
			// A reference to a cloud file opens where the provider shows it.
			if (item.mimeType === CLOUD_LINK_MIME_TYPE) {
				await this.openCloudLink(item);
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
		// ---- Cloud files: the user's Google Drive / OneDrive ----------------
		//
		// The toolbar's cloud menu offers, per provider an administrator
		// configured, importing files, importing references to them, and
		// exporting the selected files. What the user has not yet allowed is
		// asked for at that moment (connections.ensure opens the grant popup);
		// the picker then chooses the files or the destination folder, and the
		// copies run on the server in the user's own session.
		// ---- Cloud storage as a location -------------------------------------
		//
		// A cloud location (lib/cloud-location.ts) is listed through the cloud
		// source (sources/cloud-source.ts): the provider's root offers the own
		// drive and the shared drives, a drive is read a page at a time as the
		// list is scrolled. What the user does to it (new folder, rename,
		// delete) goes through the same source and the list is read again
		// afterwards; the providers send no events. The sidebar lists the
		// providers whose files bundle the user allowed.
		cloudProviderLabel(provider: string): string {
			if (provider === 'google') return this.t('app.content-browser.cloud.providerGoogle', undefined, 'Google Drive');
			if (provider === 'microsoft') return this.t('app.content-browser.cloud.providerMicrosoft', undefined, 'OneDrive');
			const storage = (this.cloudStorages as CloudStorage[]).find((s: CloudStorage) => s.provider === provider);
			return storage?.name || provider;
		},
		/** The providers an administrator enabled, and whether the user allowed each. */
		async loadCloudStorages() {
			const vm = this;
			const cloud = vm.instance?.api?.cloud;
			if (!cloud) return;
			try {
				vm.cloudStorages = await cloud.getStorages();
			} catch (e) {
				// Not reachable: the section stays as it was.
				console.warn('[ContentBrowser] cloud storages:', e);
			}
		},
		/** A provider's drives, from the sidebar. */
		async openCloudStorage(storage: CloudStorage) {
			await this.navigateToPath(cloudProviderPath(storage.provider));
		},
		/** The workspace's web content, from the sidebar's Places. */
		async openWorkspace() {
			await this.navigateToPath(WORKSPACE_PLACE_PATH);
		},
		isCloudStorageActive(storage: CloudStorage): boolean {
			return this.cloudLocation?.provider === storage.provider;
		},
		/** Whether a cloud path can be opened now: its provider's files bundle is allowed. */
		isCloudPathGranted(path: string): boolean {
			const provider = parseCloudPath(path)?.provider;
			if (!provider) return true;
			return (this.cloudStorages as CloudStorage[]).some((s: CloudStorage) => s.provider === provider && s.granted);
		},
		/** The order a cloud listing is asked for: the two the providers can produce. */
		_cloudOrder(): CloudOrder {
			return this.sortColumn === 'date' ? 'MODIFIED_DESC' : 'NAME';
		},
		_stampDates(items: ContentItem[]): ContentItem[] {
			for (const item of items) {
				item.attributes.displayDate = this.displayDate(item);
			}
			return items;
		},
		/**
		 * Lists a cloud location into `items`. Returns a path to go to instead
		 * when the location cannot be shown: the location itself once the user
		 * allowed the account, the repository when they did not, the listing
		 * above when the folder is gone.
		 */
		async _loadCloud(path: string, seq: number, crumbs: CloudCrumbs | null): Promise<string | null> {
			const vm = this;
			const source = vm._cloudSource as CloudSource | null;
			const location = parseCloudPath(path);
			if (!source || !location) {
				throw new Error(vm.t('app.content-browser.error.fetchFailed', undefined, 'Failed to fetch data'));
			}
			const cloudSeq = vm._cloudSeq;
			try {
				if (location.virtual === 'drives') {
					const listing = source.listDrivesRoot(location);
					vm.cloudCrumbs = listing.crumbs;
					vm.items = vm._stampDates(listing.items);
					return null;
				}
				if (location.virtual === 'shared') {
					vm.cloudCrumbs = source.virtualCrumbs(location);
					const listing = await source.listSharedDrives(location);
					if (vm._navSeq !== seq) return null;
					vm.items = vm._stampDates(listing.items);
					return null;
				}
				// A drive's root or a folder. The crumbs come from the listing the
				// folder was clicked in; otherwise the list is shown with the names
				// pending and they are looked up afterwards.
				const carried = crumbs && crumbs.provider === location.provider && crumbs.drive?.id === location.driveId
					&& (crumbs.current?.id ?? null) === location.id && !crumbs.pending;
				let known: CloudCrumbs;
				if (carried) {
					known = crumbs as CloudCrumbs;
				} else if (location.driveId === CLOUD_MY_DRIVE) {
					known = source.crumbsOf(location, source.myDrive(location.provider), [], null, !!location.id);
				} else {
					known = { provider: location.provider, providerName: vm.cloudProviderLabel(location.provider), drive: null, virtual: null, ancestors: [], current: null, pending: true };
				}
				vm.cloudCrumbs = known;
				// The listing comes in the provider's order: by name, or newest
				// first; the header's arrow says so (a search may have turned it).
				vm.sortDirection = vm.sortColumn === 'date' ? 'desc' : 'asc';
				const listing = await source.listFolder(location, known, null, vm._cloudOrder(), CLOUD_PAGE_SIZE);
				if (vm._navSeq !== seq) return null;
				vm.items = vm._stampDates(listing.items);
				vm.cloudCursor = listing.nextCursor;
				vm.cloudHasMore = !!listing.nextCursor;
				vm._cloudConsentRetry = '';
				if (known.pending) {
					vm._resolveCloudCrumbs(location, cloudSeq);
				}
				vm._cloudFillViewport();
				return null;
			} catch (error: any) {
				if (vm._navSeq !== seq) return null;
				if (isConsentRequired(error)) {
					// The account is not linked, or the files bundle not allowed:
					// asked once; declined, the repository is shown instead.
					let allowed = false;
					if (vm._cloudConsentRetry !== path) {
						try {
							allowed = await vm.instance.api.connections.ensure(location.provider, 'files');
						} catch (_) { /* popup blocked: as declined */ }
					}
					vm.loadCloudStorages();
					if (allowed) {
						vm._cloudConsentRetry = path;
						return path;
					}
					vm._cloudConsentRetry = '';
					vm.errorMessage = vm.t('app.content-browser.cloud.consentDeclined', undefined, 'The account was not allowed, so the repository is shown instead.');
					return '/content';
				}
				if (error instanceof GraphQLError && error.code === 'NOT_FOUND') {
					// Gone (deleted, or the user is no longer a member): the listing above.
					vm.errorMessage = vm.t('app.content-browser.cloud.gone', { name: vm.cloudProviderLabel(location.provider) }, 'This folder is no longer available on {name}.');
					return source.parentPathOf(location, crumbs) || '/content';
				}
				throw error;
			}
		},
		/** The names above a folder nobody carried: looked up after the list is shown. */
		async _resolveCloudCrumbs(location: CloudLocation, cloudSeq: number) {
			const vm = this;
			const source = vm._cloudSource as CloudSource | null;
			if (!source) return;
			let resolved: CloudCrumbs | null = null;
			try {
				resolved = await source.folderCrumbs(location);
			} catch (e) {
				console.warn('[ContentBrowser] cloud crumbs:', e);
			}
			if (vm._cloudSeq !== cloudSeq) return;
			const current = vm.cloudCrumbs as CloudCrumbs | null;
			if (resolved) {
				vm.cloudCrumbs = resolved;
			} else if (current) {
				vm.cloudCrumbs = { ...current, pending: false };
			}
			// The items carry the crumbs of their listing, and their location as names, too.
			for (const item of vm.items as ContentItem[]) {
				if (item.cloud) {
					item.cloud.crumbs = vm.cloudCrumbs as CloudCrumbs;
					item.cloud.location = source.locationTextOf(item.cloud.crumbs, item);
				}
			}
		},
		/** The next page of the cloud listing, as the list is scrolled to its end. */
		async cloudLoadMore() {
			const vm = this;
			const source = vm._cloudSource as CloudSource | null;
			const location = vm.cloudLocation as CloudLocation | null;
			const crumbs = vm.cloudCrumbs as CloudCrumbs | null;
			if (!source || !location || !crumbs || !vm.cloudHasMore || vm.cloudLoadingMore || vm.isNavigating) return;
			const cloudSeq = vm._cloudSeq;
			vm.cloudLoadingMore = true;
			try {
				const listing = await source.listFolder(location, crumbs, vm.cloudCursor, vm._cloudOrder(), CLOUD_PAGE_SIZE);
				if (vm._cloudSeq !== cloudSeq) return;
				const known = new Set((vm.items as ContentItem[]).map(i => i.id));
				vm.items = (vm.items as ContentItem[]).concat(vm._stampDates(listing.items.filter(i => !known.has(i.id))));
				vm.cloudHasMore = !!listing.nextCursor && listing.nextCursor !== vm.cloudCursor;
				vm.cloudCursor = listing.nextCursor;
			} catch (error: any) {
				if (vm._cloudSeq === cloudSeq) {
					vm.errorMessage = error?.message || String(error);
					vm.cloudHasMore = false;
				}
			} finally {
				if (vm._cloudSeq === cloudSeq) {
					vm.cloudLoadingMore = false;
					vm._cloudFillViewport();
				}
			}
		},
		_cloudFillViewport() {
			const vm = this;
			vm.$nextTick(() => {
				if (!vm.cloudHasMore) return;
				const el = vm.$refs.contentList as HTMLElement | undefined;
				if (el && el.scrollHeight <= el.clientHeight + 1) {
					vm.cloudLoadMore();
				}
			});
		},
		/** The breadcrumbs of a cloud location: provider › drive kind [› drive] › folders. */
		cloudBreadcrumb(): { name: string; path: string }[] {
			const location = this.cloudLocation as CloudLocation | null;
			if (!location) return [];
			const p = location.provider;
			const labels = this.cloudLabels as CloudLabels;
			const crumbs = this.cloudCrumbs as CloudCrumbs | null;
			const out: { name: string; path: string }[] = [{ name: labels.providerName(p), path: cloudProviderPath(p) }];
			if (location.virtual === 'drives') return out;
			const shared = location.virtual === 'shared' || (!!location.driveId && location.driveId !== CLOUD_MY_DRIVE);
			if (shared) {
				out.push({ name: labels.sharedDrives(p), path: cloudProviderPath(p) + 'shared/' });
			}
			if (location.virtual === 'shared') return out;
			const driveId = location.driveId as string;
			out.push({
				name: shared ? (crumbs?.drive?.name || '…') : labels.myDrive(p),
				path: cloudPath(p, driveId),
			});
			if (!location.id) return out;
			if (crumbs && !crumbs.pending) {
				for (const a of crumbs.ancestors) {
					out.push({ name: a.name, path: cloudPath(p, driveId, a.id) });
				}
			} else {
				out.push({ name: '…', path: cloudPath(p, driveId) });
			}
			out.push({ name: crumbs?.current?.name || '…', path: this.currentPath });
			return out;
		},
		/** The listing above the current cloud location, or null at the provider's root. */
		cloudParentPath(): string | null {
			const source = this._cloudSource as CloudSource | null;
			const location = this.cloudLocation as CloudLocation | null;
			if (!source || !location) return null;
			return source.parentPathOf(location, this.cloudCrumbs as CloudCrumbs | null);
		},
		/** The crumbs a folder row's listing would have: for the click that opens it. */
		cloudCrumbsFor(item: ContentItem): CloudCrumbs | null {
			const source = this._cloudSource as CloudSource | null;
			const info = item.cloud;
			if (!source || !info) return null;
			if (info.virtual === 'drive' && info.drive) {
				return source.crumbsOf(parseCloudPath(item.path) as CloudLocation, info.drive, [], null, false);
			}
			if (info.virtual) return null;
			if (!item.isCollection || !info.id) return null;
			// A search result's crumbs are its folder's, once looked up; until
			// then (or when the provider named only the folders) the folder is
			// opened cold and looked up there.
			if (info.search && !info.search.folderCrumbs) return null;
			const entry: CloudEntry = {
				provider: info.provider, driveId: info.driveId, id: info.id, name: item.name, mimeType: item.mimeType || null,
				size: null, folder: true, webUrl: info.webUrl, modified: item.lastModified ? item.lastModified.toISOString() : null,
				created: item.created ? item.created.toISOString() : null, nativeType: null, parentId: info.parentId, etag: info.etag,
				owner: info.owner, shared: info.shared, hasThumbnail: false, path: null, contentUrl: info.contentUrl || '',
			};
			return source.crumbsBelow(info.search?.folderCrumbs || info.crumbs, entry);
		},
		/** The folder a search result is in: listed, with its names when they were looked up. */
		async openCloudItemFolder(item: ContentItem) {
			const hit = item.cloud?.search;
			if (!hit || !hit.folderPath || hit.pending) return;
			await this.load(hit.folderPath, false, { crumbs: hit.folderCrumbs });
		},
		/** A cloud row: a folder (or drive) is listed, a file opens where the provider shows it. */
		async openCloudItem(item: ContentItem) {
			const info = item.cloud;
			if (!info) return;
			if (item.isCollection) {
				await this.load(item.path, false, { crumbs: this.cloudCrumbsFor(item) });
				return;
			}
			if (!info.id) return;
			// A Google document and an Office file are the provider's to edit.
			if (opensAtProvider({ mimeType: item.mimeType, nativeType: info.nativeType })) {
				this.openAtProvider(item);
				return;
			}
			// An editor that opens files through a file handle (lib/file-handle.ts)
			// takes the cloud file as a repository file: the shell hands it `file`.
			const editorApp = this.findEditorForMimeType(item.mimeType);
			if (editorApp && editorApp.fileHandles) {
				window.parent.postMessage({
					type: 'open-file-with-app',
					appId: editorApp.id,
					filePath: item.path,
					mimeType: item.mimeType,
					file: this.cloudFileRef(item),
				}, window.location.origin);
				return;
			}
			// No app of the Webtop opens it: Google shows the file itself; a
			// OneDrive file's bytes the browser shows (a PDF, a picture) or downloads.
			const url = info.provider === 'google' ? (info.webUrl || info.contentUrl) : (info.contentUrl || info.webUrl);
			if (url) {
				window.open(url, '_blank', 'noopener');
			}
		},
		/** How a cloud file is named to an editor (a FileRef of lib/file-handle.ts): enough to open it without another request. */
		cloudFileRef(item: ContentItem): FileRef {
			const info = item.cloud as CloudItemInfo;
			return {
				kind: 'cloud',
				location: item.path,
				name: item.name,
				mimeType: item.mimeType,
				size: item.contentLength,
				provider: info.provider,
				driveId: info.driveId,
				id: info.id,
				etag: info.etag,
				webUrl: info.webUrl,
				downloadURL: info.contentUrl,
			};
		},
		/** The location of a cloud item as names, for the clipboard and the favourites. */
		cloudItemLocationText(item: ContentItem): string {
			return item.cloud ? item.cloud.location : item.path;
		},
		/** The link a cloud item is passed on by: where the provider shows it. */
		async copyCloudLink(item: ContentItem) {
			const url = item.cloud?.webUrl;
			if (!url) {
				this.showCloudMessage(item.name, [this.t('app.content-browser.cloud.noLink', undefined, 'This item has no link to copy.')]);
				return;
			}
			await this.copyToClipboard(url);
		},
		openAtProvider(item: ContentItem) {
			const url = item.cloud?.webUrl;
			if (url) window.open(url, '_blank', 'noopener');
		},
		/** "Copy to repository…" / "Put a link in the repository…": the folder is picked first. */
		startCopyToRepository(items: ContentItem[], asLink: boolean) {
			const cloudItems = items.filter(i => i.cloud && !i.cloud.virtual && i.cloud.id);
			if (!cloudItems.length) return;
			this.folderPicker = { visible: true, action: asLink ? 'link' : 'import', items: cloudItems };
		},
		async onFolderPicked(path: string) {
			const vm = this;
			const picker = vm.folderPicker;
			const items = picker.items.slice();
			const asLink = picker.action === 'link';
			vm.folderPicker = { visible: false, action: '', items: [] };
			if (!path || !items.length) return;
			await vm.importFromCloud(items, path, asLink);
		},
		/** "Copy to cloud storage…": the provider (when several), then the folder. */
		async startCopyToCloud(items: ContentItem[], event?: MouseEvent) {
			const vm = this;
			const repositoryItems = items.filter(i => !i.cloud);
			if (!repositoryItems.length || !vm.instance) return;
			const storages = vm.cloudStorages as CloudStorage[];
			if (!storages.length) return;
			let provider = storages.length === 1 ? storages[0].provider : '';
			if (!provider) {
				const at = event ? { x: event.clientX, y: event.clientY } : (vm._contextMenuAt || { x: 0, y: 0 });
				const anchor = { left: at.x, top: at.y, right: at.x, bottom: at.y, width: 0, height: 0 };
				const handle = vm.instance.popup.open({
					anchor,
					placement: 'bottom-start',
					minWidth: 200,
					items: storages.map((s: CloudStorage) => ({ id: s.provider, label: vm.cloudProviderLabel(s.provider), icon: s.provider === 'google' ? 'bi bi-google' : 'bi bi-microsoft' })),
				});
				const result = await handle.result;
				if (result == null) return;
				provider = String(result);
			}
			let allowed = false;
			try {
				allowed = await vm.instance.api.connections.ensure(provider, 'files');
			} catch (e: any) {
				const blocked = e?.message === 'popup_blocked';
				vm.showCloudMessage(vm.cloudProviderLabel(provider), [blocked
					? vm.t('app.content-browser.cloud.popupBlocked', undefined, 'The browser blocked the sign-in window. Allow popups for this site and try again.')
					: (e?.message || String(e))]);
				return;
			}
			vm.loadCloudStorages();
			if (!allowed) return;
			vm.cloudPicker = {
				visible: true,
				provider,
				providerName: vm.cloudProviderLabel(provider),
				mode: 'folder',
				multiple: false,
				action: 'copy',
			};
			vm._cloudCopyItems = repositoryItems;
		},
		async onCloudPicked(entries: { provider: string; driveId: string | null; id: string | null; name: string }[]) {
			const vm = this;
			const picker = vm.cloudPicker;
			if (!entries || !entries.length || picker.action !== 'copy') return;
			const items = (vm._cloudCopyItems as ContentItem[] | null) || [];
			vm._cloudCopyItems = null;
			await vm.exportToCloud(picker.provider, picker.providerName, entries[0], items);
		},
		/** Copies cloud items (or writes references to them) into a repository folder. */
		async importFromCloud(items: ContentItem[], folder: string, asLink: boolean) {
			const vm = this;
			const cloud = vm.instance?.api.cloud;
			if (!cloud) return;
			vm.errorMessage = '';
			vm.uploadMonitor = {
				isCanceled: false,
				target: { currentFile: '', progressPercent: 0 },
				cancel() { this.isCanceled = true; },
			};
			const errors: string[] = [];
			let copied = 0;
			try {
				let done = 0;
				for (const item of items) {
					const info = item.cloud;
					if (vm.uploadMonitor?.isCanceled || !info || !info.id) break;
					vm.uploadMonitor.target.currentFile = item.name;
					vm.uploadMonitor.target.progressPercent = Math.round((done / items.length) * 100);
					try {
						if (asLink) {
							await cloud.createLink(info.provider, info.id, folder, { driveId: info.driveId });
						} else {
							await cloud.importFile(info.provider, info.id, folder, { driveId: info.driveId });
						}
						copied++;
					} catch (e: any) {
						errors.push(`${item.name}: ${e?.message || String(e)}`);
					}
					done++;
				}
				if (vm.uploadMonitor) vm.uploadMonitor.target.progressPercent = 100;
				if (folder === vm.currentPath) {
					await vm.load(vm.currentPath, true);
				}
			} finally {
				vm.uploadMonitor = null;
			}
			if (errors.length) {
				vm.showCloudMessage(vm.t('app.content-browser.cloud.copyToRepositoryTitle', undefined, 'Copy to repository'),
					[vm.t('app.content-browser.cloud.copyFailed', { n: errors.length, error: errors[0] }, '{n} item(s) could not be copied: {error}')]);
			} else if (copied && folder !== vm.currentPath) {
				vm.showCloudMessage(vm.t('app.content-browser.cloud.copiedTo', { folder }, 'Copied to {folder}'), []);
			}
		},
		/** Uploads repository items (files and folders) to the picked folder of a drive. */
		async exportToCloud(provider: string, providerName: string, folder: { driveId: string | null; id: string | null; name: string }, items: ContentItem[]) {
			const vm = this;
			const cloud = vm.instance?.api.cloud;
			if (!cloud || !items.length) return;
			vm.errorMessage = '';
			vm.uploadMonitor = {
				isCanceled: false,
				target: { currentFile: '', progressPercent: 0 },
				cancel() { this.isCanceled = true; },
			};
			const errors: string[] = [];
			try {
				let done = 0;
				for (const item of items) {
					if (vm.uploadMonitor?.isCanceled) break;
					vm.uploadMonitor.target.currentFile = item.name;
					vm.uploadMonitor.target.progressPercent = Math.round((done / items.length) * 100);
					try {
						const entry = await cloud.exportFile(provider, item.path, { driveId: folder.driveId || CLOUD_MY_DRIVE, folderId: folder.id });
						(vm._cloudSource as CloudSource | null)?.notePut(entry, { provider, driveId: folder.driveId, id: folder.id });
					} catch (e: any) {
						errors.push(`${item.name}: ${e?.message || String(e)}`);
					}
					done++;
				}
			} finally {
				vm.uploadMonitor = null;
			}
			// Done quietly, like an upload; only what failed is told.
			if (errors.length) {
				vm.showCloudMessage(vm.t('app.content-browser.cloud.copyTitle', { name: providerName }, 'Copy to {name}'),
					[vm.t('app.content-browser.cloud.copyFailed', { n: errors.length, error: errors[0] }, '{n} item(s) could not be copied: {error}')]);
			}
		},
		showCloudMessage(title: string, errors: string[]) {
			this.cloudResult = { visible: true, title, errors };
		},
		closeCloudResult() {
			this.cloudResult.visible = false;
		},
		/** Follows a cloud-link file to where the provider shows the file. */
		async openCloudLink(item: any) {
			const vm = this;
			try {
				if (!item.downloadURL) throw new Error(vm.t('app.content-browser.cloud.linkUnreadable', undefined, 'The link could not be read.'));
				const response = await fetch(item.downloadURL, { credentials: 'same-origin', cache: 'no-store' });
				if (!response.ok) throw new Error(`HTTP ${response.status}`);
				const link = await response.json() as CloudLink;
				if (!link.webUrl) throw new Error(vm.t('app.content-browser.cloud.linkNoUrl', undefined, 'The link has no address to open.'));
				window.open(link.webUrl, '_blank', 'noopener');
			} catch (e: any) {
				vm.showCloudMessage(item.name, [e?.message || String(e)]);
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
			if (isCloudPath(path)) {
				// A cloud item's folder: the crumbs it carries name it.
				const info = (target as ContentItem).cloud;
				if (!info || info.virtual) return;
				const parent = cloudPath(info.provider, info.driveId, info.crumbs?.current?.id ?? info.parentId ?? null);
				if (parent !== this.currentPath) {
					await this.load(parent, false, { crumbs: info.crumbs?.current ? info.crumbs : null });
				}
				const found = (this.items as any[]).find((i: any) => i.path === path);
				if (found) {
					this.selectedItems = [found.id];
					this.selectionAnchorID = found.id;
					this.focusedItemID = found.id;
				}
				return;
			}
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
			this.selectionAnchorID = item.id;
			this.focusedItemID = item.id;
			this.$nextTick(() => {
				const row = document.querySelector('.content-list .item.item-selected');
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
		// A folder's total content size, or '' while it is pending or unknown.
		displayFolderSize(item: ContentItem): string {
			const state = item.folderSize;
			if (!state || state === 'pending' || state.size === null) return '';
			return this.instance.util.bytes.format(state.size, { short: true });
		},
		_folderSizeBytes(item: ContentItem): number {
			const state = item.folderSize;
			return (state && state !== 'pending' && state.size !== null) ? state.size : -1;
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
			// The rows above a drive's folders: a drive, or the shared drives entry.
			if (item?.cloud?.virtual === 'drive') return 'bi bi-hdd';
			if (item?.cloud?.virtual === 'shared') return 'bi bi-people';
			return getFileIconUtil(item);
		},
		// The hex of a file's color (a swatch key of the shared palette).
		colorHex(key: string): string {
			return SWATCH_COLOR_MAP[key] || 'transparent';
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

			// A cloud listing is ordered by the provider, a page at a time: the
			// name or the date re-reads it in that order (the date newest first,
			// the only order the providers share); any other column is left as
			// it is, since the pages still to come could not follow it.
			if (this.isCloudLocation) {
				if (!(this.cloudSortableColumns as string[]).includes(column)) {
					this.sortColumn = 'name';
					this.sortDirection = 'asc';
					return;
				}
				// A search result has no order the provider could repeat page
				// by page (words are ranked), so it is sorted here, over the
				// pages loaded, by either column in either direction.
				if (!this.xpathSearchActive) {
					this.sortDirection = column === 'date' ? 'desc' : 'asc';
					this.load(this.currentPath, true, { crumbs: this.cloudCrumbs });
					return;
				}
			}
			// A search result is ordered by the server, one page at a time, so a
			// column the index sorts by re-runs the search in the new order (a
			// header click) or leaves the loaded pages as they came (a re-sort
			// after a list update). Only a column the index cannot sort by is
			// sorted here, over what has been loaded.
			if (this.xpathSearchActive && !this.isCloudLocation && SEARCH_SORT_FIELDS[column]) {
				if (!direction) {
					this.xpathExecuteSearch();
				}
				return;
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
					// Folders sort by their total once it has arrived; one still
					// unknown sorts as the smallest.
					av = a.isCollection ? this._folderSizeBytes(a) : (a.contentLength ?? 0);
					bv = b.isCollection ? this._folderSizeBytes(b) : (b.contentLength ?? 0);
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
		// Center pane: accept internal drags, mail attachments, save-as chips, and
		// OS file uploads.
		onDragOver(event: DragEvent) {
			if (!event.dataTransfer) return;
			event.dataTransfer.dropEffect = this._dropEffectFor(event, this.currentPath);
		},
		/**
		 * The cursor for a drag over a location (the folder being browsed, or a
		 * folder row): an attachment, a save chip and files from the PC copy in
		 * anywhere but the listings above the drives; the browser's own items
		 * follow the drop plan.
		 */
		_dropEffectFor(event: DragEvent, destPath: string): 'none' | 'copy' | 'move' {
			const virtual = !!parseCloudPath(destPath)?.virtual;
			if (this._hasMailAttachmentDrag(event)) {
				return virtual ? 'none' : 'copy';
			}
			if (this._hasInternalDragData(event)) {
				const parentData = (() => { try { return (window.parent as any).__webtopDragData; } catch (_) { return null; } })();
				const items = parentData?.items;
				if (!Array.isArray(items)) {
					// Same-frame drag data only: its items are this list's own.
					return virtual ? 'none' : (event.ctrlKey ? 'copy' : 'move');
				}
				return this._dropEffectOf(this._dropPlan(items, destPath, event.ctrlKey, parentData.sourceFolderPath), event.ctrlKey);
			}
			const types = event.dataTransfer ? Array.from(event.dataTransfer.types) : [];
			if (types.includes('application/x-webtop-save')) {
				return this._saveChipAllowedAt(destPath) ? 'copy' : 'none';
			}
			// OS file upload
			return virtual ? 'none' : 'copy';
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
			await this._dropAt(event, this.currentPath);
		},
		/**
		 * A drop on a location: the folder being browsed (the background) or a
		 * folder row. Mail attachments, the browser's own items, a save chip and
		 * files from the PC, in that order of recognition; each goes to the
		 * repository or to cloud storage as the location says.
		 */
		async _dropAt(event: DragEvent, destPath: string) {
			const vm = this;
			vm.errorMessage = '';
			if (parseCloudPath(destPath)?.virtual) return;

			// Attachments dragged out of the Mail app → copied into the folder
			const mailAttachments = vm._getMailAttachmentDrag(event);
			if (mailAttachments) {
				await vm.saveMailAttachments(mailAttachments, destPath);
				return;
			}

			// The browser's own items (this window's, or another's through the parent window)
			const internalItems = vm._getInternalDragItems(event);
			if (internalItems && internalItems.length > 0) {
				if (vm._isDragForbidden(event, destPath)) return;
				await vm._executeDrop(internalItems, destPath, event.ctrlKey);
				return;
			}

			// A save chip from an editor
			const saveData = event.dataTransfer?.getData('application/x-webtop-save');
			if (saveData) {
				if (!vm._saveChipAllowedAt(destPath)) return;
				try {
					const { name, mimeType, content, saveAsToken, convert } = JSON.parse(saveData);
					await vm.saveDroppedContent(name, mimeType, content, saveAsToken, destPath, convert);
					if (destPath === vm.currentPath) {
						await vm.load(vm.currentPath, true, { crumbs: vm.cloudCrumbs });
					}
				} catch (error: any) {
					vm.errorMessage = error?.message || vm.t('app.content-browser.error.saveFailed', undefined, 'Save failed');
				} finally {
					// The chip's drag data is the source's to clear on dragend; a
					// source gone by then (its dialog closed) leaves it, so it goes here too.
					try { if ((window.parent as any).__webtopDragData?.save) (window.parent as any).__webtopDragData = null; } catch (_) { /* cross-origin safety */ }
				}
				return;
			}

			// Files from the PC: the entries keep folders; a browser without
			// them (or a synthetic drop) hands over flat files.
			const itemList = event.dataTransfer?.items;
			const entries = itemList ? Array.from(itemList).map(i => (i as any).webkitGetAsEntry?.()).filter(e => e) : [];
			const flat = Array.from(event.dataTransfer?.files || []);
			if (entries.length === 0 && flat.length === 0) return;

			vm.uploadMonitor = {
				isCanceled: false,
				target: { currentFile: '', progressPercent: 0 },
				cancel() { this.isCanceled = true; },
			};
			try {
				const files: { file: File; path: string }[] = [];
				if (entries.length) {
					for (const entry of entries) {
						await vm.collectEntry(entry, '', files);
					}
				} else {
					for (const f of flat) files.push({ file: f, path: f.name });
				}
				if (isCloudPath(destPath)) {
					await vm.uploadToCloud(files, destPath);
				} else {
					// The repository upload writes below the folder being browsed; a
					// folder row is a sub-folder of it.
					const dir = destPath === vm.currentPath ? '' : destPath.substring(vm.currentPath.length).replace(/^\/+/, '');
					await vm.uploadFiles(dir ? files.map(f => ({ file: f.file, path: `${dir}/${f.path}` })) : files);
				}
				await vm.load(vm.currentPath, true, { crumbs: vm.cloudCrumbs });
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
				const parentPath = dir ? childPath(vm.currentPath, dir) : vm.currentPath;
				await vm.ensureFolder(parentPath);

				// Check if file already exists
				const destPath = childPath(vm.currentPath, info.path);
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
		/**
		 * Writes a save chip's content as a new file of a folder: the folder
		 * being browsed, or the folder row it was dropped on. In cloud storage
		 * the content goes to the drive (with `convert`, Google turns it into a
		 * native document), and the source app is told the cloud location.
		 */
		async saveDroppedContent(name: string, mimeType: string, content: string, saveAsToken?: string, folderPath?: string, convert?: string | null) {
			const vm = this;
			const contentService = vm.instance.api.content;
			const folder = folderPath || vm.currentPath;

			if (isCloudPath(folder)) {
				const location = parseCloudPath(folder) as CloudLocation;
				const cloud = vm.instance.api.cloud;
				const entry = await cloud.exportContent(location.provider, name, mimeType, content, {
					driveId: location.driveId, folderId: location.id, convert: !!convert,
				});
				(vm._cloudSource as CloudSource | null)?.notePut(entry, location);
				if (saveAsToken) {
					const channel = new BroadcastChannel('webtop-save-as');
					channel.postMessage({
						type: 'save-as-complete',
						path: cloudPath(entry.provider, entry.driveId, entry.id),
						name: entry.name,
						mimeType: entry.mimeType || mimeType,
						saveAsToken,
						cloud: { provider: entry.provider, driveId: entry.driveId, id: entry.id, etag: entry.etag, webUrl: entry.webUrl, contentUrl: entry.contentUrl },
					});
					channel.close();
				}
				return;
			}

			// Check if file already exists
			const destPath = childPath(folder, name);
			const existingNode = await contentService.getNode(destPath);
			if (existingNode) {
				// Set temporary uploadMonitor so conflict dialog can render
				vm.uploadMonitor = { isCanceled: false, target: { currentFile: name, progressPercent: 0 }, cancel() { this.isCanceled = true; } };
				const action = await vm.askConflict();
				vm.uploadMonitor = null;
				if (action === 'skip' || action === 'skipAll' || action === 'cancel') return;
			}

			// Multipart upload. The payload (e.g. a memo with embedded images)
			// can run to megabytes, so it goes up in chunks.
			const uploadInfo = await contentService.initiateMultipartUpload();
			const uploadId = uploadInfo.uploadId;
			try {
				await contentService.appendMultipartUploadData(uploadId, content);
				await contentService.completeMultipartUpload(
					uploadId, folder, name, mimeType, existingNode ? true : false
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
			const parent = parentPathOf(path);
			await vm.ensureFolder(parent);

			// Create this folder
			const folderName = path.substring(path.lastIndexOf('/') + 1);
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
			this.selectionAnchorID = '';
			this.focusedItemID = '';
		},
		// Position of an item among the rows the user can actually reach.
		// `filteredItems` is what the table renders, so ranges and Arrow-key
		// steps walk that list — a filter must not let a range swallow rows
		// that are off screen. Returns -1 for an id no longer on the list.
		visibleIndexOf(id: string): number {
			if (!id) return -1;
			return (this.filteredItems as any[]).findIndex((i: any) => i.id === id);
		},
		selectItem(item: any, event?: MouseEvent) {
			const e = event || (window.event as MouseEvent);
			this.applySelection(item, e?.shiftKey || false, e?.ctrlKey || e?.metaKey || false);
		},
		// Shared by mouse clicks and Arrow-key navigation. `extend` (Shift)
		// selects the range from the anchor to `item`; `toggle` (Ctrl/Cmd) adds
		// or removes a single row without dropping the rest, and combined with
		// `extend` adds the range to what is already selected.
		applySelection(item: any, extend: boolean, toggle: boolean) {
			const vm = this;
			const list = vm.filteredItems as any[];
			const index = list.findIndex((i: any) => i.id === item.id);
			if (index < 0) return;
			const anchor = vm.visibleIndexOf(vm.selectionAnchorID);

			if (extend && anchor >= 0) {
				// Range selection: rebuild it from scratch (unless Ctrl/Cmd is
				// also down) so shrinking a range with Shift+Arrow releases the
				// rows the cursor moved back past.
				const start = Math.min(anchor, index);
				const end = Math.max(anchor, index);
				if (!toggle) {
					vm.selectedItems = [];
				}
				for (let i = start; i <= end; i++) {
					const id = list[i].id;
					if (!vm.selectedItems.includes(id)) {
						vm.selectedItems.push(id);
					}
				}
				// The anchor deliberately stays put, so the next Shift+click or
				// Shift+Arrow grows or shrinks this same range.
			} else if (toggle) {
				// Ctrl/Cmd+click: toggle selection
				const idx = vm.selectedItems.indexOf(item.id);
				if (idx >= 0) {
					vm.selectedItems.splice(idx, 1);
				} else {
					vm.selectedItems.push(item.id);
				}
				vm.selectionAnchorID = item.id;
			} else {
				// Normal click: single selection
				vm.selectedItems = [item.id];
				vm.selectionAnchorID = item.id;
			}
			vm.focusedItemID = item.id;
			// The Inspector reacts to the resulting `inspectorTarget` change
			// via its own `target` watch; no host action needed here.
		},
		// Arrow Up/Down: step the cursor through the visible rows. Holding
		// Shift grows or shrinks the range from the anchor, exactly as
		// Shift+click does; holding Ctrl moves only the cursor (see focusRow).
		// Stops at both ends rather than wrapping.
		moveSelection(delta: number, extend: boolean, focusOnly: boolean) {
			const vm = this;
			const list = vm.filteredItems as any[];
			if (list.length === 0) return;

			const current = vm.visibleIndexOf(vm.focusedItemID);
			let next: number;
			if (current < 0) {
				// Nothing focused yet, or the focused row was filtered away:
				// enter the list from the end the key points at, so a single
				// press always lands on a row.
				next = delta > 0 ? 0 : list.length - 1;
			} else {
				next = current + delta;
				if (next < 0 || next >= list.length) {
					// A single step past either end stays put; a row step in the
					// grid lands on the first / last tile instead.
					if (Math.abs(delta) === 1) return;
					next = delta > 0 ? list.length - 1 : 0;
					if (next === current) return;
				}
			}

			vm.focusRow(next, extend, focusOnly);
		},
		// Home / End: jump the cursor to the first / last visible row, with the
		// same Shift (extend) and Ctrl (cursor only) modifiers as the arrows.
		jumpSelection(edge: number, extend: boolean, focusOnly: boolean) {
			const vm = this;
			const list = vm.filteredItems as any[];
			if (list.length === 0) return;
			vm.focusRow(edge > 0 ? list.length - 1 : 0, extend, focusOnly);
		},
		// Land the keyboard cursor on a visible row. Ctrl (focusOnly) moves the
		// cursor without touching the selection or the anchor — Explorer-style
		// "walk with Ctrl, pick with Space". Shift wins over Ctrl when both are
		// held: the range from the anchor is added to the existing selection.
		focusRow(index: number, extend: boolean, focusOnly: boolean) {
			const vm = this;
			const item = (vm.filteredItems as any[])[index];
			if (!item) return;
			if (extend) {
				vm.applySelection(item, true, focusOnly);
			} else if (focusOnly) {
				vm.focusedItemID = item.id;
			} else {
				vm.applySelection(item, false, false);
			}
			vm.scrollFocusedIntoView();
		},
		// Space: add or remove the cursor row without touching the rest of the
		// selection — the picking half of Ctrl+Arrow navigation. The toggled
		// row becomes the anchor (via applySelection), as in Explorer.
		toggleFocused() {
			const vm = this;
			const index = vm.visibleIndexOf(vm.focusedItemID);
			if (index < 0) return;
			vm.applySelection((vm.filteredItems as any[])[index], false, true);
		},
		// Ctrl+A: select every visible row. The cursor stays where it is (or
		// enters at the top), and the anchor moves to the first row so a
		// following Shift+click shrinks from the top edge predictably.
		selectAllVisible() {
			const vm = this;
			const list = vm.filteredItems as any[];
			if (list.length === 0) return;
			vm.selectedItems = list.map((i: any) => i.id);
			vm.selectionAnchorID = list[0].id;
			if (vm.visibleIndexOf(vm.focusedItemID) < 0) {
				vm.focusedItemID = list[0].id;
			}
		},
		// Keep the cursor row on screen. Rows carry no id of their own, but the
		// list (the tbody, or the grid) renders `filteredItems` in order, so the
		// visible index is the row index.
		scrollFocusedIntoView() {
			const vm = this;
			const index = vm.visibleIndexOf(vm.focusedItemID);
			if (index < 0) return;
			vm.$nextTick(() => {
				const rows = document.querySelectorAll('.content-list .item');
				rows[index]?.scrollIntoView({ block: 'nearest' });
			});
		},
		// Drag selection methods
		onContentMouseDown(event: MouseEvent) {
			const vm = this;
			// Only start drag selection if clicking on empty area (not on a row or tile)
			const target = event.target as HTMLElement;
			const row = target.closest('.content-list .item');
			if (row) {
				// Clicked on a row, don't start drag selection
				return;
			}

			// Check if we're in the content-list area
			const contentList = target.closest('.content-list');
			if (!contentList) return;

			// The list's own scrollbars are inside its border box but outside
			// its client box, so a press on them reaches here with the list as
			// the target. Dragging a scrollbar must neither clear the selection
			// nor start a rubber band, so leave such presses to the browser.
			const rect = contentList.getBoundingClientRect();
			const x = event.clientX - rect.left;
			const y = event.clientY - rect.top;
			if (x >= contentList.clientLeft + contentList.clientWidth ||
				y >= contentList.clientTop + contentList.clientHeight) {
				return;
			}

			// Start drag selection
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

			// Find items that intersect with selection rectangle. The rows (or
			// the grid's tiles) render `filteredItems` in order, so the element
			// index is the index into that list.
			const rows = contentList.querySelectorAll('.item');
			const visible = vm.filteredItems as any[];
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
					const item = visible[index];
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
			vm._contextMenuAt = { x: event.clientX, y: event.clientY };

			// 未選択のアイテムを右クリックした場合、そのアイテムを単一選択
			if (!vm.selectedItems.includes(item.id)) {
				vm.selectedItems = [item.id];
				vm.selectionAnchorID = item.id;
				vm.focusedItemID = item.id;
			}

			// 選択されたアイテムの情報を取得
			const selectedItemObjects = vm.items.filter((i: any) => vm.selectedItems.includes(i.id));
			if (selectedItemObjects.some((i: any) => i.cloud)) {
				vm.openCloudContextMenu(selectedItemObjects, event);
				return;
			}
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
				// A copy to a drive the user allowed; with none allowed, the first
				// use asks for the grant.
				if ((vm.cloudStorages as CloudStorage[]).length > 0) {
					menuItems.push({ id: 'copy-to-cloud', label: vm.t('app.content-browser.menu.copyToCloud', undefined, 'Copy to cloud storage…'), icon: 'bi-cloud-upload' });
				}
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
			// Lock/Unlock options. A file is always locked deep, so its content
			// is covered; a folder is locked shallow (the folder itself) or, with
			// "Lock Including Contents", deep (everything beneath it too).
			if (hasUnlocked) {
				menuItems.push({ id: 'lock', label: vm.t('app.content-browser.menu.lock', undefined, 'Lock'), icon: 'bi-lock' });
			}
			if (selectedItemObjects.some((i: any) => i.isCollection && !i.isLocked)) {
				menuItems.push({ id: 'lock-deep', label: vm.t('app.content-browser.menu.lockDeep', undefined, 'Lock Including Contents'), icon: 'bi-lock-fill' });
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
		/**
		 * The menu of cloud rows: open, open at the provider, copy the link
		 * (the webUrl, the one thing another person can open), copy to the
		 * repository or put a link there (a folder is picked), rename, delete
		 * (to the provider's trash) and a new folder beside. A row of the
		 * listings above the drives opens, links and pins, nothing else.
		 */
		openCloudContextMenu(selected: ContentItem[], event: MouseEvent) {
			const vm = this;
			const single = selected.length === 1 ? selected[0] : null;
			const info = single?.cloud ?? null;
			const virtual = selected.some(i => i.cloud?.virtual);
			const providerName = vm.cloudProviderLabel((selected[0].cloud as CloudItemInfo).provider);
			const menuItems: { id: string; label: string; icon?: string; danger?: boolean; type?: string }[] = [];
			if (single && single.isCollection) {
				menuItems.push({ id: 'open', label: vm.t('app.content-browser.menu.open', undefined, 'Open'), icon: 'bi-folder2-open' });
			}
			if (single && info?.webUrl) {
				menuItems.push({ id: 'open-at-provider', label: vm.t('app.content-browser.menu.openAtProvider', { name: providerName }, 'Open at {name}'), icon: 'bi-box-arrow-up-right' });
				menuItems.push({ id: 'copy-link', label: vm.t('app.content-browser.menu.copyLink', undefined, 'Copy link'), icon: 'bi-link-45deg' });
			}
			if (virtual) {
				if (single && !vm.isPathPinned(single.path) && info?.virtual !== 'shared') {
					menuItems.push({ type: 'separator', id: '', label: '' });
					menuItems.push({ id: 'pin', label: vm.t('app.content-browser.menu.addFavorite', undefined, 'Add to favorites'), icon: 'bi-pin-angle' });
				}
			} else {
				if (single && !single.isCollection && single.downloadURL) {
					menuItems.push({ id: 'download', label: vm.t('app.content-browser.menu.download', undefined, 'Download'), icon: 'bi-download' });
				}
				menuItems.push({ type: 'separator', id: '', label: '' });
				menuItems.push({ id: 'copy-to-repository', label: vm.t('app.content-browser.menu.copyToRepository', undefined, 'Copy to repository…'), icon: 'bi-cloud-download' });
				menuItems.push({ id: 'link-in-repository', label: vm.t('app.content-browser.menu.linkInRepository', undefined, 'Put a link in the repository…'), icon: 'bi-link' });
				menuItems.push({ type: 'separator', id: '', label: '' });
				if (single) {
					menuItems.push({ id: 'rename', label: vm.t('app.content-browser.menu.rename', undefined, 'Rename'), icon: 'bi-pencil' });
				}
				if (single && single.isCollection && !vm.isPathPinned(single.path)) {
					menuItems.push({ id: 'pin', label: vm.t('app.content-browser.menu.addFavorite', undefined, 'Add to favorites'), icon: 'bi-pin-angle' });
				}
				menuItems.push({ id: 'new-folder', label: vm.t('app.content-browser.menu.newFolder', undefined, 'New Folder'), icon: 'bi-folder-plus' });
				menuItems.push({ type: 'separator', id: '', label: '' });
				menuItems.push({ id: 'delete', label: vm.t('app.content-browser.menu.delete', undefined, 'Delete'), icon: 'bi-trash', danger: true });
			}
			window.parent.postMessage({
				type: 'show-context-menu',
				x: event.clientX,
				y: event.clientY,
				items: menuItems,
				sourceAppId: vm.instance?.id,
			}, window.location.origin);
		},
		// Context menu for empty area (background)
		onBackgroundContextMenu(event: MouseEvent) {
			const vm = this;
			// Only handle if click was on empty area (not on an item row or tile)
			const target = event.target as HTMLElement;
			if (target.closest('.content-list .item')) {
				return;
			}
			event.preventDefault();

			// Clear selection when right-clicking on background
			vm.clearSelection();

			// A cloud folder offers a new folder; the listings above the drives
			// have nothing to make anything in. Uploads come in a later step.
			if (vm.isCloudLocation) {
				if (vm.isCloudVirtualLocation) return;
				window.parent.postMessage({
					type: 'show-context-menu',
					x: event.clientX,
					y: event.clientY,
					items: [{ id: 'new-folder', label: vm.t('app.content-browser.menu.newFolder', undefined, 'New Folder'), icon: 'bi-folder-plus' }],
					sourceAppId: vm.instance?.id,
				}, window.location.origin);
				return;
			}

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

			// The grid view's sort menu (openGridSortMenu): a column keeps the
			// current direction, a direction keeps the current column.
			if (action.startsWith('sort:')) {
				const choice = action.substring('sort:'.length);
				if (choice === 'asc' || choice === 'desc') {
					vm.applySort(vm.sortColumn, choice);
				} else if (choice) {
					vm.applySort(choice, vm.sortDirection);
				}
				return;
			}

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
					vm.lockItems(selectedItemObjects.filter((i: any) => !i.isLocked), false);
					break;
				case 'lock-deep':
					vm.lockItems(selectedItemObjects.filter((i: any) => !i.isLocked), true);
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
				// ---- cloud rows ----
				case 'open-at-provider':
					if (selectedItemObjects.length === 1) vm.openAtProvider(selectedItemObjects[0]);
					break;
				case 'copy-link':
					if (selectedItemObjects.length === 1) vm.copyCloudLink(selectedItemObjects[0]);
					break;
				case 'copy-to-repository':
					vm.startCopyToRepository(selectedItemObjects, false);
					break;
				case 'link-in-repository':
					vm.startCopyToRepository(selectedItemObjects, true);
					break;
				case 'copy-to-cloud':
					vm.startCopyToCloud(selectedItemObjects);
					break;
				case 'pin':
					if (selectedItemObjects.length === 1) vm.pinCloudItem(selectedItemObjects[0]);
					break;
			}
		},
		// Locks the items: a file always deep (its content is in jcr:content,
		// which a shallow lock would leave writable), a folder deep only when
		// `folderDeep` asks for its contents to be locked as well.
		async lockItems(items: ContentItem[], folderDeep: boolean) {
			const vm = this;
			if (items.length === 0) return;

			vm.errorMessage = '';

			try {
				const contentService = vm.instance.api.content;
				for (const item of items) {
					await contentService.lockNode(item.path, { isDeep: item.isCollection ? folderDeep : true });
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
		_setSharedClipboard(mode: 'copy' | 'cut', items: { path: string; name: string; isCollection: boolean }[], sourceFolderPath?: string) {
			const data = { mode, items, sourceFolderPath: sourceFolderPath ?? null };
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
		/** What the clipboard keeps of an item: a cloud entry carries where it is, as the drag payload does. */
		_clipboardItemOf(i: any): any {
			const item: any = { path: i.path, name: i.name, isCollection: i.isCollection, mimeType: i.mimeType };
			if (i.cloud && !i.cloud.virtual) {
				item.cloud = { provider: i.cloud.provider, driveId: i.cloud.driveId, id: i.cloud.id };
			}
			return item;
		},
		clipboardCopy() {
			const vm = this;
			const selected = vm.items.filter((i: any) => vm.selectedItems.includes(i.id) && !i.cloud?.virtual);
			if (selected.length === 0) return;
			vm._setSharedClipboard('copy', selected.map((i: any) => vm._clipboardItemOf(i)), vm.currentPath);
		},
		clipboardCut() {
			const vm = this;
			const selected = vm.items.filter((i: any) => vm.selectedItems.includes(i.id) && !i.cloud?.virtual);
			if (selected.length === 0) return;
			vm._setSharedClipboard('cut', selected.map((i: any) => vm._clipboardItemOf(i)), vm.currentPath);
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

			// Across the sources, and within cloud storage, a paste does what a
			// drop does. A cut is a move, which only one drive allows; a cut
			// pasted anywhere else is refused rather than silently copied.
			const crossing = vm.isCloudLocation || items.some((i: any) => i.cloud || isCloudPath(i.path));
			if (crossing) {
				const plan = vm._dropPlan(items, vm.currentPath, mode === 'copy', (cb as any).sourceFolderPath ?? null);
				if (!plan || (mode === 'cut' && plan !== 'cloud-move')) {
					vm.errorMessage = vm.t('app.content-browser.cloud.pasteUnavailable', undefined, 'These items cannot be pasted here.');
					return;
				}
				try { (window.parent as any).__webtopDragData = { items, sourceFolderPath: (cb as any).sourceFolderPath ?? null }; } catch (_) { /* cross-origin safety */ }
				await vm._executeDrop(items, vm.currentPath, mode === 'copy');
				if (mode === 'cut') vm._clearSharedClipboard();
				return;
			}

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
					const sourceParent = parentPathOf(item.path);

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
					const targetNodePath = childPath(destPath, item.name);
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
				// A cloud entry: the link to pass on, and where it is. The download
				// URL above is the same-origin one, so Mail attaches it as a file.
				webUrl: i.cloud?.webUrl || undefined,
				cloud: i.cloud ? { provider: i.cloud.provider, driveId: i.cloud.driveId, id: i.cloud.id, etag: i.cloud.etag } : undefined,
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
			let existing = await contentService.getNode(childPath(destPath, candidate)).catch(() => null);
			if (!existing) return candidate;

			// Try "name - Copy (2).ext", "name - Copy (3).ext", ...
			for (let i = 2; i <= 100; i++) {
				candidate = `${baseName} - Copy (${i})${ext}`;
				existing = await contentService.getNode(childPath(destPath, candidate)).catch(() => null);
				if (!existing) return candidate;
			}

			// Fallback: timestamp-based name
			return `${baseName} - Copy (${Date.now()})${ext}`;
		},
		_hasInternalDragData(event: DragEvent): boolean {
			if (event.dataTransfer?.types.includes('application/x-webtop-files')) return true;
			// Cross-iframe: check parent window drag data. Other apps park their
			// own payloads there too (mailAttachments), so only `items` counts.
			try {
				if (Array.isArray((window.parent as any).__webtopDragData?.items)) return true;
			} catch (_) { /* cross-origin safety */ }
			return false;
		},
		// ── Attachments dragged out of the Mail app ──────────────────────────
		_hasMailAttachmentDrag(event: DragEvent): boolean {
			if (event.dataTransfer?.types.includes(MAIL_ATTACHMENT_DRAG_TYPE)) return true;
			try {
				if (Array.isArray((window.parent as any).__webtopDragData?.mailAttachments)) return true;
			} catch (_) { /* cross-origin safety */ }
			return false;
		},
		_getMailAttachmentDrag(event: DragEvent): MailAttachmentDrag[] | null {
			const raw = event.dataTransfer?.getData(MAIL_ATTACHMENT_DRAG_TYPE);
			if (raw) {
				try {
					const list = JSON.parse(raw);
					if (Array.isArray(list) && list.length) return list;
				} catch (_) { /* not ours */ }
			}
			try {
				const list = (window.parent as any).__webtopDragData?.mailAttachments;
				if (Array.isArray(list) && list.length) return list;
			} catch (_) { /* cross-origin safety */ }
			return null;
		},
		/**
		 * Reads each attachment from its URL and uploads it into `destPath` (the
		 * folder being browsed, a folder row below it, or a cloud folder),
		 * through the same path as files from the PC.
		 */
		async saveMailAttachments(list: MailAttachmentDrag[], destPath: string) {
			const vm = this;
			if (vm.currentFolderDeleted) return;
			const cloudDest = isCloudPath(destPath);
			const dir = cloudDest || destPath === vm.currentPath ? '' : destPath.substring(vm.currentPath.length).replace(/^\/+/, '');
			vm.errorMessage = '';
			vm.uploadMonitor = {
				isCanceled: false,
				target: { currentFile: '', progressPercent: 0 },
				cancel() { this.isCanceled = true; },
			};
			const files: { file: File; path: string }[] = [];
			try {
				for (const a of list) {
					const name = (a.name || '').replace(/[\\/\u0000-\u001F\u007F]/g, '_').trim() || 'attachment';
					vm.uploadMonitor.target.currentFile = name;
					const response = await fetch(a.url, { credentials: 'same-origin' });
					if (!response.ok) throw new Error(`HTTP ${response.status}`);
					const blob = await response.blob();
					files.push({
						file: new File([blob], name, { type: a.mimeType || blob.type || 'application/octet-stream' }),
						path: dir ? `${dir}/${name}` : name,
					});
				}
			} catch (error: any) {
				vm.errorMessage = vm.t('app.content-browser.error.attachmentReadFailed', { error: error?.message || String(error) }, 'The attachment could not be read: {error}');
				return;
			}
			if (cloudDest) {
				try {
					await vm.uploadToCloud(files, destPath);
					await vm.load(vm.currentPath, true, { crumbs: vm.cloudCrumbs });
				} catch (error: any) {
					if (!vm.uploadMonitor?.isCanceled) {
						vm.errorMessage = error?.message || String(error) || vm.t('app.content-browser.error.uploadFailed', undefined, 'Upload failed');
					}
				} finally {
					if (!vm.errorMessage) vm.uploadMonitor = null;
				}
				return;
			}
			await vm._performUpload(files);
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
		// ---- Drops between the repository and cloud storage -------------------
		//
		// What a drop does depends on where the items come from and where they
		// land (see documents/webtop-cloud-storage.md): within the repository
		// a move (Ctrl: a copy); within one cloud drive a move (Ctrl: a copy);
		// between two drives of one provider, and across the sources, always a
		// copy; across providers, nothing. The listings above the drives take
		// nothing; a drive's row stands for its root.
		/** The folder a row stands for as a drop target, or null for a row that takes nothing. */
		_dropDestOf(item: ContentItem): string | null {
			if (!item.isCollection) return null;
			if (item.cloud?.virtual === 'drive') return cloudPath(item.cloud.provider, item.cloud.driveId);
			if (item.cloud?.virtual) return null;
			return item.path;
		},
		/** The ids above and of a cloud destination, for the "into itself" check. */
		_cloudDestChain(destPath: string): string[] {
			const location = parseCloudPath(destPath);
			if (!location || !location.id) return [];
			let crumbs: CloudCrumbs | null = null;
			if (destPath === this.currentPath) {
				crumbs = this.cloudCrumbs as CloudCrumbs | null;
			} else {
				const row = (this.items as ContentItem[]).find(i => i.path === destPath);
				if (row) crumbs = this.cloudCrumbsFor(row);
			}
			const chain = crumbs && !crumbs.pending ? crumbs.ancestors.map(a => a.id) : [];
			chain.push(location.id);
			return chain;
		},
		/**
		 * What dropping `items` on `destPath` would do: 'repository' (the
		 * existing move / copy), 'cloud-move', 'cloud-copy', 'import' (cloud →
		 * repository), 'export' (repository → cloud), or null when nothing may
		 * happen (across providers, into the listings above the drives, into
		 * itself, or a plain move into the folder the items are in).
		 */
		_dropPlan(items: { path: string; isCollection: boolean; cloud?: { provider: string; driveId: string; id: string | null } | null }[], destPath: string, isCopy: boolean, sourceFolderPath?: string | null): 'repository' | 'cloud-move' | 'cloud-copy' | 'import' | 'export' | null {
			if (!items.length) return null;
			const dest = parseCloudPath(destPath);
			if (dest?.virtual) return null;
			const cloudItems = items.filter(i => i.cloud || isCloudPath(i.path));
			if (cloudItems.length && cloudItems.length !== items.length) return null;
			if (!cloudItems.length) {
				if (!dest) {
					// The repository's own rules: a plain move into the source folder is a no-op,
					// and a folder cannot go into itself.
					if (!isCopy && sourceFolderPath === destPath) return null;
					for (const it of items) {
						if (it.isCollection && (destPath === it.path || destPath.startsWith(it.path + '/'))) return null;
					}
					return 'repository';
				}
				return 'export';
			}
			const sources = cloudItems.map(i => i.cloud || parseCloudPath(i.path)).filter(Boolean) as { provider: string; driveId: string | null; id: string | null }[];
			if (!dest) return 'import';
			if (sources.some(s => s.provider !== dest.provider)) return null;
			if (sources.every(s => s.driveId === dest.driveId)) {
				// The same drive: a folder cannot go into itself or below, and a
				// plain move into the folder the items are in is a no-op.
				const chain = this._cloudDestChain(destPath);
				for (const it of cloudItems) {
					const id = it.cloud?.id ?? parseCloudPath(it.path)?.id ?? null;
					if (it.isCollection && id && chain.includes(id)) return null;
				}
				if (!isCopy && sourceFolderPath === destPath) return null;
				return isCopy ? 'cloud-copy' : 'cloud-move';
			}
			return 'cloud-copy';
		},
		/** The cursor a plan asks for. */
		_dropEffectOf(plan: string | null, isCopy: boolean): 'none' | 'copy' | 'move' {
			if (!plan) return 'none';
			if (plan === 'repository' || plan === 'cloud-move') return isCopy ? 'copy' : 'move';
			return 'copy';
		},
		/** Whether a save chip ('application/x-webtop-save') may land on a location: its formats name where they may go. */
		_saveChipAllowedAt(destPath: string): boolean {
			const location = parseCloudPath(destPath);
			if (location?.virtual) return false;
			try {
				const save = (window.parent as any).__webtopDragData?.save;
				if (save && Array.isArray(save.targets)) {
					const target = location ? `cloud:${location.provider}` : 'repository';
					return save.targets.includes(target);
				}
			} catch (_) { /* cross-origin safety */ }
			return true;
		},
		_isDragForbidden(event: DragEvent, destPath?: string): boolean {
			try {
				const parentData = (window.parent as any).__webtopDragData;
				if (!parentData) return false;
				const dest = destPath ?? this.currentPath;
				if (Array.isArray(parentData.items)) {
					return this._dropPlan(parentData.items, dest, event.ctrlKey, parentData.sourceFolderPath) === null;
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
			// Folder rows (and drive rows) own this dragover. Stop propagation
			// so the parent's onDragOver does not re-evaluate the drop against
			// the current folder and overwrite the cursor. OS files are the
			// exception: they still go into the folder being browsed.
			const dest = this._dropDestOf(item);
			const types = event.dataTransfer ? Array.from(event.dataTransfer.types) : [];
			const isOsFiles = !this._hasMailAttachmentDrag(event) && !this._hasInternalDragData(event) && !types.includes('application/x-webtop-save');
			if (isOsFiles && !item.cloud) return;
			event.stopPropagation();
			// Files from the PC go into a cloud folder row (the repository's rows
			// leave them to the folder being browsed, above).
			const effect = !dest ? 'none' : isOsFiles ? 'copy' : this._dropEffectFor(event, dest);
			if (event.dataTransfer) event.dataTransfer.dropEffect = effect;
			if (effect === 'none') {
				if (this.dragOverFolderID === item.id) this.dragOverFolderID = null;
				return;
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
			const dest = vm._dropDestOf(item);
			if (!dest) return;
			// Files from the PC dropped on a repository folder row go into the
			// folder being browsed (the parent's drop), as before.
			const types = event.dataTransfer ? Array.from(event.dataTransfer.types) : [];
			const isOsFiles = !vm._hasMailAttachmentDrag(event) && !vm._hasInternalDragData(event) && !types.includes('application/x-webtop-save');
			if (isOsFiles && !item.cloud) return;
			// The folder row owns this drop: the parent's handler must not run it again.
			event.stopPropagation();
			await vm._dropAt(event, dest);
		},
		// ---- The sidebar's provider items: a drop goes to the root of the own drive ----
		onCloudStorageDragOver(storage: CloudStorage, event: DragEvent) {
			if (!event.dataTransfer) return;
			event.dataTransfer.dropEffect = this._dropEffectFor(event, cloudPath(storage.provider, CLOUD_MY_DRIVE));
		},
		async onCloudStorageDrop(storage: CloudStorage, event: DragEvent) {
			await this._dropAt(event, cloudPath(storage.provider, CLOUD_MY_DRIVE));
		},
		/**
		 * Runs a drop of the browser's own items by its plan: the repository's
		 * own move / copy, a move or copy within a provider, a copy out of a
		 * drive into the repository, or a copy of repository items into a drive.
		 */
		async _executeDrop(dragItems: any[], destPath: string, isCopy: boolean) {
			const vm = this;
			const sourceFolderPath = (() => { try { return (window.parent as any).__webtopDragData?.sourceFolderPath ?? null; } catch (_) { return null; } })();
			const plan = vm._dropPlan(dragItems, destPath, isCopy, sourceFolderPath);
			try {
				if (plan === 'repository') {
					await vm._executeInternalDrop(dragItems, destPath, isCopy);
					return;
				}
				if (plan === 'import') {
					const items = dragItems.map((d: any) => ({ name: d.name, isCollection: !!d.isCollection, cloud: d.cloud || parseCloudPath(d.path) }))
						.filter((d: any) => d.cloud && d.cloud.id) as any[];
					await vm.importFromCloud(items.map((d: any) => ({ ...d, cloud: { provider: d.cloud.provider, driveId: d.cloud.driveId || CLOUD_MY_DRIVE, id: d.cloud.id } })), destPath, false);
					return;
				}
				if (plan === 'export') {
					const location = parseCloudPath(destPath) as CloudLocation;
					await vm.exportToCloud(location.provider, vm.cloudProviderLabel(location.provider),
						{ driveId: location.driveId, id: location.id, name: vm._cloudFolderNameOf(destPath) }, dragItems);
					if (destPath === vm.currentPath) await vm.load(vm.currentPath, true, { crumbs: vm.cloudCrumbs });
					return;
				}
				if (plan === 'cloud-move' || plan === 'cloud-copy') {
					await vm._moveOrCopyInCloud(dragItems, destPath, plan === 'cloud-copy');
				}
			} finally {
				try { (window.parent as any).__webtopDragData = null; } catch (_) { /* cross-origin safety */ }
			}
		},
		/** The name of a cloud folder the list knows, for a message; the location text otherwise. */
		_cloudFolderNameOf(destPath: string): string {
			if (destPath === this.currentPath) {
				const crumbs = this.cloudCrumbs as CloudCrumbs | null;
				if (crumbs?.current) return crumbs.current.name;
				if (crumbs?.drive) return crumbs.drive.name;
			}
			const row = (this.items as ContentItem[]).find(i => i.path === destPath);
			return row ? row.name : destPath;
		},
		/** Moves (or copies) cloud items into a folder of a drive of their provider, one request each. */
		async _moveOrCopyInCloud(dragItems: any[], destPath: string, isCopy: boolean) {
			const vm = this;
			const cloud = vm.instance?.api.cloud;
			const source = vm._cloudSource as CloudSource | null;
			const location = parseCloudPath(destPath);
			if (!cloud || !location) return;
			vm.errorMessage = '';
			vm.uploadMonitor = {
				isCanceled: false,
				target: { currentFile: '', progressPercent: 0 },
				cancel() { this.isCanceled = true; },
			};
			const errors: string[] = [];
			try {
				let done = 0;
				for (const d of dragItems) {
					if (vm.uploadMonitor?.isCanceled) break;
					const info = d.cloud || parseCloudPath(d.path);
					if (!info || !info.id) continue;
					vm.uploadMonitor.target.currentFile = d.name;
					vm.uploadMonitor.target.progressPercent = Math.round((done / dragItems.length) * 100);
					try {
						if (isCopy) {
							source?.notePut(await cloud.copy(info.provider, info.driveId || CLOUD_MY_DRIVE, info.id, location.driveId as string, location.id), location);
						} else {
							const moved = await cloud.move(info.provider, info.driveId || CLOUD_MY_DRIVE, info.id, location.id);
							source?.noteRemoved(info, location);
							source?.notePut(moved, location);
						}
					} catch (e: any) {
						errors.push(`${d.name}: ${e?.message || String(e)}`);
					}
					done++;
				}
				await vm.load(vm.currentPath, true, { crumbs: vm.cloudCrumbs });
			} finally {
				vm.uploadMonitor = null;
			}
			if (errors.length) {
				vm.errorMessage = vm.t('app.content-browser.cloud.copyFailed', { n: errors.length, error: errors[0] }, '{n} item(s) could not be copied: {error}');
			}
		},
		/** The servlet URL that takes new files for a cloud folder. */
		_cloudChildrenUrl(location: CloudLocation): string {
			return `/bin/cloud.cgi/${encodeURIComponent(location.provider)}/${encodeURIComponent(location.driveId as string)}/${encodeURIComponent(location.id || 'root')}/children`;
		},
		/**
		 * Uploads files from the PC (or mail attachments) into a cloud folder,
		 * one request per file, folders made first. A name the folder being
		 * browsed already has is asked about with the usual conflict dialog
		 * (overwrite replaces the file's content); elsewhere the provider's
		 * folder is not read first and a taken name gets a number.
		 */
		async uploadToCloud(files: { file: File; path: string }[], destPath: string) {
			const vm = this;
			const cloud = vm.instance?.api.cloud;
			const source = vm._cloudSource as CloudSource | null;
			const location = parseCloudPath(destPath);
			if (!cloud || !location || !vm.uploadMonitor) return;
			const known = destPath === vm.currentPath ? (vm.items as ContentItem[]) : [];
			// Folders by their relative path; the top level reuses one the listing shows.
			const folders = new Map<string, string | null>();
			folders.set('', location.id);
			const folderIdFor = async (dir: string): Promise<string | null> => {
				if (folders.has(dir)) return folders.get(dir) as string | null;
				const slash = dir.lastIndexOf('/');
				const parentDir = slash >= 0 ? dir.substring(0, slash) : '';
				const name = slash >= 0 ? dir.substring(slash + 1) : dir;
				const parentId = await folderIdFor(parentDir);
				let id: string | null = null;
				if (parentDir === '') {
					const existing = known.find(i => i.isCollection && i.name === name && i.cloud?.id);
					if (existing) id = existing.cloud!.id;
				}
				if (!id) {
					const made = await cloud.createFolder(location.provider, location.driveId as string, parentId, name);
					source?.notePut(made, { ...location, id: parentId });
					id = made.id;
				}
				folders.set(dir, id);
				return id;
			};
			let overwriteAll = false;
			let skipAll = false;
			let done = 0;
			for (const info of files) {
				if (vm.uploadMonitor.isCanceled) break;
				vm.uploadMonitor.target.currentFile = info.path;
				vm.uploadMonitor.target.progressPercent = Math.round((done / files.length) * 100);
				const slash = info.path.lastIndexOf('/');
				const dir = slash >= 0 ? info.path.substring(0, slash) : '';
				const name = slash >= 0 ? info.path.substring(slash + 1) : info.path;
				let conflict: 'FAIL' | 'REPLACE' | 'RENAME' = 'RENAME';
				if (dir === '' && known.some(i => !i.isCollection && i.name === name)) {
					let action = overwriteAll ? 'overwrite' : skipAll ? 'skip' : await vm.askConflict();
					if (action === 'overwriteAll') { overwriteAll = true; action = 'overwrite'; }
					if (action === 'skipAll') { skipAll = true; action = 'skip'; }
					if (action === 'cancel') { vm.uploadMonitor.cancel(); break; }
					if (action === 'skip') { done++; continue; }
					conflict = 'REPLACE';
				}
				const folderId = await folderIdFor(dir);
				const form = new FormData();
				form.append('conflict', conflict);
				form.append('file', info.file, name);
				const response = await fetch(vm._cloudChildrenUrl({ ...location, id: folderId }), { method: 'POST', credentials: 'same-origin', body: form });
				if (!response.ok) {
					let message = `HTTP ${response.status}`;
					try { message = (await response.json()).message || message; } catch (_) { /* no JSON */ }
					throw new Error(`${name}: ${message}`);
				}
				try {
					for (const entry of await response.json() as CloudEntry[]) {
						source?.notePut(entry, { ...location, id: folderId });
					}
				} catch (_) { /* uploaded; the listing shows it once the provider does */ }
				done++;
				vm.uploadMonitor.target.progressPercent = Math.round((done / files.length) * 100);
			}
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

					const sourceParent = parentPathOf(dragItem.path);

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
					const targetNodePath = childPath(destPath, dragItem.name);
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
				if (item.cloud) {
					await (vm._cloudSource as CloudSource).rename(item.cloud, newName);
					vm.closeRenameDialog();
					// A search result is renamed where it is (see submitDelete).
					if (vm.cloudSearchActive) {
						vm._overlayCloudSearch();
					} else {
						await vm.refreshList();
					}
					return;
				}
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

			// Cloud entries go to the provider's trash, one request each.
			if (items.some((it: ContentItem) => it.cloud)) {
				const source = vm._cloudSource as CloudSource;
				const errors: string[] = [];
				for (const it of items as ContentItem[]) {
					if (!it.cloud || !it.cloud.id) continue;
					try {
						await source.delete(it.cloud);
					} catch (error: any) {
						errors.push(`${it.name}: ${error?.message || String(error)}`);
					}
				}
				vm.deleteDialog.isLoading = false;
				// Search results are taken out where they are: searching again
				// would blank the list and bring them back from the provider's
				// index, which catches up minutes later.
				if (vm.cloudSearchActive) vm._overlayCloudSearch();
				if (errors.length) {
					vm.deleteDialog.errorMessage = errors[0];
					return;
				}
				vm.closeDeleteDialog();
				if (!vm.cloudSearchActive) await vm.refreshList();
				return;
			}

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
				if (vm.isCloudLocation) {
					await (vm._cloudSource as CloudSource).createFolder(vm.cloudLocation as CloudLocation, name);
					vm.closeNewFolderDialog();
					await vm.load(vm.currentPath, true, { crumbs: vm.cloudCrumbs });
					return;
				}
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
			name = withExtension(name, fileType);

			vm.newFileDialog.isLoading = true;
			vm.newFileDialog.errorMessage = '';

			try {
				const contentService = vm.instance.api.content;
				// Create the file with the type's initial content (ASCII, as base64), empty by default
				await contentService.createFile(vm.currentPath, name, fileType.mimeType, fileType.content ? btoa(fileType.content) : '');
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
			// A cloud location is made of ids: nothing a person could type.
			if (this.isCloudLocation) return;
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
			await this.copyToClipboard(this.isCloudLocation ? this.locationText : (this.editPathValue || this.currentPath));
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
		// The parent folder. Undefined while the list shows search results
		// (they come from no single folder) and at the root.
		async goToParent() {
			if (!this.canGoToParent) return;
			if (this.isCloudLocation) {
				const parent = this.cloudParentPath();
				if (!parent) return;
				const source = this._cloudSource as CloudSource;
				const crumbs = this.cloudCrumbs as CloudCrumbs | null;
				await this.load(parent, false, { crumbs: crumbs && !crumbs.pending && crumbs.current ? source.crumbsAbove(crumbs) : null });
				return;
			}
			await this.load(parentPathOf(this.currentPath));
		},
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
			if (isCloudPath(path)) {
				// A history entry of a cloud location: only its ids are kept, so
				// the provider is the one name there is.
				const pin = (this.pinnedPaths as any[]).find((p: any) => p.path === path);
				if (pin?.name) return pin.name;
				const location = parseCloudPath(path);
				return location ? this.cloudProviderLabel(location.provider) : path;
			}
			const parts = path.split('/').filter((p: string) => p);
			return parts.length > 0 ? parts[parts.length - 1] : 'root';
		},
		/** A cloud folder or drive row pinned as a favourite: its name and its location as names. */
		pinCloudItem(item: ContentItem) {
			if (!item.cloud || this.isPathPinned(item.path)) return;
			this.pinnedPaths.push({ path: item.path, name: item.name, display: this.cloudItemLocationText(item) });
			this.persistNavData();
			this.syncContentBrowserPreferences();
		},
		/** What a favourite shows under its name: the path, or a cloud location as names. */
		pinDisplayPath(pin: { path: string; display?: string }): string {
			return pin.display || pin.path;
		},
		/** A favourite of a provider the user no longer allows stays put, locked. */
		openPinnedPath(pin: { path: string }) {
			if (!this.isCloudPathGranted(pin.path)) return;
			this.navigateToPath(pin.path);
		},
		/** Whether a column header orders the list here: every one in the repository, name and date in cloud storage. */
		canSortColumn(column: string): boolean {
			return !this.isCloudLocation || (this.cloudSortableColumns as string[]).includes(column);
		},
		sortHeader(column: string) {
			if (!this.canSortColumn(column)) return;
			this.sortItems(column);
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
			if (isCloudPath(path)) {
				// The location being shown: its last name, and the whole as names.
				const crumbs = (path === this.currentPath) ? this.cloudCrumbs as CloudCrumbs | null : null;
				const location = parseCloudPath(path);
				const labels = this.cloudLabels as CloudLabels;
				let name = location ? this.cloudProviderLabel(location.provider) : path;
				if (crumbs?.current) name = crumbs.current.name;
				else if (crumbs?.drive) name = crumbs.drive.kind === 'MINE' ? labels.myDrive(crumbs.provider) : crumbs.drive.name;
				else if (location?.virtual === 'shared') name = labels.sharedDrives(location.provider);
				this.pinnedPaths.push({ path, name, display: crumbs ? cloudLocationText(crumbs, labels) : name });
				this.persistNavData();
				this.syncContentBrowserPreferences();
				return;
			}
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
			// Clicking anywhere outside the path editor cancels editing and
			// restores the breadcrumb view. `.nav-breadcrumb` is excluded
			// because the very click that opens edit mode also bubbles up to
			// this document listener — without the guard it would close the
			// editor immediately after enterEditMode() opened it.
			if (this.navEditMode && !target.closest('.nav-edit') && !target.closest('.nav-breadcrumb')) {
				this.exitEditMode();
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
			// A cloud location is searched by its provider (see the Cloud search section).
			if (vm.isCloudLocation) {
				await vm.cloudExecuteSearch();
				return;
			}
			// Surface a parse error from the full-text expression before kicking off the request.
			const ft = vm.fullTextCompiled as any as { predicate: string | null; error: string | null };
			if (ft.error) {
				vm.xpathSearchError = `Full-text query: ${ft.error}`;
				vm.xpathSearchActive = true;
				vm.items = [];
				return;
			}
			// No input at all lists every file under the current folder, which
			// is what the facets then narrow.
			vm._cancelFolderSizes();
			vm.items = [];
			const seq = ++vm._xpathSearchSeq;
			vm.xpathSearchLoading = true;
			vm.xpathSearchLoadingMore = false;
			vm.xpathSearchError = '';
			vm.xpathSearchActive = true;
			vm.xpathSearchCursor = null;
			vm.xpathSearchHasMore = false;
			// The counts are fetched alongside the first page, not after it.
			vm.loadFacetCounts();
			try {
				const contentService = vm.instance.api.content;
				// Only the first page is fetched here. The server orders the
				// result (see xpathSearchStatement), so the page is shown as it
				// comes; xpathLoadMore fetches the pages that follow as the list
				// is scrolled, and the total comes from the index's exact count.
				const result = await contentService.xpath(vm.xpathSearchStatement, { first: SEARCH_PAGE_SIZE });
				if (seq !== vm._xpathSearchSeq) {
					return;
				}
				vm.items = vm._xpathPageItems(result.edges);
				vm.xpathSearchTotalCount = result.totalCount;
				vm.xpathSearchCursor = result.pageInfo.endCursor ?? null;
				vm.xpathSearchHasMore = !!result.pageInfo.hasNextPage;
				if (!vm.xpathSortIsServerSide) {
					vm.sortItems(vm.sortColumn, vm.sortDirection);
				}
			} catch (error: any) {
				if (seq === vm._xpathSearchSeq) {
					vm.xpathSearchError = error?.message || String(error);
				}
			} finally {
				if (seq === vm._xpathSearchSeq) {
					vm.xpathSearchLoading = false;
					vm._xpathFillViewport();
				}
			}
		},
		// ---- Facets ----
		//
		// The statement of the list, or of a count: the search inputs plus the
		// predicate of every selected facet except `exclude`. The orientation
		// and format predicates apply only with the kind they belong to.
		_xpathStatement(exclude: FacetDimension | null): string {
			const path = this.currentPath === '/' ? '//' : `/jcr:root${this.currentPath}//`;
			const conditions = (this.xpathBaseConditions as string[]).slice();
			for (const dimension of ['kind', 'orientation', 'format', 'color', 'tag'] as FacetDimension[]) {
				if (dimension === exclude) continue;
				// The orientation and format narrow a kind, so a count that asks
				// what another kind would give leaves them out as well.
				if (exclude === 'kind' && (dimension === 'orientation' || dimension === 'format')) continue;
				const predicate = this._facetPredicate(dimension);
				if (predicate) conditions.push(predicate);
			}
			const predicate = conditions.length > 0 ? `[${conditions.join(' and ')}]` : '';
			return `${path}element(*, nt:file)${predicate}`;
		},
		_facetPredicate(dimension: FacetDimension): string | null {
			const s = this.facetSelection;
			const anyOf = (parts: string[]) => parts.length === 1 ? parts[0] : `(${parts.join(' or ')})`;
			if (dimension === 'kind') {
				if (!s.kind) return null;
				const media = Object.keys(FACET_MEDIA_PREFIXES).map(kind =>
					`jcr:like(@jcr:mimeType, '${FACET_MEDIA_PREFIXES[kind]}%')`);
				const script = anyOf(FACET_SCRIPT_FORMATS.
					flatMap(f => f.mimeTypes).
					map(m => `@jcr:mimeType=${xpathString(m)}`));
				if (s.kind === 'script') {
					return script;
				}
				if (s.kind === 'document') {
					// A document is what is neither a media kind nor a script.
					return `(${media.concat(script).map(p => `not(${p})`).join(' and ')})`;
				}
				return `jcr:like(@jcr:mimeType, '${FACET_MEDIA_PREFIXES[s.kind]}%')`;
			}
			if (dimension === 'orientation') {
				if (!this.facetShowsOrientation || s.orientation.length === 0) return null;
				return anyOf(s.orientation.map((v: string) => `@mi:orientation=${xpathString(v)}`));
			}
			if (dimension === 'format') {
				if (!this.facetShowsFormat || s.format.length === 0) return null;
				const formats = FACET_FORMATS_BY_KIND[s.kind];
				const ofFormats = (keys: string[]) => formats.
					filter(f => keys.includes(f.key)).
					flatMap(f => f.mimeTypes).
					map(m => `@jcr:mimeType=${xpathString(m)}`);
				const chosen = ofFormats(s.format);
				if (s.kind === 'document' && s.format.includes(FACET_FORMAT_OTHER)) {
					// "Other" is what no listed format claims.
					const listed = ofFormats(formats.map(f => f.key));
					chosen.push(`not(${anyOf(listed)})`);
				}
				if (chosen.length === 0) return null;
				return anyOf(chosen);
			}
			if (dimension === 'color') {
				if (s.color.length === 0) return null;
				return anyOf(s.color.map((v: string) => `@mi:color=${xpathString(v)}`));
			}
			if (dimension === 'tag') {
				if (s.tag.length === 0) return null;
				// Every selected tag: a multi-valued property matches when any of
				// its values does, so one predicate per tag narrows to files
				// carrying all of them.
				return s.tag.map((v: string) => `@mi:tags=${xpathString(v)}`).join(' and ');
			}
			return null;
		},
		// Fetches the counts of every facet for the current inputs and
		// selection. Runs alongside the list's first page; a result for an
		// earlier selection is dropped.
		async loadFacetCounts() {
			const vm = this;
			const seq = ++vm._facetSeq;
			vm.facetLoading = true;
			try {
				const contentService = vm.instance.api.content;
				const s = vm.facetSelection;
				// The variants to fetch: every filter applied (null), and one per
				// dimension with a selection, without that dimension's filter.
				// Tags read the fully filtered set (see the Facets section).
				const variants: (FacetDimension | null)[] = [null];
				if (s.kind) variants.push('kind');
				if (vm.facetShowsOrientation && s.orientation.length) variants.push('orientation');
				if (vm.facetShowsFormat && s.format.length) variants.push('format');
				if (s.color.length) variants.push('color');
				const results = await Promise.all(variants.map(exclude =>
					contentService.xpathFacets(`${vm._xpathStatement(exclude)} ${FACET_CLAUSE}`)));
				if (seq !== vm._facetSeq || !vm.xpathSearchActive) {
					return;
				}
				const facetOf = (exclude: FacetDimension, dimension: string): Record<string, number> => {
					const idx = Math.max(variants.indexOf(exclude), 0);
					const facet = (results[idx].facets || []).find((f: any) => f.dimension === dimension);
					const counts: Record<string, number> = {};
					for (const e of facet?.entries || []) {
						counts[String(e.label)] = e.count;
					}
					return counts;
				};
				const counts = emptyFacetCounts();
				// Kinds and formats are groups of MIME types, summed from the
				// jcr:mimeType facet of their variant.
				for (const [mimeType, n] of Object.entries(facetOf('kind', 'jcr:mimeType'))) {
					const kind = facetKindOf(mimeType);
					counts.kind[kind] = (counts.kind[kind] || 0) + n;
				}
				if (vm.facetShowsFormat) {
					for (const [mimeType, n] of Object.entries(facetOf('format', 'jcr:mimeType'))) {
						const format = facetFormatOf(s.kind, mimeType);
						if (format === null) continue;
						counts.format[format] = (counts.format[format] || 0) + n;
					}
				}
				counts.orientation = facetOf('orientation', 'mi:orientation');
				counts.color = facetOf('color', 'mi:color');
				counts.tag = facetOf('tag', 'mi:tags');
				vm.facetCounts = counts;
			} catch (error: any) {
				if (seq === vm._facetSeq) {
					console.warn('Facet counts not loaded:', error);
				}
			} finally {
				if (seq === vm._facetSeq) {
					vm.facetLoading = false;
				}
			}
		},
		facetCount(dimension: FacetDimension, value: string): number {
			return (this.facetCounts[dimension] as Record<string, number>)[value] || 0;
		},
		facetIsSelected(dimension: FacetDimension, value: string): boolean {
			if (dimension === 'kind') return this.facetSelection.kind === value;
			return (this.facetSelection[dimension] as string[]).includes(value);
		},
		// The kind is a single choice; choosing it again clears it. The
		// orientation and format belong to a kind, so they go with it.
		facetSelectKind(kind: string) {
			const s = this.facetSelection;
			s.kind = s.kind === kind ? '' : kind;
			s.orientation = [];
			s.format = [];
			this.xpathExecuteSearch();
		},
		facetToggle(dimension: FacetDimension, value: string) {
			if (dimension === 'kind') {
				this.facetSelectKind(value);
				return;
			}
			const values = this.facetSelection[dimension] as string[];
			const idx = values.indexOf(value);
			if (idx >= 0) {
				values.splice(idx, 1);
			} else {
				values.push(value);
			}
			this.xpathExecuteSearch();
		},
		// Clears the facet selection and the values of the schema conditions.
		// The conditions themselves stay, so the same properties can be
		// filled in again; the operator toggles (≥ / >) are not values and
		// stay as they are.
		facetClearAll() {
			if (!this.facetCanClear) return;
			this.facetSelection = emptyFacetSelection();
			for (const cond of this.xpathConditions as any[]) {
				cond.stringValue = '';
				cond.numberFrom = '';
				cond.numberTo = '';
				cond.booleanValue = '';
				cond.dateMode = 'none';
				cond.datePastN = 7;
				cond.dateFrom = '';
				cond.dateTo = '';
				cond.selectedChoices = [];
			}
			this.xpathExecuteSearch();
		},
		// Forgets the selection and counts without running a search: the
		// search is over, or a new one starts from scratch.
		_facetReset() {
			this._facetSeq++;
			this.facetSelection = emptyFacetSelection();
			this.facetCounts = emptyFacetCounts();
			this.facetLoading = false;
		},
		toggleFacetSection(name: string) {
			this.facetSectionExpanded[name] = !this.facetSectionExpanded[name];
		},
		facetKindIcon(kind: string): string {
			return {
				image: 'bi-image',
				video: 'bi-film',
				audio: 'bi-music-note-beamed',
				script: 'bi-file-earmark-code',
				document: 'bi-file-earmark-text',
			}[kind] || 'bi-file-earmark';
		},
		facetKinds(): string[] {
			return FACET_KINDS;
		},
		facetOrientations(): string[] {
			return FACET_ORIENTATIONS;
		},
		// The formats of the selected kind; none while the kind has no Format facet.
		facetFormats(): string[] {
			return (FACET_FORMATS_BY_KIND[this.facetSelection.kind] || []).map(f => f.key);
		},
		facetColors(): { key: string; value: string }[] {
			return SWATCH_COLORS;
		},
		// The items of one search page, in the order the server returned them.
		_xpathPageItems(edges: { node: Node }[]): ContentItem[] {
			const items: ContentItem[] = [];
			for (const edge of edges) {
				const item = nodeToContentItem(edge.node);
				item.attributes.displayDate = this.displayDate(item);
				items.push(item);
			}
			return items;
		},
		// Fetches the page after the last one loaded and appends it. A no-op
		// while a search or another page is in flight, or when nothing follows.
		async xpathLoadMore() {
			const vm = this;
			if (!vm.xpathSearchActive || !vm.xpathSearchHasMore || vm.xpathSearchLoading || vm.xpathSearchLoadingMore) {
				return;
			}
			if (vm.isCloudLocation) {
				await vm.cloudSearchLoadMore();
				return;
			}
			const seq = vm._xpathSearchSeq;
			const after = vm.xpathSearchCursor ?? undefined;
			vm.xpathSearchLoadingMore = true;
			try {
				const contentService = vm.instance.api.content;
				const result = await contentService.xpath(vm.xpathSearchStatement, { first: SEARCH_PAGE_SIZE, after });
				if (seq !== vm._xpathSearchSeq || !vm.xpathSearchActive) {
					return;
				}
				// An item that arrived twice (the result shifted between two
				// pages) is kept once, under its first position.
				const known = new Set((vm.items as ContentItem[]).map(i => i.id));
				const appended = vm._xpathPageItems(result.edges).filter((i: ContentItem) => !known.has(i.id));
				vm.items = (vm.items as ContentItem[]).concat(appended);
				vm.xpathSearchTotalCount = result.totalCount;
				const nextCursor = result.pageInfo.endCursor ?? null;
				// A cursor that did not advance would fetch the same page forever.
				vm.xpathSearchHasMore = !!result.pageInfo.hasNextPage && !!nextCursor && nextCursor !== vm.xpathSearchCursor;
				vm.xpathSearchCursor = nextCursor;
				if (!vm.xpathSortIsServerSide) {
					vm.sortItems(vm.sortColumn, vm.sortDirection);
				}
			} catch (error: any) {
				if (seq === vm._xpathSearchSeq) {
					vm.xpathSearchError = error?.message || String(error);
				}
			} finally {
				if (seq === vm._xpathSearchSeq) {
					vm.xpathSearchLoadingMore = false;
					vm._xpathFillViewport();
				}
			}
		},
		// Scrolling the list to its end asks for the next search page.
		onContentListScroll(event: Event) {
			const el = event.target as HTMLElement;
			const nearEnd = el.scrollTop + el.clientHeight >= el.scrollHeight - SEARCH_LOAD_MORE_MARGIN;
			if (this.cloudHasMore) {
				if (nearEnd) this.cloudLoadMore();
				return;
			}
			if (!this.xpathSearchActive || !this.xpathSearchHasMore) {
				return;
			}
			if (nearEnd) {
				this.xpathLoadMore();
			}
		},
		// A page too short to scroll can never ask for the next one, so pages
		// keep coming until the list overflows its viewport (or runs out).
		_xpathFillViewport() {
			const vm = this;
			vm.$nextTick(() => {
				if (!vm.xpathSearchActive || !vm.xpathSearchHasMore) {
					return;
				}
				const el = vm.$refs.contentList as HTMLElement | undefined;
				if (el && el.scrollHeight <= el.clientHeight + 1) {
					vm.xpathLoadMore();
				}
			});
		},
		// Re-run whatever produced the current list. Two different queries back
		// the list, and xpathSearchActive is what says which one is showing.
		/** The changes just made, laid over the cloud search results on the screen; the selection keeps what is left. */
		_overlayCloudSearch() {
			const vm = this;
			const source = vm._cloudSource as CloudSource | null;
			if (!source) return;
			vm.items = vm._stampDates(source.overlaySearch(vm.items as CloudContentItem[]));
			const ids = new Set((vm.items as ContentItem[]).map(i => i.id));
			vm.selectedItems = vm.selectedItems.filter((id: string) => ids.has(id));
		},
		async refreshList() {
			if (this.xpathSearchActive) {
				await this.xpathExecuteSearch();
				return;
			}
			// A cloud location keeps its names (the crumbs are ignored elsewhere).
			await this.load(this.currentPath, true, { crumbs: this.cloudCrumbs });
		},
		async xpathClearSearch() {
			// Reloading the folder is what ends the search: load() clears the
			// search state as part of replacing the list.
			await this.load(this.currentPath, true, { crumbs: this.cloudCrumbs });
		},
		// ---- Cloud search ----
		//
		// A cloud location is searched by its provider: in the folder being
		// browsed (Microsoft) or in the whole drive it is in (Google, whose
		// API has no "below this folder" condition; CloudStorage.searchScope
		// says which). The words and the facets go to the server as one
		// CloudQuery; what the provider cannot take, the server applies to
		// the answers. The result is listed through the same state as a
		// repository search (xpathSearchActive and the paging fields), so the
		// search bar, the sidebar and the menus follow it; the provider
		// counts nothing, so there is no total and the facets have no counts.
		// A result's folder is not the one being browsed: the Folder column
		// names it, and the Inspector's Location, once the names are known.
		//
		/** The query the search field and the facets make. */
		_cloudQuery(): CloudQuery {
			const s = this.cloudFacetSelection;
			const query: CloudQuery = {};
			const text = (this.fullTextKeyword as string).trim();
			if (text) query.text = text;
			const kind = CLOUD_FACET_KINDS.find(k => k.key === s.kind);
			if (kind) query.kinds = [kind.kind];
			const tz = this.localization.timeZone || undefined;
			const span = CLOUD_FACET_MODIFIED.find(m => m.key === s.modified);
			if (span && span.daysAgo !== null) {
				query.modifiedAfter = Dates.startOfDay(tz, span.daysAgo).toISOString();
			} else if (span) {
				// A range of days, in the user's time zone: from the start of
				// the first day to the end of the last.
				const from = Dates.fromZonedInputValue(s.from ? `${s.from}T00:00` : null, tz);
				if (from) query.modifiedAfter = from.toISOString();
				const to = Dates.fromZonedInputValue(s.to ? `${s.to}T00:00` : null, tz);
				if (to) query.modifiedBefore = new Date(to.getTime() + 24 * 3600 * 1000).toISOString();
			}
			if (s.ownedByMe) query.ownedByMe = true;
			return query;
		},
		/** Runs the search of the cloud location from the first page. */
		async cloudExecuteSearch() {
			const vm = this;
			const source = vm._cloudSource as CloudSource | null;
			const location = vm.cloudLocation as CloudLocation | null;
			const crumbs = vm.cloudCrumbs as CloudCrumbs | null;
			if (!source || !location || !crumbs || !vm.cloudSearchAvailable) return;
			vm._cancelFolderSizes();
			vm.items = [];
			// The listing's pages stop: the search's take their place.
			vm._cloudSeq++;
			vm.cloudCursor = null;
			vm.cloudHasMore = false;
			vm.cloudLoadingMore = false;
			cloudSearchFolderCache.clear();
			const seq = ++vm._xpathSearchSeq;
			vm.xpathSearchLoading = true;
			vm.xpathSearchLoadingMore = false;
			vm.xpathSearchError = '';
			vm.xpathSearchActive = true;
			vm.xpathSearchTotalCount = 0;
			vm.xpathSearchCursor = null;
			vm.xpathSearchHasMore = false;
			try {
				const listing = await source.search(location, crumbs, vm._cloudQuery(), null, CLOUD_SEARCH_PAGE_SIZE);
				if (seq !== vm._xpathSearchSeq) return;
				vm.items = vm._stampDates(listing.items);
				vm.xpathSearchTotalCount = vm.items.length;
				vm.xpathSearchCursor = listing.nextCursor;
				vm.xpathSearchHasMore = !!listing.nextCursor;
				vm.sortItems(vm.sortColumn, vm.sortDirection);
				vm._resolveCloudSearchFolders(listing.items, seq);
			} catch (error: any) {
				if (seq === vm._xpathSearchSeq) {
					vm.xpathSearchError = vm._cloudSearchErrorText(error);
				}
			} finally {
				if (seq === vm._xpathSearchSeq) {
					vm.xpathSearchLoading = false;
					vm._xpathFillViewport();
				}
			}
		},
		/** The next page of the cloud search, as the list is scrolled to its end. */
		async cloudSearchLoadMore() {
			const vm = this;
			const source = vm._cloudSource as CloudSource | null;
			const location = vm.cloudLocation as CloudLocation | null;
			const crumbs = vm.cloudCrumbs as CloudCrumbs | null;
			if (!source || !location || !crumbs) return;
			const seq = vm._xpathSearchSeq;
			vm.xpathSearchLoadingMore = true;
			try {
				const listing = await source.search(location, crumbs, vm._cloudQuery(), vm.xpathSearchCursor, CLOUD_SEARCH_PAGE_SIZE);
				if (seq !== vm._xpathSearchSeq || !vm.xpathSearchActive) return;
				const known = new Set((vm.items as ContentItem[]).map(i => i.id));
				const appended = listing.items.filter(i => !known.has(i.id));
				vm.items = (vm.items as ContentItem[]).concat(vm._stampDates(appended));
				vm.xpathSearchTotalCount = vm.items.length;
				// A cursor that did not advance would fetch the same page forever.
				vm.xpathSearchHasMore = !!listing.nextCursor && listing.nextCursor !== vm.xpathSearchCursor;
				vm.xpathSearchCursor = listing.nextCursor;
				vm.sortItems(vm.sortColumn, vm.sortDirection);
				vm._resolveCloudSearchFolders(appended, seq);
			} catch (error: any) {
				if (seq === vm._xpathSearchSeq) {
					vm.xpathSearchError = vm._cloudSearchErrorText(error);
					vm.xpathSearchHasMore = false;
				}
			} finally {
				if (seq === vm._xpathSearchSeq) {
					vm.xpathSearchLoadingMore = false;
					vm._xpathFillViewport();
				}
			}
		},
		/** The folders of the results whose provider named only the parent's id, looked up while the search is shown. */
		_resolveCloudSearchFolders(added: ContentItem[], seq: number) {
			const vm = this;
			const source = vm._cloudSource as CloudSource | null;
			if (!source || seq !== vm._xpathSearchSeq) return;
			// The listed items (not the page's own objects) are what the rows
			// and the Inspector watch, so those are what get filled in.
			const ids = new Set(added.filter(i => i.cloud?.search?.pending).map(i => i.id));
			for (const item of vm.items as ContentItem[]) {
				if (!ids.has(item.id)) continue;
				source.resolveSearchFolder(item as any, cloudSearchFolderCache).catch(() => { /* marked on the item */ });
			}
		},
		/** What the search bar says went wrong: a throttling provider, or the error itself. */
		_cloudSearchErrorText(error: any): string {
			if (error instanceof GraphQLError && error.code === 'RATE_LIMITED') {
				return this.t('app.content-browser.cloud.rateLimited', undefined, 'The provider is busy; try again in a moment.');
			}
			return error?.message || String(error);
		},
		/** The Folder column of a search result: the folder's name, "…" while it is looked up, or nothing. */
		cloudSearchFolderName(item: ContentItem): string {
			const hit = item.cloud?.search;
			if (!hit) return '';
			if (hit.pending) return '…';
			return hit.folderName || '';
		},
		cloudFacetKinds(): { key: string; icon: string }[] {
			return CLOUD_FACET_KINDS;
		},
		cloudFacetModifiedKeys(): string[] {
			return CLOUD_FACET_MODIFIED.map(m => m.key);
		},
		cloudFacetShows(facet: CloudFacet): boolean {
			return (this.cloudFacets as CloudFacet[]).includes(facet);
		},
		/** The Kind facet is one choice; choosing it again clears it. */
		cloudFacetSelectKind(kind: string) {
			const s = this.cloudFacetSelection;
			s.kind = s.kind === kind ? '' : kind;
			this.cloudExecuteSearch();
		},
		/** The Modified facet is one choice; a range waits for its dates. */
		cloudFacetSelectModified(key: string) {
			const s = this.cloudFacetSelection;
			s.modified = s.modified === key ? '' : key;
			if (s.modified === 'range' && !s.from && !s.to) return;
			this.cloudExecuteSearch();
		},
		/** A date of the range was entered: searched once there is one. */
		cloudFacetRangeChanged() {
			const s = this.cloudFacetSelection;
			if (s.modified !== 'range' || (!s.from && !s.to)) return;
			this.cloudExecuteSearch();
		},
		cloudFacetToggleOwner() {
			const s = this.cloudFacetSelection;
			s.ownedByMe = !s.ownedByMe;
			this.cloudExecuteSearch();
		},
		cloudFacetClearAll() {
			if (!(this.cloudFacetSelectionCount as number)) return;
			this.cloudFacetSelection = emptyCloudFacetSelection();
			this.cloudExecuteSearch();
		},
		/** Forgets the selection without searching: the search is over, or a new one starts from scratch. */
		_cloudFacetReset() {
			this.cloudFacetSelection = emptyCloudFacetSelection();
		},
		// Smart folder methods
		// Whether there is a search to save: a schema condition, a keyword or
		// a facet selection.
		smartFolderCanSave(): boolean {
			return (this.hasSearchInput as boolean) || (this.facetSelectionCount as number) > 0;
		},
		smartFolderSaveCurrent() {
			const hasSchemaCondition = this.xpathSelectedSchema && this.xpathConditions.length > 0;
			const fullText = (this.fullTextKeyword as string).trim();
			const facets = this.facetSelectionCount > 0 ? JSON.parse(JSON.stringify(this.facetSelection)) : null;
			if (!hasSchemaCondition && !fullText && !facets) return;
			// The name says what the folder searches for: the schema or the
			// keyword, then each facet chosen, then where.
			const parts: string[] = [];
			if (hasSchemaCondition) {
				const schema = (this.availableSchemas as any[]).find(s => s.key === this.xpathSelectedSchema);
				parts.push(schema ? schema.label : this.xpathSelectedSchema);
			} else if (fullText) {
				parts.push(fullText.length > 32 ? fullText.slice(0, 32) + '…' : fullText);
			}
			if (facets) {
				parts.push(...this.facetSelectionLabels());
			}
			const join = this.t('app.content-browser.smartFolders.nameJoin', undefined, ' · ');
			const folder = {
				id: this.smartFolderNextID++,
				name: `${parts.join(join)} — ${this.currentPath}`,
				path: this.currentPath,
				schemaKey: this.xpathSelectedSchema,
				conditions: JSON.parse(JSON.stringify(this.xpathConditions)),
				fullTextKeyword: fullText,
				...(facets ? { facets } : {}),
			};
			this.smartFolders.push(folder);
			this.persistSmartFolders();
		},
		// The selected facets as the labels the sidebar shows them with, in
		// the sidebar's order.
		facetSelectionLabels(): string[] {
			const s = this.facetSelection;
			const labels: string[] = [];
			if (s.kind) labels.push(this.t('app.content-browser.facet.kind.' + s.kind));
			if (this.facetShowsOrientation) {
				labels.push(...s.orientation.map((v: string) => this.t('app.content-browser.facet.orientation.' + v)));
			}
			if (this.facetShowsFormat) {
				labels.push(...s.format.map((v: string) => this.t('app.content-browser.facet.format.' + v)));
			}
			labels.push(...s.color.map((v: string) => this.t('app.content-browser.color.' + v)));
			labels.push(...s.tag.map((v: string) => '#' + v));
			return labels;
		},
		// A saved facet selection, made safe to restore: only the known
		// dimensions, each of the type the sidebar expects.
		_facetSelectionFrom(saved: any) {
			const selection = emptyFacetSelection();
			if (!saved || typeof saved !== 'object') return selection;
			const strings = (v: any) => Array.isArray(v) ? v.filter((e: any) => typeof e === 'string') : [];
			selection.kind = typeof saved.kind === 'string' && FACET_KINDS.includes(saved.kind) ? saved.kind : '';
			selection.orientation = strings(saved.orientation);
			selection.format = strings(saved.format);
			selection.color = strings(saved.color);
			selection.tag = strings(saved.tag);
			return selection;
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
			// The facets the folder was saved with; none for a folder saved
			// without any (or before facets existed).
			this._facetReset();
			this.facetSelection = this._facetSelectionFrom(folder.facets);
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
		// =====================================================================
		// List / grid view
		// =====================================================================
		setListViewMode(mode: 'list' | 'grid') {
			if (this.listViewMode === mode) return;
			this.listViewMode = mode;
			this.persistListViewState();
			this.scrollFocusedIntoView();
		},
		async persistListViewState() {
			const vm = this;
			const db = vm.instance?.api?.db;
			const userID = vm.instance?.currentUser?.id || '*';
			if (!db) return;
			try {
				await db.setUserSetting(userID, 'content-browser', 'listView', { mode: vm.listViewMode });
			} catch (e) {
				// Silently ignore errors
			}
		},
		async loadListViewState() {
			const vm = this;
			const db = vm.instance.api.db;
			const userID = vm.instance.currentUser?.id || '*';
			try {
				const state = await db.getUserSetting(userID, 'content-browser', 'listView');
				if (state && (state.mode === 'list' || state.mode === 'grid')) {
					vm.listViewMode = state.mode;
				}
			} catch (e) {
				// Silently ignore errors
			}
		},
		// The sort menu of the grid view, which has no column headers to click.
		// Uses the desktop's context menu; the choice comes back through
		// handleContextMenuAction as 'sort:<column>' or 'sort:<direction>'.
		openGridSortMenu(event: MouseEvent) {
			const vm = this;
			const columns: [string, string][] = [
				['name', 'app.content-browser.column.name'],
				['date', 'app.content-browser.column.dateModified'],
				['modifiedBy', 'app.content-browser.column.modifiedBy'],
				['lockOwner', 'app.content-browser.column.lockOwner'],
				['version', 'app.content-browser.column.version'],
				['kind', 'app.content-browser.column.kind'],
				['size', 'app.content-browser.column.size'],
			];
			// `selected` puts the desktop menu's check mark on the current choice.
			const menuItems: { id: string; label: string; selected?: boolean; type?: string }[] = columns.map(([column, key]) => ({
				id: 'sort:' + column,
				label: vm.t(key),
				selected: vm.sortColumn === column,
			}));
			menuItems.push({ type: 'separator', id: '', label: '' });
			menuItems.push({ id: 'sort:asc', label: vm.t('app.content-browser.toolbar.sortAscending', undefined, 'Ascending'), selected: vm.sortDirection === 'asc' });
			menuItems.push({ id: 'sort:desc', label: vm.t('app.content-browser.toolbar.sortDescending', undefined, 'Descending'), selected: vm.sortDirection === 'desc' });

			// Below the button rather than at the pointer, like a dropdown.
			const rect = (event.currentTarget as HTMLElement | null)?.getBoundingClientRect();
			window.parent.postMessage({
				type: 'show-context-menu',
				x: rect ? rect.left : event.clientX,
				y: rect ? rect.bottom + 4 : event.clientY,
				items: menuItems,
				sourceAppId: vm.instance?.id
			}, window.location.origin);
		},
		// Sorts as a header click would: a search result the index can order
		// by is re-run in the new order, anything else is sorted in place.
		applySort(column: string, direction: string) {
			const vm = this;
			if (vm.xpathSearchActive && SEARCH_SORT_FIELDS[column]) {
				vm.sortColumn = column;
				vm.sortDirection = direction;
				vm.xpathExecuteSearch();
				return;
			}
			vm.sortItems(column, direction);
		},
		// The URL of a file's thumbnail (mi:thumbnail, through the download
		// servlet's property mode), '' when it has none. The version pins the
		// URL to the picture: a remade thumbnail gets a new URL, and one whose
		// version equals the file's modification time is cached immutably.
		thumbnailURL(item: any): string {
			if (!item?.downloadURL) return '';
			const base = item.downloadURL.replace(/[?&]attachment$/, '');
			if (item.cloud) {
				// The provider's thumbnail, through the cloud servlet; the etag
				// pins the URL to the content, as the version does below.
				if (!item.cloud.hasThumbnail) return '';
				return base + (base.includes('?') ? '&' : '?') + 'thumbnail=medium&v=' + encodeURIComponent(item.cloud.etag || '');
			}
			if (item.thumbnailVersion == null) return '';
			return base + (base.includes('?') ? '&' : '?') + 'property=' + encodeURIComponent(THUMBNAIL_PROPERTY) + '&v=' + item.thumbnailVersion;
		},
		// The file itself, pinned to its modification time as the Inspector's
		// preview is (see selectedItemPreviewURL there).
		previewURL(item: any): string {
			if (!item?.downloadURL) return '';
			const base = item.downloadURL.replace(/[?&]attachment$/, '');
			const v = item.cloud?.etag || (item.lastModified ? new Date(item.lastModified).getTime() : Date.now());
			return base + (base.includes('?') ? '&' : '?') + 'v=' + encodeURIComponent(String(v));
		},
		// What a grid tile shows: the thumbnail, or the image itself while it
		// has none, as an <img>; a video's first frame as a <video>; otherwise
		// the file's icon (a folder, any other kind, or a picture the browser
		// could not load).
		gridMediaKind(item: any): 'image' | 'video' | 'icon' {
			if (!item || item.isCollection || !item.downloadURL) return 'icon';
			if (this.gridMediaErrors.includes(item.id)) return 'icon';
			if (item.thumbnailVersion != null || item.cloud?.hasThumbnail) return 'image';
			const mimeType = item.mimeType || '';
			if (mimeType.startsWith('image/')) return 'image';
			if (mimeType.startsWith('video/')) return 'video';
			return 'icon';
		},
		gridImageURL(item: any): string {
			return this.thumbnailURL(item) || this.previewURL(item);
		},
		// The first frame. The time fragment makes a browser that shows nothing
		// at t=0 (Safari) seek to a frame.
		gridVideoURL(item: any): string {
			return this.previewURL(item) + '#t=0.1';
		},
		isAudioItem(item: any): boolean {
			return !!item && !item.isCollection && (item.mimeType || '').startsWith('audio/');
		},
		onGridMediaError(item: any) {
			if (item && !this.gridMediaErrors.includes(item.id)) {
				this.gridMediaErrors.push(item.id);
			}
		},
		// A <video> tile is rendered without a src and gets it (from data-src)
		// the first time it scrolls into view, so a folder of many videos does
		// not fetch every header at once.
		onGridVideoIntersection(entries: IntersectionObserverEntry[], $ctx: any) {
			const el = $ctx?.element as HTMLVideoElement | undefined;
			if (!el || !entries.some((e) => e.isIntersecting)) return;
			const src = el.dataset.src;
			if (src && !el.getAttribute('src')) {
				el.preload = 'metadata';
				el.src = src;
			}
		},
		// Tiles per row of the grid, for Up / Down to step a whole row.
		gridColumnCount(): number {
			const grid = document.querySelector('.content-list .content-grid') as HTMLElement | null;
			if (!grid) return 1;
			const tracks = getComputedStyle(grid).gridTemplateColumns.split(' ').filter(Boolean).length;
			return Math.max(1, tracks);
		},
		// Sidebar panel methods
		toggleSidebarPanel() {
			this.sidebarPanelVisible = !this.sidebarPanelVisible;
			this.persistSidebarPanelState();
		},
		toggleSidebarSection(name: 'filter' | 'cloud' | 'favorites' | 'smartFolders' | 'xpathSearch') {
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

			// Ignore keydown events when focus is on an input element. A
			// contenteditable box counts too: the inspector's chat composer is
			// one (lib/mention-editor.ts), and its Backspace, Enter, arrows and
			// Ctrl+A/C/V must edit the message, not the list.
			const target = event.target as HTMLElement;
			if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT' || target.isContentEditable) {
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
			// Alt+Up / Backspace: the parent folder. Back and Forward already
			// have Alt+Left / Alt+Right, so Backspace takes the one move the
			// history keys do not cover; it reads as deleting the last segment
			// of the breadcrumb. Checked before the plain Arrow keys so Alt+Up
			// never steps the cursor instead.
			if ((event.altKey && event.key === 'ArrowUp') || (!event.altKey && !event.ctrlKey && !event.metaKey && event.key === 'Backspace')) {
				if (vm.canGoToParent) {
					event.preventDefault();
					vm.goToParent();
				}
				return;
			}
			// Enter: open the cursor row, exactly as a double-click does. Only
			// for a single selection, since each file opens an app window. Left
			// alone when a button or link has focus (Enter must still press it).
			if (event.key === 'Enter' && !event.altKey && !event.ctrlKey && !event.metaKey && !event.shiftKey) {
				if (target.tagName === 'BUTTON' || target.tagName === 'A') return;
				if (vm.selectedItems.length === 1) {
					const item = vm.items.find((i: any) => i.id === vm.selectedItems[0]);
					if (item) {
						event.preventDefault();
						vm.openItem(item);
					}
				}
				return;
			}

			// Arrow Up/Down: move the selection through the list. Shift+Arrow
			// extends the range from the anchor, Ctrl+Arrow moves only the
			// cursor (pick rows with Space). preventDefault stops the list
			// pane from also scrolling under the cursor.
			if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
				if ((vm.filteredItems as any[]).length > 0) {
					event.preventDefault();
					// In the grid a row is a row of tiles, so Up / Down step a whole row.
					const step = vm.listViewMode === 'grid' ? vm.gridColumnCount() : 1;
					vm.moveSelection((event.key === 'ArrowDown' ? 1 : -1) * step,
						event.shiftKey, event.ctrlKey || event.metaKey);
				}
				return;
			}
			// Left / Right: the neighbouring tile of the grid (Alt+Arrow, the
			// history, was taken above). The list has nothing beside a row.
			if (vm.listViewMode === 'grid' && (event.key === 'ArrowLeft' || event.key === 'ArrowRight')) {
				if ((vm.filteredItems as any[]).length > 0) {
					event.preventDefault();
					vm.moveSelection(event.key === 'ArrowRight' ? 1 : -1,
						event.shiftKey, event.ctrlKey || event.metaKey);
				}
				return;
			}
			// Home / End: jump to the first / last row, same modifiers as above.
			if (event.key === 'Home' || event.key === 'End') {
				if ((vm.filteredItems as any[]).length > 0) {
					event.preventDefault();
					vm.jumpSelection(event.key === 'End' ? 1 : -1,
						event.shiftKey, event.ctrlKey || event.metaKey);
				}
				return;
			}
			// Space: toggle the cursor row in and out of the selection. Left
			// alone when a button has focus (Space must still press it) or when
			// no row has the cursor — then the key keeps its scroll default.
			if (event.key === ' ') {
				if (target.tagName === 'BUTTON' || target.tagName === 'A') return;
				if (vm.visibleIndexOf(vm.focusedItemID) >= 0) {
					event.preventDefault();
					vm.toggleFocused();
				}
				return;
			}
			// Ctrl+A: select all visible rows
			if ((event.ctrlKey || event.metaKey) && event.key === 'a') {
				if ((vm.filteredItems as any[]).length > 0) {
					event.preventDefault();
					vm.selectAllVisible();
				}
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

// Mount immediately. The screen itself is behind the readiness gate
// (<template v-if="isReady"> in index.html), which appLaunch opens once the
// component templates are loaded — so mounting does not have to wait on a
// fetch, and window.appLaunch is defined the moment the iframe finishes
// loading.
import { VDOM } from '@mintjamsinc/ichigojs';
VDOM.createApp(App).mount('#app');
