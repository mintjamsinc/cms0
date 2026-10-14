// The picture shown for a user wherever one is: the photo set in Preferences,
// or an identicon of the user id when there is none.
//
// The photo is /home/users/<id>/avatar in the system workspace. A home is
// closed to the other users; the avatar is opened to every signed-in user,
// like the profile (UserHomeAccess on the server), so a reader sees the
// photos of the others. Resolved once per page and user; `forgetAvatar`
// when the reader's own photo changed.

import { createGraphQLClient } from '../graphql/client.js';
import { ContentServiceGraphQL } from './content-service-graphql.js';
import { sha256Hex } from './webtop-util.js';
import { Identicon } from '../lib/identicon.js';

const cache = new Map<string, Promise<string>>();

let systemContent: ContentServiceGraphQL | undefined;

export function avatarPath(userId: string): string {
	return `/home/users/${userId}/avatar`;
}

/** The URL of the user's picture: the photo, or an identicon. */
export function avatarURL(userId: string): Promise<string> {
	const id = userId || 'anonymous';
	let url = cache.get(id);
	if (!url) {
		url = resolve(id);
		cache.set(id, url);
		// Not kept when it failed: resolved again when asked again.
		url.catch(() => cache.delete(id));
	}
	return url;
}

/** Drops what was resolved for the user, so the next `avatarURL` reads it again. */
export function forgetAvatar(userId: string): void {
	cache.delete(userId || 'anonymous');
}

async function resolve(userId: string): Promise<string> {
	const photo = await photoURL(userId);
	return photo || identiconURL(userId);
}

/** The photo's download URL, with its modification time so a replaced photo is fetched anew; empty when there is none. */
async function photoURL(userId: string): Promise<string> {
	try {
		if (!systemContent) systemContent = new ContentServiceGraphQL(createGraphQLClient('system'));
		const node = await systemContent.getNode(avatarPath(userId));
		if (!node?.downloadUrl) return '';
		const ts = node.modified ? new Date(node.modified).getTime() : Date.now();
		return `${node.downloadUrl}?t=${ts}`;
	} catch {
		// Not readable, or no system workspace to ask: the identicon then.
		return '';
	}
}

async function identiconURL(userId: string): Promise<string> {
	return new Identicon(await sha256Hex(userId), {
		background: [245, 245, 245, 255],
		margin: 0.2,
		format: 'svg',
	}).dataURL as string;
}
