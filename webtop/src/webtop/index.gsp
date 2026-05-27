<!DOCTYPE html>
<html>

<head>
	<meta charset="utf-8">
	<meta name="viewport" content="width=device-width, height=device-height, initial-scale=1.0, user-scalable=no">
	<link rel="icon" href=".assets/icons/favicon.ico">
	<link rel="apple-touch-icon" href="webclip.png">
	<title>Webtop</title>

	<!-- Bootstrap Icons (bundled locally; see rollup.config.js) -->
	<link rel="stylesheet" href="./assets/vendor/bootstrap-icons/bootstrap-icons.min.css?v=__BUILD_VERSION__">

	<!-- Default CSS -->
	<link href="./assets/css/style.css?v=__BUILD_VERSION__" rel="stylesheet" />
</head>

<body>
	<div id="webtop" style="display: none;" @mounted="onMounted" @unmount="onUnmount">
		<!-- Desktop -->
		<div v-if="isReady" id="desktop">
			<header id="menubar">
				<div class="icon-block position-relative">
					<div class="absolute-0 d-flex justify-content-center align-items-center c-pointer menu-on-hover" @click="toggleMenu">
						<span class="logo"><!-- logo --></span>
					</div>
				</div>
				<div class="active-app-name px-2" v-if="activeAppName">{{activeAppName}}</div>
				<div class="flex-grow-1 d-flex justify-content-center align-items-center"><!-- spacer --></div>
				<div class="mx-4 d-flex justify-content-center align-items-center">
					<span class="user-avatar" :style="{backgroundImage: avatarURL}"><!-- avatar --></span><span class="text ms-2">{{username}}</span>
				</div>
				<div class="mx-4 d-flex justify-content-center align-items-center">
					<span class="text">{{displayTime}}</span><span class="text ms-3">{{displayDate}}</span>
				</div>
			</header>
			<div id="menu-overlay" v-if="isMenuOpen" @click="toggleMenu"></div>
			<nav id="side-menu" :class="{open: isMenuOpen}">
				<div class="side-menu-apps">
					<div v-for="app in sortedApps" :key="app.identifier" class="position-relative">
						<div class="ps-3 d-flex justify-content-between align-items-stretch c-pointer menu-on-hover">
							<div class="d-flex justify-content-start align-items-center flex-grow-1 pe-1 py-2 overflow-hidden" @click="openApp(app)">
								<div class="icon-block">
									<img class="shadow-sm" :src="iconURL(app)">
								</div>
								<div class="app-name pe-1 text-truncate">{{app.title}}</div>
							</div>
							<div class="icon-block show-on-hover menu-on-hover" @click="selectApp(app)"><i class="bi bi-chevron-right fs-small"></i></div>
						</div>
					</div>
				</div>
				<div class="divider"></div>
				<div class="side-menu-footer">
					<div class="ps-3 d-flex justify-content-start align-items-center c-pointer menu-on-hover py-2" @click="showSaveSessionDialog">
						<div class="icon-block"><i class="bi bi-floppy"></i></div>
						<div class="menu-text pe-1 text-truncate">Save session and sign out</div>
					</div>
					<div class="ps-3 d-flex justify-content-start align-items-center c-pointer menu-on-hover py-2" @click="signOut">
						<div class="icon-block"><i class="bi bi-box-arrow-left"></i></div>
						<div class="menu-text pe-1 text-truncate">Sign out</div>
					</div>
				</div>
			</nav>
			<main id="desktop-area"
				@dragover="onDesktopAreaDragOver" @drop="onDesktopAreaDrop"
				@mousedown="onDesktopAreaMouseDown" @contextmenu="onDesktopAreaContextMenu">
				<wt-desktop-icons :enabled="hasDesktopFolder" :desktopPath="desktopFolderPath"
					:selectedIds="desktopSelectedIds" :dragOverItemID="desktopDragOverItemID"></wt-desktop-icons>
				<div v-if="desktopDragSelection.active" class="desktop-selection-rect" :style="desktopSelectionStyle"></div>
				<wt-window v-for="appInstance in appInstances" :key="appInstance.id" :appInstance="appInstance"></wt-window>
			</main>
			<div id="dock-overlay" v-if="openDockAppID" @click="closeDockList"></div>
			<!-- Context Menu -->
			<div id="context-menu-overlay" v-if="contextMenu.visible" @click="hideContextMenu" @contextmenu.prevent="hideContextMenu"></div>
			<div id="context-menu" v-if="contextMenu.visible" :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }">
				<div v-for="(item, index) in contextMenu.items" :key="index">
					<div v-if="item.type === 'separator'" class="context-menu-separator"></div>
					<div v-else class="context-menu-item" :class="{ danger: item.danger }" @click="onContextMenuAction(item.id)">
						<svg v-if="item.iconSvg" class="menu-icon-svg me-2"><use :href="item.iconSvg"></use></svg>
						<i v-else-if="item.icon" :class="['bi', item.icon, 'me-2']"></i>
						<span>{{ item.label }}</span>
					</div>
				</div>
			</div>

			<!-- App popup (iframe-escaping dropdown / menu) -->
			<div id="app-popup-overlay" v-if="popup.visible" tabindex="-1" ref="popupOverlay"
				@click="closePopup()" @contextmenu.prevent="closePopup()"
				@keydown.esc="closePopup()"></div>
			<div id="app-popup" v-if="popup.visible"
				:class="['placement-' + popup.placement]"
				:style="popupStyle">
				<template v-if="isPopupGrouped()">
					<div v-for="(group, gi) in popup.items" :key="'g' + gi" class="app-popup-group">
						<div class="app-popup-group-header">
							<span class="app-popup-group-label">
								<svg v-if="group.iconSvg" class="menu-icon-svg me-1"><use :href="group.iconSvg"></use></svg>
								<i v-else-if="group.icon" :class="[group.icon, 'me-1']"></i>{{ group.label }}
							</span>
							<span v-if="group.info" class="app-popup-group-info">{{ group.info }}</span>
							<button v-if="group.headerAction" type="button"
								class="app-popup-group-action"
								:class="{ danger: group.headerAction.danger }"
								:title="group.headerAction.title || group.headerAction.label || ''"
								@click.stop="onPopupGroupActionClick(group, group.headerAction)">
								<svg v-if="group.headerAction.iconSvg" class="menu-icon-svg" :class="group.headerAction.label ? 'me-1' : ''"><use :href="group.headerAction.iconSvg"></use></svg>
								<i v-else-if="group.headerAction.icon" :class="[group.headerAction.icon, group.headerAction.label ? 'me-1' : '']"></i>
								<span v-if="group.headerAction.label">{{ group.headerAction.label }}</span>
							</button>
						</div>
						<div v-if="group.items.length === 0 && group.emptyMessage" class="app-popup-group-empty">
							{{ group.emptyMessage }}
						</div>
						<div v-for="(item, i) in group.items" :key="gi + '-' + i"
							class="app-popup-item"
							:class="{ selected: item.selected, highlighted: item.highlighted, danger: item.danger, disabled: item.disabled, 'has-actions': item.actions && item.actions.length > 0 }"
							@click="onPopupItemClick(item, i)">
							<svg v-if="item.iconSvg" class="app-popup-item-icon-svg"><use :href="item.iconSvg"></use></svg>
							<i v-else-if="item.icon" :class="[item.icon, 'app-popup-item-icon']"></i>
							<div class="app-popup-item-body">
								<span class="app-popup-item-label">{{ item.label }}</span>
								<span v-if="item.description" class="app-popup-item-desc">{{ item.description }}</span>
							</div>
							<i v-if="item.selected" class="bi bi-check-lg app-popup-item-check"></i>
							<div v-if="item.actions && item.actions.length > 0" class="app-popup-item-actions">
								<button v-for="act in item.actions" :key="act.id" type="button"
									class="app-popup-item-action-btn"
									:class="{ danger: act.danger, 'show-on-hover': act.showOnHover }"
									:title="act.title || ''"
									@click.stop="onPopupItemActionClick(item, i, act)">
									<svg v-if="act.iconSvg" class="menu-icon-svg"><use :href="act.iconSvg"></use></svg>
									<i v-else :class="act.icon"></i>
								</button>
							</div>
						</div>
					</div>
				</template>
				<template v-else>
					<div v-for="(item, i) in popup.items" :key="i"
						class="app-popup-item"
						:class="{ selected: item.selected, highlighted: item.highlighted, danger: item.danger, disabled: item.disabled, 'has-actions': item.actions && item.actions.length > 0 }"
						@click="onPopupItemClick(item, i)">
						<svg v-if="item.iconSvg" class="app-popup-item-icon-svg"><use :href="item.iconSvg"></use></svg>
						<i v-else-if="item.icon" :class="[item.icon, 'app-popup-item-icon']"></i>
						<div class="app-popup-item-body">
							<span class="app-popup-item-label">{{ item.label }}</span>
							<span v-if="item.description" class="app-popup-item-desc">{{ item.description }}</span>
						</div>
						<i v-if="item.selected" class="bi bi-check-lg app-popup-item-check"></i>
						<div v-if="item.actions && item.actions.length > 0" class="app-popup-item-actions">
							<button v-for="act in item.actions" :key="act.id" type="button"
								class="app-popup-item-action-btn"
								:class="{ danger: act.danger, 'show-on-hover': act.showOnHover }"
								:title="act.title || ''"
								@click.stop="onPopupItemActionClick(item, i, act)">
								<svg v-if="act.iconSvg" class="menu-icon-svg"><use :href="act.iconSvg"></use></svg>
								<i v-else :class="act.icon"></i>
							</button>
						</div>
					</div>
				</template>
			</div>
			<!-- Session: save + name dialog -->
			<div v-if="showSaveSessionOverlay" class="session-overlay">
				<div class="dialog-frame" style="max-width: 30rem;">
					<div class="island p-2">
						<div class="dialog-header">
							<h3>Save Session</h3>
						</div>
						<div class="dialog-body">
							<div class="mb-3">Name your current session state to pick up exactly where you left off.</div>
							<label class="dialog-label">Session Name</label>
							<input v-model="sessionNameInput" type="text" class="wt w-100" placeholder="Session name" ref="sessionNameInput">
						</div>
						<div class="dialog-footer">
							<button class="wt" @click="cancelSaveSession">Cancel</button>
							<button class="wt wt-primary" @click="confirmSaveSession"><i class="bi bi-box-arrow-left me-2"></i>Save &amp; Sign Out</button>
						</div>
					</div>
				</div>
			</div>

			<!-- Session: picker (startup) -->
			<div v-if="showSessionPicker" class="session-overlay">
				<div class="dialog-frame" style="max-width: 32rem;">
					<div class="island p-2">
						<div class="dialog-header">
							<h3>Restore a Saved Session</h3>
						</div>
						<div class="dialog-body">
							<div class="mb-3">Pick up exactly where you left off or start fresh with a clean slate.</div>
							<div class="session-list">
								<div v-for="s in sessionPickerList" :key="s.id"
									class="session-item" :class="{ selected: selectedSessionID === s.id }"
									@click="selectSession(s.id)">
									<div class="text-truncate">{{s.displayName}}</div>
									<div class="small">{{formatDate(s.savedAt)}}</div>
								</div>
							</div>
						</div>
						<div class="dialog-footer">
							<button class="wt" @click="skipSessionRestore">Start with a Fresh Desktop</button>
							<span class="flex-grow-1"><!-- spacer --></span>
							<button class="wt wt-primary" :disabled="!selectedSessionID" @click="restoreSelectedSession"><i class="bi bi-clock-history me-2"></i>Restore</button>
						</div>
					</div>
				</div>
			</div>

			<!-- Session: restoring overlay -->
			<div v-if="restoringSession" class="session-overlay">
				<div class="session-restoring">Restoring session&hellip;</div>
			</div>

			<!-- Desktop upload progress dialog (uploads / paste / Content Browser drops / deletes) -->
			<div v-if="desktopUploadMonitor && !desktopConflictDialog.visible" class="session-overlay">
				<div class="dialog-frame" style="max-width: 30rem;">
					<div class="island p-2">
						<div class="dialog-body d-flex flex-column align-items-center text-center">
							<span class="loader"></span>
							<div class="mt-4 w-100 text-truncate" :title="desktopUploadMonitor.target?.currentFile">{{ desktopUploadMonitor.target?.currentFile }}</div>
							<div class="desktop-upload-progress mt-2">
								<div class="desktop-upload-progress-bar" :style="{ width: (desktopUploadMonitor.target?.progressPercent || 0) + '%' }"></div>
							</div>
							<div v-if="desktopErrorMessage" class="desktop-upload-error mt-3"><i class="bi bi-exclamation-circle me-1"></i>{{ desktopErrorMessage }}</div>
						</div>
						<div class="dialog-footer justify-content-center">
							<button v-if="desktopErrorMessage" type="button" class="wt" @click="closeDesktopUpload">Close</button>
							<button v-else type="button" class="wt" :disabled="desktopUploadMonitor.isCanceled" @click="desktopUploadMonitor.cancel()">{{ desktopUploadMonitor.isCanceled ? 'Stopping…' : 'Cancel' }}</button>
						</div>
					</div>
				</div>
			</div>

			<!-- Desktop conflict dialog (raised mid-upload when a file already exists) -->
			<div v-if="desktopConflictDialog.visible" class="session-overlay">
				<div class="dialog-frame" style="max-width: 30rem;">
					<div class="island p-2">
						<div class="dialog-header">
							<h3>Conflicting item name</h3>
						</div>
						<div class="dialog-body">
							<div class="mb-2">The following item already exists.<br>Do you want to overwrite it?</div>
							<div class="desktop-conflict-path text-break">{{ desktopUploadMonitor && desktopUploadMonitor.target.currentFile }}</div>
						</div>
						<div class="dialog-footer flex-wrap justify-content-center">
							<button class="wt" @click="onDesktopConflictAction('cancel')">Cancel</button>
							<button class="wt" @click="onDesktopConflictAction('skip')">No</button>
							<button class="wt" @click="onDesktopConflictAction('skipAll')">No to All</button>
							<button class="wt wt-primary" @click="onDesktopConflictAction('overwrite')">Yes</button>
							<button class="wt wt-primary" @click="onDesktopConflictAction('overwriteAll')">Yes to All</button>
						</div>
					</div>
				</div>
			</div>

			<!-- Desktop delete progress monitor (async path: folders / multi-select) -->
			<div v-if="desktopDeleteMonitor" class="session-overlay">
				<div class="dialog-frame" style="max-width: 30rem;">
					<div class="island p-2">
						<div class="dialog-body d-flex flex-column align-items-center text-center">
							<span v-if="desktopDeleteMonitor.status !== 'failed'" class="loader"></span>
							<i v-else class="bi bi-exclamation-circle-fill text-danger" style="font-size: 2rem;"></i>
							<div class="mt-4 w-100 text-truncate" :title="desktopDeleteMonitor.currentPath">{{ desktopDeleteMonitor.currentPath || 'Preparing…' }}</div>
							<div class="mt-2">
								<span>{{ desktopDeleteMonitor.itemsProcessed }} / {{ desktopDeleteMonitor.itemsTotal }} items</span>
								<span v-if="desktopDeleteMonitor.itemsDeleted > 0" class="ms-2 text-muted">({{ desktopDeleteMonitor.itemsDeleted }} items deleted)</span>
							</div>
							<div v-if="desktopDeleteMonitor.errorMessage" class="mt-3 text-danger">
								<i class="bi bi-exclamation-circle me-1"></i>{{ desktopDeleteMonitor.errorMessage }}
							</div>
						</div>
						<div class="dialog-footer justify-content-center">
							<button v-if="desktopDeleteMonitor.isFinished" type="button" class="wt" @click="closeDesktopDeleteMonitor">Close</button>
							<button v-else type="button" class="wt" :disabled="desktopDeleteMonitor.isAborting" @click="requestDesktopDeleteAbort">{{ desktopDeleteMonitor.isAborting ? 'Stopping…' : 'Stop' }}</button>
						</div>
					</div>
				</div>
			</div>

			<!-- Desktop rename dialog -->
			<div v-if="desktopRenameDialog.visible" class="session-overlay">
				<div class="dialog-frame" style="max-width: 30rem;">
					<div class="island p-2">
						<div class="dialog-header">
							<h3>Rename</h3>
						</div>
						<div class="dialog-body">
							<input type="text" class="wt w-100" ref="desktopRenameInput" v-model="desktopRenameDialog.newName" @keydown="onDesktopRenameKeydown" :disabled="desktopRenameDialog.isLoading" />
							<div v-if="desktopRenameDialog.errorMessage" class="text-danger mt-2">
								<i class="bi bi-exclamation-circle me-1"></i>{{ desktopRenameDialog.errorMessage }}
							</div>
						</div>
						<div class="dialog-footer">
							<button class="wt" @click="closeDesktopRenameDialog" :disabled="desktopRenameDialog.isLoading">Cancel</button>
							<button class="wt wt-primary" @click="submitDesktopRename" :disabled="desktopRenameDialog.isLoading || !desktopRenameDialog.newName.trim()">
								<span v-if="desktopRenameDialog.isLoading" class="spinner-border spinner-border-sm me-1"></span>
								Rename
							</button>
						</div>
					</div>
				</div>
			</div>

			<!-- Desktop delete confirmation dialog -->
			<div v-if="desktopDeleteDialog.visible" class="session-overlay">
				<div class="dialog-frame" style="max-width: 30rem;">
					<div class="island p-2">
						<div class="dialog-header">
							<h3><i class="bi bi-exclamation-triangle-fill text-warning me-2"></i>Delete Confirmation</h3>
						</div>
						<div class="dialog-body">
							<div class="mb-3">
								<span v-if="desktopDeleteDialog.items.length === 1">
									Are you sure you want to delete "<strong>{{ desktopDeleteDialog.items[0].name }}</strong>"?
								</span>
								<span v-else>
									Are you sure you want to delete <strong>{{ desktopDeleteDialog.items.length }}</strong> items?
								</span>
							</div>
							<div v-if="desktopDeleteDialog.items.length > 1" class="delete-items-list mb-3">
								<ul class="list-unstyled my-0">
									<li v-for="item in desktopDeleteDialog.items" :key="item.id" class="text-truncate">
										<i :class="[item.isCollection ? 'bi bi-folder-fill' : 'bi bi-file-earmark', 'me-1']"></i>{{ item.name }}
									</li>
								</ul>
							</div>
						</div>
						<div class="dialog-footer">
							<button class="wt" @click="closeDesktopDeleteDialog">Cancel</button>
							<button class="wt wt-danger" @click="submitDesktopDelete">Delete</button>
						</div>
					</div>
				</div>
			</div>

			<!-- Desktop alert dialog (e.g. missing Desktop folder) -->
			<div v-if="desktopAlert.visible" class="session-overlay">
				<div class="dialog-frame" style="max-width: 30rem;">
					<div class="island p-2">
						<div class="dialog-header">
							<h3>{{desktopAlert.title}}</h3>
						</div>
						<div class="dialog-body">
							<div class="mb-3">{{desktopAlert.message}}</div>
						</div>
						<div class="dialog-footer">
							<button class="wt wt-primary" @click="closeDesktopAlert">OK</button>
						</div>
					</div>
				</div>
			</div>

			<div id="dock" :class="{ hidden: dockHidden }">
				<div class="dock-entry" v-for="entry in dockEntries" :key="entry.app.id"
					@mouseenter="onDockEntryEnter(entry, event)" @mouseleave="onDockEntryLeave()">
					<img class="dock-icon" :src="iconURL(entry.app)" @click="dockIconClick(entry)">
					<span class="dock-count" v-if="entry.instances.length > 1">{{entry.instances.length > 9 ? '9+' : entry.instances.length}}</span>
				</div>
			</div>

			<!-- Rendered outside #dock so backdrop-filter can blur the
				 desktop/windows behind it (the dock's own transform +
				 backdrop-filter would otherwise isolate this element's
				 backdrop). Position is set inline by onDockEntryEnter. -->
			<div id="dock-preview" class="dock-preview" :class="{ open: hoveredDockEntry !== null }"
				@mouseenter="onPreviewEnter()" @mouseleave="onDockEntryLeave()">
				<div class="dock-preview-card" :class="{ minimized: minimizedWindowIDs.indexOf(inst.id) !== -1 }"
					v-for="inst in (hoveredDockEntry ? hoveredDockEntry.instances : [])" :key="inst.id"
					@mouseenter="onPreviewItemEnter(inst)"
					@click.stop="dockItemClick(inst)">
					<div class="dock-preview-icon">
						<img :src="iconURL(hoveredDockEntry.app)">
						<span class="dock-preview-minimized-badge" v-if="minimizedWindowIDs.indexOf(inst.id) !== -1" title="Minimized"></span>
					</div>
					<div class="dock-preview-text">
						<div class="dock-preview-title text-truncate">{{hoveredDockEntry.app.title}}</div>
						<div class="dock-preview-subtitle text-truncate" v-if="instanceSubtitle(inst)">{{instanceSubtitle(inst)}}</div>
					</div>
				</div>
			</div>
		</div>
	</div>

	<!-- Boot screen (displayed before the template is evaluated, placed outside of Framework) -->
	<div id="boot"><span class="loader"></span></div>

	<!-- Webtop JS (ichigo.js is bundled in webtop.js) -->
	<script type="module">
		import {Webtop} from './webtop.js?v=__BUILD_VERSION__';
		window.Webtop = new Webtop();
		window.Webtop.launch();
	</script>
</body>

</html>