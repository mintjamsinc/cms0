/**
 * Event Hub
 *
 * Central hub for managing real-time events and subscriptions.
 * Integrates graphql-sse subscriptions with application stores.
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
  JobProgressEvent,
  WorkspaceChangeEvent,
} from '../graphql/types.js';

export type EventHandler<T = unknown> = (data: T) => void;

// ---------------------------------------------------------------------------
// Subscription selection sets.
//
// In graphql-sse the client sends the full subscription document, so it owns the
// selection set for each event type (these mirror the event-type fields in
// graphql/types.ts). Keep them in sync with the server schema's event types.
// ---------------------------------------------------------------------------
const SELECTIONS = {
  node: 'eventType path identifier nodeType sourcePath userId timestamp',
  preference: 'category data userId timestamp',
  wallpaper: 'userId action filename timestamp',
  avatar: 'userId timestamp',
  workspace: 'workspace timestamp',
  job:
    'jobId status itemsTotal itemsProcessed itemsDeleted itemsArchived itemsImported ' +
    'itemsNew itemsOverwritten itemsSkipped itemsError errorSamples dryRunHasErrors ' +
    'dryRunNodeCount dryRunBinaryCount dryRunDetail currentPath errorMessage phase ' +
    'targetWorkspace downloadUrl timestamp',
  task: 'eventType task { id name assignee taskDefinitionKey processInstanceId processDefinitionId } timestamp',
  process: 'eventType processInstance { id definitionId definitionKey businessKey } timestamp',
  route: 'routeId previousState currentState timestamp error',
} as const;

/** Quote and escape a value for inlining as a GraphQL String argument. */
function gqlString(value: string): string {
  return `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
}

/** Wrap a `field(args)` clause and its selection into a subscription document. */
function subscriptionDoc(fieldWithArgs: string, selection: string): string {
  return `subscription { ${fieldWithArgs} { ${selection} } }`;
}

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

  /** Whether connected to the subscription stream */
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
  }

  /**
   * Set up task-related subscriptions
   */
  #setupTaskSubscriptions(userId: string, groups: string[]): void {
    // Task assigned to user
    this.#unsubscribers.push(
      this.#client.subscribe<TaskEvent>(
        subscriptionDoc(`taskAssigned(assignee: ${gqlString(userId)})`, SELECTIONS.task),
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
        subscriptionDoc('taskCompleted', SELECTIONS.task),
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
            subscriptionDoc(`taskAssigned(candidateGroup: ${gqlString(group)})`, SELECTIONS.task),
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
    return this.#client.subscribe(
      subscriptionDoc(`nodeChanged(path: ${gqlString(path)}, deep: ${deep})`, SELECTIONS.node),
      handler
    );
  }

  /**
   * Subscribe to preference changes for a user.
   * The server reads /home/users/{userId}/preferences/{category}/jcr:content
   * and delivers the properties directly in the event payload — no follow-up
   * query needed on the client side. (The userId argument is accepted for
   * wire-compat but resolved server-side from the session.)
   */
  watchPreferences(
    userId: string,
    handler: EventHandler<PreferenceChangeEvent>
  ): () => void {
    return this.#client.subscribe(
      subscriptionDoc(`preferenceChanged(userId: ${gqlString(userId)})`, SELECTIONS.preference),
      handler
    );
  }

  /**
   * Subscribe to wallpaper changes for a specific user.
   */
  watchWallpapers(
    userId: string,
    handler: EventHandler<WallpaperChangeEvent>
  ): () => void {
    return this.#client.subscribe(
      subscriptionDoc(`wallpaperChanged(userId: ${gqlString(userId)})`, SELECTIONS.wallpaper),
      handler
    );
  }

  /**
   * Subscribe to avatar changes for a specific user.
   */
  watchAvatar(
    userId: string,
    handler: EventHandler<AvatarChangeEvent>
  ): () => void {
    return this.#client.subscribe(
      subscriptionDoc(`avatarChanged(userId: ${gqlString(userId)})`, SELECTIONS.avatar),
      handler
    );
  }

  /**
   * Subscribe to progress updates for a background job.
   *
   * The server emits one event each time the job's persisted record
   * changes — the worker batches writes (every 100 nodes deleted or 500ms,
   * whichever comes first) so callers don't see one event per leaf.
   */
  watchJobProgress(
    jobId: string,
    handler: EventHandler<JobProgressEvent>
  ): () => void {
    return this.#client.subscribe(
      subscriptionDoc(`jobProgress(jobId: ${gqlString(jobId)})`, SELECTIONS.job),
      handler
    );
  }

  /**
   * Subscribe to workspace runtime-state changes across the repository.
   *
   * Fires whenever any workspace's services start or stop, so the desktop's
   * workspace switcher can stay in sync. The handler should re-read the
   * workspace list rather than trust the event payload, which only names the
   * workspace that changed.
   */
  watchWorkspaces(handler: EventHandler<WorkspaceChangeEvent>): () => void {
    return this.#client.subscribe(
      subscriptionDoc('workspaceChanged', SELECTIONS.workspace),
      handler
    );
  }

  /**
   * Subscribe to process events
   */
  watchProcess(
    definitionKey: string,
    handler: EventHandler<ProcessEvent>
  ): () => void {
    return this.#client.subscribe(
      subscriptionDoc(`processStarted(definitionKey: ${gqlString(definitionKey)})`, SELECTIONS.process),
      handler
    );
  }

  /**
   * Subscribe to route state changes
   */
  watchRoute(
    routeId: string,
    handler: EventHandler<RouteStateEvent>
  ): () => void {
    return this.#client.subscribe(
      subscriptionDoc(`routeStateChanged(routeId: ${gqlString(routeId)})`, SELECTIONS.route),
      handler
    );
  }

  /**
   * Subscribe to all route state changes
   */
  watchAllRoutes(handler: EventHandler<RouteStateEvent>): () => void {
    return this.#client.subscribe(
      subscriptionDoc('routeStateChanged', SELECTIONS.route),
      handler
    );
  }

  /**
   * Subscribe to task events for a process instance
   */
  watchProcessTasks(
    processInstanceId: string,
    handler: EventHandler<TaskEvent>
  ): () => void {
    return this.#client.subscribe(
      subscriptionDoc(`taskCompleted(processInstanceId: ${gqlString(processInstanceId)})`, SELECTIONS.task),
      handler
    );
  }

  /**
   * Subscribe to changes on a specific task
   */
  watchTask(
    taskId: string,
    handler: EventHandler<TaskEvent>
  ): () => void {
    return this.#client.subscribe(
      subscriptionDoc(`taskUpdated(taskId: ${gqlString(taskId)})`, SELECTIONS.task),
      handler
    );
  }

  /**
   * Subscribe to task assignment events for a user
   */
  watchTaskAssignment(
    userId: string,
    handler: EventHandler<TaskEvent>
  ): () => void {
    return this.#client.subscribe(
      subscriptionDoc(`taskAssigned(assignee: ${gqlString(userId)})`, SELECTIONS.task),
      handler
    );
  }

  /**
   * Generic subscription — pass a full `subscription { … }` document.
   */
  subscribe<T>(document: string, handler: EventHandler<T>): () => void {
    return this.#client.subscribe(document, handler);
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
   * Reconnect to the subscription stream
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
