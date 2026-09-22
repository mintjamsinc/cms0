/**
 * Mail GraphQL API.
 *
 * The mail of a user lives in the user's home in the system workspace, and the
 * Mail schema (/etc/graphql/webtop/mail) is deployed there, so every call goes
 * to the system workspace's endpoint whichever workspace the Webtop runs in.
 */

import { createGraphQLClient, type GraphQLClient } from '../../graphql/client.js';

export type Security = 'ssl' | 'starttls' | 'none';

export interface MailServer {
	host: string;
	port: number;
	security: Security;
	username: string | null;
	sameAuthentication?: boolean | null;
}

export interface MailSyncStatus {
	state: 'never' | 'idle' | 'syncing' | 'error';
	lastRun: string | null;
	lastSuccess: string | null;
	lastError: string | null;
	progressFolder: string | null;
	progressDone: number | null;
	progressTotal: number | null;
	more: boolean;
	fetchOlderDays: number | null;
	oldestDate: string | null;
}

export interface MailAccount {
	id: string;
	name: string;
	address: string;
	personal: string | null;
	color: string | null;
	enabled: boolean;
	incoming: MailServer;
	outgoing: MailServer;
	initialDays: number;
	intervalMinutes: number;
	hasIncomingPassword: boolean;
	hasOutgoingPassword: boolean;
	status: MailSyncStatus;
}

export interface MailAccountInput {
	id?: string;
	name: string;
	address: string;
	personal: string;
	color: string;
	enabled: boolean;
	incoming: { host: string; port: number; security: Security; username: string };
	outgoing: { host: string; port: number; security: Security; username: string; sameAuthentication: boolean };
	incomingPassword?: string;
	outgoingPassword?: string;
	initialDays: number;
	intervalMinutes: number;
}

export interface MailTestResult {
	ok: boolean;
	messages: string[];
	folders: string[];
}

export interface MailFilter {
	accountIds?: string[];
	role?: 'inbox' | 'sent' | null;
	view?: 'all' | 'unread' | 'flagged' | 'trash';
	text?: string;
	color?: string;
	tag?: string;
	locked?: boolean;
}

export interface MailSummary {
	id: string;
	accountId: string;
	folder: string;
	role: string;
	subject: string;
	from: string | null;
	fromAddress: string | null;
	to: string | null;
	preview: string | null;
	sentDate: string | null;
	receivedDate: string | null;
	size: number | null;
	hasAttachments: boolean;
	seen: boolean;
	flagged: boolean;
	answered: boolean;
	trashed: boolean;
	color: string | null;
	tags: string[];
	locked: boolean;
	error: string | null;
}

export interface MailAddress {
	name: string | null;
	address: string;
}

export interface MailAttachment {
	index: number;
	name: string;
	mimeType: string;
	inline: boolean;
	contentId: string | null;
}

export interface MailMessage {
	id: string;
	accountId: string;
	folder: string;
	role: string;
	subject: string;
	from: MailAddress[];
	to: MailAddress[];
	cc: MailAddress[];
	bcc: MailAddress[];
	replyTo: MailAddress[];
	sentDate: string | null;
	receivedDate: string | null;
	size: number | null;
	messageId: string | null;
	inReplyTo: string[];
	references: string[];
	text: string | null;
	html: string | null;
	attachments: MailAttachment[];
	hasAttachments: boolean;
	seen: boolean;
	flagged: boolean;
	answered: boolean;
	trashed: boolean;
	color: string | null;
	tags: string[];
	locked: boolean;
	error: string | null;
}

export interface MailLabels {
	colors: string[];
	tags: string[];
}

export type ComposeMode = 'new' | 'reply' | 'replyAll' | 'forward';
export type ComposeFormat = 'text' | 'html';

export interface MailDraftAttachment {
	/** Name of the uploaded file in the draft's folder; also set for forwarded ones. */
	key: string;
	name: string;
	mimeType: string;
	size: number | null;
	/** An attachment of a stored message, read from it when the draft is sent. */
	forwarded?: boolean;
	messageId?: string | null;
	index?: number | null;
}

export interface MailDraft {
	id: string;
	accountId: string;
	mode: ComposeMode;
	originalId: string | null;
	to: string | null;
	cc: string | null;
	bcc: string | null;
	subject: string | null;
	format: ComposeFormat;
	/** The body; with the html format, its text alternative. */
	text: string | null;
	html: string | null;
	inReplyTo: string[];
	references: string[];
	attachments: MailDraftAttachment[];
	created: string;
	modified: string;
}

export interface MailDraftInput {
	id?: string;
	accountId?: string;
	mode?: ComposeMode;
	originalId?: string | null;
	inReplyTo?: string[];
	references?: string[];
	to?: string;
	cc?: string;
	bcc?: string;
	subject?: string;
	format?: ComposeFormat;
	text?: string;
	html?: string;
	attachments?: {
		key?: string;
		name: string;
		mimeType?: string;
		size?: number | null;
		messageId?: string | null;
		index?: number | null;
	}[];
}

const SERVER_FIELDS ='host port security username sameAuthentication';
const ACCOUNT_FIELDS = `
	id name address personal color enabled initialDays intervalMinutes
	hasIncomingPassword hasOutgoingPassword
	incoming { ${SERVER_FIELDS} }
	outgoing { ${SERVER_FIELDS} }
	status {
		state lastRun lastSuccess lastError progressFolder progressDone progressTotal
		more fetchOlderDays oldestDate
	}
`;
const SUMMARY_FIELDS = `
	id accountId folder role subject from fromAddress to preview sentDate receivedDate
	size hasAttachments seen flagged answered trashed error color tags locked
`;
const ADDRESS_FIELDS = 'name address';
const DRAFT_FIELDS = `
	id accountId mode originalId to cc bcc subject format text html inReplyTo references
	created modified
	attachments { key name mimeType size forwarded messageId index }
`;

export class MailApi {
	#client: GraphQLClient;

	constructor() {
		this.#client = createGraphQLClient('system');
	}

	async listAccounts(): Promise<MailAccount[]> {
		const data = await this.#client.query<{ mailAccounts: MailAccount[] }>(
			`query { mailAccounts { ${ACCOUNT_FIELDS} } }`);
		return data.mailAccounts;
	}

	async saveAccount(input: MailAccountInput): Promise<MailAccount> {
		const data = await this.#client.mutation<{ saveMailAccount: MailAccount }>(
			`mutation ($input: MailAccountInput!) { saveMailAccount(input: $input) { ${ACCOUNT_FIELDS} } }`,
			{ input });
		return data.saveMailAccount;
	}

	async removeAccount(id: string): Promise<boolean> {
		const data = await this.#client.mutation<{ removeMailAccount: boolean }>(
			'mutation ($id: ID!) { removeMailAccount(id: $id) }', { id });
		return data.removeMailAccount;
	}

	async testAccount(input: MailAccountInput): Promise<MailTestResult> {
		const data = await this.#client.mutation<{ testMailAccount: MailTestResult }>(
			'mutation ($input: MailAccountInput!) { testMailAccount(input: $input) { ok messages folders } }',
			{ input });
		return data.testMailAccount;
	}

	async sync(accountId?: string): Promise<void> {
		await this.#client.mutation('mutation ($accountId: ID) { syncMail(accountId: $accountId) }',
			{ accountId: accountId ?? null });
	}

	async fetchOlder(accountId: string, days: number): Promise<void> {
		await this.#client.mutation('mutation ($accountId: ID!, $days: Int!) { fetchOlderMail(accountId: $accountId, days: $days) }',
			{ accountId, days });
	}

	async listMessages(filter: MailFilter, offset: number, limit: number): Promise<{ items: MailSummary[]; hasMore: boolean }> {
		const data = await this.#client.query<{ mailMessages: { items: MailSummary[]; hasMore: boolean } }>(
			`query ($filter: MailMessageFilter, $offset: Int, $limit: Int) {
				mailMessages(filter: $filter, offset: $offset, limit: $limit) { items { ${SUMMARY_FIELDS} } hasMore }
			}`,
			{ filter, offset, limit });
		return data.mailMessages;
	}

	async getMessage(id: string): Promise<MailMessage | null> {
		const data = await this.#client.query<{ mailMessage: MailMessage | null }>(
			`query ($id: ID!) {
				mailMessage(id: $id) {
					id accountId folder role subject sentDate receivedDate size messageId inReplyTo references text html
					hasAttachments seen flagged answered trashed error color tags locked
					from { ${ADDRESS_FIELDS} } to { ${ADDRESS_FIELDS} } cc { ${ADDRESS_FIELDS} }
					bcc { ${ADDRESS_FIELDS} } replyTo { ${ADDRESS_FIELDS} }
					attachments { index name mimeType inline contentId }
				}
			}`,
			{ id });
		return data.mailMessage;
	}

	async setFlags(ids: string[], flags: { seen?: boolean; flagged?: boolean }): Promise<number> {
		const data = await this.#client.mutation<{ setMailFlags: number }>(
			'mutation ($ids: [ID!]!, $seen: Boolean, $flagged: Boolean) { setMailFlags(ids: $ids, seen: $seen, flagged: $flagged) }',
			{ ids, seen: flags.seen ?? null, flagged: flags.flagged ?? null });
		return data.setMailFlags;
	}

	async setTrashed(ids: string[], trashed: boolean): Promise<number> {
		const data = await this.#client.mutation<{ trashMail: number }>(
			'mutation ($ids: [ID!]!, $trashed: Boolean!) { trashMail(ids: $ids, trashed: $trashed) }',
			{ ids, trashed });
		return data.trashMail;
	}

	async listLabels(): Promise<MailLabels> {
		const data = await this.#client.query<{ mailLabels: MailLabels }>(
			'query { mailLabels { colors tags } }');
		return data.mailLabels;
	}

	/** color: a color name, '' to remove it, undefined to leave it. */
	async setLabels(ids: string[], labels: { color?: string; addTags?: string[]; removeTags?: string[] }): Promise<number> {
		const data = await this.#client.mutation<{ setMailLabels: number }>(
			`mutation ($ids: [ID!]!, $color: String, $addTags: [String!], $removeTags: [String!]) {
				setMailLabels(ids: $ids, color: $color, addTags: $addTags, removeTags: $removeTags)
			}`,
			{ ids, color: labels.color ?? null, addTags: labels.addTags ?? [], removeTags: labels.removeTags ?? [] });
		return data.setMailLabels;
	}

	async setLocked(ids: string[], locked: boolean): Promise<number> {
		const data = await this.#client.mutation<{ setMailLocked: number }>(
			'mutation ($ids: [ID!]!, $locked: Boolean!) { setMailLocked(ids: $ids, locked: $locked) }', { ids, locked });
		return data.setMailLocked;
	}

	async listDrafts(): Promise<MailDraft[]> {
		const data = await this.#client.query<{ mailDrafts: MailDraft[] }>(`query { mailDrafts { ${DRAFT_FIELDS} } }`);
		return data.mailDrafts;
	}

	async getDraft(id: string): Promise<MailDraft | null> {
		const data = await this.#client.query<{ mailDraft: MailDraft | null }>(
			`query ($id: ID!) { mailDraft(id: $id) { ${DRAFT_FIELDS} } }`, { id });
		return data.mailDraft;
	}

	async saveDraft(input: MailDraftInput): Promise<MailDraft> {
		const data = await this.#client.mutation<{ saveMailDraft: MailDraft }>(
			`mutation ($input: MailDraftInput!) { saveMailDraft(input: $input) { ${DRAFT_FIELDS} } }`, { input });
		return data.saveMailDraft;
	}

	async removeDraft(id: string): Promise<boolean> {
		const data = await this.#client.mutation<{ removeMailDraft: boolean }>(
			'mutation ($id: ID!) { removeMailDraft(id: $id) }', { id });
		return data.removeMailDraft;
	}

	/** Sends the draft; returns the id of the copy kept in the Webtop. */
	async sendDraft(id: string): Promise<string | null> {
		const data = await this.#client.mutation<{ sendMailDraft: string | null }>(
			'mutation ($id: ID!) { sendMailDraft(id: $id) }', { id });
		return data.sendMailDraft;
	}

	async listRecipients(): Promise<MailAddress[]> {
		const data = await this.#client.query<{ mailRecipients: MailAddress[] }>(
			`query { mailRecipients { ${ADDRESS_FIELDS} } }`);
		return data.mailRecipients;
	}
}

/**
 * URL of attachment.groovy in the system workspace's copy of this app. It is
 * derived from this page's own address, so it follows wherever the Webtop is
 * mounted.
 */
export function attachmentUrl(id: string, options: { index?: number; raw?: boolean; inline?: boolean }): string {
	const path = window.location.pathname.replace(/^(\/bin\/cms\.cgi)\/[^/]+\//, '$1/system/');
	const base = path.replace(/[^/]*$/, 'attachment.groovy');
	const params = new URLSearchParams({ id });
	if (options.raw) params.set('raw', '1');
	if (options.index !== undefined) params.set('index', String(options.index));
	if (options.inline) params.set('inline', '1');
	return `${base}?${params.toString()}`;
}
