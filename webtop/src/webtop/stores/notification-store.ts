/**
 * Notification Store
 *
 * Manages system notifications and alerts.
 */

import { createStore, createActions } from './create-store.js';

// Local notification taxonomy. These were originally shared with the removed
// `systemNotification` GraphQL subscription; they are now purely client-side UI
// types for the in-app notification/toast store.
export type NotificationType =
  | 'INFO'
  | 'WARNING'
  | 'ERROR'
  | 'TASK'
  | 'PROCESS'
  | 'CONTENT';

export type Severity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export interface Notification {
  id: string;
  type: NotificationType;
  title: string;
  message: string;
  severity: Severity;
  timestamp: string;
  read: boolean;
  data?: unknown;
}

export interface NotificationState {
  notifications: Notification[];
  unreadCount: number;
}

const initialState: NotificationState = {
  notifications: [],
  unreadCount: 0,
};

export const notificationStore = createStore<NotificationState>(initialState);

export const notificationActions = createActions(notificationStore, (setState, getState) => ({
  add(notification: Omit<Notification, 'id' | 'timestamp' | 'read'>) {
    const newNotification: Notification = {
      ...notification,
      id: crypto.randomUUID(),
      timestamp: new Date().toISOString(),
      read: false,
    };

    setState(prev => ({
      notifications: [newNotification, ...prev.notifications].slice(0, 100), // Keep last 100
      unreadCount: prev.unreadCount + 1,
    }));

    return newNotification.id;
  },

  remove(id: string) {
    setState(prev => {
      const notification = prev.notifications.find(n => n.id === id);
      const wasUnread = notification && !notification.read;
      return {
        notifications: prev.notifications.filter(n => n.id !== id),
        unreadCount: wasUnread ? prev.unreadCount - 1 : prev.unreadCount,
      };
    });
  },

  markAsRead(id: string) {
    setState(prev => {
      const notifications = prev.notifications.map(n =>
        n.id === id && !n.read ? { ...n, read: true } : n
      );
      const notification = prev.notifications.find(n => n.id === id);
      const wasUnread = notification && !notification.read;
      return {
        notifications,
        unreadCount: wasUnread ? prev.unreadCount - 1 : prev.unreadCount,
      };
    });
  },

  markAllAsRead() {
    setState(prev => ({
      notifications: prev.notifications.map(n => ({ ...n, read: true })),
      unreadCount: 0,
    }));
  },

  clearAll() {
    setState({
      notifications: [],
      unreadCount: 0,
    });
  },

  clearRead() {
    setState(prev => ({
      notifications: prev.notifications.filter(n => !n.read),
      unreadCount: prev.unreadCount, // Unchanged since we only remove read ones
    }));
  },

  info(title: string, message: string, data?: unknown) {
    return this.add({ type: 'INFO', title, message, severity: 'LOW', data });
  },

  warning(title: string, message: string, data?: unknown) {
    return this.add({ type: 'WARNING', title, message, severity: 'MEDIUM', data });
  },

  error(title: string, message: string, data?: unknown) {
    return this.add({ type: 'ERROR', title, message, severity: 'HIGH', data });
  },

  task(title: string, message: string, data?: unknown) {
    return this.add({ type: 'TASK', title, message, severity: 'MEDIUM', data });
  },

  getUnreadNotifications(): Notification[] {
    return getState().notifications.filter(n => !n.read);
  },
}));
