// wt-desktop-icons custom element
//
// Renders the contents of the user's /home/users/{userID}/Desktop folder as a
// flowing grid of icons sitting beneath the application windows. Icons are
// name-sorted; positions are not persisted. Real-time updates are driven by an
// eventHub.watchNode subscription so external changes (e.g. files dropped via
// Content Browser into the Desktop folder) appear without manual refresh.
//
// The component is a thin renderer: selection, rubber-band, context menu,
// upload progress and conflict dialog are all owned by the shell (index.ts).
// We dispatch CustomEvents on `document` for the things the shell needs to
// react to and accept selection state via a prop.
import { defineComponent } from '@mintjamsinc/ichigojs';
import { isFolderNode } from '../graphql/types.js';

interface DesktopItem {
	id: string;
	path: string;
	name: string;
	isCollection: boolean;
	mimeType?: string;
	isReferenceable: boolean;
	downloadURL?: string | null;
}

defineComponent('wt-desktop-icons', {
	template: '#wt-desktop-icons',
	props: ['enabled', 'desktopPath', 'selectedIds', 'dragOverItemID'],
	data() {
		return {
			items: [] as DesktopItem[],
			// Stored under a raw object so the unsubscribe callback isn't
			// proxied by the reactive system.
			_: this.$markRaw({
				unsubscribe: null as (() => void) | null,
				reloadTimer: null as any,
			}),
		};
	},
	watch: {
		enabled(this: any) { this.refresh(); },
		desktopPath(this: any) { this.refresh(); },
	},
	methods: {
		onMounted() {
			(this as any).refresh();
			// Allow the shell to request a reload (e.g. after user actions
			// where waiting on the watchNode round-trip would feel laggy).
			document.addEventListener('webtop-desktop-reload', (this as any).onReloadEvent);
		},
		onUnmount() {
			const vm = this as any;
			vm.unsubscribe();
			if (vm._.reloadTimer) {
				clearTimeout(vm._.reloadTimer);
				vm._.reloadTimer = null;
			}
			document.removeEventListener('webtop-desktop-reload', vm.onReloadEvent);
		},
		onReloadEvent() {
			(this as any).loadItems();
		},
		refresh() {
			const vm = this as any;
			if (vm.enabled && vm.desktopPath) {
				vm.loadItems();
				vm.subscribe();
			} else {
				vm.unsubscribe();
				vm.items = [];
				document.dispatchEvent(new CustomEvent('webtop-desktop-items-changed', {
					detail: { items: [] },
				}));
			}
		},
		async loadItems() {
			const vm = this as any;
			try {
				const api = (window as any).Webtop?.api;
				if (!api?.content) return;
				const result = await api.content.listChildren(vm.desktopPath, { first: 500 });
				const nodes = (result?.edges || []).map((e: any) => e.node).filter(Boolean);
				vm.items = nodes.map((n: any) => {
					const url = n.downloadUrl || null;
					return {
						id: n.identifier || n.path,
						path: n.path,
						name: n.name,
						isCollection: isFolderNode(n),
						mimeType: n.mimeType,
						isReferenceable: !!n.isReferenceable,
						downloadURL: url ? url + (url.includes('?') ? '&' : '?') + 'attachment' : null,
					};
				}).sort((a: DesktopItem, b: DesktopItem) => a.name.localeCompare(b.name));
				// Notify shell so it can drop selectedIds that no longer exist.
				document.dispatchEvent(new CustomEvent('webtop-desktop-items-changed', {
					detail: { items: vm.items },
				}));
			} catch (err) {
				console.warn('[Webtop] Failed to load desktop items:', err);
			}
		},
		subscribe() {
			const vm = this as any;
			vm.unsubscribe();
			try {
				const eventHub = (window as any).Webtop?.api?.eventHub;
				if (!eventHub || typeof eventHub.watchNode !== 'function') return;
				vm._.unsubscribe = eventHub.watchNode(vm.desktopPath, () => {
					vm.scheduleReload();
				}, true);
			} catch (err) {
				console.warn('[Webtop] Failed to subscribe to desktop changes:', err);
			}
		},
		unsubscribe() {
			const vm = this as any;
			if (vm._.unsubscribe) {
				try { vm._.unsubscribe(); } catch { /* ignore */ }
				vm._.unsubscribe = null;
			}
		},
		// Coalesce bursts of node-change events (e.g. multi-file upload) into a
		// single reload so the icon list doesn't churn.
		scheduleReload() {
			const vm = this as any;
			if (vm._.reloadTimer) return;
			vm._.reloadTimer = setTimeout(() => {
				vm._.reloadTimer = null;
				vm.loadItems();
			}, 200);
		},
		isSelected(item: DesktopItem): boolean {
			const ids = (this as any).selectedIds || [];
			return ids.includes(item.id);
		},
		getFileIcon(item: DesktopItem): string {
			if (item.isCollection) return 'bi bi-folder-fill';
			const mimeType = item.mimeType || '';
			const slash = mimeType.indexOf('/');
			const type = slash >= 0 ? mimeType.substring(0, slash) : mimeType;
			const subtype = slash >= 0 ? mimeType.substring(slash + 1) : '';
			if (type === 'image') return 'bi bi-image';
			if (type === 'video') return 'bi bi-file-earmark-play';
			if (type === 'audio') return 'bi bi-file-earmark-music';
			if (type === 'text') {
				if (subtype === 'csv') return 'bi bi-file-earmark-spreadsheet';
				if (subtype === 'html' || subtype === 'css' || subtype === 'javascript' || subtype === 'xml') {
					return 'bi bi-file-earmark-code';
				}
				return 'bi bi-file-earmark-text';
			}
			if (type === 'application') {
				if (subtype === 'pdf') return 'bi bi-file-earmark-pdf';
				if (subtype === 'zip' || subtype === 'x-rar-compressed' || subtype === 'x-7z-compressed'
					|| subtype === 'x-tar' || subtype === 'gzip' || subtype === 'x-bzip2') {
					return 'bi bi-file-earmark-zip';
				}
				if (subtype === 'msword' || subtype === 'vnd.openxmlformats-officedocument.wordprocessingml.document'
					|| subtype === 'vnd.oasis.opendocument.text') {
					return 'bi bi-file-earmark-word';
				}
				if (subtype === 'vnd.ms-excel' || subtype === 'vnd.openxmlformats-officedocument.spreadsheetml.sheet'
					|| subtype === 'vnd.oasis.opendocument.spreadsheet') {
					return 'bi bi-file-earmark-spreadsheet';
				}
				if (subtype === 'vnd.ms-powerpoint' || subtype === 'vnd.openxmlformats-officedocument.presentationml.presentation'
					|| subtype === 'vnd.oasis.opendocument.presentation') {
					return 'bi bi-file-earmark-slides';
				}
				if (subtype === 'json' || subtype === 'xml' || subtype === 'javascript' || subtype === 'x-javascript'
					|| subtype === 'typescript' || subtype === 'x-httpd-php' || subtype === 'x-python-code'
					|| subtype === 'x-sh' || subtype === 'x-yaml' || subtype === 'sql') {
					return 'bi bi-file-earmark-code';
				}
			}
			return 'bi bi-file-earmark';
		},
		// Selection — single click selects, ctrl/meta toggles, shift extends.
		// The shell owns selection state; we dispatch the requested change
		// instead of mutating it ourselves so rubber-band, click and
		// context-menu paths stay consistent.
		onItemMouseDown(item: DesktopItem, event: MouseEvent) {
			// Right-click is handled by onItemContextMenu below.
			if (event.button !== 0) return;
			document.dispatchEvent(new CustomEvent('webtop-desktop-icon-mousedown', {
				detail: {
					itemId: item.id,
					ctrlKey: event.ctrlKey || event.metaKey,
					shiftKey: event.shiftKey,
				},
			}));
		},
		onItemContextMenu(item: DesktopItem, event: MouseEvent) {
			event.preventDefault();
			event.stopPropagation();
			document.dispatchEvent(new CustomEvent('webtop-desktop-icon-contextmenu', {
				detail: { itemId: item.id, x: event.clientX, y: event.clientY },
			}));
		},
		// Match the payload shape used by Content Browser so its existing
		// onDragOver / onDrop / onItemDrop / _executeInternalDrop pipeline picks
		// up the drag without any new logic. See content-browser/app.ts ~3344.
		onItemDragStart(item: DesktopItem, event: DragEvent) {
			const vm = this as any;
			if (!event.dataTransfer) return;
			event.dataTransfer.effectAllowed = 'copyMove';

			// If the dragged item is part of the current selection, send the
			// whole selection. Otherwise drag just the one icon.
			const selectedIds: string[] = (vm.selectedIds as string[]) || [];
			let dragItems: DesktopItem[];
			if (selectedIds.includes(item.id)) {
				dragItems = vm.items.filter((i: DesktopItem) => selectedIds.includes(i.id));
			} else {
				dragItems = [item];
			}

			const payload = dragItems.map((i: DesktopItem) => ({
				path: i.path,
				name: i.name,
				isCollection: i.isCollection,
				mimeType: i.mimeType,
				uuid: i.isReferenceable ? i.id : undefined,
				isReferenceable: !!i.isReferenceable,
				downloadURL: i.downloadURL ? i.downloadURL.replace(/[?&]attachment$/, '') : undefined,
			}));
			event.dataTransfer.setData('application/x-webtop-files', JSON.stringify(payload));
			if (payload.length === 1 && !payload[0].isCollection) {
				event.dataTransfer.setData('application/x-webtop-file', JSON.stringify(payload[0]));
			}
			event.dataTransfer.setData('text/plain', dragItems.map(i => i.path).join('\n'));
			try {
				(window as any).__webtopDragData = {
					items: payload,
					sourceAppID: null,
					sourceFolderPath: vm.desktopPath,
				};
			} catch { /* ignore */ }
		},
		onItemDragEnd() {
			try { (window as any).__webtopDragData = null; } catch { /* ignore */ }
			document.dispatchEvent(new CustomEvent('webtop-desktop-icon-dragend'));
		},
		// Allow dropping onto folder icons. Background drops are handled in
		// the shell on #desktop-area.
		onItemDragOver(item: DesktopItem, event: DragEvent) {
			if (!item.isCollection) return;
			if (!event.dataTransfer) return;
			const types = Array.from(event.dataTransfer.types || []);
			const hasInternal = types.includes('application/x-webtop-files') || !!(window as any).__webtopDragData;
			const hasFiles = types.includes('Files');
			if (!hasInternal && !hasFiles) return;
			event.preventDefault();
			event.stopPropagation();
			event.dataTransfer.dropEffect = hasInternal && event.ctrlKey ? 'copy' : (hasInternal ? 'move' : 'copy');
			document.dispatchEvent(new CustomEvent('webtop-desktop-icon-dragover', {
				detail: { itemId: item.id },
			}));
		},
		onItemDragLeave(item: DesktopItem) {
			document.dispatchEvent(new CustomEvent('webtop-desktop-icon-dragleave', {
				detail: { itemId: item.id },
			}));
		},
		onItemDrop(item: DesktopItem, event: DragEvent) {
			if (!item.isCollection) return;
			if (!event.dataTransfer) return;
			event.preventDefault();
			event.stopPropagation();
			const types = Array.from(event.dataTransfer.types || []);
			const detail: any = { itemId: item.id, path: item.path, ctrlKey: event.ctrlKey };
			if (types.includes('Files')) {
				detail.osItems = Array.from(event.dataTransfer.items || []);
			}
			document.dispatchEvent(new CustomEvent('webtop-desktop-icon-drop', { detail }));
		},
		onItemDoubleClick(item: DesktopItem) {
			if (item.isCollection) {
				// Open folder in Content Browser. Content Browser reads
				// launchOptions.initialPath to position itself.
				window.postMessage({
					type: 'open-app',
					appId: '2468cf47-1a30-4053-b80a-9c5486954b08',
					options: { initialPath: item.path },
				}, window.location.origin);
				return;
			}
			const editor = this.findEditorForMimeType(item.mimeType || '');
			if (!editor) return;
			window.postMessage({
				type: 'open-file-with-app',
				appId: editor.id,
				filePath: item.path,
				mimeType: item.mimeType,
			}, window.location.origin);
		},
		findEditorForMimeType(mimeType: string): any {
			const apps = (window as any).Webtop?.apps || [];
			for (const app of apps) {
				if (!app.editor) continue;
				const contentTypes = app.contentTypes || [];
				for (const pattern of contentTypes) {
					if (typeof pattern !== 'string') continue;
					if (pattern.endsWith('/*')) {
						const prefix = pattern.slice(0, -1);
						if (mimeType.startsWith(prefix)) return app;
					} else if (pattern === mimeType) {
						return app;
					}
				}
			}
			return null;
		},
	},
});
