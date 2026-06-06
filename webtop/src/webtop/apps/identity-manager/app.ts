/**
 * Identity Manager Application
 *
 * Management UI for IdP users, groups, and roles.
 * Two-pane layout: list pane on the left, detail pane on the right.
 * The active section (users / groups / roles) is selected from the
 * top toolbar, which also hosts New / Refresh actions and window controls.
 */

import { ApplicationInstance } from "../../services/webtop-service.js";
import { IdpServiceGraphQL } from "../../services/idp-service-graphql.js";
import {
	createLocalizationSnapshot,
	refreshLocalization,
	handleLocalizationMessage,
} from "../../composables/use-localization.js";
import type {
	IdpUser,
	IdpRole,
	IdpRoleTreeNode,
	IdpGroup,
	IdpGroupTreeNode,
} from "../../graphql/types.js";

type SectionName = 'users' | 'groups' | 'roles';
type UserSubTab = 'profile' | 'security' | 'membership';
type GroupSubTab = 'info' | 'members';

interface DialogState {
	type: string;
	data: Record<string, unknown>;
}

interface FlatGroupNode {
	groupId: string;
	name: string;
	displayName: string | null;
	hasChildren: boolean;
	depth: number;
	matched?: boolean;
}

interface FlatRoleNode {
	roleId: string;
	name: string;
	displayName: string | null;
	hasChildren: boolean;
	depth: number;
	matched?: boolean;
}

const USER_FETCH_PAGE_SIZE = 500;
const USER_FETCH_LIMIT = 1000;

export const App = {
	data() {
		return {
			instance: null as ApplicationInstance | null,
			idp: null as IdpServiceGraphQL | null,
			messageListener: null as ((event: MessageEvent) => void) | null,
			// Reactive Localization snapshot — see composables/use-localization.ts.
			localization: createLocalizationSnapshot(),

			// General state
			isLoading: false,
			errorMessage: '',
			activeSection: 'users' as SectionName,

			// Users
			userList: [] as IdpUser[],
			selectedUser: null as IdpUser | null,
			userSearch: '',
			userSubTab: 'profile' as UserSubTab,
			editForm: {
				sn: '',
				givenName: '',
				displayName: '',
				mail: '',
				enabled: true,
			},
			passwordForm: {
				newPassword: '',
			},
			pendingRoleChanges: {} as Record<string, boolean>,

			// Groups
			groupTree: [] as IdpGroupTreeNode[],
			flatGroupTree: [] as FlatGroupNode[],
			filteredGroupTree: [] as FlatGroupNode[],
			expandedGroups: {} as Record<string, boolean>,
			selectedGroup: null as IdpGroup | null,
			groupSearch: '',
			groupSubTab: 'info' as GroupSubTab,
			groupEditForm: {
				displayName: '',
				description: '',
			},
			groupMembers: [] as IdpUser[],
			memberSearchQuery: '',
			memberCandidates: [] as (IdpUser & { alreadyMember?: boolean; staged?: boolean })[],
			memberHighlightIndex: -1,
			stagedMembers: [] as IdpUser[],
			memberSearchTimer: null as ReturnType<typeof setTimeout> | null,

			// Roles
			roleTree: [] as IdpRoleTreeNode[],
			flatRoleTree: [] as FlatRoleNode[],
			filteredRoleTree: [] as FlatRoleNode[],
			allRolesFlat: [] as FlatRoleNode[],
			expandedRoles: {} as Record<string, boolean>,
			selectedRole: null as IdpRole | null,
			roleSearch: '',
			roleEditForm: {
				displayName: '',
				description: '',
			},

			// Sidebar
			sidebarPanelVisible: true,
			sidebarPanelWidth: 280,
			_sidebarResizeMoveHandler: null as ((e: MouseEvent) => void) | null,
			_sidebarResizeUpHandler: null as ((e: MouseEvent) => void) | null,

			// Dialog
			dialog: {
				type: '',
				data: {} as Record<string, unknown>,
			} as DialogState,
		};
	},

	computed: {
		// Placeholder text for the search input in the left pane
		searchPlaceholder(): string {
			if (this.activeSection === 'users') return 'Search users...';
			if (this.activeSection === 'groups') return 'Search groups...';
			return 'Search roles...';
		},

		// Tooltip for the [+] (New) button
		newActionTitle(): string {
			if (this.activeSection === 'users') return 'New User';
			if (this.activeSection === 'groups') return 'New Group';
			return 'New Role';
		},

		// Two-way binding for the search input — routed to the active section
		currentSearchQuery: {
			get(): string {
				if (this.activeSection === 'users') return this.userSearch;
				if (this.activeSection === 'groups') return this.groupSearch;
				return this.roleSearch;
			},
			set(v: string) {
				if (this.activeSection === 'users') this.userSearch = v;
				else if (this.activeSection === 'groups') this.groupSearch = v;
				else this.roleSearch = v;
			},
		},

		// Label shown inside the create-group "Parent Group" select trigger
		parentGroupLabel(): string {
			const id = this.dialog?.data?.parentGroupId as string | undefined;
			if (!id) return '(Root level)';
			const found = (this.flatGroupTree as FlatGroupNode[]).find((g) => g.groupId === id);
			return found ? (found.displayName || found.name) : id;
		},

		// Label shown inside the create-role "Parent Role" select trigger
		parentRoleLabel(): string {
			const id = this.dialog?.data?.parentRoleId as string | undefined;
			if (!id) return '(Root level)';
			const found = (this.allRolesFlat as FlatRoleNode[]).find((r) => r.roleId === id);
			return found ? (found.displayName || found.name) : id;
		},

		// Client-side filtered user list
		displayedUsers(): IdpUser[] {
			const q = this.userSearch.trim().toLowerCase();
			if (!q) return this.userList;
			return (this.userList as IdpUser[]).filter((u) =>
				u.username.toLowerCase().includes(q) ||
				(u.displayName && u.displayName.toLowerCase().includes(q)) ||
				(u.mail && u.mail.toLowerCase().includes(q)) ||
				(u.sn && u.sn.toLowerCase().includes(q)) ||
				(u.givenName && u.givenName.toLowerCase().includes(q))
			);
		},

		// Status bar text for the active section
		listStatusText(): string {
			if (this.activeSection === 'users') {
				const total = this.userList.length;
				if (this.userSearch.trim()) {
					return `${this.displayedUsers.length} of ${total} user(s)`;
				}
				return `${total} user(s)`;
			}
			if (this.activeSection === 'groups') {
				const total = this.flatGroupTree.length;
				if (this.groupSearch.trim()) {
					const matched = (this.filteredGroupTree as FlatGroupNode[])
						.filter((n) => n.matched).length;
					return `${matched} of ${total} group(s)`;
				}
				return `${total} group(s)`;
			}
			const total = this.flatRoleTree.length;
			if (this.roleSearch.trim()) {
				const matched = (this.filteredRoleTree as FlatRoleNode[])
					.filter((n) => n.matched).length;
				return `${matched} of ${total} role(s)`;
			}
			return `${total} role(s)`;
		},

		// True when the selected user is a built-in principal that cannot be deleted
		// (currently the "anonymous" user).
		isProtectedSelectedUser(): boolean {
			if (!this.selectedUser) return false;
			return this.selectedUser.username === 'anonymous';
		},

		// True when the selected group is a built-in principal that cannot be deleted
		// (currently the "everyone" group).
		isProtectedSelectedGroup(): boolean {
			if (!this.selectedGroup) return false;
			return this.selectedGroup.groupId === 'everyone';
		},
	},

	methods: {
		// =====================================================================
		// Lifecycle
		// =====================================================================

		async onMounted() {
			const vm = this;

			vm.messageListener = (event: MessageEvent) => {
				if (event.origin !== window.location.origin) return;
				const { type, ...payload } = event.data || {};
				if (type === 'theme-changed') {
					document.documentElement.dataset.theme = payload.theme;
				}
				if (handleLocalizationMessage(type, vm.localization, vm.instance)) return;
			};
			window.addEventListener('message', vm.messageListener);

			window.appLaunch = async (instance: ApplicationInstance) => {
				vm.instance = vm.$markRaw(instance);
				vm.idp = vm.$markRaw(new IdpServiceGraphQL());

				const theme = vm.instance.api.theme.currentTheme || 'light';
				document.documentElement.dataset.theme = theme;
				vm.instance.windowTitle = 'Identity Manager';

				refreshLocalization(vm.localization, vm.instance);

				await vm.loadInitialData();

				vm.$nextTick(() => {
					instance.notifyLaunched();
				});
			};
		},

		async onUnmount() {
			const vm = this;
			if (vm.messageListener) {
				window.removeEventListener('message', vm.messageListener);
				vm.messageListener = null;
			}
			if (vm._sidebarResizeUpHandler) {
				document.removeEventListener('mouseup', vm._sidebarResizeUpHandler);
			}
			if (vm._sidebarResizeMoveHandler) {
				document.removeEventListener('mousemove', vm._sidebarResizeMoveHandler);
			}
		},

		async loadInitialData() {
			try {
				this.isLoading = true;
				await Promise.all([
					this.loadUsers(),
					this.loadRoleTree(),
					this.loadGroupTree(),
				]);
			} catch (err) {
				this.errorMessage = err instanceof Error ? err.message : String(err);
			} finally {
				this.isLoading = false;
			}
		},

		async refresh() {
			try {
				this.errorMessage = '';
				this.isLoading = true;
				if (this.activeSection === 'users') {
					await Promise.all([this.loadUsers(), this.loadRoleTree()]);
				} else if (this.activeSection === 'groups') {
					await this.loadGroupTree();
				} else if (this.activeSection === 'roles') {
					await this.loadRoleTree();
				}
			} catch (err) {
				this.errorMessage = err instanceof Error ? err.message : String(err);
			} finally {
				this.isLoading = false;
			}
		},

		// =====================================================================
		// Window controls
		// =====================================================================

		onMinimizeWindow() {
			this.instance?.minimize();
		},
		onToggleMaximizeWindow() {
			this.instance?.toggleMaximize();
		},
		onCloseWindow() {
			this.instance?.requestClose();
		},

		toggleSidebarPanel() {
			this.sidebarPanelVisible = !this.sidebarPanelVisible;
		},

		// Sidebar resize (drag the splitter between list and detail panes)
		onSidebarResizeStart(e: MouseEvent) {
			e.preventDefault();
			const vm = this;
			const startX = e.clientX;
			const startWidth = vm.sidebarPanelWidth;

			vm._sidebarResizeMoveHandler = (moveEvent: MouseEvent) => {
				const delta = moveEvent.clientX - startX;
				vm.sidebarPanelWidth = Math.max(180, Math.min(600, startWidth + delta));
			};

			vm._sidebarResizeUpHandler = () => {
				document.removeEventListener('mousemove', vm._sidebarResizeMoveHandler!);
				document.removeEventListener('mouseup', vm._sidebarResizeUpHandler!);
				vm._sidebarResizeMoveHandler = null;
				vm._sidebarResizeUpHandler = null;
			};

			document.addEventListener('mousemove', vm._sidebarResizeMoveHandler);
			document.addEventListener('mouseup', vm._sidebarResizeUpHandler);
		},

		// =====================================================================
		// Section switching / search
		// =====================================================================

		async switchSection(section: SectionName) {
			this.activeSection = section;
			this.errorMessage = '';
			if (section === 'users' && this.userList.length === 0) {
				await this.loadUsers();
			} else if (section === 'groups' && this.flatGroupTree.length === 0) {
				await this.loadGroupTree();
			} else if (section === 'roles' && this.flatRoleTree.length === 0) {
				await this.loadRoleTree();
			}
		},

		onSearchInput() {
			if (this.activeSection === 'groups') {
				this.applyGroupFilter();
			} else if (this.activeSection === 'roles') {
				this.applyRoleFilter();
			}
			// Users: filtered reactively via the displayedUsers computed.
		},

		clearSearch() {
			this.currentSearchQuery = '';
			this.onSearchInput();
		},

		clearMemberSearch() {
			this.memberSearchQuery = '';
			this.memberCandidates = [];
			this.memberHighlightIndex = -1;
		},

		// =====================================================================
		// User operations
		// =====================================================================

		async loadUsers() {
			try {
				const all: IdpUser[] = [];
				let after: string | undefined = undefined;
				while (all.length < USER_FETCH_LIMIT) {
					const remaining = USER_FETCH_LIMIT - all.length;
					const conn = await this.idp!.listUsers({
						first: Math.min(USER_FETCH_PAGE_SIZE, remaining),
						after,
					});
					for (const e of conn.edges as { node: IdpUser }[]) all.push(e.node);
					if (!conn.pageInfo.hasNextPage || !conn.pageInfo.endCursor) break;
					after = conn.pageInfo.endCursor;
				}
				this.userList = all;
			} catch (err) {
				this.errorMessage = 'Failed to load users: ' + (err instanceof Error ? err.message : String(err));
			}
		},

		async selectUser(user: IdpUser) {
			try {
				const fullUser = await this.idp!.getUser(user.username);
				if (!fullUser) return;
				this.selectedUser = fullUser;
				this.userSubTab = 'profile';
				this.editForm = {
					sn: fullUser.sn || '',
					givenName: fullUser.givenName || '',
					displayName: fullUser.displayName || '',
					mail: fullUser.mail || '',
					enabled: fullUser.enabled,
				};
				this.passwordForm.newPassword = '';
				this.pendingRoleChanges = {};
			} catch (err) {
				this.errorMessage = err instanceof Error ? err.message : String(err);
			}
		},

		userHasRole(roleId: string): boolean {
			if (!this.selectedUser) return false;
			if (roleId in this.pendingRoleChanges) return this.pendingRoleChanges[roleId];
			return this.selectedUser.roles.some((r: IdpRole) => r.roleId === roleId);
		},

		toggleUserRole(roleId: string, event: Event) {
			const checked = (event.target as HTMLInputElement).checked;
			this.pendingRoleChanges[roleId] = checked;
		},

		async saveUser() {
			if (!this.selectedUser) return;
			try {
				this.errorMessage = '';
				const username = this.selectedUser.username;

				const result = await this.idp!.updateUser({
					username,
					sn: this.editForm.sn || undefined,
					givenName: this.editForm.givenName || undefined,
					displayName: this.editForm.displayName || undefined,
					mail: this.editForm.mail || undefined,
					enabled: this.editForm.enabled,
				});
				if (result.errors) {
					this.errorMessage = result.errors.map((e: { message: string }) => e.message).join(', ');
					return;
				}

				const rolesToAssign: string[] = [];
				const rolesToRevoke: string[] = [];
				for (const [roleId, checked] of Object.entries(this.pendingRoleChanges)) {
					const hasNow = this.selectedUser.roles.some((r: IdpRole) => r.roleId === roleId);
					if (checked && !hasNow) rolesToAssign.push(roleId);
					if (!checked && hasNow) rolesToRevoke.push(roleId);
				}
				if (rolesToAssign.length > 0) {
					await this.idp!.assignRoles({ username, roles: rolesToAssign });
				}
				if (rolesToRevoke.length > 0) {
					await this.idp!.revokeRoles({ username, roles: rolesToRevoke });
				}

				await this.selectUser({ username } as IdpUser);
				await this.loadUsers();
			} catch (err) {
				this.errorMessage = err instanceof Error ? err.message : String(err);
			}
		},

		async resetUserPassword() {
			if (!this.selectedUser || !this.passwordForm.newPassword) return;
			try {
				this.errorMessage = '';
				const result = await this.idp!.resetPassword({
					username: this.selectedUser.username,
					newPassword: this.passwordForm.newPassword,
				});
				if (result.errors) {
					this.errorMessage = result.errors.map((e: { message: string }) => e.message).join(', ');
					return;
				}
				this.passwordForm.newPassword = '';
			} catch (err) {
				this.errorMessage = err instanceof Error ? err.message : String(err);
			}
		},

		async removeUserFromGroup(groupId: string) {
			if (!this.selectedUser) return;
			try {
				this.errorMessage = '';
				await this.idp!.removeGroupMembers({
					groupId,
					usernames: [this.selectedUser.username],
				});
				await this.selectUser({ username: this.selectedUser.username } as IdpUser);
			} catch (err) {
				this.errorMessage = err instanceof Error ? err.message : String(err);
			}
		},

		confirmDeleteUser() {
			if (!this.selectedUser) return;
			this.dialog = {
				type: 'confirmDelete',
				data: {
					message: `Delete user "${this.selectedUser.username}"? This action cannot be undone.`,
					target: 'user',
					id: this.selectedUser.username,
				},
			};
		},

		// =====================================================================
		// Group operations
		// =====================================================================

		async loadGroupTree() {
			try {
				this.groupTree = await this.idp!.getGroupTree({ maxDepth: 20 });
				this.rebuildFlatGroupTree();
			} catch (err) {
				this.errorMessage = 'Failed to load groups: ' + (err instanceof Error ? err.message : String(err));
			}
		},

		rebuildFlatGroupTree() {
			// flatGroupTree always contains every node (regardless of expansion or
			// search), so it can serve as the "total" count and back the parent
			// picker dropdown.
			const flat: FlatGroupNode[] = [];
			const walkAll = (nodes: IdpGroupTreeNode[]) => {
				for (const node of nodes) {
					flat.push({
						groupId: node.groupId,
						name: node.name,
						displayName: node.displayName,
						hasChildren: node.hasChildren,
						depth: node.depth,
					});
					if (node.children) walkAll(node.children);
				}
			};
			walkAll(this.groupTree);
			this.flatGroupTree = flat;
			this.applyGroupFilter();
		},

		applyGroupFilter() {
			const q = this.groupSearch.trim().toLowerCase();

			if (!q) {
				// No search: respect manual expansion state.
				const result: FlatGroupNode[] = [];
				const walk = (nodes: IdpGroupTreeNode[]) => {
					for (const node of nodes) {
						result.push({
							groupId: node.groupId,
							name: node.name,
							displayName: node.displayName,
							hasChildren: node.hasChildren,
							depth: node.depth,
							matched: false,
						});
						if (this.expandedGroups[node.groupId] && node.children) {
							walk(node.children);
						}
					}
				};
				walk(this.groupTree);
				this.filteredGroupTree = result;
				return;
			}

			// Search mode: include matching nodes plus their ancestors. Children
			// of matches are hidden. Branches containing a match are auto-expanded.
			const matches = new Set<string>();
			const visible = new Set<string>();
			const walkCollect = (nodes: IdpGroupTreeNode[], ancestors: string[]) => {
				for (const node of nodes) {
					const hit =
						node.name.toLowerCase().includes(q) ||
						(node.displayName !== null && node.displayName.toLowerCase().includes(q)) ||
						node.groupId.toLowerCase().includes(q);
					if (hit) {
						matches.add(node.groupId);
						visible.add(node.groupId);
						for (const a of ancestors) visible.add(a);
					}
					if (node.children) {
						ancestors.push(node.groupId);
						walkCollect(node.children, ancestors);
						ancestors.pop();
					}
				}
			};
			walkCollect(this.groupTree, []);

			const result: FlatGroupNode[] = [];
			const walkVisible = (nodes: IdpGroupTreeNode[]) => {
				for (const node of nodes) {
					if (!visible.has(node.groupId)) continue;
					const isMatch = matches.has(node.groupId);
					result.push({
						groupId: node.groupId,
						name: node.name,
						displayName: node.displayName,
						hasChildren: node.hasChildren,
						depth: node.depth,
						matched: isMatch,
					});
					// Hide children of matched nodes; descend only through ancestors.
					if (!isMatch && node.children) walkVisible(node.children);
				}
			};
			walkVisible(this.groupTree);
			this.filteredGroupTree = result;
		},

		toggleGroupExpand(node: FlatGroupNode) {
			if (!node.hasChildren) return;
			// Manual expansion only applies when not searching.
			if (this.groupSearch.trim()) return;
			this.expandedGroups[node.groupId] = !this.expandedGroups[node.groupId];
			this.applyGroupFilter();
		},

		async selectGroup(node: FlatGroupNode) {
			try {
				const group = await this.idp!.getGroup(node.groupId);
				if (!group) return;
				this.selectedGroup = group;
				this.groupSubTab = 'info';
				this.groupEditForm = {
					displayName: group.displayName || '',
					description: group.description || '',
				};
				this.groupMembers = [];
				this.memberSearchQuery = '';
				this.memberCandidates = [];
				this.memberHighlightIndex = -1;
				this.stagedMembers = [];
				await this.loadGroupMembers();
			} catch (err) {
				this.errorMessage = err instanceof Error ? err.message : String(err);
			}
		},

		async loadGroupMembers() {
			if (!this.selectedGroup) return;
			try {
				const conn = await this.idp!.listUsers({
					first: 500,
					groupId: this.selectedGroup.groupId,
				});
				this.groupMembers = conn.edges.map((e: { node: IdpUser }) => e.node);
			} catch {
				this.groupMembers = [];
			}
		},

		async saveGroup() {
			if (!this.selectedGroup) return;
			try {
				this.errorMessage = '';
				const result = await this.idp!.updateGroup({
					groupId: this.selectedGroup.groupId,
					displayName: this.groupEditForm.displayName || undefined,
					description: this.groupEditForm.description || undefined,
				});
				if (result.errors) {
					this.errorMessage = result.errors.map((e: { message: string }) => e.message).join(', ');
					return;
				}
				await this.loadGroupTree();
				if (result.group) {
					this.selectedGroup = result.group;
				}
			} catch (err) {
				this.errorMessage = err instanceof Error ? err.message : String(err);
			}
		},

		searchMemberCandidates() {
			if (this.memberSearchTimer) {
				clearTimeout(this.memberSearchTimer);
			}
			const query = this.memberSearchQuery.trim();
			if (!query || query.length < 1) {
				this.memberCandidates = [];
				this.memberHighlightIndex = -1;
				return;
			}
			this.memberSearchTimer = setTimeout(async () => {
				try {
					const conn = await this.idp!.listUsers({
						first: 20,
						query,
					});
					const candidates = conn.edges.map((e: { node: IdpUser }) => e.node);
					const memberUsernames = new Set(this.groupMembers.map((m: IdpUser) => m.username));
					const stagedUsernames = new Set(this.stagedMembers.map((s: IdpUser) => s.username));
					this.memberCandidates = candidates.map((u: IdpUser) => ({
						...u,
						alreadyMember: memberUsernames.has(u.username),
						staged: stagedUsernames.has(u.username),
					}));
					this.memberHighlightIndex = -1;
				} catch {
					this.memberCandidates = [];
				}
			}, 200);
		},

		handleMemberSearchKeydown(event: KeyboardEvent) {
			const len = this.memberCandidates.length;
			if (len === 0) return;

			if (event.key === 'ArrowDown') {
				event.preventDefault();
				this.memberHighlightIndex = (this.memberHighlightIndex + 1) % len;
			} else if (event.key === 'ArrowUp') {
				event.preventDefault();
				this.memberHighlightIndex = (this.memberHighlightIndex - 1 + len) % len;
			} else if (event.key === 'Enter') {
				event.preventDefault();
				if (this.memberHighlightIndex >= 0 && this.memberHighlightIndex < len) {
					this.stageCandidate(this.memberCandidates[this.memberHighlightIndex]);
				}
			} else if (event.key === 'Escape') {
				this.memberCandidates = [];
				this.memberHighlightIndex = -1;
			}
		},

		stageCandidate(candidate: IdpUser & { alreadyMember?: boolean; staged?: boolean }) {
			if (candidate.alreadyMember || candidate.staged) return;
			this.stagedMembers.push({
				username: candidate.username,
				displayName: candidate.displayName,
				mail: candidate.mail,
			} as IdpUser);
			const idx = this.memberCandidates.findIndex(
				(c: IdpUser) => c.username === candidate.username
			);
			if (idx >= 0) {
				this.memberCandidates[idx] = { ...this.memberCandidates[idx], staged: true };
			}
			this.memberSearchQuery = '';
			this.memberCandidates = [];
			this.memberHighlightIndex = -1;
		},

		unstageCandidate(user: IdpUser) {
			this.stagedMembers = this.stagedMembers.filter(
				(s: IdpUser) => s.username !== user.username
			);
		},

		async addStagedMembers() {
			if (!this.selectedGroup || this.stagedMembers.length === 0) return;
			try {
				this.errorMessage = '';
				const usernames = this.stagedMembers.map((s: IdpUser) => s.username);
				const result = await this.idp!.addGroupMembers({
					groupId: this.selectedGroup.groupId,
					usernames,
				});
				if (result.errors) {
					this.errorMessage = result.errors.map((e: { message: string }) => e.message).join(', ');
					return;
				}
				this.stagedMembers = [];
				this.memberSearchQuery = '';
				this.memberCandidates = [];
				await this.loadGroupMembers();
			} catch (err) {
				this.errorMessage = err instanceof Error ? err.message : String(err);
			}
		},

		async removeGroupMember(username: string) {
			if (!this.selectedGroup) return;
			try {
				this.errorMessage = '';
				await this.idp!.removeGroupMembers({
					groupId: this.selectedGroup.groupId,
					usernames: [username],
				});
				await this.loadGroupMembers();
			} catch (err) {
				this.errorMessage = err instanceof Error ? err.message : String(err);
			}
		},

		confirmDeleteGroup() {
			if (!this.selectedGroup) return;
			this.dialog = {
				type: 'confirmDelete',
				data: {
					message: `Delete group "${this.selectedGroup.groupId}"? This will also delete all child groups.`,
					target: 'group',
					id: this.selectedGroup.groupId,
				},
			};
		},

		// =====================================================================
		// Role operations
		// =====================================================================

		async loadRoleTree() {
			try {
				this.roleTree = await this.idp!.getRoleTree({ maxDepth: 20 });
				this.rebuildFlatRoleTree();
			} catch (err) {
				this.errorMessage = 'Failed to load roles: ' + (err instanceof Error ? err.message : String(err));
			}
		},

		rebuildFlatRoleTree() {
			// flatRoleTree / allRolesFlat hold every node (regardless of expansion
			// or search). They drive the total count, the role checklist on the
			// user Security tab, and the parent role picker.
			const allFlat: FlatRoleNode[] = [];
			const walkAll = (nodes: IdpRoleTreeNode[]) => {
				for (const node of nodes) {
					allFlat.push({
						roleId: node.roleId,
						name: node.name,
						displayName: node.displayName,
						hasChildren: node.hasChildren,
						depth: node.depth,
					});
					if (node.children) walkAll(node.children);
				}
			};
			walkAll(this.roleTree);
			this.allRolesFlat = allFlat;
			this.flatRoleTree = allFlat.slice();
			this.applyRoleFilter();
		},

		applyRoleFilter() {
			const q = this.roleSearch.trim().toLowerCase();

			if (!q) {
				const result: FlatRoleNode[] = [];
				const walk = (nodes: IdpRoleTreeNode[]) => {
					for (const node of nodes) {
						result.push({
							roleId: node.roleId,
							name: node.name,
							displayName: node.displayName,
							hasChildren: node.hasChildren,
							depth: node.depth,
							matched: false,
						});
						if (this.expandedRoles[node.roleId] && node.children) {
							walk(node.children);
						}
					}
				};
				walk(this.roleTree);
				this.filteredRoleTree = result;
				return;
			}

			const matches = new Set<string>();
			const visible = new Set<string>();
			const walkCollect = (nodes: IdpRoleTreeNode[], ancestors: string[]) => {
				for (const node of nodes) {
					const hit =
						node.name.toLowerCase().includes(q) ||
						(node.displayName !== null && node.displayName.toLowerCase().includes(q)) ||
						node.roleId.toLowerCase().includes(q);
					if (hit) {
						matches.add(node.roleId);
						visible.add(node.roleId);
						for (const a of ancestors) visible.add(a);
					}
					if (node.children) {
						ancestors.push(node.roleId);
						walkCollect(node.children, ancestors);
						ancestors.pop();
					}
				}
			};
			walkCollect(this.roleTree, []);

			const result: FlatRoleNode[] = [];
			const walkVisible = (nodes: IdpRoleTreeNode[]) => {
				for (const node of nodes) {
					if (!visible.has(node.roleId)) continue;
					const isMatch = matches.has(node.roleId);
					result.push({
						roleId: node.roleId,
						name: node.name,
						displayName: node.displayName,
						hasChildren: node.hasChildren,
						depth: node.depth,
						matched: isMatch,
					});
					if (!isMatch && node.children) walkVisible(node.children);
				}
			};
			walkVisible(this.roleTree);
			this.filteredRoleTree = result;
		},

		toggleRoleExpand(node: FlatRoleNode) {
			if (!node.hasChildren) return;
			if (this.roleSearch.trim()) return;
			this.expandedRoles[node.roleId] = !this.expandedRoles[node.roleId];
			this.applyRoleFilter();
		},

		async selectRole(node: FlatRoleNode) {
			try {
				const fullRole = await this.idp!.getRole(node.roleId);
				if (!fullRole) return;
				this.selectedRole = fullRole;
				this.roleEditForm = {
					displayName: fullRole.displayName || '',
					description: fullRole.description || '',
				};
			} catch (err) {
				this.errorMessage = err instanceof Error ? err.message : String(err);
			}
		},

		async saveRole() {
			if (!this.selectedRole) return;
			try {
				this.errorMessage = '';
				const result = await this.idp!.updateRole({
					roleId: this.selectedRole.roleId,
					displayName: this.roleEditForm.displayName || undefined,
					description: this.roleEditForm.description || undefined,
				});
				if (result.errors) {
					this.errorMessage = result.errors.map((e: { message: string }) => e.message).join(', ');
					return;
				}
				await this.loadRoleTree();
				if (result.role) {
					this.selectedRole = result.role;
				}
			} catch (err) {
				this.errorMessage = err instanceof Error ? err.message : String(err);
			}
		},

		confirmDeleteRole() {
			if (!this.selectedRole) return;
			this.dialog = {
				type: 'confirmDelete',
				data: {
					message: `Delete role "${this.selectedRole.roleId}"? This will also delete all child roles and remove it from all users.`,
					target: 'role',
					id: this.selectedRole.roleId,
				},
			};
		},

		// =====================================================================
		// Create dialogs (with shell-rendered popups for parent pickers)
		// =====================================================================

		showCreateDialog() {
			if (this.activeSection === 'users') {
				this.dialog = {
					type: 'createUser',
					data: { username: '', password: '', displayName: '', mail: '', service: false },
				};
			} else if (this.activeSection === 'groups') {
				this.dialog = {
					type: 'createGroup',
					data: {
						name: '',
						parentGroupId: this.selectedGroup ? this.selectedGroup.groupId : '',
						displayName: '',
					},
				};
			} else if (this.activeSection === 'roles') {
				this.dialog = {
					type: 'createRole',
					data: {
						name: '',
						parentRoleId: this.selectedRole ? this.selectedRole.roleId : '',
						displayName: '',
						description: '',
					},
				};
			}
		},

		closeDialog() {
			this.dialog = { type: '', data: {} };
		},

		// Shell-rendered dropdown for the create-group "Parent Group" picker
		async openParentGroupDropdown(event: MouseEvent) {
			const vm = this;
			if (!vm.instance) return;
			const trigger = event.currentTarget as HTMLElement;
			const rect = trigger.getBoundingClientRect();
			const currentId = (vm.dialog.data.parentGroupId as string) || '';
			const items: any[] = [
				{ id: '__root__', label: '(Root level)', selected: !currentId },
			];
			for (const g of vm.flatGroupTree as FlatGroupNode[]) {
				items.push({
					id: g.groupId,
					label: '  '.repeat(g.depth) + (g.displayName || g.name),
					selected: currentId === g.groupId,
				});
			}
			const handle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			vm.dialog.data.parentGroupId = result === '__root__' ? '' : String(result);
		},

		// Shell-rendered dropdown for the create-role "Parent Role" picker
		async openParentRoleDropdown(event: MouseEvent) {
			const vm = this;
			if (!vm.instance) return;
			const trigger = event.currentTarget as HTMLElement;
			const rect = trigger.getBoundingClientRect();
			const currentId = (vm.dialog.data.parentRoleId as string) || '';
			const items: any[] = [
				{ id: '__root__', label: '(Root level)', selected: !currentId },
			];
			for (const r of vm.allRolesFlat as FlatRoleNode[]) {
				items.push({
					id: r.roleId,
					label: '  '.repeat(r.depth) + (r.displayName || r.name),
					selected: currentId === r.roleId,
				});
			}
			const handle = vm.instance.popup.open({
				anchor: rect,
				placement: 'bottom-start',
				minWidth: rect.width,
				items,
			});
			const result = await handle.result;
			if (result == null) return;
			vm.dialog.data.parentRoleId = result === '__root__' ? '' : String(result);
		},

		async createUser() {
			try {
				this.errorMessage = '';
				const d = this.dialog.data;
				const service = !!d.service;
				const result = await this.idp!.createUser({
					username: d.username as string,
					// Service accounts have no password and can never sign in.
					password: service ? undefined : (d.password as string),
					displayName: (d.displayName as string) || undefined,
					mail: service ? undefined : (d.mail as string) || undefined,
					service: service || undefined,
				});
				if (result.errors) {
					this.errorMessage = result.errors.map((e: { message: string }) => e.message).join(', ');
					return;
				}
				this.closeDialog();
				await this.loadUsers();
				if (result.user) {
					await this.selectUser(result.user);
				}
			} catch (err) {
				this.errorMessage = err instanceof Error ? err.message : String(err);
			}
		},

		async createGroup() {
			try {
				this.errorMessage = '';
				const d = this.dialog.data;
				const result = await this.idp!.createGroup({
					name: d.name as string,
					parentGroupId: (d.parentGroupId as string) || undefined,
					displayName: (d.displayName as string) || undefined,
				});
				if (result.errors) {
					this.errorMessage = result.errors.map((e: { message: string }) => e.message).join(', ');
					return;
				}
				this.closeDialog();
				if (d.parentGroupId) {
					this.expandedGroups[d.parentGroupId as string] = true;
				}
				await this.loadGroupTree();
				if (result.group) {
					await this.selectGroup({
						groupId: result.group.groupId,
						name: result.group.name,
						displayName: result.group.displayName,
						hasChildren: false,
						depth: result.group.depth,
					});
				}
			} catch (err) {
				this.errorMessage = err instanceof Error ? err.message : String(err);
			}
		},

		async createRole() {
			try {
				this.errorMessage = '';
				const d = this.dialog.data;
				const result = await this.idp!.createRole({
					name: d.name as string,
					parentRoleId: (d.parentRoleId as string) || undefined,
					displayName: (d.displayName as string) || undefined,
					description: (d.description as string) || undefined,
				});
				if (result.errors) {
					this.errorMessage = result.errors.map((e: { message: string }) => e.message).join(', ');
					return;
				}
				this.closeDialog();
				if (d.parentRoleId) {
					this.expandedRoles[d.parentRoleId as string] = true;
				}
				await this.loadRoleTree();
				if (result.role) {
					await this.selectRole({
						roleId: result.role.roleId,
						name: result.role.name,
						displayName: result.role.displayName,
						hasChildren: false,
						depth: result.role.depth,
					});
				}
			} catch (err) {
				this.errorMessage = err instanceof Error ? err.message : String(err);
			}
		},

		// =====================================================================
		// Delete execution
		// =====================================================================

		async executeDelete() {
			const { target, id } = this.dialog.data;
			this.closeDialog();

			try {
				this.errorMessage = '';
				if (target === 'user') {
					const result = await this.idp!.deleteUser({ username: id as string });
					if (result.errors) {
						this.errorMessage = result.errors.map((e: { message: string }) => e.message).join(', ');
						return;
					}
					this.selectedUser = null;
					await this.loadUsers();
				} else if (target === 'group') {
					const result = await this.idp!.deleteGroup({ groupId: id as string, recursive: true });
					if (result.errors) {
						this.errorMessage = result.errors.map((e: { message: string }) => e.message).join(', ');
						return;
					}
					this.selectedGroup = null;
					await this.loadGroupTree();
				} else if (target === 'role') {
					const result = await this.idp!.deleteRole({ roleId: id as string, removeFromUsers: true, recursive: true });
					if (result.errors) {
						this.errorMessage = result.errors.map((e: { message: string }) => e.message).join(', ');
						return;
					}
					this.selectedRole = null;
					await this.loadRoleTree();
				}
			} catch (err) {
				this.errorMessage = err instanceof Error ? err.message : String(err);
			}
		},

		// =====================================================================
		// Utilities
		// =====================================================================

		formatDate(isoString: string | null): string {
			if (!isoString) return '';
			const dates = this.instance?.util?.dates;
			if (!dates) {
				try { return new Date(isoString).toLocaleString(); } catch { return isoString; }
			}
			return dates.format(isoString, {
				format: 'datetime',
				locale: this.localization.locale || undefined,
				timeZone: this.localization.timeZone || undefined,
			}) ?? isoString;
		},
	},
};

// Mount the app
import { VDOM } from '@mintjamsinc/ichigojs';
VDOM.createApp(App).mount('#app');
