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

export type CamelContextState =
  | 'STARTED'
  | 'STOPPED'
  | 'SUSPENDED'
  | 'STARTING'
  | 'STOPPING'
  | 'SUSPENDING';

export interface CamelContext {
  name: string;
  version: string;
  state: CamelContextState;
  uptime: string;
  uptimeMillis: number;
  exchangesTotal: number;
  exchangesCompleted: number;
  exchangesFailed: number;
  exchangesInflight: number;
  meanProcessingTime?: number;
  maxProcessingTime?: number;
  minProcessingTime?: number;
  totalProcessingTime?: number;
  tracing: boolean;
  messageHistory: boolean;
  logMask: boolean;
  routes: Route[];
  components: Component[];
}

export type RouteState =
  | 'STARTED'
  | 'STOPPED'
  | 'SUSPENDED'
  | 'STARTING'
  | 'STOPPING'
  | 'SUSPENDING';

export interface Route {
  id: string;
  routeId: string;
  description?: string;
  group?: string;
  status: RouteState;
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

export interface RouteDefinition {
  id: string;
  yaml: string;
  from: EndpointDefinition;
  steps: RouteStep[];
}

export interface RouteStep {
  id: string;
  type: string;
  label?: string;
  description?: string;
  children?: RouteStep[];
  properties?: unknown;
}

export interface EndpointDefinition {
  uri: string;
  component: string;
  properties?: unknown;
}

export type EndpointState = 'STARTED' | 'STOPPED' | 'SUSPENDED';

export interface Endpoint {
  uri: string;
  component: string;
  state: EndpointState;
  remote: boolean;
  singleton: boolean;
  exchangesTotal?: number;
  exchangesCompleted?: number;
  exchangesFailed?: number;
}

export interface Component {
  name: string;
  state: string;
  class: string;
  supportedSchemes: string[];
}

export interface RouteTemplate {
  id: string;
  description?: string;
  parameters: TemplateParameter[];
  definition: RouteDefinition;
}

export interface TemplateParameter {
  name: string;
  description?: string;
  required: boolean;
  defaultValue?: string;
}

export interface ValidationResult {
  valid: boolean;
  errors: ValidationError[];
  warnings: ValidationWarning[];
}

export interface ValidationError {
  line?: number;
  column?: number;
  message: string;
  element?: string;
}

export interface ValidationWarning {
  line?: number;
  column?: number;
  message: string;
  element?: string;
}

export interface ExchangeResult {
  exchangeId: string;
  success: boolean;
  body?: string;
  headers?: unknown;
  exception?: string;
  processingTime: number;
}

export interface CreateRouteInput {
  routeId: string;
  description?: string;
  group?: string;
  yaml: string;
  autoStart?: boolean;
}

export interface UpdateRouteInput {
  id: string;
  description?: string;
  group?: string;
  yaml?: string;
}

export interface SendToEndpointInput {
  endpointUri: string;
  body?: string;
  headers?: unknown;
  properties?: unknown;
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
  path: string;
  node?: Node;
  timestamp: string;
  userId: string;
}

export interface QueryChangeEvent {
  statement: string;
  addedNodes: Node[];
  removedNodes: Node[];
  modifiedNodes: Node[];
  timestamp: string;
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

export interface JobProgressEvent {
  jobId: string;
  status: JobStatus;
  itemsTotal: number;
  itemsProcessed: number;
  nodesDeleted: number;
  currentPath?: string;
  errorMessage?: string;
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

export type NotificationType =
  | 'INFO'
  | 'WARNING'
  | 'ERROR'
  | 'TASK'
  | 'PROCESS'
  | 'CONTENT';

export type Severity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export interface SystemNotification {
  id: string;
  type: NotificationType;
  title: string;
  message: string;
  severity: Severity;
  timestamp: string;
  data?: unknown;
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
  password: string;
  sn?: string;
  givenName?: string;
  displayName?: string;
  mail?: string;
  enabled?: boolean;
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
  under1s: number;
  under5s: number;
  over5s: number;
}

export interface RouteStats {
  from: string;
  to: string;
  interval: StatInterval;
  points: StatPoint[];
}

export interface HistoryExchangeSummary {
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
