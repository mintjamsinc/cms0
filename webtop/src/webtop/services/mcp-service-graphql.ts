/**
 * MCP connection service: whether MCP clients (such as Claude) may work in a
 * workspace as the signed-in user, through the platform GraphQL API. A
 * connection belongs to one workspace, so each call goes to that workspace's
 * endpoint.
 */

import { GraphQLClient, createGraphQLClient } from '../graphql/client.js';
import { MCP_QUERIES, MCP_MUTATIONS } from '../graphql/queries/mcp.js';
import type { McpConnection, SetMcpConnectionInput } from '../graphql/types.js';

export class McpServiceGraphQL {
  #clients = new Map<string, GraphQLClient>();

  #client(workspace: string): GraphQLClient {
    let client = this.#clients.get(workspace);
    if (!client) {
      client = createGraphQLClient(workspace);
      this.#clients.set(workspace, client);
    }
    return client;
  }

  async getConnection(workspace: string): Promise<McpConnection> {
    const data = await this.#client(workspace).query<{ mcpConnection: McpConnection }>(
      MCP_QUERIES.MCP_CONNECTION,
      {}
    );
    return data.mcpConnection;
  }

  async setConnection(workspace: string, input: SetMcpConnectionInput): Promise<McpConnection> {
    const data = await this.#client(workspace).mutation<{ setMcpConnection: McpConnection }>(
      MCP_MUTATIONS.SET_MCP_CONNECTION,
      { input }
    );
    return data.setMcpConnection;
  }
}

/**
 * The name an MCP client knows a workspace's server by: `cms-<server>-<workspace>`.
 * The server part tells this CMS from the user's others (development,
 * production) — the configured label, or the host name it is reached at.
 */
export function mcpServerName(serverName: string | null, hostname: string, workspace: string): string {
  const slug = (value: string) => value.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
  return ['cms', slug(serverName || hostname), slug(workspace)].filter(part => part).join('-');
}
