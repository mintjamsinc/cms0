/**
 * GraphQL documents for the MCP connection: the `mcpConnection` query and the
 * `setMcpConnection` mutation served by the platform schema
 * (mcp-schema.graphqls). Both act on the workspace the queried endpoint is
 * bound to, and on the caller's own connection.
 */

const MCP_CONNECTION_FIELDS = `
  available
  enabled
  write
  writeAllowed
  enabledAt
  serverName
  endpointPath
  clients {
    name
    redirectTarget
    authorizedAt
  }
`;

export const MCP_QUERIES = {
  MCP_CONNECTION: `
    query McpConnection {
      mcpConnection {
        ${MCP_CONNECTION_FIELDS}
      }
    }
  `,
};

export const MCP_MUTATIONS = {
  SET_MCP_CONNECTION: `
    mutation SetMcpConnection($input: SetMcpConnectionInput!) {
      setMcpConnection(input: $input) {
        ${MCP_CONNECTION_FIELDS}
      }
    }
  `,
};
