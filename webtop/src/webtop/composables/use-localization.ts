/**
 * Localization Composable
 *
 * The single bridge between the shell's Localization preference + i18n message
 * bundles and an app's reactive UI. An app keeps one small reactive snapshot in
 * its `data()` (via {@link createLocalizationSnapshot}) and routes every
 * locale-aware read through the helpers here, so the whole app repaints the
 * instant the user changes their Preferences > Localization or an i18n bundle
 * is hot-reloaded.
 *
 * What the snapshot carries (all **effective** values — preference → fallback —
 * so callers never re-resolve the fallback themselves):
 *   - `locale`       e.g. 'ja-JP'      — display language
 *   - `timeZone`     e.g. 'Asia/Tokyo' — IANA time zone for date/time
 *   - `numberFormat` e.g. 'de-DE'      — locale used to group/format numbers
 *   - `currency`     e.g. 'JPY'        — ISO 4217 default currency
 *   - `revision`     internal counter, bumped on bundle hot-reload
 *
 * Wiring (do all three in every app):
 *   1. `data()` → `localization: createLocalizationSnapshot()`
 *   2. in `appLaunch` (after `instance` is assigned) →
 *      `refreshLocalization(this.localization, this.instance)`
 *   3. in the `message` listener →
 *      `if (handleLocalizationMessage(type, vm.localization, vm.instance)) return;`
 *
 * Then expose thin wrappers as component methods and use them in templates:
 *
 * ```ts
 * methods: {
 *   t(id, params, fallback) { return translate(this.localization, this.instance, id, params, fallback); },
 *   formatNumber(v, o)      { return formatNumber(this.localization, v, o); },
 *   formatCurrency(v, o)    { return formatCurrency(this.localization, v, o); },
 *   formatDate(v, o)        { return formatDate(this.localization, v, o); },
 * }
 * ```
 * ```html
 * <h2>{{ t('app.preferences.localization.title') }}</h2>
 * <span>{{ formatCurrency(order.total) }}</span>
 * <span>{{ formatDate(node.lastModified) }}</span>
 * ```
 *
 * Why this repaints reactively: ichigojs re-evaluates a binding when any
 * reactive value the binding *read* during its last evaluation mutates. Each
 * helper deliberately reads the snapshot field(s) it depends on, so changing
 * `locale` (or bumping `revision` on bundle reload) re-runs every `t()` /
 * `format*()` binding. The shell broadcasts `localization-changed` from
 * `preferences/app.ts` (user edit) and `webtop-api.ts` (remote sync), and the
 * i18n service broadcasts `i18n-bundles-updated` when `/etc/i18n/*.json`
 * changes; {@link handleLocalizationMessage} folds both into the snapshot.
 *
 * Crossing component boundaries: pass the snapshot as a single prop
 * (e.g. `:localization="localization"`). ichigojs ≥ 0.1.68 subscribes to the
 * received reactive proxy in `VBindings#set`, so a nested mutation in the
 * parent (`vm.localization.timeZone = X`) propagates to the child's bindings
 * even though the prop reference is unchanged.
 */

import { Dates } from '../utils/dates.js';

export interface LocalizationSnapshot {
	/** Effective locale (e.g. 'ja-JP'). Empty string until {@link refreshLocalization}. */
	locale: string;
	/** Effective IANA time zone (e.g. 'Asia/Tokyo'). Empty string until {@link refreshLocalization}. */
	timeZone: string;
	/** Effective number-format locale (e.g. 'de-DE'). Empty string until {@link refreshLocalization}. */
	numberFormat: string;
	/** Effective ISO 4217 currency code (e.g. 'JPY'). Empty string until {@link refreshLocalization}. */
	currency: string;
	/**
	 * Reactive revision counter, bumped whenever the i18n message bundles are
	 * hot-reloaded. Read by {@link translate} so message bindings repaint when
	 * a bundle changes without the locale itself changing. Not meant to be read
	 * directly by app code.
	 */
	revision: number;
}

/**
 * Create the initial (empty) snapshot to place inside `data()`.
 *
 * ```ts
 * data() {
 *   return {
 *     // ...
 *     localization: createLocalizationSnapshot(),
 *   };
 * }
 * ```
 */
export function createLocalizationSnapshot(): LocalizationSnapshot {
	return { locale: '', timeZone: '', numberFormat: '', currency: '', revision: 0 };
}

/**
 * Populate the snapshot from the shell `LocalizationManager`. Safe to call
 * before the manager has loaded — leaves the snapshot untouched if the
 * preference isn't available yet.
 */
export function refreshLocalization(
	snapshot: LocalizationSnapshot,
	instance: any,
): void {
	const loc = instance?.api?.localization;
	if (!loc) return;
	snapshot.locale = loc.effectiveLocale || '';
	snapshot.timeZone = loc.effectiveTimezone || '';
	snapshot.numberFormat = loc.effectiveNumberFormat || '';
	snapshot.currency = loc.effectiveCurrency || '';
}

/**
 * Message-listener helper. Folds the two shell broadcasts that affect
 * localization into the snapshot and returns `true` when the event was one of
 * them (so callers can early-return):
 *
 *   - `localization-changed`   → re-resolve the whole snapshot (locale, zone,
 *                                number format, currency) and bump `revision`.
 *   - `i18n-bundles-updated`   → bump `revision` so message bindings repaint.
 *
 * ```ts
 * if (handleLocalizationMessage(type, vm.localization, vm.instance)) return;
 * ```
 */
export function handleLocalizationMessage(
	type: string,
	snapshot: LocalizationSnapshot,
	instance: any,
): boolean {
	if (type === 'localization-changed') {
		refreshLocalization(snapshot, instance);
		snapshot.revision++;
		return true;
	}
	if (type === 'i18n-bundles-updated') {
		snapshot.revision++;
		return true;
	}
	return false;
}

/**
 * Resolve the shell's I18nService from whichever context we're in:
 * `instance.api.i18n` (apps — `instance.api` is the shell's API), then the
 * parent window (iframe apps), then the current window (shell itself).
 * Returns `null` until the service has been initialized.
 */
function resolveI18n(instance: any): any {
	const fromInstance = instance?.api?.i18n;
	if (fromInstance) return fromInstance;
	try {
		const fromParent = (window.parent as any)?.Webtop?.i18n;
		if (fromParent) return fromParent;
	} catch {
		// Cross-origin — ignore.
	}
	return (window as any).Webtop?.i18n ?? null;
}

/**
 * Reactively translate an i18n message id against the user's effective locale.
 *
 * Reads `snapshot.locale` and `snapshot.revision` so the calling binding
 * repaints when the language switches or the bundles hot-reload. Falls back —
 * inside `I18nService.format` — through exact locale → language only → 'en' →
 * `fallback` → the id itself, so a missing key degrades gracefully rather than
 * throwing.
 *
 * @param params  ICU MessageFormat arguments (e.g. `{ count: 3 }`).
 * @param fallback Literal shown when no bundle defines the id.
 */
export function translate(
	snapshot: LocalizationSnapshot,
	instance: any,
	messageId: string,
	params?: Record<string, any>,
	fallback?: string,
): string {
	// Establish the reactive dependencies (see file header): reading these
	// snapshot fields subscribes the calling binding, so it repaints when the
	// locale switches (`locale`) or the bundles hot-reload (`revision`).
	// `locale` is also passed through to format(); `revision` participates
	// purely as a dependency.
	const locale = snapshot.locale;
	const revision = snapshot.revision;
	void revision;

	const i18n = resolveI18n(instance);
	if (!i18n || typeof i18n.format !== 'function') {
		return fallback ?? messageId;
	}
	return i18n.format(messageId, params, fallback, locale || undefined);
}

/**
 * Reactively format a number in the user's effective number-format locale.
 * Reads `snapshot.numberFormat` / `snapshot.locale` so it repaints on change.
 */
export function formatNumber(
	snapshot: LocalizationSnapshot,
	value: number | bigint | string | null | undefined,
	options: Intl.NumberFormatOptions = {},
): string {
	if (value == null || value === '') return '';
	const num = typeof value === 'string' ? Number(value) : value;
	if (typeof num === 'number' && Number.isNaN(num)) return String(value);
	const locale = snapshot.numberFormat || snapshot.locale || undefined;
	try {
		return new Intl.NumberFormat(locale, options).format(num as number);
	} catch {
		return String(value);
	}
}

/**
 * Reactively format a monetary value in the user's effective currency and
 * number-format locale. Reads `snapshot.currency` / `snapshot.numberFormat` /
 * `snapshot.locale`. When no currency is resolved yet, degrades to a plain
 * number rather than throwing.
 */
export function formatCurrency(
	snapshot: LocalizationSnapshot,
	value: number | bigint | string | null | undefined,
	options: Intl.NumberFormatOptions = {},
): string {
	if (value == null || value === '') return '';
	const num = typeof value === 'string' ? Number(value) : value;
	if (typeof num === 'number' && Number.isNaN(num)) return String(value);
	const locale = snapshot.numberFormat || snapshot.locale || undefined;
	const currency = snapshot.currency || undefined;
	if (!currency) {
		return formatNumber(snapshot, value, options);
	}
	try {
		return new Intl.NumberFormat(locale, { style: 'currency', currency, ...options }).format(num as number);
	} catch {
		return String(value);
	}
}

/**
 * Reactively format a date/time in the user's effective locale and time zone.
 * Thin wrapper over {@link Dates.format} that injects the snapshot's locale and
 * time zone, so apps stop re-implementing this per-app. Reads `snapshot.locale`
 * / `snapshot.timeZone`.
 *
 * @param options `format` ('datetime' | 'date' | 'time' | 'friendly' | custom)
 *                plus any other {@link Dates.format} option.
 */
export function formatDate(
	snapshot: LocalizationSnapshot,
	value: Date | number | string | null | undefined,
	options: { format?: string } = {},
): string {
	return (
		Dates.format(value, {
			...options,
			locale: snapshot.locale || undefined,
			timeZone: snapshot.timeZone || undefined,
		}) ?? ''
	);
}
