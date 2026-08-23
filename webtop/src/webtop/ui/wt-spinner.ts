// <wt-spinner> — activity indicator.
//
//     <wt-spinner></wt-spinner>
//     <wt-spinner size="sm"></wt-spinner>
//     <wt-spinner v-if="loading" overlay></wt-spinner>   <!-- fills the nearest
//          positioned ancestor with a translucent shield -->
//
// Replaces the per-app spinners (.spinner ×3 definitions, .loader,
// .loading-overlay ×4, and the seven copies of the .spin keyframes).

import { defineComponent } from '@mintjamsinc/ichigojs';

const SIZES = ['sm', 'md', 'lg'];

defineComponent('wt-spinner', {
	template: '#wt-spinner',
	props: {
		/** Ring size: sm (1rem) / md (1.75rem) / lg (2.5rem). */
		size: { type: String, default: 'md', validator: (v: any) => SIZES.includes(v) },
		/** When true, renders as a translucent overlay covering the nearest positioned ancestor. */
		overlay: { type: Boolean, default: false },
	},
});
