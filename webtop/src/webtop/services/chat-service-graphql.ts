/**
 * Chat service: channels and their messages, through the Chat GraphQL schema
 * (/etc/graphql/webtop/chat). A conversation lives in the workspace it was
 * started in, so the service works on the endpoint of the client it is given.
 *
 * There is no subscription of its own: a conversation is watched with the
 * platform's nodeChanged on its `watchPath` (see <wt-chat-thread>).
 */

import type { GraphQLClient } from '../graphql/client.js';

/** Names a conversation: a channel or the conversation of a file. */
export interface ChatRef {
	channelId?: string;
	fileId?: string;
}

/** dm: the direct messages of two users, shown under the other user's name. */
export type ChatChannelKind = 'public' | 'private' | 'dm';

export interface ChatChannel {
	id: string;
	title: string;
	description: string | null;
	kind: ChatChannelKind;
	archived: boolean;
	isAdmin: boolean;
	following: boolean;
	/** For a dm: the other user. */
	peerId: string | null;
	watchPath: string;
	lastMessageAt: string | null;
	readAt: string | null;
}

export interface ChatMember {
	id: string;
	displayName: string | null;
	isGroup: boolean;
	isAdmin: boolean;
}

/** The file or folder a conversation is about. */
export interface ChatFile {
	id: string;
	name: string;
	path: string;
	mimeType: string | null;
	isCollection: boolean;
}

export interface ChatConversation {
	channelId: string | null;
	fileId: string | null;
	channel: ChatChannel | null;
	/** Set for the conversation of a file. */
	file: ChatFile | null;
	members: ChatMember[];
	canPost: boolean;
	following: boolean;
	watchPath: string;
	lastMessageAt: string | null;
	readAt: string | null;
}

/** A file copied next to the message when it was posted. */
export interface ChatAttachment {
	name: string;
	path: string;
	mimeType: string | null;
	size: number | null;
}

/**
 * A reference to a file or folder, as the reader's own session sees it. When
 * the reader cannot read what is linked, only `id` and `accessible` are set.
 */
export interface ChatLink {
	id: string;
	accessible: boolean;
	name: string | null;
	path: string | null;
	mimeType: string | null;
	isCollection: boolean | null;
}

export type ChatCardFieldType = 'STRING' | 'LONG' | 'DOUBLE' | 'DECIMAL' | 'BOOLEAN' | 'DATE';

export interface ChatCardChoice {
	value: string;
	label: string;
	/** A swatch of the shared palette (lib/color-palette), or null. */
	color: string | null;
}

/** A field of a card design, declared as a column of a dataset is. */
export interface ChatCardField {
	key: string;
	label: string;
	description: string | null;
	type: ChatCardFieldType;
	multiple: boolean;
	required: boolean;
	choices: ChatCardChoice[];
}

/**
 * A card design: a folder under /etc/chat/cards holding card.yml (the fields)
 * and card.html (the page that shows the card).
 */
export interface ChatCardDesign {
	path: string;
	name: string;
	label: string;
	description: string | null;
	fields: ChatCardField[];
}

/** The values of a card's fields, by key; a multiple field holds a list. */
export type ChatCardValues = Record<string, unknown>;

/**
 * The card a message carries: the design's path and the values of its fields.
 * The design is resolved in the reader's own session; when the reader cannot
 * read it, `accessible` is false and only the message's text is shown.
 */
export interface ChatCard {
	path: string;
	accessible: boolean;
	label: string | null;
	/** When the design's page was last changed, to load it fresh. */
	version: number | null;
	fields: ChatCardValues | null;
	/** Whether the message's text is the design's summary, written because none was given. */
	bodyFromCard: boolean;
}

/** What a message carries besides its text. */
export interface ChatMessageContent {
	/** The folder under /home/users/<user>/chat/uploads the uploads are in. */
	draftId?: string;
	/** Files uploaded for this message: the name they were uploaded under, and the name to show. */
	uploads?: { key: string; name: string }[];
	/** Identifiers of repository files to attach a copy of. */
	copies?: string[];
	/** Identifiers of files or folders to link. On an edit, the full list. */
	links?: string[];
	/** On an edit: the names of the attachments to take off. */
	removeAttachments?: string[];
	/** On a post: a card design and the values of its fields. */
	card?: { path: string; fields: ChatCardValues };
}

export interface ChatMessage {
	id: string;
	author: string | null;
	authorName: string | null;
	postedAt: string;
	editedAt: string | null;
	deleted: boolean;
	kind: 'user' | 'system';
	body: string;
	mine: boolean;
	/** The user ids written as @name in the body. */
	mentions: string[];
	attachments: ChatAttachment[];
	links: ChatLink[];
	card: ChatCard | null;
}

export interface ChatMessagePage {
	items: ChatMessage[];
	/** Older messages before the page. */
	hasMore: boolean;
	/** Newer messages after the page. */
	hasMoreAfter: boolean;
}

/** A message found by a search, with the conversation it is in. */
export interface ChatSearchHit {
	channelId: string | null;
	fileId: string | null;
	channel: ChatChannel | null;
	file: ChatFile | null;
	message: ChatMessage;
}

export interface ChatSearchPage {
	items: ChatSearchHit[];
	hasMore: boolean;
	cursor: string | null;
}

const CHANNEL_FIELDS = 'id title description kind archived isAdmin following peerId watchPath lastMessageAt readAt';
const MEMBER_FIELDS = 'id displayName isGroup isAdmin';
const CONVERSATION_FIELDS = `
	channelId fileId canPost following watchPath lastMessageAt readAt
	channel { ${CHANNEL_FIELDS} }
	file { id name path mimeType isCollection }
	members { ${MEMBER_FIELDS} }
`;
const MESSAGE_FIELDS = `
	id author authorName postedAt editedAt deleted kind body mine mentions
	attachments { name path mimeType size }
	links { id accessible name path mimeType isCollection }
	card { path accessible label version fields bodyFromCard }
`;
const CARD_DESIGN_FIELDS = `
	path name label description
	fields { key label description type multiple required choices { value label color } }
`;

/** The reference as the schema takes it: one of the two ids, nothing else. */
function refInput(ref: ChatRef): ChatRef {
	return ref.channelId ? { channelId: ref.channelId } : { fileId: ref.fileId };
}

/** A key that tells two references apart. */
export function chatRefKey(ref: ChatRef | null | undefined): string {
	if (!ref) return '';
	if (ref.channelId) return `channel-${ref.channelId}`;
	if (ref.fileId) return `file-${ref.fileId}`;
	return '';
}

/** Whether the conversation has messages later than what was read. */
export function chatIsUnread(c: { lastMessageAt: string | null; readAt: string | null }): boolean {
	if (!c.lastMessageAt) return false;
	return !c.readAt || new Date(c.lastMessageAt).getTime() > new Date(c.readAt).getTime();
}

export class ChatServiceGraphQL {
	#client: GraphQLClient;

	constructor(client: GraphQLClient) {
		this.#client = client;
	}

	/** The channels of the sidebar. */
	async listChannels(): Promise<ChatChannel[]> {
		const data = await this.#client.query<{ chatChannels: ChatChannel[] }>(
			`query { chatChannels { ${CHANNEL_FIELDS} } }`);
		return data.chatChannels;
	}

	async findChannels(keyword: string, includeArchived = false): Promise<ChatChannel[]> {
		const data = await this.#client.query<{ chatPublicChannels: ChatChannel[] }>(
			`query ($keyword: String, $includeArchived: Boolean) {
				chatPublicChannels(keyword: $keyword, includeArchived: $includeArchived) { ${CHANNEL_FIELDS} }
			}`,
			{ keyword, includeArchived });
		return data.chatPublicChannels;
	}

	async getConversation(ref: ChatRef): Promise<ChatConversation> {
		const data = await this.#client.query<{ chatConversation: ChatConversation }>(
			`query ($ref: ChatRef!) { chatConversation(ref: $ref) { ${CONVERSATION_FIELDS} } }`,
			{ ref: refInput(ref) });
		return data.chatConversation;
	}

	/** The conversations of files in the sidebar, the most recently active first. */
	async listFollowedThreads(): Promise<ChatConversation[]> {
		const data = await this.#client.query<{ chatFollowedThreads: ChatConversation[] }>(
			`query { chatFollowedThreads { ${CONVERSATION_FIELDS} } }`);
		return data.chatFollowedThreads;
	}

	/** The latest messages, those before / after a message, or those around one; oldest first. */
	async listMessages(ref: ChatRef, options: { before?: string; after?: string; around?: string; first?: number } = {}): Promise<ChatMessagePage> {
		const data = await this.#client.query<{ chatMessages: ChatMessagePage }>(
			`query ($ref: ChatRef!, $before: ID, $after: ID, $around: ID, $first: Int) {
				chatMessages(ref: $ref, before: $before, after: $after, around: $around, first: $first) {
					items { ${MESSAGE_FIELDS} } hasMore hasMoreAfter
				}
			}`,
			{ ref: refInput(ref), before: options.before ?? null, after: options.after ?? null, around: options.around ?? null, first: options.first ?? 50 });
		return data.chatMessages;
	}

	/** Messages whose text contains every word, newest first. `after` is the cursor of the previous page. */
	async search(text: string, first = 20, after: string | null = null): Promise<ChatSearchPage> {
		const data = await this.#client.query<{ chatSearch: ChatSearchPage }>(
			`query ($text: String!, $first: Int, $after: String) {
				chatSearch(text: $text, first: $first, after: $after) {
					items { channelId fileId channel { ${CHANNEL_FIELDS} } file { id name path mimeType isCollection } message { ${MESSAGE_FIELDS} } }
					hasMore cursor
				}
			}`,
			{ text, first, after });
		return data.chatSearch;
	}

	/** The folder to watch for mentions of the caller. */
	async mentionWatchPath(): Promise<string> {
		const data = await this.#client.query<{ chatMentionWatchPath: string }>('query { chatMentionWatchPath }');
		return data.chatMentionWatchPath;
	}

	/** The card designs the caller can post, by label. */
	async listCards(): Promise<ChatCardDesign[]> {
		const data = await this.#client.query<{ chatCards: ChatCardDesign[] }>(
			`query { chatCards { ${CARD_DESIGN_FIELDS} } }`);
		return data.chatCards;
	}

	/** One design, or null when there is none at the path the caller can read. */
	async getCard(path: string): Promise<ChatCardDesign | null> {
		const data = await this.#client.query<{ chatCard: ChatCardDesign | null }>(
			`query ($path: String!) { chatCard(path: $path) { ${CARD_DESIGN_FIELDS} } }`, { path });
		return data.chatCard;
	}

	/** The direct messages with the user, created the first time. */
	async openDirectMessage(userId: string): Promise<ChatChannel> {
		const data = await this.#client.mutation<{ chatOpenDirectMessage: ChatChannel }>(
			`mutation ($userId: ID!) { chatOpenDirectMessage(userId: $userId) { ${CHANNEL_FIELDS} } }`, { userId });
		return data.chatOpenDirectMessage;
	}

	async createChannel(input: { title: string; description?: string; kind: ChatChannelKind }): Promise<ChatChannel> {
		const data = await this.#client.mutation<{ chatCreateChannel: ChatChannel }>(
			`mutation ($input: ChatChannelInput!) { chatCreateChannel(input: $input) { ${CHANNEL_FIELDS} } }`,
			{ input });
		return data.chatCreateChannel;
	}

	async updateChannel(channelId: string, input: { title?: string; description?: string; admins?: string[] }): Promise<ChatChannel> {
		const data = await this.#client.mutation<{ chatUpdateChannel: ChatChannel }>(
			`mutation ($channelId: ID!, $input: ChatChannelUpdateInput!) {
				chatUpdateChannel(channelId: $channelId, input: $input) { ${CHANNEL_FIELDS} }
			}`,
			{ channelId, input });
		return data.chatUpdateChannel;
	}

	async archiveChannel(channelId: string, archived: boolean): Promise<ChatChannel> {
		const data = await this.#client.mutation<{ chatArchiveChannel: ChatChannel }>(
			`mutation ($channelId: ID!, $archived: Boolean!) {
				chatArchiveChannel(channelId: $channelId, archived: $archived) { ${CHANNEL_FIELDS} }
			}`,
			{ channelId, archived });
		return data.chatArchiveChannel;
	}

	async addMembers(channelId: string, principals: string[]): Promise<ChatConversation> {
		const data = await this.#client.mutation<{ chatAddMembers: ChatConversation }>(
			`mutation ($channelId: ID!, $principals: [ID!]!) {
				chatAddMembers(channelId: $channelId, principals: $principals) { ${CONVERSATION_FIELDS} }
			}`,
			{ channelId, principals });
		return data.chatAddMembers;
	}

	async removeMembers(channelId: string, principals: string[]): Promise<ChatConversation> {
		const data = await this.#client.mutation<{ chatRemoveMembers: ChatConversation }>(
			`mutation ($channelId: ID!, $principals: [ID!]!) {
				chatRemoveMembers(channelId: $channelId, principals: $principals) { ${CONVERSATION_FIELDS} }
			}`,
			{ channelId, principals });
		return data.chatRemoveMembers;
	}

	async leaveChannel(channelId: string): Promise<boolean> {
		const data = await this.#client.mutation<{ chatLeaveChannel: boolean }>(
			'mutation ($channelId: ID!) { chatLeaveChannel(channelId: $channelId) }', { channelId });
		return data.chatLeaveChannel;
	}

	async postMessage(ref: ChatRef, body: string, content: ChatMessageContent = {}): Promise<ChatMessage> {
		const data = await this.#client.mutation<{ chatPostMessage: ChatMessage }>(
			`mutation ($ref: ChatRef!, $body: String!, $draftId: ID, $uploads: [ChatUploadInput!], $copies: [ID!], $links: [ID!], $card: ChatCardInput) {
				chatPostMessage(ref: $ref, body: $body, draftId: $draftId, uploads: $uploads, copies: $copies, links: $links, card: $card) { ${MESSAGE_FIELDS} }
			}`,
			{
				ref: refInput(ref),
				body,
				draftId: content.uploads?.length ? content.draftId : null,
				uploads: content.uploads ?? [],
				copies: content.copies ?? [],
				links: content.links ?? [],
				card: content.card ?? null,
			});
		return data.chatPostMessage;
	}

	/** Leaves the links of the message as they are when `content.links` is not given. */
	async editMessage(ref: ChatRef, messageId: string, body: string, content: ChatMessageContent = {}): Promise<ChatMessage> {
		const data = await this.#client.mutation<{ chatEditMessage: ChatMessage }>(
			`mutation ($ref: ChatRef!, $messageId: ID!, $body: String!, $draftId: ID, $uploads: [ChatUploadInput!], $copies: [ID!], $links: [ID!], $removeAttachments: [String!]) {
				chatEditMessage(ref: $ref, messageId: $messageId, body: $body, draftId: $draftId, uploads: $uploads, copies: $copies,
					links: $links, removeAttachments: $removeAttachments) { ${MESSAGE_FIELDS} }
			}`,
			{
				ref: refInput(ref),
				messageId,
				body,
				draftId: content.uploads?.length ? content.draftId : null,
				uploads: content.uploads ?? [],
				copies: content.copies ?? [],
				links: content.links ?? null,
				removeAttachments: content.removeAttachments ?? [],
			});
		return data.chatEditMessage;
	}

	async deleteMessage(ref: ChatRef, messageId: string): Promise<ChatMessage> {
		const data = await this.#client.mutation<{ chatDeleteMessage: ChatMessage }>(
			`mutation ($ref: ChatRef!, $messageId: ID!) {
				chatDeleteMessage(ref: $ref, messageId: $messageId) { ${MESSAGE_FIELDS} }
			}`,
			{ ref: refInput(ref), messageId });
		return data.chatDeleteMessage;
	}

	/** Remembers that the conversation was read up to now; returns that time. */
	async markRead(ref: ChatRef): Promise<string> {
		const data = await this.#client.mutation<{ chatMarkRead: string }>(
			'mutation ($ref: ChatRef!) { chatMarkRead(ref: $ref) }', { ref: refInput(ref) });
		return data.chatMarkRead;
	}

	async follow(ref: ChatRef): Promise<boolean> {
		const data = await this.#client.mutation<{ chatFollow: boolean }>(
			'mutation ($ref: ChatRef!) { chatFollow(ref: $ref) }', { ref: refInput(ref) });
		return data.chatFollow;
	}

	async unfollow(ref: ChatRef): Promise<boolean> {
		const data = await this.#client.mutation<{ chatUnfollow: boolean }>(
			'mutation ($ref: ChatRef!) { chatUnfollow(ref: $ref) }', { ref: refInput(ref) });
		return data.chatUnfollow;
	}
}
