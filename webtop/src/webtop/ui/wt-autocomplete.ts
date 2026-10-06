// <wt-autocomplete> — a text input whose matches open in a dropdown as the
// user types; choosing one emits 'pick'.
//
//     <wt-autocomplete :search="findUsers" :placeholder="t('search.byName')"
//         :empty-text="t('search.nobody')" @pick="choose($event.detail)"></wt-autocomplete>
//
//     <!-- Several picks in a row: the input empties after each one -->
//     <wt-autocomplete :search="findUsers" clear-on-pick @pick="stage($event.detail)"></wt-autocomplete>
//
// `search(keyword)` resolves to the matches, `{ value, label, description?,
// icon?, disabled? }`; it is called a moment after the user stops typing,
// and a reply older than the latest keyword is dropped. Without clear-on-pick
// the input keeps the label of the choice.
//
// Events: 'pick' (detail = the chosen match), 'change' (detail = the text,
// fired as the user types — a host that keeps a choice drops it here) and
// 'escape' (Escape with no dropdown open).
//
// The dropdown opens through the configured popupAdapter, like wt-select's
// menu, so it can escape the app window instead of stretching a dialog;
// otherwise an inline .wt-select-menu is rendered. Up/Down move through the
// matches and Enter takes the one marked, or the first.

import { defineComponent } from '@mintjamsinc/ichigojs';
import { getUiOptions, UiSuggestionPopupHandle, UiSuggestionPopupItem } from './ui-config.js';

export interface WtAutocompleteItem {
	value: any;
	label: string;
	description?: string;
	icon?: string;
	disabled?: boolean;
}

const SEARCH_DELAY_MS = 250;
// A focus that leaves the input closes the inline menu after this long, so a
// click on one of its rows is not lost.
const BLUR_DELAY_MS = 200;

defineComponent('wt-autocomplete', {
	template: '#wt-autocomplete',
	props: {
		/** keyword => the matches. */
		search: { type: Function, default: null },
		placeholder: { type: String, default: '' },
		/** Shown in the dropdown when nothing matches. */
		emptyText: { type: String, default: 'No match.' },
		/** Empties the input after a pick, for picking several in a row. */
		clearOnPick: { type: Boolean, default: false },
		/** Focuses the input once it is rendered. */
		autofocus: { type: Boolean, default: false },
		disabled: { type: Boolean, default: false },
	},
	emits: ['pick', 'change', 'escape'],
	data(this: any) {
		return {
			text: '',
			items: [] as WtAutocompleteItem[],
			// The match the keyboard is on, or -1.
			highlight: -1,
			error: '',
			loading: false,
			inlineOpen: false,
			// Not reactive: the popup handle's closures must not be proxied.
			runtime: this.$markRaw({
				timer: null as ReturnType<typeof setTimeout> | null,
				seq: 0,
				handle: null as UiSuggestionPopupHandle | null,
			}),
		};
	},
	computed: {
		rows(this: any): UiSuggestionPopupItem[] {
			if (this.error) {
				return [{ label: this.error, icon: 'bi bi-exclamation-triangle', disabled: true }];
			}
			if (!this.items.length) {
				return [{ label: this.emptyText, disabled: true }];
			}
			return (this.items as WtAutocompleteItem[]).map((item, index) => ({
				label: String(item.label),
				description: item.description,
				icon: item.icon,
				disabled: !!item.disabled,
				highlighted: index === this.highlight,
			}));
		},
	},
	methods: {
		onMounted(this: any): void {
			if (this.autofocus) {
				// A dialog renders its content as it opens.
				setTimeout(() => this.$refs.input?.focus(), 0);
			}
		},
		onUnmount(this: any): void {
			this.cancelSearch();
			this.closeDropdown();
		},
		onInput(this: any, value: string): void {
			this.text = value;
			this.highlight = -1;
			this.$emit('change', value);
			this.cancelSearch();
			if (!value.trim()) {
				this.runtime.seq++;
				this.reset();
				return;
			}
			this.runtime.timer = setTimeout(() => {
				this.runtime.timer = null;
				this.runSearch();
			}, SEARCH_DELAY_MS);
		},
		cancelSearch(this: any): void {
			if (this.runtime.timer) {
				clearTimeout(this.runtime.timer);
				this.runtime.timer = null;
			}
		},
		async runSearch(this: any): Promise<void> {
			const keyword = this.text.trim();
			const seq = ++this.runtime.seq;
			if (!keyword || typeof this.search !== 'function') {
				this.reset();
				return;
			}
			this.loading = true;
			try {
				const found = await this.search(keyword);
				if (seq !== this.runtime.seq) return;
				this.items = Array.isArray(found) ? found : [];
				this.error = '';
			} catch (e) {
				if (seq !== this.runtime.seq) return;
				this.items = [];
				this.error = (e instanceof Error) ? e.message : String(e);
			} finally {
				if (seq === this.runtime.seq) this.loading = false;
			}
			this.highlight = -1;
			this.showDropdown();
		},
		reset(this: any): void {
			this.items = [];
			this.error = '';
			this.loading = false;
			this.highlight = -1;
			this.closeDropdown();
		},
		isOpen(this: any): boolean {
			return !!this.runtime.handle || this.inlineOpen;
		},
		showDropdown(this: any): void {
			const input = this.$refs.input as HTMLInputElement | undefined;
			// The user has moved on while the search ran.
			if (!input || input.ownerDocument.activeElement !== input) {
				return;
			}
			if (this.runtime.handle) {
				this.runtime.handle.update(this.rows);
				return;
			}
			if (this.inlineOpen) {
				return;
			}
			const adapter = getUiOptions().popupAdapter;
			const handle = adapter?.openSuggestions({
				anchor: input,
				items: this.rows,
				onPick: (index: number) => {
					this.runtime.handle = null;
					this.pick(index);
				},
				onDismiss: () => {
					this.runtime.handle = null;
				},
			});
			if (handle) {
				this.runtime.handle = handle;
			} else {
				this.inlineOpen = true;
			}
		},
		closeDropdown(this: any): void {
			if (this.runtime.handle) {
				const handle = this.runtime.handle;
				this.runtime.handle = null;
				handle.close();
			}
			this.inlineOpen = false;
		},
		moveHighlight(this: any, offset: number): void {
			const count = this.items.length;
			if (!count) return;
			this.highlight = Math.max(-1, Math.min(count - 1, this.highlight + offset));
			if (this.isOpen()) {
				this.runtime.handle?.update(this.rows);
			} else {
				this.showDropdown();
			}
		},
		pick(this: any, index: number): void {
			const item = (this.items as WtAutocompleteItem[])[index];
			if (!item || item.disabled) return;
			this.closeDropdown();
			this.cancelSearch();
			this.runtime.seq++;
			this.text = this.clearOnPick ? '' : String(item.label);
			this.items = [];
			this.highlight = -1;
			// A click on the shell popup took the focus out of the window.
			this.$refs.input?.focus();
			this.$emit('pick', item);
		},
		onKeydown(this: any, event: KeyboardEvent): void {
			if (event.key === 'ArrowDown') {
				event.preventDefault();
				this.moveHighlight(1);
			} else if (event.key === 'ArrowUp') {
				event.preventDefault();
				this.moveHighlight(-1);
			} else if (event.key === 'Enter') {
				event.preventDefault();
				if (!this.isOpen()) return;
				const index = this.highlight >= 0 ? this.highlight :
					(this.items as WtAutocompleteItem[]).findIndex((item) => !item.disabled);
				if (index >= 0) this.pick(index);
			} else if (event.key === 'Escape') {
				// The shell popup takes Escape itself; this is the inline menu,
				// or no menu at all.
				if (this.inlineOpen) {
					event.preventDefault();
					event.stopPropagation();
					this.inlineOpen = false;
					return;
				}
				this.$emit('escape');
			}
		},
		onFocusout(this: any): void {
			if (!this.inlineOpen) return;
			setTimeout(() => {
				const input = this.$refs.input as HTMLInputElement | undefined;
				if (input && input.ownerDocument.activeElement !== input) {
					this.inlineOpen = false;
				}
			}, BLUR_DELAY_MS);
		},
	},
});
