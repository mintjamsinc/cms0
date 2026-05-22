import { WebtopContext } from '../global.js';

export class Application {
	#data;

	constructor(data) {
		this.#data = data || {};
	}

	// For backward compatibility
	get identifier() { return this.id; }

	get id() { return this.#data.identifier; }
	get name() { return this.#data.name; }
	get title() { return this.#data.title; }
	get appHome() { return this.#data.appHome; }
	get relPath() { return this.#data.relPath; }
	get icon() { return this.#data.icon; }
	get modified() {
		return this.#data.modified ? new Date(this.#data.modified) : undefined;
	}
	get enableStartMenu() { return this.#data.enableStartMenu; }
	get editor() { return this.#data.editor; }
	get contentTypes(): string[] { return this.#data.contentTypes || []; }
	get actions() { return this.#data.actions; }
	get minimumWidth() { return this.#data.minimumWidth; }
	get minimumHeight() { return this.#data.minimumHeight; }
	// When true, the shell hides the default window control buttons
	// (maximize/minimize/close) and the app renders its own.
	get customWindowControls(): boolean { return !!this.#data.customWindowControls; }
	// When true, only one instance of this app may run at a time. Re-launching
	// from the menu restores and focuses the existing window instead of opening
	// a new one.
	get singleton(): boolean { return !!this.#data.singleton; }
}

// =============================================================================
// Popup service — lets an iframe-hosted app render a dropdown / menu / picker
// in the shell's DOM (above all iframes), bypassing iframe overflow boundaries.
// Used for SELECT-replacement dropdowns, autocomplete suggestions, action menus,
// etc. Items are described declaratively; the shell handles rendering and
// posts the selected id back via postMessage.
// =============================================================================

// Trailing icon button on a popup item. Clicking does NOT dismiss the popup;
// instead the caller's onAction callback fires and may refresh items via
// handle.update(...). Use for pin/unpin/delete affordances on a row.
export interface PopupItemAction {
	id: string;                    // passed to onAction callback
	icon?: string;                 // bootstrap icon class (e.g. 'bi bi-trash')
	iconSvg?: string;              // SVG sprite reference '/path/icons.svg#id' (alt to icon)
	title?: string;                // tooltip
	danger?: boolean;
	showOnHover?: boolean;         // hidden until the row is hovered/focused
}

export interface PopupItem {
	id?: string | number;          // returned on selection; falls back to index
	label: string;
	description?: string;          // optional secondary text (two-line item)
	icon?: string;                 // bootstrap icon class (e.g. 'bi bi-folder')
	iconSvg?: string;              // SVG sprite reference '/path/icons.svg#id' (alt to icon)
	selected?: boolean;            // current chosen value (bold + check icon)
	highlighted?: boolean;         // keyboard focus / hover indicator (no check)
	danger?: boolean;
	disabled?: boolean;
	actions?: PopupItemAction[];   // trailing per-row buttons (non-dismissing)
}

// Action button rendered alongside the section header label. Clicking does
// NOT dismiss the popup; fires onGroupAction. Use for "Clear All" etc.
export interface PopupGroupAction {
	id: string;
	icon?: string;
	iconSvg?: string;              // SVG sprite reference (alt to icon)
	label?: string;
	title?: string;
	danger?: boolean;
}

export interface PopupGroup {
	label: string;                 // section header
	icon?: string;                 // optional icon next to label
	iconSvg?: string;              // SVG sprite reference (alt to icon)
	info?: string;                 // optional right-aligned text (e.g. "3 Pinned")
	emptyMessage?: string;         // shown when items.length === 0
	headerAction?: PopupGroupAction;
	items: PopupItem[];
}

export type PopupContent = PopupItem[] | PopupGroup[];

export interface PopupAnchor {
	left: number;                  // iframe-local viewport coords
	top: number;
	right?: number;
	bottom?: number;
	width?: number;
	height?: number;
}

export interface PopupOptions {
	anchor: PopupAnchor | DOMRect;
	placement?: 'bottom-start' | 'bottom-end' | 'top-start' | 'top-end';
	items: PopupContent;
	minWidth?: number;
	maxHeight?: number;
	// Inline action callbacks. Fire while the popup stays open; the caller
	// is expected to mutate state and call handle.update(...) to redraw.
	onAction?: (itemId: string | number, actionId: string) => void;
	onGroupAction?: (groupLabel: string, actionId: string) => void;
}

export interface PopupHandle {
	update(items: PopupContent): void;     // for live filtering / autocomplete
	close(): void;
	result: Promise<string | number | null>;  // null when dismissed
}

interface PopupCallbacks {
	resolve: (id: string | number | null) => void;
	onAction?: (itemId: string | number, actionId: string) => void;
	onGroupAction?: (groupLabel: string, actionId: string) => void;
}

class PopupService {
	#instanceId: string;
	#pending = new Map<string, PopupCallbacks>();

	constructor(instanceId: string) {
		this.#instanceId = instanceId;
		// PopupService methods run in the shell realm (the class is bundled
		// with the shell). Communicate with the shell's WtDesktop component
		// via CustomEvent on document — same pattern as window-control /
		// app-instance-closed. Avoids postMessage realm pitfalls.
		document.addEventListener('webtop-popup-result', (e: Event) => {
			const detail = (e as CustomEvent).detail || {};
			const cbs = this.#pending.get(detail.popupId);
			if (!cbs) return;
			this.#pending.delete(detail.popupId);
			cbs.resolve(detail.itemId ?? null);
		});
		document.addEventListener('webtop-popup-action', (e: Event) => {
			const detail = (e as CustomEvent).detail || {};
			const cbs = this.#pending.get(detail.popupId);
			if (!cbs) return;
			if (detail.kind === 'item' && cbs.onAction) {
				cbs.onAction(detail.itemId, detail.actionId);
			} else if (detail.kind === 'group' && cbs.onGroupAction) {
				cbs.onGroupAction(detail.groupLabel, detail.actionId);
			}
		});
	}

	open(options: PopupOptions): PopupHandle {
		const popupId = (typeof crypto !== 'undefined' && crypto.randomUUID)
			? crypto.randomUUID()
			: 'popup-' + Math.random().toString(36).slice(2);

		const anchor = this.#normalizeAnchor(options.anchor);
		const result = new Promise<string | number | null>((resolve) => {
			this.#pending.set(popupId, {
				resolve,
				onAction: options.onAction,
				onGroupAction: options.onGroupAction,
			});
		});

		document.dispatchEvent(new CustomEvent('webtop-popup-show', {
			detail: {
				popupId,
				sourceAppId: this.#instanceId,
				anchor,
				placement: options.placement || 'bottom-start',
				items: options.items,
				minWidth: options.minWidth,
				maxHeight: options.maxHeight,
			},
		}));

		return {
			update: (items: PopupContent) => {
				document.dispatchEvent(new CustomEvent('webtop-popup-update', {
					detail: { popupId, items },
				}));
			},
			close: () => {
				if (this.#pending.has(popupId)) {
					document.dispatchEvent(new CustomEvent('webtop-popup-close', {
						detail: { popupId },
					}));
				}
			},
			result,
		};
	}

	#normalizeAnchor(a: PopupAnchor | DOMRect): PopupAnchor {
		return {
			left: a.left,
			top: a.top,
			right: 'right' in a ? a.right : undefined,
			bottom: 'bottom' in a ? a.bottom : undefined,
			width: 'width' in a ? a.width : undefined,
			height: 'height' in a ? a.height : undefined,
		};
	}
}

export class ApplicationInstance {
	#app;
	#id: string;
	#windowTitle: string;
	#context: WebtopContext;
	#beforeCloseCallback: (() => Promise<boolean> | boolean) | null = null;
	#popup: PopupService;
	appState: (() => Record<string, unknown>) | undefined = undefined;

	constructor(app: Application, context: WebtopContext) {
		this.#app = app;
		this.#context = context;
		this.#id = crypto.randomUUID();
		this.#windowTitle = this.#app?.title || '';
		this.#popup = new PopupService(this.#id);
	}

	get id() { return this.#id; }
	get app() { return this.#app; }
	get currentUser() { return this.#context.currentUser; }
	get api() { return this.#context.api; }
	get util() { return this.#context.util; }
	get popup() { return this.#popup; }

	get windowTitle() { return this.#windowTitle; }
	set windowTitle(windowTitle: string) {
		if (windowTitle == null) {
			this.#windowTitle = this.#app?.title || '';
		} else {
			this.#windowTitle = windowTitle;
		}
	}

	getFullPath(relPath: string): string {
		relPath = relPath.startsWith('/') ? relPath : '/' + relPath;
		return `${this.#app.appHome}${relPath}`;
	}

	notifyLaunched(): void {
		document.dispatchEvent(new CustomEvent('app-launched', { detail: { id: this.#id } }));
	}

	// Push live UI hints to the shell (e.g. Dock hover preview). Apps should
	// call this whenever the relevant state changes (current folder, editing
	// file, etc.). The shell stores the latest value in its own reactive
	// state so updates render correctly without crossing iframe realm
	// boundaries during render.
	setDisplayInfo(info: { subtitle?: string } | null | undefined): void {
		const subtitle = (info && typeof info.subtitle === 'string') ? info.subtitle : '';
		document.dispatchEvent(new CustomEvent('window-display-info-changed', {
			detail: { id: this.#id, info: { subtitle } },
		}));
	}

	close(): void {
		document.dispatchEvent(new CustomEvent('app-instance-closed', { detail: { id: this.#id } }));
	}

	/**
	 * Request the shell to close this window. The beforeClose callback will be
	 * consulted; close is cancelled if it returns false.
	 */
	requestClose(): void {
		document.dispatchEvent(new CustomEvent('window-control', {
			detail: { id: this.#id, action: 'close' },
		}));
	}

	maximize(): void {
		document.dispatchEvent(new CustomEvent('window-control', {
			detail: { id: this.#id, action: 'maximize' },
		}));
	}

	minimize(): void {
		document.dispatchEvent(new CustomEvent('window-control', {
			detail: { id: this.#id, action: 'minimize' },
		}));
	}

	restore(): void {
		document.dispatchEvent(new CustomEvent('window-control', {
			detail: { id: this.#id, action: 'restore' },
		}));
	}

	toggleMaximize(): void {
		document.dispatchEvent(new CustomEvent('window-control', {
			detail: { id: this.#id, action: 'toggle-maximize' },
		}));
	}

	/**
	 * Set a callback to be called before the window is closed.
	 * If the callback returns false or a Promise that resolves to false, the close will be cancelled.
	 * @param callback The callback function to call before closing
	 */
	setBeforeCloseCallback(callback: (() => Promise<boolean> | boolean) | null): void {
		this.#beforeCloseCallback = callback;
	}

	/**
	 * Check if the window can be closed by calling the beforeClose callback.
	 * @returns true if the window can be closed, false otherwise
	 */
	async canClose(): Promise<boolean> {
		if (this.#beforeCloseCallback) {
			try {
				return await this.#beforeCloseCallback();
			} catch (error) {
				console.error('Error in beforeClose callback:', error);
				return true; // Allow close on error
			}
		}
		return true;
	}
}
