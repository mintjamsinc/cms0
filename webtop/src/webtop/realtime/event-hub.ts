/**
 * Event Hub
 *
 * Central hub for managing real-time events and subscriptions.
 * Integrates graphql-sse subscriptions with application stores.
 */

import { SubscriptionClient, createSubscriptionClient } from './subscription-client.js';
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
  ContentLengthEvent,
  TopicMessageEvent,
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
  contentLength: 'path size fileCount',
  job:
    'jobId status itemsTotal itemsProcessed itemsDeleted itemsArchived itemsImported ' +
    'itemsNew itemsOverwritten itemsSkipped itemsError errorSamples dryRunHasErrors ' +
    'dryRunNodeCount dryRunBinaryCount dryRunDetail currentPath errorMessage phase ' +
    'targetWorkspace downloadUrl timestamp',
  task: 'eventType task { id name assignee taskDefinitionKey processInstanceId processDefinitionId processInstance { definitionKey } } timestamp',
  process: 'eventType processInstance { id definitionId definitionKey businessKey } timestamp',
  route: 'routeId previousState currentState timestamp error',
  topic: 'topic payload userId messageId timestamp',
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
   * Called once the user is known. Nothing is subscribed up front: whoever
   * shows something subscribes to it (the Dashboard to its cards, the shell
   * to the user's notices), so a desktop holds no stream nobody reads.
   */
  initialize(_userId: string, _groups: string[] = []): void {
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
   * Request the total content size of each folder (its files at any depth) or
   * file (the file itself). The server computes them one at a time and emits
   * one event per path, then completes (`onComplete`); unsubscribing before
   * then drops the paths not yet computed. At most 1000 paths per call.
   */
  watchContentLengths(
    paths: string[],
    handler: EventHandler<ContentLengthEvent>,
    onComplete?: () => void
  ): () => void {
    return this.#client.subscribe(
      subscriptionDoc(`contentLengths(paths: [${paths.map(gqlString).join(', ')}])`, SELECTIONS.contentLength),
      handler,
      onComplete
    );
  }

  /**
   * Subscribe to the topic messages the signed-in user is in the audience of
   * (Mutation.publish / EventAdminAPI.publish). `topic` is one topic, a topic
   * followed by `/*` (that topic and everything under it), or `*`.
   */
  watchTopic(
    topic: string,
    handler: EventHandler<TopicMessageEvent>
  ): () => void {
    return this.#client.subscribe(
      subscriptionDoc(`topicMessage(topic: ${gqlString(topic)})`, SELECTIONS.topic),
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
