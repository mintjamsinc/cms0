/**
 * GraphQL documents for the files bundle of a linked account: the user's
 * Google Drive or OneDrive, served by the platform schema
 * (cloud-schema.graphqls) in every workspace. Files are copied between the
 * drive and the workspace the client is bound to.
 */

const CLOUD_ENTRY_FIELDS = `
  provider
  id
  name
  mimeType
  size
  folder
  webUrl
  modified
  nativeType
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
  PICKER: `
    query CloudPicker($provider: String!) {
      cloudPicker(provider: $provider) {
        provider
        available
        accessToken
        apiKey
        appId
      }
    }
  `,
  CHILDREN: `
    query CloudChildren($provider: String!, $folderId: String) {
      cloudChildren(provider: $provider, folderId: $folderId) {
        ${CLOUD_ENTRY_FIELDS}
      }
    }
  `,
  FILE: `
    query CloudFile($provider: String!, $id: String!) {
      cloudFile(provider: $provider, id: $id) {
        ${CLOUD_ENTRY_FIELDS}
      }
    }
  `,
};

export const CLOUD_MUTATIONS = {
  IMPORT_FILE: `
    mutation ImportCloudFile($provider: String!, $id: String!, $folder: String!, $name: String, $exportMimeType: String, $conflict: CloudConflict) {
      importCloudFile(provider: $provider, id: $id, folder: $folder, name: $name, exportMimeType: $exportMimeType, conflict: $conflict) {
        ${CLOUD_IMPORT_FIELDS}
      }
    }
  `,
  EXPORT_FILE: `
    mutation ExportCloudFile($provider: String!, $path: String!, $folderId: String, $name: String, $convert: Boolean) {
      exportCloudFile(provider: $provider, path: $path, folderId: $folderId, name: $name, convert: $convert) {
        ${CLOUD_ENTRY_FIELDS}
      }
    }
  `,
  EXPORT_CONTENT: `
    mutation ExportCloudContent($provider: String!, $folderId: String, $name: String!, $mimeType: String!, $content: String!, $convert: Boolean) {
      exportCloudContent(provider: $provider, folderId: $folderId, name: $name, mimeType: $mimeType, content: $content, convert: $convert) {
        ${CLOUD_ENTRY_FIELDS}
      }
    }
  `,
  CREATE_LINK: `
    mutation CreateCloudLink($provider: String!, $id: String!, $folder: String!, $name: String, $conflict: CloudConflict) {
      createCloudLink(provider: $provider, id: $id, folder: $folder, name: $name, conflict: $conflict) {
        ${CLOUD_IMPORT_FIELDS}
      }
    }
  `,
};
