// <wt-select> — dropdown select with v-model.
//
//     <wt-select v-model="locale" :items="localeOptions" :placeholder="t('pref.choose')"></wt-select>
//
//     <!-- Custom option rendering (inline menu only) -->
//     <wt-select v-model="routeId" :items="routeOptions">
//         <template v-slot:option="o">
//             <i class="bi bi-diagram-3"></i> {{ o.option.label }}
//         </template>
//     </wt-select>
//
// Items are `{ value, label, disabled? }`. The prop is named `items` — NOT
// `options` — because `:options` is a reserved binding in ichigo.js
// (directive options for v-intersection etc.) and is silently ignored.
//
// The menu opens through the configured popupAdapter (webtop shell popup —
// can escape the app window) when initUi() received one; otherwise an inline
// .wt-select-menu is rendered (BPMN forms, gallery). Replaces the ~90
// hand-written trigger blocks and the per-app openXxxDropdown helpers.

import { defineComponent } from '@mintjamsinc/ichigojs';
import { getUiOptions } from './ui-config.js';

defineComponent('wt-select', {
	template: '#wt-select',
	props: {
		/** Selected value (v-model). Matched against items[].value with strict equality. */
		modelValue: { default: undefined },
		/** Selectable items: [{ value, label, disabled? }]. */
		items: { type: Array, default: () => [] },
		/** Text shown while no item matches the current value. */
		placeholder: { type: String, default: '' },
		disabled: { type: Boolean, default: false },
	},
	emits: ['change'],
	data() {
		return { open: false };
	},
	computed: {
		selectedItem(this: any): any {
			return (this.items ?? []).find((o: any) => o && o.value === this.modelValue);
		},
		displayLabel(this: any): string {
			return this.selectedItem ? String(this.selectedItem.label) : this.placeholder;
		},
	},
	methods: {
		toggle(this: any, event: Event): void {
			if (this.disabled) {
				return;
			}

			const adapter = getUiOptions().popupAdapter;
			if (adapter) {
				adapter.openSelect({
					anchor: event.currentTarget as HTMLElement,
					options: (this.items ?? []).map((o: any) => ({ value: o.value, label: String(o.label), disabled: !!o.disabled })),
					value: this.modelValue,
					onSelect: (value: any) => {
						this.$emit('update:modelValue', value);
						this.$emit('change', value);
					},
				});
				return;
			}

			this.open = !this.open;
		},
		select(this: any, item: any): void {
			if (item.disabled) {
				return;
			}
			this.$emit('update:modelValue', item.value);
			this.$emit('change', item.value);
			this.open = false;
		},
		close(this: any): void {
			if (this.open) {
				this.open = false;
			}
		},
	},
});
