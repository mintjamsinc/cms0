// <wt-tabs> — tab bar with v-model (selected key).
//
//     <wt-tabs v-model="view" :items="[{key: 'graph', label: 'Graph'}, {key: 'list', label: 'List'}]"></wt-tabs>
//     <wt-tabs v-model="pane" :items="panes" variant="underline">
//         <template v-slot:tab="t">{{ t.tab.label }} <wt-badge v-if="t.tab.badge">{{ t.tab.badge }}</wt-badge></template>
//     </wt-tabs>
//
// Items are `{ key, label, icon?, badge?, disabled? }`. Variants: pill
// (segmented, default) and underline. Replaces the per-app detail-tab /
// property-tab implementations and the unused shared .view-tabs.

import { defineComponent } from '@mintjamsinc/ichigojs';

const VARIANTS = ['pill', 'underline'];

defineComponent('wt-tabs', {
	template: '#wt-tabs',
	props: {
		/** Selected tab key (v-model). */
		modelValue: { default: undefined },
		/** Tabs: [{ key, label, icon?, badge?, disabled? }]. */
		items: { type: Array, default: () => [] },
		/** Visual style: pill (segmented) / underline. */
		variant: { type: String, default: 'pill', validator: (v: any) => VARIANTS.includes(v) },
	},
	emits: ['change'],
	methods: {
		select(this: any, tab: any): void {
			if (tab.disabled || tab.key === this.modelValue) {
				return;
			}
			this.$emit('update:modelValue', tab.key);
			this.$emit('change', tab.key);
		},
	},
});
