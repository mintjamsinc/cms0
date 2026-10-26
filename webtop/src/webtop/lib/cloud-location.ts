// A location in cloud storage, as the Content Browser (and anything else that
// handles its paths) names it: a string beside the repository's paths, so
// the history, the favourites, the back / forward stack, the session and the
// Dock keep working on plain strings.
//
//     cloud://<provider>/                      the provider's drives (own + shared)
//     cloud://<provider>/shared/               the shared drives / libraries
//     cloud://<provider>/me/                   the root of the user's own drive
//     cloud://<provider>/<driveId>/            the root of a shared drive
//     cloud://<provider>/<driveId>/<folderId>  a folder (or a file) in a drive
//
// Ids are the providers' own and are percent-encoded in the string, so a
// character they might hold (a slash, say) cannot be read as a separator.

export const CLOUD_SCHEME = 'cloud://';

/** The drive id of the user's own drive. */
export const CLOUD_MY_DRIVE = 'me';

/** The second segment that lists the shared drives rather than naming a drive. */
export const CLOUD_SHARED_SEGMENT = 'shared';

export interface CloudLocation {
	provider: string;
	/** Null for the two listings above the drives (the provider's root, the shared drives). */
	driveId: string | null;
	/** Null at a drive's root, and above it. */
	id: string | null;
	/** The listing the location is, when it is not a folder of a drive. */
	virtual: 'drives' | 'shared' | null;
}

export function isCloudPath(path: string | null | undefined): boolean {
	return typeof path === 'string' && path.startsWith(CLOUD_SCHEME);
}

function encode(value: string): string {
	return encodeURIComponent(value);
}

function decode(value: string): string {
	try {
		return decodeURIComponent(value);
	} catch (_) {
		return value;
	}
}

/** The location a path names, or null for anything that is not a cloud path. */
export function parseCloudPath(path: string | null | undefined): CloudLocation | null {
	if (!isCloudPath(path)) return null;
	const rest = (path as string).substring(CLOUD_SCHEME.length);
	const segments = rest.split('/');
	const provider = decode(segments[0] || '');
	if (!provider) return null;
	const second = segments.length > 1 ? decode(segments[1]) : '';
	if (!second) {
		return { provider, driveId: null, id: null, virtual: 'drives' };
	}
	if (second === CLOUD_SHARED_SEGMENT) {
		return { provider, driveId: null, id: null, virtual: 'shared' };
	}
	const third = segments.length > 2 ? decode(segments[2]) : '';
	return { provider, driveId: second, id: third || null, virtual: null };
}

/** The path of a folder (or file) of a drive; the drive's root when `id` is null. */
export function cloudPath(provider: string, driveId: string, id?: string | null): string {
	const base = CLOUD_SCHEME + encode(provider) + '/' + encode(driveId) + '/';
	return id ? base + encode(id) : base;
}

/** The path of the provider's drives listing. */
export function cloudProviderPath(provider: string): string {
	return CLOUD_SCHEME + encode(provider) + '/';
}

/** The path of the provider's shared drives listing. */
export function cloudSharedPath(provider: string): string {
	return CLOUD_SCHEME + encode(provider) + '/' + CLOUD_SHARED_SEGMENT + '/';
}

/** The provider a cloud path belongs to, or null. */
export function cloudProviderOf(path: string | null | undefined): string | null {
	return parseCloudPath(path)?.provider ?? null;
}
