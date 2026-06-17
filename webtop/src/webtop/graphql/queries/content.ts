/**
 * Content Management GraphQL Queries and Mutations
 */

// =============================================================================
// Fragments
// =============================================================================

export const NODE_BASIC_FIELDS = `
  fragment NodeBasicFields on Node {
    path
    name
    nodeType
    uuid
    mimeType
    size
    hasChildren
    modified
    modifiedBy
    modifiedByDisplayName
  }
`;

export const NODE_FULL_FIELDS = `
  fragment NodeFullFields on Node {
    path
    name
    nodeType
    uuid
    created
    createdBy
    createdByDisplayName
    modified
    modifiedBy
    modifiedByDisplayName
    mimeType
    size
    encoding
    downloadUrl
    hasChildren
    isLocked
    lockInfo {
      lockOwner
      lockOwnerDisplayName
      isDeep
      isSessionScoped
      isLockOwningSession
      isLockOwner
    }
  }
`;

export const PROPERTY_VALUE_FIELDS = `
  fragment PropertyValueFields on PropertyValue {
    __typename
    ... on StringPropertyValue { type value }
    ... on StringPropertyValueArray { type values }
    ... on LongPropertyValue { type value }
    ... on LongPropertyValueArray { type values }
    ... on DoublePropertyValue { type value }
    ... on DoublePropertyValueArray { type values }
    ... on BooleanPropertyValue { type value }
    ... on BooleanPropertyValueArray { type values }
    ... on DatePropertyValue { type value }
    ... on DatePropertyValueArray { type values }
    ... on BinaryPropertyValue { type value mimeType size }
    ... on BinaryPropertyValueArray { type mimeTypes sizes }
    ... on ReferencePropertyValue { type value path }
    ... on ReferencePropertyValueArray { type values paths }
    ... on WeakreferencePropertyValue { type value path }
    ... on WeakreferencePropertyValueArray { type values paths }
  }
`;

// =============================================================================
// Queries
// =============================================================================

export const CONTENT_QUERIES = {
  /** Get a single node by path */
  GET_NODE: `
    query GetNode($path: String!) {
      node(path: $path) {
        path
        name
        nodeType
        uuid
        created
        createdBy
        createdByDisplayName
        modified
        modifiedBy
        modifiedByDisplayName
        mimeType
        size
        encoding
        downloadUrl
        scriptable
        webRender {
          templated
          fromDescriptor
          source
          outputs
          documentRoot
        }
        hasChildren
        isLocked
        lockInfo {
          lockOwner
          lockOwnerDisplayName
          isDeep
          isSessionScoped
          isLockOwningSession
          isLockOwner
        }
        isVersionable
        isCheckedOut
        baseVersionName
        properties {
          name
          propertyValue {
            __typename
            ... on StringPropertyValue { type value }
            ... on StringPropertyValueArray { type values }
            ... on LongPropertyValue { type value }
            ... on LongPropertyValueArray { type values }
            ... on DoublePropertyValue { type value }
            ... on DoublePropertyValueArray { type values }
            ... on BooleanPropertyValue { type value }
            ... on BooleanPropertyValueArray { type values }
            ... on DatePropertyValue { type value }
            ... on DatePropertyValueArray { type values }
            ... on BinaryPropertyValue { type value mimeType size }
            ... on BinaryPropertyValueArray { type mimeTypes sizes }
            ... on ReferencePropertyValue { type value path }
            ... on ReferencePropertyValueArray { type values paths }
            ... on WeakreferencePropertyValue { type value path }
            ... on WeakreferencePropertyValueArray { type values paths }
          }
        }
      }
    }
  `,

  /** List children of a node */
  LIST_CHILDREN: `
    query ListChildren($path: String!, $first: Int, $after: String) {
      children(path: $path, first: $first, after: $after) {
        edges {
          node {
            path
            name
            nodeType
            uuid
            mimeType
            size
            hasChildren
            created
            createdBy
            createdByDisplayName
            modified
            modifiedBy
            modifiedByDisplayName
            downloadUrl
            isLocked
            lockInfo {
              lockOwner
              lockOwnerDisplayName
              isDeep
              isSessionScoped
              isLockOwningSession
              isLockOwner
            }
            isVersionable
            isCheckedOut
            baseVersionName
            properties {
              name
              propertyValue {
                __typename
                ... on StringPropertyValue { type value }
              }
            }
          }
          cursor
        }
        pageInfo {
          hasNextPage
          hasPreviousPage
          startCursor
          endCursor
        }
        totalCount
      }
    }
  `,

  /** Get references to a node */
  GET_REFERENCES: `
    query GetReferences($path: String!, $first: Int, $after: String) {
      references(path: $path, first: $first, after: $after) {
        edges {
          node {
            path
            name
            nodeType
          }
          cursor
        }
        pageInfo {
          hasNextPage
          endCursor
        }
        totalCount
      }
    }
  `,

  /** Full-text search */
  SEARCH: `
    query Search($text: String!, $path: String, $first: Int, $after: String) {
      search(text: $text, path: $path, first: $first, after: $after) {
        edges {
          node {
            path
            name
            nodeType
            mimeType
            size
            modified
            modifiedBy
            score
          }
          cursor
        }
        pageInfo {
          hasNextPage
          hasPreviousPage
          startCursor
          endCursor
        }
        totalCount
      }
    }
  `,

  /** Execute XPath query */
  XPATH: `
    query XPath($query: String!, $first: Int, $after: String) {
      xpath(query: $query, first: $first, after: $after) {
        edges {
          node {
            path
            name
            nodeType
            mimeType
            size
            modified
            modifiedBy
          }
          cursor
        }
        pageInfo {
          hasNextPage
          endCursor
        }
        totalCount
      }
    }
  `,

  /** Execute XPath query with properties */
  XPATH_WITH_PROPERTIES: `
    query XPathWithProperties($query: String!, $first: Int, $after: String) {
      xpath(query: $query, first: $first, after: $after) {
        edges {
          node {
            path
            name
            nodeType
            uuid
            properties {
              name
              propertyValue {
                __typename
                ... on StringPropertyValue { type value }
                ... on StringPropertyValueArray { type values }
                ... on LongPropertyValue { type value }
                ... on LongPropertyValueArray { type values }
                ... on DoublePropertyValue { type value }
                ... on DoublePropertyValueArray { type values }
                ... on BooleanPropertyValue { type value }
                ... on BooleanPropertyValueArray { type values }
                ... on DatePropertyValue { type value }
                ... on DatePropertyValueArray { type values }
                ... on ReferencePropertyValue { type value path }
                ... on ReferencePropertyValueArray { type values paths }
                ... on WeakreferencePropertyValue { type value path }
                ... on WeakreferencePropertyValueArray { type values paths }
              }
            }
          }
          cursor
        }
        pageInfo {
          hasNextPage
          endCursor
        }
        totalCount
      }
    }
  `,

  /** Execute JCR query (SQL2, XPath, SQL) */
  QUERY: `
    query Query($statement: String!, $language: String, $first: Int, $after: String) {
      query(statement: $statement, language: $language, first: $first, after: $after) {
        edges {
          node {
            path
            name
            nodeType
            mimeType
            size
            modified
            modifiedBy
          }
          cursor
        }
        pageInfo {
          hasNextPage
          endCursor
        }
        totalCount
      }
    }
  `,

  /** Get access control list */
  GET_ACCESS_CONTROL: `
    query GetAccessControl($path: String!) {
      accessControl(path: $path) {
        entries {
          principal {
            id
            displayName
            isGroup
          }
          privileges
          allow
        }
      }
    }
  `,

  /** Get effective access control list (hierarchical: current path + ancestors) */
  GET_EFFECTIVE_ACCESS_CONTROL: `
    query GetEffectiveAccessControl($path: String!) {
      effectiveAccessControl(path: $path) {
        path
        entries {
          principal {
            id
            displayName
            isGroup
          }
          privileges
          allow
        }
      }
    }
  `,

  /** Search principals (users and groups) */
  SEARCH_PRINCIPALS: `
    query SearchPrincipals($keyword: String!, $offset: Int, $limit: Int) {
      searchPrincipals(keyword: $keyword, offset: $offset, limit: $limit) {
        identifier
        isGroup
        isService
        displayName
      }
    }
  `,

  /** Get version history */
  GET_VERSION_HISTORY: `
    query GetVersionHistory($path: String!) {
      versionHistory(path: $path) {
        edges {
          node {
            name
            created
            createdBy
            predecessors
            successors
            frozenNodePath
          }
          cursor
        }
        pageInfo {
          hasNextPage
          hasPreviousPage
          startCursor
          endCursor
        }
        totalCount
        baseVersion {
          name
          created
        }
        versionableUuid
      }
    }
  `,
} as const;

// =============================================================================
// Mutations
// =============================================================================

export const CONTENT_MUTATIONS = {
  /** Create a new folder */
  CREATE_FOLDER: `
    mutation CreateFolder($input: CreateFolderInput!) {
      createFolder(input: $input) {
        path
        name
        nodeType
        created
        createdBy
      }
    }
  `,

  /** Create a new file */
  CREATE_FILE: `
    mutation CreateFile($input: CreateFileInput!) {
      createFile(input: $input) {
        path
        name
        nodeType
        mimeType
        size
        created
        createdBy
      }
    }
  `,

  /** Delete a node */
  DELETE_NODE: `
    mutation DeleteNode($input: DeleteNodeInput!) {
      deleteNode(input: $input)
    }
  `,

  /**
   * Async deletion job — step 1.
   * Allocates a job record; returns its id so subsequent appends can target it.
   */
  INIT_DELETE_NODES: `
    mutation InitDeleteNodes($input: InitDeleteNodesInput!) {
      initDeleteNodes(input: $input) {
        jobId
        status
      }
    }
  `,

  /**
   * Async deletion job — step 2 (repeatable, max 100 paths per call).
   * Body of the job record is appended; rejected if the job has already started.
   */
  APPEND_DELETE_NODES: `
    mutation AppendDeleteNodes($input: AppendDeleteNodesInput!) {
      appendDeleteNodes(input: $input) {
        jobId
        status
        itemsAccepted
      }
    }
  `,

  /**
   * Async deletion job — step 3.
   * Hands the job to the background worker. Progress is then delivered
   * via the jobProgress(jobId) GraphQL subscription.
   */
  START_DELETE_NODES: `
    mutation StartDeleteNodes($input: StartDeleteNodesInput!) {
      startDeleteNodes(input: $input) {
        jobId
        status
        itemsTotal
      }
    }
  `,

  /**
   * Async deletion job — abort.
   * Signals the worker to stop at its next safe point (between leaves).
   * Already-deleted items remain deleted.
   */
  ABORT_DELETE_NODES: `
    mutation AbortDeleteNodes($input: AbortDeleteNodesInput!) {
      abortDeleteNodes(input: $input) {
        jobId
        status
      }
    }
  `,

  /**
   * Async ZIP-archive download job — step 1.
   * Allocates a job record; returns its id so subsequent appends can target it.
   */
  INIT_DOWNLOAD_ARCHIVE: `
    mutation InitDownloadArchive($input: InitDownloadArchiveInput!) {
      initDownloadArchive(input: $input) {
        jobId
        status
      }
    }
  `,

  /**
   * Async ZIP-archive download job — step 2 (repeatable, max 100 paths per call).
   * Appends the top-level items to bundle; rejected if the job has already started.
   */
  APPEND_DOWNLOAD_ARCHIVE: `
    mutation AppendDownloadArchive($input: AppendDownloadArchiveInput!) {
      appendDownloadArchive(input: $input) {
        jobId
        status
        itemsAccepted
      }
    }
  `,

  /**
   * Async ZIP-archive download job — step 3.
   * Records the archive file name and hands the job to the background worker.
   * Progress (and the terminal downloadUrl) arrive via the jobProgress(jobId)
   * GraphQL subscription.
   */
  START_DOWNLOAD_ARCHIVE: `
    mutation StartDownloadArchive($input: StartDownloadArchiveInput!) {
      startDownloadArchive(input: $input) {
        jobId
        status
        itemsTotal
      }
    }
  `,

  /**
   * Async ZIP-archive download job — abort.
   * Signals the worker to stop at its next safe point (between files).
   */
  ABORT_DOWNLOAD_ARCHIVE: `
    mutation AbortDownloadArchive($input: AbortDownloadArchiveInput!) {
      abortDownloadArchive(input: $input) {
        jobId
        status
      }
    }
  `,

  /**
   * Async archive import job — step 1.
   * Allocates a job record; returns its id. The archive ZIP is uploaded
   * separately via the multipart-upload mutations before the job is started.
   */
  INIT_IMPORT_ARCHIVE: `
    mutation InitImportArchive($input: InitImportArchiveInput!) {
      initImportArchive(input: $input) {
        jobId
        status
      }
    }
  `,

  /**
   * Async archive import job — step 2.
   * Records the import options (destination, identity, conflict/collision
   * policy, dry run) and hands the job to the background worker. Progress
   * arrives via the jobProgress(jobId) subscription.
   */
  START_IMPORT_ARCHIVE: `
    mutation StartImportArchive($input: StartImportArchiveInput!) {
      startImportArchive(input: $input) {
        jobId
        status
      }
    }
  `,

  /**
   * Async archive import job — abort.
   * Signals the worker to stop at its next safe point (between nodes).
   */
  ABORT_IMPORT_ARCHIVE: `
    mutation AbortImportArchive($input: AbortImportArchiveInput!) {
      abortImportArchive(input: $input) {
        jobId
        status
      }
    }
  `,

  /** Rename a node */
  RENAME_NODE: `
    mutation RenameNode($input: RenameNodeInput!) {
      renameNode(input: $input) {
        path
        name
        nodeType
        modified
        modifiedBy
      }
    }
  `,

  /** Move a node to a different parent directory */
  MOVE_NODE: `
    mutation MoveNode($input: MoveNodeInput!) {
      moveNode(input: $input) {
        path
        name
        nodeType
        modified
        modifiedBy
      }
    }
  `,

  /** Copy a node to a different parent directory (deep copy) */
  COPY_NODE: `
    mutation CopyNode($input: CopyNodeInput!) {
      copyNode(input: $input) {
        path
        name
        nodeType
        modified
        modifiedBy
      }
    }
  `,

  /** Lock a node */
  LOCK_NODE: `
    mutation LockNode($input: LockNodeInput!) {
      lockNode(input: $input) {
        path
        isLocked
        lockInfo {
          lockOwner
          lockOwnerDisplayName
          isDeep
          isSessionScoped
          isLockOwningSession
          isLockOwner
        }
      }
    }
  `,

  /** Unlock a node */
  UNLOCK_NODE: `
    mutation UnlockNode($input: UnlockNodeInput!) {
      unlockNode(input: $input)
    }
  `,

  /** Set properties on a node */
  SET_PROPERTIES: `
    mutation SetProperties($input: SetPropertiesInput!) {
      setProperties(input: $input) {
        node {
          path
          modified
          modifiedBy
          properties {
            name
            propertyValue {
              __typename
              ... on StringPropertyValue { type value }
              ... on LongPropertyValue { type value }
              ... on BooleanPropertyValue { type value }
              ... on DatePropertyValue { type value }
              ... on BinaryPropertyValue { type value mimeType size }
            }
          }
        }
        errors {
          propertyName
          message
        }
      }
    }
  `,

  /** Add a mixin type */
  ADD_MIXIN: `
    mutation AddMixin($input: AddMixinInput!) {
      addMixin(input: $input) {
        path
        nodeType
        uuid
      }
    }
  `,

  /** Delete a mixin type */
  DELETE_MIXIN: `
    mutation DeleteMixin($input: DeleteMixinInput!) {
      deleteMixin(input: $input) {
        path
        nodeType
        uuid
      }
    }
  `,

  /** Set access control */
  SET_ACCESS_CONTROL: `
    mutation SetAccessControl($input: SetAccessControlInput!) {
      setAccessControl(input: $input) {
        entries {
          principal
          privileges
          allow
        }
      }
    }
  `,

  /** Delete access control entry */
  DELETE_ACCESS_CONTROL: `
    mutation DeleteAccessControl($input: DeleteAccessControlInput!) {
      deleteAccessControl(input: $input)
    }
  `,

  /** Check in a versionable node */
  CHECKIN: `
    mutation Checkin($input: CheckinInput!) {
      checkin(input: $input) {
        name
        created
      }
    }
  `,

  /** Check out a versionable node */
  CHECKOUT: `
    mutation Checkout($input: CheckoutInput!) {
      checkout(input: $input)
    }
  `,

  /** Restore a version */
  RESTORE_VERSION: `
    mutation RestoreVersion($input: RestoreVersionInput!) {
      restoreVersion(input: $input) {
        path
        modified
        modifiedBy
      }
    }
  `,

  /** Add version control to a node */
  ADD_VERSION_CONTROL: `
    mutation AddVersionControl($input: AddVersionControlInput!) {
      addVersionControl(input: $input) {
        path
        nodeType
        uuid
      }
    }
  `,

  /** Cancel checkout (uncheckout) */
  UNCHECKOUT: `
    mutation Uncheckout($input: UncheckoutInput!) {
      uncheckout(input: $input)
    }
  `,

  /** Create checkpoint (version while keeping checked out) */
  CHECKPOINT: `
    mutation Checkpoint($input: CheckpointInput!) {
      checkpoint(input: $input) {
        name
        created
      }
    }
  `,

  /** Initiate a multipart upload */
  INITIATE_MULTIPART_UPLOAD: `
    mutation InitiateMultipartUpload($input: InitiateMultipartUploadInput) {
      initiateMultipartUpload(input: $input) {
        uploadId
        totalSize
      }
    }
  `,

  /** Append chunk to multipart upload */
  APPEND_MULTIPART_UPLOAD_CHUNK: `
    mutation AppendMultipartUploadChunk($input: AppendMultipartUploadChunkInput!) {
      appendMultipartUploadChunk(input: $input) {
        uploadId
        totalSize
      }
    }
  `,

  /** Complete multipart upload and create file */
  COMPLETE_MULTIPART_UPLOAD: `
    mutation CompleteMultipartUpload($input: CompleteMultipartUploadInput!) {
      completeMultipartUpload(input: $input) {
        path
        name
        nodeType
        mimeType
        size
        created
        createdBy
      }
    }
  `,

  /** Abort multipart upload */
  ABORT_MULTIPART_UPLOAD: `
    mutation AbortMultipartUpload($input: AbortMultipartUploadInput!) {
      abortMultipartUpload(input: $input)
    }
  `,
} as const;
