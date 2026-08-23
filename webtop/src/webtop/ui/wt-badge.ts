// <wt-badge> — semantic pill badge.
//
//     <wt-badge variant="warning">M</wt-badge>
//     <wt-badge v-for="tag of tags" :key="tag">{{ tag }}</wt-badge>
//
// Replaces the per-app badge/pill implementations (bpm-badge, dash-pill,
// service-badge, prop-type-badge, ...).

import { defineComponent } from '@mintjamsinc/ichigojs';

const VARIANTS = ['primary', 'secondary', 'neutral', 'info', 'success', 'warning', 'danger'];

defineComponent('wt-badge', {
	template: '#wt-badge',
	props: {
		/** Semantic color: primary / secondary / neutral / info / success / warning / danger. */
		variant: { type: String, default: 'neutral', validator: (v: any) => VARIANTS.includes(v) },
	},
});
