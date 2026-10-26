// The repository side of the Content Browser's list: the folder operations
// the list itself needs, over the content service. Everything else the
// Content Browser does to repository nodes (locks, versions, uploads,
// archives, …) is specific to the repository and stays where it is; this
// class holds only what a location of either source has to answer, so that
// load() and the dialogs can be given a source and not care which.

import type { ContentServiceGraphQL } from '../../../services/content-service-graphql.js';
import type { Node } from '../../../graphql/types.js';

export class RepositorySource {
	#content: ContentServiceGraphQL;

	constructor(content: ContentServiceGraphQL) {
		this.#content = content;
	}

	/** The folder node, or null when there is none (or it cannot be read). */
	async folder(path: string): Promise<Node | null> {
		return this.#content.getNode(path);
	}

	/** Every child of the folder, 200 per request. */
	async *children(path: string): AsyncGenerator<Node[]> {
		for await (const batch of this.#content.listAllChildren(path, 200)) {
			yield batch;
		}
	}

	async createFolder(path: string, name: string): Promise<void> {
		await this.#content.createFolder(path, name);
	}

	async rename(path: string, name: string): Promise<void> {
		await this.#content.renameNode(path, name);
	}
}
