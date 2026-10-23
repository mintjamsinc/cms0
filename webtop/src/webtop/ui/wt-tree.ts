// <wt-tree> — tree view with per-node scoped slot.
//
//     <wt-tree :items="schemaTree" v-model:selected="selectedId" @select="onSelect">
//         <template v-slot:item="n">
//             <i class="bi" :class="n.node.icon"></i>
//             <span class="wt-tree-label">{{ n.node.label }}</span>
//             <wt-badge v-if="n.node.changed" variant="warning">M</wt-badge>
//         </template>
//     </wt-tree>
//
// Node shape is the app's own; `id-key` / `label-key` / `children-key` /
// `disabled-key` adapt to it (defaults: id / label / children / disabled).
// A disabled node is shown muted and cannot be selected, but it still
// expands, so what lies below it stays reachable. Selection and expansion
// work uncontrolled (internal state) or controlled via v-model:selected /
// v-model:expanded (array of ids). Rendering flattens the visible nodes
// into rows — no recursive templates needed — with indent per depth.
// Implements the tree the shared CSS left as /* TODO */; replaces the three
// per-app tree implementations.

import { defineComponent } from '@mintjamsinc/ichigojs';

defineComponent('wt-tree', {
	template: '#wt-tree',
	props: {
		/** Root nodes. */
		items: { type: Array, default: () => [] },
		/** Selected node id (v-model:selected). */
		selected: { default: undefined },
		/** Expanded node ids (v-model:expanded). */
		expanded: { type: Array, default: () => [] },
		idKey: { type: String, default: 'id' },
		labelKey: { type: String, default: 'label' },
		childrenKey: { type: String, default: 'children' },
		/** Property that marks a node as not selectable. */
		disabledKey: { type: String, default: 'disabled' },
	},
	emits: ['select', 'toggle'],
	data() {
		const self = this as any;
		return {
			localSelected: self.selected,
			localExpanded: [...(self.expanded ?? [])],
		};
	},
	watch: {
		selected(this: any, value: any): void {
			this.localSelected = value;
		},
		expanded(this: any, value: any[]): void {
			this.localExpanded = [...(value ?? [])];
		},
	},
	methods: {
		/**
		 * The rows to draw: the visible nodes flattened, with depth and state.
		 *
		 * A method, not a computed: in ichigo 0.1.87 a computed that depends on
		 * data a watcher assigns (localExpanded, set when the parent changes
		 * `expanded`) is refreshed only on every second such change, so a tree
		 * whose parent expands it once stayed collapsed. A method call in the
		 * template is re-evaluated on every render and tracks its reads.
		 */
		visibleRows(this: any): any[] {
			const rows: any[] = [];
			const walk = (nodes: any[], depth: number) => {
				for (const node of nodes ?? []) {
					if (!node) {
						continue;
					}
					const id = node[this.idKey];
					const children = node[this.childrenKey];
					const hasChildren = Array.isArray(children) && children.length > 0;
					const expanded = hasChildren && this.localExpanded.includes(id);
					const disabled = !!node[this.disabledKey];
					rows.push({ id, node, depth, hasChildren, expanded, disabled, label: node[this.labelKey] });
					if (expanded) {
						walk(children, depth + 1);
					}
				}
			};
			walk(this.items, 0);
			return rows;
		},
		onSelect(this: any, row: any): void {
			if (row.disabled) {
				return;
			}
			this.localSelected = row.id;
			this.$emit('update:selected', row.id);
			this.$emit('select', row.node);
		},
		onToggle(this: any, row: any): void {
			if (!row.hasChildren) {
				return;
			}
			const index = this.localExpanded.indexOf(row.id);
			if (index === -1) {
				this.localExpanded.push(row.id);
			} else {
				this.localExpanded.splice(index, 1);
			}
			this.$emit('update:expanded', [...this.localExpanded]);
			this.$emit('toggle', { id: row.id, expanded: index === -1 });
		},
	},
});
