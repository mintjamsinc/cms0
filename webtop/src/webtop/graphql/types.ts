/**
 * GraphQL Type Definitions
 * Generated from GRAPHQL_SCHEMA.graphql
 */

// =============================================================================
// Common Types
// =============================================================================

export interface PageInfo {
  hasNextPage: boolean;
  hasPreviousPage: boolean;
  startCursor: string | null;
  endCursor: string | null;
}

export type SortOrder = 'ASC' | 'DESC';

// =============================================================================
// Content Management (JCR)
// =============================================================================

export interface LockInfo {
  lockOwner: string;
  lockOwnerDisplayName?: string | null;
  isDeep: boolean;
  isSessionScoped: boolean;
  isLockOwningSession: boolean;
  // True when the lock is held by the current user. Unlike isLockOwningSession,
  // this also matches open-scoped locks taken by the same principal in an
  // earlier session (e.g. locks created from the content browser).
  isLockOwner?: boolean;
}

export interface Node {
  path: string;
  name: string;
  nodeType: string;
  // Stable JCR identifier, present for EVERY node (unlike uuid, which is set only
  // for mix:referenceable nodes). Prefer this as the durable identity key.
  id: string;
  uuid?: string;
  created: string;
  createdBy: string;
  createdByDisplayName?: string | null;
  modified: string;
  modifiedBy: string;
  modifiedByDisplayName?: string | null;
  mimeType?: string;
  size?: number;
  encoding?: string;
  downloadUrl?: string;
  scriptable?: boolean;
  webRender?: WebRender;
  hasChildren?: boolean;
  isLocked?: boolean;
  lockInfo?: LockInfo;
  isVersionable?: boolean;
  isCheckedOut?: boolean;
  baseVersionName?: string;
  properties: Property[];
  score?: number;
}

export interface Property {
  name: string;
  propertyValue: PropertyValue;
}

/**
 * How a file node is rendered when served over the web. The server resolves the
 * template binding from the file's own `web.template` property or, failing that,
 * the nearest ancestor folder's `.web.yml` descriptor, so clients do not have to
 * re-derive it (and could not, when the binding lives in a folder descriptor
 * rather than a per-file property).
 */
export interface WebRender {
  /** Whether the file is bound to a template and is therefore served as rendered output. */
  templated: boolean;
  /** Whether the binding comes from a folder `.web.yml` descriptor rather than a per-file property. */
  fromDescriptor: boolean;
  /** The source extension the file name carries (e.g. `md`), or null if it carries none. */
  source?: string | null;
  /** Allowed output extensions (e.g. `["html", "rss"]`); empty means any. */
  outputs: string[];
  /**
   * Nearest ancestor site document root path (e.g. `/content/public`), or null
   * when no ancestor folder declares one via `.web.yml` (`site.root: true`).
   * The folder the public site is mounted at; clients rewrite site-root-absolute
   * references (e.g. `/docs/css/docs.css`) beneath it for the active mount point.
   */
  documentRoot?: string | null;
}

/**
 * Detect whether a JCR node represents a folder (collection).
 *
 * The GraphQL schema does not expose `isCollection` directly, so callers must
 * derive it from `nodeType` (with `hasChildren`/`mimeType` as a fallback for
 * unknown types). Accepts a partial Node so it can be used against raw query
 * results or already-mapped objects without forcing a full type cast.
 */
export function isFolderNode(
  node: Pick<Node, 'nodeType' | 'hasChildren' | 'mimeType'> | null | undefined
): boolean {
  if (!node) return false;
  const t = node.nodeType;
  if (t === 'nt:folder') return true;
  if (t?.endsWith(':folder') || t?.endsWith('Folder')) return true;
  return node.hasChildren === true && !node.mimeType;
}

export type PropertyValue =
  | StringPropertyValue
  | StringPropertyValueArray
  | LongPropertyValue
  | LongPropertyValueArray
  | DoublePropertyValue
  | DoublePropertyValueArray
  | BooleanPropertyValue
  | BooleanPropertyValueArray
  | DatePropertyValue
  | DatePropertyValueArray
  | BinaryPropertyValue
  | BinaryPropertyValueArray
  | ReferencePropertyValue
  | ReferencePropertyValueArray
  | WeakreferencePropertyValue
  | WeakreferencePropertyValueArray;

export interface StringPropertyValue {
  __typename: 'StringPropertyValue';
  type: string;
  value: string;
}

export interface StringPropertyValueArray {
  __typename: 'StringPropertyValueArray';
  type: string;
  values: string[];
}

export interface LongPropertyValue {
  __typename: 'LongPropertyValue';
  type: string;
  value: number;
}

export interface LongPropertyValueArray {
  __typename: 'LongPropertyValueArray';
  type: string;
  values: number[];
}

export interface DoublePropertyValue {
  __typename: 'DoublePropertyValue';
  type: string;
  value: number;
}

export interface DoublePropertyValueArray {
  __typename: 'DoublePropertyValueArray';
  type: string;
  values: number[];
}

export interface BooleanPropertyValue {
  __typename: 'BooleanPropertyValue';
  type: string;
  value: boolean;
}

export interface BooleanPropertyValueArray {
  __typename: 'BooleanPropertyValueArray';
  type: string;
  values: boolean[];
}

export interface DatePropertyValue {
  __typename: 'DatePropertyValue';
  type: string;
  value: string;
}

export interface DatePropertyValueArray {
  __typename: 'DatePropertyValueArray';
  type: string;
  values: string[];
}

export interface BinaryPropertyValue {
  __typename: 'BinaryPropertyValue';
  type: string;
  value: string | null;
  mimeType?: string;
  size?: number;
}

export interface BinaryPropertyValueArray {
  __typename: 'BinaryPropertyValueArray';
  type: string;
  mimeTypes: (string | null)[];
  sizes: (number | null)[];
}

export interface ReferencePropertyValue {
  __typename: 'ReferencePropertyValue';
  type: string;
  value: string;
  path: string | null;
}

export interface ReferencePropertyValueArray {
  __typename: 'ReferencePropertyValueArray';
  type: string;
  values: string[];
  paths: (string | null)[];
}

export interface WeakreferencePropertyValue {
  __typename: 'WeakreferencePropertyValue';
  type: string;
  value: string;
  path: string | null;
}

export interface WeakreferencePropertyValueArray {
  __typename: 'WeakreferencePropertyValueArray';
  type: string;
  values: string[];
  paths: (string | null)[];
}

export interface NodeEdge {
  node: Node;
  cursor: string;
}

export interface NodeConnection {
  edges: NodeEdge[];
  pageInfo: PageInfo;
  totalCount: number;
}

export interface PropertyValueInput {
  stringValue?: string;
  stringArrayValue?: string[];
  longValue?: number;
  longArrayValue?: number[];
  doubleValue?: number;
  doubleArrayValue?: number[];
  booleanValue?: boolean;
  booleanArrayValue?: boolean[];
  dateValue?: string;
  dateArrayValue?: string[];
  binaryValue?: string;
  binaryUploadId?: string;
  binaryArrayUploadIds?: string[];
  binaryArrayItems?: { keepIndex?: number; uploadId?: string }[];
}

export interface PropertyInput {
  name: string;
  value: PropertyValueInput;
}

export interface SetPropertiesResult {
  node: Node | null;
  errors: PropertyError[];
}

export interface PropertyError {
  propertyName: string;
  message: string;
}

export interface Version {
  name: string;
  created: string;
  createdBy?: string;
  predecessors: string[];
  successors: string[];
  frozenNodePath?: string;
}

export interface VersionEdge {
  node: Version;
  cursor: string;
}

export interface VersionConnection {
  edges: VersionEdge[];
  pageInfo: PageInfo;
  totalCount: number;
  baseVersion: Version | null;
  versionableUuid: string;
}

export interface Principal {
  id: string;
  displayName: string | null;
  isGroup: boolean;
}

export interface AccessControlEntry {
  principal: Principal;
  privileges: string[];
  allow: boolean;
}

export interface AccessControl {
  entries: AccessControlEntry[];
}

export interface AccessControlEntryInput {
  principal: string;
  privileges: string[];
  allow?: boolean;
}

export interface EffectiveAccessControlPolicy {
  path: string;
  entries: AccessControlEntry[];
}

export interface PrincipalInfo {
  identifier: string;
  isGroup: boolean;
  isService?: boolean;
  displayName?: string | null;
}

// =============================================================================
// Business Process Management (Camunda BPM)
// =============================================================================

export interface ProcessDefinition {
  id: string;
  key: string;
  name?: string;
  description?: string;
  version: number;
  deploymentId: string;
  deploymentName?: string;
  resourceName?: string;
  diagramResourceName?: string;
  suspended: boolean;
  tenantId?: string;
  category?: string;
  startFormKey?: string;
}

export interface ProcessDefinitionEdge {
  node: ProcessDefinition;
  cursor: string;
}

export interface ProcessDefinitionConnection {
  edges: ProcessDefinitionEdge[];
  pageInfo: PageInfo;
  totalCount: number;
}

export interface ProcessInstance {
  id: string;
  definitionId: string;
  definitionKey: string;
  businessKey?: string;
  caseInstanceId?: string;
  suspended: boolean;
  ended: boolean;
  tenantId?: string;
  startTime: string;
  endTime?: string;
  durationInMillis?: number;
  variables: ProcessVariable[];
  incidentCount: number;
}

export interface ProcessInstanceEdge {
  node: ProcessInstance;
  cursor: string;
}

export interface ProcessInstanceConnection {
  edges: ProcessInstanceEdge[];
  pageInfo: PageInfo;
  totalCount: number;
}

export interface Task {
  id: string;
  name: string;
  description?: string;
  assignee?: string;
  owner?: string;
  created: string;
  due?: string;
  followUp?: string;
  priority: number;
  suspended: boolean;
  processInstanceId: string;
  processDefinitionId: string;
  processDefinitionKey: string;
  executionId: string;
  taskDefinitionKey: string;
  formKey?: string;
  candidateUsers: string[];
  candidateGroups: string[];
  variables: ProcessVariable[];
  localVariables: ProcessVariable[];
  // Flattened from `processInstance.businessKey` so callers can read it
  // without traversing the relation. Populated by listTasks/getTask.
  businessKey?: string | null;
  processInstance?: { businessKey?: string | null } | null;
}

export interface TaskEdge {
  node: Task;
  cursor: string;
}

export interface TaskConnection {
  edges: TaskEdge[];
  pageInfo: PageInfo;
  totalCount: number;
}

export type TaskSortField =
  | 'ID'
  | 'NAME'
  | 'ASSIGNEE'
  | 'CREATED'
  | 'DUE'
  | 'FOLLOW_UP'
  | 'PRIORITY'
  | 'PROCESS_INSTANCE_ID';

export interface TaskCounts {
  total: number;
  unassigned: number;
  assigned: number;
  overdue: number;
  dueToday: number;
  dueThisWeek: number;
}

export interface ProcessVariable {
  name: string;
  type: string;
  value: unknown;
  valueInfo?: unknown;
}

export type IncidentType = 'failedJob' | 'failedExternalTask' | 'custom';

export interface Incident {
  id: string;
  type: IncidentType;
  message: string;
  stackTrace?: string;
  activityId: string;
  activityName?: string;
  processInstanceId: string;
  processDefinitionId: string;
  processDefinitionKey?: string;
  executionId?: string;
  jobId?: string;
  jobRetries: number;
  causeIncidentId?: string;
  rootCauseIncidentId?: string;
  configuration?: string;
  annotation?: string;
  incidentTimestamp: string;
  tenantId?: string;
}

export interface IncidentEdge {
  node: Incident;
  cursor: string;
}

export interface IncidentConnection {
  edges: IncidentEdge[];
  pageInfo: PageInfo;
  totalCount: number;
}

export interface ProcessVariableInput {
  name: string;
  type?: string;
  value: unknown;
  valueInfo?: unknown;
}

export interface ActivityInstance {
  id: string;
  activityId: string;
  activityName?: string;
  activityType: string;
  startTime: string;
  endTime?: string;
  durationInMillis?: number;
  canceled: boolean;
  completeScope: boolean;
}

export interface TaskComment {
  id: string;
  taskId: string;
  userId: string;
  time: string;
  message: string;
}

export interface Deployment {
  id: string;
  name?: string;
  deploymentTime: string;
  source?: string;
  tenantId?: string;
}

export interface StartProcessInput {
  definitionKey?: string;
  definitionId?: string;
  businessKey?: string;
  variables?: ProcessVariableInput[];
}

export interface CompleteTaskInput {
  taskId: string;
  variables?: ProcessVariableInput[];
  withVariablesInReturn?: boolean;
}

export interface DeploymentResourceInput {
  name: string;
  content: string;
}

export interface DeployProcessInput {
  name: string;
  source?: string;
  resources: DeploymentResourceInput[];
  enableDuplicateFiltering?: boolean;
  deployChangedOnly?: boolean;
}

// =============================================================================
// Process Instance Migration
// =============================================================================

export interface MigrationInstruction {
  sourceActivityIds: string[];
  targetActivityIds: string[];
  updateEventTrigger: boolean;
}

export interface MigrationPlan {
  sourceProcessDefinitionId: string;
  targetProcessDefinitionId: string;
  instructions: MigrationInstruction[];
}

/**
 * Handle to the CMS background job that drives a process-instance migration.
 * The mutation returns this immediately; live progress is delivered via
 * `jobProgress(jobId: id)`. `abortable` is `false` for Camunda 7 because the
 * underlying batch cannot be safely cancelled mid-flight — the client uses
 * it to hide the Abort button on the migration overlay.
 */
export interface MigrationJob {
  id: string;
  jobType: string;
  status: string;
  abortable: boolean;
}

export interface CreateMigrationPlanInput {
  sourceProcessDefinitionId: string;
  targetProcessDefinitionId: string;
  mapEqualActivities?: boolean;
  updateEventTriggers?: boolean;
}

export interface MigrateProcessInstanceInput {
  sourceProcessDefinitionId: string;
  targetProcessDefinitionId: string;
  processInstanceIds?: string[];
  allActiveInstances?: boolean;
  mapEqualActivities?: boolean;
  updateEventTriggers?: boolean;
  skipCustomListeners?: boolean;
  skipIoMappings?: boolean;
}

// =============================================================================
// Enterprise Integration Patterns (Apache Camel)
// =============================================================================

// Mirrors Camel's org.apache.camel.ServiceStatus enum constant names
// (PascalCase), as returned by the server (ServiceStatus.name()); 'Unknown'
// is the server's fallback when the status is unavailable.
export type RouteState =
  | 'Started'
  | 'Stopped'
  | 'Suspended'
  | 'Starting'
  | 'Stopping'
  | 'Suspending'
  | 'Unknown';

export interface Route {
  id: string;
  routeId: string;
  description?: string;
  group?: string;
  status: RouteState;
  /** Readiness from Camel Health Checks: 'UP' | 'DOWN' | 'UNKNOWN'. */
  health?: HealthState | null;
  uptime?: string;
  uptimeMillis?: number;
  exchangesTotal: number;
  exchangesCompleted: number;
  exchangesFailed: number;
  exchangesInflight: number;
  meanProcessingTime?: number;
  maxProcessingTime?: number;
  minProcessingTime?: number;
  lastProcessingTime?: number;
  totalProcessingTime?: number;
  lastError?: RouteError;
  firstExchangeCompletedTime?: string;
  lastExchangeCompletedTime?: string;
  firstExchangeFailureTime?: string;
  lastExchangeFailureTime?: string;
  definition: RouteDefinition;
  endpoints: Endpoint[];
  consumers: Endpoint[];
  producers: Endpoint[];
}

export interface RouteError {
  exchangeId?: string;
  timestamp: string;
  message: string;
  stackTrace?: string;
  endpoint?: string;
}

export interface RouteEdge {
  node: Route;
  cursor: string;
}

export interface RouteConnection {
  edges: RouteEdge[];
  pageInfo: PageInfo;
  totalCount: number;
}

/**
 * A route's structured model, dumped from the live engine. Both Camel DSL
 * interchange formats are provided:
 *   - xml  : Camel XML DSL — fed to the shared <eip-canvas> for a faithful,
 *            auto-laid-out, read-only diagram.
 *   - yaml : Camel YAML DSL — human-friendly export.
 * Either may be null if the engine could not dump that representation.
 */
export interface RouteDefinition {
  id: string;
  xml: string | null;
  yaml: string | null;
}

export type EndpointState = 'STARTED' | 'STOPPED' | 'SUSPENDED';

export type HealthState = 'UP' | 'DOWN' | 'UNKNOWN';

export interface Endpoint {
  uri: string;
  component: string;
  state: EndpointState;
  remote: boolean;
  singleton: boolean;
  exchangesTotal?: number;
  exchangesCompleted?: number;
  exchangesFailed?: number;
  /**
   * Readiness from Camel Health Checks: reflects whether the external system
   * this endpoint talks to is actually reachable (as opposed to `state`, which
   * is the endpoint's lifecycle). 'UNKNOWN' when no health check covers it.
   */
  health?: HealthState | null;
}

export interface StartRouteInput {
  id: string;
}

export interface StopRouteInput {
  id: string;
  timeout?: number;
}

export interface SuspendRouteInput {
  id: string;
  timeout?: number;
}

export interface ResumeRouteInput {
  id: string;
}

// =============================================================================
// Subscription Events
// =============================================================================

export type NodeEventType =
  | 'CREATED'
  | 'MODIFIED'
  | 'DELETED'
  | 'MOVED'
  | 'LOCKED'
  | 'UNLOCKED'
  | 'CHECKED_IN'
  | 'CHECKED_OUT';

export interface NodeChangeEvent {
  eventType: NodeEventType;
  // Absent on a DELETED drop signal for a node the subscriber cannot read: the
  // server withholds the path/name and the client drops the item by `identifier`.
  path?: string;
  // JCR identifier (UUID for referenceable nodes); the drop key for DELETED.
  identifier?: string;
  // Original path for MOVED events.
  sourcePath?: string;
  node?: Node;
  timestamp: string;
  userId: string;
}

// =============================================================================
// Background jobs
// =============================================================================

export type JobStatus =
  | 'init'
  | 'queued'
  | 'running'
  | 'aborting'
  | 'completed'
  | 'aborted'
  | 'failed';

export interface InitDeleteNodesResult {
  jobId: string;
  status: JobStatus;
}

export interface AppendDeleteNodesResult {
  jobId: string;
  status: JobStatus;
  itemsAccepted: number;
}

export interface StartDeleteNodesResult {
  jobId: string;
  status: JobStatus;
  itemsTotal: number;
}

export interface AbortDeleteNodesResult {
  jobId: string;
  status: JobStatus;
}

export interface InitDownloadArchiveResult {
  jobId: string;
  status: JobStatus;
}

export interface AppendDownloadArchiveResult {
  jobId: string;
  status: JobStatus;
  itemsAccepted: number;
}

export interface StartDownloadArchiveResult {
  jobId: string;
  status: JobStatus;
  itemsTotal: number;
}

export interface AbortDownloadArchiveResult {
  jobId: string;
  status: JobStatus;
}

export interface InitImportArchiveResult {
  jobId: string;
  status: JobStatus;
}

export interface StartImportArchiveResult {
  jobId: string;
  status: JobStatus;
}

export interface AbortImportArchiveResult {
  jobId: string;
  status: JobStatus;
}

/**
 * Identifier-conflict behaviour, as the integer codes of the server's
 * ImportContentHandler: 0 throw on collision, 1 new identifier on collision,
 * 2 always a new identifier.
 */
export type ImportUuidBehavior = 0 | 1 | 2;
/**
 * Path-conflict behaviour, as the integer codes of the server's
 * ImportContentHandler: 0 throw on conflict, 1 skip, 2 overwrite.
 */
export type ImportPathBehavior = 0 | 1 | 2;

export interface ImportArchiveOptions {
  /** Repository path of the uploaded CMS Archive (nt:file). */
  archivePath: string;
  /** Original file name of the uploaded archive (recorded on the job). */
  filename?: string;
  /** Destination the archive is imported under. */
  destinationPath: string;
  /** Identifier-conflict behaviour. Default 0 (throw on collision). */
  uuidBehavior?: ImportUuidBehavior;
  /** Path-conflict behaviour. Default 0 (throw on conflict). */
  pathBehavior?: ImportPathBehavior;
  /** Reinstate access control carried by the archive. Default false. */
  importAcl?: boolean;
  /**
   * Carry over each node's original `jcr:created`/`jcr:lastModified` from the
   * archive. Default true. When false the repository stamps the import time.
   * `jcr:createdBy`/`jcr:lastModifiedBy` are always the importing user regardless.
   */
  preserveTimestamps?: boolean;
  /** Validate and report only; make no changes. Default false. */
  dryRun?: boolean;
}

export interface JobProgressEvent {
  jobId: string;
  status: JobStatus;
  itemsTotal: number;
  itemsProcessed: number;
  /** Delete jobs only: number of items removed (nt:file and nt:folder only). */
  itemsDeleted?: number;
  /** Archive jobs only: number of files written into the ZIP. */
  itemsArchived?: number;
  /** Import jobs only: number of nodes created/updated. */
  itemsImported?: number;
  /** Import jobs only: per-file outcome counts (the four sum to itemsTotal). */
  itemsNew?: number;
  itemsOverwritten?: number;
  itemsSkipped?: number;
  itemsError?: number;
  /** Import jobs only: first errors (up to 20), each `path\tmessage`. */
  errorSamples?: string[];
  /**
   * Import dry-run verdict (present only on a dry run's terminal event): whether
   * the rehearsal hit a problem that would make the real import fail.
   */
  dryRunHasErrors?: boolean;
  /** Import dry run: number of nodes the archive would import (manifest count). */
  dryRunNodeCount?: number;
  /** Import dry run: number of binaries the archive carries (manifest count). */
  dryRunBinaryCount?: number;
  /** Import dry run: human-readable detail of the blocking problem, when `dryRunHasErrors`. */
  dryRunDetail?: string;
  currentPath?: string;
  errorMessage?: string;
  /** Set on the terminal event of jobs that produce a downloadable artifact (archive jobs). */
  downloadUrl?: string;
  /**
   * Coarse phase of a multi-step job, mapped by the UI to a localized progress
   * message. Workspace lifecycle jobs publish `creating`/`starting` (create)
   * and `stopping`/`deleting` (delete); absent on jobs with no sub-phases.
   */
  phase?: string;
  /** Workspace a lifecycle job acts upon; absent on other job types. */
  targetWorkspace?: string;
  timestamp: string;
}

/**
 * Emitted when any workspace's runtime state changes (started or stopped)
 * anywhere in the repository. Carries the affected workspace name; subscribers
 * re-read the workspace list — the live source of truth for which workspaces
 * are selectable — rather than trusting a state snapshot in the event.
 */
export interface WorkspaceChangeEvent {
  workspace: string;
  timestamp: string;
}

export type TaskEventType = 'CREATED' | 'ASSIGNED' | 'COMPLETED' | 'DELETED';

export interface TaskEvent {
  eventType: TaskEventType;
  task?: Task;
  timestamp: string;
}

export type ProcessEventType =
  | 'STARTED'
  | 'SUSPENDED'
  | 'ACTIVATED'
  | 'ENDED'
  | 'CANCELLED';

export interface ProcessEvent {
  eventType: ProcessEventType;
  processInstance?: ProcessInstance;
  timestamp: string;
}

export interface RouteStateEvent {
  routeId: string;
  previousState?: RouteState;
  currentState: RouteState;
  timestamp: string;
  error?: string;
}

// =============================================================================
// Multipart Upload
// =============================================================================

export interface MultipartUploadInfo {
  uploadId: string;
  totalSize: number;
}

export interface AppendMultipartUploadChunkInput {
  uploadId: string;
  data: string; // Base64 encoded chunk
}

export interface CompleteMultipartUploadInput {
  uploadId: string;
  path: string;
  name: string;
  mimeType: string;
  overwrite?: boolean;
}

export interface AbortMultipartUploadInput {
  uploadId: string;
}

export interface InitiateMultipartUploadInput {
  // Reserved for future use
  _placeholder?: string;
}

// =============================================================================
// Preferences
// =============================================================================

/**
 * Preference values for a single category (e.g., "appearance").
 * Matches the GraphQL subscription type PreferenceUpdate defined on the server.
 *
 * Server-side: when /home/users/{userId}/preferences/{category}/jcr:content changes,
 * the CMS reads all non-jcr: string properties and publishes them as `data`.
 */
export interface PreferenceUpdate {
  /** Preference category (e.g., "appearance", "regional") */
  category: string;
  /** Preference values as a key-value map */
  data: Record<string, unknown>;
}

/**
 * SSE event payload for the preferenceChanged subscription.
 * GraphQL subscription: preferenceChanged(userId: "<userId>")
 */
export interface PreferenceChangeEvent extends PreferenceUpdate {
  timestamp: string;
  userId: string;
}

/**
 * SSE event payload for the avatarChanged subscription.
 * GraphQL subscription: avatarChanged(userId: "<userId>")
 */
export interface AvatarChangeEvent {
  userId: string;
  timestamp: string;
}

/**
 * SSE event payload for the wallpaperChanged subscription.
 * GraphQL subscription: wallpaperChanged(userId: "<userId>")
 */
export interface WallpaperChangeEvent {
  userId: string;
  action: 'added' | 'updated' | 'deleted';
  filename: string;
  timestamp: string;
}

// =============================================================================
// IdP — User, Role, Group Management
// =============================================================================

export interface IdpRole {
  roleId: string;
  name: string;
  displayName: string | null;
  description: string | null;
  depth: number;
  hasChildren: boolean;
  descendantCount: number;
  parent?: IdpRole | null;
  ancestors?: IdpRole[];
  created: string | null;
  lastModified: string | null;
}

export interface IdpRoleTreeNode {
  roleId: string;
  name: string;
  displayName: string | null;
  hasChildren: boolean;
  depth: number;
  children: IdpRoleTreeNode[];
}

export interface IdpGroup {
  groupId: string;
  name: string;
  displayName: string | null;
  description: string | null;
  depth: number;
  hasChildren: boolean;
  descendantCount: number;
  parent?: IdpGroup | null;
  ancestors?: IdpGroup[];
  created: string | null;
  lastModified: string | null;
}

export interface IdpGroupTreeNode {
  groupId: string;
  name: string;
  displayName: string | null;
  hasChildren: boolean;
  depth: number;
  memberCount: number;
  children: IdpGroupTreeNode[];
}

export interface IdpUser {
  username: string;
  sn: string | null;
  givenName: string | null;
  displayName: string | null;
  mail: string | null;
  enabled: boolean;
  /**
   * Whether this is a service account: a non-interactive identity used by
   * integrations (via runAs). Service accounts have no password and can never
   * sign in. Defaults to false.
   */
  isService: boolean;
  roles: IdpRole[];
  memberOf: IdpGroup[];
  effectiveGroups: IdpGroup[];
  hasAvatar: boolean;
  avatarUrl: string | null;
  created: string | null;
  lastModified: string | null;
  lastLogin: string | null;
}

export interface IdpUserEdge {
  cursor: string;
  node: IdpUser;
}

export interface IdpUserConnection {
  edges: IdpUserEdge[];
  pageInfo: PageInfo;
  totalCount: number;
}

export interface IdpRoleEdge {
  cursor: string;
  node: IdpRole;
}

export interface IdpRoleConnection {
  edges: IdpRoleEdge[];
  pageInfo: PageInfo;
  totalCount: number;
}

export interface IdpGroupEdge {
  cursor: string;
  node: IdpGroup;
}

export interface IdpGroupConnection {
  edges: IdpGroupEdge[];
  pageInfo: PageInfo;
  totalCount: number;
}

export interface IdpMutationError {
  field: string | null;
  message: string;
  code: IdpErrorCode;
}

export type IdpErrorCode =
  | 'NOT_FOUND'
  | 'ALREADY_EXISTS'
  | 'INVALID_INPUT'
  | 'PERMISSION_DENIED'
  | 'PASSWORD_TOO_WEAK'
  | 'INVALID_CREDENTIALS'
  | 'HAS_CHILDREN'
  | 'HAS_MEMBERS'
  | 'CIRCULAR_REFERENCE'
  | 'INTERNAL_ERROR';

export type UserOrderField = 'USERNAME' | 'DISPLAY_NAME' | 'MAIL' | 'CREATED' | 'LAST_LOGIN';
export type RoleOrderField = 'ROLE_ID' | 'DISPLAY_NAME' | 'CREATED';
export type GroupOrderField = 'GROUP_ID' | 'DISPLAY_NAME' | 'CREATED';

// --- User mutation inputs & payloads ---

export interface CreateUserInput {
  username: string;
  /** Required for interactive users; omitted for service accounts (service: true). */
  password?: string;
  sn?: string;
  givenName?: string;
  displayName?: string;
  mail?: string;
  enabled?: boolean;
  /**
   * Create a service account: a non-interactive identity used by integrations
   * (via runAs). When true, no password is required. Defaults to false.
   */
  service?: boolean;
  roles?: string[];
  memberOf?: string[];
}

export interface CreateUserPayload {
  user: IdpUser | null;
  errors: IdpMutationError[] | null;
}

export interface UpdateUserInput {
  username: string;
  sn?: string;
  givenName?: string;
  displayName?: string;
  mail?: string;
  enabled?: boolean;
}

export interface UpdateUserPayload {
  user: IdpUser | null;
  errors: IdpMutationError[] | null;
}

export interface DeleteUserInput {
  username: string;
}

export interface DeleteUserPayload {
  username: string;
  errors: IdpMutationError[] | null;
}

export interface ChangePasswordInput {
  username: string;
  currentPassword?: string;
  newPassword: string;
}

export interface ChangePasswordPayload {
  user: Pick<IdpUser, 'username' | 'displayName'> | null;
  errors: IdpMutationError[] | null;
}

export interface ResetPasswordInput {
  username: string;
  newPassword: string;
}

export interface ResetPasswordPayload {
  user: Pick<IdpUser, 'username' | 'displayName'> | null;
  errors: IdpMutationError[] | null;
}

// --- Role assignment inputs & payloads ---

export interface AssignRolesInput {
  username: string;
  roles: string[];
}

export interface AssignRolesPayload {
  user: IdpUser | null;
  errors: IdpMutationError[] | null;
}

export interface RevokeRolesInput {
  username: string;
  roles: string[];
}

export interface RevokeRolesPayload {
  user: IdpUser | null;
  errors: IdpMutationError[] | null;
}

// --- Role mutation inputs & payloads ---

export interface CreateRoleInput {
  name: string;
  parentRoleId?: string;
  displayName?: string;
  description?: string;
}

export interface CreateRolePayload {
  role: IdpRole | null;
  errors: IdpMutationError[] | null;
}

export interface UpdateRoleInput {
  roleId: string;
  displayName?: string;
  description?: string;
}

export interface UpdateRolePayload {
  role: IdpRole | null;
  errors: IdpMutationError[] | null;
}

export interface DeleteRoleInput {
  roleId: string;
  removeFromUsers?: boolean;
  recursive?: boolean;
}

export interface DeleteRolePayload {
  roleId: string;
  errors: IdpMutationError[] | null;
}

// --- Group mutation inputs & payloads ---

export interface CreateGroupInput {
  parentGroupId?: string;
  name: string;
  displayName?: string;
  description?: string;
}

export interface CreateGroupPayload {
  group: IdpGroup | null;
  errors: IdpMutationError[] | null;
}

export interface UpdateGroupInput {
  groupId: string;
  displayName?: string;
  description?: string;
}

export interface UpdateGroupPayload {
  group: IdpGroup | null;
  errors: IdpMutationError[] | null;
}

export interface DeleteGroupInput {
  groupId: string;
  recursive?: boolean;
}

export interface DeleteGroupPayload {
  groupId: string;
  errors: IdpMutationError[] | null;
}

export interface MoveGroupInput {
  groupId: string;
  newParentGroupId?: string;
  newName?: string;
}

export interface MoveGroupPayload {
  group: IdpGroup | null;
  previousGroupId: string | null;
  errors: IdpMutationError[] | null;
}

export interface AddGroupMembersInput {
  groupId: string;
  usernames: string[];
}

export interface AddGroupMembersPayload {
  group: IdpGroup | null;
  errors: IdpMutationError[] | null;
}

export interface RemoveGroupMembersInput {
  groupId: string;
  usernames: string[];
}

export interface RemoveGroupMembersPayload {
  group: IdpGroup | null;
  errors: IdpMutationError[] | null;
}

// --- Preference mutation inputs & payloads ---

export interface UpdatePreferencesInput {
  username: string;
  category: string;
  data: Record<string, unknown>;
}

export interface UpdatePreferencesPayload {
  category: string | null;
  data: Record<string, unknown> | null;
  errors: IdpMutationError[] | null;
}

// =============================================================================
// EIP Stats / History (EIP Console)
// =============================================================================

/**
 * Bucket interval returned by `routeStats`. The server auto-resolves the
 * interval from the requested time range when none is supplied:
 *
 *   range  → interval
 *   1h    → 5min
 *   24h   → 1h
 *   7d    → 1d
 *   30d   → 1d
 */
export type StatInterval = '5min' | '1h' | '1d';

/**
 * Status filter for `routeStats` / `historyExchanges`.
 *
 * `all` and the empty string both mean "no filter".
 */
export type StatusFilter = 'all' | 'completed' | 'failed';

/**
 * One bucket of the three-band exchange-count time series.
 *
 *   under1s — elapsed < 1000ms
 *   under5s — 1000ms ≤ elapsed < 5000ms
 *   over5s  — elapsed ≥ 5000ms
 */
export interface StatPoint {
  bucket: string;
  /**
   * Per-band exchange counts, aligned with RouteStats.boundaries:
   * bands[0] = elapsed < boundaries[0]; bands[i] = [boundaries[i-1],
   * boundaries[i]); bands[last] = elapsed >= boundaries[last].
   */
  bands: number[];
}

export interface RouteStats {
  /**
   * Anchor instant the window is right-aligned to (anchor mode). The right-edge
   * bucket is the fixed wall-clock bucket that contains this instant.
   */
  anchor?: string;
  from: string;
  to: string;
  interval: StatInterval;
  /** Ascending elapsed-band boundaries in ms (N boundaries => N+1 bands). */
  boundaries: number[];
  points: StatPoint[];
}

export interface HistoryExchangeSummary {
  /**
   * JCR node path — the stable, globally-unique identity of a history record.
   * A single exchange that completes in multiple routes yields one record per
   * route (same exchangeId AND createdAt), so use this, not exchangeId, to key
   * list rows and to open the inspector.
   */
  path: string;
  exchangeId: string;
  routeId: string | null;
  status: string | null;
  elapsed: number | null;
  createdAt: string | null;
  businessKey: string | null;
}

export interface HistoryExchangeEdge {
  node: HistoryExchangeSummary;
  cursor: string;
}

export interface HistoryExchangeConnection {
  edges: HistoryExchangeEdge[];
  pageInfo: PageInfo;
  totalCount: number;
}

export interface HistoryStep {
  /** DSL-assigned step id (preferred for labels). */
  id: string | null;
  /** Endpoint URI; only set for to/toD/from steps. */
  endpointUri: string | null;
  timeTaken: number | null;
  offsetFromStart: number | null;
  order: number | null;
}

export interface HistoryExchange {
  /** JCR node path — stable unique identity (see HistoryExchangeSummary). */
  path: string;
  exchangeId: string;
  routeId: string | null;
  status: string | null;
  elapsed: number | null;
  createdAt: string | null;
  completedAt: string | null;
  exceptionType: string | null;
  exceptionMessage: string | null;
  businessKey: string | null;
  bodyType: string | null;
  bodySize: number | null;
  headers: Record<string, unknown> | null;
  steps: HistoryStep[];
}
