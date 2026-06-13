/**
 * Event Hub
 *
 * Central hub for managing real-time events and subscriptions.
 * Integrates SSE subscriptions with application stores.
 */

import { SubscriptionClient, createSubscriptionClient } from './subscription-client.js';
import { taskActions } from '../stores/task-store.js';
import { notificationActions } from '../stores/notification-store.js';
import type {
  NodeChangeEvent,
  PreferenceChangeEvent,
  WallpaperChangeEvent,
  AvatarChangeEvent,
  TaskEvent,
  ProcessEvent,
  RouteStateEvent,
  SystemNotification,
  JobProgressEvent,
  WorkspaceChangeEvent,
} from '../graphql/types.js';

export type EventHandler<T = unknown> = (data: T) => void;

/**
 * Event Hub for centralized event management
 */
export class EventHub {
  #client: SubscriptionClient;
  #unsubscribers: Array<() => void> = [];
  #workspace: string;

  constructor(workspace: string) {
    this.#workspace = workspace;
    this.#client = createSubscriptionClient(workspace, {
      onConnect: () => this.#onConnect(),
      onDisconnect: () => this.#onDisconnect(),
      onError: (error) => this.#onError(error),
    });
  }

  /** Workspace name */
  get workspace(): string {
    return this.#workspace;
  }

  /** Whether connected to SSE */
  get isConnected(): boolean {
    return this.#client.isConnected;
  }

  // =========================================================================
  // Lifecycle
  // =========================================================================

  /**
   * Initialize with standard subscriptions for a user
   */
  initialize(userId: string, groups: string[] = []): void {
    this.#setupTaskSubscriptions(userId, groups);
    this.#setupSystemSubscriptions();
  }

  /**
   * Set up task-related subscriptions
   */
  #setupTaskSubscriptions(userId: string, groups: string[]): void {
    // Task assigned to user
    this.#unsubscribers.push(
      this.#client.subscribe<TaskEvent>(
        `taskAssigned(assignee: "${userId}")`,
        (event) => {
          if (event.task) {
            taskActions.addTask(event.task);
            notificationActions.task(
              '新しいタスク',
              `タスク「${event.task.name}」が割り当てられました`,
              { taskId: event.task.id }
            );
          }
        }
      )
    );

    // Task completed
    this.#unsubscribers.push(
      this.#client.subscribe<TaskEvent>(
        'taskCompleted',
        (event) => {
          if (event.task) {
            taskActions.removeTask(event.task.id);
          }
        }
      )
    );

    // Tasks for candidate groups (claimable)
    if (groups.length > 0) {
      for (const group of groups) {
        this.#unsubscribers.push(
          this.#client.subscribe<TaskEvent>(
            `taskAssigned(candidateGroup: "${group}")`,
            (event) => {
              if (event.task && !event.task.assignee) {
                // Add to claimable tasks if unassigned
                taskActions.setClaimableTasks([
                  event.task,
                  ...taskActions.getOverdueTasks(), // This is wrong, fix later
                ]);
              }
            }
          )
        );
      }
    }
  }

  /**
   * Set up system-level subscriptions
   */
  #setupSystemSubscriptions(): void {
    this.#unsubscribers.push(
      this.#client.subscribe<SystemNotification>(
        'systemNotification',
        (notification) => {
          notificationActions.add({
            type: notification.type,
            title: notification.title,
            message: notification.message,
            severity: notification.severity,
            data: notification.data,
          });
        }
      )
    );
  }

  // =========================================================================
  // Manual Subscriptions
  // =========================================================================

  /**
   * Subscribe to node changes at a path
   */
  watchNode(
    path: string,
    handler: EventHandler<NodeChangeEvent>,
    deep = false
  ): () => void {
    const subscription = `nodeChanged(path: "${path}", deep: ${deep})`;
    return this.#client.subscribe(subscription, handler);
  }

  /**
   * Subscribe to preference changes for a user.
   * The server reads /home/users/{userId}/preferences/{category}/jcr:content
   * and delivers the properties directly in the event payload — no follow-up
   * query needed on the client side.
   *
   * Subscription: preferenceChanged(userId: "<userId>")
   */
  watchPreferences(
    userId: string,
    handler: EventHandler<PreferenceChangeEvent>
  ): () => void {
    const subscription = `preferenceChanged(userId: "${userId}")`;
    return this.#client.subscribe(subscription, handler);
  }

  /**
   * Subscribe to wallpaper changes for a specific user.
   * Subscription: wallpaperChanged(userId: "<userId>")
   */
  watchWallpapers(
    userId: string,
    handler: EventHandler<WallpaperChangeEvent>
  ): () => void {
    const subscription = `wallpaperChanged(userId: "${userId}")`;
    return this.#client.subscribe(subscription, handler);
  }

  /**
   * Subscribe to avatar changes for a specific user.
   * Subscription: avatarChanged(userId: "<userId>")
   */
  watchAvatar(
    userId: string,
    handler: EventHandler<AvatarChangeEvent>
  ): () => void {
    const subscription = `avatarChanged(userId: "${userId}")`;
    return this.#client.subscribe(subscription, handler);
  }

  /**
   * Subscribe to progress updates for a background job.
   *
   * The server emits one event each time the job's persisted record
   * changes — the worker batches writes (every 100 nodes deleted or 500ms,
   * whichever comes first) so callers don't see one event per leaf.
   *
   * Subscription: jobProgress(jobId: "<jobId>")
   */
  watchJobProgress(
    jobId: string,
    handler: EventHandler<JobProgressEvent>
  ): () => void {
    const subscription = `jobProgress(jobId: "${jobId}")`;
    return this.#client.subscribe(subscription, handler);
  }

  /**
   * Subscribe to workspace runtime-state changes across the repository.
   *
   * Fires whenever any workspace's services start or stop, so the desktop's
   * workspace switcher can stay in sync — newly started workspaces appear and
   * stopped ones disappear without a manual refresh. The handler should
   * re-read the workspace list rather than trust the event payload, which only
   * names the workspace that changed.
   *
   * Subscription: workspaceChanged
   */
  watchWorkspaces(handler: EventHandler<WorkspaceChangeEvent>): () => void {
    const subscription = 'workspaceChanged';
    return this.#client.subscribe(subscription, handler);
  }

  /**
   * Subscribe to process events
   */
  watchProcess(
    definitionKey: string,
    handler: EventHandler<ProcessEvent>
  ): () => void {
    const subscription = `processStarted(definitionKey: "${definitionKey}")`;
    return this.#client.subscribe(subscription, handler);
  }

  /**
   * Subscribe to route state changes
   */
  watchRoute(
    routeId: string,
    handler: EventHandler<RouteStateEvent>
  ): () => void {
    const subscription = `routeStateChanged(routeId: "${routeId}")`;
    return this.#client.subscribe(subscription, handler);
  }

  /**
   * Subscribe to all route state changes
   */
  watchAllRoutes(handler: EventHandler<RouteStateEvent>): () => void {
    const subscription = 'routeStateChanged';
    return this.#client.subscribe(subscription, handler);
  }

  /**
   * Subscribe to task events for a process instance
   */
  watchProcessTasks(
    processInstanceId: string,
    handler: EventHandler<TaskEvent>
  ): () => void {
    const subscription = `taskCompleted(processInstanceId: "${processInstanceId}")`;
    return this.#client.subscribe(subscription, handler);
  }

  /**
   * Subscribe to changes on a specific task
   */
  watchTask(
    taskId: string,
    handler: EventHandler<TaskEvent>
  ): () => void {
    const subscription = `taskUpdated(taskId: "${taskId}")`;
    return this.#client.subscribe(subscription, handler);
  }

  /**
   * Subscribe to task assignment events for a user
   */
  watchTaskAssignment(
    userId: string,
    handler: EventHandler<TaskEvent>
  ): () => void {
    const subscription = `taskAssigned(assignee: "${userId}")`;
    return this.#client.subscribe(subscription, handler);
  }

  /**
   * Generic subscription
   */
  subscribe<T>(subscription: string, handler: EventHandler<T>): () => void {
    return this.#client.subscribe(subscription, handler);
  }

  // =========================================================================
  // Connection Events
  // =========================================================================

  #onConnect(): void {
    console.log(`[EventHub] Connected to ${this.#workspace}`);
  }

  #onDisconnect(): void {
    console.log(`[EventHub] Disconnected from ${this.#workspace}`);
  }

  #onError(error: Event): void {
    console.error(`[EventHub] Connection error:`, error);
  }

  // =========================================================================
  // Cleanup
  // =========================================================================

  /**
   * Dispose all subscriptions and disconnect
   */
  dispose(): void {
    this.#unsubscribers.forEach(unsub => unsub());
    this.#unsubscribers = [];
    this.#client.disconnect();
  }

  /**
   * Reconnect to SSE
   */
  reconnect(): void {
    this.#client.reconnect();
  }
}

/**
 * Create an event hub for a workspace
 */
export function createEventHub(workspace: string): EventHub {
  return new EventHub(workspace);
}
