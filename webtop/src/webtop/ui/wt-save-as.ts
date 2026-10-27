// <wt-save-as> — "Save As" for the editors: a name, a format and a chip to
// drag to where the file should go.
//
//     <wt-save-as v-model:open="saveAsDialog.visible" :file-name="currentFile.name"
//         :formats="saveAsFormats" default-format="memo" :labels="saveAsLabels"
//         @saved="onSaveAsSaved($event.detail)"></wt-save-as>
//
// The chip carries the content as `application/x-webtop-save`; the Content
// Browser (a folder of the repository or of cloud storage) and the Desktop
// write it and answer on the `webtop-save-as` channel with the token the chip
// was given. The dialog closes once the chip has been dropped somewhere that
// took it, stays open when the drop was refused, and `saved` fires when the
// answer arrives:
//
//     { path, location, name, mimeType, format, cloud?, adopted }
//
// `path` / `location` is the repository path or `cloud://…`; `cloud` holds the
// entry ({provider, driveId, id, etag, webUrl, contentUrl}) when the file went
// to a drive. An app that switches to the saved file sets `adopted = true` on
// the detail; otherwise the component tells the user the file was saved, with
// a link to it at the provider when there is one.
//
// A format names the extension, the MIME type and what to produce; `convert`
// asks the drive to turn the content into its native document, and `targets`
// limits where the chip may land (the receivers read it from
// `__webtopDragData.save` on the top window during the drag and refuse the
// drop elsewhere). Changing the format swaps the name's extension.
//
// `labels` keys (all optional, English fallbacks): title, fileName, format,
// dragHint, onlyTo ({targets}), targetRepository, targetGoogle,
// targetMicrosoft, saved ({name}), open, untitled.

import { defineComponent } from '@mintjamsinc/ichigojs';
import type { WtToastMessage } from './wt-toast.js';

export type SaveAsTarget = 'repository' | 'cloud:google' | 'cloud:microsoft';

export interface SaveAsFormat {
	/** 'memo' | 'html' | 'gdoc' | 'text' | ... */
	id: string;
	/** Shown in the format list (already localized). */
	label: string;
	/** Appended to the name; '' keeps the name as typed (a Google document has none). */
	extension: string;
	/** MIME type of the content sent; a Google document sends 'text/html'. */
	mimeType: string;
	/** The MIME type when it follows the name (the text editor's "as named"). */
	mimeTypeOf?: (name: string) => string;
	/** The drive converts the content into its native document. */
	convert?: 'google-document' | 'google-spreadsheet';
	/** Where the chip may land; everywhere when left out. */
	targets?: SaveAsTarget[];
	/** Makes the content the moment the chip is picked up. */
	produce: (name: string) => string;
}

export interface SaveAsSaved {
	path: string;
	location: string;
	name: string;
	mimeType: string;
	format: string;
	cloud?: { provider: string; driveId: string; id: string; etag?: string | null; webUrl?: string | null; contentUrl?: string | null };
	/** Set by the app when it switched to the saved file. */
	adopted: boolean;
}

/** How long the "saved" note stays. */
const TOAST_MS = 8000;

// The channel of each instance, outside the reactive data: a native object
// read through a deep proxy cannot be closed.
const channels = new WeakMap<object, BroadcastChannel>();

function endsWithExtension(name: string, extension: string): boolean {
	return !!extension && name.toLowerCase().endsWith('.' + extension.toLowerCase());
}

/** The name without its last extension; the name itself when it has none. */
function stripExtension(name: string): string {
	const m = /^(.+)\.[^./]+$/.exec(name);
	return m ? m[1] : name;
}

/** The name retargeted from one format to another: the old extension off, the new one on. */
export function retargetName(name: string, from: SaveAsFormat | null, to: SaveAsFormat): string {
	let base = name;
	if (from && endsWithExtension(base, from.extension)) {
		base = base.slice(0, -(from.extension.length + 1));
	} else if (to.convert) {
		// A native document of the drive has no extension of its own.
		base = stripExtension(base);
	}
	if (to.extension && !endsWithExtension(base, to.extension)) {
		base = `${base}.${to.extension}`;
	}
	return base;
}

defineComponent('wt-save-as', {
	template: '#wt-save-as',
	props: {
		/** Visibility (v-model:open). */
		open: { type: Boolean, default: false },
		/** The name the dialog starts with. */
		fileName: { type: String, default: '' },
		/** The formats offered, in order; one hides the list. */
		formats: { type: Array, default: () => [] },
		/** Id of the format selected at first; the first format otherwise. */
		defaultFormat: { type: String, default: '' },
		/** Dialog title; labels.title otherwise. */
		title: { type: String, default: '' },
		/** Bootstrap icon class of the chip. */
		icon: { type: String, default: 'bi-file-earmark-text' },
		/** I18n labels (see the key list above). */
		labels: { type: Object, default: () => ({}) },
	},
	emits: ['update:open', 'close', 'saved'],
	data() {
		return {
			name: '',
			formatId: '',
			/** Token of the chip in flight; '' when none. */
			token: '',
			error: '',
			toast: null as WtToastMessage | null,
		};
	},
	computed: {
		format(this: any): SaveAsFormat | null {
			const formats: SaveAsFormat[] = this.formats || [];
			return formats.find((f) => f.id === this.formatId) || formats[0] || null;
		},
		chipName(this: any): string {
			return (this.name || '').trim() || this.untitledName();
		},
		/** The note under the hint when the format cannot go everywhere. */
		targetsText(this: any): string {
			const format: SaveAsFormat | null = this.format;
			const targets = format?.targets;
			if (!targets || !targets.length || targets.includes('repository')) {
				return '';
			}
			const names = targets.map((t: SaveAsTarget) => {
				if (t === 'cloud:google') return this.text('targetGoogle', 'Google Drive');
				if (t === 'cloud:microsoft') return this.text('targetMicrosoft', 'OneDrive');
				return this.text('targetRepository', 'the repository');
			});
			return this.text('onlyTo', 'This format can be saved to {targets} only.').replace('{targets}', names.join(', '));
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
		onMounted(this: any): void {
			// The receiver answers here once the dropped chip has been written.
			const channel = new BroadcastChannel('webtop-save-as');
			channel.onmessage = (event: MessageEvent) => this.onSaveAsComplete(event.data);
			channels.set(this, channel);
		},
		onUnmount(this: any): void {
			const channel = channels.get(this);
			if (channel) {
				channel.close();
				channels.delete(this);
			}
			this.clearDragData();
		},
		text(this: any, key: string, fallback: string): string {
			return (this.labels && this.labels[key]) || fallback;
		},
		untitledName(this: any): string {
			const format: SaveAsFormat | null = this.format;
			const stem = this.text('untitled', 'untitled');
			return format && format.extension ? `${stem}.${format.extension}` : stem;
		},
		start(this: any): void {
			const formats: SaveAsFormat[] = this.formats || [];
			const format = formats.find((f) => f.id === this.defaultFormat) || formats[0] || null;
			this.formatId = format ? format.id : '';
			this.error = '';
			this.token = '';
			const name = (this.fileName || '').trim();
			this.name = format ? retargetName(name || this.untitledName(), null, format) : name;
			this.clearDragData();
		},
		onFormatChange(this: any, id: string): void {
			const formats: SaveAsFormat[] = this.formats || [];
			const to = formats.find((f) => f.id === id);
			if (!to) {
				return;
			}
			const from: SaveAsFormat | null = this.format;
			this.name = retargetName((this.name || '').trim() || this.untitledName(), from, to);
			this.formatId = to.id;
		},
		mimeTypeFor(this: any, format: SaveAsFormat, name: string): string {
			return (format.mimeTypeOf ? format.mimeTypeOf(name) : '') || format.mimeType;
		},
		onForbiddenDragOver(event: DragEvent): void {
			if (event.dataTransfer) {
				event.dataTransfer.dropEffect = 'none';
			}
		},
		onDragStart(this: any, event: DragEvent): void {
			const format: SaveAsFormat | null = this.format;
			if (!event.dataTransfer || !format) {
				event.preventDefault();
				return;
			}
			const name: string = this.chipName;
			let content = '';
			try {
				content = format.produce(name);
			} catch (e) {
				this.error = this.errorText(e);
				event.preventDefault();
				return;
			}
			this.error = '';
			const mimeType = this.mimeTypeFor(format, name);
			this.token = Date.now().toString(36) + Math.random().toString(36).slice(2);
			const payload: Record<string, unknown> = { name, mimeType, content, saveAsToken: this.token };
			if (format.convert) {
				payload.convert = format.convert;
			}
			event.dataTransfer.effectAllowed = 'copy';
			event.dataTransfer.setData('application/x-webtop-save', JSON.stringify(payload));
			event.dataTransfer.setData('text/plain', name);
			// What the receivers look at on dragover: the chip's type and where
			// it may land. A drag leaves the frame, so it goes on the top window.
			try {
				(window.parent as any).__webtopDragData = {
					save: { name, mimeType, convert: format.convert || null, targets: format.targets ? [...format.targets] : null },
				};
			} catch (_) { /* cross-origin safety */ }
		},
		onDragEnd(this: any, event: DragEvent): void {
			this.clearDragData();
			// Taken somewhere: the receiver is writing it and will answer on the
			// channel. Refused: the dialog stays for another try.
			if (event.dataTransfer && event.dataTransfer.dropEffect !== 'none') {
				this.setOpen(false);
			}
		},
		clearDragData(): void {
			try {
				const top = window.parent as any;
				if (top.__webtopDragData && top.__webtopDragData.save) {
					top.__webtopDragData = null;
				}
			} catch (_) { /* cross-origin safety */ }
		},
		onSaveAsComplete(this: any, data: any): void {
			if (!data || data.type !== 'save-as-complete' || !this.token || data.saveAsToken !== this.token) {
				return;
			}
			this.token = '';
			const format: SaveAsFormat | null = this.format;
			const detail: SaveAsSaved = {
				path: data.path,
				location: data.path,
				name: data.name,
				mimeType: data.mimeType,
				format: format ? format.id : '',
				adopted: false,
			};
			if (data.cloud) {
				detail.cloud = data.cloud;
			}
			this.setOpen(false);
			this.$emit('saved', detail);
			if (!detail.adopted) {
				this.showToast(this.text('saved', 'Saved {name}.').replace('{name}', detail.name), (data.cloud && data.cloud.webUrl) || '');
			}
		},
		showToast(this: any, text: string, url: string): void {
			this.toast = {
				text,
				link: url ? { href: url, label: this.text('open', 'Open') } : null,
				duration: TOAST_MS,
			};
		},
		close(this: any): void {
			this.setOpen(false);
		},
		setOpen(this: any, value: boolean): void {
			if (!value && this.open) {
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
