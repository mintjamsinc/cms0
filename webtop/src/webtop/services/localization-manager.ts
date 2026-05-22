import { WebtopAPI } from './webtop-api.js';

const APP_ID = 'webtop';
const LOCALE_KEY = 'localization.locale';
const TIMEZONE_KEY = 'localization.timezone';
const NUMBER_FORMAT_KEY = 'localization.numberFormat';
const CURRENCY_KEY = 'localization.currency';

export interface LocalizationSettings {
	locale: string;       // '' = auto (browser)
	timezone: string;     // '' = auto (system)
	numberFormat: string; // '' = auto (locale default)
	currency: string;     // ISO 4217 code, e.g. 'JPY' / 'USD'. '' = auto
}

/**
 * Localization settings manager.
 *
 * Stores locale / timezone / number format / currency preferences both in
 * IndexedDB (fast local read) and in JCR `/home/users/{id}/preferences/`
 * (via `idp.updatePreferences`) for cross-device sync.
 *
 * An empty string means "use the system/browser default" for each setting.
 */
export class LocalizationManager {
	#api: WebtopAPI;
	#settings: LocalizationSettings = {
		locale: '',
		timezone: '',
		numberFormat: '',
		currency: '',
	};
	#loaded = false;

	constructor(api: WebtopAPI) {
		this.#api = api;
	}

	get loaded(): boolean { return this.#loaded; }
	get settings(): LocalizationSettings { return { ...this.#settings }; }

	/** Get the effective locale (preference → browser). */
	get effectiveLocale(): string {
		if (this.#settings.locale) return this.#settings.locale;
		return (navigator.language || 'en').toLowerCase();
	}

	/** Get the effective timezone (preference → browser). */
	get effectiveTimezone(): string {
		if (this.#settings.timezone) return this.#settings.timezone;
		try {
			return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
		} catch {
			return 'UTC';
		}
	}

	/** Get the effective number-format locale (preference → effective locale). */
	get effectiveNumberFormat(): string {
		return this.#settings.numberFormat || this.effectiveLocale;
	}

	/** Get the effective currency code (preference → locale default → 'USD'). */
	get effectiveCurrency(): string {
		if (this.#settings.currency) return this.#settings.currency;
		// Derive a sensible default from the effective locale
		const loc = this.effectiveLocale.toLowerCase();
		if (loc.startsWith('ja')) return 'JPY';
		if (loc.startsWith('en-gb')) return 'GBP';
		if (loc.startsWith('en')) return 'USD';
		if (loc.startsWith('de') || loc.startsWith('fr') || loc.startsWith('it') || loc.startsWith('es')) return 'EUR';
		return 'USD';
	}

	/** Load all localization settings from IndexedDB. */
	async init(): Promise<void> {
		const userID = this.#getUserID();
		const [locale, timezone, numberFormat, currency] = await Promise.all([
			this.#api.db.getUserSetting(userID, APP_ID, LOCALE_KEY),
			this.#api.db.getUserSetting(userID, APP_ID, TIMEZONE_KEY),
			this.#api.db.getUserSetting(userID, APP_ID, NUMBER_FORMAT_KEY),
			this.#api.db.getUserSetting(userID, APP_ID, CURRENCY_KEY),
		]);
		this.#settings = {
			locale: (locale as string) || '',
			timezone: (timezone as string) || '',
			numberFormat: (numberFormat as string) || '',
			currency: (currency as string) || '',
		};
		this.#loaded = true;
	}

	/** Update one or more settings locally (IndexedDB). */
	async updateLocal(patch: Partial<LocalizationSettings>): Promise<void> {
		const userID = this.#getUserID();
		if (patch.locale != null) {
			this.#settings.locale = patch.locale;
			await this.#api.db.setUserSetting(userID, APP_ID, LOCALE_KEY, patch.locale);
		}
		if (patch.timezone != null) {
			this.#settings.timezone = patch.timezone;
			await this.#api.db.setUserSetting(userID, APP_ID, TIMEZONE_KEY, patch.timezone);
		}
		if (patch.numberFormat != null) {
			this.#settings.numberFormat = patch.numberFormat;
			await this.#api.db.setUserSetting(userID, APP_ID, NUMBER_FORMAT_KEY, patch.numberFormat);
		}
		if (patch.currency != null) {
			this.#settings.currency = patch.currency;
			await this.#api.db.setUserSetting(userID, APP_ID, CURRENCY_KEY, patch.currency);
		}
	}

	/** Replace all settings from a remote payload (used by subscription handler). */
	async applyRemote(patch: Partial<LocalizationSettings>): Promise<boolean> {
		let changed = false;
		const userID = this.#getUserID();
		const apply = async (key: string, dbKey: string, value: string | undefined) => {
			if (value == null) return;
			if ((this.#settings as any)[key] !== value) {
				(this.#settings as any)[key] = value;
				await this.#api.db.setUserSetting(userID, APP_ID, dbKey, value);
				changed = true;
			}
		};
		await apply('locale', LOCALE_KEY, patch.locale);
		await apply('timezone', TIMEZONE_KEY, patch.timezone);
		await apply('numberFormat', NUMBER_FORMAT_KEY, patch.numberFormat);
		await apply('currency', CURRENCY_KEY, patch.currency);
		return changed;
	}

	#getUserID(): string {
		return this.#api.context?.currentUser?.id || '*';
	}
}
