# Cloud storage in the Content Browser

The Content Browser opens the user's Google Drive and OneDrive beside the
repository's folders: the same list, the same breadcrumbs, favourites,
history and back / forward, the same Inspector. Nothing is synchronized or
mounted. A listing is read from the provider when the user looks at it and
again after what the user does to it; a copy between a drive and the
repository is an action of the user and produces an ordinary file.

What a user needs first is the *files* bundle of a linked account
(Preferences › Linked accounts; see `linked-accounts.md`). An administrator
enables the providers there.

## Where things are

The sidebar has a **Cloud storage** section listing the providers whose files
bundle the user allowed. A provider the administrator enabled but the user has
not allowed yet is not listed; a line below the section says so and opens
Preferences. The section is absent when no provider is enabled.

Clicking a provider shows its **drives**:

| Google | Microsoft | What it is |
| --- | --- | --- |
| My Drive | OneDrive | The user's own drive |
| Shared drives | Shared libraries | The shared drives the user is a member of; the document libraries of the SharePoint sites the user follows |

A shared drive or library looks the same to every member and is named by the
same ids for everyone, so a location in one can be passed from one person to
another; a location in someone's own drive cannot. A library is named "Site ›
Library", or by the site alone for its default library.

Below a drive, folders open as in the repository: a double-click goes down,
the breadcrumbs go up ("Google Drive › My Drive › 2026 › Invoices"), Back and
Forward and the Up key work, and the Dock preview shows the same names. The
path bar cannot be typed into here: a cloud location is made of the provider's
ids, which only the names stand for. Copying the path copies the names.

A folder's listing arrives a page at a time (200 entries) and the next page
loads as the list is scrolled. The list is ordered by the provider: the
*Name* header lists folders first and then by name, the *Date modified* header
newest first; the other headers do nothing in cloud storage. The grid view
shows the provider's thumbnails.

## Favourites, history and sessions

A cloud folder, a drive or the shared drives can be pinned as a favourite
(*Add to favorites* in its menu, or the pin in the history). The favourite
keeps the location as names under its name. A favourite of a provider the
user no longer allows is shown locked until the bundle is allowed again.

Opening a favourite, a history entry or a restored session lands on the
folder cold: the list is shown first with "…" in the breadcrumbs, and the
names above the folder are looked up and filled in (Google tells only the
parent of each folder, so this costs one request per level; the result is
remembered for a minute). A folder that is gone, or a shared drive the user
is no longer a member of, takes the list to the listing above it with a
message.

## What a cloud item offers

The context menu of a file or folder of a drive:

| Entry | What happens |
| --- | --- |
| Open | A folder is listed. A file opens where the provider shows it, in a new tab (apps open cloud files themselves in a later step). |
| Open at Google Drive / OneDrive | The provider's own page for the item, in a new tab. |
| Copy link | Copies the provider's link to the item — the one thing another person can open, with their own access at the provider. |
| Download | The file's bytes, through the Webtop (a Google document as its Office export). |
| Copy to repository… | A folder of the repository is picked (the picker starts at the repository folder last shown); the selected items are copied there, folders with everything in them. A Google document becomes its Office export. |
| Put a link in the repository… | A small file of type `application/vnd.mintjams.webtop.cloud-link+json` per item, which opens the item at the provider. |
| Rename | Renames at the provider. |
| Add to favorites | A folder. |
| New Folder | Beside the selection (also on empty space). |
| Delete | Moves the item to the provider's trash, where it can be restored. The dialog says so. |

The menu of a repository file or folder gains **Copy to cloud storage…**: the
provider is chosen when several are enabled, the account is linked and the
bundle allowed on first use, and a folder of a drive is picked. Folders are
copied with everything in them, up to 1,000 entries per folder.

The rows above a drive's folders (the drives, the shared drives) open and link
and can be pinned; nothing is made in them.

Dropping files or items into cloud storage, and dragging cloud items into the
repository, is not available yet; the drop cursor says so.

## The Inspector

A cloud item shows its preview (an image, a video or an audio file plays from
the Webtop's own URL of the file), its name, type, size, the dates, the
**Owner**, whether it is **Shared**, and its **Location** as names with a copy
button, plus an **Open at provider** action. The repository's sections
(properties, access control, versions, locks, labels, the conversation) do not
apply and are not shown.

## Search

Searching from a cloud location is not available yet; the search field is
disabled there and says so.

## Where the pieces are

- `webtop/src/webtop/lib/cloud-location.ts`: the `cloud://<provider>/<driveId>/<folderId>` paths the Content Browser, the favourites and the sessions keep.
- `webtop/src/webtop/apps/content-browser/sources/cloud-source.ts`: the cloud listings (the two listings above the drives are made here), the crumbs, the location text.
- `/bin/cloud.cgi/<provider>/<driveId>/<id>/<name>`: the bytes of a cloud file, with `?thumbnail=` for the grid (see `linked-accounts.md`).
