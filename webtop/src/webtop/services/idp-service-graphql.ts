/**
 * IdP Service - GraphQL wrapper for IdP user/group/role management.
 * Operates against the "system" workspace.
 */

import { GraphQLClient, createGraphQLClient } from '../graphql/client.js';
import { IDP_QUERIES, IDP_MUTATIONS } from '../graphql/queries/idp.js';
import type {
  IdpUser,
  IdpRole,
  IdpRoleTreeNode,
  IdpGroup,
  IdpGroupTreeNode,
  IdpUserConnection,
  IdpRoleConnection,
  IdpGroupConnection,
  CreateUserInput,
  CreateUserPayload,
  UpdateUserInput,
  UpdateUserPayload,
  DeleteUserInput,
  DeleteUserPayload,
  ChangePasswordInput,
  ChangePasswordPayload,
  ResetPasswordInput,
  ResetPasswordPayload,
  AssignRolesInput,
  AssignRolesPayload,
  RevokeRolesInput,
  RevokeRolesPayload,
  CreateRoleInput,
  CreateRolePayload,
  UpdateRoleInput,
  UpdateRolePayload,
  DeleteRoleInput,
  DeleteRolePayload,
  CreateGroupInput,
  CreateGroupPayload,
  UpdateGroupInput,
  UpdateGroupPayload,
  DeleteGroupInput,
  DeleteGroupPayload,
  MoveGroupInput,
  MoveGroupPayload,
  AddGroupMembersInput,
  AddGroupMembersPayload,
  RemoveGroupMembersInput,
  RemoveGroupMembersPayload,
  UpdatePreferencesInput,
  UpdatePreferencesPayload,
  UserOrderField,
  RoleOrderField,
  GroupOrderField,
  SortOrder,
} from '../graphql/types.js';

// Re-export types for convenience
export type {
  IdpUser,
  IdpRole,
  IdpRoleTreeNode,
  IdpGroup,
  IdpGroupTreeNode,
  IdpUserConnection,
  IdpRoleConnection,
  IdpGroupConnection,
};

export interface ListUsersOptions {
  first?: number;
  after?: string;
  query?: string;
  roleId?: string;
  groupId?: string;
  includeDescendants?: boolean;
  orderBy?: UserOrderField;
  orderDirection?: SortOrder;
}

export interface ListRolesOptions {
  first?: number;
  after?: string;
  query?: string;
  orderBy?: RoleOrderField;
  orderDirection?: SortOrder;
}

export interface ListGroupsOptions {
  first?: number;
  after?: string;
  query?: string;
  orderBy?: GroupOrderField;
  orderDirection?: SortOrder;
}

export interface RoleTreeOptions {
  rootRoleId?: string;
  maxDepth?: number;
}

export interface GroupTreeOptions {
  rootGroupId?: string;
  maxDepth?: number;
}

/**
 * IdP management service backed by GraphQL.
 */
export class IdpServiceGraphQL {
  #client: GraphQLClient;

  constructor(client?: GraphQLClient) {
    this.#client = client ?? createGraphQLClient('system');
  }

  // =========================================================================
  // User queries
  // =========================================================================

  async getUser(username: string): Promise<IdpUser | null> {
    const data = await this.#client.query<{ user: IdpUser | null }>(
      IDP_QUERIES.GET_USER,
      { username }
    );
    return data.user;
  }

  async getMe(): Promise<IdpUser | null> {
    const data = await this.#client.query<{ me: IdpUser | null }>(
      IDP_QUERIES.GET_ME
    );
    return data.me;
  }

  async listUsers(options: ListUsersOptions = {}): Promise<IdpUserConnection> {
    const data = await this.#client.query<{ users: IdpUserConnection }>(
      IDP_QUERIES.LIST_USERS,
      options as Record<string, unknown>
    );
    return data.users;
  }

  // =========================================================================
  // Role queries
  // =========================================================================

  async getRole(roleId: string): Promise<IdpRole | null> {
    const data = await this.#client.query<{ role: IdpRole | null }>(
      IDP_QUERIES.GET_ROLE,
      { roleId }
    );
    return data.role;
  }

  async listRoles(options: ListRolesOptions = {}): Promise<IdpRoleConnection> {
    const data = await this.#client.query<{ roles: IdpRoleConnection }>(
      IDP_QUERIES.LIST_ROLES,
      options as Record<string, unknown>
    );
    return data.roles;
  }

  async getRoleTree(options: RoleTreeOptions = {}): Promise<IdpRoleTreeNode[]> {
    const data = await this.#client.query<{ roleTree: IdpRoleTreeNode[] }>(
      IDP_QUERIES.GET_ROLE_TREE,
      options as Record<string, unknown>
    );
    return data.roleTree;
  }

  // =========================================================================
  // Group queries
  // =========================================================================

  async getGroup(groupId: string): Promise<IdpGroup | null> {
    const data = await this.#client.query<{ group: IdpGroup | null }>(
      IDP_QUERIES.GET_GROUP,
      { groupId }
    );
    return data.group;
  }

  async listGroups(options: ListGroupsOptions = {}): Promise<IdpGroupConnection> {
    const data = await this.#client.query<{ groups: IdpGroupConnection }>(
      IDP_QUERIES.LIST_GROUPS,
      options as Record<string, unknown>
    );
    return data.groups;
  }

  async getGroupTree(options: GroupTreeOptions = {}): Promise<IdpGroupTreeNode[]> {
    const data = await this.#client.query<{ groupTree: IdpGroupTreeNode[] }>(
      IDP_QUERIES.GET_GROUP_TREE,
      options as Record<string, unknown>
    );
    return data.groupTree;
  }

  // =========================================================================
  // User mutations
  // =========================================================================

  async createUser(input: CreateUserInput): Promise<CreateUserPayload> {
    const data = await this.#client.mutation<{ createUser: CreateUserPayload }>(
      IDP_MUTATIONS.CREATE_USER,
      { input }
    );
    return data.createUser;
  }

  async updateUser(input: UpdateUserInput): Promise<UpdateUserPayload> {
    const data = await this.#client.mutation<{ updateUser: UpdateUserPayload }>(
      IDP_MUTATIONS.UPDATE_USER,
      { input }
    );
    return data.updateUser;
  }

  async deleteUser(input: DeleteUserInput): Promise<DeleteUserPayload> {
    const data = await this.#client.mutation<{ deleteUser: DeleteUserPayload }>(
      IDP_MUTATIONS.DELETE_USER,
      { input }
    );
    return data.deleteUser;
  }

  async changePassword(input: ChangePasswordInput): Promise<ChangePasswordPayload> {
    const data = await this.#client.mutation<{ changePassword: ChangePasswordPayload }>(
      IDP_MUTATIONS.CHANGE_PASSWORD,
      { input }
    );
    return data.changePassword;
  }

  async resetPassword(input: ResetPasswordInput): Promise<ResetPasswordPayload> {
    const data = await this.#client.mutation<{ resetPassword: ResetPasswordPayload }>(
      IDP_MUTATIONS.RESET_PASSWORD,
      { input }
    );
    return data.resetPassword;
  }

  // =========================================================================
  // Role assignment mutations
  // =========================================================================

  async assignRoles(input: AssignRolesInput): Promise<AssignRolesPayload> {
    const data = await this.#client.mutation<{ assignRoles: AssignRolesPayload }>(
      IDP_MUTATIONS.ASSIGN_ROLES,
      { input }
    );
    return data.assignRoles;
  }

  async revokeRoles(input: RevokeRolesInput): Promise<RevokeRolesPayload> {
    const data = await this.#client.mutation<{ revokeRoles: RevokeRolesPayload }>(
      IDP_MUTATIONS.REVOKE_ROLES,
      { input }
    );
    return data.revokeRoles;
  }

  // =========================================================================
  // Role mutations
  // =========================================================================

  async createRole(input: CreateRoleInput): Promise<CreateRolePayload> {
    const data = await this.#client.mutation<{ createRole: CreateRolePayload }>(
      IDP_MUTATIONS.CREATE_ROLE,
      { input }
    );
    return data.createRole;
  }

  async updateRole(input: UpdateRoleInput): Promise<UpdateRolePayload> {
    const data = await this.#client.mutation<{ updateRole: UpdateRolePayload }>(
      IDP_MUTATIONS.UPDATE_ROLE,
      { input }
    );
    return data.updateRole;
  }

  async deleteRole(input: DeleteRoleInput): Promise<DeleteRolePayload> {
    const data = await this.#client.mutation<{ deleteRole: DeleteRolePayload }>(
      IDP_MUTATIONS.DELETE_ROLE,
      { input }
    );
    return data.deleteRole;
  }

  // =========================================================================
  // Group mutations
  // =========================================================================

  async createGroup(input: CreateGroupInput): Promise<CreateGroupPayload> {
    const data = await this.#client.mutation<{ createGroup: CreateGroupPayload }>(
      IDP_MUTATIONS.CREATE_GROUP,
      { input }
    );
    return data.createGroup;
  }

  async updateGroup(input: UpdateGroupInput): Promise<UpdateGroupPayload> {
    const data = await this.#client.mutation<{ updateGroup: UpdateGroupPayload }>(
      IDP_MUTATIONS.UPDATE_GROUP,
      { input }
    );
    return data.updateGroup;
  }

  async deleteGroup(input: DeleteGroupInput): Promise<DeleteGroupPayload> {
    const data = await this.#client.mutation<{ deleteGroup: DeleteGroupPayload }>(
      IDP_MUTATIONS.DELETE_GROUP,
      { input }
    );
    return data.deleteGroup;
  }

  async moveGroup(input: MoveGroupInput): Promise<MoveGroupPayload> {
    const data = await this.#client.mutation<{ moveGroup: MoveGroupPayload }>(
      IDP_MUTATIONS.MOVE_GROUP,
      { input }
    );
    return data.moveGroup;
  }

  async addGroupMembers(input: AddGroupMembersInput): Promise<AddGroupMembersPayload> {
    const data = await this.#client.mutation<{ addGroupMembers: AddGroupMembersPayload }>(
      IDP_MUTATIONS.ADD_GROUP_MEMBERS,
      { input }
    );
    return data.addGroupMembers;
  }

  async removeGroupMembers(input: RemoveGroupMembersInput): Promise<RemoveGroupMembersPayload> {
    const data = await this.#client.mutation<{ removeGroupMembers: RemoveGroupMembersPayload }>(
      IDP_MUTATIONS.REMOVE_GROUP_MEMBERS,
      { input }
    );
    return data.removeGroupMembers;
  }

  // =========================================================================
  // Preference mutations
  // =========================================================================

  async updatePreferences(input: UpdatePreferencesInput): Promise<UpdatePreferencesPayload> {
    const data = await this.#client.mutation<{ updatePreferences: UpdatePreferencesPayload }>(
      IDP_MUTATIONS.UPDATE_PREFERENCES,
      { input }
    );
    return data.updatePreferences;
  }
}
