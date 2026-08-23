// <wt-empty-state> — centered "nothing here" placeholder.
//
//     <wt-empty-state v-if="items.length === 0" icon="bi-inbox">
//         {{ t('list.empty') }}
//         <button slot="actions" class="wt wt-primary" @click="create">{{ t('list.create') }}</button>
//     </wt-empty-state>
//
// Replaces the seven per-app empty-state classes (content-empty,
// detail-panel-empty, list-empty, tree-empty, ...).

import { defineComponent } from '@mintjamsinc/ichigojs';

defineComponent('wt-empty-state', {
	template: '#wt-empty-state',
	props: {
		/** Bootstrap Icons class for the large icon (e.g. 'bi-inbox'). Empty = no icon. */
		icon: { type: String, default: '' },
	},
});
