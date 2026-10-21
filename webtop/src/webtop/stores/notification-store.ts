/**
 * Notification Store
 *
 * What the shell shows in its toasts and in the notification center: one
 * entry per notice, newest first. Most are `remote`: kept for the user on
 * the server (services/notice-service-graphql.ts), the same on every
 * desktop, and put here by the shell whenever the user's list changes.
 * The rest are for this browser only — a topic message on
 * `webtop/notifications`, an app's `notify` message to the shell — and go
 * with the session.
 *
 * A notice's title and body are either plain text
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
 * when no bundle has it). A placeholder value may itself be such a message,
 * resolved for the reader before it is put in.
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
  /** Kept for the user on the server: read and dismissed there too, and the same on every desktop. */
  remote?: boolean;
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
      // Among the notices of this browser only: the server does the same
      // among those it keeps.
      const kept = key ? prev.notifications.filter(n => n.remote || n.data?.key !== key) : prev.notifications;
      const notifications = [newNotification, ...kept].slice(0, MAX_KEPT);
      return { notifications, unreadCount: countUnread(notifications) };
    });

    return newNotification.id;
  },

  /**
   * Puts the user's list as the server keeps it in place of the remote
   * notices held so far; the notices of this browser only stay. Newest
   * first across both.
   */
  replaceRemote(remote: Notification[]) {
    setState(prev => {
      const local = prev.notifications.filter(n => !n.remote);
      const notifications = [...remote.map(n => ({ ...n, remote: true })), ...local]
        .sort((a, b) => (a.timestamp < b.timestamp ? 1 : a.timestamp > b.timestamp ? -1 : 0))
        .slice(0, MAX_KEPT);
      return { notifications, unreadCount: countUnread(notifications) };
    });
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
