// <wt-folder-picker> — modal that picks a folder of a workspace.
//
//     <wt-folder-picker v-model:open="picker.visible" :service="instance.api.content"
//         :root-label="instance.api.workspace" :initial-path="lastFolder"
//         :title="t('app.x.saveTo')" :labels="folderPickerLabels"
//         @select="onFolderPicked($event)"></wt-folder-picker>
//
// The tree is loaded a level at a time from `service` (a ContentServiceGraphQL:
// listFolders, createFolder, getPermissions). A folder the caller may not
// create anything in is shown muted with a lock and cannot be picked, but it
// still expands, so a writable folder below it stays reachable. *New folder*
// creates one in the picked folder and picks it.
//
// `select` carries the picked folder's path. `labels` keys (all optional,
// English fallbacks): select, cancel, newFolder, newFolderName, create,
// loading, noAccess, invalidName.

import { defineComponent } from '@mintjamsinc/ichigojs';

interface PickerNode {
	/** The folder's path; the node id. */
	id: string;
	label: string;
	children: PickerNode[];
	/** True once the children have been read; a leaf is loaded from the start. */
	loaded: boolean;
	/** Not selectable: the caller may not create anything here. */
	disabled: boolean;
	/** The "loading" row shown under a folder whose children are not read yet. */
	placeholder?: boolean;
}

function joinPath(parent: string, name: string): string {
	return parent.endsWith('/') ? `${parent}${name}` : `${parent}/${name}`;
}

function parentOf(path: string): string {
	const index = path.lastIndexOf('/');
	return index <= 0 ? '/' : path.substring(0, index);
}

defineComponent('wt-folder-picker', {
	template: '#wt-folder-picker',
	props: {
		/** Visibility (v-model:open). */
		open: { type: Boolean, default: false },
		/** Content service of the workspace to pick from. */
		service: { type: Object, default: null },
		/** The folder the tree starts at. */
		root: { type: String, default: '/' },
		/** Label of the root row; the root path when empty. */
		rootLabel: { type: String, default: '' },
		/** Folder to expand to and pick at first, when it exists under the root. */
		initialPath: { type: String, default: '' },
		title: { type: String, default: '' },
		/** I18n labels (see the key list above). */
		labels: { type: Object, default: () => ({}) },
		/** Offers *New folder*. */
		allowCreate: { type: Boolean, default: true },
	},
	emits: ['update:open', 'select', 'close'],
	data() {
		return {
			items: [] as PickerNode[],
			selected: undefined as string | undefined,
			expanded: [] as string[],
			loading: false,
			error: '',
			creating: false,
			newName: '',
			createBusy: false,
		};
	},
	computed: {
		selectedNode(this: any): PickerNode | null {
			return this.selected ? this.findNode(this.selected) : null;
		},
		canSelect(this: any): boolean {
			const node = this.selectedNode;
			return !!node && !node.disabled && !node.placeholder && !this.loading && !this.createBusy;
		},
	},
	watch: {
		open(this: any, value: boolean): void {
			if (value) {
				this.start();
			}
		},
	},
	methods: {
		text(this: any, key: string, fallback: string): string {
			return (this.labels && this.labels[key]) || fallback;
		},
		placeholderFor(this: any, path: string): PickerNode {
			return { id: `${path}/\u0000loading`, label: this.text('loading', 'Loading…'), children: [], loaded: true, disabled: true, placeholder: true };
		},
		findNode(this: any, id: string): PickerNode | null {
			const walk = (nodes: PickerNode[]): PickerNode | null => {
				for (const node of nodes) {
					if (node.id === id) {
						return node;
					}
					const found = walk(node.children);
					if (found) {
						return found;
					}
				}
				return null;
			};
			return walk(this.items);
		},
		async start(this: any): Promise<void> {
			this.error = '';
			this.creating = false;
			this.newName = '';
			this.selected = undefined;
			this.expanded = [];
			const root: PickerNode = {
				id: this.root,
				label: this.rootLabel || this.root,
				children: [this.placeholderFor(this.root)],
				loaded: false,
				disabled: true,
			};
			this.items = [root];
			if (!this.service) {
				return;
			}
			this.loading = true;
			try {
				const permissions = await this.service.getPermissions(this.root);
				const rootNode = this.findNode(this.root);
				if (rootNode) {
					rootNode.disabled = !(permissions && permissions.addChildren);
				}
				await this.loadChildren(this.root);
				this.expanded = [this.root];
				await this.revealInitial();
			} catch (e) {
				this.error = this.errorText(e);
			} finally {
				this.loading = false;
			}
		},
		/** Expands down to `initialPath` and picks it, when it is under the root. */
		async revealInitial(this: any): Promise<void> {
			const rootNode = this.findNode(this.root);
			const target: string = this.initialPath || '';
			const under = target && (target === this.root || target.startsWith(this.root.endsWith('/') ? this.root : `${this.root}/`));
			if (!under || target === this.root) {
				if (rootNode && !rootNode.disabled) {
					this.selected = this.root;
				}
				return;
			}
			// Each ancestor in turn, from the root down.
			const ancestors: string[] = [];
			for (let p = parentOf(target); p.length >= this.root.length && p !== this.root; p = parentOf(p)) {
				ancestors.unshift(p);
			}
			for (const path of ancestors) {
				if (!this.findNode(path)) {
					return;
				}
				await this.loadChildren(path);
				if (!this.expanded.includes(path)) {
					this.expanded = [...this.expanded, path];
				}
			}
			const node = this.findNode(target);
			if (node) {
				this.selected = node.disabled ? undefined : target;
				await this.loadChildren(target);
				if (!this.expanded.includes(target)) {
					this.expanded = [...this.expanded, target];
				}
			} else if (rootNode && !rootNode.disabled) {
				this.selected = this.root;
			}
		},
		async loadChildren(this: any, path: string): Promise<void> {
			const node = this.findNode(path);
			if (!node || node.loaded || !this.service) {
				return;
			}
			const folders = await this.service.listFolders(path);
			node.children = folders.map((f: { path: string; name: string; hasChildren: boolean; addChildren: boolean }) => ({
				id: f.path,
				label: f.name,
				children: f.hasChildren ? [this.placeholderFor(f.path)] : [],
				loaded: !f.hasChildren,
				disabled: !f.addChildren,
			}));
			node.loaded = true;
		},
		onToggle(this: any, change: { id: string; expanded: boolean }): void {
			if (!change.expanded) {
				return;
			}
			this.loadChildren(change.id).catch((e: unknown) => {
				this.error = this.errorText(e);
			});
		},
		onSelect(this: any, node: PickerNode): void {
			if (node.placeholder) {
				return;
			}
			this.error = '';
			this.creating = false;
		},
		startCreate(this: any): void {
			if (!this.canSelect) {
				return;
			}
			this.newName = '';
			this.creating = true;
			setTimeout(() => {
				const input = this.$refs?.newFolderInput as HTMLInputElement | undefined;
				input?.focus();
			}, 0);
		},
		cancelCreate(this: any): void {
			this.creating = false;
			this.newName = '';
		},
		async create(this: any): Promise<void> {
			const parent: PickerNode | null = this.selectedNode;
			const name = (this.newName || '').trim();
			if (!parent || !name || this.createBusy || !this.service) {
				return;
			}
			if (/[\\/:*?"<>|\u0000-\u001F]/.test(name) || name === '.' || name === '..') {
				this.error = this.text('invalidName', 'The name cannot contain / \\ : * ? " < > |');
				return;
			}
			this.createBusy = true;
			this.error = '';
			try {
				await this.service.createFolder(parent.id, name);
				parent.loaded = false;
				parent.children = [this.placeholderFor(parent.id)];
				await this.loadChildren(parent.id);
				if (!this.expanded.includes(parent.id)) {
					this.expanded = [...this.expanded, parent.id];
				}
				const created = joinPath(parent.id, name);
				this.selected = this.findNode(created) ? created : parent.id;
				this.creating = false;
				this.newName = '';
			} catch (e) {
				this.error = this.errorText(e);
			} finally {
				this.createBusy = false;
			}
		},
		confirm(this: any): void {
			if (!this.canSelect) {
				return;
			}
			const path = this.selected;
			this.setOpen(false);
			this.$emit('select', path);
		},
		close(this: any): void {
			this.setOpen(false);
		},
		setOpen(this: any, value: boolean): void {
			if (!value) {
				this.$emit('update:open', false);
				this.$emit('close');
			}
		},
		errorText(e: unknown): string {
			if (e instanceof Error) {
				return e.message;
			}
			return String(e);
		},
	},
});
