/**
 * Notice service: the notices of the notification center, kept per user on
 * the server through the notices GraphQL schema (/etc/graphql/webtop/notices),
 * so that the center shows the same list on every desktop the user opens
 * and a notice read or dismissed on one is read or dismissed on all.
 *
 * There is no subscription of its own: a client watches `watchPath` with
 * the platform's nodeChanged and reads the list again when something under
 * it changes (see "Notices" in index.ts).
 */

import type { GraphQLClient } from '../graphql/client.js';
import type { NoticeText } from '../lib/notifications.js';

/** A notice as the server keeps it; the texts are resolved for the reader by the shell. */
export interface WebtopNotice {
	id: string;
	title: string | NoticeText;
	body: string | NoticeText | null;
	/** The app (its folder name under apps/) a click opens; the i18n scope of the texts. */
	app: string | null;
	icon: string | null;
	options: Record<string, unknown> | null;
	context: string | null;
	key: string | null;
	read: boolean;
	/** When it was published (ISO 8601). */
	at: string;
}

const NOTICE_FIELDS = 'id title body app icon options context key read at';

export class NoticeServiceGraphQL {
	#client: GraphQLClient;

	constructor(client: GraphQLClient) {
		this.#client = client;
	}

	/** The caller's notices, newest first. */
	async list(): Promise<WebtopNotice[]> {
		const data = await this.#client.query<{ webtopNotices: WebtopNotice[] }>(
			`query { webtopNotices { ${NOTICE_FIELDS} } }`);
		return data.webtopNotices;
	}

	/** The folder to watch with nodeChanged for changes to the caller's list. */
	async watchPath(): Promise<string> {
		const data = await this.#client.query<{ webtopNoticeWatchPath: string }>('query { webtopNoticeWatchPath }');
		return data.webtopNoticeWatchPath;
	}

	async markRead(id: string): Promise<boolean> {
		const data = await this.#client.mutation<{ webtopNoticeRead: boolean }>(
			'mutation ($id: ID!) { webtopNoticeRead(id: $id) }', { id });
		return data.webtopNoticeRead;
	}

	async markAllRead(): Promise<boolean> {
		const data = await this.#client.mutation<{ webtopNoticesReadAll: boolean }>('mutation { webtopNoticesReadAll }');
		return data.webtopNoticesReadAll;
	}

	async remove(id: string): Promise<boolean> {
		const data = await this.#client.mutation<{ webtopNoticeRemove: boolean }>(
			'mutation ($id: ID!) { webtopNoticeRemove(id: $id) }', { id });
		return data.webtopNoticeRemove;
	}

	async clear(): Promise<boolean> {
		const data = await this.#client.mutation<{ webtopNoticesClear: boolean }>('mutation { webtopNoticesClear }');
		return data.webtopNoticesClear;
	}
}
