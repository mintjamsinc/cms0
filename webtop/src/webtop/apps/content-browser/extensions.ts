// Extensions of the Content Browser.
//
// An app declares what it adds to the browser in its app.yml, by name:
//
//   extensions:
//     content-browser:
//       drafts:
//         title: Site (drafts)
//         module: browser/drafts.js
//
// and a folder's .extensions.yml turns it on for the folder and everything
// below it (the nearest file on the way up counts, as with .web.yml):
//
//   content-browser:
//     - site/drafts
//
// The module is loaded into this frame the first time a folder that names it
// is shown. Its default export is called once per root (the folder of the
// .extensions.yml) with a context, and answers with the hooks below. An
// extension hands back data only; the browser draws it:
//
//   export default function (ctx) {
//     return {
//       column: { title },                       // one column, optional
//       async states(items, folder) {},          // -> { [path]: { label, tone, title } }
//       menu(selection, folder) {},              // -> [{ id, label, icon, danger }], synchronous
//       async run(id, selection, folder) {},     // a menu item or a banner button was chosen
//       async banner(folder) {},                 // -> { text, tone, actions: [{ id, label }] } | null
//     };
//   }
//
// Nothing here may stop the list: a module that fails to load, throws or
// takes too long is logged and left out. An item of .extensions.yml that no
// installed app declares is dropped without a word, since an uninstalled
// app leaves its items behind.

/** What is extended: the top-level key of .extensions.yml and of `extensions` in app.yml. */
const KIND = 'content-browser';
/** How long states() and banner() may take before the list goes on without them. */
const HOOK_TIMEOUT_MS = 5000;
const TONES = ['neutral', 'info', 'success', 'warning', 'danger'];
const MAX_BANNER_ACTIONS = 3;

export interface ExtensionItem {
	path: string;
	name: string;
	isCollection: boolean;
	mimeType: string | null;
	modified: string | null;
	lockOwner: string | null;
	/** The state this extension gave the item last (menu() decides by it). */
	state?: ExtensionState | null;
}

export interface ExtensionState {
	label: string;
	tone: string;
	title?: string;
}

export interface ExtensionColumn {
	key: string;
	title: string;
}

export interface ExtensionMenuItem {
	id: string;
	label: string;
	icon?: string;
	danger?: boolean;
}

export interface ExtensionBanner {
	key: string;
	text: string;
	tone: string;
	actions: { id: string; label: string }[];
}

/** What decorate() hands the browser: all of it plain data. */
export interface ExtensionDecoration {
	columns: ExtensionColumn[];
	/** path -> column key -> state */
	states: Record<string, Record<string, ExtensionState>>;
	banners: ExtensionBanner[];
}

/** What the browser lends the host. */
export interface ExtensionHostDeps {
	/** instance.api of the browser's window. */
	api: any;
	userId: string | null;
	locale: () => string | null;
	/** The installed apps (window.parent.Webtop.apps). */
	apps: () => any[];
	/** Opens an app by relPath with launch options. */
	openApp: (relPath: string, options: Record<string, unknown>) => void;
	openFolder: (path: string) => void;
	select: (paths: string[]) => void;
	notify: (text: string, tone: string) => void;
	/** Asks the browser to decorate again (an extension learned of a change elsewhere). */
	refresh: () => void;
}

interface Hooks {
	column?: { title?: string };
	states?: (items: ExtensionItem[], folder: string | null) => any;
	menu?: (selection: ExtensionItem[], folder: string | null) => any;
	run?: (id: string, selection: ExtensionItem[], folder: string | null) => any;
	banner?: (folder: string) => any;
}

interface Active {
	/** <app relPath>/<name>@<root>: one instance per extension and root. */
	key: string;
	use: string;
	root: string;
	hooks: Hooks;
}

interface Resolved {
	root: string | null;
	entries: { use: string; options: Record<string, unknown> | null }[];
}

const FOLDER_EXTENSIONS_QUERY = `
	query FolderExtensions($paths: [String!]!) {
		folderExtensions(paths: $paths) {
			path
			root
			invalid
			entries { kind use options }
		}
	}
`;

function parentOf(path: string): string {
	const index = path.lastIndexOf('/');
	return index <= 0 ? '/' : path.substring(0, index);
}

function timeout<T>(promise: Promise<T>, ms: number): Promise<T> {
	return new Promise<T>((resolve, reject) => {
		const timer = window.setTimeout(() => reject(new Error('timed out')), ms);
		promise.then(
			(value) => { window.clearTimeout(timer); resolve(value); },
			(error) => { window.clearTimeout(timer); reject(error); },
		);
	});
}

function tone(value: unknown): string {
	return typeof value === 'string' && TONES.includes(value) ? value : 'neutral';
}

export class ExtensionHost {
	private deps: ExtensionHostDeps;
	/** folder -> what its nearest .extensions.yml turns on. Dropped by forget(). */
	private resolved = new Map<string, Resolved>();
	/** instance key -> the instance, or null when it could not be made. */
	private instances = new Map<string, Promise<Active | null>>();
	/** What was decorated last: the instances in force per folder, and the states given. */
	private activeByFolder = new Map<string, Active[]>();
	private lastStates: Record<string, Record<string, ExtensionState>> = {};
	/** Whether the server knows folderExtensions; an older one makes the host inert. */
	private unsupported = false;
	private warnedInvalid = new Set<string>();

	constructor(deps: ExtensionHostDeps) {
		this.deps = deps;
	}

	/** Forgets what the folders resolve to, so the next decorate() asks again. */
	forget(): void {
		this.resolved.clear();
	}

	/**
	 * The columns, states and banners for a list. `folder` is the folder being
	 * browsed, or null for a search result (whose items come from anywhere, and
	 * which shows no banner).
	 */
	async decorate(items: ExtensionItem[], folder: string | null): Promise<ExtensionDecoration> {
		const decoration: ExtensionDecoration = { columns: [], states: {}, banners: [] };
		if (this.unsupported) return decoration;

		// The folders in play: the one browsed, or those of the result's items.
		const byFolder = new Map<string, ExtensionItem[]>();
		if (folder !== null) {
			byFolder.set(folder, items);
		} else {
			for (const item of items) {
				const parent = parentOf(item.path);
				const group = byFolder.get(parent);
				if (group) group.push(item); else byFolder.set(parent, [item]);
			}
		}
		await this.resolve(Array.from(byFolder.keys()));

		// Group the items by instance: one states() call per extension and root.
		const activeByFolder = new Map<string, Active[]>();
		const work = new Map<string, { active: Active; items: ExtensionItem[] }>();
		for (const [path, group] of byFolder) {
			const actives = await this.activate(path);
			activeByFolder.set(path, actives);
			for (const active of actives) {
				const entry = work.get(active.key);
				if (entry) entry.items.push(...group); else work.set(active.key, { active, items: [...group] });
			}
		}

		await Promise.all(Array.from(work.values()).map(async ({ active, items: group }) => {
			const hooks = active.hooks;
			if (hooks.column && typeof hooks.states === 'function') {
				decoration.columns.push({ key: active.key, title: String(hooks.column.title || '') });
				if (group.length > 0) {
					try {
						const states = await timeout(Promise.resolve(hooks.states(group, folder)), HOOK_TIMEOUT_MS);
						if (states && typeof states === 'object') {
							for (const item of group) {
								const state = states[item.path];
								if (!state || typeof state.label !== 'string' || !state.label) continue;
								(decoration.states[item.path] ||= {})[active.key] = {
									label: state.label,
									tone: tone(state.tone),
									title: typeof state.title === 'string' ? state.title : undefined,
								};
							}
						}
					} catch (error) {
						console.warn(`[content-browser] extension ${active.use}: states failed`, error);
					}
				}
			}
			if (folder !== null && typeof hooks.banner === 'function') {
				try {
					const banner = await timeout(Promise.resolve(hooks.banner(folder)), HOOK_TIMEOUT_MS);
					if (banner && typeof banner.text === 'string' && banner.text) {
						const actions = Array.isArray(banner.actions) ? banner.actions : [];
						decoration.banners.push({
							key: active.key,
							text: banner.text,
							tone: tone(banner.tone),
							actions: actions
								.filter((a: any) => a && typeof a.id === 'string' && typeof a.label === 'string')
								.slice(0, MAX_BANNER_ACTIONS)
								.map((a: any) => ({ id: a.id, label: a.label })),
						});
					}
				} catch (error) {
					console.warn(`[content-browser] extension ${active.use}: banner failed`, error);
				}
			}
		}));

		// Promise.all finishes in any order; the columns and banners keep the
		// order of .extensions.yml.
		const order = Array.from(work.keys());
		decoration.columns.sort((a, b) => order.indexOf(a.key) - order.indexOf(b.key));
		decoration.banners.sort((a, b) => order.indexOf(a.key) - order.indexOf(b.key));

		this.activeByFolder = activeByFolder;
		this.lastStates = decoration.states;
		return decoration;
	}

	/**
	 * The menu items the extensions add for a selection (empty: the folder's
	 * background). Synchronous, from what decorate() left: a menu does not wait.
	 * The ids are `ext:<instance key>:<id>`, which run() takes back.
	 */
	menu(selection: ExtensionItem[], folder: string | null): ExtensionMenuItem[] {
		const items: ExtensionMenuItem[] = [];
		for (const active of this.activesFor(selection, folder)) {
			if (typeof active.hooks.menu !== 'function') continue;
			try {
				const offered = active.hooks.menu(this.withStates(selection, active), folder);
				if (!Array.isArray(offered)) continue;
				for (const item of offered) {
					if (!item || typeof item.id !== 'string' || typeof item.label !== 'string') continue;
					items.push({
						id: `ext:${active.key}:${item.id}`,
						label: item.label,
						icon: typeof item.icon === 'string' ? item.icon : undefined,
						danger: item.danger === true,
					});
				}
			} catch (error) {
				console.warn(`[content-browser] extension ${active.use}: menu failed`, error);
			}
		}
		return items;
	}

	/** Whether a context-menu action is an extension's. */
	static isAction(action: string): boolean {
		return action.startsWith('ext:');
	}

	/** Runs a menu item (`ext:<key>:<id>`) or a banner button (key and id apart). */
	async run(action: string, selection: ExtensionItem[], folder: string | null, key?: string): Promise<void> {
		let id = action;
		if (key === undefined) {
			// The key holds no colon after its root's path... but a path may, so
			// the instance is found by prefix rather than by splitting.
			const rest = action.substring('ext:'.length);
			const active = this.allActives().find((a) => rest.startsWith(a.key + ':'));
			if (!active) return;
			key = active.key;
			id = rest.substring(active.key.length + 1);
		}
		const active = this.allActives().find((a) => a.key === key);
		if (!active || typeof active.hooks.run !== 'function') return;
		try {
			await active.hooks.run(id, this.withStates(selection, active), folder);
		} catch (error) {
			console.warn(`[content-browser] extension ${active.use}: run failed`, error);
		}
	}

	// ---------------------------------------------------------------------

	private allActives(): Active[] {
		const seen = new Map<string, Active>();
		for (const actives of this.activeByFolder.values()) {
			for (const active of actives) seen.set(active.key, active);
		}
		return Array.from(seen.values());
	}

	/** The instances in force for a selection: those of its folder, when it has one folder. */
	private activesFor(selection: ExtensionItem[], folder: string | null): Active[] {
		if (folder !== null) return this.activeByFolder.get(folder) || [];
		const parents = new Set(selection.map((item) => parentOf(item.path)));
		if (parents.size === 0) return [];
		// A selection across folders is offered what every one of them has.
		let common: Active[] | null = null;
		for (const parent of parents) {
			const actives: Active[] = this.activeByFolder.get(parent) || [];
			common = common === null ? actives : common.filter((a: Active) => actives.includes(a));
		}
		return common || [];
	}

	private withStates(selection: ExtensionItem[], active: Active): ExtensionItem[] {
		return selection.map((item) => ({ ...item, state: this.lastStates[item.path]?.[active.key] || null }));
	}

	private async resolve(folders: string[]): Promise<void> {
		const missing = folders.filter((folder) => !this.resolved.has(folder));
		if (missing.length === 0) return;
		try {
			// The server takes 200 paths at once.
			for (let i = 0; i < missing.length; i += 200) {
				const data = await this.deps.api.graphql.query(FOLDER_EXTENSIONS_QUERY, { paths: missing.slice(i, i + 200) });
				for (const result of data?.folderExtensions || []) {
					if (result.invalid && result.root && !this.warnedInvalid.has(result.root)) {
						this.warnedInvalid.add(result.root);
						console.warn(`[content-browser] ${result.root}/.extensions.yml could not be read as YAML and is ignored.`);
					}
					this.resolved.set(result.path, {
						root: result.root || null,
						entries: (result.entries || [])
							.filter((entry: any) => entry.kind === KIND)
							.map((entry: any) => ({ use: entry.use, options: entry.options || null })),
					});
				}
			}
		} catch (error) {
			// A server without folderExtensions: no extensions, and no further asking.
			this.unsupported = true;
			console.warn('[content-browser] extensions are unavailable', error);
		}
		for (const folder of missing) {
			if (!this.resolved.has(folder)) this.resolved.set(folder, { root: null, entries: [] });
		}
	}

	/** The instances of the extensions a folder's .extensions.yml turns on, in its order. */
	private async activate(folder: string): Promise<Active[]> {
		const resolved = this.resolved.get(folder);
		if (!resolved || !resolved.root) return [];
		const actives: Active[] = [];
		for (const entry of resolved.entries) {
			const key = `${entry.use}@${resolved.root}`;
			let promise = this.instances.get(key);
			if (!promise) {
				promise = this.instantiate(key, entry.use, resolved.root, entry.options);
				this.instances.set(key, promise);
			}
			const active = await promise;
			if (active) actives.push(active);
		}
		return actives;
	}

	private async instantiate(key: string, use: string, root: string, options: Record<string, unknown> | null): Promise<Active | null> {
		// <app relPath>/<name>: the app's folder may itself be nested.
		const slash = use.lastIndexOf('/');
		const relPath = use.substring(0, slash);
		const name = use.substring(slash + 1);
		const app = this.deps.apps().find((a: any) => a.relPath === relPath);
		const declared = app && (app.extensions || []).find((e: any) => e.kind === KIND && e.identifier === name);
		if (!declared) {
			// Not installed (any more), or not declared: the item is dropped silently.
			return null;
		}
		try {
			const version = app.modified ? `?v=${encodeURIComponent(String(app.modified))}` : '';
			const url = new URL(`../${relPath}/${declared.module}${version}`, document.baseURI).href;
			const module = await import(/* @vite-ignore */ url);
			if (typeof module.default !== 'function') {
				throw new Error('the module has no default function');
			}
			const deps = this.deps;
			const hooks = await module.default({
				root,
				options: options || {},
				api: deps.api,
				userId: deps.userId,
				get locale() { return deps.locale(); },
				t: (id: string, params?: Record<string, unknown>, fallback?: string) => {
					const i18n = deps.api?.i18n;
					if (!i18n || typeof i18n.format !== 'function') return fallback ?? id;
					return i18n.format(id, params || undefined, fallback, undefined, relPath);
				},
				openApp: (launch?: Record<string, unknown>) => deps.openApp(relPath, launch || {}),
				openFolder: (path: string) => deps.openFolder(path),
				select: (paths: string[]) => deps.select(paths),
				refresh: () => deps.refresh(),
				notify: (text: string, kind?: string) => deps.notify(String(text), tone(kind)),
			});
			if (!hooks || typeof hooks !== 'object') {
				throw new Error('the module returned no hooks');
			}
			return { key, use, root, hooks };
		} catch (error) {
			console.warn(`[content-browser] extension ${use} could not be loaded`, error);
			return null;
		}
	}
}

/** The fields of a list item an extension is shown. */
export function toExtensionItem(item: any): ExtensionItem {
	return {
		path: item.path,
		name: item.name,
		isCollection: !!item.isCollection,
		mimeType: item.mimeType || null,
		modified: item.lastModified ? String(item.lastModified) : null,
		lockOwner: item.isLocked ? (item.lockInfo?.lockOwner || item.lockOwner || null) : null,
	};
}
