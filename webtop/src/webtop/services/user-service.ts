import { avatarURL } from './avatars.js';
import type { IdpUser, IdpGroup } from '../graphql/types.js';

export class User {
	#data: IdpUser | null;

	constructor(data: IdpUser | null) {
		this.#data = data;
	}

	get id(): string {
		return this.#data?.username ?? '';
	}

	get isAnonymous(): boolean {
		return this.#data === null;
	}

	get isGroup(): boolean {
		return false;
	}

	get isAdmin(): boolean {
		return !!this.#data?.roles?.some(
			r => r.name === 'administrator' || r.roleId === 'administrator'
		);
	}

	get fullName(): string {
		return this.#data?.displayName ?? this.#data?.username ?? '';
	}

	get mail(): string {
		return this.#data?.mail ?? '';
	}

	get groups(): IdpGroup[] {
		return this.#data?.effectiveGroups ?? [];
	}

	get lastModified(): Date {
		return this.#data?.lastModified ? new Date(this.#data.lastModified) : new Date();
	}

	/** The user's picture: the photo set in Preferences, or an identicon (see services/avatars.ts). */
	getPhotoURL(): Promise<string> {
		return avatarURL(this.id);
	}
}
