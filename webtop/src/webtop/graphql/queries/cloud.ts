/**
 * GraphQL documents for the files bundle of a linked account: the user's
 * drives at Google or Microsoft, served by the platform schema
 * (cloud-schema.graphqls) in every workspace. A drive is "me" (the user's
 * own) or the id of a shared drive / library; within it, entries are named
 * by the provider's ids. Files are copied between a drive and the workspace
 * the client is bound to.
 */

const CLOUD_ENTRY_FIELDS = `
  provider
  driveId
  id
  name
  mimeType
  size
  folder
  webUrl
  modified
  created
  nativeType
  parentId
  etag
  owner
  shared
  hasThumbnail
  path
  contentUrl
`;

const CLOUD_DRIVE_FIELDS = `
  provider
  id
  name
  kind
  webUrl
`;

const CLOUD_ENTRIES_FIELDS = `
  entries {
    ${CLOUD_ENTRY_FIELDS}
  }
  nextCursor
`;

const CLOUD_IMPORT_FIELDS = `
  path
  name
  mimeType
  size
  entry {
    ${CLOUD_ENTRY_FIELDS}
  }
`;

export const CLOUD_QUERIES = {
  STORAGES: `
    query CloudStorages {
      cloudStorages {
        provider
        name
        granted
        searchScope
        facets
        canPreview
      }
    }
  `,
  DRIVES: `
    query CloudDrives($provider: String!) {
      cloudDrives(provider: $provider) {
        ${CLOUD_DRIVE_FIELDS}
      }
    }
  `,
  FOLDER: `
    query CloudFolder($provider: String!, $driveId: String!, $folderId: String) {
      cloudFolder(provider: $provider, driveId: $driveId, folderId: $folderId) {
        drive {
          ${CLOUD_DRIVE_FIELDS}
        }
        entry {
          ${CLOUD_ENTRY_FIELDS}
        }
        ancestors {
          ${CLOUD_ENTRY_FIELDS}
        }
      }
    }
  `,
  CHILDREN: `
    query CloudChildren($provider: String!, $driveId: String!, $folderId: String, $after: String, $first: Int, $order: CloudOrder) {
      cloudChildren(provider: $provider, driveId: $driveId, folderId: $folderId, after: $after, first: $first, order: $order) {
        ${CLOUD_ENTRIES_FIELDS}
      }
    }
  `,
  SEARCH: `
    query CloudSearch($provider: String!, $driveId: String!, $folderId: String, $query: CloudQuery!, $after: String, $first: Int) {
      cloudSearch(provider: $provider, driveId: $driveId, folderId: $folderId, query: $query, after: $after, first: $first) {
        ${CLOUD_ENTRIES_FIELDS}
      }
    }
  `,
  FILE: `
    query CloudFile($provider: String!, $driveId: String!, $id: String!) {
      cloudFile(provider: $provider, driveId: $driveId, id: $id) {
        ${CLOUD_ENTRY_FIELDS}
      }
    }
  `,
  PREVIEW: `
    query CloudPreview($provider: String!, $driveId: String!, $id: String!) {
      cloudPreview(provider: $provider, driveId: $driveId, id: $id)
    }
  `,
};

export const CLOUD_MUTATIONS = {
  CREATE_FOLDER: `
    mutation CreateCloudFolder($provider: String!, $driveId: String!, $parentId: String, $name: String!) {
      createCloudFolder(provider: $provider, driveId: $driveId, parentId: $parentId, name: $name) {
        ${CLOUD_ENTRY_FIELDS}
      }
    }
  `,
  RENAME: `
    mutation RenameCloudEntry($provider: String!, $driveId: String!, $id: String!, $name: String!) {
      renameCloudEntry(provider: $provider, driveId: $driveId, id: $id, name: $name) {
        ${CLOUD_ENTRY_FIELDS}
      }
    }
  `,
  DELETE: `
    mutation DeleteCloudEntry($provider: String!, $driveId: String!, $id: String!) {
      deleteCloudEntry(provider: $provider, driveId: $driveId, id: $id)
    }
  `,
  MOVE: `
    mutation MoveCloudEntry($provider: String!, $driveId: String!, $id: String!, $toFolderId: String) {
      moveCloudEntry(provider: $provider, driveId: $driveId, id: $id, toFolderId: $toFolderId) {
        ${CLOUD_ENTRY_FIELDS}
      }
    }
  `,
  COPY: `
    mutation CopyCloudEntry($provider: String!, $driveId: String!, $id: String!, $toDriveId: String!, $toFolderId: String, $name: String) {
      copyCloudEntry(provider: $provider, driveId: $driveId, id: $id, toDriveId: $toDriveId, toFolderId: $toFolderId, name: $name) {
        ${CLOUD_ENTRY_FIELDS}
      }
    }
  `,
  IMPORT_FILE: `
    mutation ImportCloudFile($provider: String!, $driveId: String, $id: String!, $folder: String!, $name: String, $exportMimeType: String, $conflict: CloudConflict) {
      importCloudFile(provider: $provider, driveId: $driveId, id: $id, folder: $folder, name: $name, exportMimeType: $exportMimeType, conflict: $conflict) {
        ${CLOUD_IMPORT_FIELDS}
      }
    }
  `,
  EXPORT_FILE: `
    mutation ExportCloudFile($provider: String!, $path: String!, $driveId: String, $folderId: String, $name: String, $convert: Boolean) {
      exportCloudFile(provider: $provider, path: $path, driveId: $driveId, folderId: $folderId, name: $name, convert: $convert) {
        ${CLOUD_ENTRY_FIELDS}
      }
    }
  `,
  EXPORT_CONTENT: `
    mutation ExportCloudContent($provider: String!, $driveId: String, $folderId: String, $name: String!, $mimeType: String!, $content: String!, $convert: Boolean) {
      exportCloudContent(provider: $provider, driveId: $driveId, folderId: $folderId, name: $name, mimeType: $mimeType, content: $content, convert: $convert) {
        ${CLOUD_ENTRY_FIELDS}
      }
    }
  `,
  CREATE_LINK: `
    mutation CreateCloudLink($provider: String!, $driveId: String, $id: String!, $folder: String!, $name: String, $conflict: CloudConflict) {
      createCloudLink(provider: $provider, driveId: $driveId, id: $id, folder: $folder, name: $name, conflict: $conflict) {
        ${CLOUD_IMPORT_FIELDS}
      }
    }
  `,
};
