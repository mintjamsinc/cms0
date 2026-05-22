/**
 * Content Composables
 *
 * Composables for working with JCR content nodes via GraphQL.
 */

import type { Node, NodeConnection, PropertyInput } from '../graphql/types.js';
import type { ContentServiceGraphQL } from '../services/content-service-graphql.js';
import type { EventHub } from '../realtime/event-hub.js';
import { useAsync, usePaginated, type Connection } from './use-async.js';
import { createStore } from '../stores/create-store.js';

/**
 * Hook for fetching and watching a single node
 */
export interface UseNodeOptions {
  /** Whether to watch for changes */
  watch?: boolean;
  /** Cache duration in ms */
  cacheDuration?: number;
}

export interface UseNodeReturn {
  /** Current node state */
  state: {
    node: Node | null;
    loading: boolean;
    error: string | null;
  };
  /** Fetch the node */
  fetch: () => Promise<Node | null>;
  /** Refresh the node */
  refresh: () => Promise<Node | null>;
  /** Subscribe to state changes */
  subscribe: (listener: (state: { node: Node | null; loading: boolean; error: string | null }) => void) => () => void;
  /** Clean up watchers */
  dispose: () => void;
}

export function useNode(
  contentService: ContentServiceGraphQL,
  path: string,
  options: UseNodeOptions = {},
  eventHub?: EventHub
): UseNodeReturn {
  const { watch = false, cacheDuration } = options;

  const async = useAsync(
    () => contentService.getNode(path),
    { cacheDuration }
  );

  let unwatch: (() => void) | null = null;

  // Set up watcher if requested and eventHub is available
  if (watch && eventHub) {
    unwatch = eventHub.watchNode(path, (event) => {
      if (event.eventType === 'DELETED') {
        async.setData(null);
      } else {
        // Refetch on change
        async.execute();
      }
    });
  }

  return {
    get state() {
      const s = async.state;
      return {
        node: s.data,
        loading: s.loading,
        error: s.error,
      };
    },
    fetch: async.execute,
    refresh: async.execute,
    subscribe: (listener) => async.subscribe((s) => listener({
      node: s.data,
      loading: s.loading,
      error: s.error,
    })),
    dispose: () => {
      unwatch?.();
    },
  };
}

/**
 * Hook for listing child nodes with pagination
 */
export interface UseNodeListOptions {
  /** Number of items per page */
  pageSize?: number;
  /** Whether to watch for changes */
  watch?: boolean;
}

export interface UseNodeListReturn {
  /** Current state */
  state: {
    nodes: Node[];
    loading: boolean;
    loadingMore: boolean;
    error: string | null;
    hasMore: boolean;
    totalCount: number | null;
  };
  /** Load first page */
  load: () => Promise<void>;
  /** Load more items */
  loadMore: () => Promise<void>;
  /** Refresh the list */
  refresh: () => Promise<void>;
  /** Subscribe to state changes */
  subscribe: (listener: (state: UseNodeListReturn['state']) => void) => () => void;
  /** Clean up */
  dispose: () => void;
}

export function useNodeList(
  contentService: ContentServiceGraphQL,
  parentPath: string,
  options: UseNodeListOptions = {},
  eventHub?: EventHub
): UseNodeListReturn {
  const { pageSize = 50, watch = false } = options;

  const paginated = usePaginated<Node>(
    async (cursor, size) => {
      const result = await contentService.listChildren(parentPath, { first: size, after: cursor ?? undefined });
      return result as Connection<Node>;
    },
    { pageSize }
  );

  let unwatch: (() => void) | null = null;

  // Watch for changes in parent path
  if (watch && eventHub) {
    unwatch = eventHub.watchNode(parentPath, (event) => {
      // Refresh list when children change
      if (event.eventType === 'CREATED' || event.eventType === 'DELETED') {
        paginated.load();
      }
    }, true); // deep watch
  }

  return {
    get state() {
      const s = paginated.state;
      return {
        nodes: s.items,
        loading: s.loading,
        loadingMore: s.loadingMore,
        error: s.error,
        hasMore: s.hasMore,
        totalCount: s.totalCount,
      };
    },
    load: paginated.load,
    loadMore: paginated.loadMore,
    refresh: paginated.load,
    subscribe: (listener) => paginated.subscribe((s) => listener({
      nodes: s.items,
      loading: s.loading,
      loadingMore: s.loadingMore,
      error: s.error,
      hasMore: s.hasMore,
      totalCount: s.totalCount,
    })),
    dispose: () => {
      unwatch?.();
    },
  };
}

/**
 * Hook for searching nodes
 */
export interface UseSearchOptions {
  /** Base path to search within */
  basePath?: string;
  /** Number of results per page */
  pageSize?: number;
}

export interface UseSearchReturn {
  /** Current state */
  state: {
    results: Node[];
    loading: boolean;
    loadingMore: boolean;
    error: string | null;
    hasMore: boolean;
    totalCount: number | null;
    query: string;
  };
  /** Execute search */
  search: (query: string) => Promise<void>;
  /** Load more results */
  loadMore: () => Promise<void>;
  /** Clear results */
  clear: () => void;
  /** Subscribe to state changes */
  subscribe: (listener: (state: UseSearchReturn['state']) => void) => () => void;
}

export function useSearch(
  contentService: ContentServiceGraphQL,
  options: UseSearchOptions = {}
): UseSearchReturn {
  const { basePath = '/', pageSize = 20 } = options;

  let currentQuery = '';

  const paginated = usePaginated<Node>(
    async (cursor, size) => {
      const result = await contentService.search(currentQuery, {
        path: basePath,
        first: size,
        after: cursor ?? undefined,
      });
      return result as Connection<Node>;
    },
    { pageSize }
  );

  const search = async (query: string): Promise<void> => {
    currentQuery = query;
    if (!query.trim()) {
      paginated.reset();
      return;
    }
    await paginated.load();
  };

  const clear = () => {
    currentQuery = '';
    paginated.reset();
  };

  return {
    get state() {
      const s = paginated.state;
      return {
        results: s.items,
        loading: s.loading,
        loadingMore: s.loadingMore,
        error: s.error,
        hasMore: s.hasMore,
        totalCount: s.totalCount,
        query: currentQuery,
      };
    },
    search,
    loadMore: paginated.loadMore,
    clear,
    subscribe: (listener) => paginated.subscribe((s) => listener({
      results: s.items,
      loading: s.loading,
      loadingMore: s.loadingMore,
      error: s.error,
      hasMore: s.hasMore,
      totalCount: s.totalCount,
      query: currentQuery,
    })),
  };
}

/**
 * Hook for node mutations
 */
export interface UseNodeMutationsReturn {
  /** Create a folder */
  createFolder: (parentPath: string, name: string) => Promise<Node | null>;
  /** Create a file */
  createFile: (parentPath: string, name: string, mimeType: string, content: string) => Promise<Node | null>;
  /** Delete a node */
  deleteNode: (path: string) => Promise<boolean>;
  /** Set properties */
  setProperties: (path: string, properties: PropertyInput[]) => Promise<{ node: Node | null; errors: Array<{ propertyName: string; message: string }> }>;
  /** Lock a node */
  lockNode: (path: string) => Promise<Node | null>;
  /** Unlock a node */
  unlockNode: (path: string) => Promise<boolean>;
  /** Current mutation state */
  state: {
    pending: boolean;
    error: string | null;
  };
  /** Subscribe to state changes */
  subscribe: (listener: (state: { pending: boolean; error: string | null }) => void) => () => void;
}

export function useNodeMutations(
  contentService: ContentServiceGraphQL
): UseNodeMutationsReturn {
  const store = createStore<{ pending: boolean; error: string | null }>({
    pending: false,
    error: null,
  });

  const wrap = async <T>(fn: () => Promise<T>): Promise<T | null> => {
    store.setState({ pending: true, error: null });
    try {
      const result = await fn();
      store.setState({ pending: false });
      return result;
    } catch (err) {
      const error = err instanceof Error ? err.message : String(err);
      store.setState({ pending: false, error });
      return null;
    }
  };

  return {
    createFolder: (parentPath, name) =>
      wrap(() => contentService.createFolder(parentPath, name)),

    createFile: (parentPath, name, mimeType, content) =>
      wrap(() => contentService.createFile(parentPath, name, mimeType, content)),

    deleteNode: (path) =>
      wrap(() => contentService.deleteNode(path)).then(r => r ?? false),

    setProperties: async (path, properties) => {
      const result = await wrap(() => contentService.setProperties(path, properties));
      return result ?? { node: null, errors: [] };
    },

    lockNode: (path) =>
      wrap(() => contentService.lockNode(path)),

    unlockNode: (path) =>
      wrap(() => contentService.unlockNode(path)).then(r => r ?? false),

    get state() {
      return store.state;
    },

    subscribe: store.subscribe.bind(store),
  };
}

/**
 * Hook for XPath queries
 */
export interface UseXPathReturn {
  /** Current state */
  state: {
    results: Node[];
    loading: boolean;
    loadingMore: boolean;
    error: string | null;
    hasMore: boolean;
    totalCount: number | null;
  };
  /** Execute XPath query */
  query: (xpath: string) => Promise<void>;
  /** Load more results */
  loadMore: () => Promise<void>;
  /** Clear results */
  clear: () => void;
  /** Subscribe to state changes */
  subscribe: (listener: (state: UseXPathReturn['state']) => void) => () => void;
}

export function useXPath(
  contentService: ContentServiceGraphQL,
  options: { pageSize?: number } = {}
): UseXPathReturn {
  const { pageSize = 20 } = options;

  let currentXPath = '';

  const paginated = usePaginated<Node>(
    async (cursor, size) => {
      const result = await contentService.xpath(currentXPath, { first: size, after: cursor ?? undefined });
      return result as Connection<Node>;
    },
    { pageSize }
  );

  const query = async (xpath: string): Promise<void> => {
    currentXPath = xpath;
    if (!xpath.trim()) {
      paginated.reset();
      return;
    }
    await paginated.load();
  };

  const clear = () => {
    currentXPath = '';
    paginated.reset();
  };

  return {
    get state() {
      const s = paginated.state;
      return {
        results: s.items,
        loading: s.loading,
        loadingMore: s.loadingMore,
        error: s.error,
        hasMore: s.hasMore,
        totalCount: s.totalCount,
      };
    },
    query,
    loadMore: paginated.loadMore,
    clear,
    subscribe: (listener) => paginated.subscribe((s) => listener({
      results: s.items,
      loading: s.loading,
      loadingMore: s.loadingMore,
      error: s.error,
      hasMore: s.hasMore,
      totalCount: s.totalCount,
    })),
  };
}
