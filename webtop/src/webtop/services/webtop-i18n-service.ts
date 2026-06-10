/**
 * Webtop I18n Service
 *
 * Provides a global cache for i18n message bundles stored at /etc/i18n/.
 * Bundles are flat JSON objects whose keys are hierarchical dotted message IDs:
 *
 *   {
 *     "cms.validation.string.tooLong": "Value is too long (max {max} chars)",
 *     ...
 *   }
 *
 * Layout — modular, one file per app, merged by locale. The locale of a file is
 * the **last dot-delimited segment** of its name (before `.json`):
 *
 *   /etc/i18n/en.json                 -> locale "en"   (cms0 core: common.*, webtop.*, cms.*)
 *   /etc/i18n/ja.json                 -> locale "ja"
 *   /etc/i18n/content-browser.en.json -> locale "en"   (one cms0 app)
 *   /etc/i18n/content-browser.ja.json -> locale "ja"
 *   /etc/i18n/commerce.en.json        -> locale "en"   (an add-on module)
 *   /etc/i18n/commerce.ja.json        -> locale "ja"
 *   /etc/i18n/en-US.json              -> locale "en-us"
 *
 * All files for the same locale are **merged** into one bundle, so independently
 * deployed units (the cms0 core, each cms0 app, the Commerce app suite, future
 * app packs) each ship their own `<unit>.<locale>.json` and contribute their
 * keys without editing — or colliding with — another unit's file. Units must
 * keep their keys in disjoint namespaces (an app owns `app.<appId>.*`); on a key
 * collision the file that sorts last by name wins, which is deterministic but
 * unintended, so namespaces are the contract that keeps merges clean.
 *
 * Supports initial loading, real-time updates via node watch subscription,
 * broadcasting to all app iframes, and message formatting via intl-messageformat.
 */

import { IntlMessageFormat } from 'intl-messageformat';
import type { ContentServiceGraphQL } from './content-service-graphql.js';
import type { EventHub } from '../realtime/event-hub.js';

/**
 * Options applied to every {@link IntlMessageFormat} we compile.
 *
 * `ignoreTag: true` disables intl-messageformat's rich-text **tag** syntax, so
 * angle-bracket markup inside a message (e.g. `<strong>…</strong>`) is preserved
 * as literal text instead of being parsed as a `<strong>` element. Without this,
 * any message containing markup throws at `format()` time (a tag with no handler
 * in `params`), and the call silently degrades to returning the raw message id.
 *
 * Apps legitimately embed inline HTML in messages and render the result with
 * `v-html` (emphasis, inline code, etc.). Treating tags as literal text keeps
 * that a first-class, predictable capability for every bundle while leaving the
 * rest of ICU MessageFormat (placeholders, plural/select, number/date) intact.
 */
const MESSAGE_FORMAT_OPTS = { ignoreTag: true } as const;

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
					formatter = new IntlMessageFormat(template, loc, undefined, MESSAGE_FORMAT_OPTS);
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
				const formatter = new IntlMessageFormat(fallbackMessage, targetLocale, undefined, MESSAGE_FORMAT_OPTS);
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
	 * Return a resolved, flat message map for a locale, merged across the
	 * fallback chain (exact → language → 'en') so the **exact** locale wins for
	 * any key it defines while missing keys degrade to the language bundle and
	 * finally English — the same precedence {@link format} applies per key.
	 *
	 * Optionally restrict the result to keys under a dotted `prefix`
	 * (e.g. `'form.commerce.shopify.'`) so a caller pulls only its own
	 * namespace rather than the entire bundle.
	 *
	 * This exists for sandboxed consumers that cannot reach this service
	 * directly — notably BPMN form iframes, which run in an opaque origin
	 * (`sandbox` without `allow-same-origin`) and therefore have no
	 * `window.parent.Webtop` access. The Tasks host forwards the result over
	 * its postMessage RPC bridge, letting a form translate its own labels (and
	 * compile ICU MessageFormat templates locally) against the very same
	 * `/etc/i18n/*.json` bundles every shell app uses.
	 */
	getMessages(prefix?: string, locale?: string): Record<string, string> {
		const targetLocale = (locale || this.currentLocale).toLowerCase();
		const candidates = this.#buildLocaleCandidates(targetLocale);
		// Overlay from least- to most-specific (en → language → exact) so a key
		// defined by the exact locale overrides the same key in its fallbacks.
		const merged: Record<string, string> = {};
		for (const loc of [...candidates].reverse()) {
			const bundle = this.#bundles.get(loc);
			if (!bundle) continue;
			for (const [key, template] of Object.entries(bundle)) {
				if (prefix && !key.startsWith(prefix)) continue;
				merged[key] = template;
			}
		}
		return merged;
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

			// Collect every bundle file first, then load them in a stable order
			// (sorted by file name) so that, when two files target the same locale,
			// the merge result is deterministic across boots. Modules keep their
			// keys in disjoint namespaces, so in practice the merge never has to
			// resolve a real conflict; sorting just removes any boot-order luck.
			const files: Array<{ name: string; locale: string; downloadUrl: string; path?: string }> = [];
			for await (const batch of this.#contentService.listAllChildren(BUNDLES_PATH, 50)) {
				for (const node of batch) {
					if (!node.name?.endsWith('.json')) continue;
					if (!node.downloadUrl) continue;

					// Locale is the last dot-delimited segment of the file name
					// (before .json), so a module prefix is ignored:
					//   ja.json          → "ja"
					//   commerce.ja.json → "ja"
					//   en-US.json       → "en-us"
					const base = node.name.replace(/\.json$/, '');
					const locale = base.substring(base.lastIndexOf('.') + 1).toLowerCase();
					if (!locale) continue;

					files.push({ name: node.name, locale, downloadUrl: node.downloadUrl, path: node.path });
				}
			}

			files.sort((a, b) => a.name.localeCompare(b.name));

			for (const file of files) {
				try {
					const response = await fetch(file.downloadUrl);
					if (!response.ok) continue;
					const text = await response.text();
					const data = JSON.parse(text) as Record<string, string>;
					if (data && typeof data === 'object') {
						// Merge into (rather than replace) the locale's bundle so
						// multiple module files for one locale all contribute.
						const merged = newBundles.get(file.locale) || {};
						Object.assign(merged, data);
						newBundles.set(file.locale, merged);
					}
				} catch {
					console.warn(`[I18nService] Failed to parse: ${file.path ?? file.name}`);
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
		// Notify the shell itself first: it owns this service and therefore does
		// not receive the iframe postMessage below. Reuse the `webtop-message`
		// CustomEvent channel that the shell already listens on (index.ts), so
		// the desktop / menus / dialogs repaint their `t()` bindings too.
		try {
			document.dispatchEvent(
				new CustomEvent('webtop-message', { detail: { type: 'i18n-bundles-updated' } }),
			);
		} catch {
			// Ignore — non-DOM context.
		}

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
