// <wt-section> — collapsible section with header, actions and body.
//
//     <wt-section :title="t('sidebar.favorites')" v-model:open="favOpen">
//         <button slot="actions" class="btn-icon" @click="addFavorite"><i class="bi bi-plus"></i></button>
//         <ul>...</ul>
//     </wt-section>
//
// Works uncontrolled too (no v-model:open binding): the open state is kept
// locally and 'update:open' still reports changes. Implements the section
// pattern the shared CSS left as /* TODO */ and the per-app
// sidebar-section-header copies.

import { defineComponent } from '@mintjamsinc/ichigojs';

defineComponent('wt-section', {
	template: '#wt-section',
	props: {
		/** Open state (v-model:open). */
		open: { type: Boolean, default: true },
		/** Header title; the title slot overrides it. */
		title: { type: String, default: '' },
		/** When false, the section is a static header + body (no toggle). */
		collapsible: { type: Boolean, default: true },
	},
	data() {
		return { isOpen: (this as any).open ?? true };
	},
	watch: {
		open(this: any, value: boolean): void {
			this.isOpen = value;
		},
	},
	methods: {
		toggle(this: any): void {
			if (!this.collapsible) {
				return;
			}
			this.isOpen = !this.isOpen;
			this.$emit('update:open', this.isOpen);
		},
	},
});
