import { WebtopAPI } from './webtop-api.js';

const APP_ID = 'webtop';
const THEME_KEY = 'theme.mode';

export class ThemeManager {
	#api: WebtopAPI;
	#mode: string;
	#userID: string;

	constructor(api: WebtopAPI) {
		this.#api = api;
		this.#userID = this.#getUserID;
	}

	get currentTheme(): string {
		return document.documentElement.dataset.theme;
	}

	get #getUserID(): string {
		return this.#api.context?.currentUser?.id || '*';
	}

	async getThemeSetting(): Promise<string> {
		const value = await this.#api.db.getUserSetting(this.#userID, APP_ID, THEME_KEY);
		return value || 'auto';
	}

	async setThemeSetting(mode: string): Promise<void> {
		mode = mode || 'auto';
		await this.#api.db.setUserSetting(this.#userID, APP_ID, THEME_KEY, mode);
		this.#mode = mode;
	}

	async applyTheme(): Promise<void> {
		let newTheme;
		if (this.#mode === 'auto') {
			const hour = new Date().getHours();
			newTheme = (hour >= 19 || hour < 6) ? 'dark' : 'light';
		} else {
			newTheme = this.#mode;
		}

		if (this.currentTheme != newTheme) {
			document.documentElement.dataset.theme = newTheme;
			document.dispatchEvent(new CustomEvent('theme-changed', { detail: { theme: newTheme } }));
			this.#api.webtop.postMessage({ type: 'theme-changed', theme: newTheme });
		}
	}

	async init() {
		this.#userID = this.#getUserID;
		this.#mode = await this.getThemeSetting();
		this.applyTheme();
	}
}
