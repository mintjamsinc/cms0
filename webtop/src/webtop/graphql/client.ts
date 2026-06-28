/**
 * GraphQL Client
 *
 * Lightweight GraphQL client using fetch API.
 * Supports queries, mutations, and error handling.
 */

export interface GraphQLClientOptions {
  /** GraphQL endpoint URL */
  endpoint: string;
  /** Default headers to include in requests */
  headers?: Record<string, string>;
  /** Error handler for all GraphQL errors */
  onError?: (error: GraphQLError) => void;
}

export interface GraphQLResponse<T> {
  data?: T;
  errors?: GraphQLErrorDetail[];
}

export interface GraphQLErrorDetail {
  message: string;
  locations?: Array<{ line: number; column: number }>;
  path?: Array<string | number>;
  extensions?: {
    code: string;
    reason?: string;
    resourceId?: string;
    details?: unknown;
    stackTrace?: string;
    exception?: string;
    exceptionMessage?: string;
  };
}

/**
 * GraphQL Error class with enhanced error details
 */
export class GraphQLError extends Error {
  public readonly errors?: GraphQLErrorDetail[];
  public readonly response?: Response;

  constructor(
    message: string,
    errors?: GraphQLErrorDetail[],
    response?: Response
  ) {
    super(message);
    this.name = 'GraphQLError';
    this.errors = errors;
    this.response = response;
  }

  /** Machine-readable error code */
  get code(): string | undefined {
    return this.errors?.[0]?.extensions?.code;
  }

  /** Additional error reason */
  get reason(): string | undefined {
    return this.errors?.[0]?.extensions?.reason;
  }

  /** Affected resource ID */
  get resourceId(): string | undefined {
    return this.errors?.[0]?.extensions?.resourceId;
  }

  /** Whether this is an authentication error */
  get isAuthError(): boolean {
    return this.code === 'UNAUTHORIZED' || this.response?.status === 401;
  }

  /** Whether this is a permission error */
  get isPermissionError(): boolean {
    return this.code === 'FORBIDDEN' || this.response?.status === 403;
  }

  /** Whether this is a not found error */
  get isNotFoundError(): boolean {
    return this.code === 'NOT_FOUND' || this.response?.status === 404;
  }
}

/**
 * GraphQL Client for making queries and mutations
 */
export class GraphQLClient {
  #endpoint: string;
  #headers: Record<string, string>;
  #onError?: (error: GraphQLError) => void;

  constructor(options: GraphQLClientOptions) {
    this.#endpoint = options.endpoint;
    this.#headers = options.headers ?? {};
    this.#onError = options.onError;
  }

  /** Current endpoint */
  get endpoint(): string {
    return this.#endpoint;
  }

  /**
   * Execute a GraphQL query
   */
  async query<T = unknown>(
    query: string,
    variables?: Record<string, unknown>
  ): Promise<T> {
    return this.#execute<T>(query, variables);
  }

  /**
   * Execute a GraphQL mutation
   */
  async mutation<T = unknown>(
    mutation: string,
    variables?: Record<string, unknown>
  ): Promise<T> {
    return this.#execute<T>(mutation, variables);
  }

  /**
   * Execute GraphQL operation
   */
  async #execute<T>(
    query: string,
    variables?: Record<string, unknown>
  ): Promise<T> {
    let response: Response;

    try {
      response = await fetch(this.#endpoint, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json',
          ...this.#headers,
        },
        credentials: 'include',
        body: JSON.stringify({ query, variables }),
      });
    } catch (networkError) {
      const error = new GraphQLError(
        `Network error: ${(networkError as Error).message}`
      );
      this.#onError?.(error);
      throw error;
    }

    if (!response.ok) {
      const error = new GraphQLError(
        `HTTP ${response.status}: ${response.statusText}`,
        undefined,
        response
      );
      this.#onError?.(error);
      throw error;
    }

    let result: GraphQLResponse<T>;
    try {
      result = await response.json();
    } catch (parseError) {
      const error = new GraphQLError(
        `Failed to parse response: ${(parseError as Error).message}`,
        undefined,
        response
      );
      this.#onError?.(error);
      throw error;
    }

    if (result.errors?.length) {
      const error = new GraphQLError(
        result.errors[0].message,
        result.errors,
        response
      );
      this.#onError?.(error);
      throw error;
    }

    return result.data as T;
  }

  /**
   * Update default headers (e.g., after authentication)
   */
  setHeaders(headers: Record<string, string>): void {
    this.#headers = { ...this.#headers, ...headers };
  }

  /**
   * Remove a header
   */
  removeHeader(key: string): void {
    delete this.#headers[key];
  }

  /**
   * Set endpoint (e.g., when switching workspaces)
   */
  setEndpoint(endpoint: string): void {
    this.#endpoint = endpoint;
  }
}

/**
 * Create a GraphQL client for a specific workspace
 */
export function createGraphQLClient(
  workspace: string,
  options: Partial<GraphQLClientOptions> = {}
): GraphQLClient {
  // Endpoint cutover (#7b): /bin/graphql.cgi is now served by the platform
  // (graphql-java) engine. Rollback = point this at /bin/graphql-legacy.cgi
  // (the retired handmade engine) and redeploy.
  const endpoint = `/bin/graphql.cgi/${workspace}`;
  return new GraphQLClient({
    endpoint,
    ...options,
  });
}
