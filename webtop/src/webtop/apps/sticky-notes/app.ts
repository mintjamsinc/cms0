/**
 * Sticky Notes Application
 *
 * The window lists every note on the desktop, so one hidden under windows can
 * be found: a click shows it above the windows until a window is used again.
 * New notes are placed from here or from "Add Widget…" on the desktop. The
 * notes themselves are the `note` widget (widget.ts); their text is read from
 * the files in notes.ts, their places from the shell.
 */

import { VDOM } from '@mintjamsinc/ichigojs';
import type { ApplicationInstance, WidgetLayer } from '../../services/webtop-service.js';
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
} from '../../composables/use-localization.js';
import { NoteStore, NOTE_TEXT_COLOR, noteColorValue, emptyNote } from './notes.js';

const NOTE_WIDGET = 'note';
// Typing in a note saves every few hundred milliseconds; the list follows
// at a calmer pace.
const REFRESH_DELAY_MS = 800;

interface NoteRow {
	id: string;
	layer: WidgetLayer;
	text: string;
	color: string;
	updatedAt: string;
}

let store: NoteStore | null = null;
let refreshTimer: ReturnType<typeof setTimeout> | null = null;
let refreshSeq = 0;
let unwatchFolder: (() => void) | null = null;
let unsubscribeWidgets: (() => void) | null = null;

const App = {
	data() {
		return {
			instance: null as ApplicationInstance | null,
			messageListener: null as ((event: MessageEvent) => void) | null,
			localization: createLocalizationSnapshot(),
			isReady: false,
			loading: true,
			notes: [] as NoteRow[],
			search: '',
			textColor: NOTE_TEXT_COLOR,
		};
	},
	computed: {
		filteredNotes(): NoteRow[] {
			const q = this.search.trim().toLowerCase();
			return q ? this.notes.filter((n: NoteRow) => n.text.toLowerCase().includes(q)) : this.notes;
		},
	},
	methods: {
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.instance, messageId, params, fallback);
		},

		onMounted() {
			const vm = this;

			vm.messageListener = (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const { type, ...payload } = event.data || {};
				if (handleLocalizationMessage(type, vm.localization, vm.instance)) return;
				if (type === 'theme-changed') {
					document.documentElement.dataset.theme = payload.theme;
				}
			};
			window.addEventListener('message', vm.messageListener);

			window.appLaunch = async (instance: ApplicationInstance) => {
				vm.instance = this.$markRaw(instance);
				refreshLocalization(vm.localization, vm.instance);
				document.documentElement.dataset.theme = instance.api.theme.currentTheme || 'light';

				store = new NoteStore({ userId: instance.currentUser?.id || '', content: instance.api.systemContent });
				unsubscribeWidgets = instance.onWidgetsChanged(() => vm.scheduleRefresh());
				try {
					unwatchFolder = instance.api.systemEventHub.watchNode(store.folder, () => vm.scheduleRefresh(), true);
				} catch (e) {
					console.warn('[Sticky Notes] Note changes cannot be watched:', e);
				}
				instance.setBeforeCloseCallback(() => {
					vm.stopWatching();
					return true;
				});

				vm.isReady = true;
				vm.$nextTick(() => instance.notifyLaunched());
				await vm.refresh();
			};
		},
		onUnmount() {
			if (this.messageListener) window.removeEventListener('message', this.messageListener);
			this.stopWatching();
		},
		stopWatching() {
			unsubscribeWidgets?.();
			unsubscribeWidgets = null;
			unwatchFolder?.();
			unwatchFolder = null;
			if (refreshTimer) {
				clearTimeout(refreshTimer);
				refreshTimer = null;
			}
		},

		// --- window controls ------------------------------------------------

		onMinimizeWindow() {
			this.instance?.minimize();
		},
		onToggleMaximizeWindow() {
			this.instance?.toggleMaximize();
		},
		onCloseWindow() {
			this.instance?.requestClose();
		},

		// --- the list -------------------------------------------------------

		scheduleRefresh() {
			if (refreshTimer) clearTimeout(refreshTimer);
			refreshTimer = setTimeout(() => {
				refreshTimer = null;
				this.refresh();
			}, REFRESH_DELAY_MS);
		},
		/** The notes on the desktop (the shell's placements) with their text, newest edit first. */
		async refresh() {
			const vm = this;
			if (!vm.instance || !store) return;
			const seq = ++refreshSeq;
			const [placements, files] = await Promise.all([vm.instance.listWidgets(), store.list()]);
			if (seq !== refreshSeq) return;
			vm.notes = placements
				.filter((p) => p.widget === NOTE_WIDGET)
				.map((p) => {
					const note = files.get(p.id) || emptyNote();
					return { id: p.id, layer: p.layer, text: note.text, color: note.color, updatedAt: note.updatedAt };
				})
				// Empty notes (never written) last; otherwise the latest edit first.
				.sort((a, b) => (b.updatedAt || '').localeCompare(a.updatedAt || ''));
			vm.loading = false;
		},
		async newNote() {
			const vm = this;
			if (!vm.instance) return;
			const id = await vm.instance.addWidget(NOTE_WIDGET);
			if (id) vm.instance.revealWidget(id);
		},
		reveal(note: NoteRow) {
			this.instance?.revealWidget(note.id);
		},
		togglePin(note: NoteRow) {
			this.instance?.setWidgetLayer(note.id, note.layer === 'pinned' ? 'desktop' : 'pinned');
		},
		noteColor(note: NoteRow): string {
			return noteColorValue(note.color);
		},
		formatTime(iso: string): string {
			if (!iso) return '';
			try {
				return new Date(iso).toLocaleString(this.localization.locale || undefined, {
					timeZone: this.localization.timeZone || undefined,
					dateStyle: 'medium',
					timeStyle: 'short',
				});
			} catch {
				return iso;
			}
		},
	},
};

VDOM.createApp(App).mount('#app');
