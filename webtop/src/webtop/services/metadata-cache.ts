/**
 * Metadata Definition Cache
 *
 * Provides a global cache for metadata schema definitions stored at
 * /etc/metadata/schemas/. Schemas may pull in reusable property bundles
 * ("mixins") from /etc/metadata/mixins/ via a `mixins: ["seo", ...]`
 * reference list; the cache resolves those references at load time so
 * downstream consumers (wt-inspector, content-browser) see a single flat
 * `properties` array, identical to a non-mixin schema. The cache supports
 * initial loading, real-time updates via node watch subscription on both
 * directories, and broadcasting to all app iframes.
 *
 * Resolution rules (must match server-side MetadataAPI.resolveMixins):
 *   1. Mixins are expanded in declared order; first occurrence wins on
 *      collisions between two mixins.
 *   2. The schema's own properties fully replace a mixin property with the
 *      same `key` (full override, not deep merge).
 *   3. Mixin nesting is not supported in Phase 1 — any `mixins` field on a
 *      mixin file is ignored.
 *   4. Unresolvable mixin references are logged and skipped (the schema
 *      still loads with the rest of its mixins).
 */

import type { ContentServiceGraphQL } from './content-service-graphql.js';
import type { EventHub } from '../realtime/event-hub.js';

export interface SchemaDefinitionFile {
	key: string;
	label: string;
	description: string;
	properties: any[];
}

const SCHEMAS_PATH = '/etc/metadata/schemas';
const MIXINS_PATH  = '/etc/metadata/mixins';

export class MetadataDefinitionCache {
	// Resolved (mixin-expanded) schemas, keyed by schema key. This is the
	// public-facing surface — downstream consumers see no mixins.
	#definitions = new Map<string, SchemaDefinitionFile>();
	// Raw (unresolved) schemas as authored, retained so a mixin change can
	// trigger re-resolution without re-fetching every schema file.
	#rawSchemas = new Map<string, SchemaDefinitionFile>();
	// Raw mixin definitions, keyed by mixin key.
	#mixins = new Map<string, SchemaDefinitionFile>();
	#loaded = false;
	#contentService: ContentServiceGraphQL;
	#eventHub: EventHub | null;
	#unwatchSchemas: (() => void) | null = null;
	#unwatchMixins: (() => void) | null = null;
	#refreshDebounceTimer: number | null = null;

	constructor(contentService: ContentServiceGraphQL, eventHub: EventHub | null) {
		this.#contentService = contentService;
		this.#eventHub = eventHub;
	}

	get loaded(): boolean {
		return this.#loaded;
	}

	getDefinition(key: string): SchemaDefinitionFile | undefined {
		return this.#definitions.get(key);
	}

	getAllDefinitions(): SchemaDefinitionFile[] {
		return [...this.#definitions.values()];
	}

	/**
	 * Initialize: load all definitions and start watching for changes
	 */
	async initialize(): Promise<void> {
		await this.#loadAll();
		this.#startWatch();
	}

	/**
	 * Force refresh all definitions
	 */
	async refresh(): Promise<void> {
		await this.#loadAll();
	}

	/**
	 * Clean up subscription
	 */
	destroy(): void {
		if (this.#unwatchSchemas) {
			this.#unwatchSchemas();
			this.#unwatchSchemas = null;
		}
		if (this.#unwatchMixins) {
			this.#unwatchMixins();
			this.#unwatchMixins = null;
		}
		if (this.#refreshDebounceTimer) {
			clearTimeout(this.#refreshDebounceTimer);
			this.#refreshDebounceTimer = null;
		}
	}

	/**
	 * Load all definition files from /etc/metadata/{schemas,mixins}/ and
	 * resolve mixin references into flat schemas.
	 */
	async #loadAll(): Promise<void> {
		const [rawSchemas, mixins] = await Promise.all([
			this.#loadDir(SCHEMAS_PATH),
			this.#loadDir(MIXINS_PATH),
		]);

		this.#rawSchemas = rawSchemas;
		this.#mixins = mixins;

		const resolved = new Map<string, SchemaDefinitionFile>();
		for (const [key, raw] of rawSchemas) {
			resolved.set(key, this.#resolveMixins(raw));
		}
		this.#definitions = resolved;
		this.#loaded = true;
	}

	async #loadDir(dirPath: string): Promise<Map<string, SchemaDefinitionFile>> {
		const out = new Map<string, SchemaDefinitionFile>();
		try {
			const parentNode = await this.#contentService.getNode(dirPath);
			if (!parentNode) return out;

			for await (const batch of this.#contentService.listAllChildren(dirPath, 50)) {
				for (const node of batch) {
					if (!node.name?.endsWith('.json')) continue;
					if (!node.downloadUrl) continue;

					try {
						const response = await fetch(node.downloadUrl);
						if (!response.ok) continue;

						const text = await response.text();
						const data = JSON.parse(text) as SchemaDefinitionFile;
						if (data.key) {
							out.set(data.key, data);
						}
					} catch {
						console.warn(`[MetadataCache] Failed to parse: ${node.path}`);
					}
				}
			}
		} catch {
			// Directory may not exist yet — caller treats an empty map as "none".
		}
		return out;
	}

	#resolveMixins(schema: SchemaDefinitionFile): SchemaDefinitionFile {
		const mixinRefs: unknown = (schema as any).mixins;
		if (!Array.isArray(mixinRefs) || mixinRefs.length === 0) {
			// Strip the `mixins` field for shape consistency; consumers should
			// never see it.
			const { mixins: _drop, ...rest } = schema as any;
			return rest as SchemaDefinitionFile;
		}

		const resolved: any[] = [];
		const indexByKey = new Map<string, number>();

		for (const mxKeyRaw of mixinRefs) {
			if (typeof mxKeyRaw !== 'string') continue;
			const mx = this.#mixins.get(mxKeyRaw);
			if (!mx) {
				console.warn(`[MetadataCache] Mixin not found: "${mxKeyRaw}" referenced by schema "${schema.key}"`);
				continue;
			}
			const mxProps = Array.isArray(mx.properties) ? mx.properties : [];
			for (const p of mxProps) {
				if (!p || typeof p.key !== 'string') continue;
				if (indexByKey.has(p.key)) {
					console.warn(`[MetadataCache] Mixin property "${p.key}" from "${mxKeyRaw}" shadowed by earlier mixin (schema "${schema.key}")`);
					continue;
				}
				indexByKey.set(p.key, resolved.length);
				resolved.push(p);
			}
		}

		const ownProps = Array.isArray(schema.properties) ? schema.properties : [];
		for (const p of ownProps) {
			if (!p || typeof p.key !== 'string') continue;
			const idx = indexByKey.get(p.key);
			if (idx != null) {
				resolved[idx] = p; // full override
			} else {
				indexByKey.set(p.key, resolved.length);
				resolved.push(p);
			}
		}

		const { mixins: _drop, ...rest } = schema as any;
		return { ...(rest as SchemaDefinitionFile), properties: resolved };
	}

	/**
	 * Start watching both definition directories for changes via EventHub.
	 * A change in either schemas/ or mixins/ triggers a coalesced reload so
	 * mixin edits propagate to every dependent schema.
	 */
	#startWatch(): void {
		if (!this.#eventHub) return;

		const debouncedReload = () => {
			if (this.#refreshDebounceTimer) {
				clearTimeout(this.#refreshDebounceTimer);
			}
			this.#refreshDebounceTimer = window.setTimeout(async () => {
				this.#refreshDebounceTimer = null;
				await this.#loadAll();
				this.#broadcastUpdate();
			}, 1000);
		};

		this.#unwatchSchemas = this.#eventHub.watchNode(SCHEMAS_PATH, debouncedReload, false);
		this.#unwatchMixins  = this.#eventHub.watchNode(MIXINS_PATH,  debouncedReload, false);
	}

	/**
	 * Broadcast update to all app iframes
	 */
	#broadcastUpdate(): void {
		const iframes = document.querySelectorAll<HTMLIFrameElement>('iframe');
		const message = {
			type: 'metadata-definitions-updated',
			event: 'CHANGED',
		};
		for (const iframe of iframes) {
			try {
				iframe.contentWindow?.postMessage(message, window.location.origin);
			} catch {
				// Ignore cross-origin errors
			}
		}
	}
}
