import { WebtopDatabase } from './webtop-database.js';
import { ThemeManager } from './theme-manager.js';
import { LocalizationManager } from './localization-manager.js';
import { WallpaperManager } from './wallpaper-manager.js';
import { SessionManager } from './session-manager.js';
import { ResourceManager, ResourceResolver } from './resource-manager.js';
import { WebtopServiceGraphQL } from './webtop-service-graphql.js';

// GraphQL-based services
import { GraphQLClient, createGraphQLClient } from '../graphql/client.js';
import { ContentServiceGraphQL } from './content-service-graphql.js';
import { BpmServiceGraphQL } from './bpm-service-graphql.js';
import { EipServiceGraphQL } from './eip-service-graphql.js';
import { EventHub, createEventHub } from '../realtime/event-hub.js';
import { IdpServiceGraphQL } from './idp-service-graphql.js';
import { User } from './user-service.js';
import type { PreferenceChangeEvent, WallpaperChangeEvent, AvatarChangeEvent } from '../graphql/types.js';

// Utilities
import { UrlUtils } from '../utils/url.js';

export class WebtopAPI {
	#context;
	#workspace: string;

	#db: WebtopDatabase;
	#theme: ThemeManager;
	#localization: LocalizationManager;
	#wallpaper: WallpaperManager;
	#session: SessionManager;
	#resourceResolver: ResourceResolver;
	#resource: ResourceManager;

	// GraphQL-based services
	#graphqlClient: GraphQLClient;
	#contentGraphQL: ContentServiceGraphQL;
	#bpmGraphQL: BpmServiceGraphQL;
	#eipGraphQL: EipServiceGraphQL;
	#webtopGraphQL: WebtopServiceGraphQL;
	#idpGraphQL: IdpServiceGraphQL;
	#eventHub: EventHub;

	// System workspace services (for user home data: preferences, avatars, etc.)
	#systemGraphQLClient: GraphQLClient;
	#systemContentGraphQL: ContentServiceGraphQL;
	#systemEventHub: EventHub;
	#preferenceUnsubscribers: Array<() => void> = [];

	constructor(context, workspace?: string) {
		this.#context = context;
		// Auto-detect workspace from URL if not specified
		this.#workspace = workspace ?? UrlUtils.getWorkspace();

		this.#db = new WebtopDatabase();
		this.#theme = new ThemeManager(this);
		this.#localization = new LocalizationManager(this);
		this.#wallpaper = new WallpaperManager(this);
		this.#session = new SessionManager(this);
		this.#resourceResolver = new ResourceResolver(context);
		this.#resource = new ResourceManager(this);
		// GraphQL services
		this.#graphqlClient = createGraphQLClient(this.#workspace, {
			onError: (error) => {
				console.error('[GraphQL Error]', error.message, error.code);
			},
		});
		this.#contentGraphQL = new ContentServiceGraphQL(this.#graphqlClient);
		this.#bpmGraphQL = new BpmServiceGraphQL(this.#graphqlClient);
		this.#eipGraphQL = new EipServiceGraphQL(this.#graphqlClient);
		this.#webtopGraphQL = new WebtopServiceGraphQL(this.#graphqlClient, { rootPath: this.#context.rootPath });
		this.#idpGraphQL = new IdpServiceGraphQL();
		this.#eventHub = createEventHub(this.#workspace);
		this.#systemGraphQLClient = createGraphQLClient('system');
		this.#systemContentGraphQL = new ContentServiceGraphQL(this.#systemGraphQLClient);
		this.#systemEventHub = createEventHub('system');

		console.log('[WebtopAPI] Initialized with workspace:', this.#workspace);
	}

	// Context
	get context() { return this.#context; }
	get workspace() { return this.#workspace; }

	get db() { return this.#db; }
	get theme() { return this.#theme; }
	get localization() { return this.#localization; }
	/**
	 * The shell's i18n message-bundle service. Owned by the Webtop context and
	 * surfaced here so apps reach it the same way as every other service
	 * (`instance.api.i18n`). `null` until `Webtop.initI18n()` has completed.
	 */
	get i18n() { return this.#context.i18n; }
	get wallpaper() { return this.#wallpaper; }
	get session() { return this.#session; }
	get resourceResolver() { return this.#resourceResolver; }
	get resource() { return this.#resource; }
	// GraphQL services (new API)
	get graphql() { return this.#graphqlClient; }
	get content() { return this.#contentGraphQL; }
	get processes() { return this.#bpmGraphQL; }
	get routes() { return this.#eipGraphQL; }
	get webtop() { return this.#webtopGraphQL; }
	get idp() { return this.#idpGraphQL; }
	get eventHub() { return this.#eventHub; }
	/** Content service for the system workspace (user home, preferences, etc.) */
	get systemContent() { return this.#systemContentGraphQL; }

	async initialize() {
		await this.#db.open();
		this.#context.currentUser = await this.#loadCurrentUser();

		// 匿名ユーザーはこの後ログイン画面へリダイレクトされるため、
		// 認証必須リソースの読み込みはスキップする
		if (this.#context.currentUser.isAnonymous) {
			return;
		}

		await this.#theme.init();
		await this.#localization.init();
		await this.#wallpaper.init();

		// コンテンツを取得する
		for (const id in this.#context.resourcePaths) {
			this.#resource.getResource(id, false);
		}

		// Initialize real-time event hub
		const userId = this.#context.currentUser.id;
		const groups = this.#context.currentUser.groups.map(g => g.groupId);
		this.#eventHub.initialize(userId, groups);

		// Set admin mode for WebtopServiceGraphQL
		this.#webtopGraphQL.setAdminMode(this.#context.currentUser.isAdmin);

		// Subscribe to preference / wallpaper / avatar changes for cross-browser/tab sync
		this.#setupPreferencesSubscription(userId);
		this.#setupWallpaperSubscription(userId);
		this.#setupAvatarSubscription(userId);
	}

	/**
	 * Fetch the current user via the GraphQL `me` query. GraphQL rejects
	 * unauthenticated callers with an error, which we translate into an
	 * anonymous User so the shell can redirect to the login flow.
	 */
	async #loadCurrentUser(): Promise<User> {
		try {
			const me = await this.#idpGraphQL.getMe();
			return new User(me);
		} catch {
			return new User(null);
		}
	}

	async logout() {
		await fetch('/idp/api/logout', { method: 'POST', credentials: 'include' });
		this.#context.currentUser = new User(null);
		this.#preferenceUnsubscribers.forEach(f => f());
		this.#preferenceUnsubscribers = [];
		this.#systemEventHub?.dispose();
		this.#eventHub?.dispose();
	}

	// =========================================================================
	// Preferences sync (cross-browser/tab via GraphQL subscription)
	// =========================================================================

	/**
	 * Subscribe to the dedicated preferenceChanged subscription.
	 * The server reads the JCR node properties and delivers them directly in the
	 * SSE payload — no follow-up query required on the client (steps 6–9).
	 */
	#setupPreferencesSubscription(userId: string): void {
		const unsub = this.#systemEventHub.watchPreferences(
			userId,
			(event: PreferenceChangeEvent) => {
				this.#onPreferenceChanged(event).catch(e => {
					console.warn('[WebtopAPI] Preferences sync error:', e);
				});
			}
		);
		this.#preferenceUnsubscribers.push(unsub);
	}

	#setupAvatarSubscription(userId: string): void {
		const unsub = this.#systemEventHub.watchAvatar(
			userId,
			(event: AvatarChangeEvent) => {
				this.#onAvatarChanged(event).catch(e => {
					console.warn('[WebtopAPI] Avatar sync error:', e);
				});
			}
		);
		this.#preferenceUnsubscribers.push(unsub);
	}

	async #onAvatarChanged(_event: AvatarChangeEvent): Promise<void> {
		this.#webtopGraphQL.postMessage({ type: 'avatar-changed' });
	}

	#setupWallpaperSubscription(userId: string): void {
		const unsub = this.#systemEventHub.watchWallpapers(
			userId,
			(event: WallpaperChangeEvent) => {
				this.#onWallpaperChanged(event).catch(e => {
					console.warn('[WebtopAPI] Wallpaper sync error:', e);
				});
			}
		);
		this.#preferenceUnsubscribers.push(unsub);
	}

	async #onWallpaperChanged(event: WallpaperChangeEvent): Promise<void> {
		const { action, filename, userId } = event;

		if (action === 'deleted') {
			const key = `${userId}/webtop/${filename}`;
			const currentID = await this.#wallpaper.getCurrentWallpaperID();
			await this.#db.delete('wallpapers', key);
			if (currentID === filename) {
				await this.#wallpaper.setCurrentWallpaperID('default');
				await this.#wallpaper.applyWallpaper();
			}
		} else {
			// added or updated — ensure the local record reflects the server state
			await this.#wallpaper.ensureWallpaperRegistered(filename, userId);
		}

		this.#webtopGraphQL.postMessage({ type: 'wallpaper-catalog-changed' });
	}

	/**
	 * Called when a preferenceChanged event arrives from the server.
	 * The event already contains the updated property values, so no extra
	 * query is needed.
	 */
	async #onPreferenceChanged(event: PreferenceChangeEvent): Promise<void> {
		if (event.category === 'profile') {
			const displayName = event.data['displayName'] as string | undefined;
			this.#webtopGraphQL.postMessage({ type: 'profile-changed', displayName });
			return;
		}

		if (event.category === 'localization') {
			const patch = {
				locale: event.data['locale'] as string | undefined,
				timezone: event.data['timezone'] as string | undefined,
				numberFormat: event.data['numberFormat'] as string | undefined,
				currency: event.data['currency'] as string | undefined,
			};
			const changed = await this.#localization.applyRemote(patch);
			if (changed) {
				this.#webtopGraphQL.postMessage({
					type: 'localization-changed',
					settings: this.#localization.settings,
				});
				this.#webtopGraphQL.postMessage({ type: 'preferences-changed-remotely' });
			}
			return;
		}

		if (event.category === 'content-browser') {
			// Forward content-browser preferences to app instances via postMessage
			this.#webtopGraphQL.postMessage({
				type: 'content-browser-preferences-changed',
				data: event.data,
			});
			return;
		}

		if (event.category !== 'appearance') return;

		let changed = false;

		// Theme sync
		const serverTheme = event.data['theme'] as string | undefined;
		if (serverTheme) {
			const localTheme = await this.#theme.getThemeSetting();
			if (serverTheme !== localTheme) {
				await this.#theme.setThemeSetting(serverTheme);
				await this.#theme.applyTheme();
				changed = true;
			}
		}

		// Wallpaper sync
		const serverWallpaper = event.data['wallpaper'] as string | undefined;
		if (serverWallpaper) {
			const localWallpaper = await this.#wallpaper.getCurrentWallpaperID();
			if (serverWallpaper !== localWallpaper) {
				await this.#wallpaper.ensureWallpaperRegistered(serverWallpaper, event.userId);
				await this.#wallpaper.setCurrentWallpaperID(serverWallpaper);
				await this.#wallpaper.applyWallpaper();
				changed = true;
			}
		}

		if (changed) {
			this.#webtopGraphQL.postMessage({ type: 'preferences-changed-remotely' });
		}
	}

	/**
	 * Switch to a different workspace
	 */
	switchWorkspace(workspace: string): void {
		this.#workspace = workspace;
		this.#graphqlClient.setEndpoint(`/bin/graphql.cgi/${workspace}`);
		this.#eventHub.dispose();
		this.#eventHub = createEventHub(workspace);

		if (!this.#context.currentUser.isAnonymous) {
			const userId = this.#context.currentUser.id;
			const groups = this.#context.currentUser.groups.map(g => g.groupId);
			this.#eventHub.initialize(userId, groups);
		}
	}
}
