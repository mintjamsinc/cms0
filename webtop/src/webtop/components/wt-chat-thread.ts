// <wt-chat-thread> — one conversation: its messages and the box to post into.
//
//     <wt-chat-thread :conversation="{ channelId: id }" :api="chatApi"
//         :localization="localization"
//         @loaded="onConversationLoaded($event.detail)"
//         @activity="onActivity($event.detail)"
//         @read="onRead($event.detail)"></wt-chat-thread>
//
// The same element shows a channel in the Chat app and the conversation of a
// file wherever one is shown; the host only names the conversation.
//
// Props:
//   conversation   { channelId } or { fileId } (services/chat-service-graphql)
//   api            { chat: ChatServiceGraphQL, eventHub: EventHub,
//                    content: ContentServiceGraphQL, workspace, userId },
//                  marked raw by the host: the services carry private fields a
//                  Proxy cannot call. `content` (the content service of the
//                  workspace the conversation is in), `workspace` and `userId`
//                  are what attaching files needs; without them the thread
//                  posts text only
//   localization   the host's localization snapshot (see use-localization.ts)
//   focusOnOpen    puts the cursor in the message box when a conversation is
//                  opened. For a host whose whole purpose is the conversation;
//                  a side pane leaves the focus where the user is working
//   focusMessage   the id of a message to show: the conversation opens around
//                  it, scrolled to it, instead of at its latest messages
//   reload         a number the host raises to have the conversation read
//                  again: what changed outside it, such as a linked file that
//                  was moved, raises no event the thread could be watching
//
// Events (detail):
//   loaded     the ChatConversation, when the conversation was read or its
//              settings changed
//   failed     { message } when the conversation cannot be read (any more)
//   activity   { lastMessageAt } when messages were read
//   read       { readAt } when the read position moved
//   posted     the ChatMessage the reader posted
//
// Live updates need no subscription of their own. The element watches the
// conversation's `watchPath` with nodeChanged, on the host's existing event
// stream, and reads the messages again when something under it changes. For a
// channel that is the folder the messages are in. The conversation of a file
// cannot be read in the repository, so there it is a folder that gets a mark
// whenever the conversation changes.
//
// Files. A file dropped, pasted or picked from the computer is attached: it is
// uploaded into the user's home first and copied next to the message when it
// is posted. A repository file dragged in from the Content Browser is linked,
// which gives nobody access to it, unless the author switches it to a copy.
//
// Cards. A message may carry a card: a design (a folder under /etc/chat/cards
// with card.yml and card.html) and the values of its fields. The design's page
// is shown in a same-origin frame under the message, as tall as the page, and
// reaches this element through `window.parent.ChatCardHost.connect(window)`,
// which hands it the values, the message and the host's theme (ChatCardBridge
// below). The card button of the composer offers the designs the writer can
// read, a form made from the design's fields, and the same frame as a preview.
//
// The host loads the template (loadChatThreadTemplate) before the element is
// compiled and links components/wt-chat-thread.css.

import { defineComponent } from '@mintjamsinc/ichigojs';
import { BUILD_VERSION } from '../utils/build-version.js';
import { createLocalizationSnapshot, translate } from '../composables/use-localization.js';
import { createGraphQLClient, type GraphQLClient } from '../graphql/client.js';
import { sha256Hex } from '../services/webtop-util.js';
import { Identicon } from '../lib/identicon.js';
import { renderChatMarkdown } from '../lib/chat-markdown.js';
import { getFileIcon } from '../lib/inspector-utils.js';
import { downloadUrl, openConversationInChat, openFileInEditor, revealInContentBrowser } from '../lib/open-file.js';
import { Bytes } from '../utils/bytes.js';
import { Dates } from '../utils/dates.js';
import {
	chatRefKey,
	type ChatAttachment,
	type ChatCard,
	type ChatCardDesign,
	type ChatCardField,
	type ChatCardValues,
	type ChatConversation,
	type ChatLink,
	type ChatMessage,
	type ChatMessageContent,
	type ChatRef,
} from '../services/chat-service-graphql.js';

const PAGE_SIZE = 50;
// A refresh reads again what is shown, up to this many of the latest messages.
const MAX_REFRESH = 200;
// Changes come in bursts (a post is a node, its content and its properties).
const REFRESH_DELAY_MS = 250;
// Messages of one author this close together are shown under one heading.
const GROUP_WINDOW_MS = 5 * 60 * 1000;
// How near the end counts as "at the end" when new messages arrive.
const BOTTOM_SLACK_PX = 48;
// How near the start older messages are fetched.
const TOP_SLACK_PX = 160;
const COMPOSER_MAX_HEIGHT_PX = 160;
// How long a message the reader was brought to stays marked.
const FOCUS_MARK_MS = 2500;
// Suggestions for an @mention being typed.
const MENTION_SUGGESTIONS = 8;
const MENTION_DELAY_MS = 150;
// What one message may carry; the server holds the same limits.
const MAX_ATTACHMENTS = 10;
const MAX_LINKS = 10;
// The drag types of the Content Browser and the desktop.
const WEBTOP_FILES = 'application/x-webtop-files';
const WEBTOP_FILE = 'application/x-webtop-file';

/** A file uploaded into the home, waiting for its message to be posted. */
interface PendingUpload {
	key: string;
	name: string;
	mimeType: string;
	size: number;
}

/** A repository file or folder the message is to link, or to attach a copy of. */
interface PendingFile {
	id: string;
	name: string;
	path: string;
	mimeType: string | null;
	isCollection: boolean;
	mode: 'link' | 'copy';
}

/** What a message being written carries besides its text. */
interface Pending {
	/** Names the folder in the home the uploads go to. */
	draftId: string;
	uploads: PendingUpload[];
	files: PendingFile[];
	uploading: number;
	error: string;
}

function randomKey(): string {
	const bytes = new Uint8Array(16);
	crypto.getRandomValues(bytes);
	return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

function makePending(): Pending {
	return { draftId: randomKey(), uploads: [], files: [], uploading: 0, error: '' };
}

function emptyEditing() {
	return {
		id: '',
		text: '',
		saving: false,
		error: '',
		/** The attachments of the message that stay, and those to take off. */
		attachments: [] as ChatAttachment[],
		removed: [] as string[],
		/** The links of the message that stay. */
		links: [] as ChatLink[],
		pending: makePending(),
	};
}

interface Row {
	key: string;
	m: ChatMessage;
	/** The day's label when the message is the first of its day, else ''. */
	day: string;
	/** Whether the author and time are shown above the message. */
	head: boolean;
	/** Whether the reader was brought to this message. */
	focus: boolean;
	time: string;
	fullTime: string;
	html: string;
}

// The frame of a card is as tall as its page, up to this.
const CARD_MAX_HEIGHT_PX = 640;
// A preview is told of the values being typed this long after the last change.
const CARD_PREVIEW_DELAY_MS = 250;

/** What a card's page is told of: the host's theme or localization changed, or (a preview) the values did. */
type CardEventType = 'theme' | 'localization' | 'card';
type CardListener = (type: CardEventType, payload: unknown) => void;

/**
 * What a card's page gets from `window.parent.ChatCardHost.connect(window)`.
 *
 *     const host = window.parent.ChatCardHost.connect(window);
 *     render(host.card.fields);
 *     host.subscribe((type, payload) => { if (type === 'card') render(payload.fields); });
 *
 * Everything handed over is plain data: a copy, never the element's own state.
 * The frame is sized to the page by the element; a page whose height changes
 * without the document growing (a collapsed section) calls `resize()`.
 */
export interface ChatCardBridge {
	/** Bumped when the shape changes incompatibly. */
	readonly version: number;
	/** The workspace the conversation is in. */
	readonly workspace: string;
	/** Where the Webtop is served from (…/usr/share/webtop/), to load its stylesheets and fonts. */
	readonly webtopBaseUrl: string;
	/** True in the composer, where the page shows the values being typed. */
	readonly preview: boolean;
	/** The design's path and the values of its fields. */
	readonly card: { path: string; fields: ChatCardValues };
	/** The message the card is on; null for a preview. */
	readonly message: Record<string, unknown> | null;
	readonly conversation: { channelId: string | null; fileId: string | null };
	readonly currentUser: { id: string };
	/** `light` or `dark`. */
	readonly theme: string;
	readonly localization: { locale: string; timeZone: string };
	/** A client for the workspace, with the reader's credentials. */
	readonly graphql: GraphQLClient;
	translate(messageId: string, params?: Record<string, unknown>, fallback?: string): string;
	/** Told of `theme`, `localization` and, for a preview, `card` ({ fields }). Returns what unsubscribes. */
	subscribe(listener: CardListener): () => void;
	/** Fits the frame to the page again. */
	resize(): void;
	/** Opens a file of the repository in its editor; false when none takes it. */
	openFile(path: string, mimeType?: string | null): boolean;
	/** Opens a conversation in the Chat app. */
	openConversation(ref: { channelId?: string; fileId?: string }): boolean;
}

interface CardSlot {
	bridge: ChatCardBridge;
	listeners: Set<CardListener>;
}

// The frames of the cards shown in this window, whichever thread shows them:
// a page finds its own through its frame.
const cardSlots = new WeakMap<HTMLIFrameElement, CardSlot>();

/** Publishes `window.ChatCardHost`, through which the pages of the cards reach their frames. */
function installCardHost() {
	const w = window as unknown as { ChatCardHost?: unknown };
	if (w.ChatCardHost) return;
	w.ChatCardHost = {
		version: 1,
		connect(cardWindow: Window): ChatCardBridge {
			const frame = cardWindow?.frameElement as HTMLIFrameElement | null;
			const slot = frame ? cardSlots.get(frame) : undefined;
			if (!slot) throw new Error('This page is not shown as a card of a conversation.');
			return slot.bridge;
		},
	};
}

/** A copy as plain data: no proxies of this element's state cross into a card's page. */
function toPlainData<T>(value: T): T {
	if (value === undefined || value === null) return value;
	return JSON.parse(JSON.stringify(value)) as T;
}

/** What the composer holds for a card being added to the message. */
function emptyCardDraft() {
	return {
		open: false,
		loading: false,
		error: '',
		designs: [] as ChatCardDesign[],
		design: null as ChatCardDesign | null,
		/** What was typed for each field, by key; the values of a multiple field separated by commas. */
		values: {} as Record<string, string>,
	};
}

// Identicons by user id, shared by every thread of the page.
const avatarCache = new Map<string, string>();

let templateReady: Promise<void> | undefined;

/**
 * Fetches the element's <template> and adds it to the page. To be awaited
 * before the host compiles markup that contains <wt-chat-thread>.
 */
export function loadChatThreadTemplate(): Promise<void> {
	if (!templateReady) {
		templateReady = (async () => {
			const res = await fetch(new URL(`../../components/wt-chat-thread.html?v=${BUILD_VERSION}`, document.baseURI));
			if (!res.ok) {
				throw new Error(`wt-chat-thread: failed to load the template (HTTP ${res.status})`);
			}
			const doc = new DOMParser().parseFromString(await res.text(), 'text/html');
			for (const tmpl of Array.from(doc.querySelectorAll('template'))) {
				document.body.appendChild(tmpl);
			}
		})();
	}
	return templateReady;
}

function errorText(e: unknown): string {
	return (e instanceof Error) ? e.message : String(e);
}

defineComponent('wt-chat-thread', {
	template: '#wt-chat-thread',
	props: ['conversation', 'api', 'localization', 'reload', 'focusOnOpen', 'focusMessage'],
	emits: ['loaded', 'failed', 'activity', 'read', 'posted'],
	data(this: any) {
		return {
			// Non-reactive private state (subscriptions, timers, counters).
			_: this.$markRaw({
				key: '',
				unwatch: null as (() => void) | null,
				watchPath: '',
				refreshTimer: null as ReturnType<typeof setTimeout> | null,
				// Counts loads, so a reply for a conversation left behind is dropped.
				epoch: 0,
				refreshing: false,
				refreshAgain: false,
				atBottom: true,
				composing: false,
				focusTimer: null as ReturnType<typeof setTimeout> | null,
				mentionTimer: null as ReturnType<typeof setTimeout> | null,
				mentionSeq: 0,
				// The frames of the cards this thread shows, and what fits each
				// to its page.
				cardFrames: new Set<HTMLIFrameElement>(),
				cardObservers: new Map<HTMLIFrameElement, ResizeObserver>(),
				themeObserver: null as MutationObserver | null,
				previewTimer: null as ReturnType<typeof setTimeout> | null,
			}),
			info: null as ChatConversation | null,
			messages: [] as ChatMessage[],
			hasMore: false,
			// Newer messages beyond what is shown: the conversation was opened
			// around a message rather than at its end.
			hasMoreAfter: false,
			isLoadingNewer: false,
			// The message the reader was brought to, while it is marked.
			focusedId: '',
			isLoading: false,
			isLoadingOlder: false,
			errorMessage: '',
			// Set when messages arrived while the reader was further up.
			newBelow: false,
			avatars: {} as Record<string, string>,

			draft: '',
			compose: makePending(),
			isSending: false,
			sendError: '',
			isDragOver: false,

			editing: emptyEditing(),
			confirmDeleteId: '',

			// An @mention being typed in the message box: the name so far, the
			// users it may be, and which of them is highlighted.
			mention: { open: false, query: '', start: -1, items: [] as { id: string; name: string }[], index: 0 },

			// A card being added to the new message.
			cardDraft: emptyCardDraft(),
		};
	},
	computed: {
		canPost(this: any): boolean {
			return !!this.info?.canPost;
		},
		/** Whether the host gave what attaching files needs. */
		canAttach(this: any): boolean {
			return !!(this.api?.content && this.api?.workspace && this.api?.userId);
		},
		canSend(this: any): boolean {
			const p = this.compose as Pending;
			if (this.isSending || p.uploading) return false;
			if (this.cardDraft.design) return !this.cardProblem;
			return !!String(this.draft || '').trim() || p.uploads.length + p.files.length > 0;
		},
		/** What keeps the card being written from being posted: the first required field left empty, or ''. */
		cardProblem(this: any): string {
			const design = this.cardDraft.design as ChatCardDesign | null;
			if (!design) return '';
			for (const f of design.fields) {
				if (f.required && !this.cardItems(f).length) {
					return this.t('webtop.chat.card.required', { label: f.label }, '{label} is required.');
				}
			}
			return '';
		},
		/** The card of the composer as a card to show: what the preview frame loads. */
		previewCard(this: any): ChatCard | null {
			const design = this.cardDraft.design as ChatCardDesign | null;
			if (!design) return null;
			return { path: design.path, accessible: true, label: design.label, version: null, fields: null, bodyFromCard: false };
		},
		canSaveEdit(this: any): boolean {
			const e = this.editing;
			const p = e.pending as Pending;
			const carried = e.attachments.length + e.links.length + p.uploads.length + p.files.length;
			return !e.saving && !p.uploading && (!!String(e.text || '').trim() || carried > 0);
		},
		isArchived(this: any): boolean {
			return !!this.info?.channel?.archived;
		},
		rows(this: any): Row[] {
			const locale = this.localization?.locale || navigator.language || 'en';
			const timeZone = this.localization?.timeZone || undefined;
			const dayKey = new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit', timeZone });
			const dayLabel = new Intl.DateTimeFormat(locale, { year: 'numeric', month: 'long', day: 'numeric', weekday: 'short', timeZone });
			const time = new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit', timeZone });
			const full = new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'medium', timeZone });

			const rows: Row[] = [];
			let lastDay = '';
			let last: ChatMessage | null = null;
			for (const m of this.messages as ChatMessage[]) {
				const posted = new Date(m.postedAt);
				const day = dayKey.format(posted);
				const newDay = day !== lastDay;
				const head = newDay || !last || last.author !== m.author || last.kind !== m.kind ||
					posted.getTime() - new Date(last.postedAt).getTime() > GROUP_WINDOW_MS;
				// A text that is only the card's summary is said by the card.
				const cardSays = !!m.card && m.card.accessible && m.card.bodyFromCard;
				rows.push({
					key: m.id,
					m,
					day: newDay ? dayLabel.format(posted) : '',
					head,
					focus: m.id === this.focusedId,
					time: time.format(posted),
					fullTime: full.format(posted),
					html: (m.deleted || cardSays) ? '' : renderChatMarkdown(m.body, { mentions: m.mentions, me: this.api?.userId }),
				});
				lastDay = day;
				last = m;
			}
			return rows;
		},
	},
	watch: {
		conversation(this: any, newVal: ChatRef | null) {
			if (chatRefKey(newVal) !== this._.key) {
				this.open();
			}
		},
		// The props arrive one at a time, in no fixed order: the conversation may
		// be named before the services are there to read it with.
		api(this: any) {
			this.openIfIdle();
		},
		reload(this: any) {
			if (!this.info) return;
			this.reloadInfo();
			this.refresh();
		},
		localization(this: any) {
			this.announceCards('localization', this.cardLocalization());
		},
		// The host names another message to bring the reader to.
		focusMessage(this: any, id: string | null) {
			// Only within the conversation that is open: when the host names
			// another conversation at the same time, opening it brings the
			// reader to the message.
			if (id && this.info && !this.isLoading && chatRefKey(this.conversation) === this._.key) this.showMessage(id);
		},
	},
	methods: {
		t(this: any, messageId: string, params?: Record<string, any>, fallback?: string): string {
			return translate(this.localization || createLocalizationSnapshot(), undefined, messageId, params, fallback);
		},
		// The conversation is opened as soon as the element is set up (`mount`),
		// without waiting for the frame the `mounted` hook is called on: a window
		// that is not being painted gets no frames. `mounted` covers the props
		// that were not there yet.
		onMount(this: any) {
			installCardHost();
			this.watchTheme();
			this.openIfIdle();
		},
		onMounted(this: any) {
			this.openIfIdle();
		},
		/** Opens the conversation when it is named, can be read, and nothing was opened for it yet. */
		openIfIdle(this: any) {
			if (this.info || this.isLoading || this.errorMessage) return;
			if (chatRefKey(this.conversation) && this.api?.chat) this.open();
		},
		onUnmount(this: any) {
			this._.epoch++;
			this.stopWatching();
			this.closeMentions();
			if (this._.focusTimer) {
				clearTimeout(this._.focusTimer);
				this._.focusTimer = null;
			}
			if (this._.previewTimer) {
				clearTimeout(this._.previewTimer);
				this._.previewTimer = null;
			}
			if (this._.themeObserver) {
				this._.themeObserver.disconnect();
				this._.themeObserver = null;
			}
			for (const frame of Array.from(this._.cardFrames as Set<HTMLIFrameElement>)) {
				this.releaseCard(frame);
			}
		},

		// =====================================================================
		// Loading
		// =====================================================================

		/** Shows the conversation the host names, from its latest messages. */
		async open(this: any) {
			const vm = this;
			const ref = vm.conversation as ChatRef | null;
			const epoch = ++vm._.epoch;
			vm._.key = chatRefKey(ref);
			vm.stopWatching();
			vm.info = null;
			vm.messages = [];
			vm.hasMore = false;
			vm.hasMoreAfter = false;
			vm.focusedId = '';
			vm.errorMessage = '';
			vm.newBelow = false;
			vm.closeMentions();
			vm.sendError = '';
			vm.compose = makePending();
			vm.cardDraft = emptyCardDraft();
			vm.isDragOver = false;
			vm.cancelEdit();
			vm.confirmDeleteId = '';
			vm._.atBottom = true;
			if (!vm._.key || !vm.api?.chat) {
				return;
			}

			vm.isLoading = true;
			// Opened around a message the host names, or at the latest messages.
			const around = (vm.focusMessage as string | null) || '';
			try {
				const [info, page] = await Promise.all([
					vm.api.chat.getConversation(ref),
					vm.api.chat.listMessages(ref, around ? { around, first: PAGE_SIZE } : { first: PAGE_SIZE }),
				]);
				if (epoch !== vm._.epoch) return;
				vm.info = info;
				vm.messages = page.items;
				vm.hasMore = page.hasMore;
				vm.hasMoreAfter = page.hasMoreAfter;
				vm.loadAvatars(page.items);
				vm.startWatching(info.watchPath);
				vm.$emit('loaded', info);
				vm.emitActivity();
				vm.$nextTick(() => {
					if (around) {
						vm.markMessage(around);
					} else {
						vm.scrollToBottom();
						vm.markRead();
					}
					if (vm.focusOnOpen) vm.focusComposer();
				});
			} catch (e) {
				if (epoch !== vm._.epoch) return;
				vm.fail(e);
			} finally {
				if (epoch === vm._.epoch) vm.isLoading = false;
			}
		},
		fail(this: any, e: unknown) {
			console.warn('[wt-chat-thread]', e);
			this.stopWatching();
			this.info = null;
			this.messages = [];
			this.errorMessage = errorText(e);
			this.$emit('failed', { message: this.errorMessage });
		},
		/** Reads the settings again: the channel was renamed, archived, ... */
		async reloadInfo(this: any) {
			const vm = this;
			const epoch = vm._.epoch;
			try {
				const info = await vm.api.chat.getConversation(vm.conversation);
				if (epoch !== vm._.epoch) return;
				vm.info = info;
				vm.$emit('loaded', info);
			} catch (e) {
				// The conversation is gone, or the reader was taken out of it.
				if (epoch === vm._.epoch) vm.fail(e);
			}
		},
		/**
		 * Brings the reader to a message of the open conversation: scrolls to it
		 * when it is shown, otherwise reads the messages around it.
		 */
		async showMessage(this: any, id: string) {
			const vm = this;
			if ((vm.messages as ChatMessage[]).some((m) => m.id === id)) {
				vm.markMessage(id);
				return;
			}
			const epoch = vm._.epoch;
			try {
				const page = await vm.api.chat.listMessages(vm.conversation, { around: id, first: PAGE_SIZE });
				if (epoch !== vm._.epoch) return;
				vm.messages = page.items;
				vm.hasMore = page.hasMore;
				vm.hasMoreAfter = page.hasMoreAfter;
				vm.newBelow = false;
				vm.loadAvatars(page.items);
				vm.$nextTick(() => vm.markMessage(id));
			} catch (e) {
				console.warn('[wt-chat-thread] the message could not be shown:', e);
			}
		},
		/** Scrolls to the message and marks it for a moment. */
		markMessage(this: any, id: string) {
			const vm = this;
			const el = vm.$refs.scroll as HTMLElement | undefined;
			const row = el?.querySelector(`.wt-chat-msg[data-id="${id}"]`) as HTMLElement | null;
			if (row) {
				row.scrollIntoView({ block: 'center' });
			}
			vm._.atBottom = !!el && el.scrollHeight - el.scrollTop - el.clientHeight <= BOTTOM_SLACK_PX;
			vm.focusedId = id;
			if (vm._.focusTimer) clearTimeout(vm._.focusTimer);
			vm._.focusTimer = setTimeout(() => {
				vm._.focusTimer = null;
				if (vm.focusedId === id) vm.focusedId = '';
			}, FOCUS_MARK_MS);
		},
		/**
		 * Reads the latest messages again, as many as are shown, and puts them in
		 * place of what was shown: new messages appear, edited and deleted ones
		 * change. Messages further back than that stay as they are. While the
		 * reader is away from the end (newer messages beyond what is shown), what
		 * is shown is left alone and the reader is told there is more below.
		 */
		async refresh(this: any) {
			const vm = this;
			if (vm.hasMoreAfter) {
				vm.newBelow = true;
				return;
			}
			if (vm._.refreshing) {
				vm._.refreshAgain = true;
				return;
			}
			vm._.refreshing = true;
			const epoch = vm._.epoch;
			try {
				const shown = vm.messages as ChatMessage[];
				const first = Math.min(Math.max(shown.length, PAGE_SIZE), MAX_REFRESH);
				const page = await vm.api.chat.listMessages(vm.conversation, { first });
				if (epoch !== vm._.epoch) return;

				const lastBefore = shown.length ? shown[shown.length - 1].id : '';
				const firstId = page.items.length ? page.items[0].id : '';
				const older = firstId ? shown.filter((m) => m.id < firstId) : [];
				vm.messages = older.concat(page.items);
				if (!older.length) vm.hasMore = page.hasMore;
				vm.loadAvatars(page.items);
				vm.emitActivity();

				const latest = page.items.length ? page.items[page.items.length - 1] : null;
				if (latest && latest.id !== lastBefore && latest.id > lastBefore) {
					if (vm._.atBottom || latest.mine) {
						vm.$nextTick(() => {
							vm.scrollToBottom();
							vm.markRead();
						});
					} else {
						vm.newBelow = true;
					}
				}
			} catch (e) {
				console.warn('[wt-chat-thread] refresh failed:', e);
			} finally {
				vm._.refreshing = false;
				if (vm._.refreshAgain) {
					vm._.refreshAgain = false;
					if (epoch === vm._.epoch) vm.scheduleRefresh();
				}
			}
		},
		async loadOlder(this: any) {
			const vm = this;
			const shown = vm.messages as ChatMessage[];
			if (!vm.hasMore || vm.isLoadingOlder || !shown.length) return;
			vm.isLoadingOlder = true;
			const epoch = vm._.epoch;
			try {
				const page = await vm.api.chat.listMessages(vm.conversation, { before: shown[0].id, first: PAGE_SIZE });
				if (epoch !== vm._.epoch) return;
				const el = vm.$refs.scroll as HTMLElement | undefined;
				const height = el ? el.scrollHeight : 0;
				const top = el ? el.scrollTop : 0;
				const have = new Set((vm.messages as ChatMessage[]).map((m) => m.id));
				vm.messages = page.items.filter((m: ChatMessage) => !have.has(m.id)).concat(vm.messages);
				vm.hasMore = page.hasMore;
				vm.loadAvatars(page.items);
				// Keep what the reader was looking at where it was.
				vm.$nextTick(() => {
					if (el) el.scrollTop = top + (el.scrollHeight - height);
				});
			} catch (e) {
				console.warn('[wt-chat-thread] older messages could not be loaded:', e);
			} finally {
				if (epoch === vm._.epoch) vm.isLoadingOlder = false;
			}
		},
		/** Reads the messages after the newest shown, when the reader is away from the end. */
		async loadNewer(this: any) {
			const vm = this;
			const shown = vm.messages as ChatMessage[];
			if (!vm.hasMoreAfter || vm.isLoadingNewer || !shown.length) return;
			vm.isLoadingNewer = true;
			const epoch = vm._.epoch;
			try {
				const page = await vm.api.chat.listMessages(vm.conversation, { after: shown[shown.length - 1].id, first: PAGE_SIZE });
				if (epoch !== vm._.epoch) return;
				const have = new Set((vm.messages as ChatMessage[]).map((m) => m.id));
				vm.messages = vm.messages.concat(page.items.filter((m: ChatMessage) => !have.has(m.id)));
				vm.hasMoreAfter = page.hasMoreAfter;
				vm.loadAvatars(page.items);
				if (!vm.hasMoreAfter && vm.newBelow) {
					// Caught up: whatever arrived meanwhile is read in the usual way.
					vm.newBelow = false;
					vm.scheduleRefresh();
				}
			} catch (e) {
				console.warn('[wt-chat-thread] newer messages could not be loaded:', e);
			} finally {
				if (epoch === vm._.epoch) vm.isLoadingNewer = false;
			}
		},
		emitActivity(this: any) {
			const shown = this.messages as ChatMessage[];
			if (shown.length) {
				this.$emit('activity', { lastMessageAt: shown[shown.length - 1].postedAt });
			}
		},
		async loadAvatars(this: any, messages: ChatMessage[]) {
			const vm = this;
			for (const m of messages) {
				const id = m.author;
				if (!id || vm.avatars[id]) continue;
				let url = avatarCache.get(id);
				if (!url) {
					url = new Identicon(await sha256Hex(id), {
						background: [245, 245, 245, 255],
						margin: 0.2,
						format: 'svg',
					}).dataURL as string;
					avatarCache.set(id, url);
				}
				vm.avatars[id] = url;
			}
		},

		// =====================================================================
		// Live updates
		// =====================================================================

		startWatching(this: any, watchPath: string) {
			const vm = this;
			const hub = vm.api?.eventHub;
			if (!hub || !watchPath) return;
			vm._.watchPath = watchPath;
			vm._.unwatch = hub.watchNode(watchPath, (event: { path?: string | null }) => {
				// A message lies under messages/; anything else is the channel
				// itself: its settings, or who may read it. For the conversation
				// of a file every mark is a change to the messages.
				const path = event?.path || '';
				if (vm.info?.fileId || path.startsWith(`${watchPath}/messages/`)) {
					vm.scheduleRefresh();
				} else {
					vm.reloadInfo();
				}
			}, true);
		},
		stopWatching(this: any) {
			if (this._.unwatch) {
				try { this._.unwatch(); } catch { /* ignore */ }
				this._.unwatch = null;
			}
			if (this._.refreshTimer) {
				clearTimeout(this._.refreshTimer);
				this._.refreshTimer = null;
			}
			this._.refreshAgain = false;
		},
		scheduleRefresh(this: any) {
			const vm = this;
			if (vm._.refreshTimer) clearTimeout(vm._.refreshTimer);
			vm._.refreshTimer = setTimeout(() => {
				vm._.refreshTimer = null;
				vm.refresh();
			}, REFRESH_DELAY_MS);
		},

		// =====================================================================
		// Reading
		// =====================================================================

		onScroll(this: any, event: Event) {
			const el = event.target as HTMLElement;
			const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight <= BOTTOM_SLACK_PX;
			this._.atBottom = atBottom;
			if (atBottom && this.hasMoreAfter) {
				this.loadNewer();
			} else if (atBottom && this.newBelow) {
				this.newBelow = false;
				this.markRead();
			}
			if (el.scrollTop <= TOP_SLACK_PX) {
				this.loadOlder();
			}
		},
		scrollToBottom(this: any) {
			const el = this.$refs.scroll as HTMLElement | undefined;
			if (el) {
				el.scrollTop = el.scrollHeight;
			}
			this._.atBottom = true;
			this.newBelow = false;
		},
		/** Back to the end of the conversation: as it is now when the reader was away from it. */
		async jumpToLatest(this: any) {
			const vm = this;
			if (vm.hasMoreAfter) {
				const epoch = vm._.epoch;
				try {
					const page = await vm.api.chat.listMessages(vm.conversation, { first: PAGE_SIZE });
					if (epoch !== vm._.epoch) return;
					vm.messages = page.items;
					vm.hasMore = page.hasMore;
					vm.hasMoreAfter = false;
					vm.loadAvatars(page.items);
					vm.emitActivity();
					vm.$nextTick(() => {
						vm.scrollToBottom();
						vm.markRead();
					});
				} catch (e) {
					console.warn('[wt-chat-thread] the latest messages could not be loaded:', e);
				}
				return;
			}
			vm.scrollToBottom();
			vm.markRead();
		},
		/** Moves the read position to now, when there is something not yet read. */
		async markRead(this: any) {
			const vm = this;
			const info = vm.info as ChatConversation | null;
			const shown = vm.messages as ChatMessage[];
			// Away from the end, what is shown is not the latest: nothing is read up to.
			if (!info || !shown.length || vm.hasMoreAfter) return;
			const latest = new Date(shown[shown.length - 1].postedAt).getTime();
			if (info.readAt && new Date(info.readAt).getTime() >= latest) return;
			const epoch = vm._.epoch;
			try {
				const readAt = await vm.api.chat.markRead(vm.conversation);
				if (epoch !== vm._.epoch || !vm.info) return;
				vm.info.readAt = readAt;
				vm.$emit('read', { readAt });
			} catch (e) {
				console.warn('[wt-chat-thread] the read position could not be saved:', e);
			}
		},

		// =====================================================================
		// Posting
		// =====================================================================

		focusComposer(this: any) {
			(this.$refs.composer as HTMLTextAreaElement | undefined)?.focus();
		},
		onCompositionStart(this: any) {
			this._.composing = true;
		},
		onCompositionEnd(this: any) {
			this._.composing = false;
		},
		/** Enter posts; Shift+Enter starts a new line. Enter that ends an IME conversion does neither. */
		onComposerKeydown(this: any, event: KeyboardEvent) {
			if (this.mention.open && this.onMentionKeydown(event)) return;
			if (event.key !== 'Enter' || event.shiftKey || event.ctrlKey || event.altKey || event.metaKey) return;
			if (event.isComposing || this._.composing || event.keyCode === 229) return;
			event.preventDefault();
			this.send();
		},
		onComposerInput(this: any) {
			this.growComposer();
			this.trackMention();
		},

		// =====================================================================
		// @mentions
		// =====================================================================

		/** Whether an @name is being typed before the cursor; if so, looks the users up. */
		trackMention(this: any) {
			const vm = this;
			const el = vm.$refs.composer as HTMLTextAreaElement | undefined;
			if (!el || !vm.canAttach) {
				vm.closeMentions();
				return;
			}
			const upToCaret = el.value.slice(0, el.selectionStart ?? el.value.length);
			// What follows the @ is looked up by name, in any script: "@福田" finds
			// the user by display name; what is put in is the user's id.
			const m = /(^|[^\w@])@([^\s@]*)$/.exec(upToCaret);
			if (!m) {
				vm.closeMentions();
				return;
			}
			vm.mention.query = m[2] || '';
			vm.mention.start = upToCaret.length - (m[2] || '').length - 1;
			vm.mention.open = true;
			if (vm._.mentionTimer) clearTimeout(vm._.mentionTimer);
			vm._.mentionTimer = setTimeout(() => {
				vm._.mentionTimer = null;
				vm.searchMentions();
			}, MENTION_DELAY_MS);
		},
		async searchMentions(this: any) {
			const vm = this;
			const query = vm.mention.query as string;
			const seq = ++vm._.mentionSeq;
			try {
				const found = await vm.api.content.searchPrincipals(query, 0, MENTION_SUGGESTIONS * 2);
				if (seq !== vm._.mentionSeq || !vm.mention.open) return;
				vm.mention.items = found
					.filter((p: any) => !p.isGroup && !p.isService)
					.slice(0, MENTION_SUGGESTIONS)
					.map((p: any) => ({ id: p.identifier, name: p.displayName || p.identifier }));
				vm.mention.index = 0;
			} catch (e) {
				if (seq === vm._.mentionSeq) vm.mention.items = [];
			}
		},
		closeMentions(this: any) {
			this.mention.open = false;
			this.mention.items = [];
			this.mention.query = '';
			this.mention.start = -1;
			if (this._.mentionTimer) {
				clearTimeout(this._.mentionTimer);
				this._.mentionTimer = null;
			}
		},
		/** The keys that work the suggestions; true when the key was taken. */
		onMentionKeydown(this: any, event: KeyboardEvent): boolean {
			const items = this.mention.items as { id: string }[];
			if (event.key === 'Escape') {
				event.preventDefault();
				this.closeMentions();
				return true;
			}
			if (!items.length) return false;
			if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
				event.preventDefault();
				const n = items.length;
				this.mention.index = (this.mention.index + (event.key === 'ArrowDown' ? 1 : n - 1)) % n;
				return true;
			}
			if ((event.key === 'Enter' || event.key === 'Tab') && !event.isComposing && event.keyCode !== 229) {
				event.preventDefault();
				this.pickMention(items[this.mention.index]);
				return true;
			}
			return false;
		},
		/** Puts the user into the message in place of what was typed after the @. */
		pickMention(this: any, user: { id: string }) {
			const vm = this;
			const el = vm.$refs.composer as HTMLTextAreaElement | undefined;
			const start = vm.mention.start as number;
			if (!el || start < 0) {
				vm.closeMentions();
				return;
			}
			const end = el.selectionStart ?? el.value.length;
			const inserted = `@${user.id} `;
			vm.draft = el.value.slice(0, start) + inserted + el.value.slice(end);
			vm.closeMentions();
			vm.$nextTick(() => {
				el.focus();
				const caret = start + inserted.length;
				el.setSelectionRange(caret, caret);
				vm.growComposer();
			});
		},
		growComposer(this: any) {
			const el = this.$refs.composer as HTMLTextAreaElement | undefined;
			if (!el) return;
			el.style.height = 'auto';
			el.style.height = `${Math.min(el.scrollHeight, COMPOSER_MAX_HEIGHT_PX)}px`;
		},
		async send(this: any) {
			const vm = this;
			const body = String(vm.draft || '').trim();
			if (!vm.canSend || !vm.canPost) return;
			vm.isSending = true;
			vm.sendError = '';
			const epoch = vm._.epoch;
			try {
				const content = vm.contentOf(vm.compose) as ChatMessageContent;
				const design = vm.cardDraft.design as ChatCardDesign | null;
				if (design) {
					content.card = { path: design.path, fields: vm.draftValues() };
				}
				const message = await vm.api.chat.postMessage(vm.conversation, body, content);
				if (epoch !== vm._.epoch) return;
				vm.draft = '';
				vm.compose = makePending();
				vm.cardDraft = emptyCardDraft();
				if (!(vm.messages as ChatMessage[]).some((m) => m.id === message.id)) {
					vm.messages = (vm.messages as ChatMessage[]).concat([message]);
				}
				vm.loadAvatars([message]);
				vm.emitActivity();
				vm.$emit('posted', message);
				vm.$nextTick(() => {
					vm.growComposer();
					vm.scrollToBottom();
					vm.markRead();
				});
			} catch (e) {
				if (epoch === vm._.epoch) vm.sendError = errorText(e);
			} finally {
				vm.isSending = false;
				vm.$nextTick(() => vm.focusComposer());
			}
		},

		// =====================================================================
		// Attaching and linking files
		// =====================================================================

		/** What is being written: the message under edit, or else the new one. */
		activePending(this: any): Pending {
			return this.editing.id ? this.editing.pending : this.compose;
		},
		/** How many attachments and links the message being written has so far. */
		carried(this: any, p: Pending): { attachments: number; links: number } {
			// Told apart by the draft, not by identity: reactive data hands out proxies.
			const editing = !!this.editing.id && p.draftId === this.editing.pending.draftId;
			const copies = p.files.filter((f) => f.mode === 'copy').length;
			return {
				attachments: p.uploads.length + p.uploading + copies + (editing ? this.editing.attachments.length : 0),
				links: p.files.length - copies + (editing ? this.editing.links.length : 0),
			};
		},
		contentOf(this: any, p: Pending): ChatMessageContent {
			return {
				draftId: p.draftId,
				uploads: p.uploads.map((u) => ({ key: u.key, name: u.name })),
				copies: p.files.filter((f) => f.mode === 'copy').map((f) => f.id),
				links: p.files.filter((f) => f.mode === 'link').map((f) => f.id),
			};
		},
		pickFiles(this: any) {
			(this.$refs.filePicker as HTMLInputElement | undefined)?.click();
		},
		onFilesPicked(this: any, event: Event) {
			const input = event.target as HTMLInputElement;
			const files = Array.from(input.files || []);
			// Cleared, so picking the same file again is a change again.
			input.value = '';
			this.uploadFiles(files);
		},
		/**
		 * Uploads files from the computer into the user's home. They are copied
		 * next to the message, and removed from the home, when it is posted.
		 */
		async uploadFiles(this: any, files: File[]) {
			const vm = this;
			if (!files.length || !vm.canAttach || !vm.canPost) return;
			const p = vm.activePending() as Pending;
			const content = vm.api.content;
			const folder = `/home/users/${vm.api.userId}/chat/uploads/${p.draftId}`;
			p.error = '';
			for (const file of files) {
				if (vm.carried(p).attachments >= MAX_ATTACHMENTS) {
					p.error = vm.t('webtop.chat.attach.tooMany', { max: MAX_ATTACHMENTS }, 'A message can carry up to {max} attachments.');
					break;
				}
				p.uploading++;
				try {
					const key = randomKey();
					const mimeType = file.type || 'application/octet-stream';
					const upload = await content.initiateMultipartUpload();
					await content.appendMultipartUploadData(upload.uploadId, file);
					await content.completeMultipartUpload(upload.uploadId, folder, key, mimeType, true);
					p.uploads = p.uploads.concat([{ key, name: file.name || 'file', mimeType, size: file.size }]);
				} catch (e) {
					p.error = vm.t('webtop.chat.attach.failed', { name: file.name, error: errorText(e) }, '{name} could not be attached: {error}');
				} finally {
					p.uploading--;
				}
			}
		},
		removeUpload(this: any, p: Pending, u: PendingUpload) {
			p.uploads = p.uploads.filter((x) => x.key !== u.key);
			// Left in the home it would stay until the message is posted.
			this.api.content.deleteNode(`/home/users/${this.api.userId}/chat/uploads/${p.draftId}/${u.key}`).catch(() => undefined);
		},
		/** Adds repository files dragged in from the Content Browser, as links. */
		async addRepositoryFiles(this: any, items: { path: string; name: string; isCollection?: boolean; mimeType?: string | null }[]) {
			const vm = this;
			if (!items.length || !vm.canAttach || !vm.canPost) return;
			const p = vm.activePending() as Pending;
			p.error = '';
			for (const item of items) {
				if (vm.carried(p).links >= MAX_LINKS) {
					p.error = vm.t('webtop.chat.link.tooMany', { max: MAX_LINKS }, 'A message can carry up to {max} links.');
					break;
				}
				try {
					// A link is kept by identifier, so it survives a move or a rename.
					const node = await vm.api.content.getNode(item.path);
					if (!node) continue;
					const linked = p.files.some((f) => f.id === node.id) ||
						(!!vm.editing.id && p.draftId === vm.editing.pending.draftId && vm.editing.links.some((l: ChatLink) => l.id === node.id));
					if (linked) continue;
					p.files = p.files.concat([{
						id: node.id,
						name: item.name,
						path: item.path,
						mimeType: item.mimeType || null,
						isCollection: !!item.isCollection,
						mode: 'link',
					}]);
				} catch (e) {
					p.error = errorText(e);
				}
			}
		},
		removeFile(this: any, p: Pending, f: PendingFile) {
			p.files = p.files.filter((x) => x.id !== f.id);
		},
		/** Switches a repository file between a link and an attached copy. */
		toggleFileMode(this: any, p: Pending, f: PendingFile) {
			if (f.isCollection) return;
			if (f.mode === 'link' && this.carried(p).attachments >= MAX_ATTACHMENTS) {
				p.error = this.t('webtop.chat.attach.tooMany', { max: MAX_ATTACHMENTS }, 'A message can carry up to {max} attachments.');
				return;
			}
			p.files = p.files.map((x) => (x.id === f.id ? { ...x, mode: x.mode === 'link' ? 'copy' : 'link' } : x));
		},
		isFileDrag(this: any, event: DragEvent): boolean {
			const types = Array.from(event.dataTransfer?.types || []);
			return types.includes('Files') || types.includes(WEBTOP_FILES) || types.includes(WEBTOP_FILE);
		},
		onDragOver(this: any, event: DragEvent) {
			if (!this.canAttach || !this.canPost || !this.isFileDrag(event)) return;
			// The files are for the message: the host around the thread (a memo
			// that takes dropped files itself) is not to see the drag.
			event.preventDefault();
			event.stopPropagation();
			if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy';
			this.isDragOver = true;
		},
		onDragLeave(this: any, event: DragEvent) {
			// Leaving for a child is not leaving.
			const to = event.relatedTarget as Node | null;
			if (to && (event.currentTarget as HTMLElement).contains(to)) return;
			this.isDragOver = false;
		},
		onDrop(this: any, event: DragEvent) {
			this.isDragOver = false;
			if (!this.canAttach || !this.canPost || !this.isFileDrag(event)) return;
			event.preventDefault();
			event.stopPropagation();
			const dt = event.dataTransfer!;
			const raw = dt.getData(WEBTOP_FILES) || dt.getData(WEBTOP_FILE);
			if (raw) {
				try {
					const parsed = JSON.parse(raw);
					this.addRepositoryFiles(Array.isArray(parsed) ? parsed : [parsed]);
				} catch (e) {
					console.warn('[wt-chat-thread] unreadable drop:', e);
				}
				return;
			}
			this.uploadFiles(Array.from(dt.files || []));
		},
		/** A file on the clipboard (a screenshot, a copied file) is attached. */
		onPaste(this: any, event: ClipboardEvent) {
			const files = Array.from(event.clipboardData?.files || []);
			if (!files.length || !this.canAttach) return;
			event.preventDefault();
			this.uploadFiles(files);
		},

		// =====================================================================
		// Attachments and links of a message
		// =====================================================================

		fileIcon(this: any, f: { mimeType?: string | null; isCollection?: boolean | null }): string {
			return getFileIcon({ mimeType: f.mimeType || '', isCollection: !!f.isCollection } as any);
		},
		formatSize(this: any, size: number | null | undefined): string {
			if (size == null) return '';
			return Bytes.format(size, { short: true, zeroBytesDisplay: '0 B', locale: this.localization?.numberFormat || undefined });
		},
		isImage(this: any, a: ChatAttachment): boolean {
			return /^image\/(png|jpeg|gif|webp)$/.test(a.mimeType || '');
		},
		/**
		 * Where the attachment is fetched from. That of a channel is a file its
		 * participants can read. That of a file's conversation is read by nobody
		 * in the repository and is served by the Chat app's attachment.groovy.
		 */
		fileUrl(this: any, a: ChatAttachment, attachment = false): string {
			const fileId = this.info?.fileId;
			if (!fileId) {
				return downloadUrl(this.api?.workspace || '', a.path, !!attachment);
			}
			const messageId = /\/([0-9a-f]{20})\.files\/[^/]+$/.exec(a.path)?.[1] || '';
			const params = new URLSearchParams({ fileId, messageId, name: a.name });
			if (!attachment) params.set('inline', '1');
			return `${new URL('../chat/attachment.groovy', document.baseURI).pathname}?${params.toString()}`;
		},
		/** Opens the attachment in its editor; one no editor takes is downloaded. */
		openAttachment(this: any, a: ChatAttachment) {
			// An editor opens a file by its path, which only a channel's attachment has for the reader.
			if (!this.info?.fileId && openFileInEditor(a.path, a.mimeType)) return;
			const link = document.createElement('a');
			link.href = this.fileUrl(a, true);
			link.download = a.name;
			document.body.appendChild(link);
			link.click();
			link.remove();
		},
		folderOf(this: any, path: string | null): string {
			if (!path) return '';
			const index = path.lastIndexOf('/');
			return index > 0 ? path.substring(0, index) : '/';
		},
		/** Opens what is linked, where it lies: a link is never a copy. */
		openLink(this: any, l: ChatLink) {
			if (!l.accessible || !l.path) return;
			if (l.isCollection || !openFileInEditor(l.path, l.mimeType)) {
				revealInContentBrowser(l.path, !!l.isCollection);
			}
		},
		revealLink(this: any, l: ChatLink) {
			if (l.accessible && l.path) revealInContentBrowser(l.path, !!l.isCollection);
		},

		// =====================================================================
		// Cards shown on messages
		// =====================================================================

		/** Where the design's page is served from: the CMS HTML servlet, in the conversation's workspace. */
		cardUrl(this: any, card: ChatCard | null): string {
			if (!card) return 'about:blank';
			const workspace = String(this.api?.workspace || '');
			const base = `/bin/cms.cgi${workspace ? '/' + workspace.replace(/^\//, '') : ''}${card.path}/card.html`;
			return card.version ? `${base}?v=${card.version}` : base;
		},
		/**
		 * Gives the frame of a card what its page will ask for. Called when the
		 * frame is set up, before its page can run; the page's own `connect`
		 * finds the frame it is in. `m` is null for the composer's preview.
		 */
		bindCard(this: any, ctx: { element: HTMLElement }, m: ChatMessage | null) {
			const frame = ctx?.element as HTMLIFrameElement | undefined;
			if (!frame || frame.tagName !== 'IFRAME') return;
			const listeners = new Set<CardListener>();
			cardSlots.set(frame, { bridge: this.buildCardBridge(frame, m ? m.id : null, listeners), listeners });
			this._.cardFrames.add(frame);
		},
		unbindCard(this: any, ctx: { element: HTMLElement }) {
			const frame = ctx?.element as HTMLIFrameElement | undefined;
			if (frame) this.releaseCard(frame);
		},
		releaseCard(this: any, frame: HTMLIFrameElement) {
			const observer = this._.cardObservers.get(frame) as ResizeObserver | undefined;
			if (observer) {
				observer.disconnect();
				this._.cardObservers.delete(frame);
			}
			this._.cardFrames.delete(frame);
			cardSlots.delete(frame);
		},
		buildCardBridge(this: any, frame: HTMLIFrameElement, messageId: string | null, listeners: Set<CardListener>): ChatCardBridge {
			const vm = this;
			let client: GraphQLClient | null = null;
			const message = (): ChatMessage | null => (messageId === null) ?
				null : ((vm.messages as ChatMessage[]).find((x) => x.id === messageId) || null);
			return {
				version: 1,
				get workspace(): string { return String(vm.api?.workspace || ''); },
				get webtopBaseUrl(): string { return new URL('../../', document.baseURI).href; },
				get preview(): boolean { return messageId === null; },
				get card(): { path: string; fields: ChatCardValues } {
					if (messageId === null) {
						const design = vm.cardDraft.design as ChatCardDesign | null;
						return { path: design ? design.path : '', fields: vm.draftValues() };
					}
					const card = message()?.card;
					return toPlainData({ path: card?.path || '', fields: card?.fields || {} });
				},
				get message(): Record<string, unknown> | null {
					const m = message();
					if (!m) return null;
					const { card, ...rest } = m;
					void card;
					return toPlainData(rest as unknown as Record<string, unknown>);
				},
				get conversation(): { channelId: string | null; fileId: string | null } {
					const ref = vm.conversation as ChatRef | null;
					return { channelId: ref?.channelId || null, fileId: ref?.fileId || null };
				},
				get currentUser(): { id: string } { return { id: String(vm.api?.userId || '') }; },
				get theme(): string { return vm.hostTheme(); },
				get localization(): { locale: string; timeZone: string } { return vm.cardLocalization(); },
				get graphql(): GraphQLClient {
					if (!client) client = createGraphQLClient(String(vm.api?.workspace || ''));
					return client;
				},
				translate(messageId: string, params?: Record<string, unknown>, fallback?: string): string {
					return vm.t(messageId, params, fallback);
				},
				subscribe(listener: CardListener): () => void {
					if (typeof listener !== 'function') return () => undefined;
					listeners.add(listener);
					return () => { listeners.delete(listener); };
				},
				resize(): void { vm.fitCard(frame); },
				openFile(path: string, mimeType?: string | null): boolean {
					return openFileInEditor(String(path || ''), mimeType ?? null);
				},
				openConversation(ref: { channelId?: string; fileId?: string }): boolean {
					return openConversationInChat(toPlainData(ref || {}));
				},
			};
		},
		/** The page of a card arrived: the frame takes the page's height, now and as it changes. */
		onCardLoad(this: any, event: Event) {
			const vm = this;
			const frame = event.target as HTMLIFrameElement;
			const previous = vm._.cardObservers.get(frame) as ResizeObserver | undefined;
			if (previous) previous.disconnect();
			vm.fitCard(frame);
			const doc = frame.contentDocument;
			const win = frame.contentWindow as (Window & { ResizeObserver?: typeof ResizeObserver }) | null;
			const Observer = win?.ResizeObserver || ResizeObserver;
			if (!doc?.documentElement || !Observer) return;
			const observer = new Observer(() => vm.fitCard(frame));
			observer.observe(doc.documentElement);
			if (doc.body) observer.observe(doc.body);
			vm._.cardObservers.set(frame, observer);
		},
		fitCard(this: any, frame: HTMLIFrameElement) {
			let height = 0;
			try {
				const root = frame.contentDocument?.documentElement;
				if (root) height = Math.ceil(root.getBoundingClientRect().height);
			} catch {
				return;
			}
			if (height <= 0) return;
			const next = `${Math.min(height, CARD_MAX_HEIGHT_PX)}px`;
			if (frame.style.height === next) return;
			frame.style.height = next;
			// A card that grew under the reader's view keeps the end in view.
			if (this._.atBottom && frame.closest('.wt-chat-scroll')) this.scrollToBottom();
		},
		hostTheme(this: any): string {
			return document.documentElement.dataset.theme || 'light';
		},
		cardLocalization(this: any): { locale: string; timeZone: string } {
			return {
				locale: this.localization?.locale || navigator.language || 'en',
				timeZone: this.localization?.timeZone || Dates.resolveTimeZone(null),
			};
		},
		/** Follows the host's theme, so the cards do too. */
		watchTheme(this: any) {
			const vm = this;
			if (vm._.themeObserver || typeof MutationObserver === 'undefined') return;
			vm._.themeObserver = new MutationObserver(() => vm.announceCards('theme', vm.hostTheme()));
			vm._.themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
		},
		/** Tells the pages of this thread's cards something changed. */
		announceCards(this: any, type: CardEventType, payload: unknown) {
			for (const frame of this._.cardFrames as Set<HTMLIFrameElement>) {
				const slot = cardSlots.get(frame);
				if (!slot) continue;
				for (const listener of slot.listeners) {
					try { listener(type, toPlainData(payload)); } catch (e) { console.warn('[wt-chat-thread] a card failed on', type, e); }
				}
			}
		},

		// =====================================================================
		// A card added to the new message
		// =====================================================================

		/** Offers the designs; the panel opens on the list, or on the card already chosen. */
		async openCards(this: any) {
			const vm = this;
			if (!vm.canPost) return;
			vm.cardDraft.open = true;
			if (vm.cardDraft.design || vm.cardDraft.loading) return;
			vm.cardDraft.loading = true;
			vm.cardDraft.error = '';
			try {
				vm.cardDraft.designs = await vm.api.chat.listCards();
			} catch (e) {
				vm.cardDraft.error = errorText(e);
			} finally {
				vm.cardDraft.loading = false;
			}
		},
		/** Closes the panel and takes the card off the message. */
		closeCards(this: any) {
			this.cardDraft = emptyCardDraft();
		},
		pickCard(this: any, design: ChatCardDesign) {
			this.cardDraft.design = design;
			this.cardDraft.values = {};
		},
		/** Back to the list, keeping nothing of what was typed. */
		changeCard(this: any) {
			this.cardDraft.design = null;
			this.cardDraft.values = {};
			this.openCards();
		},
		cardValue(this: any, f: ChatCardField): string {
			return this.cardDraft.values[f.key] || '';
		},
		setCardValue(this: any, f: ChatCardField, text: string) {
			this.cardDraft.values = { ...this.cardDraft.values, [f.key]: text };
			this.schedulePreview();
		},
		/** The values typed for a field, one per item of a multiple field. */
		cardItems(this: any, f: ChatCardField): string[] {
			const text = String(this.cardDraft.values[f.key] || '');
			if (f.multiple) return text.split(/[,\n]/).map((s) => s.trim()).filter((s) => s !== '');
			const one = text.trim();
			return one ? [one] : [];
		},
		hasChoice(this: any, f: ChatCardField, value: string): boolean {
			return this.cardItems(f).includes(value);
		},
		/** A multiple field with choices is a row of checkboxes. */
		toggleChoice(this: any, f: ChatCardField, value: string, checked: boolean) {
			const items = this.cardItems(f).filter((v: string) => v !== value);
			if (checked) items.push(value);
			this.setCardValue(f, items.join(', '));
		},
		cardStep(this: any, f: ChatCardField): string {
			return f.type === 'LONG' ? '1' : 'any';
		},
		isNumberField(this: any, f: ChatCardField): boolean {
			return f.type === 'LONG' || f.type === 'DOUBLE' || f.type === 'DECIMAL';
		},
		/**
		 * The values as they are posted, in the types of the fields, the way a
		 * dataset's cells are stored: numbers as numbers, DECIMAL as the digits
		 * typed, BOOLEAN as true, DATE (typed as a wall-clock time in the reader's
		 * time zone) as an ISO instant. What the server refuses, it says.
		 */
		draftValues(this: any): ChatCardValues {
			const design = this.cardDraft.design as ChatCardDesign | null;
			const out: ChatCardValues = {};
			if (!design) return out;
			const timeZone = this.localization?.timeZone || undefined;
			const convert = (f: ChatCardField, s: string): unknown => {
				switch (f.type) {
					case 'LONG': case 'DOUBLE': {
						const n = Number(s);
						return Number.isFinite(n) ? n : s;
					}
					case 'BOOLEAN': return s === 'true';
					case 'DATE': return Dates.fromZonedInputValue(s, timeZone)?.toISOString() ?? s;
					default: return s;
				}
			};
			for (const f of design.fields) {
				const items = this.cardItems(f).map((s: string) => convert(f, s));
				if (!items.length) continue;
				out[f.key] = f.multiple ? items : items[0];
			}
			return out;
		},
		/** Tells the preview of the values after the typing pauses. */
		schedulePreview(this: any) {
			const vm = this;
			if (vm._.previewTimer) clearTimeout(vm._.previewTimer);
			vm._.previewTimer = setTimeout(() => {
				vm._.previewTimer = null;
				const frame = vm.$refs.preview as HTMLIFrameElement | HTMLIFrameElement[] | undefined;
				const el = Array.isArray(frame) ? frame[0] : frame;
				const slot = el ? cardSlots.get(el) : undefined;
				if (!slot) return;
				if (slot.listeners.size) {
					const payload = toPlainData({ fields: vm.draftValues() });
					for (const listener of slot.listeners) {
						try { listener('card', payload); } catch (e) { console.warn('[wt-chat-thread] the preview failed:', e); }
					}
				} else {
					// A page that does not listen is loaded again with the new values.
					try { el!.contentWindow?.location.reload(); } catch { /* ignore */ }
				}
			}, CARD_PREVIEW_DELAY_MS);
		},

		// =====================================================================
		// Changing one's own messages
		// =====================================================================

		replaceMessage(this: any, message: ChatMessage) {
			this.messages = (this.messages as ChatMessage[]).map((m) => (m.id === message.id ? message : m));
		},
		startEdit(this: any, m: ChatMessage) {
			this.confirmDeleteId = '';
			this.editing = { ...emptyEditing(), id: m.id, text: m.body, attachments: m.attachments.slice(), links: m.links.slice() };
			this.$nextTick(() => {
				// Inside the v-for the ref may come as a list of one.
				const ref = this.$refs.editor as HTMLTextAreaElement | HTMLTextAreaElement[] | undefined;
				const el = Array.isArray(ref) ? ref[0] : ref;
				if (el) {
					el.focus();
					el.setSelectionRange(el.value.length, el.value.length);
				}
			});
		},
		cancelEdit(this: any) {
			this.editing = emptyEditing();
		},
		/** Takes an attachment, or a link, off the message being edited. */
		removeEditAttachment(this: any, a: ChatAttachment) {
			const e = this.editing;
			e.attachments = e.attachments.filter((x: ChatAttachment) => x.name !== a.name);
			e.removed = e.removed.concat([a.name]);
		},
		removeEditLink(this: any, l: ChatLink) {
			this.editing.links = this.editing.links.filter((x: ChatLink) => x.id !== l.id);
		},
		onEditKeydown(this: any, event: KeyboardEvent) {
			if (event.key === 'Escape') {
				event.preventDefault();
				event.stopPropagation();
				this.cancelEdit();
				return;
			}
			if (event.key !== 'Enter' || event.shiftKey || event.ctrlKey || event.altKey || event.metaKey) return;
			if (event.isComposing || this._.composing || event.keyCode === 229) return;
			event.preventDefault();
			this.saveEdit();
		},
		async saveEdit(this: any) {
			const vm = this;
			const e = vm.editing;
			const body = String(e.text || '').trim();
			if (!e.id || !vm.canSaveEdit) return;
			e.saving = true;
			e.error = '';
			try {
				const content = vm.contentOf(e.pending) as ChatMessageContent;
				content.links = e.links.map((l: ChatLink) => l.id).concat(content.links || []);
				content.removeAttachments = e.removed.slice();
				vm.replaceMessage(await vm.api.chat.editMessage(vm.conversation, e.id, body, content));
				vm.cancelEdit();
			} catch (err) {
				e.saving = false;
				e.error = errorText(err);
			}
		},
		askDelete(this: any, m: ChatMessage) {
			this.cancelEdit();
			this.confirmDeleteId = m.id;
		},
		cancelDelete(this: any) {
			this.confirmDeleteId = '';
		},
		async deleteMessage(this: any, m: ChatMessage) {
			const vm = this;
			vm.confirmDeleteId = '';
			try {
				vm.replaceMessage(await vm.api.chat.deleteMessage(vm.conversation, m.id));
			} catch (e) {
				vm.sendError = errorText(e);
			}
		},
	},
});
