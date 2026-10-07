/**
 * Notification Store
 *
 * What the shell shows in its toasts and in the notification center: one
 * entry per notice, newest first, kept for this session only (a reload
 * starts empty). Whatever is durable about a notice — an unread mark, a
 * task — lives in the repository; this is the list one glances at.
 *
 * A notice comes from a topic message on `webtop/notifications`, from an
 * app's `notify` message to the shell, or from the shell itself (the task
 * subscriptions in event-hub.ts). Its title and body are either plain text
 * or a reference to an i18n message, which the shell resolves in the
 * reader's language at render time — in the scope of the app that sent it
 * when `data.app` names one — so a notice published once reads right for
 * every recipient.
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

/**
 * A text of a notice: written out, or as an i18n message to resolve for the
 * reader (`id` in the bundles, `params` for its placeholders, `fallback`
 * when no bundle has it).
 */
export type NotificationText = string | { id: string; params?: Record<string, unknown>; fallback?: string };

/** What a notice carries besides its texts: where a click takes the reader, and how it is shown. */
export interface NotificationData {
  /** The app (its folder name under apps/) a click opens, and whose i18n scope resolves the texts. */
  app?: string;
  /** Launch options for that app (`openAppWithOptions`). */
  options?: Record<string, unknown>;
  /** Bootstrap icon class shown when the app has no icon, e.g. `bi-chat-dots`. */
  icon?: string;
  /**
   * What the notice is about, e.g. `chat:channel:<id>`. A notice is not
   * shown while the active window reports the same context: the reader is
   * already looking at it.
   */
  context?: string;
  /** Notices with the same key supersede one another: the newer replaces the older. */
  key?: string;
  [extra: string]: unknown;
}

export interface Notification {
  id: string;
  type: NotificationType;
  title: NotificationText;
  message: NotificationText;
  severity: Severity;
  timestamp: string;
  read: boolean;
  data?: NotificationData;
}

export interface NotificationState {
  notifications: Notification[];
  unreadCount: number;
}

const initialState: NotificationState = {
  notifications: [],
  unreadCount: 0,
};

const MAX_KEPT = 100;

export const notificationStore = createStore<NotificationState>(initialState);

function countUnread(notifications: Notification[]): number {
  return notifications.filter(n => !n.read).length;
}

export const notificationActions = createActions(notificationStore, (setState, getState) => ({
  add(notification: Omit<Notification, 'id' | 'timestamp' | 'read'>) {
    const newNotification: Notification = {
      ...notification,
      id: crypto.randomUUID(),
      timestamp: new Date().toISOString(),
      read: false,
    };

    setState(prev => {
      const key = notification.data?.key;
      // A notice about the same thing supersedes the earlier one, read or
      // not: the newest state of a conversation is what the list shows.
      const kept = key ? prev.notifications.filter(n => n.data?.key !== key) : prev.notifications;
      const notifications = [newNotification, ...kept].slice(0, MAX_KEPT);
      return { notifications, unreadCount: countUnread(notifications) };
    });

    return newNotification.id;
  },

  remove(id: string) {
    setState(prev => {
      const notifications = prev.notifications.filter(n => n.id !== id);
      return { notifications, unreadCount: countUnread(notifications) };
    });
  },

  markAsRead(id: string) {
    setState(prev => {
      const notifications = prev.notifications.map(n =>
        n.id === id && !n.read ? { ...n, read: true } : n
      );
      return { notifications, unreadCount: countUnread(notifications) };
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

  info(title: NotificationText, message: NotificationText, data?: NotificationData) {
    return this.add({ type: 'INFO', title, message, severity: 'LOW', data });
  },

  warning(title: NotificationText, message: NotificationText, data?: NotificationData) {
    return this.add({ type: 'WARNING', title, message, severity: 'MEDIUM', data });
  },

  error(title: NotificationText, message: NotificationText, data?: NotificationData) {
    return this.add({ type: 'ERROR', title, message, severity: 'HIGH', data });
  },

  task(title: NotificationText, message: NotificationText, data?: NotificationData) {
    return this.add({ type: 'TASK', title, message, severity: 'MEDIUM', data });
  },

  getUnreadNotifications(): Notification[] {
    return getState().notifications.filter(n => !n.read);
  },
}));
