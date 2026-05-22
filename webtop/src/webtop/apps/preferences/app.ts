/**
 * Preferences Application
 *
 * User personalization settings: appearance (theme, wallpaper),
 * localization, profile (display name, avatar), security (password),
 * and saved sessions.
 */

import { VDOM } from '@mintjamsinc/ichigojs';
import { ApplicationInstance } from "../../services/webtop-service.js";
import { IdpServiceGraphQL } from "../../services/idp-service-graphql.js";

const MAX_AVATAR_SIZE = 2 * 1024 * 1024; // 2MB
const ACCEPTED_IMAGE_TYPES = /^image\/(png|jpeg|jpg|gif|webp|bmp|svg\+xml)$/;
const AVATAR_ACCEPTED_TYPES = /^image\/(png|jpeg|jpg|gif)$/;

const App = {
	data() {
		return {
			instance: null as ApplicationInstance | null,
			idp: null as IdpServiceGraphQL | null,
			section: 'appearance',

			// Remote update notice
			remoteUpdateNotice: false,

			// Appearance - Theme
			theme: 'auto',

			// Localization
			locale: '',
			timezone: '',
			numberFormat: '',
			currency: '',
			availableLocales: [] as string[],
			availableTimezones: [] as string[],
			availableCurrencies: [
				{ code: 'JPY', label: 'Japanese Yen (¥)' },
				{ code: 'USD', label: 'US Dollar ($)' },
				{ code: 'EUR', label: 'Euro (€)' },
				{ code: 'GBP', label: 'British Pound (£)' },
				{ code: 'CNY', label: 'Chinese Yuan (¥)' },
				{ code: 'KRW', label: 'Korean Won (₩)' },
			] as { code: string; label: string }[],
			localizationMessage: '',
			localizationMessageType: 'success',

			// Appearance - Wallpaper
			wallpaperCatalog: [] as { id: string; url: string; timestamp: number }[],
			selectedWallpaperID: null as string | null,
			currentWallpaperURL: null as string | null,
			wallpaperDragOver: false,

			// Profile
			avatarURL: null as string | null,
			avatarDragOver: false,
			displayName: '',
			originalDisplayName: '',
			profileSaving: false,
			profileMessage: '',
			profileMessageType: 'success',

			// Security
			passwordForm: {
				current: '',
				newPassword: '',
				confirm: '',
			},
			passwordSaving: false,
			passwordMessage: '',
			passwordMessageType: 'success',

			// Sessions
			sessionList: [] as { id: string; displayName: string; savedAt: string }[],
			sessionsLoading: false,
			sessionMessage: '',
			sessionMessageType: 'success',

			// Confirmation dialog
			confirmDialog: {
				visible: false,
				title: '',
				message: '',
				confirmLabel: 'OK',
				iconClass: 'bi-question-circle',
				danger: false,
				onConfirm: null as (() => void) | null,
			},

			// Notification dialog
			notificationDialog: {
				visible: false,
				kind: 'info' as 'info' | 'success' | 'warning' | 'error',
				title: '',
				message: '',
			},
		};
	},
	computed: {
		displayNameChanged() {
			return this.displayName !== this.originalDisplayName;
		},
		canChangePassword() {
			return this.passwordForm.current
				&& this.passwordForm.newPassword
				&& this.passwordForm.confirm
				&& this.passwordForm.newPassword === this.passwordForm.confirm;
		},
	},
	methods: {
		async onMounted() {
			const vm = this;

			vm.messageListener = (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const { type, ...payload } = event.data || {};
				if (type === 'theme-changed') {
					document.documentElement.dataset.theme = payload.theme;
				} else if (type === 'preferences-changed-remotely') {
					if (vm.instance?.api) {
						vm.instance.api.theme.getThemeSetting().then((t: string) => {
							if (t) vm.theme = t;
						}).catch(() => {});
						vm.loadWallpaperCatalog().catch(e => console.warn('[Preferences] catalog reload failed:', e));
					}
				} else if (type === 'wallpaper-catalog-changed') {
					vm.loadWallpaperCatalog().catch(e => console.warn('[Preferences] catalog reload failed:', e));
				} else if (type === 'profile-changed') {
					vm.loadProfile().catch(e => console.warn('[Preferences] profile reload failed:', e));
				} else if (type === 'avatar-changed') {
					if (vm.instance) {
						vm.loadAvatarURL().catch(e => console.warn('[Preferences] avatar reload failed:', e));
					}
				}
			};
			window.addEventListener('message', vm.messageListener);

			window.appLaunch = async (instance: ApplicationInstance) => {
				vm.instance = this.$markRaw(instance);
				vm.idp = this.$markRaw(new IdpServiceGraphQL());

				const theme = vm.instance.api.theme.currentTheme || 'light';
				document.documentElement.dataset.theme = theme;

				await vm.loadSettings();

				this.$nextTick(() => {
					instance.notifyLaunched();
				});
			};
		},
		onUnmount() {
			if (this.messageListener) {
				window.removeEventListener('message', this.messageListener);
			}
		},

		// =====================================================================
		// Section navigation
		// =====================================================================

		async selectSection(section: string) {
			this.section = section;
			if (section === 'sessions') {
				await this.loadSessions();
			}
		},

		// =====================================================================
		// Window controls
		// =====================================================================

		onMinimizeWindow() {
			this.instance?.minimize();
		},
		onToggleMaximizeWindow() {
			this.instance?.toggleMaximize();
		},
		onCloseWindow() {
			this.instance?.requestClose();
		},

		// =====================================================================
		// Drag & drop policy
		// =====================================================================

		// App root rejects drops by default so the browser does not navigate.
		onAppDragOver(event: DragEvent) {
			if (event.dataTransfer) event.dataTransfer.dropEffect = 'none';
		},
		// The main pane (everything except wallpaper / avatar drop areas) rejects drops.
		onForbiddenDragOver(event: DragEvent) {
			if (event.dataTransfer) event.dataTransfer.dropEffect = 'none';
		},

		// =====================================================================
		// Settings load
		// =====================================================================

		async loadSettings() {
			const vm = this;
			await Promise.all([
				vm.loadThemeSetting(),
				vm.loadWallpaperCatalog(),
				vm.loadProfile(),
				vm.loadLocalization(),
			]);
		},

		// =====================================================================
		// Localization
		// =====================================================================

		async loadLocalization() {
			const vm = this;
			const loc = vm.instance?.api?.localization;
			if (!loc) return;
			if (!loc.loaded) await loc.init();
			const s = loc.settings;
			vm.locale = s.locale || '';
			vm.timezone = s.timezone || '';
			vm.numberFormat = s.numberFormat || '';
			vm.currency = s.currency || '';

			try {
				const i18n = (window.parent as any)?.Webtop?.i18n;
				vm.availableLocales = i18n?.availableLocales || [];
			} catch {
				vm.availableLocales = [];
			}

			try {
				const tzs = (Intl as any).supportedValuesOf?.('timeZone');
				if (Array.isArray(tzs)) vm.availableTimezones = tzs;
			} catch {
				vm.availableTimezones = [];
			}
		},

		localeLabel(value: string): string {
			return value || 'Auto (browser language)';
		},
		timezoneLabel(value: string): string {
			return value || 'Auto (system time zone)';
		},
		numberFormatLabel(value: string): string {
			return value || 'Auto (use display language)';
		},
		currencyLabel(value: string): string {
			if (!value) return 'Auto (derived from language)';
			const found = this.availableCurrencies.find(c => c.code === value);
			return found ? found.label : value;
		},

		async openLocaleDropdown(event: MouseEvent) {
			const vm = this;
			const items = [
				{ id: '__auto__', label: 'Auto (browser language)', selected: !vm.locale },
				...vm.availableLocales.map(l => ({ id: l, label: l, selected: l === vm.locale })),
			];
			const result = await vm.openSelectPopup(event, items);
			if (result == null) return;
			vm.locale = result === '__auto__' ? '' : String(result);
			await vm.onLocalizationChange();
		},
		async openTimezoneDropdown(event: MouseEvent) {
			const vm = this;
			const items = [
				{ id: '__auto__', label: 'Auto (system time zone)', selected: !vm.timezone },
				...vm.availableTimezones.map(tz => ({ id: tz, label: tz, selected: tz === vm.timezone })),
			];
			const result = await vm.openSelectPopup(event, items);
			if (result == null) return;
			vm.timezone = result === '__auto__' ? '' : String(result);
			await vm.onLocalizationChange();
		},
		async openNumberFormatDropdown(event: MouseEvent) {
			const vm = this;
			const items = [
				{ id: '__auto__', label: 'Auto (use display language)', selected: !vm.numberFormat },
				...vm.availableLocales.map(l => ({ id: l, label: l, selected: l === vm.numberFormat })),
			];
			const result = await vm.openSelectPopup(event, items);
			if (result == null) return;
			vm.numberFormat = result === '__auto__' ? '' : String(result);
			await vm.onLocalizationChange();
		},
		async openCurrencyDropdown(event: MouseEvent) {
			const vm = this;
			const items = [
				{ id: '__auto__', label: 'Auto (derived from language)', selected: !vm.currency },
				...vm.availableCurrencies.map(c => ({ id: c.code, label: c.label, selected: c.code === vm.currency })),
			];
			const result = await vm.openSelectPopup(event, items);
			if (result == null) return;
			vm.currency = result === '__auto__' ? '' : String(result);
			await vm.onLocalizationChange();
		},

		async openSelectPopup(event: MouseEvent, items: any[]): Promise<string | number | null> {
			const vm = this;
			const trigger = (event.currentTarget as HTMLElement) || (event.target as HTMLElement);
			if (!trigger || !vm.instance) return null;
			const rect = trigger.getBoundingClientRect();
			const handle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				maxHeight: 360,
				items,
			});
			return await handle.result;
		},

		async onLocalizationChange() {
			const vm = this;
			const loc = vm.instance?.api?.localization;
			if (!loc) return;
			await loc.updateLocal({
				locale: vm.locale,
				timezone: vm.timezone,
				numberFormat: vm.numberFormat,
				currency: vm.currency,
			});
			try {
				const i18n = (window.parent as any)?.Webtop?.i18n;
				i18n?.invalidateFormatterCache?.();
			} catch {
				// Ignore
			}
			const username = vm.instance.currentUser?.id;
			if (username) {
				try {
					await vm.idp.updatePreferences({
						username,
						category: 'localization',
						data: {
							locale: vm.locale,
							timezone: vm.timezone,
							numberFormat: vm.numberFormat,
							currency: vm.currency,
						},
					});
					vm.localizationMessage = 'Saved';
					vm.localizationMessageType = 'success';
				} catch (e) {
					console.warn('[Preferences] Failed to save localization to server:', e);
					vm.localizationMessage = 'Failed to save to server';
					vm.localizationMessageType = 'error';
				}
			}
			vm.instance.api.webtopGraphQL.postMessage({
				type: 'localization-changed',
				settings: loc.settings,
			});
		},

		// =====================================================================
		// Theme
		// =====================================================================

		async loadThemeSetting() {
			const vm = this;
			vm.theme = await vm.instance.api.theme.getThemeSetting();
		},

		async onThemeChange() {
			const vm = this;
			await vm.instance.api.theme.setThemeSetting(vm.theme);
			await vm.instance.api.theme.applyTheme();
			const username = vm.instance.currentUser?.id;
			if (username) {
				try {
					await vm.idp.updatePreferences({
						username,
						category: 'appearance',
						data: { theme: vm.theme },
					});
				} catch (e) {
					console.warn('[Preferences] Failed to save appearance to server:', e);
				}
			}
			vm.instance.api.webtopGraphQL.postMessage({
				type: 'theme-changed',
				theme: vm.instance.api.theme.currentTheme,
			});
		},

		// =====================================================================
		// Wallpaper
		// =====================================================================

		generateWallpaperFilename(): string {
			const now = new Date();
			const pad = (n: number, len = 2) => String(n).padStart(len, '0');
			const ts =
				now.getFullYear().toString() +
				pad(now.getMonth() + 1) +
				pad(now.getDate()) +
				pad(now.getHours()) +
				pad(now.getMinutes()) +
				pad(now.getSeconds()) +
				pad(now.getMilliseconds(), 3);
			return `wallpaper_${ts}`;
		},

		async syncWallpapersFromServer(userID: string) {
			const vm = this;
			const systemContent = vm.instance.api.systemContent;
			const wallpaperPath = `/home/users/${userID}/wallpapers`;

			try {
				const connection = await systemContent.listChildren(wallpaperPath);
				if (!connection?.edges?.length) return;

				for (const edge of connection.edges) {
					const filename = edge.node?.name;
					if (!filename) continue;
					await vm.instance.api.wallpaper.ensureWallpaperRegistered(filename, userID);
				}
			} catch {
				// Directory may not exist yet
			}
		},

		async loadWallpaperCatalog() {
			const vm = this;
			const db = vm.instance.api.db;
			const userID = vm.instance.currentUser?.id || '*';

			if (userID !== '*') {
				await vm.syncWallpapersFromServer(userID);
			}

			const allWallpapers: any[] = await db.getAll('wallpapers');
			const catalog: { id: string; url: string; timestamp: number }[] = [];

			for (const wp of allWallpapers) {
				if (!wp.url) continue;
				const keyParts = wp.id.split('/');
				const wpUserID = keyParts[0];
				if (wpUserID === '*' || wpUserID === userID) {
					const wpName = keyParts.length >= 3 ? keyParts.slice(2).join('/') : wp.id;
					catalog.push({ id: wpName, url: wp.url, timestamp: wp.timestamp });
				}
			}

			vm.wallpaperCatalog = catalog;

			const currentID = await vm.instance.api.wallpaper.getCurrentWallpaperID();
			vm.selectedWallpaperID = currentID;

			const currentWP = catalog.find(w => w.id === currentID);
			vm.currentWallpaperURL = currentWP ? `${currentWP.url}?t=${currentWP.timestamp}` : null;
		},

		async selectWallpaper(id: string) {
			const vm = this;
			await vm.instance.api.wallpaper.setCurrentWallpaperID(id);
			await vm.instance.api.wallpaper.applyWallpaper();
			vm.selectedWallpaperID = id;

			const wp = vm.wallpaperCatalog.find((w: any) => w.id === id);
			vm.currentWallpaperURL = wp ? `${wp.url}?t=${wp.timestamp}` : null;

			const username = vm.instance.currentUser?.id;
			if (username) {
				try {
					await vm.idp.updatePreferences({
						username,
						category: 'appearance',
						data: { wallpaper: id },
					});
				} catch (e) {
					console.warn('[Preferences] Failed to save wallpaper to server:', e);
				}
			}
		},

		async deleteWallpaper(id: string) {
			const vm = this;
			vm.openConfirmDialog({
				title: 'Delete Wallpaper',
				message: 'This wallpaper will be removed from your catalog. Continue?',
				confirmLabel: 'Delete',
				danger: true,
				iconClass: 'bi-exclamation-triangle-fill text-warning',
				onConfirm: async () => {
					await vm.instance.api.wallpaper.removeWallpaper(id);
					await vm.loadWallpaperCatalog();
				},
			});
		},

		triggerWallpaperUpload() {
			const input = document.querySelector('input[ref="wallpaperFileInput"]') as HTMLInputElement;
			if (input) input.click();
		},

		async onWallpaperFileSelected(event: Event) {
			const input = event.target as HTMLInputElement;
			const file = input.files?.[0];
			try {
				if (file) await this.uploadWallpaperFile(file);
			} finally {
				input.value = '';
			}
		},

		// ---- Wallpaper drag-and-drop ----
		onWallpaperDragEnter(event: DragEvent) {
			if (this.canAcceptImageDrop(event)) {
				this.wallpaperDragOver = true;
				if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy';
			}
		},
		onWallpaperDragOver(event: DragEvent) {
			if (this.canAcceptImageDrop(event)) {
				this.wallpaperDragOver = true;
				if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy';
			} else if (event.dataTransfer) {
				event.dataTransfer.dropEffect = 'none';
			}
		},
		onWallpaperDragLeave(event: DragEvent) {
			// Only clear when leaving the drop area itself (not inner elements).
			const related = event.relatedTarget as Node | null;
			const current = event.currentTarget as Node | null;
			if (!current || !related || !current.contains(related)) {
				this.wallpaperDragOver = false;
			}
		},
		async onWallpaperDrop(event: DragEvent) {
			this.wallpaperDragOver = false;
			const file = await this.extractDroppedImage(event);
			if (!file) return;
			await this.uploadWallpaperFile(file);
		},

		async uploadWallpaperFile(file: File) {
			const vm = this;
			if (!file.type.startsWith('image/')) {
				vm.openNotification({
					kind: 'error',
					title: 'Invalid File',
					message: 'Only image files can be used as wallpapers.',
				});
				return;
			}

			const username = vm.instance.currentUser?.id;
			if (!username) return;

			const filename = vm.generateWallpaperFilename();
			const wallpaperPath = `/home/users/${username}/wallpapers`;

			try {
				const systemContent = vm.instance.api.systemContent;
				const uploadInfo = await systemContent.initiateMultipartUpload();
				await vm.uploadFileInChunks(systemContent, uploadInfo, file);
				await systemContent.completeMultipartUpload(
					uploadInfo.uploadId, wallpaperPath, filename, file.type, true,
				);

				const node = await systemContent.getNode(`${wallpaperPath}/${filename}`);
				if (!node?.downloadUrl) return;

				const timestamp = node.modified ? new Date(node.modified).getTime() : Date.now();
				await vm.instance.api.wallpaper.addWallpaper({ id: filename, url: node.downloadUrl, timestamp });
				await vm.instance.api.wallpaper.setCurrentWallpaperID(filename);
				await vm.instance.api.wallpaper.applyWallpaper();

				try {
					await vm.idp.updatePreferences({
						username,
						category: 'appearance',
						data: { wallpaper: filename },
					});
				} catch (e) {
					console.warn('[Preferences] Failed to save wallpaper to server:', e);
				}

				await vm.loadWallpaperCatalog();
			} catch (e: any) {
				console.warn('[Preferences] wallpaper upload failed:', e);
				vm.openNotification({
					kind: 'error',
					title: 'Upload Failed',
					message: e?.message || 'Failed to upload wallpaper.',
				});
			}
		},

		// =====================================================================
		// Profile
		// =====================================================================

		async loadProfile() {
			const vm = this;
			try {
				const me = await vm.idp.getMe();
				if (me) {
					vm.displayName = me.displayName || '';
					vm.originalDisplayName = vm.displayName;
				}
			} catch (e) {
				console.warn('[Preferences] Failed to load profile:', e);
			}
			await vm.loadAvatarURL();
		},

		async loadAvatarURL() {
			const vm = this;
			const username = vm.instance?.currentUser?.id;
			if (!username) return;
			try {
				const node = await vm.instance.api.systemContent.getNode(`/home/users/${username}/avatar`);
				if (node?.downloadUrl) {
					const ts = node.modified ? new Date(node.modified).getTime() : Date.now();
					vm.avatarURL = `${node.downloadUrl}?t=${ts}`;
				}
			} catch {
				// No avatar uploaded yet
			}
		},

		async saveDisplayName() {
			const vm = this;
			vm.profileSaving = true;
			vm.profileMessage = '';
			try {
				const username = vm.instance.currentUser.id;
				const result = await vm.idp.updateUser({
					username,
					displayName: vm.displayName,
				});
				if (result.errors?.length) {
					vm.profileMessage = result.errors[0].message;
					vm.profileMessageType = 'error';
				} else {
					vm.originalDisplayName = vm.displayName;
					vm.profileMessage = 'Display name updated.';
					vm.profileMessageType = 'success';
					vm.instance.api.webtopGraphQL.postMessage({ type: 'profile-changed', displayName: vm.displayName });
					vm.idp.updatePreferences({
						username,
						category: 'profile',
						data: { displayName: vm.displayName },
					}).catch(e => console.warn('[Preferences] Failed to sync display name:', e));
				}
			} catch (e: any) {
				vm.profileMessage = e.message || 'Failed to save.';
				vm.profileMessageType = 'error';
			} finally {
				vm.profileSaving = false;
			}
		},

		triggerAvatarUpload() {
			const input = document.querySelector('input[ref="avatarFileInput"]') as HTMLInputElement;
			if (input) input.click();
		},

		async onAvatarFileSelected(event: Event) {
			const input = event.target as HTMLInputElement;
			const file = input.files?.[0];
			try {
				if (file) await this.uploadAvatarFile(file);
			} finally {
				input.value = '';
			}
		},

		// ---- Avatar drag-and-drop ----
		onAvatarDragEnter(event: DragEvent) {
			if (this.canAcceptImageDrop(event)) {
				this.avatarDragOver = true;
				if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy';
			}
		},
		onAvatarDragOver(event: DragEvent) {
			if (this.canAcceptImageDrop(event)) {
				this.avatarDragOver = true;
				if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy';
			} else if (event.dataTransfer) {
				event.dataTransfer.dropEffect = 'none';
			}
		},
		onAvatarDragLeave(event: DragEvent) {
			const related = event.relatedTarget as Node | null;
			const current = event.currentTarget as Node | null;
			if (!current || !related || !current.contains(related)) {
				this.avatarDragOver = false;
			}
		},
		async onAvatarDrop(event: DragEvent) {
			this.avatarDragOver = false;
			const file = await this.extractDroppedImage(event);
			if (!file) return;
			await this.uploadAvatarFile(file);
		},

		async uploadAvatarFile(file: File) {
			const vm = this;
			if (file.size > MAX_AVATAR_SIZE) {
				vm.profileMessage = 'File is too large. Maximum size is 2MB.';
				vm.profileMessageType = 'error';
				return;
			}
			if (!AVATAR_ACCEPTED_TYPES.test(file.type)) {
				vm.profileMessage = 'Only JPG, PNG, and GIF files are allowed.';
				vm.profileMessageType = 'error';
				return;
			}

			vm.profileSaving = true;
			vm.profileMessage = '';

			try {
				const systemContent = vm.instance.api.systemContent;
				const username = vm.instance.currentUser.id;

				const uploadInfo = await systemContent.initiateMultipartUpload();
				await vm.uploadFileInChunks(systemContent, uploadInfo, file);

				const avatarPath = `/home/users/${username}`;
				const avatarNode = await systemContent.completeMultipartUpload(
					uploadInfo.uploadId,
					avatarPath,
					'avatar',
					file.type,
					true,
				);

				if (avatarNode?.downloadUrl) {
					const ts = avatarNode.modified ? new Date(avatarNode.modified).getTime() : Date.now();
					vm.avatarURL = `${avatarNode.downloadUrl}?t=${ts}`;
				}

				vm.profileMessage = 'Profile photo updated.';
				vm.profileMessageType = 'success';

				vm.instance.api.webtopGraphQL.postMessage({ type: 'avatar-changed' });
			} catch (e: any) {
				vm.profileMessage = e.message || 'Failed to upload photo.';
				vm.profileMessageType = 'error';
			} finally {
				vm.profileSaving = false;
			}
		},

		// =====================================================================
		// Drag & drop helpers
		// =====================================================================

		canAcceptImageDrop(event: DragEvent): boolean {
			if (!event.dataTransfer) return false;
			const types = event.dataTransfer.types;
			return types.includes('Files') || types.includes('application/x-webtop-file');
		},

		/**
		 * Resolve a dropped image to a File. Local OS files arrive as DataTransfer.files;
		 * cross-iframe Content Browser drags arrive as a JSON descriptor on the
		 * `application/x-webtop-file` type and have to be downloaded first.
		 */
		async extractDroppedImage(event: DragEvent): Promise<File | null> {
			const dt = event.dataTransfer;
			if (!dt) return null;

			// Local files (OS)
			const localFile = dt.files?.[0];
			if (localFile && localFile.type.startsWith('image/')) {
				return localFile;
			}

			// Content Browser cross-iframe drag
			const webtopFileData = dt.getData('application/x-webtop-file');
			if (webtopFileData) {
				try {
					const fileInfo = JSON.parse(webtopFileData);
					if (fileInfo?.path) {
						return await this.downloadContentNodeAsFile(fileInfo.path);
					}
				} catch (e) {
					console.warn('[Preferences] Failed to parse content browser drag data:', e);
				}
			}

			return null;
		},

		async downloadContentNodeAsFile(path: string): Promise<File | null> {
			const vm = this;
			const systemContent = vm.instance.api.systemContent;
			const node = await systemContent.getNode(path);
			if (!node?.downloadUrl) return null;
			const mime = node.mimeType || node.contentType || '';
			if (!ACCEPTED_IMAGE_TYPES.test(mime) && !mime.startsWith('image/')) {
				vm.openNotification({
					kind: 'error',
					title: 'Invalid File',
					message: 'Only image files can be used here.',
				});
				return null;
			}
			const response = await fetch(node.downloadUrl, { credentials: 'include' });
			const blob = await response.blob();
			const filename = (path.split('/').pop() || 'image');
			return new File([blob], filename, { type: mime || blob.type });
		},

		// =====================================================================
		// Security - Password
		// =====================================================================

		async changePassword() {
			const vm = this;
			if (!vm.canChangePassword) return;

			if (vm.passwordForm.newPassword !== vm.passwordForm.confirm) {
				vm.passwordMessage = 'Passwords do not match.';
				vm.passwordMessageType = 'error';
				return;
			}

			vm.passwordSaving = true;
			vm.passwordMessage = '';

			try {
				const username = vm.instance.currentUser.id;
				const result = await vm.idp.changePassword({
					username,
					currentPassword: vm.passwordForm.current,
					newPassword: vm.passwordForm.newPassword,
				});

				if (result.errors?.length) {
					vm.passwordMessage = result.errors[0].message;
					vm.passwordMessageType = 'error';
				} else {
					vm.passwordForm.current = '';
					vm.passwordForm.newPassword = '';
					vm.passwordForm.confirm = '';
					vm.passwordMessage = 'Password changed successfully.';
					vm.passwordMessageType = 'success';
				}
			} catch (e: any) {
				vm.passwordMessage = e.message || 'Failed to change password.';
				vm.passwordMessageType = 'error';
			} finally {
				vm.passwordSaving = false;
			}
		},

		// =====================================================================
		// Sessions
		// =====================================================================

		async loadSessions() {
			const vm = this;
			if (!vm.instance) return;
			vm.sessionsLoading = true;
			vm.sessionMessage = '';
			try {
				vm.sessionList = await vm.instance.api.session.listSessions();
			} catch (e: any) {
				vm.sessionMessage = e.message || 'Failed to load sessions.';
				vm.sessionMessageType = 'error';
			} finally {
				vm.sessionsLoading = false;
			}
		},

		confirmDeleteSession(session: { id: string; displayName: string }) {
			const vm = this;
			vm.openConfirmDialog({
				title: 'Delete Session',
				message: `Delete saved session "${session.displayName}"?`,
				confirmLabel: 'Delete',
				danger: true,
				iconClass: 'bi-exclamation-triangle-fill text-warning',
				onConfirm: () => vm.deleteSession(session.id),
			});
		},

		async deleteSession(id: string) {
			const vm = this;
			try {
				await vm.instance.api.session.deleteSession(id);
				vm.sessionList = vm.sessionList.filter((s: any) => s.id !== id);
				vm.sessionMessage = 'Session deleted.';
				vm.sessionMessageType = 'success';
			} catch (e: any) {
				vm.sessionMessage = e.message || 'Failed to delete session.';
				vm.sessionMessageType = 'error';
			}
		},

		// =====================================================================
		// Dialogs
		// =====================================================================

		openConfirmDialog(opts: {
			title: string;
			message: string;
			confirmLabel?: string;
			iconClass?: string;
			danger?: boolean;
			onConfirm: () => void;
		}) {
			this.confirmDialog.title = opts.title;
			this.confirmDialog.message = opts.message;
			this.confirmDialog.confirmLabel = opts.confirmLabel || 'OK';
			this.confirmDialog.iconClass = opts.iconClass || 'bi-question-circle';
			this.confirmDialog.danger = !!opts.danger;
			this.confirmDialog.onConfirm = opts.onConfirm;
			this.confirmDialog.visible = true;
		},
		closeConfirmDialog() {
			this.confirmDialog.visible = false;
			this.confirmDialog.onConfirm = null;
		},
		acceptConfirmDialog() {
			const cb = this.confirmDialog.onConfirm;
			this.confirmDialog.visible = false;
			this.confirmDialog.onConfirm = null;
			if (cb) cb();
		},

		openNotification(opts: { kind?: 'info' | 'success' | 'warning' | 'error'; title: string; message: string }) {
			this.notificationDialog.kind = opts.kind || 'info';
			this.notificationDialog.title = opts.title;
			this.notificationDialog.message = opts.message;
			this.notificationDialog.visible = true;
		},
		closeNotificationDialog() {
			this.notificationDialog.visible = false;
		},
		notificationIconClass(): string {
			switch (this.notificationDialog.kind) {
				case 'success': return 'bi-check-circle-fill text-success';
				case 'warning': return 'bi-exclamation-triangle-fill text-warning';
				case 'error': return 'bi-x-circle-fill text-danger';
				default: return 'bi-info-circle-fill';
			}
		},

		// =====================================================================
		// Utilities
		// =====================================================================

		formatDate(dateStr: string): string {
			try {
				return new Date(dateStr).toLocaleString(navigator.language || 'en-US');
			} catch {
				return dateStr;
			}
		},

		readSliceAsBase64(blob: Blob): Promise<string> {
			return new Promise((resolve, reject) => {
				const reader = new FileReader();
				reader.onload = () => {
					const result = (reader.result as string).split(',')[1];
					resolve(result);
				};
				reader.onerror = () => reject(reader.error);
				reader.readAsDataURL(blob);
			});
		},

		async uploadFileInChunks(
			contentService: any,
			uploadInfo: any,
			file: File,
			chunkSize = 512 * 1024,
		): Promise<void> {
			let offset = 0;
			while (offset < file.size) {
				const slice = file.slice(offset, offset + chunkSize);
				const base64 = await (this as any).readSliceAsBase64(slice);
				await contentService.appendMultipartUploadChunk(uploadInfo.uploadId, base64);
				offset += chunkSize;
			}
		},
	},
};

VDOM.createApp(App).mount('#app');
