// Conversation block: a conversation shown inside a memo.
//
// The block is the same <wt-chat-thread> the Chat app and the Inspector show,
// live in the editor: messages appear as they are posted, and the box under
// them posts. It is an atom node, like the dataset block (dataset-view.ts),
// with a hand-written node view around the element.
//
// The element is an ichigo.js component, and a component is set up by the
// application that owns it. The node view is no such application, so each
// block mounts a small one of its own around its thread.
//
// The memo stores a reference only, never the messages:
//
//   <div data-chat-view></div>                    the conversation of this memo
//   <div data-chat-view data-file="<id>"></div>   the conversation of another file
//   <div data-chat-view data-channel="<id>"></div> a channel
//
// The conversation of the memo itself is stored without an identifier on
// purpose: a copy of the memo then shows the copy's own conversation, not the
// original's. An identifier is stored only for a conversation that is not the
// memo's own.
//
// Printing shows the messages on the page as they are and leaves out the box
// to post into (see the print rules in components/wt-chat-thread.css).

import { VDOM } from '@mintjamsinc/ichigojs';
import { Node as TiptapNode, mergeAttributes, type Editor } from '@tiptap/core';
import type { Node as PMNode } from '@tiptap/pm/model';
import type { NodeView } from '@tiptap/pm/view';
import { openConversationInChat } from '../../lib/open-file.js';
import type { ChatChannel, ChatConversation, ChatRef, ChatServiceGraphQL } from '../../services/chat-service-graphql.js';

export const CHAT_VIEW_NODE = 'chatView';

// How often a block that is not in the document yet (the editor of a tab that
// is not shown) looks whether it is by now.
const MOUNT_RETRY_MS = 250;

const WEBTOP_FILES = 'application/x-webtop-files';
const WEBTOP_FILE = 'application/x-webtop-file';

interface ChatViewAttrs {
	/** The identifier of the file whose conversation is shown; '' for the memo's own. */
	file: string;
	/** The channel shown instead, or ''. */
	channel: string;
}

/** What <wt-chat-thread> works with (see the component's `api` prop). */
export interface ChatThreadApi {
	chat: ChatServiceGraphQL;
	eventHub: unknown;
	content: { getNode(path: string): Promise<{ id: string } | null> };
	workspace: string;
	userId: string;
}

export interface ChatViewHost {
	/** The services of the thread; null until the app has launched. */
	api: () => ChatThreadApi | null;
	popup: () => any;
	t: (key: string, params?: Record<string, any>, fallback?: string) => string;
	/** The host's localization snapshot, handed to the thread as it is. */
	localization: () => unknown;
	/** The identifier of the memo this editor shows; '' while it was never saved. */
	memoId: () => string;
}

// The blocks on the page, so the app can have them look again when a memo gets
// its identifier (its first save).
const views = new Set<ChatNodeView>();

/** Has every conversation block work out its conversation again. */
export function refreshChatViews(): void {
	for (const view of views) view.render();
}

export function createChatViewExtension(host: ChatViewHost) {
	return TiptapNode.create({
		name: CHAT_VIEW_NODE,
		group: 'block',
		atom: true,
		// As for the dataset block: moved with the editor's drag handle, so that
		// selecting text in the block never starts a drag of the whole block.
		draggable: false,
		selectable: true,

		addAttributes() {
			return {
				file: { default: '', rendered: false },
				channel: { default: '', rendered: false },
			};
		},

		parseHTML() {
			return [{
				tag: 'div[data-chat-view]',
				getAttrs: (element) => {
					const el = element as HTMLElement;
					return {
						file: el.getAttribute('data-file') || '',
						channel: el.getAttribute('data-channel') || '',
					};
				},
			}];
		},

		// The reference only: the messages are never written into the memo.
		renderHTML({ node }) {
			const attrs = node.attrs as ChatViewAttrs;
			return ['div', mergeAttributes({
				'data-chat-view': '',
				'data-file': attrs.file || null,
				'data-channel': attrs.channel || null,
			})];
		},

		addNodeView() {
			return ({ node, getPos, editor }) => new ChatNodeView(host, editor as Editor, node, getPos as () => number | undefined);
		},
	});
}

function el<K extends keyof HTMLElementTagNameMap>(tag: K, className?: string, text?: string): HTMLElementTagNameMap[K] {
	const e = document.createElement(tag);
	if (className) e.className = className;
	if (text != null) e.textContent = text;
	return e;
}

class ChatNodeView implements NodeView {
	dom: HTMLElement;

	private node: PMNode;
	private readonly head: HTMLElement;
	private readonly icon: HTMLElement;
	private readonly title: HTMLElement;
	private readonly body: HTMLElement;
	// The application around the block's <wt-chat-thread>, while one is shown.
	private app: { bindings?: { set(key: string, value: any): void }; unmount(): void } | null = null;
	// The conversation the thread shows, as a key; '' while there is none.
	private key = '';
	private info: ChatConversation | null = null;
	// Set while the block waits to be in the document (see render).
	private mountTimer: ReturnType<typeof setTimeout> | null = null;
	private destroyed = false;

	constructor(
		private readonly host: ChatViewHost,
		private readonly editor: Editor,
		node: PMNode,
		private readonly getPos: () => number | undefined,
	) {
		this.node = node;
		this.dom = el('div', 'memo-chat');
		this.dom.setAttribute('data-chat-view', '');
		// The editor's own content is not editable here; what is inside is.
		this.dom.contentEditable = 'false';

		this.head = el('div', 'memo-chat-head');
		this.icon = el('i', 'bi bi-chat-dots');
		this.title = el('span', 'memo-chat-title');
		const open = el('button', 'btn-icon');
		open.type = 'button';
		open.title = this.t('app.memo.chat.openInChat', undefined, 'Open in Chat');
		open.appendChild(el('i', 'bi bi-box-arrow-up-right'));
		open.addEventListener('click', () => {
			const ref = this.ref();
			if (ref) openConversationInChat(ref);
		});
		const menu = el('button', 'btn-icon');
		menu.type = 'button';
		menu.title = this.t('app.memo.chat.choose', undefined, 'Choose the conversation');
		menu.appendChild(el('i', 'bi bi-three-dots'));
		menu.addEventListener('click', () => { void this.openMenu(menu); });
		const spacer = el('span', 'memo-chat-spacer');
		this.head.append(this.icon, this.title, spacer, open, menu);

		// A repository file dropped on the heading: the block shows that file's
		// conversation. (Dropped on the messages, it is linked in a message.)
		this.head.addEventListener('dragover', (e) => {
			const types = Array.from(e.dataTransfer?.types || []);
			if (!types.includes(WEBTOP_FILES) && !types.includes(WEBTOP_FILE)) return;
			e.preventDefault();
			e.stopPropagation();
			// Within what the Content Browser allows (copy or move): 'link' would be refused.
			if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy';
			this.head.classList.add('is-drop');
		});
		this.head.addEventListener('dragleave', () => this.head.classList.remove('is-drop'));
		this.head.addEventListener('drop', (e) => {
			this.head.classList.remove('is-drop');
			const raw = e.dataTransfer?.getData(WEBTOP_FILES) || e.dataTransfer?.getData(WEBTOP_FILE);
			if (!raw) return;
			e.preventDefault();
			e.stopPropagation();
			try {
				const parsed = JSON.parse(raw);
				const item = Array.isArray(parsed) ? parsed[0] : parsed;
				if (item?.path) void this.showFile(item.path);
			} catch { /* not ours */ }
		});

		this.body = el('div', 'memo-chat-body');
		this.dom.append(this.head, this.body);

		views.add(this);
		this.render();
	}

	private t(key: string, params?: Record<string, any>, fallback?: string): string {
		return this.host.t(key, params, fallback);
	}

	private get attrs(): ChatViewAttrs {
		return this.node.attrs as ChatViewAttrs;
	}

	/** The conversation the block is to show, or null while the memo has no identifier yet. */
	private ref(): ChatRef | null {
		if (this.attrs.channel) return { channelId: this.attrs.channel };
		const fileId = this.attrs.file || this.host.memoId();
		return fileId ? { fileId } : null;
	}

	private setAttrs(patch: Partial<ChatViewAttrs>): void {
		const pos = this.getPos();
		if (pos == null) return;
		this.editor.view.dispatch(this.editor.view.state.tr.setNodeMarkup(pos, undefined, { ...this.node.attrs, ...patch }));
	}

	private removeBlock(): void {
		const pos = this.getPos();
		if (pos == null) return;
		this.editor.view.dispatch(this.editor.view.state.tr.delete(pos, pos + this.node.nodeSize));
		this.editor.commands.focus();
	}

	/** Shows the conversation the attributes name, creating the thread the first time. */
	render(): void {
		const api = this.host.api();
		const ref = this.ref();
		const key = ref ? (ref.channelId ? `channel-${ref.channelId}` : `file-${ref.fileId}`) : '';
		if (!api || !ref) {
			// Nothing to show yet: a memo gets its conversation with its first save.
			this.key = '';
			this.info = null;
			this.unmountThread();
			this.body.replaceChildren(el('div', 'memo-chat-empty',
				this.t('app.memo.chat.unsaved', undefined, 'Save the memo to start its conversation.')));
			this.renderTitle();
			return;
		}
		if (!this.app && !this.dom.isConnected) {
			// A component is only set up inside the document, and a node view is
			// built before the editor puts it there: wait until it is in.
			this.renderTitle();
			if (!this.mountTimer && !this.destroyed) {
				this.mountTimer = setTimeout(() => {
					this.mountTimer = null;
					this.render();
				}, this.body.childElementCount ? MOUNT_RETRY_MS : 0);
				// What shows meanwhile also tells a retry from the first try.
				if (!this.body.childElementCount) this.body.replaceChildren(el('div', 'memo-chat-thread'));
			}
			return;
		}
		if (!this.app) {
			const view = this;
			const localization = this.host.localization();
			const mount = el('div', 'memo-chat-thread');
			mount.innerHTML = '<wt-chat-thread :conversation="conversation" :api="api" :localization="localization"'
				+ ' @loaded="onLoaded($event.detail)" @failed="onFailed"></wt-chat-thread>';
			this.body.replaceChildren(mount);
			this.key = key;
			this.info = null;
			this.app = VDOM.createApp({
				data(this: any) {
					// Marked raw: the services carry private fields a Proxy cannot call.
					return { conversation: ref, api: this.$markRaw(api), localization };
				},
				methods: {
					onLoaded(info: ChatConversation) {
						view.info = info;
						view.renderTitle();
					},
					onFailed() {
						view.info = null;
						view.renderTitle();
					},
				},
			}).mount(mount);
		} else if (key !== this.key) {
			this.key = key;
			this.info = null;
			// A new object: the thread opens the conversation it is handed.
			this.app.bindings?.set('conversation', ref);
		}
		this.renderTitle();
	}

	private unmountThread(): void {
		if (!this.app) return;
		try { this.app.unmount(); } catch { /* already gone */ }
		this.app = null;
	}

	private renderTitle(): void {
		const attrs = this.attrs;
		let icon = 'bi bi-chat-dots';
		let title: string;
		if (attrs.channel) {
			const channel = this.info?.channel;
			icon = channel?.kind === 'public' ? 'bi bi-hash' : 'bi bi-lock';
			title = channel?.title || this.t('app.memo.chat.channel', undefined, 'Channel');
		} else if (attrs.file) {
			icon = 'bi bi-file-earmark-text';
			title = this.info?.file?.name || this.t('app.memo.chat.file', undefined, 'Conversation of a file');
		} else {
			title = this.t('app.memo.chat.own', undefined, 'Conversation of this memo');
		}
		this.icon.className = icon;
		this.title.textContent = title;
		this.title.title = this.info?.file?.path || '';
	}

	/** Points the block at the conversation of the file at the path. */
	private async showFile(path: string): Promise<void> {
		const api = this.host.api();
		if (!api) return;
		try {
			const node = await api.content.getNode(path);
			if (!node) return;
			// The memo itself is its own conversation, kept without an identifier.
			this.setAttrs({ file: node.id === this.host.memoId() ? '' : node.id, channel: '' });
		} catch (e) {
			console.warn('[Memo] The conversation of the file could not be shown:', e);
		}
	}

	/** The conversations to choose from: the memo's own, and the channels of the sidebar. */
	private async openMenu(anchor: HTMLElement): Promise<void> {
		const api = this.host.api();
		const popup = this.host.popup();
		if (!api || !popup) return;
		let channels: ChatChannel[] = [];
		try {
			channels = await api.chat.listChannels();
		} catch (e) {
			console.warn('[Memo] The channels could not be listed:', e);
		}
		const attrs = this.attrs;
		const rect = anchor.getBoundingClientRect();
		const handle = popup.open({
			anchor: { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom, width: rect.width, height: rect.height },
			placement: 'bottom-end',
			minWidth: 240,
			maxHeight: 360,
			items: [
				{
					label: this.t('app.memo.chat.menu.memo', undefined, 'This memo'),
					emptyMessage: '',
					items: [{
						id: 'own',
						label: this.t('app.memo.chat.own', undefined, 'Conversation of this memo'),
						icon: 'bi bi-chat-dots',
						selected: !attrs.file && !attrs.channel,
					}],
				},
				{
					label: this.t('app.memo.chat.menu.channels', undefined, 'Channels'),
					emptyMessage: this.t('app.memo.chat.menu.noChannels', undefined, 'You are not in any channel.'),
					items: channels.map((c) => ({
						id: `channel:${c.id}`,
						label: c.title,
						icon: c.kind === 'public' ? 'bi bi-hash' : 'bi bi-lock',
						selected: attrs.channel === c.id,
					})),
				},
				{
					label: this.t('app.memo.chat.menu.block', undefined, 'Block'),
					items: [{
						id: 'remove',
						label: this.t('app.memo.chat.removeBlock', undefined, 'Remove block'),
						icon: 'bi bi-x-lg',
					}],
				},
			],
		});
		const picked = await handle.result;
		if (picked === 'remove') {
			this.removeBlock();
		} else if (picked === 'own') {
			this.setAttrs({ file: '', channel: '' });
		} else if (typeof picked === 'string' && picked.startsWith('channel:')) {
			this.setAttrs({ file: '', channel: picked.slice('channel:'.length) });
		}
	}

	update(node: PMNode): boolean {
		if (node.type !== this.node.type) return false;
		this.node = node;
		this.render();
		return true;
	}

	// What happens inside the block is the block's: typing a message is not
	// typing into the memo.
	stopEvent(): boolean {
		return true;
	}

	ignoreMutation(): boolean {
		return true;
	}

	selectNode(): void {
		this.dom.classList.add('is-selected');
	}

	deselectNode(): void {
		this.dom.classList.remove('is-selected');
	}

	destroy(): void {
		this.destroyed = true;
		views.delete(this);
		if (this.mountTimer) {
			clearTimeout(this.mountTimer);
			this.mountTimer = null;
		}
		this.unmountThread();
	}
}
