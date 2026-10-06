// Popup adapter backed by the webtop shell popup API (instance.popup), so
// wt-select menus and wt-autocomplete matches escape the app window's iframe. Every app wires it the
// same way, as soon as the shell hands over the application instance:
//
//     import { initUi } from '../../ui/index.js';
//     import { createShellPopupAdapter } from '../../ui/shell-popup-adapter.js';
//
//     window.appLaunch = async (instance) => {
//         initUi({ popupAdapter: createShellPopupAdapter(instance) });
//         ...
//     };
//
// Environments without a shell (BPMN forms, the gallery) skip this and
// wt-select falls back to its inline menu.

import {
	UiPopupAdapter,
	UiSelectPopupRequest,
	UiSuggestionPopupHandle,
	UiSuggestionPopupItem,
	UiSuggestionPopupRequest,
} from './ui-config.js';

function suggestionRows(items: UiSuggestionPopupItem[]): any[] {
	return items.map((item, index) => ({
		id: String(index),
		label: item.label,
		description: item.description,
		icon: item.icon,
		highlighted: !!item.highlighted,
		disabled: !!item.disabled,
	}));
}

export function createShellPopupAdapter(instance: any): UiPopupAdapter {
	return {
		openSelect(request: UiSelectPopupRequest): void {
			if (!instance?.popup) {
				return;
			}
			const rect = request.anchor.getBoundingClientRect();
			const handle = instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				maxHeight: 360,
				// Option values may be any type (including ''), so items are
				// addressed by index and mapped back on selection.
				items: request.options.map((option, index) => ({
					id: String(index),
					label: option.label,
					selected: option.value === request.value,
					disabled: !!option.disabled,
				})),
			});
			Promise.resolve(handle.result).then((id: any) => {
				if (id == null) {
					return;
				}
				const option = request.options[Number(id)];
				if (option && !option.disabled) {
					request.onSelect(option.value);
				}
			}).catch(() => {
				// Popup dismissed.
			});
		},
		openSuggestions(request: UiSuggestionPopupRequest): UiSuggestionPopupHandle | null {
			if (!instance?.popup) {
				return null;
			}
			const rect = request.anchor.getBoundingClientRect();
			const handle = instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				maxHeight: 360,
				items: suggestionRows(request.items),
			});
			// close() resolves the result with null too; that is not a dismissal.
			let closed = false;
			Promise.resolve(handle.result).then((id: any) => {
				if (closed) {
					return;
				}
				closed = true;
				if (id == null) {
					request.onDismiss();
				} else {
					request.onPick(Number(id));
				}
			}).catch(() => {
				// Popup dismissed.
			});
			return {
				update(items: UiSuggestionPopupItem[]): void {
					if (!closed) {
						handle.update(suggestionRows(items));
					}
				},
				close(): void {
					if (!closed) {
						closed = true;
						handle.close();
					}
				},
			};
		},
	};
}
