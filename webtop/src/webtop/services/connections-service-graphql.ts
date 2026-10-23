/**
 * Linked accounts: the Google / Microsoft accounts the signed-in user linked
 * to their Webtop account, and the permission bundles granted to each.
 *
 * Reading and revoking go through GraphQL. Linking and granting happen in a
 * popup on /bin/auth.cgi/oauth/authorize, which ends on a page that reports
 * back over the `webtop-connections` BroadcastChannel and closes itself.
 * {@link ensure} is what an app calls before it uses a bundle: it resolves at
 * once when the bundle is granted, and otherwise opens the popup and resolves
 * when the user is done (incremental authorization: an app asks for what it
 * needs, when it needs it).
 */

import { GraphQLClient } from '../graphql/client.js';
import { CONNECTIONS_QUERIES, CONNECTIONS_MUTATIONS } from '../graphql/queries/connections.js';
import type { ConnectionEvent, LinkedAccount, LinkedAccounts } from '../graphql/types.js';

export const CONNECTIONS_CHANNEL = 'webtop-connections';

/** How long a popup may stay open before the wait gives up. */
const GRANT_TIMEOUT_MS = 10 * 60 * 1000;

export class ConnectionsServiceGraphQL {
  #client: GraphQLClient;

  constructor(client: GraphQLClient) {
    this.#client = client;
  }

  async getLinkedAccounts(): Promise<LinkedAccounts> {
    const data = await this.#client.query<{ linkedAccounts: LinkedAccounts }>(
      CONNECTIONS_QUERIES.LINKED_ACCOUNTS,
      {}
    );
    return data.linkedAccounts;
  }

  /** Removes the link to the provider. Returns false when there was none. */
  async unlink(provider: string): Promise<boolean> {
    const data = await this.#client.mutation<{ unlinkAccount: boolean }>(
      CONNECTIONS_MUTATIONS.UNLINK_ACCOUNT,
      { provider }
    );
    return data.unlinkAccount;
  }

  /** Stops using a bundle of a linked account. */
  async revokeBundle(provider: string, bundle: string): Promise<LinkedAccount> {
    const data = await this.#client.mutation<{ revokeLinkedAccountBundle: LinkedAccount }>(
      CONNECTIONS_MUTATIONS.REVOKE_LINKED_ACCOUNT_BUNDLE,
      { provider, bundle }
    );
    return data.revokeLinkedAccountBundle;
  }

  /** The URL of the page that starts a link (no bundle) or a grant. */
  authorizeUrl(authorizePath: string, provider: string, bundle?: string | null): string {
    const params = new URLSearchParams({ provider });
    if (bundle) params.set('bundle', bundle);
    params.set('return', returnPath());
    return `${authorizePath}?${params.toString()}`;
  }

  /**
   * Opens the grant page in a popup and resolves with what it reports.
   * Rejects when the popup was blocked. A popup closed without a report
   * resolves as not ok.
   */
  async grant(provider: string, bundle?: string | null, authorizePath?: string): Promise<ConnectionEvent> {
    const path = authorizePath || (await this.getLinkedAccounts()).authorizePath;
    const url = this.authorizeUrl(path, provider, bundle);
    const popup = window.open(url, 'webtop-connection', 'popup=yes,width=560,height=720');
    if (!popup) {
      throw new Error('popup_blocked');
    }
    return await new Promise<ConnectionEvent>((resolve) => {
      const channel = new BroadcastChannel(CONNECTIONS_CHANNEL);
      let done = false;
      const finish = (event: ConnectionEvent) => {
        if (done) return;
        done = true;
        channel.close();
        clearInterval(watch);
        clearTimeout(timer);
        resolve(event);
      };
      channel.onmessage = (message: MessageEvent) => {
        const event = message.data as ConnectionEvent;
        if (event && event.type === 'connection' && (!event.provider || event.provider === provider)) {
          finish(event);
        }
      };
      // The user may close the popup without finishing; give the report a
      // moment to arrive first, since the page closes itself right after posting.
      const watch = setInterval(() => {
        if (popup.closed) {
          setTimeout(() => finish({ type: 'connection', ok: false, provider, bundle: bundle ?? null, error: 'closed' }), 500);
          clearInterval(watch);
        }
      }, 500);
      const timer = setTimeout(() => finish({ type: 'connection', ok: false, provider, bundle: bundle ?? null, error: 'timeout' }), GRANT_TIMEOUT_MS);
    });
  }

  /**
   * Makes sure the user has the bundle of the provider: true when it is
   * granted, already or after the popup; false when the user declined or the
   * provider is not configured. Rejects when the popup was blocked.
   */
  async ensure(provider: string, bundle: string): Promise<boolean> {
    const linked = await this.getLinkedAccounts();
    if (!linked.providers.some((p) => p.id === provider)) {
      return false;
    }
    const account = linked.accounts.find((a) => a.provider === provider);
    if (account && !account.needsReauth && account.bundles.includes(bundle)) {
      return true;
    }
    const result = await this.grant(provider, bundle, linked.authorizePath);
    return result.ok;
  }
}

/** Where the grant page sends the user when it cannot close itself: the Webtop. */
function returnPath(): string {
  try {
    return (window.top || window).location.pathname;
  } catch (_) {
    return window.location.pathname;
  }
}
