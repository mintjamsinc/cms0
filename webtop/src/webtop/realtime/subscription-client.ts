/**
 * SSE Subscription Client
 *
 * Manages Server-Sent Events connections for real-time GraphQL subscriptions.
 */

export interface SubscriptionMessage {
  subscription: string;
  data: unknown;
}

export type SubscriptionHandler<T = unknown> = (data: T) => void;

export interface SubscriptionClientOptions {
  /** SSE endpoint URL */
  endpoint: string;

  /** Initial reconnect delay in ms */
  reconnectDelay?: number;

  /** Maximum reconnect delay in ms */
  maxReconnectDelay?: number;

  /** Called when connection is established */
  onConnect?: () => void;

  /** Called when connection is lost */
  onDisconnect?: () => void;

  /** Called on connection error */
  onError?: (error: Event) => void;
}

/**
 * SSE-based subscription client for real-time updates
 */
export class SubscriptionClient {
  #endpoint: string;
  #eventSource: EventSource | null = null;
  #handlers = new Map<string, Set<SubscriptionHandler>>();
  #reconnectDelay: number;
  #maxReconnectDelay: number;
  #currentDelay: number;
  #subscriptions: string[] = [];
  #options: SubscriptionClientOptions;
  #reconnectTimer: number | null = null;
  #isConnecting = false;

  constructor(options: SubscriptionClientOptions) {
    this.#endpoint = options.endpoint;
    this.#reconnectDelay = options.reconnectDelay ?? 1000;
    this.#maxReconnectDelay = options.maxReconnectDelay ?? 30000;
    this.#currentDelay = this.#reconnectDelay;
    this.#options = options;
  }

  /** Whether the client is currently connected */
  get isConnected(): boolean {
    return this.#eventSource?.readyState === EventSource.OPEN;
  }

  /** Number of active subscriptions */
  get subscriptionCount(): number {
    return this.#subscriptions.length;
  }

  /**
   * Subscribe to a GraphQL subscription
   *
   * @param subscription - Subscription query (e.g., 'nodeChanged(path: "/content")')
   * @param handler - Callback for received data
   * @returns Unsubscribe function
   */
  subscribe<T>(
    subscription: string,
    handler: SubscriptionHandler<T>
  ): () => void {
    // Normalize subscription string
    const normalizedSub = this.#normalizeSubscription(subscription);

    if (!this.#handlers.has(normalizedSub)) {
      this.#handlers.set(normalizedSub, new Set());
      this.#subscriptions.push(normalizedSub);
      this.#reconnect();
    }

    this.#handlers.get(normalizedSub)!.add(handler as SubscriptionHandler);

    // Return unsubscribe function
    return () => {
      this.#handlers.get(normalizedSub)?.delete(handler as SubscriptionHandler);

      // Remove subscription if no more handlers
      if (this.#handlers.get(normalizedSub)?.size === 0) {
        this.#handlers.delete(normalizedSub);
        this.#subscriptions = this.#subscriptions.filter(s => s !== normalizedSub);
        this.#reconnect();
      }
    };
  }

  /**
   * Normalize subscription string for consistent handling
   */
  #normalizeSubscription(subscription: string): string {
    return subscription.trim();
  }

  /**
   * Connect to SSE endpoint
   */
  #connect(): void {
    if (this.#subscriptions.length === 0) {
      this.#close();
      return;
    }

    if (this.#isConnecting) return;
    this.#isConnecting = true;

    const params = new URLSearchParams();
    params.set('subscriptions', JSON.stringify(this.#subscriptions));

    const url = `${this.#endpoint}?${params}`;

    try {
      this.#eventSource = new EventSource(url, { withCredentials: true });

      this.#eventSource.onopen = () => {
        this.#isConnecting = false;
        this.#currentDelay = this.#reconnectDelay; // Reset delay on successful connect
        this.#options.onConnect?.();
      };

      this.#eventSource.onmessage = (event) => {
        this.#handleMessage(event);
      };

      this.#eventSource.onerror = (event) => {
        this.#isConnecting = false;
        this.#options.onError?.(event);
        this.#options.onDisconnect?.();
        this.#close();
        this.#scheduleReconnect();
      };
    } catch (error) {
      this.#isConnecting = false;
      console.error('Failed to create EventSource:', error);
      this.#scheduleReconnect();
    }
  }

  /**
   * Handle incoming SSE message
   */
  #handleMessage(event: MessageEvent): void {
    try {
      const message: SubscriptionMessage = JSON.parse(event.data);

      if (message.subscription) {
        this.#dispatch(message.subscription, message.data);
      }
    } catch (error) {
      console.warn('Failed to parse SSE message:', error, event.data);
    }
  }

  /**
   * Dispatch data to handlers
   */
  #dispatch(subscription: string, data: unknown): void {
    this.#handlers.get(subscription)?.forEach(handler => {
      try {
        handler(data);
      } catch (error) {
        console.error(`Error in subscription handler for '${subscription}':`, error);
      }
    });
  }

  /**
   * Schedule a reconnection attempt
   */
  #scheduleReconnect(): void {
    if (this.#reconnectTimer !== null) return;

    this.#reconnectTimer = window.setTimeout(() => {
      this.#reconnectTimer = null;
      this.#currentDelay = Math.min(this.#currentDelay * 2, this.#maxReconnectDelay);
      this.#connect();
    }, this.#currentDelay);
  }

  /**
   * Reconnect with current subscriptions
   */
  #reconnect(): void {
    this.#close();
    this.#connect();
  }

  /**
   * Close connection
   */
  #close(): void {
    if (this.#reconnectTimer !== null) {
      clearTimeout(this.#reconnectTimer);
      this.#reconnectTimer = null;
    }

    if (this.#eventSource) {
      this.#eventSource.close();
      this.#eventSource = null;
    }

    this.#isConnecting = false;
  }

  /**
   * Disconnect and clean up
   */
  disconnect(): void {
    this.#close();
    this.#handlers.clear();
    this.#subscriptions = [];
  }

  /**
   * Force reconnection
   */
  reconnect(): void {
    this.#currentDelay = this.#reconnectDelay;
    this.#reconnect();
  }
}

/**
 * Create a subscription client for a workspace
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
