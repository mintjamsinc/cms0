// <wt-item-row> — one item of a list in a side pane: an icon, a label with a
// line of meta text under it, flag icons, and a "…" button that asks the
// owner to open the item's menu. An optional body (the default slot) shows
// under the label, e.g. the value of a property.
//
//     <wt-item-row v-for="prop in props" :key="prop.name"
//         icon="bi bi-fonts" :label="prop.label" :meta="prop.type + ' · ' + prop.name"
//         menu :menu-title="t('more')" @row-menu="openMenu(prop, $event.detail)">
//         <template v-slot:flags="f"><i v-if="prop.required" class="bi bi-asterisk"></i></template>
//         <template v-slot="b">{{ prop.value }}</template>
//     </wt-item-row>
//
// The menu itself is the owner's: `row-menu` carries { anchor }, the button's
// rectangle, to place a popup against. `row-click` fires for a click on the
// head when `clickable` is set.
//
// The same look is available to DOM built by hand (the memo app's dataset
// pane) through the .wt-item-row-* classes; keep the two in step.

import { defineComponent } from '@mintjamsinc/ichigojs';

const STATES = ['', 'modified', 'new', 'deleted', 'invalid'];

defineComponent('wt-item-row', {
	template: '#wt-item-row',
	props: {
		/** Icon classes, e.g. "bi bi-fonts". */
		icon: { type: String, default: '' },
		/** The item's name. */
		label: { type: String, default: '' },
		/** Small text under the label (type, key, ...). */
		meta: { type: String, default: '' },
		/** Shows the "…" button. */
		menu: { type: Boolean, default: false },
		/** Tooltip of the "…" button. */
		menuTitle: { type: String, default: '' },
		/** A click on the head emits `row-click`. */
		clickable: { type: Boolean, default: false },
		/** Pending state of the item: modified / new / deleted / invalid. */
		state: { type: String, default: '', validator: (v: any) => STATES.includes(v) },
	},
	emits: ['row-menu', 'row-click'],
	methods: {
		onMenu(this: any, event: MouseEvent): void {
			const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
			this.$emit('row-menu', {
				anchor: { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom, width: rect.width, height: rect.height },
			});
		},
		onClick(this: any): void {
			if (this.clickable) {
				this.$emit('row-click');
			}
		},
	},
});
