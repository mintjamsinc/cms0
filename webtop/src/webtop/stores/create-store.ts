/**
 * Simple Reactive Store Factory
 *
 * Creates lightweight reactive stores for state management.
 * Compatible with ichigo.js components.
 */

export type Listener<T> = (state: T, prevState: T) => void;

export interface Store<T> {
  /** Current state (read-only) */
  readonly state: T;

  /** Subscribe to state changes */
  subscribe(listener: Listener<T>): () => void;

  /** Update state */
  setState(updater: Partial<T> | ((prev: T) => Partial<T>)): void;

  /** Reset state to initial values */
  reset(): void;

  /** Get a snapshot of current state */
  getSnapshot(): T;
}

/**
 * Create a reactive store
 */
export function createStore<T extends object>(initialState: T): Store<T> {
  let state = { ...initialState };
  const listeners = new Set<Listener<T>>();

  const notify = (prevState: T) => {
    listeners.forEach(listener => {
      try {
        listener(state, prevState);
      } catch (error) {
        console.error('Store listener error:', error);
      }
    });
  };

  return {
    get state() {
      return state;
    },

    subscribe(listener: Listener<T>): () => void {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },

    setState(updater: Partial<T> | ((prev: T) => Partial<T>)): void {
      const prevState = state;
      const updates = typeof updater === 'function' ? updater(state) : updater;
      state = { ...state, ...updates };
      notify(prevState);
    },

    reset(): void {
      const prevState = state;
      state = { ...initialState };
      notify(prevState);
    },

    getSnapshot(): T {
      return { ...state };
    },
  };
}

/**
 * Create a derived store that computes values from other stores
 */
export function createDerivedStore<T extends object, D>(
  store: Store<T>,
  derive: (state: T) => D
): { readonly value: D; subscribe: (listener: (value: D) => void) => () => void } {
  let cachedValue = derive(store.state);
  const listeners = new Set<(value: D) => void>();

  store.subscribe((state) => {
    const newValue = derive(state);
    if (newValue !== cachedValue) {
      cachedValue = newValue;
      listeners.forEach(listener => listener(newValue));
    }
  });

  return {
    get value() {
      return cachedValue;
    },

    subscribe(listener: (value: D) => void): () => void {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

/**
 * Combine multiple stores into a single derived value
 */
export function combineStores<T extends Record<string, Store<unknown>>>(
  stores: T
): Store<{ [K in keyof T]: T[K] extends Store<infer S> ? S : never }> {
  type CombinedState = { [K in keyof T]: T[K] extends Store<infer S> ? S : never };

  const getState = (): CombinedState => {
    const result = {} as CombinedState;
    for (const key in stores) {
      result[key] = stores[key].state as CombinedState[typeof key];
    }
    return result;
  };

  let state = getState();
  const listeners = new Set<Listener<CombinedState>>();

  const notify = (prevState: CombinedState) => {
    listeners.forEach(listener => listener(state, prevState));
  };

  // Subscribe to all source stores
  for (const key in stores) {
    stores[key].subscribe(() => {
      const prevState = state;
      state = getState();
      notify(prevState);
    });
  }

  return {
    get state() {
      return state;
    },

    subscribe(listener: Listener<CombinedState>): () => void {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },

    setState(): void {
      throw new Error('Cannot setState on combined store');
    },

    reset(): void {
      for (const key in stores) {
        stores[key].reset();
      }
    },

    getSnapshot(): CombinedState {
      return { ...state };
    },
  };
}

/**
 * Create actions for a store
 */
export function createActions<T extends object, A extends Record<string, (...args: unknown[]) => void>>(
  store: Store<T>,
  actionCreators: (setState: Store<T>['setState'], getState: () => T) => A
): A {
  return actionCreators(store.setState.bind(store), () => store.state);
}
