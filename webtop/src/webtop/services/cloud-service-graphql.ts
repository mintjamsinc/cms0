/**
 * Cloud files: the user's Google Drive or OneDrive through the files bundle
 * of their linked account (see connections-service-graphql.ts), and copies
 * between it and the workspace this service's client is bound to.
 *
 * A call made before the account is linked, or before the files bundle is
 * granted, fails with the error code CONSENT_REQUIRED; {@link isConsentRequired}
 * tells it apart, and the app then calls `connections.ensure(provider,
 * 'files')` and tries again.
 */

import { GraphQLClient, GraphQLError } from '../graphql/client.js';
import { CLOUD_QUERIES, CLOUD_MUTATIONS } from '../graphql/queries/cloud.js';
import type { CloudConflict, CloudEntry, CloudImport, CloudPicker } from '../graphql/types.js';

export const CLOUD_FILES_BUNDLE = 'files';

export class CloudServiceGraphQL {
  #client: GraphQLClient;

  constructor(client: GraphQLClient) {
    this.#client = client;
  }

  /** What the browser needs to run the provider's own picker as the user. */
  async getPicker(provider: string): Promise<CloudPicker> {
    const data = await this.#client.query<{ cloudPicker: CloudPicker }>(CLOUD_QUERIES.PICKER, { provider });
    return data.cloudPicker;
  }

  /** The files and folders in a folder of the drive; the root when folderId is null. */
  async listChildren(provider: string, folderId?: string | null): Promise<CloudEntry[]> {
    const data = await this.#client.query<{ cloudChildren: CloudEntry[] }>(CLOUD_QUERIES.CHILDREN, {
      provider,
      folderId: folderId ?? null,
    });
    return data.cloudChildren;
  }

  async getFile(provider: string, id: string): Promise<CloudEntry | null> {
    const data = await this.#client.query<{ cloudFile: CloudEntry | null }>(CLOUD_QUERIES.FILE, { provider, id });
    return data.cloudFile;
  }

  /** Copies a cloud file into a folder of the workspace. */
  async importFile(
    provider: string,
    id: string,
    folder: string,
    options: { name?: string | null; exportMimeType?: string | null; conflict?: CloudConflict } = {}
  ): Promise<CloudImport> {
    const data = await this.#client.mutation<{ importCloudFile: CloudImport }>(CLOUD_MUTATIONS.IMPORT_FILE, {
      provider,
      id,
      folder,
      name: options.name ?? null,
      exportMimeType: options.exportMimeType ?? null,
      conflict: options.conflict ?? 'RENAME',
    });
    return data.importCloudFile;
  }

  /** Uploads a file of the workspace to a folder of the drive (the root when folderId is null). */
  async exportFile(
    provider: string,
    path: string,
    options: { folderId?: string | null; name?: string | null; convert?: boolean } = {}
  ): Promise<CloudEntry> {
    const data = await this.#client.mutation<{ exportCloudFile: CloudEntry }>(CLOUD_MUTATIONS.EXPORT_FILE, {
      provider,
      path,
      folderId: options.folderId ?? null,
      name: options.name ?? null,
      convert: options.convert ?? false,
    });
    return data.exportCloudFile;
  }

  /** Uploads text content as a new file of the drive; `convert` turns it into a Google document. */
  async exportContent(
    provider: string,
    name: string,
    mimeType: string,
    content: string,
    options: { folderId?: string | null; convert?: boolean } = {}
  ): Promise<CloudEntry> {
    const data = await this.#client.mutation<{ exportCloudContent: CloudEntry }>(CLOUD_MUTATIONS.EXPORT_CONTENT, {
      provider,
      folderId: options.folderId ?? null,
      name,
      mimeType,
      content,
      convert: options.convert ?? false,
    });
    return data.exportCloudContent;
  }

  /** Writes a reference to a cloud file into a folder of the workspace. */
  async createLink(
    provider: string,
    id: string,
    folder: string,
    options: { name?: string | null; conflict?: CloudConflict } = {}
  ): Promise<CloudImport> {
    const data = await this.#client.mutation<{ createCloudLink: CloudImport }>(CLOUD_MUTATIONS.CREATE_LINK, {
      provider,
      id,
      folder,
      name: options.name ?? null,
      conflict: options.conflict ?? 'RENAME',
    });
    return data.createCloudLink;
  }
}

/** Whether an error says the user has to link the account or grant the files bundle first. */
export function isConsentRequired(error: unknown): boolean {
  if (error instanceof GraphQLError) {
    return error.code === 'CONSENT_REQUIRED';
  }
  const message = error instanceof Error ? error.message : String(error ?? '');
  return message.startsWith('consent_required');
}
