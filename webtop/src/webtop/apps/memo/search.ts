// ----------------------------------------------------------------------------
// Find & Replace for the Tiptap (ProseMirror) memo editor.
//
// The Text Editor leans on CodeMirror's built-in @codemirror/search; Tiptap has
// no equivalent, so this module provides the same feature set as a self-contained
// ProseMirror plugin plus a handful of command helpers the app calls from its
// sidebar form:
//
//   • highlight every match (and the "current" one distinctly) via decorations
//   • next / previous navigation that selects + scrolls the current match
//   • replace current / replace all
//   • match-case, whole-word and regular-expression options
//
// The plugin is inert until a non-empty query is set, so adding it to every
// editor (see app.ts buildExtensions) costs nothing while the sidebar is closed.
// ----------------------------------------------------------------------------
import { Extension } from "@tiptap/core";
import type { Editor } from "@tiptap/core";
import { Plugin, PluginKey, TextSelection } from "@tiptap/pm/state";
import type { Transaction } from "@tiptap/pm/state";
import { Decoration, DecorationSet } from "@tiptap/pm/view";
import type { EditorView } from "@tiptap/pm/view";
import type { Node as PMNode } from "@tiptap/pm/model";

export interface SearchOptions {
	caseSensitive: boolean;
	wholeWord: boolean;
	regex: boolean;
}

export interface SearchMatch {
	from: number;
	to: number;
}

interface SearchPluginState {
	query: string;
	options: SearchOptions;
	matches: SearchMatch[];
	// Index into `matches` of the highlighted "current" match, or -1 when the
	// search is fresh (the user has typed a query but not yet navigated).
	current: number;
	decorations: DecorationSet;
}

interface SetMeta {
	type: "set";
	query: string;
	options: SearchOptions;
}

interface CurrentMeta {
	type: "current";
	current: number;
}

type SearchMeta = SetMeta | CurrentMeta;

export const searchPluginKey = new PluginKey<SearchPluginState>("memoSearch");

const DEFAULT_OPTIONS: SearchOptions = {
	caseSensitive: false,
	wholeWord: false,
	regex: false,
};

function sameOptions(a: SearchOptions, b: SearchOptions): boolean {
	return a.caseSensitive === b.caseSensitive
		&& a.wholeWord === b.wholeWord
		&& a.regex === b.regex;
}

function escapeRegExp(s: string): string {
	return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

// Build the matcher for the query. Returns null for an empty query or an
// invalid user-supplied regular expression (callers treat null as "no matches",
// so a half-typed regex simply shows nothing rather than throwing).
function buildRegex(query: string, options: SearchOptions, global: boolean): RegExp | null {
	if (!query) return null;
	let pattern = options.regex ? query : escapeRegExp(query);
	if (options.wholeWord) pattern = `\\b(?:${pattern})\\b`;
	let flags = global ? "g" : "";
	if (!options.caseSensitive) flags += "i";
	try {
		return new RegExp(pattern, flags);
	} catch {
		return null;
	}
}

// Collect match ranges across the document. Adjacent text nodes (a single word
// can be split into several nodes when only part of it carries a mark, e.g.
// "wor**d**") are concatenated into one run so matches that span them are still
// found; runs reset at every non-text node, so matches never cross a hard break
// or block boundary. Positions stay valid because sibling text nodes are
// contiguous: a run starting at doc position P maps run-offset k to P + k.
function computeMatches(doc: PMNode, regex: RegExp | null): SearchMatch[] {
	const results: SearchMatch[] = [];
	if (!regex) return results;

	const runs: { text: string; pos: number }[] = [];
	let lastWasText = false;
	doc.descendants((node, pos) => {
		if (node.isText) {
			const text = node.text || "";
			if (lastWasText && runs.length > 0) {
				runs[runs.length - 1].text += text;
			} else {
				runs.push({ text, pos });
			}
			lastWasText = true;
		} else {
			lastWasText = false;
		}
		return true;
	});

	for (const run of runs) {
		regex.lastIndex = 0;
		let m: RegExpExecArray | null;
		// eslint-disable-next-line no-cond-assign
		while ((m = regex.exec(run.text)) !== null) {
			if (m[0].length === 0) {
				// Zero-width match (e.g. "a*"): advance to avoid an infinite loop.
				regex.lastIndex += 1;
				continue;
			}
			results.push({ from: run.pos + m.index, to: run.pos + m.index + m[0].length });
		}
	}
	return results;
}

function buildDecorations(doc: PMNode, matches: SearchMatch[], current: number): DecorationSet {
	if (matches.length === 0) return DecorationSet.empty;
	const decos = matches.map((m, i) => Decoration.inline(m.from, m.to, {
		class: i === current ? "memo-search-match memo-search-match-current" : "memo-search-match",
	}));
	return DecorationSet.create(doc, decos);
}

function clampIndex(index: number, length: number): number {
	if (length === 0) return -1;
	return ((index % length) + length) % length;
}

function emptyState(): SearchPluginState {
	return {
		query: "",
		options: { ...DEFAULT_OPTIONS },
		matches: [],
		current: -1,
		decorations: DecorationSet.empty,
	};
}

function memoSearchPlugin(): Plugin<SearchPluginState> {
	return new Plugin<SearchPluginState>({
		key: searchPluginKey,
		state: {
			init: emptyState,
			apply(tr: Transaction, value: SearchPluginState, _oldState, newState): SearchPluginState {
				const meta = tr.getMeta(searchPluginKey) as SearchMeta | undefined;
				const setMeta = meta && meta.type === "set" ? meta : null;
				const currentMeta = meta && meta.type === "current" ? meta : null;

				// A 'set' meta overrides the query/options; otherwise they carry over.
				const query = setMeta ? (setMeta.query || "") : value.query;
				const options = setMeta ? setMeta.options : value.options;
				// The query/options genuinely changed (vs. an idempotent re-apply that
				// the app fires before each navigation).
				const queryChanged = setMeta != null
					&& (setMeta.query !== value.query || !sameOptions(setMeta.options, value.options));

				// Recompute matches whenever the query/options or the document change.
				const needRecompute = setMeta != null || tr.docChanged;
				if (!needRecompute && !currentMeta) return value;
				const matches = needRecompute
					? computeMatches(newState.doc, buildRegex(query, options, true))
					: value.matches;

				// Resolve the highlighted match. An explicit 'current' meta wins
				// (navigation / replace); a real query change clears it; otherwise the
				// previous index is kept while it still points at a live match.
				let current: number;
				if (currentMeta) {
					current = currentMeta.current < 0 ? -1 : clampIndex(currentMeta.current, matches.length);
				} else if (queryChanged) {
					current = -1;
				} else if (value.current >= 0 && value.current < matches.length) {
					current = value.current;
				} else {
					current = -1;
				}

				return { query, options, matches, current, decorations: buildDecorations(newState.doc, matches, current) };
			},
		},
		props: {
			decorations(state) {
				return searchPluginKey.getState(state)?.decorations ?? null;
			},
		},
	});
}

// Tiptap extension wrapper — added to every editor in app.ts buildExtensions().
export function createSearchExtension(): Extension {
	return Extension.create({
		name: "memoSearch",
		addProseMirrorPlugins() {
			return [memoSearchPlugin()];
		},
	});
}

export function getMemoSearchState(editor: Editor): SearchPluginState | null {
	return searchPluginKey.getState(editor.state) ?? null;
}

// Set (or clear, with an empty query) the active search. Highlights update but
// the selection is left alone — navigation is what moves and scrolls the caret.
export function setMemoSearch(editor: Editor, query: string, options: SearchOptions): void {
	const { state, view } = editor;
	view.dispatch(state.tr.setMeta(searchPluginKey, { type: "set", query, options } as SetMeta));
}

export function clearMemoSearch(editor: Editor): void {
	setMemoSearch(editor, "", { ...DEFAULT_OPTIONS });
}

// Bring a document position into view inside the editor's scroll container
// (#editor-host). The transaction's own scrollIntoView() is unreliable here:
// focus sits in the sidebar's Find field, not the editor, so we scroll the
// match's DOM node ourselves. `block: "nearest"` scrolls the minimum needed —
// off-screen matches come into view, already-visible ones don't jump.
function scrollPosIntoView(view: EditorView, pos: number): void {
	try {
		const { node } = view.domAtPos(pos);
		const el = (node && node.nodeType === Node.TEXT_NODE ? node.parentNode : node) as Element | null;
		if (el && typeof el.scrollIntoView === "function") {
			el.scrollIntoView({ block: "nearest", inline: "nearest" });
		}
	} catch {
		/* best-effort — never let a scroll failure break navigation */
	}
}

// Select + scroll the match at `index` (wraps around) and mark it current.
function goToMatch(editor: Editor, index: number): boolean {
	const st = getMemoSearchState(editor);
	if (!st || st.matches.length === 0) return false;
	const i = clampIndex(index, st.matches.length);
	const m = st.matches[i];
	const { state, view } = editor;
	const tr = state.tr.setMeta(searchPluginKey, { type: "current", current: i } as CurrentMeta);
	tr.setSelection(TextSelection.create(tr.doc, m.from, m.to));
	view.dispatch(tr);
	// Scroll after dispatch so the updated decoration/selection DOM is in place.
	scrollPosIntoView(view, m.from);
	return true;
}

export function memoSearchNext(editor: Editor): boolean {
	const st = getMemoSearchState(editor);
	if (!st || st.matches.length === 0) return false;
	if (st.current < 0) {
		// Fresh search: jump to the first match at or after the caret.
		const pos = editor.state.selection.from;
		let idx = st.matches.findIndex((m) => m.from >= pos);
		if (idx < 0) idx = 0;
		return goToMatch(editor, idx);
	}
	return goToMatch(editor, st.current + 1);
}

export function memoSearchPrev(editor: Editor): boolean {
	const st = getMemoSearchState(editor);
	if (!st || st.matches.length === 0) return false;
	if (st.current < 0) {
		// Fresh search: jump to the last match at or before the caret.
		const pos = editor.state.selection.from;
		let idx = -1;
		for (let i = st.matches.length - 1; i >= 0; i--) {
			if (st.matches[i].to <= pos) { idx = i; break; }
		}
		if (idx < 0) idx = st.matches.length - 1;
		return goToMatch(editor, idx);
	}
	return goToMatch(editor, st.current - 1);
}

// Compute the replacement string for one match. Literal in plain mode; in regex
// mode the matched slice is re-run through the (non-global) pattern so $1/$& and
// friends in the replacement expand against this match's capture groups.
function computeReplacement(doc: PMNode, match: SearchMatch, query: string, options: SearchOptions, replacement: string): string {
	if (!options.regex) return replacement;
	const re = buildRegex(query, options, false);
	if (!re) return replacement;
	const matched = doc.textBetween(match.from, match.to);
	try {
		return matched.replace(re, replacement);
	} catch {
		return replacement;
	}
}

// Replace the current match (or, for a fresh search, the first match at/after
// the caret), then advance to the following match. Returns false when there is
// nothing to replace.
export function memoReplaceCurrent(editor: Editor, replacement: string): boolean {
	const st = getMemoSearchState(editor);
	if (!st || st.matches.length === 0) return false;
	let i = st.current;
	if (i < 0) {
		const pos = editor.state.selection.from;
		i = st.matches.findIndex((m) => m.from >= pos);
		if (i < 0) i = 0;
	}
	const m = st.matches[i];
	const { state, view } = editor;
	const replText = computeReplacement(state.doc, m, st.query, st.options, replacement);
	view.dispatch(state.tr.insertText(replText, m.from, m.to));

	// The plugin has re-found matches against the new document; land on the next
	// one at or after the just-inserted text.
	const after = getMemoSearchState(editor);
	if (after && after.matches.length > 0) {
		const pos = m.from + replText.length;
		let next = after.matches.findIndex((x) => x.from >= pos);
		if (next < 0) next = 0;
		goToMatch(editor, next);
	}
	return true;
}

// Replace every match in one transaction. Edits are applied from the end of the
// document backwards so earlier match positions stay valid. Returns the count.
export function memoReplaceAll(editor: Editor, replacement: string): number {
	const st = getMemoSearchState(editor);
	if (!st || st.matches.length === 0) return 0;
	const { state, view } = editor;
	const tr = state.tr;
	for (let i = st.matches.length - 1; i >= 0; i--) {
		const m = st.matches[i];
		const replText = computeReplacement(state.doc, m, st.query, st.options, replacement);
		tr.insertText(replText, m.from, m.to);
	}
	const count = st.matches.length;
	tr.setMeta(searchPluginKey, { type: "current", current: -1 } as CurrentMeta);
	view.dispatch(tr);
	return count;
}
