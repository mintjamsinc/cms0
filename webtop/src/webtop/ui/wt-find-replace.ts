// <wt-find-replace> — the sidebar Find & Replace pane (term + options +
// replace + status), shared by the memo and text-editor apps.
//
//     <wt-find-replace v-model="searchTerm" v-model:replace="replaceTerm"
//         v-model:case-sensitive="searchCaseSensitive"
//         v-model:whole-word="searchWholeWord"
//         v-model:regex="searchRegex"
//         :focus-seq="searchFocusSeq"
//         :labels="findReplaceLabels"
//         @change="applySearchQuery"
//         @find-next="findNextMatch" @find-prev="findPrev"
//         @replace="replaceCurrent" @replace-all="replaceAllInDoc"
//         @escape="focusEditor">
//         <template v-slot:status> ...host-specific match counters... </template>
//     </wt-find-replace>
//
// The component owns only the pane's UI; querying and replacing stay host
// work (each editor drives its own search plugin), wired through the events.
// `change` fires after ANY query-relevant edit (term input, option toggle,
// clear) — the models are already updated when it arrives. Keyboard: Enter /
// Shift+Enter on the find input step next/prev; Enter / Ctrl+Enter on the
// replace input replace one/all; Escape emits 'escape' (hosts refocus the
// editor). Bump `focus-seq` to focus & select the find input (pane open,
// Ctrl+F).
//
// `labels` keys (all optional, English fallbacks):
//   find, replace, findPlaceholder, replacePlaceholder, replaceAll, clear,
//   matchCase, wholeWord, regex, previous, next

import { defineComponent } from '@mintjamsinc/ichigojs';

defineComponent('wt-find-replace', {
	template: '#wt-find-replace',
	props: {
		/** Search term (v-model). */
		modelValue: { type: String, default: '' },
		/** Replacement text (v-model:replace). */
		replace: { type: String, default: '' },
		/** Match-case option (v-model:case-sensitive). */
		caseSensitive: { type: Boolean, default: false },
		/** Whole-word option (v-model:whole-word). */
		wholeWord: { type: Boolean, default: false },
		/** Regex option (v-model:regex). */
		regex: { type: Boolean, default: false },
		/** Increment to focus & select the find input. */
		focusSeq: { type: Number, default: 0 },
		/** I18n labels (see the key list above). */
		labels: { type: Object, default: () => ({}) },
	},
	emits: ['change', 'find-next', 'find-prev', 'replace', 'replace-all', 'escape'],
	watch: {
		focusSeq(this: any) {
			const input = this.$refs.findInput as HTMLInputElement | undefined;
			if (input) {
				input.focus();
				input.select();
			}
		},
	},
	methods: {
		text(this: any, key: string, fallback: string): string {
			return (this.labels && this.labels[key]) || fallback;
		},
		onTermInput(this: any, value: string): void {
			this.$emit('update:modelValue', value);
			this.$emit('change');
		},
		clearTerm(this: any): void {
			this.$emit('update:modelValue', '');
			this.$emit('change');
		},
		onReplaceInput(this: any, value: string): void {
			this.$emit('update:replace', value);
		},
		toggle(this: any, prop: 'caseSensitive' | 'wholeWord' | 'regex'): void {
			this.$emit('update:' + prop, !this[prop]);
			this.$emit('change');
		},
		onTermKeydown(this: any, event: KeyboardEvent): void {
			if (event.key === 'Enter') {
				event.preventDefault();
				this.$emit(event.shiftKey ? 'find-prev' : 'find-next');
			} else if (event.key === 'Escape') {
				event.preventDefault();
				this.$emit('escape');
			}
		},
		onReplaceKeydown(this: any, event: KeyboardEvent): void {
			if (event.key === 'Enter') {
				event.preventDefault();
				this.$emit(event.ctrlKey || event.metaKey ? 'replace-all' : 'replace');
			} else if (event.key === 'Escape') {
				event.preventDefault();
				this.$emit('escape');
			}
		},
	},
});
