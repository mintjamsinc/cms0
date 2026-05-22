/**
 * Async State Composable
 *
 * Generic composable for managing async operations with loading/error states.
 */

import { createStore } from '../stores/create-store.js';

export interface AsyncState<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
  lastFetched: number | null;
}

export interface UseAsyncOptions<T> {
  /** Initial data value */
  initialData?: T | null;
  /** Cache duration in ms (default: no cache) */
  cacheDuration?: number;
  /** Called on successful fetch */
  onSuccess?: (data: T) => void;
  /** Called on error */
  onError?: (error: Error) => void;
}

export interface UseAsyncReturn<T, P extends unknown[]> {
  /** Current state */
  state: AsyncState<T>;
  /** Execute the async function */
  execute: (...params: P) => Promise<T | null>;
  /** Reset to initial state */
  reset: () => void;
  /** Set data manually */
  setData: (data: T | null) => void;
  /** Subscribe to state changes */
  subscribe: (listener: (state: AsyncState<T>) => void) => () => void;
}

/**
 * Create an async state manager for a given async function
 */
export function useAsync<T, P extends unknown[] = []>(
  asyncFn: (...params: P) => Promise<T>,
  options: UseAsyncOptions<T> = {}
): UseAsyncReturn<T, P> {
  const { initialData = null, cacheDuration, onSuccess, onError } = options;

  const store = createStore<AsyncState<T>>({
    data: initialData,
    loading: false,
    error: null,
    lastFetched: null,
  });

  const execute = async (...params: P): Promise<T | null> => {
    const state = store.state;

    // Check cache validity
    if (cacheDuration && state.lastFetched) {
      const age = Date.now() - state.lastFetched;
      if (age < cacheDuration && state.data !== null) {
        return state.data;
      }
    }

    store.setState({ loading: true, error: null });

    try {
      const data = await asyncFn(...params);
      store.setState({
        data,
        loading: false,
        error: null,
        lastFetched: Date.now(),
      });
      onSuccess?.(data);
      return data;
    } catch (err) {
      const error = err instanceof Error ? err : new Error(String(err));
      store.setState({
        loading: false,
        error: error.message,
      });
      onError?.(error);
      return null;
    }
  };

  const reset = () => {
    store.setState({
      data: initialData,
      loading: false,
      error: null,
      lastFetched: null,
    });
  };

  const setData = (data: T | null) => {
    store.setState({ data });
  };

  return {
    get state() {
      return store.state;
    },
    execute,
    reset,
    setData,
    subscribe: store.subscribe.bind(store),
  };
}

/**
 * Create a paginated async state manager
 */
export interface PaginatedState<T> {
  items: T[];
  loading: boolean;
  loadingMore: boolean;
  error: string | null;
  hasMore: boolean;
  cursor: string | null;
  totalCount: number | null;
}

export interface PageInfo {
  hasNextPage: boolean;
  endCursor: string | null;
}

export interface Connection<T> {
  edges: Array<{ node: T; cursor: string }>;
  pageInfo: PageInfo;
  totalCount?: number;
}

export interface UsePaginatedOptions<T> {
  /** Number of items per page */
  pageSize?: number;
  /** Called on successful fetch */
  onSuccess?: (items: T[]) => void;
  /** Called on error */
  onError?: (error: Error) => void;
}

export interface UsePaginatedReturn<T, P extends unknown[]> {
  /** Current state */
  state: PaginatedState<T>;
  /** Load first page */
  load: (...params: P) => Promise<void>;
  /** Load more items */
  loadMore: (...params: P) => Promise<void>;
  /** Reset to initial state */
  reset: () => void;
  /** Subscribe to state changes */
  subscribe: (listener: (state: PaginatedState<T>) => void) => () => void;
}

/**
 * Create a paginated state manager for relay-style connections
 */
export function usePaginated<T, P extends unknown[] = []>(
  fetchFn: (cursor: string | null, pageSize: number, ...params: P) => Promise<Connection<T>>,
  options: UsePaginatedOptions<T> = {}
): UsePaginatedReturn<T, P> {
  const { pageSize = 20, onSuccess, onError } = options;

  const store = createStore<PaginatedState<T>>({
    items: [],
    loading: false,
    loadingMore: false,
    error: null,
    hasMore: true,
    cursor: null,
    totalCount: null,
  });

  const load = async (...params: P): Promise<void> => {
    store.setState({ loading: true, error: null, items: [], cursor: null });

    try {
      const connection = await fetchFn(null, pageSize, ...params);
      const items = connection.edges.map(e => e.node);

      store.setState({
        items,
        loading: false,
        hasMore: connection.pageInfo.hasNextPage,
        cursor: connection.pageInfo.endCursor,
        totalCount: connection.totalCount ?? null,
      });

      onSuccess?.(items);
    } catch (err) {
      const error = err instanceof Error ? err : new Error(String(err));
      store.setState({
        loading: false,
        error: error.message,
      });
      onError?.(error);
    }
  };

  const loadMore = async (...params: P): Promise<void> => {
    const state = store.state;
    if (!state.hasMore || state.loadingMore || state.loading) return;

    store.setState({ loadingMore: true, error: null });

    try {
      const connection = await fetchFn(state.cursor, pageSize, ...params);
      const newItems = connection.edges.map(e => e.node);

      store.setState(prev => ({
        items: [...prev.items, ...newItems],
        loadingMore: false,
        hasMore: connection.pageInfo.hasNextPage,
        cursor: connection.pageInfo.endCursor,
        totalCount: connection.totalCount ?? prev.totalCount,
      }));

      onSuccess?.(newItems);
    } catch (err) {
      const error = err instanceof Error ? err : new Error(String(err));
      store.setState({
        loadingMore: false,
        error: error.message,
      });
      onError?.(error);
    }
  };

  const reset = () => {
    store.setState({
      items: [],
      loading: false,
      loadingMore: false,
      error: null,
      hasMore: true,
      cursor: null,
      totalCount: null,
    });
  };

  return {
    get state() {
      return store.state;
    },
    load,
    loadMore,
    reset,
    subscribe: store.subscribe.bind(store),
  };
}
