// <wt-cloud-picker> — picks files, or a folder, of the user's Google Drive or
// OneDrive (the files bundle of a linked account).
//
//     <wt-cloud-picker v-model:open="cloudPicker.visible" :service="instance.api.cloud"
//         :provider="cloudPicker.provider" :provider-name="cloudPicker.providerName"
//         :mode="cloudPicker.mode" :multiple="true" :title="t('...')" :labels="cloudPickerLabels"
//         @select="onCloudPicked($event.detail)"></wt-cloud-picker>
//
// Google reaches only what the user picks for the app (the drive.file scope),
// so for Google the provider's own picker is run in the browser: `service`
// hands over the token and keys (getPicker) and the Google Picker script is
// loaded on first use. When it cannot run (no API key configured) the
// component falls back to browsing with listChildren, which then shows only
// the files this app created. OneDrive is always browsed here: the list is
// read a folder at a time with listChildren.
//
// `mode` is `files` (pick one or more files) or `folder` (pick a destination
// folder; the drive's root counts). `select` carries an array of entries
// ({provider, id, name, mimeType, size, folder, webUrl}); in folder mode one
// entry, whose id is null for the root. `labels` keys (all optional, English
// fallbacks): select, cancel, loading, empty, root, openingPicker, up.

import { defineComponent } from '@mintjamsinc/ichigojs';

interface Entry {
	provider: string;
	id: string | null;
	name: string;
	mimeType: string | null;
	size: number | null;
	folder: boolean;
	webUrl: string | null;
	nativeType?: string | null;
}

const GOOGLE_API_SCRIPT = 'https://apis.google.com/js/api.js';
const GOOGLE_NATIVE_PREFIX = 'application/vnd.google-apps.';
const GOOGLE_FOLDER = 'application/vnd.google-apps.folder';

let googleApiLoading: Promise<void> | null = null;

/** Loads the Google API loader and its picker module once per document. */
function loadGooglePicker(): Promise<void> {
	if (googleApiLoading) {
		return googleApiLoading;
	}
	googleApiLoading = new Promise<void>((resolve, reject) => {
		const w = window as any;
		const whenLoaded = () => {
			if (!w.gapi) {
				reject(new Error('The Google API loader did not load.'));
				return;
			}
			w.gapi.load('picker', { callback: () => resolve(), onerror: () => reject(new Error('The Google Picker did not load.')) });
		};
		if (w.gapi) {
			whenLoaded();
			return;
		}
		const script = document.createElement('script');
		script.src = GOOGLE_API_SCRIPT;
		script.async = true;
		script.onload = whenLoaded;
		script.onerror = () => {
			googleApiLoading = null;
			reject(new Error('The Google API loader could not be loaded.'));
		};
		document.head.appendChild(script);
	});
	return googleApiLoading;
}

function iconFor(entry: Entry): string {
	if (entry.folder) return 'bi-folder';
	const type = entry.mimeType || '';
	if (type.startsWith(GOOGLE_NATIVE_PREFIX)) {
		const kind = type.substring(GOOGLE_NATIVE_PREFIX.length);
		if (kind === 'document') return 'bi-file-earmark-text';
		if (kind === 'spreadsheet') return 'bi-file-earmark-spreadsheet';
		if (kind === 'presentation') return 'bi-file-earmark-slides';
		if (kind === 'drawing') return 'bi-file-earmark-image';
		return 'bi-file-earmark';
	}
	if (type.startsWith('image/')) return 'bi-file-earmark-image';
	if (type.startsWith('video/')) return 'bi-file-earmark-play';
	if (type.startsWith('audio/')) return 'bi-file-earmark-music';
	if (type === 'application/pdf') return 'bi-file-earmark-pdf';
	if (type.includes('spreadsheet') || type.includes('excel') || type === 'text/csv') return 'bi-file-earmark-spreadsheet';
	if (type.includes('presentation') || type.includes('powerpoint')) return 'bi-file-earmark-slides';
	if (type.includes('word') || type.includes('opendocument.text') || type === 'application/rtf') return 'bi-file-earmark-word';
	if (type.includes('zip') || type.includes('compressed')) return 'bi-file-earmark-zip';
	if (type.startsWith('text/')) return 'bi-file-earmark-text';
	return 'bi-file-earmark';
}

function formatSize(size: number | null): string {
	if (size == null) return '';
	if (size < 1024) return `${size} B`;
	if (size < 1024 * 1024) return `${Math.round(size / 1024)} KB`;
	if (size < 1024 * 1024 * 1024) return `${(size / 1024 / 1024).toFixed(1)} MB`;
	return `${(size / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

defineComponent('wt-cloud-picker', {
	template: '#wt-cloud-picker',
	props: {
		/** Visibility (v-model:open). */
		open: { type: Boolean, default: false },
		/** A CloudServiceGraphQL: getPicker, listChildren. */
		service: { type: Object, default: null },
		/** google or microsoft. */
		provider: { type: String, default: '' },
		/** The provider's display name, for the root row and messages. */
		providerName: { type: String, default: '' },
		/** files | folder */
		mode: { type: String, default: 'files' },
		/** Lets several files be picked (files mode). */
		multiple: { type: Boolean, default: false },
		title: { type: String, default: '' },
		/** I18n labels (see the key list above). */
		labels: { type: Object, default: () => ({}) },
	},
	emits: ['update:open', 'select', 'close'],
	data() {
		// The service is a class instance with private fields; read through the
		// reactive proxy its methods would lose `this`. Marked raw, it is handed
		// back as it is. The parent marks it raw as well, for a service that
		// arrives after the mount.
		const self = this as any;
		if (self.service && self.$markRaw) {
			self.$markRaw(self.service);
		}
		return {
			/** Whether the dialog's own list is shown (false while the provider's picker runs). */
			browsing: false,
			/** The folders from the root down to the one listed; the root has a null id. */
			path: [] as Entry[],
			entries: [] as Entry[],
			selected: [] as string[],
			loading: false,
			error: '',
			openingPicker: false,
		};
	},
	computed: {
		current(this: any): Entry | null {
			return this.path.length ? this.path[this.path.length - 1] : null;
		},
		rows(this: any): Entry[] {
			if (this.mode === 'folder') {
				return (this.entries as Entry[]).filter((e) => e.folder);
			}
			return this.entries;
		},
		canSelect(this: any): boolean {
			if (this.loading || this.openingPicker) return false;
			if (this.mode === 'folder') return this.path.length > 0;
			return this.selected.length > 0;
		},
		selectLabel(this: any): string {
			if (this.mode === 'folder') {
				const node: Entry | null = this.selectedFolder();
				const name = node ? node.name : '';
				return this.text('selectFolder', 'Select {name}').replace('{name}', name);
			}
			return this.text('select', 'Select');
		},
	},
	watch: {
		open(this: any, value: boolean): void {
			if (value) {
				this.start();
			}
		},
	},
	methods: {
		text(this: any, key: string, fallback: string): string {
			return (this.labels && this.labels[key]) || fallback;
		},
		iconFor(entry: Entry): string {
			return 'bi ' + iconFor(entry);
		},
		formatSize(size: number | null): string {
			return formatSize(size);
		},
		rootEntry(this: any): Entry {
			return { provider: this.provider, id: null, name: this.text('root', this.providerName || this.provider), mimeType: null, size: null, folder: true, webUrl: null };
		},
		/** In folder mode: the selected folder row, or the folder being listed. */
		selectedFolder(this: any): Entry | null {
			if (this.selected.length === 1) {
				const row = (this.entries as Entry[]).find((e) => e.id === this.selected[0]);
				if (row && row.folder) return row;
			}
			return this.current;
		},
		async start(this: any): Promise<void> {
			this.error = '';
			this.entries = [];
			this.selected = [];
			this.path = [];
			this.browsing = false;
			this.openingPicker = false;
			if (!this.service || !this.provider) {
				this.browsing = true;
				return;
			}
			if (this.provider === 'google') {
				this.openingPicker = true;
				try {
					const picker = await this.service.getPicker(this.provider);
					if (picker && picker.available && picker.apiKey) {
						await this.runGooglePicker(picker);
						return;
					}
				} catch (e) {
					this.error = this.errorText(e);
					this.openingPicker = false;
					this.browsing = true;
					return;
				}
				this.openingPicker = false;
			}
			this.browsing = true;
			await this.navigate(this.rootEntry());
		},
		/** Runs the Google Picker; resolves when the user picked or cancelled. */
		async runGooglePicker(this: any, picker: { accessToken: string; apiKey: string; appId: string | null }): Promise<void> {
			await loadGooglePicker();
			const g = (window as any).google;
			const builder = new g.picker.PickerBuilder()
				.setOAuthToken(picker.accessToken)
				.setDeveloperKey(picker.apiKey)
				.setOrigin(`${window.location.protocol}//${window.location.host}`)
				.setLocale((document.documentElement.lang || navigator.language || 'en').substring(0, 2));
			if (picker.appId) {
				builder.setAppId(picker.appId);
			}
			if (this.mode === 'folder') {
				const view = new g.picker.DocsView(g.picker.ViewId.FOLDERS)
					.setSelectFolderEnabled(true)
					.setIncludeFolders(true)
					.setMimeTypes(GOOGLE_FOLDER);
				builder.addView(view);
			} else {
				builder.addView(new g.picker.DocsView().setIncludeFolders(true));
				builder.addView(g.picker.ViewId.DOCS);
				if (this.multiple) {
					builder.enableFeature(g.picker.Feature.MULTISELECT_ENABLED);
				}
			}
			builder.enableFeature(g.picker.Feature.SUPPORT_DRIVES);
			if (this.title) {
				builder.setTitle(this.title);
			}
			await new Promise<void>((resolve) => {
				builder.setCallback((data: any) => {
					const action = data && data.action;
					if (action === g.picker.Action.PICKED) {
						const docs: any[] = data.docs || [];
						const entries: Entry[] = docs.map((d) => ({
							provider: 'google',
							id: d.id,
							name: d.name,
							mimeType: d.mimeType || null,
							size: typeof d.sizeBytes === 'number' ? d.sizeBytes : null,
							folder: d.mimeType === GOOGLE_FOLDER,
							webUrl: d.url || null,
							nativeType: d.mimeType && d.mimeType.startsWith(GOOGLE_NATIVE_PREFIX) && d.mimeType !== GOOGLE_FOLDER
								? d.mimeType.substring(GOOGLE_NATIVE_PREFIX.length) : null,
						}));
						this.openingPicker = false;
						this.setOpen(false);
						this.$emit('select', entries);
						resolve();
					} else if (action === g.picker.Action.CANCEL) {
						this.openingPicker = false;
						this.setOpen(false);
						resolve();
					}
				});
				builder.build().setVisible(true);
			});
		},
		async navigate(this: any, folder: Entry): Promise<void> {
			if (!this.service) return;
			this.loading = true;
			this.error = '';
			this.selected = [];
			try {
				const entries: Entry[] = await this.service.listChildren(this.provider, folder.id);
				const at = (this.path as Entry[]).findIndex((p) => p.id === folder.id);
				this.path = at >= 0 ? this.path.slice(0, at + 1) : [...this.path, folder];
				this.entries = entries;
			} catch (e) {
				this.error = this.errorText(e);
			} finally {
				this.loading = false;
			}
		},
		up(this: any): void {
			if (this.path.length < 2) return;
			this.navigate(this.path[this.path.length - 2]);
		},
		onRowClick(this: any, entry: Entry, event: MouseEvent): void {
			if (!entry.id) return;
			if (entry.folder && this.mode !== 'folder') {
				this.navigate(entry);
				return;
			}
			if (this.mode === 'files' && this.multiple && (event.ctrlKey || event.metaKey)) {
				this.selected = this.selected.includes(entry.id)
					? this.selected.filter((id: string) => id !== entry.id)
					: [...this.selected, entry.id];
				return;
			}
			this.selected = [entry.id];
		},
		onRowDoubleClick(this: any, entry: Entry): void {
			if (entry.folder) {
				this.navigate(entry);
				return;
			}
			if (this.mode === 'files' && entry.id) {
				this.selected = [entry.id];
				this.confirm();
			}
		},
		isSelected(this: any, entry: Entry): boolean {
			return !!entry.id && this.selected.includes(entry.id);
		},
		confirm(this: any): void {
			if (!this.canSelect) return;
			let picked: Entry[];
			if (this.mode === 'folder') {
				const folder = this.selectedFolder();
				picked = folder ? [folder] : [];
			} else {
				picked = (this.entries as Entry[]).filter((e) => e.id && this.selected.includes(e.id) && !e.folder);
			}
			if (!picked.length) return;
			this.setOpen(false);
			this.$emit('select', picked);
		},
		close(this: any): void {
			this.setOpen(false);
		},
		setOpen(this: any, value: boolean): void {
			if (!value) {
				this.$emit('update:open', false);
				this.$emit('close');
			}
		},
		errorText(e: unknown): string {
			if (e instanceof Error) {
				return e.message;
			}
			return String(e);
		},
	},
});
