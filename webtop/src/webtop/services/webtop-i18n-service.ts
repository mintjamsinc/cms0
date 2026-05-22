/**
 * Webtop I18n Service
 *
 * Provides a global cache for i18n message bundles stored at /etc/i18n/.
 * Expected layout: one flat JSON file per locale, e.g. /etc/i18n/en.json,
 * /etc/i18n/ja.json, containing a flat object whose keys are hierarchical
 * dotted message IDs:
 *
 *   {
 *     "cms.validation.string.tooLong": "Value is too long (max {max} chars)",
 *     ...
 *   }
 *
 * Supports initial loading, real-time updates via node watch subscription,
 * broadcasting to all app iframes, and message formatting via intl-messageformat.
 */

import { IntlMessageFormat } from 'intl-messageformat';
import type { ContentServiceGraphQL } from './content-service-graphql.js';
import type { EventHub } from '../realtime/event-hub.js';

export interface I18nMessageDescriptor {
	messageId: string;
	params?: Record<string, any>;
	fallbackMessage?: string;
}

export interface I18nValidationError {
	messageId: string;
	severity?: 'error' | 'warning' | 'info';
	params?: Record<string, any>;
	ruleId?: string;
	fallbackMessage?: string;
}

/**
 * Resolve the locale to use.
 *
 * Consults the user's Localization preference (via window.Webtop.api.localization)
 * first, then falls back to the browser locale.
 */
export function resolveLocale(): string {
	try {
		const localization = (window as any).Webtop?.api?.localization;
		if (localization && localization.effectiveLocale) {
			return String(localization.effectiveLocale).toLowerCase();
		}
	} catch {
		// Ignore — fall through to browser locale
	}
	return (navigator.language || 'en').toLowerCase();
}

export class I18nService {
	#bundles = new Map<string, Record<string, string>>();
	#loaded = false;
	#contentService: ContentServiceGraphQL;
	#eventHub: EventHub | null;
	#unwatchNode: (() => void) | null = null;
	#refreshDebounceTimer: number | null = null;
	// Cache of compiled IntlMessageFormat instances keyed by `${locale}::${messageId}`.
	#formatterCache = new Map<string, IntlMessageFormat>();

	constructor(contentService: ContentServiceGraphQL, eventHub: EventHub | null) {
		this.#contentService = contentService;
		this.#eventHub = eventHub;
	}

	get loaded(): boolean {
		return this.#loaded;
	}

	get currentLocale(): string {
		return resolveLocale();
	}

	/** List of locale identifiers for which a bundle is loaded. */
	get availableLocales(): string[] {
		return [...this.#bundles.keys()].sort();
	}

	/**
	 * Initialize: load all bundles and start watching for changes.
	 */
	async initialize(): Promise<void> {
		await this.#loadAll();
		this.#startWatch();
	}

	/**
	 * Force refresh all bundles.
	 */
	async refresh(): Promise<void> {
		await this.#loadAll();
		this.#formatterCache.clear();
	}

	/**
	 * Clear the compiled formatter cache. Call after the effective locale
	 * changes so future format() calls pick up the new locale.
	 */
	invalidateFormatterCache(): void {
		this.#formatterCache.clear();
	}

	/**
	 * Clean up subscription.
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
	 * Format a message with the specified locale (or current locale if
	 * omitted). Falls back through: exact locale → language only → 'en' →
	 * fallbackMessage → messageId itself.
	 */
	format(
		messageId: string,
		params?: Record<string, any>,
		fallbackMessage?: string,
		locale?: string,
	): string {
		const targetLocale = (locale || this.currentLocale).toLowerCase();
		const candidates = this.#buildLocaleCandidates(targetLocale);

		for (const loc of candidates) {
			const bundle = this.#bundles.get(loc);
			if (!bundle) continue;
			const template = bundle[messageId];
			if (template == null) continue;
			try {
				const cacheKey = `${loc}::${messageId}`;
				let formatter = this.#formatterCache.get(cacheKey);
				if (!formatter) {
					formatter = new IntlMessageFormat(template, loc);
					this.#formatterCache.set(cacheKey, formatter);
				}
				const formatted = formatter.format(params || {});
				return Array.isArray(formatted) ? formatted.join('') : String(formatted);
			} catch {
				// Malformed template — fall through to next candidate
			}
		}

		if (fallbackMessage) {
			try {
				const formatter = new IntlMessageFormat(fallbackMessage, targetLocale);
				const formatted = formatter.format(params || {});
				return Array.isArray(formatted) ? formatted.join('') : String(formatted);
			} catch {
				return fallbackMessage;
			}
		}
		return messageId;
	}

	/**
	 * Format a validation error object (see I18nValidationError).
	 */
	formatValidationError(err: I18nValidationError, locale?: string): string {
		return this.format(err.messageId, err.params, err.fallbackMessage, locale);
	}

	/**
	 * Return locale fallback chain: exact → language only → 'en'.
	 */
	#buildLocaleCandidates(locale: string): string[] {
		const set = new Set<string>();
		set.add(locale);
		const dash = locale.indexOf('-');
		if (dash > 0) set.add(locale.substring(0, dash));
		set.add('en');
		return [...set];
	}

	/**
	 * Load all bundle files from /etc/i18n/.
	 */
	async #loadAll(): Promise<void> {
		const BUNDLES_PATH = '/etc/i18n';

		try {
			const parentNode = await this.#contentService.getNode(BUNDLES_PATH);
			if (!parentNode) {
				this.#bundles.clear();
				this.#loaded = true;
				return;
			}

			const newBundles = new Map<string, Record<string, string>>();

			for await (const batch of this.#contentService.listAllChildren(BUNDLES_PATH, 50)) {
				for (const node of batch) {
					if (!node.name?.endsWith('.json')) continue;
					if (!node.downloadUrl) continue;

					// Locale is derived from the file name minus extension:
					// /etc/i18n/ja.json → "ja", /etc/i18n/en-US.json → "en-us"
					const locale = node.name.replace(/\.json$/, '').toLowerCase();

					try {
						const response = await fetch(node.downloadUrl);
						if (!response.ok) continue;
						const text = await response.text();
						const data = JSON.parse(text) as Record<string, string>;
						if (data && typeof data === 'object') {
							newBundles.set(locale, data);
						}
					} catch {
						console.warn(`[I18nService] Failed to parse: ${node.path}`);
					}
				}
			}

			this.#bundles = newBundles;
			this.#formatterCache.clear();
			this.#loaded = true;
		} catch {
			this.#bundles.clear();
			this.#loaded = true;
		}
	}

	/**
	 * Start watching the bundles directory for changes via EventHub.
	 */
	#startWatch(): void {
		if (!this.#eventHub) return;

		this.#unwatchNode = this.#eventHub.watchNode(
			'/etc/i18n',
			() => {
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
	 * Broadcast update to all app iframes.
	 */
	#broadcastUpdate(): void {
		const iframes = document.querySelectorAll<HTMLIFrameElement>('iframe');
		const message = {
			type: 'i18n-bundles-updated',
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
