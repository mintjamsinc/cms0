// <wt-live-indicator> — "Live / Offline" subscription-state dot + label.
//
//     <wt-live-indicator :connected="liveConnected"
//         :on-label="t('toolbar.live')" :off-label="t('toolbar.offline')"
//         :on-title="t('toolbar.liveOn')" :off-title="t('toolbar.liveOff')"></wt-live-indicator>
//
// While connected the dot pulses in the primary color. Replaces the
// per-app eip-live / dash-live implementations.

import { defineComponent } from '@mintjamsinc/ichigojs';

defineComponent('wt-live-indicator', {
	template: '#wt-live-indicator',
	props: {
		/** Whether the live stream is currently connected. */
		connected: { type: Boolean, default: false },
		/** Label while connected. */
		onLabel: { type: String, default: 'Live' },
		/** Label while disconnected. */
		offLabel: { type: String, default: 'Offline' },
		/** Tooltip while connected. */
		onTitle: { type: String, default: '' },
		/** Tooltip while disconnected. */
		offTitle: { type: String, default: '' },
	},
});
