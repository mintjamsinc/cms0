/**
 * Application Store
 *
 * Manages application instances and window state.
 */

import { createStore, createActions } from './create-store.js';
import type { Application, ApplicationInstance } from '../services/webtop-service.js';

export interface AppState {
  /** Installed applications */
  applications: Application[];

  /** Running application instances */
  instances: ApplicationInstance[];

  /** Currently active (focused) instance ID */
  activeInstanceId: string | null;

  /** Loading state */
  loading: boolean;

  /** Error message */
  error: string | null;
}

const initialState: AppState = {
  applications: [],
  instances: [],
  activeInstanceId: null,
  loading: false,
  error: null,
};

export const appStore = createStore<AppState>(initialState);

export const appActions = createActions(appStore, (setState, getState) => ({
  setApplications(applications: Application[]) {
    setState({ applications, loading: false, error: null });
  },

  addInstance(instance: ApplicationInstance) {
    setState(prev => ({
      instances: [...prev.instances, instance],
      activeInstanceId: instance.id,
    }));
  },

  removeInstance(instanceId: string) {
    setState(prev => ({
      instances: prev.instances.filter(i => i.id !== instanceId),
      activeInstanceId:
        prev.activeInstanceId === instanceId
          ? prev.instances.find(i => i.id !== instanceId)?.id ?? null
          : prev.activeInstanceId,
    }));
  },

  setActiveInstance(instanceId: string | null) {
    setState({ activeInstanceId: instanceId });
  },

  bringToFront(instanceId: string) {
    setState({ activeInstanceId: instanceId });
  },

  setLoading(loading: boolean) {
    setState({ loading });
  },

  setError(error: string | null) {
    setState({ error, loading: false });
  },

  getInstancesByApp(appId: string): ApplicationInstance[] {
    return getState().instances.filter(i => i.app.id === appId);
  },

  getActiveInstance(): ApplicationInstance | undefined {
    const state = getState();
    return state.instances.find(i => i.id === state.activeInstanceId);
  },
}));
