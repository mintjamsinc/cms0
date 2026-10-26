// <wt-cloud-picker> — picks files, or a folder, of the user's drives at Google
// or Microsoft (the files bundle of a linked account).
//
//     <wt-cloud-picker v-model:open="cloudPicker.visible" :service="instance.api.cloud"
//         :provider="cloudPicker.provider" :provider-name="cloudPicker.providerName"
//         :mode="cloudPicker.mode" :multiple="true" :title="t('...')" :labels="cloudPickerLabels"
//         @select="onCloudPicked($event.detail)"></wt-cloud-picker>
//
// The list starts with the user's drives (their own, then the shared drives
// or libraries they are a member of), and goes down a folder at a time with
// listChildren. Everything is read through the service; no provider script
// runs in the browser.
//
// `mode` is `files` (pick one or more files) or `folder` (pick a destination
// folder; a drive's root counts). `select` carries an array of entries
// ({provider, driveId, id, name, mimeType, size, folder, webUrl}); in folder
// mode one entry, whose id is null for a drive's root. `labels` keys (all
// optional, English fallbacks): select, selectFolder, cancel, loading, empty,
// root, up, myDrive, sharedDrives.

import { defineComponent } from '@mintjamsinc/ichigojs';

interface Entry {
	provider: string;
	/** The drive the entry is in; null for the row that lists the drives. */
	driveId: string | null;
	id: string | null;
	name: string;
	mimeType: string | null;
	size: number | null;
	folder: boolean;
	webUrl: string | null;
	nativeType?: string | null;
	/** A drive row (its id is the drive's id, and it opens the drive's root). */
	drive?: boolean;
}

const GOOGLE_NATIVE_PREFIX = 'application/vnd.google-apps.';
/** How many entries of a folder are read into the list at most. */
const LIST_LIMIT = 1000;

function iconFor(entry: Entry): string {
	if (entry.drive) return 'bi-hdd';
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
		/** A CloudServiceGraphQL: getDrives, listChildren. */
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
			/** Whether the dialog's own list is shown. */
			browsing: false,
			/** The folders from the drives row down to the one listed; the drives row has a null driveId. */
			path: [] as Entry[],
			entries: [] as Entry[],
			selected: [] as string[],
			loading: false,
			error: '',
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
			if (this.loading) return false;
			if (this.mode === 'folder') {
				const node: Entry | null = this.selectedFolder();
				return !!node && !!node.driveId;
			}
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
		/** The row above the drives: the provider itself. */
		rootEntry(this: any): Entry {
			return { provider: this.provider, driveId: null, id: null, name: this.text('root', this.providerName || this.provider), mimeType: null, size: null, folder: true, webUrl: null };
		},
		/** In folder mode: the selected folder row, or the folder being listed. */
		selectedFolder(this: any): Entry | null {
			if (this.selected.length === 1) {
				const row = (this.entries as Entry[]).find((e) => this.key(e) === this.selected[0]);
				if (row && row.folder) return row;
			}
			return this.current;
		},
		key(entry: Entry): string {
			return `${entry.driveId || ''}|${entry.id || ''}`;
		},
		async start(this: any): Promise<void> {
			this.error = '';
			this.entries = [];
			this.selected = [];
			this.path = [];
			this.browsing = true;
			if (!this.service || !this.provider) {
				return;
			}
			await this.navigate(this.rootEntry());
		},
		async navigate(this: any, folder: Entry): Promise<void> {
			if (!this.service) return;
			this.loading = true;
			this.error = '';
			this.selected = [];
			try {
				let entries: Entry[];
				if (!folder.driveId) {
					// The provider's drives: the user's own first, then the shared ones.
					const drives: any[] = await this.service.getDrives(this.provider);
					entries = drives.map((d) => ({
						provider: this.provider,
						driveId: d.id,
						id: d.id,
						name: d.kind === 'MINE' ? this.text('myDrive', d.name) : d.name,
						mimeType: null,
						size: null,
						folder: true,
						webUrl: d.webUrl || null,
						drive: true,
					}));
				} else {
					entries = [];
					let after: string | null = null;
					do {
						const page: any = await this.service.listChildren(this.provider, folder.driveId, folder.drive ? null : folder.id, { after, first: 200 });
						for (const e of page.entries as any[]) {
							entries.push({ provider: e.provider, driveId: e.driveId, id: e.id, name: e.name, mimeType: e.mimeType, size: e.size, folder: e.folder, webUrl: e.webUrl, nativeType: e.nativeType });
						}
						after = page.nextCursor;
					} while (after && entries.length < LIST_LIMIT);
				}
				const target: Entry = folder.drive ? { ...folder, id: null, drive: false } : folder;
				const at = (this.path as Entry[]).findIndex((p) => this.key(p) === this.key(target));
				this.path = at >= 0 ? this.path.slice(0, at + 1) : [...this.path, target];
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
			if (entry.drive) {
				this.navigate(entry);
				return;
			}
			if (!entry.id) return;
			if (entry.folder && this.mode !== 'folder') {
				this.navigate(entry);
				return;
			}
			const key = this.key(entry);
			if (this.mode === 'files' && this.multiple && (event.ctrlKey || event.metaKey)) {
				this.selected = this.selected.includes(key)
					? this.selected.filter((k: string) => k !== key)
					: [...this.selected, key];
				return;
			}
			this.selected = [key];
		},
		onRowDoubleClick(this: any, entry: Entry): void {
			if (entry.folder) {
				this.navigate(entry);
				return;
			}
			if (this.mode === 'files' && entry.id) {
				this.selected = [this.key(entry)];
				this.confirm();
			}
		},
		isSelected(this: any, entry: Entry): boolean {
			return !!entry.id && this.selected.includes(this.key(entry));
		},
		confirm(this: any): void {
			if (!this.canSelect) return;
			let picked: Entry[];
			if (this.mode === 'folder') {
				const folder = this.selectedFolder();
				picked = folder ? [folder] : [];
			} else {
				picked = (this.entries as Entry[]).filter((e) => e.id && this.selected.includes(this.key(e)) && !e.folder);
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
