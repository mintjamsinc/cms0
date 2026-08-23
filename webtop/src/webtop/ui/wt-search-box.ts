// <wt-search-box> — filter/search input with a built-in clear button.
//
//     <wt-search-box v-model="filterText" :placeholder="t('list.filter')"></wt-search-box>
//     <wt-search-box v-model="query" @search="runSearch"></wt-search-box>
//
// Events: 'search' fires on Enter (detail = current text), 'clear' fires
// when the clear button is pressed (after the model is emptied). Built on
// the shared .input-group classes; replaces the three per-app search-input
// implementations.

import { defineComponent } from '@mintjamsinc/ichigojs';

defineComponent('wt-search-box', {
	template: '#wt-search-box',
	props: {
		/** Current text (v-model). */
		modelValue: { type: String, default: '' },
		placeholder: { type: String, default: '' },
		disabled: { type: Boolean, default: false },
	},
	emits: ['search', 'clear', 'change'],
	methods: {
		onInput(this: any, value: string): void {
			this.$emit('update:modelValue', value);
			this.$emit('change', value);
		},
		onClear(this: any): void {
			this.$emit('update:modelValue', '');
			this.$emit('change', '');
			this.$emit('clear');
		},
	},
});
