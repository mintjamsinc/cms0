/**
 * GraphQL Queries and Mutations for IdP User/Group/Role Management
 */

// =============================================================================
// Fragments
// =============================================================================

const ROLE_BASIC_FIELDS = `
  roleId
  name
  displayName
  description
  depth
  hasChildren
  descendantCount
  created
  lastModified
`;

const ROLE_FIELDS = ROLE_BASIC_FIELDS;

const GROUP_BASIC_FIELDS = `
  groupId
  name
  displayName
  description
  depth
  hasChildren
  descendantCount
  created
  lastModified
`;

const USER_BASIC_FIELDS = `
  username
  sn
  givenName
  displayName
  mail
  enabled
  hasAvatar
  avatarUrl
  created
  lastModified
  lastLogin
`;

const USER_FULL_FIELDS = `
  ${USER_BASIC_FIELDS}
  roles {
    ${ROLE_FIELDS}
  }
  memberOf {
    ${GROUP_BASIC_FIELDS}
  }
  effectiveGroups {
    ${GROUP_BASIC_FIELDS}
  }
`;

const MUTATION_ERROR_FIELDS = `
  field
  message
  code
`;

const PAGE_INFO_FIELDS = `
  hasNextPage
  hasPreviousPage
  startCursor
  endCursor
`;

// =============================================================================
// User Queries
// =============================================================================

export const IDP_QUERIES = {
  GET_USER: `
    query GetUser($username: String!) {
      user(username: $username) {
        ${USER_FULL_FIELDS}
      }
    }
  `,

  GET_ME: `
    query GetMe {
      me {
        ${USER_FULL_FIELDS}
      }
    }
  `,

  LIST_USERS: `
    query ListUsers(
      $first: Int
      $after: String
      $last: Int
      $before: String
      $query: String
      $roleId: String
      $groupId: String
      $includeDescendants: Boolean
      $orderBy: UserOrderField
      $orderDirection: OrderDirection
    ) {
      users(
        first: $first
        after: $after
        last: $last
        before: $before
        query: $query
        roleId: $roleId
        groupId: $groupId
        includeDescendants: $includeDescendants
        orderBy: $orderBy
        orderDirection: $orderDirection
      ) {
        edges {
          cursor
          node {
            ${USER_FULL_FIELDS}
          }
        }
        pageInfo {
          ${PAGE_INFO_FIELDS}
        }
        totalCount
      }
    }
  `,

  // --- Role queries ---

  GET_ROLE: `
    query GetRole($roleId: String!) {
      role(roleId: $roleId) {
        ${ROLE_BASIC_FIELDS}
        parent {
          ${ROLE_BASIC_FIELDS}
        }
        ancestors {
          ${ROLE_BASIC_FIELDS}
        }
      }
    }
  `,

  GET_ROLE_TREE: `
    query GetRoleTree($rootRoleId: String, $maxDepth: Int) {
      roleTree(rootRoleId: $rootRoleId, maxDepth: $maxDepth) {
        roleId name displayName hasChildren depth
        children {
          roleId name displayName hasChildren depth
          children {
            roleId name displayName hasChildren depth
            children {
              roleId name displayName hasChildren depth
              children {
                roleId name displayName hasChildren depth
                children {
                  roleId name displayName hasChildren depth
                  children {
                    roleId name displayName hasChildren depth
                    children {
                      roleId name displayName hasChildren depth
                      children {
                        roleId name displayName hasChildren depth
                        children {
                          roleId name displayName hasChildren depth
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  `,

  LIST_ROLES: `
    query ListRoles(
      $first: Int
      $after: String
      $query: String
      $orderBy: RoleOrderField
      $orderDirection: OrderDirection
    ) {
      roles(
        first: $first
        after: $after
        query: $query
        orderBy: $orderBy
        orderDirection: $orderDirection
      ) {
        edges {
          cursor
          node {
            ${ROLE_FIELDS}
          }
        }
        pageInfo {
          ${PAGE_INFO_FIELDS}
        }
        totalCount
      }
    }
  `,

  // --- Group queries ---

  GET_GROUP: `
    query GetGroup($groupId: String!) {
      group(groupId: $groupId) {
        ${GROUP_BASIC_FIELDS}
        parent {
          ${GROUP_BASIC_FIELDS}
        }
        ancestors {
          ${GROUP_BASIC_FIELDS}
        }
      }
    }
  `,

  LIST_GROUPS: `
    query ListGroups(
      $first: Int
      $after: String
      $query: String
      $orderBy: GroupOrderField
      $orderDirection: OrderDirection
    ) {
      groups(
        first: $first
        after: $after
        query: $query
        orderBy: $orderBy
        orderDirection: $orderDirection
      ) {
        edges {
          cursor
          node {
            ${GROUP_BASIC_FIELDS}
          }
        }
        pageInfo {
          ${PAGE_INFO_FIELDS}
        }
        totalCount
      }
    }
  `,

  GET_GROUP_TREE: `
    query GetGroupTree($rootGroupId: String, $maxDepth: Int) {
      groupTree(rootGroupId: $rootGroupId, maxDepth: $maxDepth) {
        groupId
        name
        displayName
        hasChildren
        depth
        memberCount
        children {
          groupId
          name
          displayName
          hasChildren
          depth
          memberCount
          children {
            groupId
            name
            displayName
            hasChildren
            depth
            memberCount
            children {
              groupId
              name
              displayName
              hasChildren
              depth
              memberCount
              children {
                groupId
                name
                displayName
                hasChildren
                depth
                memberCount
                children {
                  groupId
                  name
                  displayName
                  hasChildren
                  depth
                  memberCount
                  children {
                    groupId
                    name
                    displayName
                    hasChildren
                    depth
                    memberCount
                    children {
                      groupId
                      name
                      displayName
                      hasChildren
                      depth
                      memberCount
                      children {
                        groupId
                        name
                        displayName
                        hasChildren
                        depth
                        memberCount
                        children {
                          groupId
                          name
                          displayName
                          hasChildren
                          depth
                          memberCount
                          children {
                            groupId
                            name
                            displayName
                            hasChildren
                            depth
                            memberCount
                            children {
                              groupId
                              name
                              displayName
                              hasChildren
                              depth
                              memberCount
                              children {
                                groupId
                                name
                                displayName
                                hasChildren
                                depth
                                memberCount
                                children {
                                  groupId
                                  name
                                  displayName
                                  hasChildren
                                  depth
                                  memberCount
                                  children {
                                    groupId
                                    name
                                    displayName
                                    hasChildren
                                    depth
                                    memberCount
                                    children {
                                      groupId
                                      name
                                      displayName
                                      hasChildren
                                      depth
                                      memberCount
                                      children {
                                        groupId
                                        name
                                        displayName
                                        hasChildren
                                        depth
                                        memberCount
                                        children {
                                          groupId
                                          name
                                          displayName
                                          hasChildren
                                          depth
                                          memberCount
                                          children {
                                            groupId
                                            name
                                            displayName
                                            hasChildren
                                            depth
                                            memberCount
                                          }
                                        }
                                      }
                                    }
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  `,
} as const;

// =============================================================================
// Mutations
// =============================================================================

export const IDP_MUTATIONS = {
  // --- User mutations ---

  CREATE_USER: `
    mutation CreateUser($input: CreateUserInput!) {
      createUser(input: $input) {
        user {
          ${USER_FULL_FIELDS}
        }
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,

  UPDATE_USER: `
    mutation UpdateUser($input: UpdateUserInput!) {
      updateUser(input: $input) {
        user {
          ${USER_FULL_FIELDS}
        }
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,

  DELETE_USER: `
    mutation DeleteUser($input: DeleteUserInput!) {
      deleteUser(input: $input) {
        username
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,

  CHANGE_PASSWORD: `
    mutation ChangePassword($input: ChangePasswordInput!) {
      changePassword(input: $input) {
        user {
          username
          displayName
        }
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,

  RESET_PASSWORD: `
    mutation ResetPassword($input: ResetPasswordInput!) {
      resetPassword(input: $input) {
        user {
          username
          displayName
        }
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,

  // --- Role assignment mutations ---

  ASSIGN_ROLES: `
    mutation AssignRoles($input: AssignRolesInput!) {
      assignRoles(input: $input) {
        user {
          ${USER_FULL_FIELDS}
        }
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,

  REVOKE_ROLES: `
    mutation RevokeRoles($input: RevokeRolesInput!) {
      revokeRoles(input: $input) {
        user {
          ${USER_FULL_FIELDS}
        }
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,

  // --- Role mutations ---

  CREATE_ROLE: `
    mutation CreateRole($input: CreateRoleInput!) {
      createRole(input: $input) {
        role {
          ${ROLE_FIELDS}
        }
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,

  UPDATE_ROLE: `
    mutation UpdateRole($input: UpdateRoleInput!) {
      updateRole(input: $input) {
        role {
          ${ROLE_FIELDS}
        }
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,

  DELETE_ROLE: `
    mutation DeleteRole($input: DeleteRoleInput!) {
      deleteRole(input: $input) {
        roleId
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,

  // --- Group mutations ---

  CREATE_GROUP: `
    mutation CreateGroup($input: CreateGroupInput!) {
      createGroup(input: $input) {
        group {
          ${GROUP_BASIC_FIELDS}
          parent {
            ${GROUP_BASIC_FIELDS}
          }
        }
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,

  UPDATE_GROUP: `
    mutation UpdateGroup($input: UpdateGroupInput!) {
      updateGroup(input: $input) {
        group {
          ${GROUP_BASIC_FIELDS}
        }
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,

  DELETE_GROUP: `
    mutation DeleteGroup($input: DeleteGroupInput!) {
      deleteGroup(input: $input) {
        groupId
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,

  MOVE_GROUP: `
    mutation MoveGroup($input: MoveGroupInput!) {
      moveGroup(input: $input) {
        group {
          ${GROUP_BASIC_FIELDS}
        }
        previousGroupId
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,

  // --- Group membership mutations ---

  ADD_GROUP_MEMBERS: `
    mutation AddGroupMembers($input: AddGroupMembersInput!) {
      addGroupMembers(input: $input) {
        group {
          ${GROUP_BASIC_FIELDS}
        }
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,

  REMOVE_GROUP_MEMBERS: `
    mutation RemoveGroupMembers($input: RemoveGroupMembersInput!) {
      removeGroupMembers(input: $input) {
        group {
          ${GROUP_BASIC_FIELDS}
        }
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,

  // --- Preference mutations ---

  UPDATE_PREFERENCES: `
    mutation UpdatePreferences($input: UpdatePreferencesInput!) {
      updatePreferences(input: $input) {
        category
        data
        errors {
          ${MUTATION_ERROR_FIELDS}
        }
      }
    }
  `,
} as const;
