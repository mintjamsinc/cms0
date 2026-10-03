/**
 * Chat Application
 *
 * Conversations in channels, and the conversations of files. The sidebar lists
 * the private channels the user takes part in, the public channels the user
 * added and the conversations of files the user follows; the pane beside it
 * shows one of them in a <wt-chat-thread>, which is also what shows a
 * conversation elsewhere in the Webtop.
 *
 * Every call goes to the Chat GraphQL schema of the workspace the Webtop runs
 * in (services/chat-service-graphql.ts): conversations are kept per workspace.
 * Who takes part in a channel is who can read its folder, so participants are
 * managed here, through the schema, and not with the Content Browser.
 *
 * Nothing is polled. Each channel of the sidebar is watched with nodeChanged
 * on the Webtop's own event stream: a new message elsewhere marks its channel
 * unread, anything else (a rename, an archive, a change of participants) reads
 * the list again.
 */

import { VDOM } from '@mintjamsinc/ichigojs';
import { ApplicationInstance } from "../../services/webtop-service.js";
import { initUi } from "../../ui/index.js";
import { createShellPopupAdapter } from "../../ui/shell-popup-adapter.js";
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
	translate,
} from "../../composables/use-localization.js";
import type { PrincipalInfo } from "../../graphql/types.js";
import {
	ChatServiceGraphQL,
	chatIsUnread,
	type ChatChannel,
	type ChatChannelKind,
	type ChatConversation,
	type ChatFile,
	type ChatMember,
	type ChatRef,
	type ChatSearchHit,
} from "../../services/chat-service-graphql.js";
// Side-effect import: registers the <wt-chat-thread> custom element.
import { loadChatThreadTemplate } from "../../components/wt-chat-thread.js";
import { getFileIcon } from "../../lib/inspector-utils.js";
import { Identicon } from "../../lib/identicon.js";
import { sha256Hex } from "../../services/webtop-util.js";
import { openFileInEditor, revealInContentBrowser } from "../../lib/open-file.js";

interface LaunchOptions {
	channelId?: string;
	fileId?: string;
}

// Kept out of reactive data: ichigo.js wraps data in deep Proxies.
let chat: ChatServiceGraphQL | null = null;
let reloadTimer: ReturnType<typeof setTimeout> | null = null;
let statusTimer: ReturnType<typeof setTimeout> | null = null;
let browseTimer: ReturnType<typeof setTimeout> | null = null;
let memberTimer: ReturnType<typeof setTimeout> | null = null;
let directTimer: ReturnType<typeof setTimeout> | null = null;
// Stops watching for mentions of the user.
let unwatchMentions: (() => void) | null = null;
let confirmAction: (() => void) | null = null;
// Called when the conversation opened at launch has been read, or could not be.
let launchThreadSettled: (() => void) | null = null;
// The channels being watched, by their folder.
const watches = new Map<string, () => void>();
// Sequence numbers of the latest searches; older replies are dropped.
let browseSeq = 0;
let memberSeq = 0;
let directSeq = 0;
let searchSeq = 0;
let lastFocusReload = 0;

// Channels watched at a time, the most recently active first. The others are
// read when they are opened.
const MAX_WATCHED = 50;
// Changes to a channel come in bursts.
const RELOAD_DELAY_MS = 1000;
const SEARCH_DELAY_MS = 250;
// A window coming to the front reads the list again, to find new invitations.
const FOCUS_RELOAD_INTERVAL_MS = 30 * 1000;
const MEMBER_SUGGESTIONS = 10;
// How long the window waits, at launch, for the conversation it opens.
const LAUNCH_THREAD_WAIT_MS = 5000;

function emptyChannelDialog() {
	return {
		visible: false,
		/** The channel being edited, or '' when one is created. */
		id: '',
		title: '',
		description: '',
		kind: 'private' as ChatChannelKind,
		saving: false,
		error: '',
	};
}

function errorText(e: unknown): string {
	return (e instanceof Error) ? e.message : String(e);
}

const App = {
	data() {
		return {
			instance: null as ApplicationInstance | null,
			messageListener: null as ((event: MessageEvent) => void) | null,
			keyListener: null as ((event: KeyboardEvent) => void) | null,
			focusListener: null as (() => void) | null,
			localization: createLocalizationSnapshot(),
			isReady: false,

			channels: [] as ChatChannel[],
			isChannelsLoaded: false,
			// The conversations of files the user follows.
			threads: [] as ChatConversation[],
			// The open channel, or '' while none or the conversation of a file is open.
			currentId: '',
			// The file whose conversation is open, or ''.
			currentFileId: '',
			// The reference handed to <wt-chat-thread>; replaced only when another
			// conversation is opened.
			currentRef: null as ChatRef | null,
			// A channel that is open without being in the sidebar: a public one
			// opened from "Browse channels", or an archived one.
			visiting: null as ChatChannel | null,
			conversation: null as ChatConversation | null,
			// Raised to have the open conversation read again.
			reloadCount: 0,
			// A message the open conversation is to be brought to (a search hit).
			focusMessageId: '' as string,
			// Identicons of the other users of direct messages, by user id.
			avatars: {} as Record<string, string>,
			searchText: '',
			search: {
				active: false,
				text: '',
				items: [] as ChatSearchHit[],
				cursor: null as string | null,
				loading: false,
				error: '',
			},
			threadApi: null as Record<string, unknown> | null,

			sidePanelWidth: 220,
			isResizing: false,
			statusMessage: '',

			channelDialog: emptyChannelDialog(),
			browseDialog: {
				visible: false,
				keyword: '',
				includeArchived: false,
				results: [] as ChatChannel[],
				loading: false,
				error: '',
			},
			membersDialog: {
				visible: false,
				keyword: '',
				results: [] as PrincipalInfo[],
				searching: false,
				busy: false,
				error: '',
			},
			confirmDialog: { visible: false, title: '', message: '' },
			directDialog: {
				visible: false,
				keyword: '',
				results: [] as PrincipalInfo[],
				searching: false,
				busy: false,
				error: '',
			},
		};
	},
	computed: {
		current(): ChatChannel | null {
			if (!this.currentId) return null;
			const listed = (this.channels as ChatChannel[]).find((c) => c.id === this.currentId);
			if (listed) return listed;
			return (this.visiting && this.visiting.id === this.currentId) ? this.visiting : null;
		},
		/** The channels of the sidebar, without the direct messages. */
		groupChannels(): ChatChannel[] {
			return (this.channels as ChatChannel[]).filter((c) => c.kind !== 'dm');
		},
		/** The direct messages, under the other user's name. */
		directChannels(): ChatChannel[] {
			return (this.channels as ChatChannel[]).filter((c) => c.kind === 'dm');
		},
		/** Whether the open channel is one the sidebar does not list. */
		isVisiting(): boolean {
			return !!this.currentId && !(this.channels as ChatChannel[]).some((c) => c.id === this.currentId);
		},
		/** The file whose conversation is open: as the thread read it, or as the sidebar lists it. */
		currentFile(): ChatFile | null {
			const id = this.currentFileId as string;
			if (!id) return null;
			if (this.conversation?.fileId === id && this.conversation.file) return this.conversation.file;
			return (this.threads as ChatConversation[]).find((t) => t.fileId === id)?.file || null;
		},
		/** Whether the open file's conversation is in the sidebar. */
		isFollowingFile(): boolean {
			return (this.threads as ChatConversation[]).some((t) => t.fileId === this.currentFileId);
		},
		members(): ChatMember[] {
			return this.conversation?.members || [];
		},
		membersLabel(): string {
			if (this.current?.kind === 'public') {
				return this.t('app.chat.members.everyone', undefined, 'Everyone');
			}
			return String((this.members as ChatMember[]).length);
		},
		canManageMembers(): boolean {
			const c = this.current as ChatChannel | null;
			return !!c && c.isAdmin && c.kind === 'private' && !c.archived;
		},
		kindItems(): { value: ChatChannelKind; label: string }[] {
			return [
				{ value: 'private', label: this.t('app.chat.channel.kind.private', undefined, 'Private: only the people invited') },
				{ value: 'public', label: this.t('app.chat.channel.kind.public', undefined, 'Public: everyone') },
			];
		},
		kindHint(): string {
			return this.channelDialog.kind === 'public' ?
				this.t('app.chat.channel.kind.publicHint', undefined, 'Everyone can find the channel, read it and post to it.') :
				this.t('app.chat.channel.kind.privateHint', undefined, 'Only the users and groups you add can read the channel.');
		},
	},
	methods: {
		t(messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization, this.instance, messageId, params, fallback);
		},

		onMounted() {
			const vm = this;

			vm.messageListener = (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const { type, ...payload } = event.data || {};
				if (handleLocalizationMessage(type, vm.localization, vm.instance)) {
					return;
				}
				if (type === 'app-reopen') {
					vm.applyLaunchOptions(payload.options);
					return;
				}
				if (type === 'theme-changed') {
					document.documentElement.dataset.theme = payload.theme;
				}
			};
			window.addEventListener('message', vm.messageListener);
			vm.keyListener = (event: KeyboardEvent) => {
				if (event.key === 'F5') {
					event.preventDefault();
					vm.refreshAll();
				}
			};
			window.addEventListener('keydown', vm.keyListener);

			// An invitation to a private channel raises no event the invited user
			// could be watching for, so the list is read again when the window
			// comes to the front.
			vm.focusListener = () => {
				const now = Date.now();
				if (!chat || now - lastFocusReload < FOCUS_RELOAD_INTERVAL_MS) return;
				lastFocusReload = now;
				vm.loadChannels();
				vm.loadThreads();
			};
			window.addEventListener('focus', vm.focusListener);

			window.appLaunch = async (instance: ApplicationInstance, options?: LaunchOptions) => {
				vm.instance = this.$markRaw(instance);
				refreshLocalization(vm.localization, vm.instance);
				document.documentElement.dataset.theme = vm.instance.api.theme.currentTheme || 'light';

				try {
					await Promise.all([
						initUi({ popupAdapter: createShellPopupAdapter(instance) }),
						loadChatThreadTemplate(),
					]);
				} catch (e) {
					console.warn('[Chat] Failed to load component templates:', e);
				}

				chat = new ChatServiceGraphQL(instance.api.graphql);
				// Marked raw so the reactive system never Proxy-wraps the services:
				// both carry private fields, which throw when called through a Proxy.
				vm.threadApi = this.$markRaw({
					chat,
					eventHub: instance.api.eventHub,
					// What attaching files needs: the content service of this
					// workspace, and whose home the uploads go to.
					content: instance.api.content,
					workspace: instance.api.workspace,
					userId: instance.currentUser?.id || '',
				});
				instance.setBeforeCloseCallback(() => {
					vm.stopWatching();
					return true;
				});
				vm.watchMentions();

				vm.isReady = true;

				// The window is shown once the sidebar and the conversation it
				// opens are in: shown empty first, it looks as if the
				// conversations were gone.
				lastFocusReload = Date.now();
				await Promise.all([vm.loadChannels(), vm.loadThreads()]);
				const threadSettled = new Promise<void>((resolve) => { launchThreadSettled = resolve; });
				if (options?.channelId || options?.fileId) {
					await vm.applyLaunchOptions(options);
				} else if (vm.channels.length) {
					vm.selectChannel(vm.mostRecent().id);
				}
				if (vm.currentRef) {
					await Promise.race([threadSettled, new Promise<void>((resolve) => setTimeout(resolve, LAUNCH_THREAD_WAIT_MS))]);
				}
				launchThreadSettled = null;

				await new Promise<void>((resolve) => vm.$nextTick(() => resolve()));
				this.$nextTick(() => {
					instance.notifyLaunched();
				});
			};
		},
		onUnmount() {
			if (this.messageListener) {
				window.removeEventListener('message', this.messageListener);
			}
			if (this.keyListener) {
				window.removeEventListener('keydown', this.keyListener);
			}
			if (this.focusListener) {
				window.removeEventListener('focus', this.focusListener);
			}
			this.stopWatching();
		},
		/** Another app asked for a conversation: `{ channelId }` or `{ fileId }`. */
		async applyLaunchOptions(options?: LaunchOptions | null) {
			if (options?.fileId) {
				this.selectThread(options.fileId);
				return;
			}
			const id = options?.channelId;
			if (!id || !chat) return;
			if ((this.channels as ChatChannel[]).some((c) => c.id === id)) {
				this.selectChannel(id);
				return;
			}
			try {
				const conversation = await chat.getConversation({ channelId: id });
				if (conversation.channel) this.visit(conversation.channel);
			} catch (e) {
				this.showError(e);
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
		// The sidebar
		// =====================================================================

		async loadChannels() {
			if (!chat) return;
			try {
				this.channels = await chat.listChannels();
				this.isChannelsLoaded = true;
				this.watchConversations();
				this.loadAvatars();
			} catch (e) {
				console.warn('[Chat] Channels could not be loaded:', e);
				this.showStatus(this.t('app.chat.error.load', undefined, 'The channels could not be loaded.'));
			}
		},
		async loadThreads() {
			if (!chat) return;
			try {
				this.threads = await chat.listFollowedThreads();
				this.watchConversations();
			} catch (e) {
				console.warn('[Chat] The conversations of files could not be loaded:', e);
			}
		},
		/**
		 * Reads the channels and the open conversation again. Live updates cover
		 * what happens in the conversations; this is for what changes outside
		 * them, such as a linked file that was moved or renamed.
		 */
		refreshAll() {
			lastFocusReload = Date.now();
			this.loadChannels();
			this.loadThreads();
			this.reloadCount++;
		},
		scheduleReload() {
			if (reloadTimer) clearTimeout(reloadTimer);
			reloadTimer = setTimeout(() => {
				reloadTimer = null;
				this.loadChannels();
			}, RELOAD_DELAY_MS);
		},
		/** The channel with the latest message, or the first when none has any. */
		mostRecent(): ChatChannel {
			const channels = this.channels as ChatChannel[];
			return channels.reduce((best, c) => ((c.lastMessageAt || '') > (best.lastMessageAt || '') ? c : best), channels[0]);
		},
		/** Identicons for the other users of the direct messages. */
		async loadAvatars() {
			for (const c of this.channels as ChatChannel[]) {
				const id = c.peerId;
				if (!id || this.avatars[id]) continue;
				this.avatars[id] = new Identicon(await sha256Hex(id), {
					background: [245, 245, 245, 255],
					margin: 0.2,
					format: 'svg',
				}).dataURL as string;
			}
		},
		isUnread(c: ChatChannel): boolean {
			return c.id !== this.currentId && chatIsUnread(c);
		},
		selectChannel(id: string) {
			this.search.active = false;
			this.focusMessageId = '';
			if (id === this.currentId) return;
			this.visiting = null;
			this.conversation = null;
			this.currentFileId = '';
			this.currentId = id;
			this.currentRef = { channelId: id };
		},
		/** Opens the conversation of a file, whether the sidebar lists it or not. */
		selectThread(fileId: string) {
			this.search.active = false;
			this.focusMessageId = '';
			if (fileId === this.currentFileId) return;
			this.visiting = null;
			this.conversation = null;
			this.currentId = '';
			this.currentFileId = fileId;
			this.currentRef = { fileId };
		},
		isThreadUnread(t: ChatConversation): boolean {
			return t.fileId !== this.currentFileId && chatIsUnread(t);
		},
		fileIcon(f: ChatFile | null): string {
			return f ? getFileIcon({ mimeType: f.mimeType || '', isCollection: f.isCollection }) : 'bi bi-file-earmark';
		},
		patchThread(fileId: string, fields: Partial<ChatConversation>) {
			const listed = (this.threads as ChatConversation[]).find((t) => t.fileId === fileId);
			if (listed) Object.assign(listed, fields);
		},
		/** Opens a channel the sidebar does not list. */
		visit(channel: ChatChannel) {
			if ((this.channels as ChatChannel[]).some((c) => c.id === channel.id)) {
				this.selectChannel(channel.id);
				return;
			}
			this.search.active = false;
			this.focusMessageId = '';
			this.conversation = null;
			this.visiting = channel;
			this.currentFileId = '';
			this.currentId = channel.id;
			this.currentRef = { channelId: channel.id };
		},
		closeCurrent() {
			this.visiting = null;
			this.conversation = null;
			this.currentId = '';
			this.currentFileId = '';
			this.currentRef = null;
		},
		/** Puts what is known anew about a channel into the list, or into the visited one. */
		patchChannel(id: string, fields: Partial<ChatChannel>) {
			const listed = (this.channels as ChatChannel[]).find((c) => c.id === id);
			if (listed) Object.assign(listed, fields);
			if (this.visiting && this.visiting.id === id) Object.assign(this.visiting, fields);
		},

		// =====================================================================
		// Live updates
		// =====================================================================

		/** Watches the most recently active conversations of the sidebar. */
		watchConversations() {
			const hub = this.instance?.api.eventHub;
			if (!hub) return;
			const listed: { watchPath: string; lastMessageAt: string | null; channelId?: string; fileId?: string }[] = [
				...(this.channels as ChatChannel[]).map((c) => ({ watchPath: c.watchPath, lastMessageAt: c.lastMessageAt, channelId: c.id })),
				...(this.threads as ChatConversation[]).map((t) => ({ watchPath: t.watchPath, lastMessageAt: t.lastMessageAt, fileId: t.fileId || '' })),
			];
			const wanted = listed
				.sort((a, b) => (b.lastMessageAt || '').localeCompare(a.lastMessageAt || ''))
				.slice(0, MAX_WATCHED);
			const paths = new Set(wanted.map((c) => c.watchPath));
			for (const [path, unwatch] of watches) {
				if (!paths.has(path)) {
					try { unwatch(); } catch { /* ignore */ }
					watches.delete(path);
				}
			}
			for (const c of wanted) {
				if (watches.has(c.watchPath)) continue;
				if (c.fileId) {
					// The conversation of a file: every mark under its signals is a
					// change to its messages. The open one is kept up to date by its thread.
					const fileId = c.fileId;
					watches.set(c.watchPath, hub.watchNode(c.watchPath, (event) => {
						if (fileId === this.currentFileId) return;
						if (event.eventType === 'CREATED') {
							this.patchThread(fileId, { lastMessageAt: new Date().toISOString() });
						}
					}, true));
					continue;
				}
				const id = c.channelId as string;
				const messages = `${c.watchPath}/messages/`;
				watches.set(c.watchPath, hub.watchNode(c.watchPath, (event) => {
					const path = event?.path || '';
					if (!path.startsWith(messages)) {
						// The channel itself changed, or it can no longer be read.
						this.scheduleReload();
						return;
					}
					// The open channel is kept up to date by its thread.
					if (id === this.currentId) return;
					if (event.eventType === 'CREATED' && path.endsWith('.md')) {
						this.patchChannel(id, { lastMessageAt: new Date().toISOString() });
					}
				}, true));
			}
		},
		/**
		 * Watches for mentions of the user: a mark is left for each. The
		 * conversation it was made in may be one the sidebar does not list
		 * yet, so the lists are read again.
		 */
		async watchMentions() {
			const hub = this.instance?.api.eventHub;
			if (!hub || !chat || unwatchMentions) return;
			try {
				const path = await chat.mentionWatchPath();
				unwatchMentions = hub.watchNode(path, (event) => {
					if (event.eventType !== 'CREATED') return;
					this.showStatus(this.t('app.chat.mention.notice', undefined, 'You were mentioned.'));
					this.scheduleReload();
					this.loadThreads();
				}, true);
			} catch (e) {
				console.warn('[Chat] Mentions cannot be watched:', e);
			}
		},
		stopWatching() {
			for (const unwatch of watches.values()) {
				try { unwatch(); } catch { /* ignore */ }
			}
			watches.clear();
			if (unwatchMentions) {
				try { unwatchMentions(); } catch { /* ignore */ }
				unwatchMentions = null;
			}
			if (reloadTimer) {
				clearTimeout(reloadTimer);
				reloadTimer = null;
			}
		},

		// =====================================================================
		// The open conversation
		// =====================================================================

		onThreadLoaded(conversation: ChatConversation) {
			launchThreadSettled?.();
			if (conversation.fileId) {
				if (conversation.fileId !== this.currentFileId) return;
				this.conversation = conversation;
				this.patchThread(conversation.fileId, conversation);
				return;
			}
			if (conversation.channelId !== this.currentId) return;
			this.conversation = conversation;
			const channel = conversation.channel;
			if (!channel) return;
			const listed = (this.channels as ChatChannel[]).some((c) => c.id === channel.id);
			this.patchChannel(channel.id, channel);
			// An archived channel leaves the sidebar but stays open, read-only.
			if (listed && channel.archived) {
				this.visiting = channel;
				this.scheduleReload();
			}
		},
		onThreadFailed(detail: { message: string }) {
			launchThreadSettled?.();
			this.showStatus(detail.message);
			this.closeCurrent();
			this.loadChannels();
			this.loadThreads();
		},
		onThreadActivity(detail: { lastMessageAt: string }) {
			if (this.currentId) this.patchChannel(this.currentId, { lastMessageAt: detail.lastMessageAt });
			if (this.currentFileId) this.patchThread(this.currentFileId, { lastMessageAt: detail.lastMessageAt });
		},
		onThreadRead(detail: { readAt: string }) {
			if (this.currentId) this.patchChannel(this.currentId, { readAt: detail.readAt });
			if (this.currentFileId) this.patchThread(this.currentFileId, { readAt: detail.readAt });
		},
		/** Posting to the conversation of a file adds it to the sidebar. */
		onThreadPosted() {
			if (this.currentFileId && !this.isFollowingFile) this.loadThreads();
		},
		openCurrentFile() {
			const f = this.currentFile as ChatFile | null;
			if (!f) return;
			if (f.isCollection || !openFileInEditor(f.path, f.mimeType)) {
				revealInContentBrowser(f.path, f.isCollection);
			}
		},
		revealCurrentFile() {
			const f = this.currentFile as ChatFile | null;
			if (f) revealInContentBrowser(f.path, f.isCollection);
		},
		/** Adds the open file's conversation to the sidebar, or takes it out. */
		async toggleFollowFile() {
			const fileId = this.currentFileId as string;
			if (!chat || !fileId) return;
			try {
				if (this.isFollowingFile) {
					await chat.unfollow({ fileId });
				} else {
					await chat.follow({ fileId });
				}
				await this.loadThreads();
			} catch (e) {
				this.showError(e);
			}
		},
		async reloadConversation() {
			if (!chat || !this.currentId) return;
			const id = this.currentId;
			try {
				const conversation = await chat.getConversation({ channelId: id });
				if (id === this.currentId) this.onThreadLoaded(conversation);
			} catch (e) {
				if (id === this.currentId) this.onThreadFailed({ message: errorText(e) });
			}
		},

		openChannelMenu(event: MouseEvent) {
			const c = this.current as ChatChannel | null;
			if (!c || !this.instance) return;
			const items: { id: string; label: string; icon: string; danger?: boolean }[] = [];
			if (this.conversation) {
				items.push({ id: 'members', label: this.t('app.chat.members.title', undefined, 'Participants'), icon: 'bi bi-people' });
			}
			if (c.isAdmin && !c.archived) {
				items.push({ id: 'settings', label: this.t('app.chat.channel.settings', undefined, 'Channel settings'), icon: 'bi bi-gear' });
			}
			if (c.kind === 'public' && !c.archived) {
				items.push(this.isVisiting ?
					{ id: 'follow', label: this.t('app.chat.channel.follow', undefined, 'Add to sidebar'), icon: 'bi bi-plus-lg' } :
					{ id: 'unfollow', label: this.t('app.chat.channel.unfollow', undefined, 'Remove from sidebar'), icon: 'bi bi-dash-lg' });
			}
			if (c.kind === 'private' && !c.archived) {
				items.push({ id: 'leave', label: this.t('app.chat.channel.leave', undefined, 'Leave channel'), icon: 'bi bi-box-arrow-right', danger: true });
			}
			if (c.isAdmin) {
				items.push(c.archived ?
					{ id: 'unarchive', label: this.t('app.chat.channel.unarchive', undefined, 'Restore channel'), icon: 'bi bi-arrow-counterclockwise' } :
					{ id: 'archive', label: this.t('app.chat.channel.archive', undefined, 'Archive channel'), icon: 'bi bi-archive', danger: true });
			}
			if (!items.length) return;
			const handle = this.instance.popup.open({
				anchor: (event.currentTarget as HTMLElement).getBoundingClientRect(),
				placement: 'bottom-end',
				minWidth: 200,
				items,
			});
			handle.result.then((picked) => {
				switch (picked) {
					case 'members': this.openMembers(); break;
					case 'settings': this.openSettings(); break;
					case 'follow': this.followCurrent(); break;
					case 'unfollow': this.unfollowCurrent(); break;
					case 'leave': this.askLeave(); break;
					case 'archive': this.askArchive(); break;
					case 'unarchive': this.setArchived(false); break;
				}
			});
		},
		async followCurrent() {
			const c = this.current as ChatChannel | null;
			if (!chat || !c) return;
			try {
				await chat.follow({ channelId: c.id });
				await this.loadChannels();
			} catch (e) {
				this.showError(e);
			}
		},
		async unfollowCurrent() {
			const c = this.current as ChatChannel | null;
			if (!chat || !c) return;
			try {
				await chat.unfollow({ channelId: c.id });
				// The channel stays open, as one that is being visited.
				this.visiting = { ...c, following: false };
				await this.loadChannels();
			} catch (e) {
				this.showError(e);
			}
		},
		askLeave() {
			const c = this.current as ChatChannel | null;
			if (!c) return;
			this.openConfirm(
				this.t('app.chat.channel.leave', undefined, 'Leave channel'),
				this.t('app.chat.channel.leaveConfirm', { name: c.title }, 'Leave {name}? You will need to be invited again to come back.'),
				async () => {
					if (!chat) return;
					try {
						await chat.leaveChannel(c.id);
						this.closeCurrent();
						await this.loadChannels();
					} catch (e) {
						this.showError(e);
					}
				});
		},
		askArchive() {
			const c = this.current as ChatChannel | null;
			if (!c) return;
			this.openConfirm(
				this.t('app.chat.channel.archive', undefined, 'Archive channel'),
				this.t('app.chat.channel.archiveConfirm', { name: c.title },
					'Archive {name}? It leaves every sidebar and takes no more messages. The messages stay, and an administrator can restore the channel.'),
				() => this.setArchived(true));
		},
		async setArchived(archived: boolean) {
			const c = this.current as ChatChannel | null;
			if (!chat || !c) return;
			try {
				const channel = await chat.archiveChannel(c.id, archived);
				// Archived, the channel leaves the list; keep it open, read-only.
				this.visiting = channel;
				await this.loadChannels();
				await this.reloadConversation();
			} catch (e) {
				this.showError(e);
			}
		},

		// =====================================================================
		// Creating a channel and its settings
		// =====================================================================

		openCreate() {
			this.channelDialog = { ...emptyChannelDialog(), visible: true };
			this.focusLater('channelTitle');
		},
		openSettings() {
			const c = this.current as ChatChannel | null;
			if (!c) return;
			this.channelDialog = {
				...emptyChannelDialog(),
				visible: true,
				id: c.id,
				title: c.title,
				description: c.description || '',
				kind: c.kind,
			};
			this.focusLater('channelTitle');
		},
		closeChannelDialog() {
			this.channelDialog.visible = false;
		},
		async saveChannel() {
			const d = this.channelDialog;
			const title = d.title.trim();
			if (!chat || !title || d.saving) return;
			d.saving = true;
			d.error = '';
			try {
				if (d.id) {
					const channel = await chat.updateChannel(d.id, { title, description: d.description.trim() });
					this.patchChannel(channel.id, channel);
					this.closeChannelDialog();
				} else {
					const channel = await chat.createChannel({ title, description: d.description.trim(), kind: d.kind });
					this.closeChannelDialog();
					await this.loadChannels();
					this.selectChannel(channel.id);
				}
			} catch (e) {
				d.error = errorText(e);
			} finally {
				d.saving = false;
			}
		},

		// =====================================================================
		// Browsing channels
		// =====================================================================

		openBrowse() {
			this.browseDialog = { visible: true, keyword: '', includeArchived: false, results: [], loading: false, error: '' };
			this.runBrowse();
			this.focusLater('browseKeyword');
		},
		closeBrowse() {
			this.browseDialog.visible = false;
			if (browseTimer) {
				clearTimeout(browseTimer);
				browseTimer = null;
			}
		},
		scheduleBrowse() {
			if (browseTimer) clearTimeout(browseTimer);
			browseTimer = setTimeout(() => {
				browseTimer = null;
				this.runBrowse();
			}, SEARCH_DELAY_MS);
		},
		toggleBrowseArchived(checked: boolean) {
			this.browseDialog.includeArchived = checked;
			this.runBrowse();
		},
		async runBrowse() {
			const d = this.browseDialog;
			if (!chat) return;
			const seq = ++browseSeq;
			d.loading = true;
			d.error = '';
			try {
				const results = await chat.findChannels(d.keyword.trim(), d.includeArchived);
				if (seq === browseSeq) d.results = results;
			} catch (e) {
				if (seq === browseSeq) {
					d.results = [];
					d.error = errorText(e);
				}
			} finally {
				if (seq === browseSeq) d.loading = false;
			}
		},
		openFromBrowse(c: ChatChannel) {
			this.closeBrowse();
			this.visit(c);
		},
		async followFromBrowse(c: ChatChannel) {
			if (!chat) return;
			try {
				await chat.follow({ channelId: c.id });
				c.following = true;
				await this.loadChannels();
			} catch (e) {
				this.browseDialog.error = errorText(e);
			}
		},

		// =====================================================================
		// Direct messages
		// =====================================================================

		openNewDirect() {
			this.directDialog = { visible: true, keyword: '', results: [], searching: false, busy: false, error: '' };
			this.focusLater('directKeyword');
		},
		closeNewDirect() {
			this.directDialog.visible = false;
			if (directTimer) {
				clearTimeout(directTimer);
				directTimer = null;
			}
		},
		scheduleDirectSearch() {
			if (directTimer) clearTimeout(directTimer);
			directTimer = setTimeout(() => {
				directTimer = null;
				this.searchDirect();
			}, SEARCH_DELAY_MS);
		},
		/** Users matching the keyword: not groups, not service accounts, not oneself. */
		async searchDirect() {
			const d = this.directDialog;
			const keyword = d.keyword.trim();
			const seq = ++directSeq;
			if (!keyword || !this.instance) {
				d.results = [];
				d.searching = false;
				return;
			}
			d.searching = true;
			try {
				const me = this.instance.currentUser?.id;
				const found = await this.instance.api.content.searchPrincipals(keyword, 0, MEMBER_SUGGESTIONS * 2);
				if (seq !== directSeq) return;
				d.results = found.filter((p: PrincipalInfo) => !p.isGroup && !p.isService && p.identifier !== me).slice(0, MEMBER_SUGGESTIONS);
			} catch (e) {
				if (seq === directSeq) {
					d.results = [];
					d.error = errorText(e);
				}
			} finally {
				if (seq === directSeq) d.searching = false;
			}
		},
		async openDirectWith(p: PrincipalInfo) {
			const d = this.directDialog;
			if (!chat || d.busy) return;
			d.busy = true;
			d.error = '';
			try {
				const channel = await chat.openDirectMessage(p.identifier);
				this.closeNewDirect();
				await this.loadChannels();
				this.selectChannel(channel.id);
			} catch (e) {
				d.error = errorText(e);
			} finally {
				d.busy = false;
			}
		},

		// =====================================================================
		// Search
		// =====================================================================

		runSearch() {
			const text = this.searchText.trim();
			if (!chat || !text) return;
			this.search = { active: true, text, items: [], cursor: null, loading: false, error: '' };
			this.loadSearch(null);
		},
		moreSearch() {
			if (this.search.cursor) this.loadSearch(this.search.cursor);
		},
		async loadSearch(after: string | null) {
			const s = this.search;
			if (!chat) return;
			const seq = ++searchSeq;
			s.loading = true;
			s.error = '';
			try {
				const page = await chat.search(s.text, 20, after);
				if (seq !== searchSeq) return;
				s.items = after ? s.items.concat(page.items) : page.items;
				s.cursor = page.cursor;
			} catch (e) {
				if (seq === searchSeq) s.error = errorText(e);
			} finally {
				if (seq === searchSeq) s.loading = false;
			}
		},
		closeSearch() {
			searchSeq++;
			this.search.active = false;
			this.searchText = '';
		},
		hitIcon(h: ChatSearchHit): string {
			if (h.file) return this.fileIcon(h.file);
			const kind = h.channel?.kind;
			return kind === 'public' ? 'bi bi-hash' : (kind === 'dm' ? 'bi bi-person' : 'bi bi-lock');
		},
		hitTitle(h: ChatSearchHit): string {
			return h.file ? h.file.name : (h.channel?.title || '');
		},
		formatHitTime(value: string): string {
			const locale = this.localization.locale || navigator.language || 'en';
			return new Date(value).toLocaleString(locale, { dateStyle: 'medium', timeStyle: 'short', timeZone: this.localization.timeZone || undefined });
		},
		/** Opens the conversation of the hit at the message. */
		openHit(h: ChatSearchHit) {
			if (h.fileId) {
				this.selectThread(h.fileId);
			} else if (h.channel) {
				if ((this.channels as ChatChannel[]).some((c) => c.id === h.channel!.id)) {
					this.selectChannel(h.channel.id);
				} else {
					this.visit(h.channel);
				}
			} else {
				return;
			}
			// Set after the selection, which clears it.
			this.focusMessageId = h.message.id;
		},

		// =====================================================================
		// Participants
		// =====================================================================
		openMembers() {
			this.membersDialog = { visible: true, keyword: '', results: [], searching: false, busy: false, error: '' };
			// The list shown may be from when the channel was opened.
			this.reloadConversation();
			if (this.canManageMembers) this.focusLater('memberKeyword');
		},
		closeMembers() {
			this.membersDialog.visible = false;
			if (memberTimer) {
				clearTimeout(memberTimer);
				memberTimer = null;
			}
		},
		memberName(m: ChatMember): string {
			if (m.id === 'everyone') return this.t('app.chat.members.everyone', undefined, 'Everyone');
			return m.displayName || m.id;
		},
		scheduleMemberSearch() {
			if (memberTimer) clearTimeout(memberTimer);
			memberTimer = setTimeout(() => {
				memberTimer = null;
				this.searchMembers();
			}, SEARCH_DELAY_MS);
		},
		/** Users and groups matching the keyword that are not participants yet. */
		async searchMembers() {
			const d = this.membersDialog;
			const keyword = d.keyword.trim();
			const seq = ++memberSeq;
			if (!keyword || !this.instance) {
				d.results = [];
				d.searching = false;
				return;
			}
			d.searching = true;
			try {
				// Asked for a few more than are shown: participants are left out.
				const found = await this.instance.api.content.searchPrincipals(keyword, 0, MEMBER_SUGGESTIONS * 2);
				if (seq !== memberSeq) return;
				const present = new Set((this.members as ChatMember[]).map((m) => m.id));
				d.results = found.filter((p: PrincipalInfo) => !p.isService && !present.has(p.identifier)).slice(0, MEMBER_SUGGESTIONS);
			} catch (e) {
				if (seq === memberSeq) {
					d.results = [];
					d.error = errorText(e);
				}
			} finally {
				if (seq === memberSeq) d.searching = false;
			}
		},
		/** Runs a change of the participants and shows the list it returns. */
		async changeMembers(change: (id: string) => Promise<ChatConversation | void>) {
			const d = this.membersDialog;
			const id = this.currentId;
			if (!chat || !id || d.busy) return;
			d.busy = true;
			d.error = '';
			try {
				const conversation = await change(id);
				if (conversation && id === this.currentId) {
					this.onThreadLoaded(conversation);
				} else {
					await this.reloadConversation();
				}
				d.results = (d.results as PrincipalInfo[]).filter((p) => !(this.members as ChatMember[]).some((m) => m.id === p.identifier));
			} catch (e) {
				d.error = errorText(e);
			} finally {
				d.busy = false;
			}
		},
		addMember(p: PrincipalInfo) {
			this.changeMembers((id) => chat!.addMembers(id, [p.identifier]));
		},
		removeMember(m: ChatMember) {
			this.changeMembers((id) => chat!.removeMembers(id, [m.id]));
		},
		toggleAdmin(m: ChatMember) {
			const admins = (this.members as ChatMember[]).filter((x) => x.isAdmin && x.id !== m.id).map((x) => x.id);
			if (!m.isAdmin) admins.push(m.id);
			this.changeMembers(async (id) => {
				await chat!.updateChannel(id, { admins });
			});
		},

		// =====================================================================
		// Helpers
		// =====================================================================

		focusLater(ref: string) {
			// The dialog renders its content when it opens.
			this.$nextTick(() => setTimeout(() => (this.$refs[ref] as HTMLElement | undefined)?.focus(), 0));
		},
		showStatus(message: string) {
			this.statusMessage = message;
			if (statusTimer) clearTimeout(statusTimer);
			statusTimer = setTimeout(() => {
				this.statusMessage = '';
				statusTimer = null;
			}, 5000);
		},
		showError(e: unknown) {
			console.warn('[Chat]', e);
			this.showStatus(errorText(e));
		},
		openConfirm(title: string, message: string, onAccept: () => void) {
			confirmAction = onAccept;
			this.confirmDialog = { visible: true, title, message };
		},
		closeConfirmDialog() {
			this.confirmDialog.visible = false;
			confirmAction = null;
		},
		acceptConfirmDialog() {
			const fn = confirmAction;
			this.closeConfirmDialog();
			if (fn) fn();
		},
	},
};

VDOM.createApp(App).mount('#app');
