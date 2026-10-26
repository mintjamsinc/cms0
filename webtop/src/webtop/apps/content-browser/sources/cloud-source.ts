// The cloud side of the Content Browser's list: a location of a drive the
// user reaches through a linked account (see lib/cloud-location.ts), read
// through the cloud service a page at a time. The two listings above the
// drives — the provider's root (own drive / shared drives) and the shared
// drives themselves — are made here from cloudDrives; nothing else of them
// exists on the server.
//
// What the list shows is a ContentItem like the repository's, so the rows,
// the grid, the selection, the drag payload and the Inspector keep working;
// `cloud` carries what only a cloud entry has. Every item also carries the
// crumbs of the list it came from, so a click on a folder, the breadcrumbs
// and the Inspector's "Location" need no further request.

import type { CloudServiceGraphQL } from '../../../services/cloud-service-graphql.js';
import type { CloudDrive, CloudEntry, CloudOrder, CloudQuery } from '../../../graphql/types.js';
import type { InspectorTarget } from '../../../lib/inspector-target.js';
import { cloudPath, cloudProviderPath, cloudSharedPath, CLOUD_MY_DRIVE, type CloudLocation } from '../../../lib/cloud-location.js';

/** The folders above a listed location, and the location itself, as names and paths. */
export interface CloudCrumbs {
	provider: string;
	/** The provider as the user sees it (Google Drive, OneDrive). */
	providerName: string;
	/** The drive the location is in; null for the listings above the drives. */
	drive: CloudDrive | null;
	/** The listing the location is, when it is not a folder of a drive. */
	virtual: 'drives' | 'shared' | null;
	/** The folders between the drive's root and the location, top first. */
	ancestors: CloudEntry[];
	/** The folder listed; null at a drive's root and above. */
	current: CloudEntry | null;
	/** True while the ancestors are still being looked up (a cold landing). */
	pending: boolean;
}

/** What only a cloud entry has, on a ContentItem. */
export interface CloudItemInfo {
	provider: string;
	driveId: string;
	/** The provider's id; null for the row of a drive's root. */
	id: string | null;
	parentId: string | null;
	etag: string | null;
	webUrl: string | null;
	nativeType: string | null;
	hasThumbnail: boolean;
	owner: string | null;
	shared: boolean;
	contentUrl: string | null;
	/** A row of the listings above the drives: the two entry rows, a shared drive, or a drive's root. */
	virtual: 'drives' | 'shared' | 'drive' | null;
	/** The drive, for a drive row. */
	drive: CloudDrive | null;
	/** The crumbs of the list the item was read in. */
	crumbs: CloudCrumbs;
	/** Where the item is, as names: "Google Drive › My Drive › 2026 › a.pdf". Empty while a search result's folder is still being looked up. */
	location: string;
	/** Set on a search result, whose folder the listing does not tell: the folder it is in, as far as it is known. */
	search: CloudSearchHit | null;
}

/** The folder a search result is in. */
export interface CloudSearchHit {
	/** The folder's name; null when the entry has no folder the user can open (a file shared with a Google user, say). */
	folderName: string | null;
	/** The cloud path of the folder, when the entry has a parent. */
	folderPath: string | null;
	/** The crumbs of the folder, once looked up (Google tells only the parent's id). */
	folderCrumbs: CloudCrumbs | null;
	/** True while the folder's names are being looked up. */
	pending: boolean;
	/** True when the look-up failed: the location cannot be told. */
	failed: boolean;
}

/** The labels the virtual rows and the breadcrumbs need, already translated. */
export interface CloudLabels {
	providerName: (provider: string) => string;
	/** "My Drive" / "OneDrive": the row and crumb of the user's own drive. */
	myDrive: (provider: string) => string;
	/** "Shared drives" / "Shared libraries". */
	sharedDrives: (provider: string) => string;
}

const EMPTY_TARGET: Omit<InspectorTarget, 'id' | 'name' | 'path' | 'isCollection' | 'downloadURL' | 'created' | 'lastModified' | 'contentLength' | 'mimeType' | 'createdBy' | 'createdByDisplayName'> = {
	lastModifiedBy: '',
	lastModifiedByDisplayName: null,
	encoding: '',
	isLocked: false,
	lockInfo: null,
	isReferenceable: false,
	isVersionable: false,
	isCheckedOut: false,
	baseVersionName: null,
	color: '',
	tags: [],
	orientation: '',
	imageWidth: null,
	imageHeight: null,
	thumbnailVersion: null,
};

/** A ContentItem as the list needs it; the host adds `attributes.displayDate`. */
export interface CloudContentItem extends InspectorTarget {
	exists: boolean;
	hasChildren: boolean;
	folderSize: null;
	attributes: Record<string, any>;
	cloud: CloudItemInfo;
}

/** The id a cloud item carries in the list: unique within a listing, stable across reloads. */
export function cloudItemId(driveId: string, id: string | null): string {
	return `cloud:${driveId}:${id || ''}`;
}

/** A listed entry of a drive as a list item. */
export function cloudEntryToContentItem(entry: CloudEntry, crumbs: CloudCrumbs, labels: CloudLabels): CloudContentItem {
	const folder = !!entry.folder;
	return {
		...EMPTY_TARGET,
		id: cloudItemId(entry.driveId, entry.id),
		name: entry.name,
		path: cloudPath(entry.provider, entry.driveId, entry.id),
		isCollection: folder,
		// The same shape as a repository item's: the plain URL plus
		// `attachment`, which every reader strips or keeps as it needs.
		downloadURL: folder ? null : entry.contentUrl + '?attachment',
		created: entry.created ? new Date(entry.created) : null,
		createdBy: entry.owner || '',
		createdByDisplayName: entry.owner || null,
		lastModified: entry.modified ? new Date(entry.modified) : null,
		contentLength: entry.size || 0,
		mimeType: entry.mimeType || (folder ? '' : 'application/octet-stream'),
		exists: true,
		hasChildren: folder,
		folderSize: null,
		attributes: {},
		cloud: {
			provider: entry.provider,
			driveId: entry.driveId,
			id: entry.id,
			parentId: entry.parentId,
			etag: entry.etag,
			webUrl: entry.webUrl,
			nativeType: entry.nativeType,
			hasThumbnail: !!entry.hasThumbnail,
			owner: entry.owner,
			shared: !!entry.shared,
			contentUrl: entry.contentUrl,
			virtual: null,
			drive: null,
			crumbs,
			location: cloudLocationText(crumbs, labels, entry),
			search: null,
		},
	};
}

/** A row of a listing above the drives: the own drive, the shared drives entry, or one shared drive. */
function virtualItem(provider: string, name: string, path: string, virtual: 'drives' | 'shared' | 'drive', driveId: string, drive: CloudDrive | null, crumbs: CloudCrumbs, location: string): CloudContentItem {
	return {
		...EMPTY_TARGET,
		id: virtual === 'shared' ? `cloud:virtual:${provider}:shared` : cloudItemId(driveId, null),
		name,
		path,
		isCollection: true,
		downloadURL: null,
		created: null,
		createdBy: '',
		createdByDisplayName: null,
		lastModified: null,
		contentLength: 0,
		mimeType: '',
		exists: true,
		hasChildren: true,
		folderSize: null,
		attributes: {},
		cloud: {
			provider,
			driveId,
			id: null,
			parentId: null,
			etag: null,
			webUrl: drive?.webUrl ?? null,
			nativeType: null,
			hasThumbnail: false,
			owner: null,
			shared: false,
			contentUrl: null,
			virtual,
			drive,
			crumbs,
			location,
			search: null,
		},
	};
}

/** One page of a cloud listing. */
export interface CloudListing {
	items: CloudContentItem[];
	/** For the next page; null on the last (always null for the listings above the drives). */
	nextCursor: string | null;
	/** The crumbs of the listed location, when the listing itself established them. */
	crumbs: CloudCrumbs;
}

/** The two listings above the drives need no request of their own apart from the drives. */
export class CloudSource {
	#cloud: CloudServiceGraphQL;
	#labels: CloudLabels;

	constructor(cloud: CloudServiceGraphQL, labels: CloudLabels) {
		this.#cloud = cloud;
		this.#labels = labels;
	}

	/** Crumbs of a listing above the drives. */
	virtualCrumbs(location: CloudLocation): CloudCrumbs {
		return {
			provider: location.provider,
			providerName: this.#labels.providerName(location.provider),
			drive: null,
			virtual: location.virtual,
			ancestors: [],
			current: null,
			pending: false,
		};
	}

	/** Crumbs of a drive's root or of a folder whose ancestors are known. */
	crumbsOf(location: CloudLocation, drive: CloudDrive, ancestors: CloudEntry[], current: CloudEntry | null, pending = false): CloudCrumbs {
		return {
			provider: location.provider,
			providerName: this.#labels.providerName(location.provider),
			drive,
			virtual: null,
			ancestors,
			current,
			pending,
		};
	}

	/** The drive row of the user's own drive, as a CloudDrive, without asking the provider. */
	myDrive(provider: string): CloudDrive {
		return { provider, id: CLOUD_MY_DRIVE, name: this.#labels.myDrive(provider), kind: 'MINE', webUrl: null };
	}

	/** The rows at the provider's root: the own drive and the shared drives entry. */
	listDrivesRoot(location: CloudLocation): CloudListing {
		const crumbs = this.virtualCrumbs(location);
		const provider = location.provider;
		const head = this.#labels.providerName(provider);
		return {
			items: [
				virtualItem(provider, this.#labels.myDrive(provider), cloudPath(provider, CLOUD_MY_DRIVE), 'drive', CLOUD_MY_DRIVE, this.myDrive(provider), crumbs,
					`${head} › ${this.#labels.myDrive(provider)}`),
				virtualItem(provider, this.#labels.sharedDrives(provider), cloudSharedPath(provider), 'shared', '', null, crumbs,
					`${head} › ${this.#labels.sharedDrives(provider)}`),
			],
			nextCursor: null,
			crumbs,
		};
	}

	/** The shared drives / libraries, one row each. */
	async listSharedDrives(location: CloudLocation): Promise<CloudListing> {
		const crumbs = this.virtualCrumbs(location);
		const drives = await this.#cloud.getDrives(location.provider);
		const head = `${this.#labels.providerName(location.provider)} › ${this.#labels.sharedDrives(location.provider)}`;
		return {
			items: drives.filter(d => d.kind === 'SHARED').map(d =>
				virtualItem(location.provider, d.name, cloudPath(location.provider, d.id), 'drive', d.id, d, crumbs, `${head} › ${d.name}`)),
			nextCursor: null,
			crumbs,
		};
	}

	/** One page of a folder of a drive. The crumbs are the caller's (it knows where it is). */
	async listFolder(location: CloudLocation, crumbs: CloudCrumbs, cursor: string | null, order: CloudOrder, first = 200): Promise<CloudListing> {
		const page = await this.#cloud.listChildren(location.provider, location.driveId as string, location.id, { after: cursor, first, order });
		return {
			items: page.entries.map(e => cloudEntryToContentItem(e, crumbs, this.#labels)),
			nextCursor: page.nextCursor,
			crumbs,
		};
	}

	/**
	 * One page of a search: in the folder (Microsoft) or the whole drive
	 * (Google; see CloudStorage.searchScope). A result's folder is not the
	 * listed one, so each item carries what the provider tells about it
	 * (search): Microsoft names every folder above in `path`, Google only the
	 * parent's id, which `resolveSearchFolder` turns into names afterwards.
	 */
	async search(location: CloudLocation, crumbs: CloudCrumbs, query: CloudQuery, cursor: string | null, first = 100): Promise<CloudListing> {
		const page = await this.#cloud.search(location.provider, location.driveId as string, location.id, query, { after: cursor, first });
		return {
			items: page.entries.map(e => this.#searchHitItem(e, crumbs)),
			nextCursor: page.nextCursor,
			crumbs,
		};
	}

	#searchHitItem(entry: CloudEntry, crumbs: CloudCrumbs): CloudContentItem {
		// The drive's root crumbs stand in until the folder is known; a
		// result's own folder is what `search` describes.
		const root: CloudCrumbs = { ...crumbs, ancestors: [], current: null, pending: false };
		const item = cloudEntryToContentItem(entry, root, this.#labels);
		const folderPath = entry.parentId ? cloudPath(entry.provider, entry.driveId, entry.parentId) : null;
		if (entry.path) {
			// "/a/b/name": the names above are known at once.
			const names = entry.path.split('/').filter(Boolean);
			names.pop();
			const parts = [this.#driveHead(crumbs), ...names, entry.name];
			item.cloud.location = parts.join(' › ');
			item.cloud.search = { folderName: names.length ? names[names.length - 1] : this.#driveLabel(crumbs), folderPath, folderCrumbs: null, pending: false, failed: false };
		} else if (entry.parentId) {
			item.cloud.location = '';
			item.cloud.search = { folderName: null, folderPath, folderCrumbs: null, pending: true, failed: false };
		} else {
			// No parent the user can open (shared with the user, say): the name alone.
			item.cloud.location = `${crumbs.providerName} › ${entry.name}`;
			item.cloud.search = { folderName: null, folderPath: null, folderCrumbs: null, pending: false, failed: false };
		}
		return item;
	}

	/** "Google Drive › My Drive" / "Google Drive › Shared drives › Sales": the head of a location in the drive the crumbs describe. */
	#driveHead(crumbs: CloudCrumbs): string {
		return cloudLocationText({ ...crumbs, ancestors: [], current: null, pending: false }, this.#labels);
	}

	/** The name of the drive the crumbs describe, as the folder of an entry at its root. */
	#driveLabel(crumbs: CloudCrumbs): string {
		if (!crumbs.drive) return crumbs.providerName;
		return crumbs.drive.kind === 'MINE' ? this.#labels.myDrive(crumbs.provider) : crumbs.drive.name;
	}

	/**
	 * Fills in the folder of a search result whose provider told only the
	 * parent's id: the folder's crumbs (one cloudFolder per distinct folder,
	 * shared through `cache`) give the folder's name, the item's location
	 * and the crumbs a click on the folder carries.
	 */
	async resolveSearchFolder(item: CloudContentItem, cache: Map<string, Promise<CloudCrumbs>>): Promise<void> {
		const hit = item.cloud.search;
		if (!hit || !hit.pending || !item.cloud.parentId) return;
		const location: CloudLocation = { provider: item.cloud.provider, driveId: item.cloud.driveId, id: item.cloud.parentId, virtual: null };
		const key = `${location.provider}|${location.driveId}|${location.id}`;
		let pending = cache.get(key);
		if (!pending) {
			pending = this.folderCrumbs(location);
			cache.set(key, pending);
		}
		try {
			const folderCrumbs = await pending;
			hit.folderCrumbs = folderCrumbs;
			hit.folderName = folderCrumbs.current ? folderCrumbs.current.name : this.#driveLabel(folderCrumbs);
			item.cloud.crumbs = folderCrumbs;
			item.cloud.location = cloudLocationText(folderCrumbs, this.#labels, item);
		} catch (_) {
			cache.delete(key);
			hit.failed = true;
		} finally {
			hit.pending = false;
		}
	}

	/** The location text of a listed item, from the crumbs it carries (after they were resolved, say). */
	locationTextOf(crumbs: CloudCrumbs, item: { name: string }): string {
		return cloudLocationText(crumbs, this.#labels, item);
	}

	/** Where a folder sits, asked of the server: for a landing whose crumbs nobody carried. */
	async folderCrumbs(location: CloudLocation): Promise<CloudCrumbs> {
		const folder = await this.#cloud.getFolder(location.provider, location.driveId as string, location.id);
		const drive = folder.drive.kind === 'MINE' ? this.myDrive(location.provider) : folder.drive;
		return this.crumbsOf(location, drive, folder.ancestors, folder.entry, false);
	}

	/** The crumbs of a folder clicked in a listing whose crumbs are known. */
	crumbsBelow(parent: CloudCrumbs, folder: CloudEntry): CloudCrumbs {
		return {
			...parent,
			ancestors: parent.current ? [...parent.ancestors, parent.current] : [...parent.ancestors],
			current: folder,
			pending: false,
		};
	}

	/** The crumbs of the folder above the one the crumbs describe, or null at a drive's root. */
	crumbsAbove(crumbs: CloudCrumbs): CloudCrumbs | null {
		if (!crumbs.current) return null;
		const ancestors = crumbs.ancestors.slice();
		const current = ancestors.pop() ?? null;
		return { ...crumbs, ancestors, current, pending: false };
	}

	async createFolder(location: CloudLocation, name: string): Promise<CloudEntry> {
		return this.#cloud.createFolder(location.provider, location.driveId as string, location.id, name);
	}

	async rename(item: CloudItemInfo, name: string): Promise<CloudEntry> {
		return this.#cloud.rename(item.provider, item.driveId, item.id as string, name);
	}

	async delete(item: CloudItemInfo): Promise<boolean> {
		return this.#cloud.delete(item.provider, item.driveId, item.id as string);
	}

	/** The path of the listing above a location: the crumbs' parent, the drive list, or the provider's root. */
	parentPathOf(location: CloudLocation, crumbs: CloudCrumbs | null): string | null {
		if (location.virtual === 'drives') return null;
		if (location.virtual === 'shared') return cloudProviderPath(location.provider);
		if (!location.id) {
			// A drive's root: back to the provider's root (own drive) or the shared drives.
			return location.driveId === CLOUD_MY_DRIVE ? cloudProviderPath(location.provider) : cloudSharedPath(location.provider);
		}
		if (crumbs && !crumbs.pending) {
			const last = crumbs.ancestors[crumbs.ancestors.length - 1];
			return cloudPath(location.provider, location.driveId as string, last ? last.id : null);
		}
		// The ancestors are not known: the drive's root is always above.
		return cloudPath(location.provider, location.driveId as string);
	}
}

/** "Google Drive › My Drive › 2026 › Invoices": the crumbs as one line, for the Dock, a favourite or the clipboard. */
export function cloudLocationText(crumbs: CloudCrumbs, labels: CloudLabels, item?: { name: string } | null): string {
	const parts: string[] = [crumbs.providerName];
	if (crumbs.virtual === 'shared') {
		parts.push(labels.sharedDrives(crumbs.provider));
	} else if (crumbs.drive) {
		if (crumbs.drive.kind === 'MINE') {
			parts.push(labels.myDrive(crumbs.provider));
		} else {
			parts.push(labels.sharedDrives(crumbs.provider), crumbs.drive.name);
		}
		if (crumbs.pending) {
			parts.push('…');
		} else {
			for (const a of crumbs.ancestors) parts.push(a.name);
		}
		if (crumbs.current) parts.push(crumbs.current.name);
	}
	if (item) parts.push(item.name);
	return parts.join(' › ');
}
