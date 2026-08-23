// <wt-dialog> — modal dialog with v-model:open.
//
//     <wt-dialog v-model:open="showConfirm" size="sm" :title="t('dialog.unsaved')">
//         <p>{{ t('dialog.unsavedBody') }}</p>
//         <template v-slot:footer="d">
//             <button class="wt" @click="showConfirm = false">{{ t('cancel') }}</button>
//             <button class="wt wt-danger" @click="discard">{{ t('discard') }}</button>
//         </template>
//     </wt-dialog>
//
// Built on the shared .dialog-* classes; the size prop replaces the
// per-dialog style="max-width: NNrem" hard-coding. Escape closes by default
// (esc-close), clicking the overlay does not (overlay-close).
//
// Rules:
// - Pass title, body and footer as <template v-slot...> (the body via the
//   default `v-slot="d"`). The dialog re-renders its overlay per open, so
//   plain (non-template) slot content with bindings would go stale after
//   the first open; scoped templates are re-instantiated every time.
// - Do not place a wt-dialog inside an .island: the island's backdrop-filter
//   creates a containing block that would trap the fixed-position overlay.

import { defineComponent } from '@mintjamsinc/ichigojs';

const SIZES = ['sm', 'md', 'lg', 'xl'];

defineComponent('wt-dialog', {
	template: '#wt-dialog',
	props: {
		/** Visibility (v-model:open). */
		open: { type: Boolean, default: false },
		/** Frame width: sm (28rem) / md (32rem) / lg (36rem) / xl (42rem). */
		size: { type: String, default: 'md', validator: (v: any) => SIZES.includes(v) },
		/** Title text; the title slot overrides it. */
		title: { type: String, default: '' },
		/** Shows the ✕ close button. */
		closable: { type: Boolean, default: true },
		/** Close on Escape. */
		escClose: { type: Boolean, default: true },
		/** Close when the overlay backdrop is clicked. */
		overlayClose: { type: Boolean, default: false },
	},
	emits: ['close'],
	methods: {
		close(this: any): void {
			this.$emit('update:open', false);
			this.$emit('close');
		},
		onOverlayClick(this: any, event: MouseEvent): void {
			if (this.overlayClose && event.target === event.currentTarget) {
				this.close();
			}
		},
		onEscape(this: any): void {
			if (this.escClose) {
				this.close();
			}
		},
	},
});
