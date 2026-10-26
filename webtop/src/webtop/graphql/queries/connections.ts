/**
 * GraphQL documents for linked accounts: the Google / Microsoft accounts the
 * signed-in user linked to their Webtop account, served by the platform
 * schema (connections-schema.graphqls). The accounts live in the user's home
 * in the system workspace, so these go to the system workspace's endpoint
 * (WebtopAPI.connections), whichever workspace the Webtop runs in. Linking
 * and granting happen in the browser (/bin/auth.cgi/oauth/authorize); these
 * documents read the result and take grants away.
 */

const LINKED_ACCOUNT_FIELDS = `
  provider
  subject
  email
  displayName
  bundles
  linkedAt
  lastUsed
  needsReauth
  bundlesNeedingGrant
`;

export const CONNECTIONS_QUERIES = {
  LINKED_ACCOUNTS: `
    query LinkedAccounts {
      linkedAccounts {
        providers {
          id
          name
        }
        accounts {
          ${LINKED_ACCOUNT_FIELDS}
        }
        bundles
        authorizePath
        configurationError
      }
    }
  `,
};

export const CONNECTIONS_MUTATIONS = {
  UNLINK_ACCOUNT: `
    mutation UnlinkAccount($provider: String!) {
      unlinkAccount(provider: $provider)
    }
  `,
  REVOKE_LINKED_ACCOUNT_BUNDLE: `
    mutation RevokeLinkedAccountBundle($provider: String!, $bundle: String!) {
      revokeLinkedAccountBundle(provider: $provider, bundle: $bundle) {
        ${LINKED_ACCOUNT_FIELDS}
      }
    }
  `,
};
