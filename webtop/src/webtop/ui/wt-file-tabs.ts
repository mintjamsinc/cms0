// <wt-file-tabs> — document tab strip with close buttons and an add button.
//
//     <wt-file-tabs :model-value="currentFileIndex" :items="fileTabItems"
//         :add-title="t('tabs.newFile')" :close-title="t('common.close')"
//         @select="selectTab($event.detail)"
//         @close="closeFile($event.detail)"
//         @add="newFile"></wt-file-tabs>
//
// Items are `{ key?, label, modified?, title? }`; selection is by INDEX
// (v-model) because every host keeps its open-files state index-based.
// Selecting a tab only reports the index — switching documents involves
// host-side work (editor swap, state save), so wire @select to the host's
// own handler. Replaces the four identical .file-tabs-bar implementations
// (memo / text-editor / bpmn-modeler / eip-modeler).

import { defineComponent } from '@mintjamsinc/ichigojs';

defineComponent('wt-file-tabs', {
	template: '#wt-file-tabs',
	props: {
		/** Index of the active tab (v-model). */
		modelValue: { type: Number, default: -1 },
		/** Tabs: [{ key?, label, modified?, title? }]. */
		items: { type: Array, default: () => [] },
		/** Tooltip for the add (+) button. */
		addTitle: { type: String, default: '' },
		/** Tooltip for each tab's close (×) button. */
		closeTitle: { type: String, default: '' },
	},
	emits: ['select', 'close', 'add'],
	methods: {
		select(this: any, index: number): void {
			if (index === this.modelValue) {
				return;
			}
			this.$emit('update:modelValue', index);
			this.$emit('select', index);
		},
	},
});
