// <wt-checkbox> — labeled checkbox with v-model.
//
//     <wt-checkbox v-model="enabled" :label="t('svc.enabled')"></wt-checkbox>
//     <wt-checkbox v-model="strict">Strict mode <wt-badge>beta</wt-badge></wt-checkbox>
//
// The label comes from the `label` prop or, for rich content, the default
// slot (which overrides the prop). Replaces the per-app checkbox-row
// wrappers (wt-checkbox-label, filter-check, route-checkbox-item, ...).

import { defineComponent } from '@mintjamsinc/ichigojs';

defineComponent('wt-checkbox', {
	template: '#wt-checkbox',
	props: {
		/** Checked state (v-model). */
		modelValue: { type: Boolean, default: false },
		/** Label text; the default slot overrides it. */
		label: { type: String, default: '' },
		disabled: { type: Boolean, default: false },
	},
	emits: ['change'],
	methods: {
		onChange(this: any, checked: boolean): void {
			this.$emit('update:modelValue', checked);
			this.$emit('change', checked);
		},
	},
});
