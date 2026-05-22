import type { WebtopAPI } from './services/webtop-api.js';
import type { User } from './services/user-service.js';
import { Application, ApplicationInstance } from './services/webtop-service.js';
import { WebtopUtil } from './services/webtop-util.js';
import type { MetadataDefinitionCache } from './services/metadata-cache.js';
import type { I18nService } from './services/webtop-i18n-service.js';

export interface WebtopContext {
	readonly api: WebtopAPI;
	readonly util: WebtopUtil;
	readonly resourcePaths: Record<string, string>;
	readonly rootPath: string;
	currentUser: User;
	apps: Application[];
	readonly sortedApps: Application[];
	readonly metadataDefinitions: MetadataDefinitionCache | null;
	readonly i18n: I18nService | null;
	getFullPath(relPath: string): string;
	launch(): void;
	initMetadataDefinitions(): Promise<void>;
	initI18n(): Promise<void>;
}

declare global {
	interface Window {
		Webtop: WebtopContext;
		appLaunch?: (instance: ApplicationInstance, options?: { path?: string; mimeType?: string }) => void;
	}
}

export { };

