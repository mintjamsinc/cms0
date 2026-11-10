import type { WebtopAPI } from './services/webtop-api.js';
import type { User } from './services/user-service.js';
import { Application, ApplicationInstance, WidgetInstance } from './services/webtop-service.js';
import { WebtopUtil } from './services/webtop-util.js';
import type { MetadataDefinitionCache } from './services/metadata-cache.js';
import type { I18nService } from './services/webtop-i18n-service.js';
import type { UrlInfo } from './utils/url.js';

export interface WebtopContext {
	readonly api: WebtopAPI;
	readonly util: WebtopUtil;
	readonly resourcePaths: Record<string, string>;
	readonly urlInfo: UrlInfo;
	readonly rootPath: string;
	currentUser: User;
	apps: Application[];
	readonly sortedApps: Application[];
	readonly startMenuCategoryGroups: { id: string; apps: Application[] }[];
	readonly metadataDefinitions: MetadataDefinitionCache | null;
	readonly i18n: I18nService | null;
	getFullPath(relPath: string): string;
	launch(): void;
	initMetadataDefinitions(): Promise<void>;
	initI18n(): Promise<void>;
	/** Whether calls are waiting for the user to sign in again. */
	readonly reauthPending: boolean;
	/** Asks the user to sign in again; resolves true once the same user has. */
	reauthenticate(): Promise<boolean>;
	/** Compares who is signed in now with the user of this desktop. */
	checkSignIn(): Promise<void>;
}

declare global {
	interface Window {
		Webtop: WebtopContext;
		appLaunch?: (instance: ApplicationInstance, options?: { path?: string; mimeType?: string;[key: string]: any }) => void;
		widgetLaunch?: (widget: WidgetInstance) => void | Promise<void>;
	}
}

export { };

