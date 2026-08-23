/**
 * Webtop I18n Service
 *
 * Provides a global cache for i18n message bundles. Bundles are flat JSON
 * objects whose keys are hierarchical dotted message IDs:
 *
 *   {
 *     "cms.validation.string.tooLong": "Value is too long (max {max} chars)",
 *     ...
 *   }
 *
 * Bundles come from two places:
 *
 * **1. Global bundles — `/etc/i18n/`.** Cross-app namespaces (`common.*`,
 * `webtop.*`, `cms.*`, `form.*`) and any key that must be visible outside a
 * single app. The locale of a file is the **last dot-delimited segment** of
 * its name (before `.json`):
 *
 *   /etc/i18n/en.json                 -> locale "en"   (cms0 core)
 *   /etc/i18n/ja.json                 -> locale "ja"
 *   /etc/i18n/wt-inspector.en.json    -> locale "en"   (shared component)
 *   /etc/i18n/en-US.json              -> locale "en-us"
 *
 * All global files for the same locale are **merged** into one bundle (sorted
 * by file name for determinism), so independently deployed units contribute
 * keys without editing each other's files. Units must keep their keys in
 * disjoint namespaces.
 *
 * **2. App bundles — `/content/webtop/apps/<appId>/i18n/<locale>.json`.**
 * Each app ships its own bundle files inside its app folder, so the app's
 * strings deploy (and hot-reload) with the app itself. App bundles are
 * **scoped**: they are kept per app and consulted only when a lookup carries
 * that app's id (the `translate()` composable passes `instance.app.relPath`
 * automatically). Keys therefore cannot collide across apps, and an app may
 * use short keys (`title`, `orders.empty`) — though the conventional
 * `app.<appId>.*` prefix keeps working too. Lookups fall back to the global
 * bundles, so shared `common.*` / `webtop.*` keys and not-yet-migrated keys
 * resolve as before.
 *
 * Supports initial loading, real-time updates via node watch subscriptions
 * (shallow on /etc/i18n, deep on the apps tree filtered to i18n folders),
 * broadcasting to all app iframes, and message formatting via
 * intl-messageformat.
 */

import { IntlMessageFormat } from 'intl-messageformat';
import type { ContentServiceGraphQL } from './content-service-graphql.js';
import type { EventHub } from '../realtime/event-hub.js';
import { withContentVersion } from '../utils/content-version.js';
import { UrlUtils } from '../utils/url.js';

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

/** JCR folder holding the global (cross-app) bundles. */
const GLOBAL_BUNDLES_PATH = '/etc/i18n';

/** Name of the per-app bundle folder inside an app's folder. */
const APP_I18N_FOLDER = 'i18n';

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

/** A bundle file discovered in the repository, ready to fetch. */
interface BundleFile {
	name: string;
	locale: string;
	downloadUrl: string;
	path?: string;
	modified?: string;
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
	/** Global bundles: locale -> merged messages (from /etc/i18n). */
	#bundles = new Map<string, Record<string, string>>();
	/** App-scoped bundles: appId -> locale -> messages (from <app>/i18n/). */
	#appBundles = new Map<string, Map<string, Record<string, string>>>();
	#loaded = false;
	#contentService: ContentServiceGraphQL;
	#eventHub: EventHub | null;
	#unwatchNode: (() => void) | null = null;
	#unwatchApps: (() => void) | null = null;
	#refreshDebounceTimer: number | null = null;
	// Cache of compiled IntlMessageFormat instances keyed by
	// `g:${locale}:${messageId}` (global) / `a:${appId}:${locale}:${messageId}`
	// (app scope) so the same id may compile differently per scope.
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

	/**
	 * List of locale identifiers for which any bundle (global or app) is
	 * loaded. Backs the Preferences language list.
	 */
	get availableLocales(): string[] {
		const locales = new Set<string>(this.#bundles.keys());
		for (const perLocale of this.#appBundles.values()) {
			for (const locale of perLocale.keys()) locales.add(locale);
		}
		return [...locales].sort();
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
	 * Clean up subscriptions.
	 */
	destroy(): void {
		if (this.#unwatchNode) {
			this.#unwatchNode();
			this.#unwatchNode = null;
		}
		if (this.#unwatchApps) {
			this.#unwatchApps();
			this.#unwatchApps = null;
		}
		if (this.#refreshDebounceTimer) {
			clearTimeout(this.#refreshDebounceTimer);
			this.#refreshDebounceTimer = null;
		}
	}

	/**
	 * Format a message with the specified locale (or current locale if
	 * omitted).
	 *
	 * When `appId` is given, that app's own bundle is consulted first through
	 * the whole locale chain (exact → language → 'en'), then the global
	 * bundles through the same chain — so an app's keys are closed to the app
	 * while shared keys still resolve. Finally: fallbackMessage → messageId.
	 */
	format(
		messageId: string,
		params?: Record<string, any>,
		fallbackMessage?: string,
		locale?: string,
		appId?: string,
	): string {
		const targetLocale = (locale || this.currentLocale).toLowerCase();
		const candidates = this.#buildLocaleCandidates(targetLocale);

		// 1. App scope (closed to the app; wins over global for its own keys).
		if (appId) {
			const perLocale = this.#appBundles.get(appId);
			if (perLocale) {
				for (const loc of candidates) {
					const template = perLocale.get(loc)?.[messageId];
					if (template == null) continue;
					const result = this.#formatTemplate(template, loc, `a:${appId}:${loc}:${messageId}`, params);
					if (result != null) return result;
				}
			}
		}

		// 2. Global bundles.
		for (const loc of candidates) {
			const template = this.#bundles.get(loc)?.[messageId];
			if (template == null) continue;
			const result = this.#formatTemplate(template, loc, `g:${loc}:${messageId}`, params);
			if (result != null) return result;
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
	 * Compile (with caching) and format one template. Returns null when the
	 * template is malformed so the caller falls through to the next candidate.
	 */
	#formatTemplate(
		template: string,
		locale: string,
		cacheKey: string,
		params?: Record<string, any>,
	): string | null {
		try {
			let formatter = this.#formatterCache.get(cacheKey);
			if (!formatter) {
				formatter = new IntlMessageFormat(template, locale, undefined, MESSAGE_FORMAT_OPTS);
				this.#formatterCache.set(cacheKey, formatter);
			}
			const formatted = formatter.format(params || {});
			return Array.isArray(formatted) ? formatted.join('') : String(formatted);
		} catch {
			return null;
		}
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
	 * namespace rather than the entire bundle. When `appId` is given, that
	 * app's scoped bundle is overlaid on top of the global result (the same
	 * precedence {@link format} applies).
	 *
	 * This exists for sandboxed consumers that cannot reach this service
	 * directly — notably BPMN form iframes, which run in an opaque origin
	 * (`sandbox` without `allow-same-origin`) and therefore have no
	 * `window.parent.Webtop` access. The Tasks host forwards the result over
	 * its postMessage RPC bridge, letting a form translate its own labels (and
	 * compile ICU MessageFormat templates locally) against the very same
	 * bundles every shell app uses.
	 */
	getMessages(prefix?: string, locale?: string, appId?: string): Record<string, string> {
		const targetLocale = (locale || this.currentLocale).toLowerCase();
		const candidates = this.#buildLocaleCandidates(targetLocale);
		// Overlay from least- to most-specific (en → language → exact) so a key
		// defined by the exact locale overrides the same key in its fallbacks;
		// the app scope is overlaid after the global bundles so it wins for any
		// key it defines.
		const merged: Record<string, string> = {};
		const overlay = (bundle: Record<string, string> | undefined) => {
			if (!bundle) return;
			for (const [key, template] of Object.entries(bundle)) {
				if (prefix && !key.startsWith(prefix)) continue;
				merged[key] = template;
			}
		};
		for (const loc of [...candidates].reverse()) {
			overlay(this.#bundles.get(loc));
		}
		if (appId) {
			const perLocale = this.#appBundles.get(appId);
			if (perLocale) {
				for (const loc of [...candidates].reverse()) {
					overlay(perLocale.get(loc));
				}
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
	 * Load all bundles: the global /etc/i18n files and every app's i18n
	 * folder, in parallel. State is swapped in atomically at the end so a
	 * concurrent format() never sees a half-loaded world.
	 */
	async #loadAll(): Promise<void> {
		const [globalBundles, appBundles] = await Promise.all([
			this.#loadGlobalBundles(),
			this.#loadAppBundles(),
		]);
		this.#bundles = globalBundles;
		this.#appBundles = appBundles;
		this.#formatterCache.clear();
		this.#loaded = true;
	}

	/**
	 * Load the global bundle files from /etc/i18n/.
	 */
	async #loadGlobalBundles(): Promise<Map<string, Record<string, string>>> {
		const newBundles = new Map<string, Record<string, string>>();
		try {
			const parentNode = await this.#contentService.getNode(GLOBAL_BUNDLES_PATH);
			if (!parentNode) return newBundles;

			// Collect every bundle file first, then load them in a stable order
			// (sorted by file name) so that, when two files target the same locale,
			// the merge result is deterministic across boots. Modules keep their
			// keys in disjoint namespaces, so in practice the merge never has to
			// resolve a real conflict; sorting just removes any boot-order luck.
			const files: BundleFile[] = [];
			for await (const batch of this.#contentService.listAllChildren(GLOBAL_BUNDLES_PATH, 50)) {
				for (const node of batch) {
					const file = this.#toBundleFile(node);
					if (file) files.push(file);
				}
			}

			files.sort((a, b) => a.name.localeCompare(b.name));

			for (const file of files) {
				const data = await this.#fetchBundle(file);
				if (!data) continue;
				// Merge into (rather than replace) the locale's bundle so
				// multiple module files for one locale all contribute.
				const merged = newBundles.get(file.locale) || {};
				Object.assign(merged, data);
				newBundles.set(file.locale, merged);
			}
		} catch {
			// Leave whatever loaded so far; callers fall back per key.
		}
		return newBundles;
	}

	/**
	 * Load every app's scoped bundles from
	 * `<appsPath>/<appId>/i18n/<locale>.json`. Apps without an i18n folder are
	 * simply skipped; one app failing to load never affects the others.
	 */
	async #loadAppBundles(): Promise<Map<string, Map<string, Record<string, string>>>> {
		const appBundles = new Map<string, Map<string, Record<string, string>>>();
		const appsPath = UrlUtils.getAppsPath();
		try {
			const appFolders: string[] = [];
			for await (const batch of this.#contentService.listAllChildren(appsPath, 50)) {
				for (const node of batch) {
					// App folders have no downloadUrl (files do).
					if (!node.name || node.downloadUrl) continue;
					appFolders.push(node.name);
				}
			}

			await Promise.all(appFolders.map(async (appId) => {
				const perLocale = await this.#loadOneAppBundles(`${appsPath}/${appId}/${APP_I18N_FOLDER}`);
				if (perLocale && perLocale.size > 0) {
					appBundles.set(appId, perLocale);
				}
			}));
		} catch {
			// Apps tree unavailable — app-scoped lookups just fall back to global.
		}
		return appBundles;
	}

	/**
	 * Load one app's i18n folder into a locale -> messages map. Returns null
	 * when the folder does not exist.
	 */
	async #loadOneAppBundles(i18nPath: string): Promise<Map<string, Record<string, string>> | null> {
		try {
			const folder = await this.#contentService.getNode(i18nPath);
			if (!folder) return null;

			const files: BundleFile[] = [];
			for await (const batch of this.#contentService.listAllChildren(i18nPath, 50)) {
				for (const node of batch) {
					const file = this.#toBundleFile(node);
					if (file) files.push(file);
				}
			}
			files.sort((a, b) => a.name.localeCompare(b.name));

			const perLocale = new Map<string, Record<string, string>>();
			for (const file of files) {
				const data = await this.#fetchBundle(file);
				if (!data) continue;
				const merged = perLocale.get(file.locale) || {};
				Object.assign(merged, data);
				perLocale.set(file.locale, merged);
			}
			return perLocale;
		} catch {
			return null;
		}
	}

	/**
	 * Interpret a JCR node as a bundle file. The locale is the last
	 * dot-delimited segment of the file name (before .json), so a module
	 * prefix is ignored:
	 *   ja.json          → "ja"
	 *   commerce.ja.json → "ja"
	 *   en-US.json       → "en-us"
	 */
	#toBundleFile(node: { name?: string; downloadUrl?: string; path?: string; modified?: string }): BundleFile | null {
		if (!node.name?.endsWith('.json')) return null;
		if (!node.downloadUrl) return null;
		const base = node.name.replace(/\.json$/, '');
		const locale = base.substring(base.lastIndexOf('.') + 1).toLowerCase();
		if (!locale) return null;
		return { name: node.name, locale, downloadUrl: node.downloadUrl, path: node.path, modified: node.modified };
	}

	/**
	 * Fetch and parse one bundle file. Returns null on any failure (logged),
	 * so a broken file never takes the rest of the bundles down.
	 */
	async #fetchBundle(file: BundleFile): Promise<Record<string, string> | null> {
		try {
			// Version-stamp the URL with the bundle's last-modified time so an
			// unchanged file is served from the browser's immutable cache on the
			// next boot instead of a revalidation round-trip (see withContentVersion).
			const response = await fetch(withContentVersion(file.downloadUrl, file.modified));
			if (!response.ok) return null;
			const text = await response.text();
			const data = JSON.parse(text) as Record<string, string>;
			return data && typeof data === 'object' ? data : null;
		} catch {
			console.warn(`[I18nService] Failed to parse: ${file.path ?? file.name}`);
			return null;
		}
	}

	/**
	 * Start watching the bundle locations for changes via EventHub:
	 * shallow on /etc/i18n (as before) and deep on the apps tree, filtered to
	 * events under an app's i18n folder so app deployments don't trigger
	 * needless reloads.
	 */
	#startWatch(): void {
		if (!this.#eventHub) return;

		this.#unwatchNode = this.#eventHub.watchNode(
			GLOBAL_BUNDLES_PATH,
			() => this.#scheduleRefresh(),
			false, // shallow - direct children only
		);

		const appsPath = UrlUtils.getAppsPath();
		const i18nPathPattern = new RegExp(`^${appsPath}/[^/]+/${APP_I18N_FOLDER}(/|$)`);
		this.#unwatchApps = this.#eventHub.watchNode(
			appsPath,
			(event: { path?: string }) => {
				if (!event?.path || !i18nPathPattern.test(event.path)) return;
				this.#scheduleRefresh();
			},
			true, // deep - i18n files live two levels down
		);
	}

	/**
	 * Debounced full reload + broadcast, shared by both watches.
	 */
	#scheduleRefresh(): void {
		if (this.#refreshDebounceTimer) {
			clearTimeout(this.#refreshDebounceTimer);
		}
		this.#refreshDebounceTimer = window.setTimeout(async () => {
			this.#refreshDebounceTimer = null;
			await this.#loadAll();
			this.#broadcastUpdate();
		}, 1000);
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
