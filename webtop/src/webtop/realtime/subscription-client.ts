/**
 * graphql-sse Subscription Client (single connection mode)
 *
 * All of a client's subscriptions are multiplexed over ONE Server-Sent-Events
 * connection, per the graphql-sse "single connection mode":
 *
 *   1. PUT  …/stream                 -> reserve a token (text/plain)
 *   2. GET  …/stream?token=…         -> open the one SSE stream (EventSource)
 *   3. POST …/stream?token=…         -> add an operation {query, extensions:{operationId}}
 *   4. DELETE …/stream?token&operationId -> stop one operation
 *
 * Events arrive on the single stream as `next` / `complete` frames carrying
 * {id, payload}; results are demultiplexed to handlers by operationId. This keeps
 * the browser to a single HTTP/2 stream regardless of how many subscriptions run
 * (distinct connections mode opened one stream each, which churned HTTP/2 and DB
 * connections).
 */

export type SubscriptionHandler<T = unknown> = (data: T) => void;

export interface SubscriptionClientOptions {
  /** SSE endpoint URL (…/stream). */
  endpoint: string;

  /** Called when the connection is established. */
  onConnect?: () => void;

  /** Called when the connection is lost. */
  onDisconnect?: () => void;

  /** Called on connection error. */
  onError?: (error: Event) => void;
}

interface ActiveOperation {
  document: string;
  handler: SubscriptionHandler;
}

/**
 * graphql-sse client in single connection mode: one EventSource multiplexes every
 * subscription operation.
 */
export class SubscriptionClient {
  #endpoint: string;
  #options: SubscriptionClientOptions;
  #operations = new Map<string, ActiveOperation>();
  #operationSeq = 0;
  #token: string | null = null;
  #eventSource: EventSource | null = null;
  #connecting: Promise<boolean> | null = null;
  #disposed = false;
  #reconnectDelay = 1000;
  #maxReconnectDelay = 30000;
  #reconnectTimer: number | null = null;

  constructor(options: SubscriptionClientOptions) {
    this.#endpoint = options.endpoint;
    this.#options = options;
  }

  /** Whether the single SSE connection is open. */
  get isConnected(): boolean {
    return this.#eventSource?.readyState === EventSource.OPEN;
  }

  /** Number of active subscription operations. */
  get subscriptionCount(): number {
    return this.#operations.size;
  }

  /**
   * Add a subscription. `document` is a full `subscription { … }` query; the handler
   * receives the single root field value from each event.
   */
  subscribe<T>(document: string, handler: SubscriptionHandler<T>): () => void {
    const operationId = String(++this.#operationSeq);
    this.#operations.set(operationId, { document, handler: handler as SubscriptionHandler });
    void this.#startOperation(operationId);
    return () => {
      void this.#unsubscribe(operationId);
    };
  }

  async #startOperation(operationId: string): Promise<void> {
    try {
      const ready = await this.#ensureConnection();
      if (ready && !this.#disposed && this.#operations.has(operationId)) {
        await this.#postOperation(operationId);
      }
    } catch (error) {
      console.warn('[subscription] failed to start operation:', error);
      // A scheduled reconnect (on connection error) re-posts all active operations.
    }
  }

  /** Establish the single connection (PUT reserve + GET stream). Resolves true when open. */
  #ensureConnection(): Promise<boolean> {
    if (this.#disposed) {
      return Promise.resolve(false);
    }
    if (this.#eventSource && this.#eventSource.readyState === EventSource.OPEN && this.#token) {
      return Promise.resolve(true);
    }
    if (this.#connecting) {
      return this.#connecting;
    }
    this.#connecting = this.#openConnection().finally(() => {
      this.#connecting = null;
    });
    return this.#connecting;
  }

  async #openConnection(): Promise<boolean> {
    // 1) Reserve a token.
    const reservation = await fetch(this.#endpoint, { method: 'PUT', credentials: 'include' });
    if (!reservation.ok) {
      throw new Error(`stream reservation failed: ${reservation.status}`);
    }
    const token = (await reservation.text()).trim();
    if (this.#disposed || !token) {
      return false;
    }
    this.#token = token;

    // 2) Open the one SSE stream and wait for it to connect.
    return await new Promise<boolean>((resolve) => {
      const es = new EventSource(`${this.#endpoint}?token=${encodeURIComponent(token)}`, {
        withCredentials: true,
      });
      this.#eventSource = es;
      let settled = false;

      es.onopen = () => {
        this.#reconnectDelay = 1000;
        this.#options.onConnect?.();
        if (!settled) {
          settled = true;
          resolve(true);
        }
      };
      es.onerror = (event) => {
        if (!settled) {
          settled = true;
          resolve(false);
        }
        this.#handleConnectionError(event);
      };
      es.addEventListener('next', (event) => this.#onNext(event as MessageEvent));
      es.addEventListener('complete', (event) => this.#onComplete(event as MessageEvent));
    });
  }

  async #postOperation(operationId: string): Promise<void> {
    const operation = this.#operations.get(operationId);
    if (!operation || !this.#token) {
      return;
    }
    const response = await fetch(`${this.#endpoint}?token=${encodeURIComponent(this.#token)}`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ query: operation.document, extensions: { operationId } }),
    });
    if (response.status === 404) {
      // Token gone (server dropped the connection) — re-establish and re-post.
      this.#handleConnectionError(new Event('error'));
    }
  }

  async #unsubscribe(operationId: string): Promise<void> {
    const existed = this.#operations.delete(operationId);
    if (existed && this.#token) {
      try {
        await fetch(
          `${this.#endpoint}?token=${encodeURIComponent(this.#token)}&operationId=${encodeURIComponent(operationId)}`,
          { method: 'DELETE', credentials: 'include' }
        );
      } catch {
        /* best effort */
      }
    }
    // Close the idle connection when nothing is subscribed.
    if (this.#operations.size === 0) {
      this.#teardown();
    }
  }

  #onNext(event: MessageEvent): void {
    let message: { id?: string; payload?: { data?: Record<string, unknown>; errors?: unknown[] } };
    try {
      message = JSON.parse(event.data);
    } catch {
      return;
    }
    if (!message?.id) {
      return;
    }
    const operation = this.#operations.get(message.id);
    if (!operation) {
      return;
    }
    const result = message.payload;
    if (result?.errors?.length) {
      console.warn('[subscription] GraphQL errors:', result.errors);
    }
    const data = result?.data;
    if (data && typeof data === 'object') {
      // Each subscription has exactly one root field — hand its value to the handler.
      const value = (Object.values(data) as unknown[])[0];
      if (value !== undefined && value !== null) {
        operation.handler(value);
      }
    }
  }

  #onComplete(event: MessageEvent): void {
    let message: { id?: string };
    try {
      message = JSON.parse(event.data);
    } catch {
      return;
    }
    if (message?.id) {
      this.#operations.delete(message.id);
    }
  }

  #handleConnectionError(event: Event): void {
    if (this.#disposed) {
      return;
    }
    this.#options.onError?.(event);
    this.#options.onDisconnect?.();
    // The server drops the token (and its operations) when the SSE drops, so a full
    // re-establish — new token, new stream, re-post every active operation — is needed.
    // Closing the EventSource here also stops its built-in retry of the dead token.
    this.#teardown(true);
    if (this.#operations.size > 0) {
      this.#scheduleReconnect();
    }
  }

  #scheduleReconnect(): void {
    if (this.#disposed || this.#reconnectTimer !== null) {
      return;
    }
    const delay = this.#reconnectDelay;
    this.#reconnectDelay = Math.min(this.#reconnectDelay * 2, this.#maxReconnectDelay);
    this.#reconnectTimer = window.setTimeout(() => {
      this.#reconnectTimer = null;
      void this.#reestablish();
    }, delay);
  }

  async #reestablish(): Promise<void> {
    if (this.#disposed || this.#operations.size === 0) {
      return;
    }
    try {
      const ready = await this.#ensureConnection();
      if (!ready) {
        this.#scheduleReconnect();
        return;
      }
      for (const operationId of [...this.#operations.keys()]) {
        await this.#postOperation(operationId);
      }
    } catch {
      this.#scheduleReconnect();
    }
  }

  /** Closes the connection. Keeps the operation set when reconnecting. */
  #teardown(keepOperations = false): void {
    if (this.#reconnectTimer !== null) {
      clearTimeout(this.#reconnectTimer);
      this.#reconnectTimer = null;
    }
    if (this.#eventSource) {
      try {
        this.#eventSource.close();
      } catch {
        /* ignore */
      }
      this.#eventSource = null;
    }
    this.#token = null;
    if (!keepOperations) {
      this.#operations.clear();
    }
  }

  /** Close the connection and drop all subscriptions. */
  disconnect(): void {
    this.#disposed = true;
    this.#teardown();
  }

  /** Force a reconnect, re-posting all active operations. */
  reconnect(): void {
    this.#reconnectDelay = 1000;
    this.#teardown(true);
    if (this.#operations.size > 0) {
      void this.#reestablish();
    }
  }
}

/**
 * Create a subscription client for a workspace.
 *
 * Points at the platform engine's graphql-sse stream on /bin/graphql.cgi (the
 * production endpoint as of the #7b cutover; queries/mutations share this engine).
 */
export function createSubscriptionClient(
  workspace: string,
  options: Partial<SubscriptionClientOptions> = {}
): SubscriptionClient {
  return new SubscriptionClient({
    endpoint: `/bin/graphql.cgi/${workspace}/stream`,
    ...options,
  });
}
