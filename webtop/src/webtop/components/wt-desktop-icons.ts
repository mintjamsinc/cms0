// wt-desktop-icons custom element
//
// Renders the contents of the user's /home/users/{userID}/Desktop folder as a
// grid of icons sitting beneath the application windows. In the default
// `auto` layout the icons flow in name order and positions are not persisted;
// in the `manual` layout each icon stays in the grid cell the user dropped it
// in, and those cells are kept in the user's preferences
// (services/desktop-layout-store.ts). Real-time updates are driven by an
// eventHub.watchNode subscription so external changes (e.g. files dropped via
// Content Browser into the Desktop folder) appear without manual refresh.
//
// The component is a thin renderer: selection, rubber-band, context menu,
// upload progress and conflict dialog are all owned by the shell (index.ts).
// We dispatch CustomEvents on `document` for the things the shell needs to
// react to and accept selection state via a prop. The icon layout is the one
// piece of state owned here; the shell learns the current mode through
// `webtop-desktop-layout-changed` and drives it with the `webtop-desktop-*`
// document events listed in the template.
import { defineComponent } from '@mintjamsinc/ichigojs';
import { isFolderNode } from '../graphql/types.js';
import {
	DesktopLayoutStore,
	cellAt,
	computeLayout,
	flowCells,
	gridFor,
	nearestFreeCell,
	parseLayout,
	remPixels,
} from '../services/desktop-layout-store.js';
import type { DesktopCell, DesktopGrid, DesktopLayout, DesktopLayoutMode } from '../services/desktop-layout-store.js';

/** How long after a remote change this tab waits for its own save to settle before re-reading. */
const LAYOUT_RESYNC_MS = 1000;

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
	emits: ['webtop-desktop-items-changed', 'webtop-desktop-icon-click', 'webtop-desktop-icon-contextmenu', 'webtop-desktop-icon-dragend', 'webtop-desktop-icon-dragover', 'webtop-desktop-icon-dragleave', 'webtop-desktop-icon-drop', 'webtop-desktop-layout-changed'],
	data() {
		return {
			items: [] as DesktopItem[],
			// Icon layout: the mode and, in manual mode, the stored cells.
			layout: { mode: 'auto', positions: {} } as DesktopLayout,
			// The cell each icon is shown in this render (manual mode only).
			cells: {} as Record<string, DesktopCell>,
			// Stored under a raw object so the unsubscribe callback isn't
			// proxied by the reactive system.
			_: this.$markRaw({
				unsubscribe: null as (() => void) | null,
				reloadTimer: null as any,
				store: null as DesktopLayoutStore | null,
				resizeHandler: null as (() => void) | null,
				resizeTimer: null as any,
				resyncTimer: null as any,
				pageHideHandler: null as (() => void) | null,
				// The icons being dragged and where the pointer grabbed the
				// pressed one, so a drop on the desktop lands the icon where
				// the user let go of it rather than snapping its corner there.
				drag: null as null | { ids: string[]; anchorId: string; grabX: number; grabY: number },
			}),
		};
	},
	watch: {
		enabled(this: any) { this.refresh(); },
		desktopPath(this: any) { this.refresh(); },
	},
	methods: {
		onMounted() {
			const vm = this as any;
			// The grid is as big as the desktop: re-place icons that no longer
			// fit (or fit again) when the window is resized.
			vm._.resizeHandler = () => {
				if (vm._.resizeTimer) clearTimeout(vm._.resizeTimer);
				vm._.resizeTimer = setTimeout(() => {
					vm._.resizeTimer = null;
					vm.applyLayout();
				}, 100);
			};
			window.addEventListener('resize', vm._.resizeHandler);
			vm._.pageHideHandler = () => { vm._.store?.flush(); };
			window.addEventListener('pagehide', vm._.pageHideHandler);
			vm.refresh();
		},
		onUnmount() {
			const vm = this as any;
			vm.unsubscribe();
			if (vm._.reloadTimer) {
				clearTimeout(vm._.reloadTimer);
				vm._.reloadTimer = null;
			}
			if (vm._.resizeTimer) {
				clearTimeout(vm._.resizeTimer);
				vm._.resizeTimer = null;
			}
			if (vm._.resyncTimer) {
				clearTimeout(vm._.resyncTimer);
				vm._.resyncTimer = null;
			}
			if (vm._.resizeHandler) {
				window.removeEventListener('resize', vm._.resizeHandler);
				vm._.resizeHandler = null;
			}
			if (vm._.pageHideHandler) {
				window.removeEventListener('pagehide', vm._.pageHideHandler);
				vm._.pageHideHandler = null;
			}
			vm._.store?.flush();
		},
		onReloadEvent() {
			(this as any).loadItems();
		},
		async refresh() {
			const vm = this as any;
			if (vm.enabled && vm.desktopPath) {
				// The layout first, so the icons never flash in the other mode.
				await vm.loadLayout();
				await vm.loadItems();
				vm.subscribe();
			} else {
				vm.unsubscribe();
				vm.items = [];
				vm.cells = {};
				vm.$emit('webtop-desktop-items-changed', { items: [] }, { target: document });
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
				vm.applyLayout();
				// Notify shell so it can drop selectedIds that no longer exist.
				vm.$emit('webtop-desktop-items-changed', { items: vm.items }, { target: document });
			} catch (err) {
				console.warn('[Webtop] Failed to load desktop items:', err);
			}
		},
		// -----------------------------------------------------------------
		// Icon layout (auto flow vs. manual grid cells).
		// -----------------------------------------------------------------
		async loadLayout() {
			const vm = this as any;
			const userId = (window as any).Webtop?.currentUser?.id;
			const api = (window as any).Webtop?.api;
			if (!userId || !api) {
				vm._.store = null;
				vm.setLayout({ mode: 'auto', positions: {} });
				return;
			}
			if (!vm._.store) vm._.store = new DesktopLayoutStore(api, userId);
			vm.setLayout(await vm._.store.load());
		},
		setLayout(layout: DesktopLayout) {
			const vm = this as any;
			const modeChanged = vm.layout.mode !== layout.mode;
			vm.layout = layout;
			if (modeChanged) vm.emitLayoutMode();
		},
		emitLayoutMode() {
			const vm = this as any;
			vm.$emit('webtop-desktop-layout-changed', { mode: vm.layout.mode }, { target: document });
		},
		measureGrid(): DesktopGrid {
			const vm = this as any;
			// The layer fills #desktop-area (inset: 0), so either one measures the grid.
			const root = (vm.$refs.root as HTMLElement | undefined) || document.getElementById('desktop-area');
			return gridFor(root?.clientWidth || 0, root?.clientHeight || 0, remPixels());
		},
		/**
		 * Give every icon its cell for this render. Icons new to the store
		 * are placed at the first free cell and that cell is stored, so a
		 * file keeps the spot it first appeared in.
		 */
		applyLayout() {
			const vm = this as any;
			if (vm.layout.mode !== 'manual') {
				if (Object.keys(vm.cells).length) vm.cells = {};
				return;
			}
			const grid = vm.measureGrid();
			const ids = vm.items.map((i: DesktopItem) => i.id);
			const { cells, added } = computeLayout(ids, vm.layout.positions, grid);
			vm.cells = cells;
			if (grid.measured && Object.keys(added).length > 0) {
				Object.assign(vm.layout.positions, added);
				vm.persistLayout(true);
			}
		},
		/** Save the layout; `prune` also drops cells of icons that are gone. */
		persistLayout(prune: boolean) {
			const vm = this as any;
			if (prune && vm.items.length > 0) {
				const alive = new Set(vm.items.map((i: DesktopItem) => i.id));
				for (const id of Object.keys(vm.layout.positions)) {
					if (!alive.has(id)) delete vm.layout.positions[id];
				}
			}
			vm._.store?.save(vm.layout);
		},
		iconStyle(item: DesktopItem) {
			const vm = this as any;
			if (vm.layout.mode !== 'manual') return {};
			const c = vm.cells[item.id];
			if (!c) return {};
			return { '--col': String(c.col), '--row': String(c.row) };
		},
		// Shell → component: switch the layout. Going manual keeps the icons
		// where the flow had them; going auto drops the stored cells.
		onLayoutModeEvent(event: CustomEvent) {
			const vm = this as any;
			const mode = (event.detail || {}).mode as DesktopLayoutMode;
			if (mode !== 'auto' && mode !== 'manual') return;
			if (mode === vm.layout.mode) return;
			if (mode === 'manual') {
				const grid = vm.measureGrid();
				const ids = vm.items.map((i: DesktopItem) => i.id);
				vm.layout = { mode, positions: grid.measured ? flowCells(ids, grid) : {} };
			} else {
				vm.layout = { mode, positions: {} };
			}
			vm.emitLayoutMode();
			vm.applyLayout();
			vm.persistLayout(false);
		},
		// Shell → component: in manual mode, line the icons up by name again
		// (down each column, then right) and keep them there.
		onArrangeEvent() {
			const vm = this as any;
			if (vm.layout.mode !== 'manual') return;
			vm.layout = { mode: 'manual', positions: {} };
			vm.applyLayout();
		},
		// Shell → component: the icons being dragged were dropped on empty
		// desktop at (x, y) in desktop-area pixels. Move them there, keeping
		// their arrangement relative to the one that was grabbed; an occupied
		// cell sends an icon to the nearest free one.
		onPlaceEvent(event: CustomEvent) {
			const vm = this as any;
			const drag = vm._.drag;
			vm._.drag = null;
			if (!drag || vm.layout.mode !== 'manual') return;
			const d = event.detail || {};
			if (typeof d.x !== 'number' || typeof d.y !== 'number') return;
			const anchorCell = vm.cells[drag.anchorId];
			if (!anchorCell) return;
			const grid = vm.measureGrid();
			if (!grid.measured) return;
			const target = cellAt(d.x - drag.grabX, d.y - drag.grabY, remPixels());
			const dCol = target.col - anchorCell.col;
			const dRow = target.row - anchorCell.row;
			const moving = drag.ids.filter((id: string) => !!vm.cells[id]);
			const movingSet = new Set(moving);
			const taken = new Set<string>();
			for (const id of Object.keys(vm.cells)) {
				if (movingSet.has(id)) continue;
				const c = vm.cells[id];
				taken.add(`${c.col}:${c.row}`);
			}
			// The grabbed icon first, so it gets the exact cell when it is free.
			const order = [drag.anchorId, ...moving.filter((id: string) => id !== drag.anchorId)];
			const positions = { ...vm.layout.positions };
			for (const id of order) {
				const cur = vm.cells[id];
				const wanted = { col: Math.max(0, cur.col + dCol), row: Math.max(0, cur.row + dRow) };
				const cell = nearestFreeCell(wanted, taken, grid);
				taken.add(`${cell.col}:${cell.row}`);
				positions[id] = cell;
			}
			vm.layout = { mode: 'manual', positions };
			vm.applyLayout();
			vm.persistLayout(true);
		},
		// Shell → component: an icon's id changed (a rename of a node whose
		// id is its path). Carry its cell over so the icon stays put.
		onRekeyEvent(event: CustomEvent) {
			const vm = this as any;
			const d = event.detail || {};
			if (typeof d.from !== 'string' || typeof d.to !== 'string' || d.from === d.to) return;
			const cell = vm.layout.positions[d.from];
			if (!cell) return;
			const positions = { ...vm.layout.positions };
			delete positions[d.from];
			positions[d.to] = cell;
			vm.layout = { mode: vm.layout.mode, positions };
			vm.persistLayout(false);
		},
		/**
		 * The layout changed in another tab or browser (or this one's own
		 * save echoing back). While this tab has a save waiting or on its way
		 * the change may already be stale, so the stored layout is read again
		 * once this tab's writes are done.
		 */
		onRemoteLayout(event: CustomEvent) {
			const vm = this as any;
			const store = vm._.store;
			if (!store) return;
			const raw = (event.detail || {})[DesktopLayoutStore.property];
			if (store.isOwnWrite(raw)) return;
			if (store.busy) {
				vm.scheduleLayoutResync();
				return;
			}
			vm.setLayout(parseLayout(raw));
			vm.applyLayout();
		},
		scheduleLayoutResync() {
			const vm = this as any;
			if (vm._.resyncTimer) return;
			vm._.resyncTimer = setTimeout(async () => {
				vm._.resyncTimer = null;
				const store = vm._.store;
				if (!store) return;
				if (store.busy) {
					vm.scheduleLayoutResync();
					return;
				}
				const layout = await store.load();
				// Changed again meanwhile: this tab's save is the newer word.
				if (store.busy) return;
				vm.setLayout(layout);
				vm.applyLayout();
			}, LAYOUT_RESYNC_MS);
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
		//
		// This runs on `click`, not `mousedown`, on purpose: a click does not
		// fire when the press turns into a drag, so grabbing one icon out of a
		// multi-selection keeps the whole selection intact and the drag carries
		// every selected file. Collapsing to a single item on mousedown would
		// discard the rest of the selection before dragstart fires.
		onItemClick(item: DesktopItem, event: MouseEvent) {
			// Right-click is handled by onItemContextMenu below.
			if (event.button !== 0) return;
			(this as any).$emit('webtop-desktop-icon-click', {
				itemId: item.id,
				ctrlKey: event.ctrlKey || event.metaKey,
				shiftKey: event.shiftKey,
			}, { target: document });
		},
		onItemContextMenu(item: DesktopItem, event: MouseEvent) {
			event.preventDefault();
			event.stopPropagation();
			(this as any).$emit('webtop-desktop-icon-contextmenu', { itemId: item.id, x: event.clientX, y: event.clientY }, { target: document });
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
			vm._setDragImage(event, dragItems);
			// Remembered for a drop on empty desktop in manual mode (onPlaceEvent).
			try {
				const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
				vm._.drag = {
					ids: dragItems.map((i: DesktopItem) => i.id),
					anchorId: item.id,
					grabX: event.clientX - rect.left,
					grabY: event.clientY - rect.top,
				};
			} catch {
				vm._.drag = null;
			}
			try {
				(window as any).__webtopDragData = {
					items: payload,
					sourceAppID: null,
					sourceFolderPath: vm.desktopPath,
				};
			} catch { /* ignore */ }
		},
		onItemDragEnd() {
			// `dragend` follows `drop`, so a reposition has already read this.
			(this as any)._.drag = null;
			try { (window as any).__webtopDragData = null; } catch { /* ignore */ }
			(this as any).$emit('webtop-desktop-icon-dragend', undefined, { target: document });
		},
		// Custom drag ghost showing only the dragged items, so the browser's
		// default image never picks up neighbouring, unselected icons. Rendered
		// off-screen just long enough for the browser to snapshot it.
		_setDragImage(event: DragEvent, dragItems: DesktopItem[]) {
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
				icon.className = count === 1 ? (this as any).getFileIcon(dragItems[0]) : 'bi bi-files';
				ghost.appendChild(icon);

				const label = document.createElement('span');
				label.style.cssText = 'overflow:hidden;text-overflow:ellipsis';
				label.textContent = count === 1 ? (dragItems[0].name || '') : `${count} items`;
				ghost.appendChild(label);

				document.body.appendChild(ghost);
				event.dataTransfer.setDragImage(ghost, 12, 12);
				setTimeout(() => { try { ghost.remove(); } catch { /* ignore */ } }, 0);
			} catch { /* setDragImage is best-effort */ }
		},
		// Allow dropping onto folder icons. Background drops are handled in
		// the shell on #desktop-area.
		onItemDragOver(item: DesktopItem, event: DragEvent) {
			if (!item.isCollection) return;
			if (!event.dataTransfer) return;
			const types = Array.from(event.dataTransfer.types || []);
			const hasInternal = types.includes('application/x-webtop-files') || !!(window as any).__webtopDragData;
			const hasFiles = types.includes('Files');
			// Editor "Save As" payload (text-editor, memo, bpmn/eip-modeler).
			const hasSaveAs = types.includes('application/x-webtop-save');
			if (!hasInternal && !hasFiles && !hasSaveAs) return;
			event.preventDefault();
			event.stopPropagation();
			event.dataTransfer.dropEffect = hasInternal && event.ctrlKey ? 'copy' : (hasInternal ? 'move' : 'copy');
			(this as any).$emit('webtop-desktop-icon-dragover', { itemId: item.id }, { target: document });
		},
		onItemDragLeave(item: DesktopItem) {
			(this as any).$emit('webtop-desktop-icon-dragleave', { itemId: item.id }, { target: document });
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
			// Forward the editor Save-As payload (read synchronously while the
			// DataTransfer is still valid) so the shell can save into this folder.
			if (types.includes('application/x-webtop-save')) {
				detail.saveAs = event.dataTransfer.getData('application/x-webtop-save');
			}
			(this as any).$emit('webtop-desktop-icon-drop', detail, { target: document });
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
