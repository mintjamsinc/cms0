import { WebtopContext } from './global.js';
import { VDOM } from '@mintjamsinc/ichigojs';
import { WebtopAPI } from './services/webtop-api.js';
import { User } from './services/user-service.js';
import { Application, ApplicationInstance } from './services/webtop-service.js';
import type { SessionData, SessionEntry } from './services/session-manager.js';
import { WebtopUtil } from './services/webtop-util.js';
import './components/wt-window.js';
import './components/wt-desktop-icons.js';
import { loadSavedWindowSize } from './components/wt-window.js';
import { UrlUtils } from './utils/url.js';
import { BUILD_VERSION } from './utils/build-version.js';
import { MetadataDefinitionCache } from './services/metadata-cache.js';
import { I18nService } from './services/webtop-i18n-service.js';
import { deleteContentItems, type DeleteJobHandle, type DeleteJobProgress } from './services/content-delete.js';
import type { JobStatus } from './graphql/types.js';

// Re-export GraphQL, services, stores, realtime, utilities, and composables for external use
export * from './graphql/index.js';
export * from './services/content-service-graphql.js';
export * from './services/metadata-cache.js';
export * from './services/webtop-i18n-service.js';
export * from './services/bpm-service-graphql.js';
export * from './services/eip-service-graphql.js';
export * from './services/webtop-service-graphql.js';
export * from './stores/index.js';
export * from './realtime/index.js';
export { UrlUtils } from './utils/url.js';
export { BUILD_VERSION, CACHE_BUSTER, appendCacheBuster } from './utils/build-version.js';
// Re-export composables excluding PageInfo and Connection which conflict with graphql exports
export {
  useAsync,
  usePaginated,
  useNode,
  useNodeList,
  useSearch,
  useNodeMutations,
  useXPath,
  useTask,
  useTaskList,
  useTaskCounts,
  useTaskMutations,
  useProcessDefinitions,
  useProcessInstance,
  useProcessOperations,
  useRoute,
  useRouteList,
  useCamelContext,
  useCamelContextList,
  useRouteStatistics,
  useRouteMutations,
  useRouteEditor,
} from './composables/index.js';

// Single shared keydown handler reference for the app popup.
// Kept at module scope because ichigo.js treats every methods-entry as a
// function (a null-valued field in methods triggers a binding warning).
let popupIframeEscHandler: ((e: KeyboardEvent) => void) | null = null;

async function loadComponent(relPath: string): Promise<void> {
	const res = await fetch(`./components/${relPath}?v=${BUILD_VERSION}`);
	const html = await res.text();
	const doc = new DOMParser().parseFromString(html, 'text/html');
	for (const tmpl of Array.from(doc.querySelectorAll('template'))) {
		document.body.appendChild(tmpl);
	}
}

const WtDesktop = {
	data() {
		return {
			isReady: false,
			username: null,
			avatarURL: null,
			isMenuOpen: false,
			logoURL: null,
			displayDate: null,
			displayTime: null,
			sortedApps: null,
			timerId: null,
			appInstances: [],
			activeAppInstanceID: null as string | null,
			maximizedWindowIDs: [] as string[],
			minimizedWindowIDs: [] as string[],
			// Dock hover preview state. Hovering a dock entry opens a horizontal
			// preview popover above it. Hovering an individual card "peeks" the
			// corresponding window by hiding all others (z-index unchanged).
			hoverDockAppID: null as string | null,
			peekedInstanceID: null as string | null,
			_dockHideTimer: null as any,
			// Live UI hints pushed by apps via instance.setDisplayInfo().
			// Keyed by instance.id. Stored as a reactive object so the Dock
			// preview re-renders when an app updates its state without
			// requiring the shell to call back into the iframe realm.
			instanceDisplayInfo: {} as Record<string, { subtitle: string }>,
			// Session save overlay
			showSaveSessionOverlay: false,
			sessionNameInput: '',
			// Session picker overlay (shown at startup)
			showSessionPicker: false,
			sessionPickerList: [] as SessionEntry[],
			selectedSessionID: null as string | null,
			// Restoring overlay
			restoringSession: false,
			// Context menu state. `onAction` is set when the menu was raised
			// by a shell-realm component (e.g. desktop icons) — the action id
			// is invoked locally instead of postMessage'd into an iframe.
			contextMenu: {
				visible: false,
				x: 0,
				y: 0,
				items: [] as { id: string; label: string; icon?: string; danger?: boolean; type?: string }[],
				sourceAppId: null as string | null,
				onAction: null as ((id: string) => void) | null,
			},
			// Desktop folder state. Set in onMounted by probing
			// /home/users/{userID}/Desktop. Existing users created before this
			// feature won't have one — drop/paste then shows desktopAlert.
			hasDesktopFolder: false,
			desktopFolderPath: '' as string,
			desktopAlert: {
				visible: false,
				title: '',
				message: '',
			},
			// Rename dialog for a single desktop icon. Item is captured on open
			// so subsequent selection changes don't redirect the rename target.
			desktopRenameDialog: {
				visible: false,
				item: null as any,
				newName: '',
				isLoading: false,
				errorMessage: '',
			},
			// Confirmation dialog shown before running a desktop delete.
			// Items are captured at open time so subsequent selection changes
			// don't affect what gets deleted on submit.
			desktopDeleteDialog: {
				visible: false,
				items: [] as any[],
			},
			// Async delete monitor for the desktop. Mirrors content-browser's
			// deleteMonitor; populated only when the helper takes the async
			// path (folders or multi-select).
			desktopDeleteMonitor: null as null | {
				jobId: string;
				handle: DeleteJobHandle;
				itemsTotal: number;
				itemsProcessed: number;
				nodesDeleted: number;
				currentPath: string;
				status: JobStatus;
				errorMessage: string;
				isFinished: boolean;
				isAborting: boolean;
			},
			// Selection state for desktop icons. Source-of-truth lives here so
			// rubber-band, click and context-menu paths stay consistent.
			desktopItems: [] as { id: string; path: string; name: string; isCollection: boolean; mimeType?: string; isReferenceable: boolean; downloadURL?: string | null }[],
			desktopSelectedIds: [] as string[],
			desktopLastSelectedIndex: -1,
			desktopDragOverItemID: null as string | null,
			// Rubber-band selection rectangle, in #desktop-area-relative coords.
			desktopDragSelection: {
				active: false,
				startX: 0,
				startY: 0,
				currentX: 0,
				currentY: 0,
				additive: false,            // true if Ctrl/Meta held at start
				baseSelection: [] as string[],
			},
			// Upload monitor — drives the upload-overlay + cancel button.
			// Shape mirrors content-browser/app.ts so the same template can
			// render for OS uploads, paste and Content Browser drops.
			desktopUploadMonitor: null as null | {
				isCanceled: boolean;
				target: { currentFile: string; progressPercent: number };
				cancel(): void;
			},
			desktopConflictDialog: {
				visible: false,
				resolve: null as null | ((action: string) => void),
			},
			desktopErrorMessage: '',
			// App popup state — single shared instance; only one popup at a time.
			// Apps request a popup via instance.popup.open(); the PopupService
			// (shell-realm) dispatches CustomEvents on document — see below.
			popup: {
				visible: false,
				popupId: null as string | null,
				sourceAppId: null as string | null,
				x: 0,
				y: 0,
				placement: 'bottom-start' as 'bottom-start' | 'bottom-end' | 'top-start' | 'top-end',
				items: [] as any[],            // PopupItem[] | PopupGroup[]
				minWidth: 0,
				maxHeight: 0,
				anchorWidth: 0,
				anchorHeight: 0,
			},
		};
	},
	computed: {
		currentDate() {
			return new Date();
		},
		popupStyle() {
			const p = (this as any).popup;
			const style: Record<string, string> = {
				left: p.x + 'px',
				top: p.y + 'px',
			};
			const min = p.minWidth || p.anchorWidth;
			if (min) style.minWidth = min + 'px';
			if (p.maxHeight) style.maxHeight = p.maxHeight + 'px';
			return style;
		},
		dockEntries() {
			const groups = new Map<string, { app: Application; instances: ApplicationInstance[] }>();
			for (const inst of this.appInstances) {
				const id = inst.app.id;
				let group = groups.get(id);
				if (!group) {
					group = { app: inst.app, instances: [] };
					groups.set(id, group);
				}
				group.instances.push(inst);
			}
			return Array.from(groups.values());
		},
		dockHidden() {
			return (this as any).maximizedWindowIDs.length > 0;
		},
		hoveredDockEntry() {
			const id = (this as any).hoverDockAppID;
			if (!id) return null;
			return (this as any).dockEntries.find(
				(e: { app: Application }) => e.app.id === id,
			) || null;
		},
		desktopSelectionRect() {
			const d = (this as any).desktopDragSelection;
			return {
				left: Math.min(d.startX, d.currentX),
				top: Math.min(d.startY, d.currentY),
				right: Math.max(d.startX, d.currentX),
				bottom: Math.max(d.startY, d.currentY),
			};
		},
		desktopSelectionStyle() {
			const r = (this as any).desktopSelectionRect;
			return {
				left: r.left + 'px',
				top: r.top + 'px',
				width: (r.right - r.left) + 'px',
				height: (r.bottom - r.top) + 'px',
			};
		},
		activeAppName() {
			if (!this.activeAppInstanceID) return '';
			const inst = this.appInstances.find((i: ApplicationInstance) => i.id === this.activeAppInstanceID);
			return inst?.app?.title || '';
		},
	},
	methods: {
		async onMounted() {
			const vm = this;

			// API初期化（デフォルトのテーマと壁紙も適用される）
			await window.Webtop.api.initialize();

			// ゲストユーザーの場合はログインへ
			if (window.Webtop.currentUser.isAnonymous) {
				window.location.href = '/bin/auth.cgi/saml2/login?RelayState=' + encodeURIComponent(window.location.href);
				return;
			}

			vm.username = window.Webtop.currentUser.fullName || window.Webtop.currentUser.id;

			// Load avatar: system workspace takes priority over Identicon
			try {
				const userId = window.Webtop.currentUser.id;
				const avatarNode = await window.Webtop.api.systemContent.getNode(`/home/users/${userId}/avatar`);
				if (avatarNode?.downloadUrl) {
					const ts = avatarNode.modified ? new Date(avatarNode.modified).getTime() : Date.now();
					vm.avatarURL = `url(${avatarNode.downloadUrl}?t=${ts})`;
				} else {
					vm.avatarURL = `url(${await window.Webtop.currentUser.getPhotoURL()})`;
				}
			} catch {
				vm.avatarURL = `url(${await window.Webtop.currentUser.getPhotoURL()})`;
			}

			// Probe the user's Desktop folder. New users get one auto-created at
			// registration time; existing users may not have one yet, in which
			// case drop/paste is rejected with desktopAlert.
			try {
				const userId = window.Webtop.currentUser.id;
				vm.desktopFolderPath = `/home/users/${userId}/Desktop`;
				const desktopNode = await window.Webtop.api.content.getNode(vm.desktopFolderPath);
				vm.hasDesktopFolder = !!desktopNode;
			} catch {
				vm.hasDesktopFolder = false;
			}

			// ユーザーにパーソナライズされたテーマと壁紙を適用
			await window.Webtop.api.theme.applyTheme();
			await window.Webtop.api.wallpaper.applyWallpaper();

			// アプリケーションをロード
			window.Webtop.apps = (await window.Webtop.api.webtop.listApps()).apps;
			vm.sortedApps = window.Webtop.sortedApps;

			// メタデータ定義キャッシュを初期化（バックグラウンド）
			window.Webtop.initMetadataDefinitions().catch((err: any) => {
				console.warn('[Webtop] Failed to initialize metadata definitions cache:', err);
			});
			// I18n バンドルキャッシュを初期化（バックグラウンド）
			window.Webtop.initI18n().catch((err: any) => {
				console.warn('[Webtop] Failed to initialize i18n bundles cache:', err);
			});
			// onTimer内で初回必ず commit される

			// ロゴ設定（アセットを廃止）
			// vm.logoURL = await window.Webtop.api.resource.getResource('asset:logo');

			// 日時表示タイマー開始
			vm.onTimer();
			vm.timerId = setInterval(() => {
				vm.onTimer();
			}, 5000);

			// イベント
			document.addEventListener('app-instance-closed', (event: CustomEvent) => {
				const index = vm.appInstances.findIndex(instance => {
					return instance.id == event.detail.id;
				});
				if (index != -1) {
					vm.appInstances.splice(index, 1);
				}
				if (vm.activeAppInstanceID === event.detail.id) {
					vm.activeAppInstanceID = null;
				}
				const mIdx = vm.maximizedWindowIDs.indexOf(event.detail.id);
				if (mIdx !== -1) {
					vm.maximizedWindowIDs.splice(mIdx, 1);
				}
				const minIdx = vm.minimizedWindowIDs.indexOf(event.detail.id);
				if (minIdx !== -1) {
					vm.minimizedWindowIDs.splice(minIdx, 1);
				}
				if (vm.peekedInstanceID === event.detail.id) {
					vm.clearPeek();
				}
				if (vm.instanceDisplayInfo[event.detail.id]) {
					const next = { ...vm.instanceDisplayInfo };
					delete next[event.detail.id];
					vm.instanceDisplayInfo = next;
				}
			});

			// Apps push display info (subtitle, etc.) via
			// instance.setDisplayInfo(). Keep the value in shell-side
			// reactive state so the Dock preview reflects the latest value
			// without crossing realm boundaries during render.
			document.addEventListener('window-display-info-changed', (event: CustomEvent) => {
				const { id, info } = event.detail || {};
				if (!id) return;
				vm.instanceDisplayInfo = {
					...vm.instanceDisplayInfo,
					[id]: { subtitle: (info && typeof info.subtitle === 'string') ? info.subtitle : '' },
				};
			});

			// ウィンドウ最大化状態の追跡（Dockの表示制御に使用）
			document.addEventListener('window-maximize-changed', (event: CustomEvent) => {
				const { id, maximized } = event.detail;
				const idx = vm.maximizedWindowIDs.indexOf(id);
				if (maximized && idx === -1) {
					vm.maximizedWindowIDs.push(id);
				} else if (!maximized && idx !== -1) {
					vm.maximizedWindowIDs.splice(idx, 1);
				}
			});

			// ウィンドウ最小化状態の追跡（Dockプレビューでの表示に使用）
			document.addEventListener('window-minimize-changed', (event: CustomEvent) => {
				const { id, minimized } = event.detail;
				const idx = vm.minimizedWindowIDs.indexOf(id);
				if (minimized && idx === -1) {
					vm.minimizedWindowIDs.push(id);
				} else if (!minimized && idx !== -1) {
					vm.minimizedWindowIDs.splice(idx, 1);
				}
			});

			// アクティブウィンドウの追跡
			document.addEventListener('window-activated', (event: CustomEvent) => {
				vm.activeAppInstanceID = event.detail.id;
			});
			document.addEventListener('window-deactivated', (event: CustomEvent) => {
				if (vm.activeAppInstanceID === event.detail.id) {
					vm.activeAppInstanceID = null;
				}
			});

			// iframeからのメッセージを受信
			// Messages from iframes to the main window (e.g. context menu, open-app)
			window.addEventListener('message', (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const { type, ...payload } = event.data || {};
				if (type === 'show-context-menu') {
					vm.showContextMenu(payload);
				} else if (type === 'open-file-with-app') {
					vm.openFileWithApp(payload);
				} else if (type === 'open-app') {
					vm.openAppWithOptions(payload);
				} else if (type === 'window-control') {
					vm.handleWindowControl(event.source as Window | null, payload);
				}
			});

			// App popup events — dispatched in shell-realm by PopupService
			// (bundled with the shell). See services/webtop-service.ts.
			document.addEventListener('webtop-popup-show', (e: Event) => {
				vm.showPopup((e as CustomEvent).detail || {});
			});
			document.addEventListener('webtop-popup-update', (e: Event) => {
				vm.updatePopup((e as CustomEvent).detail || {});
			});
			document.addEventListener('webtop-popup-close', (e: Event) => {
				vm.closePopup((e as CustomEvent).detail || {});
			});

			// Dismiss popup on Escape pressed in the shell.
			document.addEventListener('keydown', (e: KeyboardEvent) => {
				if (e.key === 'Escape' && vm.popup.visible) {
					vm.dismissPopup(null);
				}
			});

			// Clipboard paste targeting the desktop background. Inputs and
			// editable elements are skipped so app-internal paste keeps working.
			document.addEventListener('paste', (e: ClipboardEvent) => {
				const target = e.target as HTMLElement | null;
				if (target && (target.matches('input, textarea, [contenteditable="true"]')
					|| target.closest('input, textarea, [contenteditable="true"]'))) {
					return;
				}
				vm.onDesktopAreaPaste(e);
			});

			// wt-desktop-icons → shell. The component is in shell-realm but
			// keeps no selection/UI state of its own; CustomEvents on document
			// drive selection, context menu, and folder-drop pipelines.
			document.addEventListener('webtop-desktop-items-changed', (e: Event) => {
				const items = (e as CustomEvent).detail?.items || [];
				vm.desktopItems = items;
				// Drop selectedIds whose items disappeared.
				const stillExists = new Set(items.map((i: any) => i.id));
				vm.desktopSelectedIds = vm.desktopSelectedIds.filter((id: string) => stillExists.has(id));
				if (vm.desktopLastSelectedIndex >= items.length) {
					vm.desktopLastSelectedIndex = -1;
				}
			});
			document.addEventListener('webtop-desktop-icon-mousedown', (e: Event) => {
				const d = (e as CustomEvent).detail || {};
				vm.onDesktopIconSelect(d.itemId, { ctrlKey: !!d.ctrlKey, shiftKey: !!d.shiftKey });
			});
			document.addEventListener('webtop-desktop-icon-contextmenu', (e: Event) => {
				const d = (e as CustomEvent).detail || {};
				vm.onDesktopIconContextMenu(d.itemId, d.x, d.y);
			});
			document.addEventListener('webtop-desktop-icon-dragover', (e: Event) => {
				const d = (e as CustomEvent).detail || {};
				vm.desktopDragOverItemID = d.itemId;
			});
			document.addEventListener('webtop-desktop-icon-dragleave', (e: Event) => {
				const d = (e as CustomEvent).detail || {};
				if (vm.desktopDragOverItemID === d.itemId) vm.desktopDragOverItemID = null;
			});
			document.addEventListener('webtop-desktop-icon-dragend', () => {
				vm.desktopDragOverItemID = null;
			});
			document.addEventListener('webtop-desktop-icon-drop', (e: Event) => {
				const d = (e as CustomEvent).detail || {};
				vm.desktopDragOverItemID = null;
				vm.onDesktopIconDrop(d.path, d.ctrlKey, d.osItems);
			});

			// Broadcasts from WebtopServiceGraphQL.postMessage (via CustomEvent)
			document.addEventListener('webtop-message', (event: CustomEvent) => {
				const { type, ...payload } = (event.detail as any) || {};
				if (type === 'profile-changed') {
					if (payload.displayName) {
						vm.username = payload.displayName;
					}
				} else if (type === 'avatar-changed') {
					const userId = window.Webtop.currentUser?.id;
					if (userId) {
						window.Webtop.api.systemContent.getNode(`/home/users/${userId}/avatar`).then(async node => {
							if (node?.downloadUrl) {
								vm.avatarURL = `url(${node.downloadUrl}?t=${Date.now()})`;
							} else {
								vm.avatarURL = `url(${await window.Webtop.currentUser.getPhotoURL()})`;
							}
						}).catch(async () => {
							vm.avatarURL = `url(${await window.Webtop.currentUser.getPhotoURL()})`;
						});
					}
				}
			});

			// 起動完了
			vm.isReady = true;

			// Show webtop screen and hide boot screen
			const webtopScreen = document.getElementById('webtop');
			if (webtopScreen) {
				webtopScreen.style.display = 'block';
			}
			vm.$nextTick(() => {
				const bootScreen = document.getElementById('boot');
				if (bootScreen) {
					bootScreen.style.display = 'none';
				}
			});

			// Check for saved sessions and show picker if any exist
			vm.checkForSessions().catch(e => console.warn('[Webtop] Session check failed:', e));
		},
		async onUnmount() {
			const vm = this;

			// 日時表示タイマー終了
			if (vm.timerId) {
				clearInterval(vm.timerId);
				vm.timerId = null;
			}
		},
		onTimer() {
			const vm = this;

			const now = new Date();
			const locale = navigator.language || 'en-US';

			const dateText = now.toLocaleString(locale, {
				weekday: 'short',
				year: undefined,
				month: 'short',
				day: 'numeric'
			});
			const timeText = now.toLocaleString(locale, {
				hour: 'numeric',
				minute: 'numeric'
			});

			if (vm.displayDate != dateText || vm.displayTime != timeText) {
				vm.displayDate = dateText;
				vm.displayTime = timeText;
			}

			window.Webtop.api.theme.applyTheme();

			window.Webtop.api.webtop.postMessage({ type: 'theme-tick' });
		},
		toggleMenu() {
			const vm = this;
			vm.isMenuOpen = !vm.isMenuOpen;
		},
		iconURL(app: Application) {
			if (!app?.icon) {
				return '';
			}
			return `./apps/${app.relPath}/${app.icon}?v=${BUILD_VERSION}`;
		},
		// Pick a launch position that doesn't exactly overlap an already-open
		// window. We cascade by (CASCADE_STEP, CASCADE_STEP) until we find a
		// free (x,y), wrapping back near the origin if we walk off-screen.
		// Matching by exact coordinates is intentional — mirroring native OS
		// behavior — so manually-moved windows don't poison new launches.
		computeInitialWindowPosition() {
			const vm = this as any;
			const BASE_X = 100, BASE_Y = 100, CASCADE_STEP = 30, MAX_ITER = 50;
			const used = new Set<string>();
			for (const inst of vm.appInstances) {
				const s = (inst as any)._sessionState;
				if (s && typeof s.x === 'number' && typeof s.y === 'number') {
					used.add(`${s.x},${s.y}`);
					continue;
				}
				const init = (inst as any)._initialWindowState;
				if (init && typeof init.x === 'number' && typeof init.y === 'number') {
					used.add(`${init.x},${init.y}`);
				}
			}
			let x = BASE_X, y = BASE_Y, i = 0;
			while (used.has(`${x},${y}`) && i < MAX_ITER) {
				i++;
				x = BASE_X + ((i * CASCADE_STEP) % Math.max(CASCADE_STEP, window.innerWidth - BASE_X - 200));
				y = BASE_Y + ((i * CASCADE_STEP) % Math.max(CASCADE_STEP, window.innerHeight - BASE_Y - 200));
			}
			return { x, y };
		},
		async openApp(app: Application) {
			// const url = window.Webtop.getFullPath(`/apps/${app.relPath}/`);
			// window.open(url, '_blank');
			const vm = this;
			vm.isMenuOpen = false;
			if (app.singleton) {
				const existing = vm.appInstances.find((i: ApplicationInstance) => i.app.id === app.id);
				if (existing) {
					vm.restoreWindow(existing);
					return;
				}
			}
			const instance = new ApplicationInstance(app, window.Webtop);
			const pos = vm.computeInitialWindowPosition();
			const saved = await loadSavedWindowSize(app.id);
			(instance as any)._initialWindowState = saved
				? { ...pos, width: saved.width, height: saved.height }
				: pos;
			// Mark instance as raw before adding to reactive array
			// This prevents ApplicationInstance (which has private fields) from being wrapped in Proxy
			vm.appInstances.push(this.$markRaw(instance));
		},
		restoreWindow(instance: ApplicationInstance) {
			document.dispatchEvent(new CustomEvent('restore-window', { detail: { id: instance.id } }));
		},
		dockItemClick(instance: ApplicationInstance) {
			const vm = this as any;
			vm.clearPeek();
			vm.hoverDockAppID = null;
			this.restoreWindow(instance);
		},
		dockIconClick(entry: { app: Application; instances: ApplicationInstance[] }) {
			// Single-instance shortcut: clicking the icon directly restores the
			// window. With multiple instances, the user picks via the hover
			// preview cards instead.
			const vm = this as any;
			if (entry.instances.length === 1) {
				vm.clearPeek();
				vm.hoverDockAppID = null;
				this.restoreWindow(entry.instances[0]);
			}
		},
		instanceSubtitle(instance: ApplicationInstance): string {
			const info = this.instanceDisplayInfo[instance.id];
			return info?.subtitle || '';
		},
		onDockEntryEnter(entry: { app: Application; instances: ApplicationInstance[] }, event?: MouseEvent) {
			const vm = this as any;
			if (vm._dockHideTimer) {
				clearTimeout(vm._dockHideTimer);
				vm._dockHideTimer = null;
			}
			vm.hoverDockAppID = entry.app.id;
			const target = event?.currentTarget as HTMLElement | undefined;
			if (!target) return;
			// Anchor the global #dock-preview to this icon. We use position:
			// fixed (set in CSS) so the preview lives outside #dock's
			// stacking context — required for backdrop-filter to see the
			// desktop behind it. bottom = (viewport_h - icon_top) puts the
			// preview's bottom edge exactly at the icon's top edge.
			const iconRect = target.getBoundingClientRect();
			const previewBottom = window.innerHeight - iconRect.top;
			const anchorX = iconRect.left + iconRect.width / 2;
			vm.$nextTick(() => {
				const preview = document.getElementById('dock-preview');
				if (!preview) return;
				preview.style.bottom = previewBottom + 'px';
				preview.style.left = anchorX + 'px';
				// Clamp horizontal position so the preview stays inside the
				// viewport even when many windows force it to wrap or push
				// it past a screen edge.
				preview.style.setProperty('--preview-shift', '0px');
				const rect = preview.getBoundingClientRect();
				const margin = 8;
				let dx = 0;
				if (rect.left < margin) {
					dx = margin - rect.left;
				} else if (rect.right > window.innerWidth - margin) {
					dx = (window.innerWidth - margin) - rect.right;
				}
				if (dx !== 0) {
					preview.style.setProperty('--preview-shift', dx + 'px');
				}
			});
		},
		onDockEntryLeave() {
			const vm = this as any;
			if (vm._dockHideTimer) clearTimeout(vm._dockHideTimer);
			vm._dockHideTimer = setTimeout(() => {
				vm.hoverDockAppID = null;
				vm.clearPeek();
				vm._dockHideTimer = null;
			}, 150);
		},
		onPreviewEnter() {
			const vm = this as any;
			if (vm._dockHideTimer) {
				clearTimeout(vm._dockHideTimer);
				vm._dockHideTimer = null;
			}
		},
		onPreviewItemEnter(instance: ApplicationInstance) {
			const vm = this as any;
			// Minimized windows don't peek — the card itself shows the state.
			if (vm.minimizedWindowIDs.indexOf(instance.id) !== -1) {
				vm.clearPeek();
				return;
			}
			vm.peekedInstanceID = instance.id;
			document.dispatchEvent(new CustomEvent('window-peek-set', {
				detail: { id: instance.id },
			}));
		},
		clearPeek() {
			const vm = this as any;
			if (vm.peekedInstanceID === null) return;
			vm.peekedInstanceID = null;
			document.dispatchEvent(new CustomEvent('window-peek-clear'));
		},
		selectApp(_app: Application) {
			// Placeholder for future app preview logic
		},
		signOut() {
			sessionStorage.clear();
			window.location.href = '/bin/auth.cgi/saml2/logout?RelayState=' + encodeURIComponent('/');
		},
		showSaveSessionDialog() {
			const vm = this;
			vm.sessionNameInput = new Date().toLocaleString(navigator.language || 'en-US', {
				month: 'short', day: 'numeric', hour: 'numeric', minute: 'numeric',
			});
			vm.showSaveSessionOverlay = true;
			vm.isMenuOpen = false;
			vm.$nextTick(() => {
				(vm.$refs.sessionNameInput as HTMLInputElement)?.focus();
			});
		},
		cancelSaveSession() {
			this.showSaveSessionOverlay = false;
			this.sessionNameInput = '';
		},
		async confirmSaveSession() {
			const vm = this;
			const name = vm.sessionNameInput.trim() || 'Session';
			vm.showSaveSessionOverlay = false;
			vm.sessionNameInput = '';
			try {
				const windows = vm.captureWindowStates();
				await window.Webtop.api.session.saveSession(name, windows);
			} catch (e) {
				console.warn('[Webtop] Failed to save session:', e);
			}
			vm.signOut();
		},
		captureWindowStates() {
			return this.appInstances
				.filter((inst: any) => inst._sessionState)
				.map((inst: any) => ({
					appId: inst.app.id,
					x: inst._sessionState.x,
					y: inst._sessionState.y,
					width: inst._sessionState.width,
					height: inst._sessionState.height,
					zIndex: inst._sessionState.zIndex,
					maximized: inst._sessionState.maximized || false,
					launchOptions: { ...(inst.launchOptions || {}), ...(typeof inst.appState === 'function' ? inst.appState() : {}) },
				}))
				.sort((a: any, b: any) => a.zIndex - b.zIndex);
		},
		async checkForSessions() {
			const vm = this;
			if (window.Webtop.currentUser?.isAnonymous) return;
			const sessions = await window.Webtop.api.session.listSessions();
			if (sessions.length === 0) return;
			vm.sessionPickerList = sessions;
			vm.selectedSessionID = sessions[0]?.id ?? null;
			vm.showSessionPicker = true;
		},
		selectSession(id: string) {
			this.selectedSessionID = id;
		},
		skipSessionRestore() {
			this.showSessionPicker = false;
			this.sessionPickerList = [];
			this.selectedSessionID = null;
		},
		async restoreSelectedSession() {
			const vm = this;
			const entry = vm.sessionPickerList.find((s: SessionEntry) => s.id === vm.selectedSessionID);
			if (!entry) return;
			vm.showSessionPicker = false;
			vm.restoringSession = true;
			try {
				const data: SessionData = await window.Webtop.api.session.loadSession(entry.downloadUrl);
				// Restore windows in z-order (bottom to top)
				const windows = [...data.windows].sort((a, b) => (a.zIndex ?? 0) - (b.zIndex ?? 0));
				for (const win of windows) {
					const app = window.Webtop.apps.find((a: Application) => a.id === win.appId);
					if (!app) continue;
					const scaled = window.Webtop.api.session.scaleWindowState(win, data.environment);
					const instance = new ApplicationInstance(app, window.Webtop);
					(instance as any)._initialWindowState = {
						x: scaled.x, y: scaled.y, width: scaled.width, height: scaled.height,
					};
					if (win.launchOptions) (instance as any).launchOptions = win.launchOptions;
					vm.appInstances.push(vm.$markRaw(instance));
					// Allow ichigo.js to process each window before creating the next
					await new Promise(r => setTimeout(r, 30));
				}
			} catch (e) {
				console.warn('[Webtop] Failed to restore session:', e);
			} finally {
				vm.restoringSession = false;
			}
		},
		formatDate(dateStr: string): string {
			try {
				return new Date(dateStr).toLocaleString(navigator.language || 'en-US');
			} catch {
				return dateStr;
			}
		},
		windowOptions(appInstance: ApplicationInstance) {
			const opts: any = { appInstance };
			const state = (appInstance as any)._initialWindowState;
			if (state) {
				opts.x = state.x;
				opts.y = state.y;
				opts.width = state.width;
				opts.height = state.height;
			}
			return opts;
		},
		// Context menu methods
		showContextMenu(payload: { x: number; y: number; items: any[]; sourceAppId?: string | null; onAction?: (id: string) => void }) {
			const vm = this;

			// iframeの位置を取得して座標を補正
			let actualX = payload.x;
			let actualY = payload.y;
			if (payload.sourceAppId) {
				const iframe = document.querySelector(`iframe[data-app-id="${payload.sourceAppId}"]`) as HTMLIFrameElement;
				if (iframe) {
					const iframeRect = iframe.getBoundingClientRect();
					actualX = payload.x + iframeRect.left;
					actualY = payload.y + iframeRect.top;
				}
			}

			// 画面端からはみ出さないように位置を調整
			const menuWidth = 200;
			const menuHeight = payload.items.length * 32 + 16;
			const maxX = window.innerWidth - menuWidth;
			const maxY = window.innerHeight - menuHeight;

			vm.contextMenu.x = Math.min(actualX, maxX);
			vm.contextMenu.y = Math.min(actualY, maxY);
			vm.contextMenu.items = payload.items;
			vm.contextMenu.sourceAppId = payload.sourceAppId || null;
			vm.contextMenu.onAction = payload.onAction || null;
			vm.contextMenu.visible = true;
		},
		hideContextMenu() {
			const vm = this;
			vm.contextMenu.visible = false;
			vm.contextMenu.items = [];
			vm.contextMenu.sourceAppId = null;
			vm.contextMenu.onAction = null;
		},
		onContextMenuAction(action: string) {
			const vm = this;
			const sourceAppId = vm.contextMenu.sourceAppId;
			const onAction = vm.contextMenu.onAction;
			vm.hideContextMenu();

			// Shell-realm dispatch (e.g. desktop icons) — handled inline.
			if (onAction) {
				try { onAction(action); } catch (err) { console.warn('[Webtop] context menu action failed:', err); }
				return;
			}

			// アクションをソースアプリに通知
			if (sourceAppId) {
				// 該当するiframeを見つけてメッセージを送信
				const appInstance = vm.appInstances.find((inst: ApplicationInstance) => inst.id === sourceAppId);
				if (appInstance) {
					const iframe = document.querySelector(`iframe[data-app-id="${sourceAppId}"]`) as HTMLIFrameElement;
					if (iframe?.contentWindow) {
						iframe.contentWindow.postMessage({
							type: 'context-menu-action',
							action: action
						}, window.location.origin);
					}
				}
			}
		},
		// ----------------------------------------------------------------
		// App popup (iframe-escaping dropdown / menu)
		// ----------------------------------------------------------------
		_attachPopupEscListeners() {
			const vm = this;
			popupIframeEscHandler = (e: KeyboardEvent) => {
				if (e.key === 'Escape' && vm.popup.visible) {
					e.preventDefault();
					e.stopPropagation();
					vm.dismissPopup(null);
				}
			};
			// Same-origin iframes only — we deliberately don't attach
			// to cross-origin iframes (would throw on contentDocument access).
			for (const iframe of Array.from(document.querySelectorAll('iframe[data-app-id]')) as HTMLIFrameElement[]) {
				try {
					iframe.contentDocument?.addEventListener('keydown', popupIframeEscHandler, true);
				} catch { /* cross-origin — ignore */ }
			}
		},
		_detachPopupEscListeners() {
			if (!popupIframeEscHandler) return;
			for (const iframe of Array.from(document.querySelectorAll('iframe[data-app-id]')) as HTMLIFrameElement[]) {
				try {
					iframe.contentDocument?.removeEventListener('keydown', popupIframeEscHandler, true);
				} catch { /* ignore */ }
			}
			popupIframeEscHandler = null;
		},
		showPopup(payload: any) {
			const vm = this;
			const iframe = document.querySelector(`iframe[data-app-id="${payload.sourceAppId}"]`) as HTMLIFrameElement | null;
			if (!iframe) return;
			const iframeRect = iframe.getBoundingClientRect();
			const a = payload.anchor || {};
			// Translate iframe-local anchor to page coords
			const pageLeft = iframeRect.left + (a.left ?? 0);
			const pageTop = iframeRect.top + (a.top ?? 0);
			const pageRight = iframeRect.left + (a.right ?? a.left + (a.width ?? 0));
			const pageBottom = iframeRect.top + (a.bottom ?? a.top + (a.height ?? 0));
			const placement = payload.placement || 'bottom-start';

			let x = pageLeft;
			let y = pageBottom + 4;
			if (placement === 'bottom-end') { x = pageRight; }
			if (placement === 'top-start') { y = pageTop - 4; }
			if (placement === 'top-end') { x = pageRight; y = pageTop - 4; }

			vm.popup.visible = true;
			vm.popup.popupId = payload.popupId;
			vm.popup.sourceAppId = payload.sourceAppId;
			vm.popup.x = x;
			vm.popup.y = y;
			vm.popup.placement = placement;
			vm.popup.items = payload.items || [];
			vm.popup.minWidth = payload.minWidth || 0;
			vm.popup.maxHeight = payload.maxHeight || 0;
			vm.popup.anchorWidth = a.width || 0;
			vm.popup.anchorHeight = a.height || 0;
			// The originating click stays focused inside its iframe, so
			// shell-document keydown won't see Escape. Attach a capture
			// listener directly on every same-origin iframe document.
			(vm as any)._attachPopupEscListeners();
		},
		updatePopup(payload: any) {
			const vm = this;
			if (vm.popup.popupId !== payload.popupId) return;
			vm.popup.items = payload.items || [];
		},
		closePopup(payload?: any) {
			const vm = this;
			if (payload && payload.popupId && payload.popupId !== vm.popup.popupId) return;
			vm.dismissPopup(null);
		},
		dismissPopup(itemId: string | number | null) {
			const vm = this;
			const popupId = vm.popup.popupId;
			vm.popup.visible = false;
			vm.popup.popupId = null;
			vm.popup.sourceAppId = null;
			vm.popup.items = [];
			(vm as any)._detachPopupEscListeners();
			if (popupId) {
				// Deliver result via CustomEvent — PopupService runs in the
				// shell realm (same document), so window postMessage would miss.
				document.dispatchEvent(new CustomEvent('webtop-popup-result', {
					detail: { popupId, itemId },
				}));
			}
		},
		onPopupItemClick(item: any, index: number) {
			if (item.disabled) return;
			const id = item.id ?? index;
			this.dismissPopup(id);
		},
		onPopupItemActionClick(item: any, index: number, action: any) {
			const vm = this;
			const popupId = vm.popup.popupId;
			if (!popupId) return;
			const itemId = item.id ?? index;
			document.dispatchEvent(new CustomEvent('webtop-popup-action', {
				detail: { popupId, kind: 'item', itemId, actionId: action.id },
			}));
		},
		onPopupGroupActionClick(group: any, action: any) {
			const vm = this;
			const popupId = vm.popup.popupId;
			if (!popupId) return;
			document.dispatchEvent(new CustomEvent('webtop-popup-action', {
				detail: { popupId, kind: 'group', groupLabel: group.label, actionId: action.id },
			}));
		},
		isPopupGrouped(): boolean {
			const items = this.popup.items;
			return items.length > 0 && items[0] && 'items' in items[0];
		},
		async openAppWithOptions(payload: { appId: string; options?: Record<string, any> }) {
			const vm = this;
			const app = window.Webtop.apps.find((a: Application) => a.id === payload.appId);
			if (!app) {
				console.warn(`[Webtop] App not found: ${payload.appId}`);
				return;
			}
			const instance = new ApplicationInstance(app, window.Webtop);
			// If the caller pre-computed an exact placement (e.g. text-editor
			// opens its preview to its own right), honor that verbatim and
			// skip the cascade/saved-size logic.
			const requested = payload.options?.initialWindowState;
			if (requested && typeof requested.x === 'number' && typeof requested.y === 'number'
				&& typeof requested.width === 'number' && typeof requested.height === 'number') {
				(instance as any)._initialWindowState = { ...requested };
			} else {
				const pos = vm.computeInitialWindowPosition();
				const saved = await loadSavedWindowSize(app.id);
				(instance as any)._initialWindowState = saved
					? { ...pos, width: saved.width, height: saved.height }
					: pos;
			}
			if (payload.options) {
				(instance as any).launchOptions = payload.options;
			}
			vm.appInstances.push(vm.$markRaw(instance));
		},
		handleWindowControl(source: Window | null, payload: { action: string; id?: string }) {
			const vm = this;
			// Identify the requesting instance: prefer explicit id, otherwise
			// match the iframe whose contentWindow sent the message.
			let instance: ApplicationInstance | undefined;
			if (payload.id) {
				instance = vm.appInstances.find((i: ApplicationInstance) => i.id === payload.id);
			}
			if (!instance && source) {
				for (const iframe of Array.from(document.querySelectorAll('iframe[data-app-id]')) as HTMLIFrameElement[]) {
					if (iframe.contentWindow === source) {
						const id = iframe.getAttribute('data-app-id');
						instance = vm.appInstances.find((i: ApplicationInstance) => i.id === id);
						break;
					}
				}
			}
			if (!instance) return;
			switch (payload.action) {
				case 'maximize': instance.maximize(); break;
				case 'minimize': instance.minimize(); break;
				case 'restore': instance.restore(); break;
				case 'toggle-maximize': instance.toggleMaximize(); break;
				case 'close': instance.requestClose(); break;
			}
		},
		async openFileWithApp(payload: { appId: string; filePath: string; mimeType: string }) {
			const vm = this;
			// Find the app by ID
			const app = window.Webtop.apps.find((a: Application) => a.id === payload.appId);
			if (!app) {
				console.warn(`[Webtop] App not found: ${payload.appId}`);
				return;
			}

			// Create new instance with launch options
			const instance = new ApplicationInstance(app, window.Webtop);
			const pos = vm.computeInitialWindowPosition();
			const saved = await loadSavedWindowSize(app.id);
			(instance as any)._initialWindowState = saved
				? { ...pos, width: saved.width, height: saved.height }
				: pos;
			// Store launch options for the window component to pass to the app
			(instance as any).launchOptions = {
				path: payload.filePath,
				mimeType: payload.mimeType,
			};
			vm.appInstances.push(vm.$markRaw(instance));
		},
		// =====================================================================
		// Desktop drag/drop, paste, selection, context menu, upload progress.
		// =====================================================================
		_hasInternalDragData(event: DragEvent): boolean {
			if (event.dataTransfer?.types && Array.from(event.dataTransfer.types).includes('application/x-webtop-files')) return true;
			try { if ((window as any).__webtopDragData) return true; } catch { /* ignore */ }
			return false;
		},
		_getInternalDragItems(event: DragEvent): { path: string; name: string; isCollection: boolean }[] | null {
			try {
				const raw = event.dataTransfer?.getData('application/x-webtop-files');
				if (raw) return JSON.parse(raw);
			} catch { /* ignore */ }
			try {
				const parentData = (window as any).__webtopDragData;
				if (parentData?.items) return parentData.items;
			} catch { /* ignore */ }
			return null;
		},
		_isInternalDropForbidden(event: DragEvent): boolean {
			const vm = this as any;
			try {
				const parentData = (window as any).__webtopDragData;
				if (parentData && parentData.sourceFolderPath === vm.desktopFolderPath) {
					// Same folder: Ctrl (copy) duplicates, plain move is forbidden (no-op).
					return !event.ctrlKey;
				}
			} catch { /* ignore */ }
			return false;
		},
		// Desktop background drop target. Accepts OS file drops AND internal
		// Webtop drags (e.g. dragged out of Content Browser).
		onDesktopAreaDragOver(event: DragEvent) {
			const vm = this as any;
			if (!event.dataTransfer) return;
			const types = Array.from(event.dataTransfer.types || []);
			const hasInternal = vm._hasInternalDragData(event);
			const hasFiles = types.includes('Files');
			if (!hasInternal && !hasFiles) return;
			event.preventDefault();
			if (hasInternal) {
				if (vm._isInternalDropForbidden(event)) {
					event.dataTransfer.dropEffect = 'none';
				} else {
					event.dataTransfer.dropEffect = event.ctrlKey ? 'copy' : 'move';
				}
			} else {
				event.dataTransfer.dropEffect = 'copy';
			}
		},
		async onDesktopAreaDrop(event: DragEvent) {
			const vm = this as any;
			if (!event.dataTransfer) return;
			const types = Array.from(event.dataTransfer.types || []);
			const hasInternal = vm._hasInternalDragData(event);
			const hasFiles = types.includes('Files');
			if (!hasInternal && !hasFiles) return;
			event.preventDefault();

			if (!vm.hasDesktopFolder) {
				vm.showMissingDesktopAlert();
				return;
			}

			if (hasInternal) {
				if (vm._isInternalDropForbidden(event)) return;
				const items = vm._getInternalDragItems(event);
				if (!items || items.length === 0) return;
				await vm._executeInternalDropToDesktop(items, vm.desktopFolderPath, event.ctrlKey);
				try { (window as any).__webtopDragData = null; } catch { /* ignore */ }
				return;
			}

			// OS file drop — collect entries (preserves directory structure).
			const itemList = event.dataTransfer.items;
			if (itemList && itemList.length > 0) {
				const entries = Array.from(itemList).map(i => (i as any).webkitGetAsEntry?.()).filter(e => e);
				if (entries.length > 0) {
					const files: { file: File; relPath: string }[] = [];
					for (const entry of entries) {
						await vm._collectFileEntry(entry, '', files);
					}
					await vm.uploadFilesToDesktop(files);
					return;
				}
			}
			const files = Array.from(event.dataTransfer.files || []);
			if (files.length > 0) {
				await vm.uploadFilesToDesktop(files.map((f: File) => ({ file: f, relPath: f.name })));
			}
		},
		async onDesktopAreaPaste(event: ClipboardEvent) {
			const vm = this as any;
			if (!event.clipboardData) return;
			const files = Array.from(event.clipboardData.files || []);
			if (files.length === 0) return;
			event.preventDefault();

			if (!vm.hasDesktopFolder) {
				vm.showMissingDesktopAlert();
				return;
			}
			await vm.uploadFilesToDesktop(files.map((f: File) => ({ file: f, relPath: f.name })));
		},
		// Drop on a folder icon — same logic as drop on the desktop background
		// but the destination is the inner folder.
		async onDesktopIconDrop(targetPath: string, isCopy: boolean, _osItems?: any[]) {
			const vm = this as any;
			if (!vm.hasDesktopFolder) return;
			// Internal drag is the common case. OS files dropped on a folder
			// icon: not currently supported (would require holding the
			// DataTransfer until after async work; we keep desktop-area drop
			// handling for now).
			const data = (window as any).__webtopDragData;
			const items = data?.items;
			if (!items || items.length === 0) return;
			if (data.sourceFolderPath === targetPath && !isCopy) return;
			await vm._executeInternalDropToDesktop(items, targetPath, isCopy);
			try { (window as any).__webtopDragData = null; } catch { /* ignore */ }
		},
		async _collectFileEntry(entry: any, prefix: string, out: { file: File; relPath: string }[]) {
			const full = prefix ? `${prefix}/${entry.name}` : entry.name;
			if (entry.isFile) {
				await new Promise<void>((resolve, reject) => {
					entry.file((f: File) => { out.push({ file: f, relPath: full }); resolve(); }, reject);
				});
			} else if (entry.isDirectory) {
				const reader = entry.createReader();
				await new Promise<void>((resolve, reject) => {
					const read = () => reader.readEntries(async (ents: any[]) => {
						if (!ents.length) { resolve(); return; }
						for (const e of ents) {
							await this._collectFileEntry(e, full, out);
						}
						read();
					}, reject);
					read();
				});
			}
		},
		// ---------------------------------------------------------------------
		// Upload pipeline (OS files / paste / Content Browser drop reused this).
		// ---------------------------------------------------------------------
		async uploadFilesToDesktop(files: { file: File; relPath: string }[]) {
			const vm = this as any;
			if (vm.desktopUploadMonitor) return;
			if (files.length === 0) return;

			const userId = window.Webtop.currentUser.id;
			const parentPath = `/home/users/${userId}/Desktop`;
			const contentService = window.Webtop.api.content;
			const chunkSize = 524288; // 512KB chunks (Base64 encoded ~700KB)

			vm.desktopErrorMessage = '';
			vm.desktopUploadMonitor = {
				isCanceled: false,
				target: { currentFile: '', progressPercent: 0 },
				cancel() { this.isCanceled = true; },
			};

			let overwriteAll = false;
			let skipAll = false;

			try {
				for (const info of files) {
					if (vm.desktopUploadMonitor.isCanceled) break;
					vm.desktopUploadMonitor.target.currentFile = info.relPath;
					vm.desktopUploadMonitor.target.progressPercent = 0;

					// Ensure parent subdirectories exist (preserves OS folder structure).
					const parts = info.relPath.split('/');
					const filename = parts.pop() as string;
					const dir = parts.join('/');
					const folderPath = parentPath + (dir ? '/' + dir : '');
					await vm._ensureDesktopFolder(folderPath);

					const destPath = folderPath + '/' + filename;
					const existing = await contentService.getNode(destPath).catch(() => null);
					let nameToUse = filename;
					if (existing) {
						let action = overwriteAll ? 'overwrite' : skipAll ? 'skip' : await vm.askDesktopConflict(info.relPath);
						if (action === 'overwriteAll') { overwriteAll = true; action = 'overwrite'; }
						if (action === 'skipAll') { skipAll = true; action = 'skip'; }
						if (action === 'cancel') { vm.desktopUploadMonitor.cancel(); break; }
						if (action === 'skip') continue;
						if (action === 'overwrite') {
							try { await contentService.deleteNode(destPath); } catch { /* fall through to overwrite */ }
						}
					}

					const uploadInfo = await contentService.initiateMultipartUpload();
					const uploadId = uploadInfo.uploadId;
					try {
						const totalSize = info.file.size;
						let offset = 0;
						while (offset < totalSize && !vm.desktopUploadMonitor.isCanceled) {
							const blob = info.file.slice(offset, offset + chunkSize);
							const encoded = await new Promise<string>((resolve, reject) => {
								const reader = new FileReader();
								reader.onloadend = (e) => {
									const result = (e.target as FileReader).result as string;
									resolve(result.substring(result.indexOf(';base64,') + 8));
								};
								reader.onerror = () => reject(reader.error);
								reader.readAsDataURL(blob);
							});
							await contentService.appendMultipartUploadChunk(uploadId, encoded);
							offset += chunkSize;
							vm.desktopUploadMonitor.target.progressPercent = totalSize > 0
								? Math.min(100, Math.floor((offset / totalSize) * 100))
								: 100;
						}
						if (vm.desktopUploadMonitor.isCanceled) {
							try { await contentService.abortMultipartUpload(uploadId); } catch { /* ignore */ }
							break;
						}
						await contentService.completeMultipartUpload(
							uploadId,
							folderPath,
							nameToUse,
							info.file.type || 'application/octet-stream',
							false,
						);
					} catch (error: any) {
						try { await contentService.abortMultipartUpload(uploadId); } catch { /* ignore */ }
						if (!vm.desktopUploadMonitor.isCanceled) {
							vm.desktopErrorMessage = error?.message || String(error) || 'Upload failed';
						}
						break;
					}
				}
			} finally {
				if (!vm.desktopErrorMessage) {
					vm.desktopUploadMonitor = null;
				}
				document.dispatchEvent(new CustomEvent('webtop-desktop-reload'));
			}
		},
		closeDesktopUpload() {
			const vm = this as any;
			vm.desktopUploadMonitor = null;
			vm.desktopErrorMessage = '';
		},
		askDesktopConflict(path: string): Promise<string> {
			const vm = this as any;
			return new Promise<string>((resolve) => {
				vm.desktopUploadMonitor.target.currentFile = path;
				vm.desktopConflictDialog.resolve = resolve;
				vm.desktopConflictDialog.visible = true;
			});
		},
		onDesktopConflictAction(action: string) {
			const vm = this as any;
			vm.desktopConflictDialog.visible = false;
			const resolve = vm.desktopConflictDialog.resolve;
			vm.desktopConflictDialog.resolve = null;
			if (resolve) resolve(action);
		},
		async _ensureDesktopFolder(path: string) {
			const contentService = window.Webtop.api.content;
			const userId = window.Webtop.currentUser.id;
			const root = `/home/users/${userId}/Desktop`;
			if (path === root) return;
			const existing = await contentService.getNode(path).catch(() => null);
			if (existing) return;
			const slash = path.lastIndexOf('/');
			const parent = path.substring(0, slash);
			const name = path.substring(slash + 1);
			await this._ensureDesktopFolder(parent);
			await contentService.createFolder(parent, name).catch(() => null);
		},
		// ---------------------------------------------------------------------
		// Internal move/copy from Content Browser → Desktop folder/icon.
		// ---------------------------------------------------------------------
		async _executeInternalDropToDesktop(
			dragItems: { path: string; name: string; isCollection: boolean }[],
			destPath: string,
			isCopy: boolean,
		) {
			const vm = this as any;
			const contentService = window.Webtop.api.content;
			vm.desktopErrorMessage = '';
			vm.desktopUploadMonitor = {
				isCanceled: false,
				target: { currentFile: '', progressPercent: 0 },
				cancel() { this.isCanceled = true; },
			};
			let overwriteAll = false;
			let skipAll = false;

			try {
				for (const dragItem of dragItems) {
					if (vm.desktopUploadMonitor.isCanceled) break;
					vm.desktopUploadMonitor.target.currentFile = dragItem.name;
					vm.desktopUploadMonitor.target.progressPercent = 0;

					const sourceParent = dragItem.path.substring(0, dragItem.path.lastIndexOf('/'));
					if (dragItem.isCollection && (destPath === dragItem.path || destPath.startsWith(dragItem.path + '/'))) {
						continue;
					}
					if (!isCopy && sourceParent === destPath) continue;

					if (isCopy && sourceParent === destPath) {
						const copyName = await vm._generateDesktopCopyName(dragItem.name, destPath, contentService);
						await contentService.copyNode(dragItem.path, destPath, copyName);
						continue;
					}

					const targetNodePath = destPath + '/' + dragItem.name;
					const existing = await contentService.getNode(targetNodePath).catch(() => null);
					if (existing) {
						let action = overwriteAll ? 'overwrite' : skipAll ? 'skip' : await vm.askDesktopConflict(dragItem.name);
						if (action === 'overwriteAll') { overwriteAll = true; action = 'overwrite'; }
						if (action === 'skipAll') { skipAll = true; action = 'skip'; }
						if (action === 'cancel') { vm.desktopUploadMonitor.cancel(); break; }
						if (action === 'skip') continue;
						await contentService.deleteNode(targetNodePath);
					}
					if (isCopy) {
						await contentService.copyNode(dragItem.path, destPath);
					} else {
						await contentService.moveNode(dragItem.path, destPath);
					}
					vm.desktopUploadMonitor.target.progressPercent = 100;
				}
			} catch (error: any) {
				if (!vm.desktopUploadMonitor.isCanceled) {
					vm.desktopErrorMessage = error?.message || String(error) || 'Operation failed';
				}
			} finally {
				if (!vm.desktopErrorMessage) {
					vm.desktopUploadMonitor = null;
				}
				document.dispatchEvent(new CustomEvent('webtop-desktop-reload'));
			}
		},
		async _generateDesktopCopyName(originalName: string, destPath: string, contentService: any): Promise<string> {
			const dot = originalName.lastIndexOf('.');
			const base = dot > 0 ? originalName.substring(0, dot) : originalName;
			const ext = dot > 0 ? originalName.substring(dot) : '';
			let candidate = `${base} - Copy${ext}`;
			let existing = await contentService.getNode(`${destPath}/${candidate}`).catch(() => null);
			if (!existing) return candidate;
			for (let i = 2; i <= 100; i++) {
				candidate = `${base} - Copy (${i})${ext}`;
				existing = await contentService.getNode(`${destPath}/${candidate}`).catch(() => null);
				if (!existing) return candidate;
			}
			return `${base} - Copy (${Date.now()})${ext}`;
		},
		// ---------------------------------------------------------------------
		// Selection — click, ctrl/meta toggle, shift extend, rubber-band.
		// ---------------------------------------------------------------------
		onDesktopIconSelect(itemId: string, mods: { ctrlKey: boolean; shiftKey: boolean }) {
			const vm = this as any;
			const items = vm.desktopItems;
			const index = items.findIndex((i: any) => i.id === itemId);
			if (index < 0) return;

			if (mods.shiftKey && vm.desktopLastSelectedIndex >= 0) {
				const start = Math.min(vm.desktopLastSelectedIndex, index);
				const end = Math.max(vm.desktopLastSelectedIndex, index);
				const next: string[] = mods.ctrlKey ? [...vm.desktopSelectedIds] : [];
				for (let i = start; i <= end; i++) {
					const id = items[i].id;
					if (!next.includes(id)) next.push(id);
				}
				vm.desktopSelectedIds = next;
			} else if (mods.ctrlKey) {
				const idx = vm.desktopSelectedIds.indexOf(itemId);
				if (idx >= 0) vm.desktopSelectedIds.splice(idx, 1);
				else vm.desktopSelectedIds.push(itemId);
				vm.desktopLastSelectedIndex = index;
			} else {
				vm.desktopSelectedIds = [itemId];
				vm.desktopLastSelectedIndex = index;
			}
		},
		// Rubber-band selection. Mousedown on #desktop-area falls through any
		// app windows because they sit above only inside their own bounds; the
		// .desktop-icons overlay is pointer-events: none so clicking empty
		// space lands here. Mousedown on an icon is handled by the component
		// (onItemMouseDown) — its CustomEvent fires before this DOM bubble
		// reaches us, and we early-return when the target is inside an icon.
		onDesktopAreaMouseDown(event: MouseEvent) {
			const vm = this as any;
			if (event.button !== 0) return;
			const target = event.target as HTMLElement | null;
			if (target?.closest('.desktop-icon')) return;
			if (target?.closest('wt-window, .wt-window')) return;
			const area = document.getElementById('desktop-area');
			if (!area) return;
			const rect = area.getBoundingClientRect();
			vm.desktopDragSelection.active = true;
			vm.desktopDragSelection.startX = event.clientX - rect.left;
			vm.desktopDragSelection.startY = event.clientY - rect.top;
			vm.desktopDragSelection.currentX = vm.desktopDragSelection.startX;
			vm.desktopDragSelection.currentY = vm.desktopDragSelection.startY;
			vm.desktopDragSelection.additive = event.ctrlKey || event.metaKey;
			vm.desktopDragSelection.baseSelection = vm.desktopDragSelection.additive ? [...vm.desktopSelectedIds] : [];
			if (!vm.desktopDragSelection.additive) {
				vm.desktopSelectedIds = [];
				vm.desktopLastSelectedIndex = -1;
			}
			vm._desktopBoundMouseMove = vm.onDesktopAreaMouseMove.bind(vm);
			vm._desktopBoundMouseUp = vm.onDesktopAreaMouseUp.bind(vm);
			document.addEventListener('mousemove', vm._desktopBoundMouseMove);
			document.addEventListener('mouseup', vm._desktopBoundMouseUp);
		},
		onDesktopAreaMouseMove(event: MouseEvent) {
			const vm = this as any;
			if (!vm.desktopDragSelection.active) return;
			const area = document.getElementById('desktop-area');
			if (!area) return;
			const rect = area.getBoundingClientRect();
			vm.desktopDragSelection.currentX = event.clientX - rect.left;
			vm.desktopDragSelection.currentY = event.clientY - rect.top;
			const sel = vm.desktopSelectionRect;
			const next: string[] = [...vm.desktopDragSelection.baseSelection];
			const icons = area.querySelectorAll('.desktop-icon[data-item-id]') as NodeListOf<HTMLElement>;
			icons.forEach((el) => {
				const r = el.getBoundingClientRect();
				const top = r.top - rect.top;
				const left = r.left - rect.left;
				const right = left + r.width;
				const bottom = top + r.height;
				if (!(sel.right < left || sel.left > right || sel.bottom < top || sel.top > bottom)) {
					const id = el.getAttribute('data-item-id');
					if (id && !next.includes(id)) next.push(id);
				}
			});
			vm.desktopSelectedIds = next;
		},
		onDesktopAreaMouseUp(_event: MouseEvent) {
			const vm = this as any;
			vm.desktopDragSelection.active = false;
			if (vm._desktopBoundMouseMove) {
				document.removeEventListener('mousemove', vm._desktopBoundMouseMove);
				vm._desktopBoundMouseMove = null;
			}
			if (vm._desktopBoundMouseUp) {
				document.removeEventListener('mouseup', vm._desktopBoundMouseUp);
				vm._desktopBoundMouseUp = null;
			}
		},
		// ---------------------------------------------------------------------
		// Context menu (per-icon and empty area).
		// ---------------------------------------------------------------------
		onDesktopIconContextMenu(itemId: string, x: number, y: number) {
			const vm = this as any;
			if (!vm.desktopSelectedIds.includes(itemId)) {
				const idx = vm.desktopItems.findIndex((i: any) => i.id === itemId);
				vm.desktopSelectedIds = [itemId];
				vm.desktopLastSelectedIndex = idx;
			}
			const selected = vm.desktopItems.filter((i: any) => vm.desktopSelectedIds.includes(i.id));
			const isSingle = selected.length === 1;
			const hasFolder = selected.some((i: any) => i.isCollection);
			const hasFile = selected.some((i: any) => !i.isCollection);
			const items: any[] = [];
			if (isSingle && hasFolder) items.push({ id: 'open', label: 'Open', icon: 'bi-folder2-open' });
			if (isSingle && !hasFolder) items.push({ id: 'open', label: 'Open', icon: 'bi-box-arrow-up-right' });
			if (hasFile) items.push({ id: 'download', label: 'Download', icon: 'bi-download' });
			items.push({ type: 'separator', id: '', label: '' });
			if (isSingle) items.push({ id: 'rename', label: 'Rename', icon: 'bi-pencil' });
			items.push({ id: 'delete', label: 'Delete', icon: 'bi-trash', danger: true });
			vm.showContextMenu({
				x, y, items,
				onAction: (id: string) => vm.onDesktopContextAction(id, selected),
			});
		},
		onDesktopAreaContextMenu(event: MouseEvent) {
			const vm = this as any;
			const target = event.target as HTMLElement | null;
			if (target?.closest('.desktop-icon')) return;
			if (target?.closest('wt-window, .wt-window')) return;
			event.preventDefault();
			vm.desktopSelectedIds = [];
			vm.desktopLastSelectedIndex = -1;
			const items: any[] = [
				{ id: 'refresh', label: 'Refresh', icon: 'bi-arrow-clockwise' },
				{ type: 'separator', id: '', label: '' },
				{ id: 'open-content-browser', label: 'Open in Content Browser', icon: 'bi-folder2-open' },
			];
			vm.showContextMenu({
				x: event.clientX, y: event.clientY, items,
				onAction: (id: string) => vm.onDesktopContextAction(id, []),
			});
		},
		async onDesktopContextAction(action: string, items: any[]) {
			const vm = this as any;
			switch (action) {
				case 'refresh':
					document.dispatchEvent(new CustomEvent('webtop-desktop-reload'));
					return;
				case 'open-content-browser':
					vm.openAppWithOptions({ appId: '2468cf47-1a30-4053-b80a-9c5486954b08', options: { initialPath: vm.desktopFolderPath } });
					return;
				case 'open':
					if (items.length === 1) {
						const item = items[0];
						if (item.isCollection) {
							vm.openAppWithOptions({ appId: '2468cf47-1a30-4053-b80a-9c5486954b08', options: { initialPath: item.path } });
						} else {
							const editor = vm._findDesktopEditor(item.mimeType || '');
							if (editor) {
								await vm.openFileWithApp({ appId: editor.id, filePath: item.path, mimeType: item.mimeType });
							}
						}
					}
					return;
				case 'download':
					for (const item of items) {
						if (!item.isCollection && item.downloadURL) {
							vm._downloadDesktopFile(item.downloadURL, item.name);
						}
					}
					return;
				case 'rename':
					if (items.length === 1) vm.showDesktopRenameDialog(items[0]);
					return;
				case 'delete': {
					if (items.length === 0) return;
					vm.desktopDeleteDialog.items = items.slice();
					vm.desktopDeleteDialog.visible = true;
					return;
				}
			}
		},
		showDesktopRenameDialog(item: any) {
			const vm = this as any;
			vm.desktopRenameDialog.item = item;
			vm.desktopRenameDialog.newName = item.name;
			vm.desktopRenameDialog.errorMessage = '';
			vm.desktopRenameDialog.isLoading = false;
			vm.desktopRenameDialog.visible = true;
			vm.$nextTick(() => {
				const input = vm.$refs.desktopRenameInput as HTMLInputElement | undefined;
				if (!input) return;
				input.focus();
				// Select filename without extension for files; full name for folders.
				const lastDot = item.name.lastIndexOf('.');
				if (lastDot > 0 && !item.isCollection) {
					input.setSelectionRange(0, lastDot);
				} else {
					input.select();
				}
			});
		},
		closeDesktopRenameDialog() {
			const vm = this as any;
			vm.desktopRenameDialog.visible = false;
			vm.desktopRenameDialog.item = null;
			vm.desktopRenameDialog.newName = '';
			vm.desktopRenameDialog.errorMessage = '';
			vm.desktopRenameDialog.isLoading = false;
		},
		async submitDesktopRename() {
			const vm = this as any;
			const item = vm.desktopRenameDialog.item;
			if (!item) return;

			const newName = vm.desktopRenameDialog.newName.trim();
			if (!newName) {
				vm.desktopRenameDialog.errorMessage = 'Name cannot be empty';
				return;
			}
			if (newName === item.name) {
				vm.closeDesktopRenameDialog();
				return;
			}

			vm.desktopRenameDialog.isLoading = true;
			vm.desktopRenameDialog.errorMessage = '';
			try {
				await window.Webtop.api.content.renameNode(item.path, newName);
				vm.closeDesktopRenameDialog();
				document.dispatchEvent(new CustomEvent('webtop-desktop-reload'));
			} catch (err: any) {
				vm.desktopRenameDialog.errorMessage = err?.message || 'Failed to rename';
			} finally {
				vm.desktopRenameDialog.isLoading = false;
			}
		},
		onDesktopRenameKeydown(event: KeyboardEvent) {
			const vm = this as any;
			if (event.key === 'Enter') {
				event.preventDefault();
				vm.submitDesktopRename();
			} else if (event.key === 'Escape') {
				event.preventDefault();
				vm.closeDesktopRenameDialog();
			}
		},
		closeDesktopDeleteDialog() {
			const vm = this as any;
			vm.desktopDeleteDialog.visible = false;
			vm.desktopDeleteDialog.items = [];
		},
		async submitDesktopDelete() {
			const vm = this as any;
			const items: any[] = vm.desktopDeleteDialog.items.slice();
			vm.desktopDeleteDialog.visible = false;
			vm.desktopDeleteDialog.items = [];
			if (items.length === 0) return;

			const contentService = window.Webtop.api.content;
			const eventHub = window.Webtop.api.eventHub;

			try {
				const result = await deleteContentItems(
					contentService,
					eventHub,
					items.map((it: any) => ({
						path: it.path,
						isCollection: it.isCollection,
						hasChildren: it.hasChildren,
					})),
					{
						onStart: (handle: DeleteJobHandle) => {
							vm.desktopDeleteMonitor = {
								jobId: handle.jobId,
								handle,
								itemsTotal: items.length,
								itemsProcessed: 0,
								nodesDeleted: 0,
								currentPath: '',
								status: 'init' as JobStatus,
								errorMessage: '',
								isFinished: false,
								isAborting: false,
							};
						},
						onProgress: (progress: DeleteJobProgress) => {
							vm.handleDesktopDeleteProgress(progress);
						},
					},
				);

				if (result.sync) {
					vm.desktopSelectedIds = [];
					document.dispatchEvent(new CustomEvent('webtop-desktop-reload'));
				}
			} catch (err: any) {
				const msg = err?.message || String(err) || 'Failed to delete';
				if (vm.desktopDeleteMonitor) {
					vm.desktopDeleteMonitor.errorMessage = msg;
					vm.desktopDeleteMonitor.isFinished = true;
				} else {
					vm.desktopAlert = { visible: true, title: 'Delete failed', message: msg };
				}
			}
		},
		handleDesktopDeleteProgress(progress: DeleteJobProgress) {
			const vm = this as any;
			const m = vm.desktopDeleteMonitor;
			if (!m || m.jobId !== progress.jobId) return;
			m.status = progress.status;
			m.itemsTotal = progress.itemsTotal;
			m.itemsProcessed = progress.itemsProcessed;
			m.nodesDeleted = progress.nodesDeleted;
			if (progress.currentPath) m.currentPath = progress.currentPath;
			if (progress.errorMessage) m.errorMessage = progress.errorMessage;
			if (progress.status === 'aborting') m.isAborting = true;

			if (progress.status === 'completed' || progress.status === 'aborted' || progress.status === 'failed') {
				m.isFinished = true;
				m.isAborting = false;
				// Refresh desktop icons so deleted items disappear.
				document.dispatchEvent(new CustomEvent('webtop-desktop-reload'));
				// Auto-close on success/abort. Failures stay open so the user
				// can read errorMessage.
				if (progress.status !== 'failed') {
					const closingJobId = m.jobId;
					setTimeout(() => {
						if (vm.desktopDeleteMonitor && vm.desktopDeleteMonitor.jobId === closingJobId) {
							vm.closeDesktopDeleteMonitor();
						}
					}, 1500);
				}
			}
		},
		requestDesktopDeleteAbort() {
			const vm = this as any;
			const m = vm.desktopDeleteMonitor;
			if (!m || m.isFinished || m.isAborting) return;
			m.isAborting = true;
			m.handle.abort();
		},
		closeDesktopDeleteMonitor() {
			const vm = this as any;
			if (vm.desktopDeleteMonitor) {
				try { vm.desktopDeleteMonitor.handle.release(); } catch { /* noop */ }
			}
			vm.desktopDeleteMonitor = null;
			vm.desktopSelectedIds = [];
		},
		_findDesktopEditor(mimeType: string): any {
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
		_downloadDesktopFile(url: string, filename: string) {
			const a = document.createElement('a');
			a.href = url;
			a.download = filename;
			document.body.appendChild(a);
			a.click();
			document.body.removeChild(a);
		},
		showMissingDesktopAlert() {
			const vm = this as any;
			vm.desktopAlert = {
				visible: true,
				title: 'Desktop folder not available',
				message: 'Your Desktop folder is not set up. Please ask your administrator to provision it, then sign out and sign in again.',
			};
		},
		closeDesktopAlert() {
			const vm = this as any;
			vm.desktopAlert = { ...vm.desktopAlert, visible: false };
		},
	},
};

export class Webtop implements WebtopContext {
	#api: WebtopAPI;
	#util: WebtopUtil;
	#rootPath: string;
	#resourcePaths: Record<string, string>; // リソースID -> リソースパス（相対パス）
	#currentUser: User;
	#apps: Application[];
	#metadataDefinitions: MetadataDefinitionCache | null = null;
	#i18n: I18nService | null = null;

	constructor() {
		// URLからワークスペースを取得してrootPathを動的に設定
		const urlInfo = UrlUtils.getUrlInfo();
		this.#rootPath = urlInfo.webtopContentPath;
		this.#resourcePaths = {
			// アセットを廃止
			// 'asset:logo:light': 'assets/icons/logo_light.svg',
			// 'asset:logo:dark': 'assets/icons/logo_dark.svg',
		};
		this.#api = new WebtopAPI(this);
		this.#util = new WebtopUtil();
		this.#currentUser = null;

		console.log('[Webtop] Initialized with rootPath:', this.#rootPath);
	}

	get api(): WebtopAPI { return this.#api; }

	get util(): WebtopUtil { return this.#util; }

	get resourcePaths(): Record<string, string> { return this.#resourcePaths; }

	get rootPath(): string { return this.#rootPath; }

	get currentUser(): User { return this.#currentUser; }

	set currentUser(user: User) { this.#currentUser = user; }

	get apps(): Application[] { return this.#apps; }

	set apps(apps: Application[]) { this.#apps = apps; }

	get metadataDefinitions(): MetadataDefinitionCache | null { return this.#metadataDefinitions; }

	get i18n(): I18nService | null { return this.#i18n; }

	/**
	 * Initialize metadata definitions cache.
	 * Call after API and EventHub are ready.
	 */
	async initMetadataDefinitions(): Promise<void> {
		if (this.#metadataDefinitions) return;
		this.#metadataDefinitions = new MetadataDefinitionCache(
			this.#api.content,
			this.#api.eventHub,
		);
		await this.#metadataDefinitions.initialize();
		console.log('[Webtop] Metadata definitions cache initialized');
	}

	/**
	 * Initialize i18n bundles cache.
	 * Call after API and EventHub are ready.
	 */
	async initI18n(): Promise<void> {
		if (this.#i18n) return;
		this.#i18n = new I18nService(
			this.#api.content,
			this.#api.eventHub,
		);
		await this.#i18n.initialize();
		console.log('[Webtop] I18n bundles cache initialized');
	}

	get sortedApps(): Application[] {
		const apps = (window.Webtop?.apps || []).filter(app => {
			return app.enableStartMenu === undefined || app.enableStartMenu;
		});
		return apps.sort((a, b) => (a.title || '').localeCompare(b.title || ''));
	}

	getFullPath(relPath: string): string {
		relPath = relPath.startsWith('/') ? relPath : '/' + relPath;
		return `${this.#rootPath}${relPath}`;
	}

	async launch(): Promise<void> {
		// Load the wt-window template before mounting so that the custom element's
		// connectedCallback (triggered by v-for instantiation) can find it.
		await loadComponent('wt-window.html');
		await loadComponent('wt-desktop-icons.html');

		const app = VDOM.createApp(WtDesktop);
		app.mount('#webtop');
	}
};
