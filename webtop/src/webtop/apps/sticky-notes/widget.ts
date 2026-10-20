/**
 * Sticky Notes — the `note` widget.
 *
 * One note on the desktop. The shell places it and draws the grip; this page
 * is the note itself: its text, its colour, and the question asked before a
 * note with text is removed.
 *
 * The text is the same Tiptap editor as the Memo app, cut down to what a
 * note needs: lists, checkboxes, bold, strike-through, one heading level, a
 * quotation. There is no toolbar, menu or slash command — a decoration comes
 * from typing it, as in Memo: "- " starts a list, "[ ] " a checkbox, "# " a
 * heading, "> " a quotation, **bold**, ~~done~~, Ctrl+B / Ctrl+I. Text
 * pasted from a memo keeps its decorations.
 *
 * The note is saved a moment after typing stops (and when the note loses
 * focus) to the placement's file (notes.ts), and a change made to that file
 * in another browser is shown here unless the note is being edited.
 */

import { VDOM } from '@mintjamsinc/ichigojs';
import { Editor } from '@tiptap/core';
import { Placeholder } from '@tiptap/extension-placeholder';
import type { WidgetInstance } from '../../services/webtop-service.js';
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
} from '../../composables/use-localization.js';
import {
	NoteStore,
	NOTE_COLORS,
	NOTE_TEXT_COLOR,
	NOTE_FORMAT_VERSION,
	DEFAULT_NOTE_COLOR,
	noteColorValue,
	noteContent,
	type Note,
	type NoteDoc,
} from './notes.js';
import { noteExtensions } from './note-editor.js';

const SAVE_DELAY_MS = 600;
const COLOR_ACTION = 'color:';

// Outside reactive data: the editor, the store and the timers are plain
// objects and functions (see the radio app for the same split).
let editor: Editor | null = null;
let store: NoteStore | null = null;
let saveTimer: ReturnType<typeof setTimeout> | null = null;
let saving: Promise<void> | null = null;
let unwatch: (() => void) | null = null;
let removeAnswer: ((ok: boolean) => void) | null = null;
// Written into every save, so the change event of our own save is not
// mistaken for an edit made elsewhere.
const writerId = crypto.randomUUID();

/**
 * The note as plain text for the list window: one line per paragraph, list
 * item or heading, without the blank lines Tiptap's getText() puts between
 * nested blocks, and without trailing empty lines.
 */
function plainText(ed: Editor): string {
	const lines: string[] = [];
	ed.state.doc.descendants((node) => {
		if (node.isTextblock) {
			lines.push(node.textContent);
			return false;
		}
		return true;
	});
	return lines.join('\n').replace(/\n+$/, '');
}

const Widget = {
	data() {
		return {
			widget: null as WidgetInstance | null,
			messageListener: null as ((event: MessageEvent) => void) | null,
			pageHideListener: null as (() => void) | null,
			localization: createLocalizationSnapshot(),
			isReady: false,
			color: DEFAULT_NOTE_COLOR,
			// Changed here and not yet saved.
			dirty: false,
			// Changed elsewhere while this note was being edited; read again on blur.
			remoteChanged: false,
			confirmingRemove: false,
		};
	},
	computed: {
		noteStyle() {
			return { background: noteColorValue(this.color), color: NOTE_TEXT_COLOR };
		},
	},
	methods: {
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.widget, messageId, params, fallback);
		},

		onMounted() {
			const vm = this;

			vm.messageListener = (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const { type, ...payload } = event.data || {};
				if (handleLocalizationMessage(type, vm.localization, vm.widget)) return;
				if (type === 'theme-changed') {
					document.documentElement.dataset.theme = payload.theme;
				} else if (type === 'context-menu-action' && typeof payload.action === 'string' && payload.action.startsWith(COLOR_ACTION)) {
					vm.applyColor(payload.action.substring(COLOR_ACTION.length));
				}
			};
			window.addEventListener('message', vm.messageListener);
			vm.pageHideListener = () => { vm.flush(); };
			window.addEventListener('pagehide', vm.pageHideListener);

			window.widgetLaunch = async (widget: WidgetInstance) => {
				vm.widget = this.$markRaw(widget);
				refreshLocalization(vm.localization, vm.widget);
				document.documentElement.dataset.theme = widget.api.theme.currentTheme || 'light';

				store = new NoteStore({ userId: widget.currentUser?.id || '', content: widget.api.systemContent });
				const note = await store.load(widget.id);
				if (note) vm.color = note.color;

				try {
					unwatch = widget.api.systemEventHub.watchNode(store.pathOf(widget.id), () => vm.onFileChanged(), true);
				} catch (e) {
					console.warn('[Sticky Notes] Changes from other browsers cannot be watched:', e);
				}

				widget.setBeforeRemoveCallback(async () => {
					if (editor && !editor.isEmpty && !(await vm.askRemove())) return false;
					if (saveTimer) {
						clearTimeout(saveTimer);
						saveTimer = null;
					}
					vm.dirty = false;
					unwatch?.();
					unwatch = null;
					await saving?.catch(() => { /* deleted below anyway */ });
					await store?.delete(widget.id);
					return true;
				});

				vm.isReady = true;
				// The editor's host is inside the readiness gate, so it exists
				// only after this render.
				vm.$nextTick(() => {
					vm.createEditor(noteContent(note));
					widget.notifyLaunched();
				});
			};
		},
		onUnmount() {
			if (this.messageListener) window.removeEventListener('message', this.messageListener);
			if (this.pageHideListener) window.removeEventListener('pagehide', this.pageHideListener);
			unwatch?.();
			unwatch = null;
			editor?.destroy();
			editor = null;
		},

		// --- the editor -----------------------------------------------------

		createEditor(content: NoteDoc) {
			const vm = this;
			const host = vm.$refs.editorHost as HTMLElement | undefined;
			if (!host || editor) return;
			editor = new Editor({
				element: host,
				extensions: [
					...noteExtensions(),
					Placeholder.configure({
						placeholder: () => vm.t('app.sticky-notes.note.placeholder', undefined, 'Write a note… (- for a list, [ ] for a checkbox)'),
					}),
				],
				content,
				autofocus: false,
				editorProps: {
					attributes: { spellcheck: 'false' },
				},
				onUpdate: () => vm.onInput(),
				onBlur: () => vm.onBlur(),
			});
		},

		// --- saving ---------------------------------------------------------

		onInput() {
			this.dirty = true;
			this.scheduleSave();
		},
		onBlur() {
			this.flush().then(() => {
				if (this.remoteChanged) this.onFileChanged();
			});
		},
		scheduleSave() {
			if (saveTimer) clearTimeout(saveTimer);
			saveTimer = setTimeout(() => {
				saveTimer = null;
				this.flush();
			}, SAVE_DELAY_MS);
		},
		async flush(): Promise<void> {
			const vm = this;
			if (saveTimer) {
				clearTimeout(saveTimer);
				saveTimer = null;
			}
			if (saving) await saving.catch(() => { /* the save below carries the newer text */ });
			if (!vm.dirty || !store || !vm.widget || !editor) return;
			vm.dirty = false;
			const note: Note = {
				version: NOTE_FORMAT_VERSION,
				text: plainText(editor),
				doc: editor.getJSON() as NoteDoc,
				color: vm.color,
				updatedAt: new Date().toISOString(),
				writer: writerId,
			};
			saving = store.save(vm.widget.id, note);
			try {
				await saving;
			} catch (e) {
				console.warn('[Sticky Notes] The note could not be saved:', e);
				vm.dirty = true;
			} finally {
				saving = null;
			}
		},

		/** The file changed: our own save echoing back, or an edit in another browser. */
		async onFileChanged() {
			const vm = this;
			if (!store || !vm.widget) return;
			// The cursor is in the note (the window itself may be behind another).
			const editing = !!editor && document.activeElement === editor.view.dom;
			if (vm.dirty || editing) {
				vm.remoteChanged = true;
				return;
			}
			vm.remoteChanged = false;
			const note = await store.load(vm.widget.id);
			if (!note || note.writer === writerId || vm.dirty) return;
			vm.color = note.color;
			editor?.commands.setContent(noteContent(note), { emitUpdate: false });
		},

		// --- colour ---------------------------------------------------------

		openColorMenu(event: MouseEvent) {
			const vm = this;
			const items = NOTE_COLORS.map((c) => ({
				id: COLOR_ACTION + c.key,
				label: vm.t('app.sticky-notes.color.' + c.key, undefined, c.label),
				swatch: c.value,
				selected: c.key === vm.color,
			}));
			const btn = (event.currentTarget as HTMLElement) || (event.target as HTMLElement);
			const r = btn.getBoundingClientRect();
			window.parent.postMessage({
				type: 'show-context-menu',
				x: r.left,
				y: r.bottom + 4,
				variant: 'swatch-grid',
				columns: 6,
				items,
				sourceAppId: vm.widget?.id,
			}, window.location.origin);
		},
		applyColor(key: string) {
			if (!NOTE_COLORS.some((c) => c.key === key) || key === this.color) return;
			this.color = key;
			this.dirty = true;
			this.flush();
		},

		// --- removal --------------------------------------------------------

		askRemove(): Promise<boolean> {
			this.confirmingRemove = true;
			return new Promise((resolve) => { removeAnswer = resolve; });
		},
		answerRemove(ok: boolean) {
			this.confirmingRemove = false;
			const answer = removeAnswer;
			removeAnswer = null;
			answer?.(ok);
		},
	},
};

VDOM.createApp(Widget).mount('#note');
