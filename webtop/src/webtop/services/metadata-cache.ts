/**
 * Metadata Definition Cache
 *
 * Provides a global cache for metadata schema definitions stored at
 * /etc/metadata/schemas/. Supports initial loading, real-time updates
 * via node watch subscription, and broadcasting to all app iframes.
 */

import type { ContentServiceGraphQL } from './content-service-graphql.js';
import type { EventHub } from '../realtime/event-hub.js';

export interface SchemaDefinitionFile {
	key: string;
	label: string;
	description: string;
	properties: any[];
}

export class MetadataDefinitionCache {
	#definitions = new Map<string, SchemaDefinitionFile>();
	#loaded = false;
	#contentService: ContentServiceGraphQL;
	#eventHub: EventHub | null;
	#unwatchNode: (() => void) | null = null;
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
		if (this.#unwatchNode) {
			this.#unwatchNode();
			this.#unwatchNode = null;
		}
		if (this.#refreshDebounceTimer) {
			clearTimeout(this.#refreshDebounceTimer);
			this.#refreshDebounceTimer = null;
		}
	}

	/**
	 * Load all definition files from /etc/metadata/schemas/
	 */
	async #loadAll(): Promise<void> {
		const DEFINITIONS_PATH = '/etc/metadata/schemas';

		try {
			// Check if the directory exists
			const parentNode = await this.#contentService.getNode(DEFINITIONS_PATH);
			if (!parentNode) {
				this.#definitions.clear();
				this.#loaded = true;
				return;
			}

			const newDefs = new Map<string, SchemaDefinitionFile>();

			// Fetch all children
			for await (const batch of this.#contentService.listAllChildren(DEFINITIONS_PATH, 50)) {
				for (const node of batch) {
					if (!node.name?.endsWith('.json')) continue;
					if (!node.downloadUrl) continue;

					try {
						const response = await fetch(node.downloadUrl);
						if (!response.ok) continue;

						const text = await response.text();
						const data = JSON.parse(text) as SchemaDefinitionFile;
						if (data.key) {
							newDefs.set(data.key, data);
						}
					} catch {
						// Skip files that cannot be parsed
						console.warn(`[MetadataCache] Failed to parse: ${node.path}`);
					}
				}
			}

			this.#definitions = newDefs;
			this.#loaded = true;
		} catch {
			// Directory may not exist yet - that's OK
			this.#definitions.clear();
			this.#loaded = true;
		}
	}

	/**
	 * Start watching the definitions directory for changes via EventHub
	 */
	#startWatch(): void {
		if (!this.#eventHub) return;

		this.#unwatchNode = this.#eventHub.watchNode(
			'/etc/metadata/schemas',
			() => {
				// Debounce: reload after a short delay to batch rapid changes
				if (this.#refreshDebounceTimer) {
					clearTimeout(this.#refreshDebounceTimer);
				}
				this.#refreshDebounceTimer = window.setTimeout(async () => {
					this.#refreshDebounceTimer = null;
					await this.#loadAll();
					this.#broadcastUpdate();
				}, 1000);
			},
			false, // shallow - direct children only
		);
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
