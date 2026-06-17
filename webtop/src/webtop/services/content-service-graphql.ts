/**
 * Content Service (GraphQL-based)
 *
 * Provides content management operations using GraphQL API.
 */

import { GraphQLClient } from '../graphql/client.js';
import { CONTENT_QUERIES, CONTENT_MUTATIONS } from '../graphql/queries/content.js';
import { resolveLocale } from './webtop-i18n-service.js';
import type {
  Node,
  NodeConnection,
  PropertyInput,
  SetPropertiesResult,
  AccessControl,
  AccessControlEntryInput,
  EffectiveAccessControlPolicy,
  PrincipalInfo,
  VersionConnection,
  Version,
  MultipartUploadInfo,
  InitiateMultipartUploadInput,
  InitDeleteNodesResult,
  AppendDeleteNodesResult,
  StartDeleteNodesResult,
  AbortDeleteNodesResult,
  InitDownloadArchiveResult,
  AppendDownloadArchiveResult,
  StartDownloadArchiveResult,
  AbortDownloadArchiveResult,
  InitImportArchiveResult,
  StartImportArchiveResult,
  AbortImportArchiveResult,
  ImportArchiveOptions,
} from '../graphql/types.js';

/** Server-side cap on paths per append call (shared by delete and archive jobs). */
const DELETE_APPEND_CHUNK = 100;

export interface ListChildrenOptions {
  first?: number;
  after?: string;
}

export interface SearchOptions {
  path?: string;
  first?: number;
  after?: string;
}

export interface QueryOptions {
  language?: 'JCR-SQL2' | 'XPath' | 'SQL';
  first?: number;
  after?: string;
}

/**
 * Content Service for JCR-based content management
 */
export class ContentServiceGraphQL {
  #client: GraphQLClient;

  constructor(client: GraphQLClient) {
    this.#client = client;
  }

  // =========================================================================
  // Queries
  // =========================================================================

  /**
   * Get a single node by path
   */
  async getNode(path: string): Promise<Node | null> {
    const data = await this.#client.query<{ node: Node | null }>(
      CONTENT_QUERIES.GET_NODE,
      { path }
    );
    return data.node;
  }

  /**
   * List children of a node
   */
  async listChildren(
    path: string,
    options: ListChildrenOptions = {}
  ): Promise<NodeConnection> {
    const data = await this.#client.query<{ children: NodeConnection }>(
      CONTENT_QUERIES.LIST_CHILDREN,
      {
        path,
        first: options.first ?? 50,
        after: options.after,
      }
    );
    return data.children;
  }

  /**
   * Get all children (auto-pagination)
   */
  async *listAllChildren(
    path: string,
    pageSize = 50
  ): AsyncGenerator<Node[], void, unknown> {
    let after: string | undefined;
    let hasMore = true;

    while (hasMore) {
      const result = await this.listChildren(path, { first: pageSize, after });
      const nodes = result.edges.map(e => e.node);

      if (nodes.length > 0) {
        yield nodes;
      }

      hasMore = result.pageInfo.hasNextPage;
      const nextCursor = result.pageInfo.endCursor ?? undefined;
      // Stop if cursor did not advance (prevents infinite loop)
      if (hasMore && (!nextCursor || nextCursor === after)) {
        break;
      }
      after = nextCursor;
    }
  }

  /**
   * Get references to a node
   */
  async getReferences(
    path: string,
    options: ListChildrenOptions = {}
  ): Promise<NodeConnection> {
    const data = await this.#client.query<{ references: NodeConnection }>(
      CONTENT_QUERIES.GET_REFERENCES,
      {
        path,
        first: options.first ?? 20,
        after: options.after,
      }
    );
    return data.references;
  }

  /**
   * Full-text search
   */
  async search(text: string, options: SearchOptions = {}): Promise<NodeConnection> {
    const data = await this.#client.query<{ search: NodeConnection }>(
      CONTENT_QUERIES.SEARCH,
      {
        text,
        path: options.path ?? '/',
        first: options.first ?? 50,
        after: options.after,
      }
    );
    return data.search;
  }

  /**
   * Execute XPath query
   */
  async xpath(query: string, options: Omit<QueryOptions, 'language'> = {}): Promise<NodeConnection> {
    const data = await this.#client.query<{ xpath: NodeConnection }>(
      CONTENT_QUERIES.XPATH,
      {
        query,
        first: options.first ?? 50,
        after: options.after,
      }
    );
    return data.xpath;
  }

  /**
   * Execute XPath query with properties
   */
  async xpathWithProperties(query: string, options: Omit<QueryOptions, 'language'> = {}): Promise<NodeConnection> {
    const data = await this.#client.query<{ xpath: NodeConnection }>(
      CONTENT_QUERIES.XPATH_WITH_PROPERTIES,
      {
        query,
        first: options.first ?? 50,
        after: options.after,
      }
    );
    return data.xpath;
  }

  /**
   * Execute JCR query
   */
  async query(statement: string, options: QueryOptions = {}): Promise<NodeConnection> {
    const data = await this.#client.query<{ query: NodeConnection }>(
      CONTENT_QUERIES.QUERY,
      {
        statement,
        language: options.language ?? 'JCR-SQL2',
        first: options.first ?? 50,
        after: options.after,
      }
    );
    return data.query;
  }

  /**
   * Get access control list
   */
  async getAccessControl(path: string): Promise<AccessControl> {
    const data = await this.#client.query<{ accessControl: AccessControl }>(
      CONTENT_QUERIES.GET_ACCESS_CONTROL,
      { path }
    );
    return data.accessControl;
  }

  /**
   * Get effective access control list (hierarchical: current path + ancestors)
   */
  async getEffectiveAccessControl(path: string): Promise<EffectiveAccessControlPolicy[]> {
    const data = await this.#client.query<{ effectiveAccessControl: EffectiveAccessControlPolicy[] }>(
      CONTENT_QUERIES.GET_EFFECTIVE_ACCESS_CONTROL,
      { path }
    );
    return data.effectiveAccessControl;
  }

  /**
   * Search principals (users and groups)
   */
  async searchPrincipals(keyword: string, offset?: number, limit?: number): Promise<PrincipalInfo[]> {
    const data = await this.#client.query<{ searchPrincipals: PrincipalInfo[] }>(
      CONTENT_QUERIES.SEARCH_PRINCIPALS,
      { keyword, offset, limit }
    );
    return data.searchPrincipals;
  }

  /**
   * Get version history
   */
  async getVersionHistory(path: string): Promise<VersionConnection | null> {
    const data = await this.#client.query<{ versionHistory: VersionConnection | null }>(
      CONTENT_QUERIES.GET_VERSION_HISTORY,
      { path }
    );
    return data.versionHistory;
  }

  // =========================================================================
  // Mutations
  // =========================================================================

  /**
   * Create a new folder
   */
  async createFolder(
    parentPath: string,
    name: string,
    nodeType?: string
  ): Promise<Node> {
    const data = await this.#client.mutation<{ createFolder: Node }>(
      CONTENT_MUTATIONS.CREATE_FOLDER,
      { input: { path: parentPath, name, nodeType } }
    );
    return data.createFolder;
  }

  /**
   * Create a new file
   */
  async createFile(
    parentPath: string,
    name: string,
    mimeType: string,
    content: string, // Base64 encoded
    nodeType?: string
  ): Promise<Node> {
    const data = await this.#client.mutation<{ createFile: Node }>(
      CONTENT_MUTATIONS.CREATE_FILE,
      { input: { path: parentPath, name, mimeType, content, nodeType } }
    );
    return data.createFile;
  }

  /**
   * Delete a node (synchronous, single-path).
   *
   * Suitable for small targets where the request thread can wait for the
   * whole subtree to be removed. For folders or multi-selection use
   * {@link initDeleteNodes} / {@link appendDeleteNodes} / {@link startDeleteNodes}
   * instead — the worker is async and progress is streamed via subscription.
   */
  async deleteNode(path: string): Promise<boolean> {
    const data = await this.#client.mutation<{ deleteNode: boolean }>(
      CONTENT_MUTATIONS.DELETE_NODE,
      { input: { path } }
    );
    return data.deleteNode;
  }

  /**
   * Async deletion job — step 1.
   * Allocates a job record on the server and returns its id.
   */
  async initDeleteNodes(): Promise<InitDeleteNodesResult> {
    const data = await this.#client.mutation<{ initDeleteNodes: InitDeleteNodesResult }>(
      CONTENT_MUTATIONS.INIT_DELETE_NODES,
      { input: {} }
    );
    return data.initDeleteNodes;
  }

  /**
   * Async deletion job — step 2.
   * Append a single chunk (up to {@link DELETE_APPEND_CHUNK} paths) to the
   * job body. Use {@link appendAllDeleteNodes} when you have more than one
   * chunk's worth of paths.
   */
  async appendDeleteNodes(jobId: string, paths: string[]): Promise<AppendDeleteNodesResult> {
    if (paths.length > DELETE_APPEND_CHUNK) {
      throw new Error(`appendDeleteNodes accepts at most ${DELETE_APPEND_CHUNK} paths per call`);
    }
    const data = await this.#client.mutation<{ appendDeleteNodes: AppendDeleteNodesResult }>(
      CONTENT_MUTATIONS.APPEND_DELETE_NODES,
      { input: { jobId, paths } }
    );
    return data.appendDeleteNodes;
  }

  /**
   * Async deletion job — append a longer list of paths by chunking, in
   * order, into successive {@link appendDeleteNodes} calls. Returns the
   * total number of items the server accepted across the calls.
   */
  async appendAllDeleteNodes(jobId: string, paths: string[]): Promise<number> {
    let accepted = 0;
    for (let i = 0; i < paths.length; i += DELETE_APPEND_CHUNK) {
      const chunk = paths.slice(i, i + DELETE_APPEND_CHUNK);
      const r = await this.appendDeleteNodes(jobId, chunk);
      accepted += r.itemsAccepted;
    }
    return accepted;
  }

  /**
   * Async deletion job — step 3.
   * Hands the job to the background worker; the mutation returns
   * immediately. Subscribe to {@code jobProgress(jobId)} for live updates.
   */
  async startDeleteNodes(jobId: string): Promise<StartDeleteNodesResult> {
    const data = await this.#client.mutation<{ startDeleteNodes: StartDeleteNodesResult }>(
      CONTENT_MUTATIONS.START_DELETE_NODES,
      { input: { jobId } }
    );
    return data.startDeleteNodes;
  }

  /**
   * Async deletion job — request abort.
   * Items already deleted stay deleted; the worker stops at its next
   * safe point.
   */
  async abortDeleteNodes(jobId: string): Promise<AbortDeleteNodesResult> {
    const data = await this.#client.mutation<{ abortDeleteNodes: AbortDeleteNodesResult }>(
      CONTENT_MUTATIONS.ABORT_DELETE_NODES,
      { input: { jobId } }
    );
    return data.abortDeleteNodes;
  }

  /**
   * Async ZIP-archive download job — step 1.
   * Allocates a job record on the server and returns its id.
   */
  async initDownloadArchive(): Promise<InitDownloadArchiveResult> {
    const data = await this.#client.mutation<{ initDownloadArchive: InitDownloadArchiveResult }>(
      CONTENT_MUTATIONS.INIT_DOWNLOAD_ARCHIVE,
      { input: {} }
    );
    return data.initDownloadArchive;
  }

  /**
   * Async ZIP-archive download job — step 2.
   * Append a single chunk (up to {@link DELETE_APPEND_CHUNK} paths) of
   * top-level items to bundle.
   */
  async appendDownloadArchive(jobId: string, paths: string[]): Promise<AppendDownloadArchiveResult> {
    if (paths.length > DELETE_APPEND_CHUNK) {
      throw new Error(`appendDownloadArchive accepts at most ${DELETE_APPEND_CHUNK} paths per call`);
    }
    const data = await this.#client.mutation<{ appendDownloadArchive: AppendDownloadArchiveResult }>(
      CONTENT_MUTATIONS.APPEND_DOWNLOAD_ARCHIVE,
      { input: { jobId, paths } }
    );
    return data.appendDownloadArchive;
  }

  /**
   * Async ZIP-archive download job — append a longer list of paths by
   * chunking, in order, into successive {@link appendDownloadArchive} calls.
   * Returns the total number of items the server accepted across the calls.
   */
  async appendAllDownloadArchive(jobId: string, paths: string[]): Promise<number> {
    let accepted = 0;
    for (let i = 0; i < paths.length; i += DELETE_APPEND_CHUNK) {
      const chunk = paths.slice(i, i + DELETE_APPEND_CHUNK);
      const r = await this.appendDownloadArchive(jobId, chunk);
      accepted += r.itemsAccepted;
    }
    return accepted;
  }

  /**
   * Async ZIP-archive download job — step 3.
   * Records the archive file name and hands the job to the background worker;
   * the mutation returns immediately. Subscribe to {@code jobProgress(jobId)}
   * for live updates and the terminal downloadUrl.
   */
  async startDownloadArchive(
    jobId: string,
    filename: string,
    options: { includeMetadata?: boolean; includeAcl?: boolean } = {},
  ): Promise<StartDownloadArchiveResult> {
    const input: Record<string, unknown> = { jobId, filename };
    // Defaults are applied server-side (metadata on, ACL off); only send the
    // flags when the caller overrides them.
    if (options.includeMetadata !== undefined) input.includeMetadata = options.includeMetadata;
    if (options.includeAcl !== undefined) input.includeAcl = options.includeAcl;
    const data = await this.#client.mutation<{ startDownloadArchive: StartDownloadArchiveResult }>(
      CONTENT_MUTATIONS.START_DOWNLOAD_ARCHIVE,
      { input }
    );
    return data.startDownloadArchive;
  }

  /**
   * Async ZIP-archive download job — request abort.
   * The worker stops at its next safe point (between files).
   */
  async abortDownloadArchive(jobId: string): Promise<AbortDownloadArchiveResult> {
    const data = await this.#client.mutation<{ abortDownloadArchive: AbortDownloadArchiveResult }>(
      CONTENT_MUTATIONS.ABORT_DOWNLOAD_ARCHIVE,
      { input: { jobId } }
    );
    return data.abortDownloadArchive;
  }

  /**
   * Async archive import job — step 1.
   * Allocates a job record on the server and returns its id.
   */
  async initImportArchive(): Promise<InitImportArchiveResult> {
    const data = await this.#client.mutation<{ initImportArchive: InitImportArchiveResult }>(
      CONTENT_MUTATIONS.INIT_IMPORT_ARCHIVE,
      { input: {} }
    );
    return data.initImportArchive;
  }

  /**
   * Async archive import job — step 2.
   * Records the import options and hands the job to the background worker;
   * subscribe to {@code jobProgress(jobId)} for live updates.
   */
  async startImportArchive(jobId: string, options: ImportArchiveOptions): Promise<StartImportArchiveResult> {
    const input: Record<string, unknown> = {
      jobId,
      archivePath: options.archivePath,
      destinationPath: options.destinationPath,
      // The report CSV is generated server-side at run time; tell the job which
      // locale to write its own strings (the "処理" column and its error
      // messages) in, so they match the UI language the user ran the import in.
      locale: resolveLocale(),
    };
    if (options.filename !== undefined) input.filename = options.filename;
    if (options.uuidBehavior !== undefined) input.uuidBehavior = options.uuidBehavior;
    if (options.pathBehavior !== undefined) input.pathBehavior = options.pathBehavior;
    if (options.importAcl !== undefined) input.importAcl = options.importAcl;
    if (options.preserveTimestamps !== undefined) input.preserveTimestamps = options.preserveTimestamps;
    if (options.dryRun !== undefined) input.dryRun = options.dryRun;
    const data = await this.#client.mutation<{ startImportArchive: StartImportArchiveResult }>(
      CONTENT_MUTATIONS.START_IMPORT_ARCHIVE,
      { input }
    );
    return data.startImportArchive;
  }

  /**
   * Async archive import job — request abort.
   * The worker stops at its next safe point (between nodes).
   */
  async abortImportArchive(jobId: string): Promise<AbortImportArchiveResult> {
    const data = await this.#client.mutation<{ abortImportArchive: AbortImportArchiveResult }>(
      CONTENT_MUTATIONS.ABORT_IMPORT_ARCHIVE,
      { input: { jobId } }
    );
    return data.abortImportArchive;
  }

  /**
   * Rename a node
   */
  async renameNode(path: string, name: string): Promise<Node> {
    const data = await this.#client.mutation<{ renameNode: Node }>(
      CONTENT_MUTATIONS.RENAME_NODE,
      { input: { path, name } }
    );
    return data.renameNode;
  }

  /**
   * Move a node to a different parent directory
   */
  async moveNode(sourcePath: string, destPath: string, name?: string): Promise<Node> {
    const data = await this.#client.mutation<{ moveNode: Node }>(
      CONTENT_MUTATIONS.MOVE_NODE,
      { input: { sourcePath, destPath, name } }
    );
    return data.moveNode;
  }

  /**
   * Copy a node to a different parent directory (deep copy)
   */
  async copyNode(sourcePath: string, destPath: string, name?: string): Promise<Node> {
    const data = await this.#client.mutation<{ copyNode: Node }>(
      CONTENT_MUTATIONS.COPY_NODE,
      { input: { sourcePath, destPath, name } }
    );
    return data.copyNode;
  }

  /**
   * Lock a node
   */
  async lockNode(
    path: string,
    options: { isDeep?: boolean; isSessionScoped?: boolean } = {}
  ): Promise<Node> {
    const data = await this.#client.mutation<{ lockNode: Node }>(
      CONTENT_MUTATIONS.LOCK_NODE,
      {
        input: {
          path,
          isDeep: options.isDeep ?? false,
          isSessionScoped: options.isSessionScoped ?? false,
        }
      }
    );
    return data.lockNode;
  }

  /**
   * Unlock a node
   */
  async unlockNode(path: string): Promise<boolean> {
    const data = await this.#client.mutation<{ unlockNode: boolean }>(
      CONTENT_MUTATIONS.UNLOCK_NODE,
      { input: { path } }
    );
    return data.unlockNode;
  }

  /**
   * Set properties on a node
   */
  async setProperties(
    path: string,
    properties: PropertyInput[]
  ): Promise<SetPropertiesResult> {
    const data = await this.#client.mutation<{ setProperties: SetPropertiesResult }>(
      CONTENT_MUTATIONS.SET_PROPERTIES,
      { input: { path, properties } }
    );
    return data.setProperties;
  }

  /**
   * Add a mixin type
   */
  async addMixin(path: string, mixinType: string): Promise<Node> {
    const data = await this.#client.mutation<{ addMixin: Node }>(
      CONTENT_MUTATIONS.ADD_MIXIN,
      { input: { path, mixinType } }
    );
    return data.addMixin;
  }

  /**
   * Delete a mixin type
   */
  async deleteMixin(path: string, mixinType: string): Promise<Node> {
    const data = await this.#client.mutation<{ deleteMixin: Node }>(
      CONTENT_MUTATIONS.DELETE_MIXIN,
      { input: { path, mixinType } }
    );
    return data.deleteMixin;
  }

  /**
   * Set access control
   */
  async setAccessControl(
    path: string,
    options: {
      principal?: string;
      privileges?: string[];
      allow?: boolean;
      entries?: AccessControlEntryInput[];
    }
  ): Promise<AccessControl> {
    const data = await this.#client.mutation<{ setAccessControl: AccessControl }>(
      CONTENT_MUTATIONS.SET_ACCESS_CONTROL,
      { input: { path, ...options } }
    );
    return data.setAccessControl;
  }

  /**
   * Delete access control entry
   */
  async deleteAccessControl(path: string, principal: string): Promise<boolean> {
    const data = await this.#client.mutation<{ deleteAccessControl: boolean }>(
      CONTENT_MUTATIONS.DELETE_ACCESS_CONTROL,
      { input: { path, principal } }
    );
    return data.deleteAccessControl;
  }

  /**
   * Check in a versionable node
   */
  async checkin(path: string): Promise<Version> {
    const data = await this.#client.mutation<{ checkin: Version }>(
      CONTENT_MUTATIONS.CHECKIN,
      { input: { path } }
    );
    return data.checkin;
  }

  /**
   * Check out a versionable node
   */
  async checkout(path: string): Promise<boolean> {
    const data = await this.#client.mutation<{ checkout: boolean }>(
      CONTENT_MUTATIONS.CHECKOUT,
      { input: { path } }
    );
    return data.checkout;
  }

  /**
   * Restore a version
   */
  async restoreVersion(path: string, versionName: string): Promise<Node> {
    const data = await this.#client.mutation<{ restoreVersion: Node }>(
      CONTENT_MUTATIONS.RESTORE_VERSION,
      { input: { path, versionName } }
    );
    return data.restoreVersion;
  }

  /**
   * Add version control to a node (makes it versionable)
   */
  async addVersionControl(path: string): Promise<Node> {
    const data = await this.#client.mutation<{ addVersionControl: Node }>(
      CONTENT_MUTATIONS.ADD_VERSION_CONTROL,
      { input: { path } }
    );
    return data.addVersionControl;
  }

  /**
   * Cancel a checkout (uncheckout)
   */
  async uncheckout(path: string): Promise<boolean> {
    const data = await this.#client.mutation<{ uncheckout: boolean }>(
      CONTENT_MUTATIONS.UNCHECKOUT,
      { input: { path } }
    );
    return data.uncheckout;
  }

  /**
   * Create a checkpoint (version while keeping checked out)
   */
  async checkpoint(path: string): Promise<Version> {
    const data = await this.#client.mutation<{ checkpoint: Version }>(
      CONTENT_MUTATIONS.CHECKPOINT,
      { input: { path } }
    );
    return data.checkpoint;
  }

  // =========================================================================
  // Utility Methods
  // =========================================================================

  /**
   * Check if a node exists
   */
  async exists(path: string): Promise<boolean> {
    const node = await this.getNode(path);
    return node !== null;
  }

  /**
   * Get a property value from a node
   */
  async getProperty<T = unknown>(path: string, propertyName: string): Promise<T | null> {
    const node = await this.getNode(path);
    if (!node) return null;

    const prop = node.properties.find(p => p.name === propertyName);
    if (!prop) return null;

    const pv = prop.propertyValue;
    if ('value' in pv) {
      return pv.value as T;
    } else if ('values' in pv) {
      return pv.values as T;
    }
    return null;
  }

  /**
   * Set a single property
   */
  async setProperty(
    path: string,
    name: string,
    value: string | number | boolean | string[] | number[] | boolean[]
  ): Promise<SetPropertiesResult> {
    const propertyInput = this.#createPropertyInput(name, value);
    return this.setProperties(path, [propertyInput]);
  }

  /**
   * Create property input from value
   */
  #createPropertyInput(
    name: string,
    value: string | number | boolean | string[] | number[] | boolean[]
  ): PropertyInput {
    if (Array.isArray(value)) {
      if (typeof value[0] === 'string') {
        return { name, value: { stringArrayValue: value as string[] } };
      } else if (typeof value[0] === 'number') {
        return { name, value: { longArrayValue: value as number[] } };
      } else {
        return { name, value: { booleanArrayValue: value as boolean[] } };
      }
    } else {
      if (typeof value === 'string') {
        return { name, value: { stringValue: value } };
      } else if (typeof value === 'number') {
        return { name, value: { longValue: value } };
      } else {
        return { name, value: { booleanValue: value } };
      }
    }
  }

  /**
   * Update the MIME type of a file node via GraphQL setProperties
   */
  async updateMimeType(path: string, mimeType: string): Promise<void> {
    await this.setProperties(path, [{ name: 'jcr:mimeType', value: { stringValue: mimeType } }]);
  }

  /**
   * Update the character encoding of a file node via GraphQL setProperties
   */
  async updateEncoding(path: string, encoding: string): Promise<void> {
    await this.setProperties(path, [{ name: 'jcr:encoding', value: { stringValue: encoding } }]);
  }

  // =========================================================================
  // Multipart Upload
  // =========================================================================

  /**
   * Initiate a multipart upload
   */
  async initiateMultipartUpload(input?: InitiateMultipartUploadInput): Promise<MultipartUploadInfo> {
    const data = await this.#client.mutation<{ initiateMultipartUpload: MultipartUploadInfo }>(
      CONTENT_MUTATIONS.INITIATE_MULTIPART_UPLOAD,
      { input: input ?? {} }
    );
    return data.initiateMultipartUpload;
  }

  /**
   * Append a chunk to a multipart upload
   */
  async appendMultipartUploadChunk(
    uploadId: string,
    chunkData: string // Base64 encoded
  ): Promise<MultipartUploadInfo> {
    const data = await this.#client.mutation<{ appendMultipartUploadChunk: MultipartUploadInfo }>(
      CONTENT_MUTATIONS.APPEND_MULTIPART_UPLOAD_CHUNK,
      { input: { uploadId, data: chunkData } }
    );
    return data.appendMultipartUploadChunk;
  }

  /**
   * Complete a multipart upload and create the file
   */
  async completeMultipartUpload(
    uploadId: string,
    path: string,
    name: string,
    mimeType: string,
    overwrite?: boolean
  ): Promise<Node> {
    const data = await this.#client.mutation<{ completeMultipartUpload: Node }>(
      CONTENT_MUTATIONS.COMPLETE_MULTIPART_UPLOAD,
      { input: { uploadId, path, name, mimeType, overwrite } }
    );
    return data.completeMultipartUpload;
  }

  /**
   * Abort a multipart upload
   */
  async abortMultipartUpload(uploadId: string): Promise<boolean> {
    const data = await this.#client.mutation<{ abortMultipartUpload: boolean }>(
      CONTENT_MUTATIONS.ABORT_MULTIPART_UPLOAD,
      { input: { uploadId } }
    );
    return data.abortMultipartUpload;
  }
}
