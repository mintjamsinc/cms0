// <wt-field> — form field wrapper: label + input + hint / error.
//
//     <wt-field :label="t('pref.language')" :hint="t('pref.languageHint')">
//         <wt-select v-model="locale" :options="localeOptions"></wt-select>
//     </wt-field>
//     <wt-field :label="t('user.name')" required :error="nameError">
//         <input class="wt w-100" v-model="name">
//     </wt-field>
//
// The input itself is slotted in — a plain input.wt, a wt-select, a textarea,
// anything — so the wrapper only unifies the label / spacing / message layout
// (the per-app property-label / property-row / form-row variants).
// A non-empty `error` adds .has-error (red border on the slotted control)
// and replaces the hint.

import { defineComponent } from '@mintjamsinc/ichigojs';

defineComponent('wt-field', {
	template: '#wt-field',
	props: {
		/** Field label text. Empty = no label row. */
		label: { type: String, default: '' },
		/** Help text shown under the control (hidden while an error is shown). */
		hint: { type: String, default: '' },
		/** Error text; non-empty switches the field into its error state. */
		error: { type: String, default: '' },
		/** Shows a required marker next to the label. */
		required: { type: Boolean, default: false },
	},
});
