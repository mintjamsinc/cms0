/**
 * Localization Composable
 *
 * Tiny helper for apps that want to render dates / times / numbers in the
 * user's Preferences > Localization zone and locale.
 *
 * Each app holds a small reactive snapshot in its `data()` (via
 * {@link createLocalizationSnapshot}) and:
 *   - calls {@link refreshLocalization} once in `appLaunch` (after `instance`
 *     is assigned) to seed it from the shell's `LocalizationManager`, and
 *   - delegates the `localization-changed` postMessage to
 *     {@link handleLocalizationMessage} in its message listener.
 *
 * The shell already broadcasts `localization-changed` from
 * `preferences/app.ts` (user edit) and `webtop-api.ts` (remote sync), so
 * everything date-shaped that reads the reactive snapshot — `{{ formatDate(x) }}`
 * bindings, computeds that read `this.localization.timeZone`, etc. — repaints
 * the moment the preference changes.
 *
 * The snapshot exposes **effective** values (preference → browser fallback)
 * so callers can pass them straight to `Intl` / `Dates.format` / the
 * `datetime-local` conversion helpers without re-resolving the fallback.
 *
 * Crossing component boundaries: pass the snapshot as a single prop
 * (e.g. `:localization="localization"`). ichigojs ≥ 0.1.68 subscribes
 * to the received reactive proxy in `VBindings#set`, so a nested
 * mutation in the parent (`vm.localization.timeZone = X`) propagates
 * to the child's bindings even though the prop reference is unchanged.
 */

export interface LocalizationSnapshot {
	/** Effective locale (e.g. 'ja-JP'). Empty string until {@link refreshLocalization}. */
	locale: string;
	/** Effective IANA time zone (e.g. 'Asia/Tokyo'). Empty string until {@link refreshLocalization}. */
	timeZone: string;
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
	return { locale: '', timeZone: '' };
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
}

/**
 * Message-listener helper. Returns `true` when the event was a
 * `localization-changed` broadcast (and the snapshot has been refreshed),
 * so callers can early-return:
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
	if (type !== 'localization-changed') return false;
	refreshLocalization(snapshot, instance);
	return true;
}
