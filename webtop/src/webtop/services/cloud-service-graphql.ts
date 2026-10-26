/**
 * Cloud files: the user's drives at Google or Microsoft through the files
 * bundle of their linked account (see connections-service-graphql.ts), and
 * copies between them and the workspace this service's client is bound to.
 *
 * A drive is named by a drive id: "me" for the user's own (My Drive,
 * OneDrive), else the id of a shared drive / library the user is a member
 * of. Within a drive, folders and files are the provider's ids; a null
 * folder is the drive's root. A file's bytes are at `entry.contentUrl`, a
 * same-origin URL (/bin/cloud.cgi/...) that takes Range, `?attachment`,
 * `?thumbnail=small|medium|large`, PUT with If-Match, and a multipart POST
 * to `<folder contentUrl>/children`.
 *
 * A call made before the account is linked, or before the files bundle is
 * granted, fails with the error code CONSENT_REQUIRED; {@link isConsentRequired}
 * tells it apart, and the app then calls `connections.ensure(provider,
 * 'files')` and tries again. A throttled call fails with RATE_LIMITED
 * ({@link isRateLimited}): try again a little later.
 */

import { GraphQLClient, GraphQLError } from '../graphql/client.js';
import { CLOUD_QUERIES, CLOUD_MUTATIONS } from '../graphql/queries/cloud.js';
import type {
  CloudConflict,
  CloudDrive,
  CloudEntries,
  CloudEntry,
  CloudFolder,
  CloudImport,
  CloudOrder,
  CloudQuery,
  CloudStorage,
} from '../graphql/types.js';

export const CLOUD_FILES_BUNDLE = 'files';

/** The drive id of the user's own drive. */
export const MY_DRIVE = 'me';

export class CloudServiceGraphQL {
  #client: GraphQLClient;

  constructor(client: GraphQLClient) {
    this.#client = client;
  }

  /** Every provider an administrator configured, granted to the user or not. */
  async getStorages(): Promise<CloudStorage[]> {
    const data = await this.#client.query<{ cloudStorages: CloudStorage[] }>(CLOUD_QUERIES.STORAGES);
    return data.cloudStorages;
  }

  /** The user's drives of a provider: their own first, then the shared ones by name. */
  async getDrives(provider: string): Promise<CloudDrive[]> {
    const data = await this.#client.query<{ cloudDrives: CloudDrive[] }>(CLOUD_QUERIES.DRIVES, { provider });
    return data.cloudDrives;
  }

  /** Where a folder sits: its drive, the folder (null for the root) and the folders above it. */
  async getFolder(provider: string, driveId: string, folderId?: string | null): Promise<CloudFolder> {
    const data = await this.#client.query<{ cloudFolder: CloudFolder }>(CLOUD_QUERIES.FOLDER, {
      provider,
      driveId,
      folderId: folderId ?? null,
    });
    return data.cloudFolder;
  }

  /** One page of the files and folders in a folder of a drive; the root when folderId is null. */
  async listChildren(
    provider: string,
    driveId: string,
    folderId?: string | null,
    options: { after?: string | null; first?: number; order?: CloudOrder } = {}
  ): Promise<CloudEntries> {
    const data = await this.#client.query<{ cloudChildren: CloudEntries }>(CLOUD_QUERIES.CHILDREN, {
      provider,
      driveId,
      folderId: folderId ?? null,
      after: options.after ?? null,
      first: options.first ?? 200,
      order: options.order ?? 'NAME',
    });
    return data.cloudChildren;
  }

  /** One page of a search in a folder (Microsoft) or the whole drive (Google). */
  async search(
    provider: string,
    driveId: string,
    folderId: string | null,
    query: CloudQuery,
    options: { after?: string | null; first?: number } = {}
  ): Promise<CloudEntries> {
    const data = await this.#client.query<{ cloudSearch: CloudEntries }>(CLOUD_QUERIES.SEARCH, {
      provider,
      driveId,
      folderId: folderId ?? null,
      query,
      after: options.after ?? null,
      first: options.first ?? 100,
    });
    return data.cloudSearch;
  }

  async getFile(provider: string, driveId: string, id: string): Promise<CloudEntry | null> {
    const data = await this.#client.query<{ cloudFile: CloudEntry | null }>(CLOUD_QUERIES.FILE, {
      provider,
      driveId,
      id,
    });
    return data.cloudFile;
  }

  /** A URL that embeds the provider's viewer of the file, or null when it has none. */
  async getPreview(provider: string, driveId: string, id: string): Promise<string | null> {
    const data = await this.#client.query<{ cloudPreview: string | null }>(CLOUD_QUERIES.PREVIEW, {
      provider,
      driveId,
      id,
    });
    return data.cloudPreview;
  }

  async createFolder(provider: string, driveId: string, parentId: string | null, name: string): Promise<CloudEntry> {
    const data = await this.#client.mutation<{ createCloudFolder: CloudEntry }>(CLOUD_MUTATIONS.CREATE_FOLDER, {
      provider,
      driveId,
      parentId: parentId ?? null,
      name,
    });
    return data.createCloudFolder;
  }

  async rename(provider: string, driveId: string, id: string, name: string): Promise<CloudEntry> {
    const data = await this.#client.mutation<{ renameCloudEntry: CloudEntry }>(CLOUD_MUTATIONS.RENAME, {
      provider,
      driveId,
      id,
      name,
    });
    return data.renameCloudEntry;
  }

  /** Moves the entry to the provider's trash. */
  async delete(provider: string, driveId: string, id: string): Promise<boolean> {
    const data = await this.#client.mutation<{ deleteCloudEntry: boolean }>(CLOUD_MUTATIONS.DELETE, {
      provider,
      driveId,
      id,
    });
    return data.deleteCloudEntry;
  }

  /** Moves the entry to another folder of the same drive (the root when toFolderId is null). */
  async move(provider: string, driveId: string, id: string, toFolderId: string | null): Promise<CloudEntry> {
    const data = await this.#client.mutation<{ moveCloudEntry: CloudEntry }>(CLOUD_MUTATIONS.MOVE, {
      provider,
      driveId,
      id,
      toFolderId: toFolderId ?? null,
    });
    return data.moveCloudEntry;
  }

  /** Copies the entry (a folder with everything in it) into a folder of a drive of the same provider. */
  async copy(
    provider: string,
    driveId: string,
    id: string,
    toDriveId: string,
    toFolderId: string | null,
    options: { name?: string | null } = {}
  ): Promise<CloudEntry> {
    const data = await this.#client.mutation<{ copyCloudEntry: CloudEntry }>(CLOUD_MUTATIONS.COPY, {
      provider,
      driveId,
      id,
      toDriveId,
      toFolderId: toFolderId ?? null,
      name: options.name ?? null,
    });
    return data.copyCloudEntry;
  }

  /** Copies a cloud entry (a folder with everything in it) into a folder of the workspace. */
  async importFile(
    provider: string,
    id: string,
    folder: string,
    options: {
      driveId?: string | null;
      name?: string | null;
      exportMimeType?: string | null;
      conflict?: CloudConflict;
    } = {}
  ): Promise<CloudImport> {
    const data = await this.#client.mutation<{ importCloudFile: CloudImport }>(CLOUD_MUTATIONS.IMPORT_FILE, {
      provider,
      driveId: options.driveId ?? MY_DRIVE,
      id,
      folder,
      name: options.name ?? null,
      exportMimeType: options.exportMimeType ?? null,
      conflict: options.conflict ?? 'RENAME',
    });
    return data.importCloudFile;
  }

  /** Uploads a file or folder of the workspace to a folder of a drive (the root when folderId is null). */
  async exportFile(
    provider: string,
    path: string,
    options: { driveId?: string | null; folderId?: string | null; name?: string | null; convert?: boolean } = {}
  ): Promise<CloudEntry> {
    const data = await this.#client.mutation<{ exportCloudFile: CloudEntry }>(CLOUD_MUTATIONS.EXPORT_FILE, {
      provider,
      path,
      driveId: options.driveId ?? MY_DRIVE,
      folderId: options.folderId ?? null,
      name: options.name ?? null,
      convert: options.convert ?? false,
    });
    return data.exportCloudFile;
  }

  /** Uploads text content as a new file of a drive; `convert` turns it into a Google document. */
  async exportContent(
    provider: string,
    name: string,
    mimeType: string,
    content: string,
    options: { driveId?: string | null; folderId?: string | null; convert?: boolean } = {}
  ): Promise<CloudEntry> {
    const data = await this.#client.mutation<{ exportCloudContent: CloudEntry }>(CLOUD_MUTATIONS.EXPORT_CONTENT, {
      provider,
      driveId: options.driveId ?? MY_DRIVE,
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
    options: { driveId?: string | null; name?: string | null; conflict?: CloudConflict } = {}
  ): Promise<CloudImport> {
    const data = await this.#client.mutation<{ createCloudLink: CloudImport }>(CLOUD_MUTATIONS.CREATE_LINK, {
      provider,
      driveId: options.driveId ?? MY_DRIVE,
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

/** Whether an error says the provider is throttling: try again a little later. */
export function isRateLimited(error: unknown): boolean {
  return error instanceof GraphQLError && error.code === 'RATE_LIMITED';
}
