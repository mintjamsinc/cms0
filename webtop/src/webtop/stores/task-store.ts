/**
 * Task Store
 *
 * Manages BPM task state for the task list application.
 */

import { createStore, createActions } from './create-store.js';
import type { Task, TaskCounts } from '../graphql/types.js';

export interface TaskState {
  /** Current user's tasks */
  tasks: Task[];

  /** Claimable tasks (unassigned but available to user) */
  claimableTasks: Task[];

  /** Task counts for dashboard */
  counts: TaskCounts | null;

  /** Currently selected task ID */
  selectedTaskId: string | null;

  /** Loading state */
  loading: boolean;

  /** Error message */
  error: string | null;

  /** Last refresh timestamp */
  lastRefresh: string | null;
}

const initialState: TaskState = {
  tasks: [],
  claimableTasks: [],
  counts: null,
  selectedTaskId: null,
  loading: false,
  error: null,
  lastRefresh: null,
};

export const taskStore = createStore<TaskState>(initialState);

export const taskActions = createActions(taskStore, (setState, getState) => ({
  setTasks(tasks: Task[]) {
    setState({
      tasks,
      loading: false,
      error: null,
      lastRefresh: new Date().toISOString(),
    });
  },

  setClaimableTasks(tasks: Task[]) {
    setState({ claimableTasks: tasks });
  },

  setCounts(counts: TaskCounts) {
    setState({ counts });
  },

  selectTask(taskId: string | null) {
    setState({ selectedTaskId: taskId });
  },

  addTask(task: Task) {
    setState(prev => ({
      tasks: [task, ...prev.tasks],
    }));
  },

  updateTask(taskId: string, updates: Partial<Task>) {
    setState(prev => ({
      tasks: prev.tasks.map(t =>
        t.id === taskId ? { ...t, ...updates } : t
      ),
    }));
  },

  removeTask(taskId: string) {
    setState(prev => ({
      tasks: prev.tasks.filter(t => t.id !== taskId),
      selectedTaskId: prev.selectedTaskId === taskId ? null : prev.selectedTaskId,
    }));
  },

  moveToClaimable(taskId: string) {
    setState(prev => {
      const task = prev.tasks.find(t => t.id === taskId);
      if (!task) return prev;
      return {
        tasks: prev.tasks.filter(t => t.id !== taskId),
        claimableTasks: [{ ...task, assignee: undefined }, ...prev.claimableTasks],
        selectedTaskId: prev.selectedTaskId === taskId ? null : prev.selectedTaskId,
      };
    });
  },

  moveFromClaimable(taskId: string, assignee: string) {
    setState(prev => {
      const task = prev.claimableTasks.find(t => t.id === taskId);
      if (!task) return prev;
      return {
        claimableTasks: prev.claimableTasks.filter(t => t.id !== taskId),
        tasks: [{ ...task, assignee }, ...prev.tasks],
      };
    });
  },

  setLoading(loading: boolean) {
    setState({ loading });
  },

  setError(error: string | null) {
    setState({ error, loading: false });
  },

  getSelectedTask(): Task | undefined {
    const state = getState();
    return state.tasks.find(t => t.id === state.selectedTaskId);
  },

  getOverdueTasks(): Task[] {
    const now = new Date();
    return getState().tasks.filter(t => {
      if (!t.due) return false;
      return new Date(t.due) < now;
    });
  },

  getTasksDueToday(): Task[] {
    const now = new Date();
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const tomorrow = new Date(today.getTime() + 24 * 60 * 60 * 1000);

    return getState().tasks.filter(t => {
      if (!t.due) return false;
      const due = new Date(t.due);
      return due >= today && due < tomorrow;
    });
  },

  sortByPriority() {
    setState(prev => ({
      tasks: [...prev.tasks].sort((a, b) => b.priority - a.priority),
    }));
  },

  sortByDueDate() {
    setState(prev => ({
      tasks: [...prev.tasks].sort((a, b) => {
        if (!a.due && !b.due) return 0;
        if (!a.due) return 1;
        if (!b.due) return -1;
        return new Date(a.due).getTime() - new Date(b.due).getTime();
      }),
    }));
  },

  sortByCreated() {
    setState(prev => ({
      tasks: [...prev.tasks].sort((a, b) =>
        new Date(b.created).getTime() - new Date(a.created).getTime()
      ),
    }));
  },
}));
