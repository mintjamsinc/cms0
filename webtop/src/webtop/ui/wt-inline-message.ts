// <wt-inline-message> — inline info / success / warning / error note.
//
//     <wt-inline-message v-if="saved" type="success">{{ t('pref.saved') }}</wt-inline-message>
//     <wt-inline-message v-if="error" type="error">{{ error }}</wt-inline-message>
//
// Replaces the ~14 per-app inline notice classes (pref-inline-*,
// invalid-feedback, validation-warning, editor-hint, ...).

import { defineComponent } from '@mintjamsinc/ichigojs';

const TYPES = ['info', 'success', 'warning', 'error'];

const TYPE_ICONS: Record<string, string> = {
	info: 'bi-info-circle',
	success: 'bi-check-circle',
	warning: 'bi-exclamation-triangle',
	error: 'bi-x-circle',
};

defineComponent('wt-inline-message', {
	template: '#wt-inline-message',
	props: {
		/** Message kind: info / success / warning / error. */
		type: { type: String, default: 'info', validator: (v: any) => TYPES.includes(v) },
	},
	computed: {
		iconClass(this: any): string {
			return TYPE_ICONS[this.type] ?? TYPE_ICONS.info;
		},
	},
});
