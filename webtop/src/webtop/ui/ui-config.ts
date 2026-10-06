// Shared configuration for the wt-* UI framework.
//
// Lives in its own module (instead of index.ts) so components can read the
// configuration without importing index.ts, which imports every component —
// that would be a circular dependency.

/**
 * A select-menu popup request handed to the host environment. The adapter
 * decides how to present the menu (the webtop shell shows a popup that can
 * escape the app window's iframe).
 */
export interface UiSelectPopupRequest {
	/** The trigger element the menu should anchor to. */
	anchor: HTMLElement;
	/** The selectable options, in display order. */
	options: { value: any; label: string; disabled?: boolean }[];
	/** The currently selected value (for check-marking). */
	value: any;
	/** Called with the chosen option's value. */
	onSelect: (value: any) => void;
}

/** A row of a suggestion popup. */
export interface UiSuggestionPopupItem {
	label: string;
	/** Secondary text under the label. */
	description?: string;
	/** Bootstrap icon class (e.g. 'bi bi-person'). */
	icon?: string;
	/** The row the keyboard is on. */
	highlighted?: boolean;
	disabled?: boolean;
}

/**
 * A suggestion popup under a text input (wt-autocomplete). Unlike a select
 * menu it stays open while the user types, and its rows are replaced as the
 * results come in.
 */
export interface UiSuggestionPopupRequest {
	/** The input the popup should anchor to. */
	anchor: HTMLElement;
	items: UiSuggestionPopupItem[];
	/** Called with the index of the chosen row. */
	onPick: (index: number) => void;
	/** Called when the popup closes without a choice (outside click, Escape). */
	onDismiss: () => void;
}

export interface UiSuggestionPopupHandle {
	/** Replaces the rows of the open popup. */
	update(items: UiSuggestionPopupItem[]): void;
	/** Closes the popup; neither onPick nor onDismiss is called. */
	close(): void;
}

/**
 * Adapter for menus that must escape the app window. Webtop apps implement
 * this on top of `instance.popup`; environments without a shell (BPMN forms,
 * the gallery) leave it unset and components fall back to inline menus.
 */
export interface UiPopupAdapter {
	openSelect(request: UiSelectPopupRequest): void;
	/** Returns null when the popup cannot be shown. */
	openSuggestions(request: UiSuggestionPopupRequest): UiSuggestionPopupHandle | null;
}

export interface UiInitOptions {
	/**
	 * Base URL of the deployed ui/ directory (must end with '/'). Defaults to
	 * '../../ui/' resolved against the document, which matches apps at
	 * /webtop/apps/<name>/. The shell and standalone pages (gallery, BPMN
	 * forms) pass their own base.
	 */
	baseUrl?: string | URL;

	/**
	 * Adapter for menus that must escape the app window (shell popup API).
	 * Consumed by wt-select and wt-autocomplete; components fall back to
	 * inline menus when absent.
	 */
	popupAdapter?: UiPopupAdapter;
}

let uiOptions: UiInitOptions = {};

/**
 * Returns the options initUi() was configured with. Used by components that
 * need environment adapters (e.g. wt-select's popupAdapter).
 */
export function getUiOptions(): UiInitOptions {
	return uiOptions;
}

/**
 * Merges options into the shared configuration. Called by initUi().
 */
export function mergeUiOptions(options: UiInitOptions): void {
	uiOptions = { ...uiOptions, ...options };
}
