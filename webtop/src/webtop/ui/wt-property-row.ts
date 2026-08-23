// <wt-property-row> — one "name + badges + value" property line, in
// read-only or editable form. The shared look for wt-inspector's property
// display, bpm-console's detail panel / process variables, and the
// property-label/property-row variants in the other apps.
//
//     <!-- read-only -->
//     <wt-property-row name="Instance ID" :model-value="inst.id" readonly></wt-property-row>
//     <wt-property-row :name="prop.label" :badges="[{label: prop.type}]" readonly>
//         <template v-slot:value="p">{{ formatValue(p.value) }}</template>
//     </wt-property-row>
//
//     <!-- editable: default editor is input.wt; the editor slot replaces it.
//          Slot content is compiled in the app scope, so the app can wire its
//          own bindings directly. -->
//     <wt-property-row :name="t('user.name')" v-model="user.name"></wt-property-row>
//     <wt-property-row :name="t('user.locale')" readonly="false">
//         <template v-slot:editor="p"><wt-select v-model="user.locale" :items="locales"></wt-select></template>
//     </wt-property-row>

import { defineComponent } from '@mintjamsinc/ichigojs';

defineComponent('wt-property-row', {
	template: '#wt-property-row',
	props: {
		/** Property (display) name. */
		name: { type: String, default: '' },
		/** Value (v-model in editable mode; display source in read-only mode). */
		modelValue: { default: undefined },
		/** Badges rendered after the name: [{ label, variant? }]. */
		badges: { type: Array, default: () => [] },
		/** Read-only display (true) or editable (false). */
		readonly: { type: Boolean, default: true },
		/** Renders the value in the muted color (e.g. empty placeholders). */
		muted: { type: Boolean, default: false },
	},
	computed: {
		displayValue(this: any): string {
			return this.modelValue === undefined || this.modelValue === null ? '' : String(this.modelValue);
		},
	},
});
