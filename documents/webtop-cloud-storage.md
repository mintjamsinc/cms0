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

## Drops

Items drag between the repository and cloud storage as they drag within the
repository, and the cursor says what a drop would do:

| From → to | What happens |
| --- | --- |
| Within one drive | A move; Ctrl copies. Not into itself, and a plain move into the folder the items are in does nothing. |
| Between two drives of one provider | A copy, always (the providers do not move between drives). |
| Between providers | Nothing. |
| Cloud → repository (a folder of the list, or the Desktop) | A copy; a folder with everything in it. |
| Repository → cloud (a folder of the list, a drive's row, or a provider in the sidebar) | A copy; a folder with everything in it. |
| Files from the PC → cloud | Uploaded, folders made first. A name the folder already has is asked about: overwrite replaces the file's content, skip leaves it. |
| A mail attachment → cloud | Uploaded like a file from the PC. |
| A save chip (an editor's *Save As*, see `webtop-save-as.md`) → cloud | Written to the drive; a chip whose format is a Google document is accepted on Google Drive only. The editor keeps the file it has open and says the copy was saved, with a link to it at the provider; writing cloud files from the editors comes in a later step. |

The listings above the drives (the drives, the shared drives) take nothing.
Copy and paste do the same as a drop; a cut is a move, so it can only be
pasted into a folder of the same drive.

A cloud item dropped on a chat conversation (the Chat app, a memo's
conversation) is not linked like a repository file: its name and the
provider's own link go into the message as text, since that link is what
another person can open.

## The Inspector

A cloud item shows its preview (an image, a video or an audio file plays from
the Webtop's own URL of the file), its name, type, size, the dates, the
**Owner**, whether it is **Shared**, and its **Location** as names with a copy
button, plus an **Open at provider** action. The repository's sections
(properties, access control, versions, locks, labels, the conversation) do not
apply and are not shown.

## Search

The search field above the list searches a drive through its provider. What
a search covers depends on the provider, and the search bar says which:

| Provider | Scope | The search bar says |
| --- | --- | --- |
| Google | The whole drive the folder is in: My Drive, or the shared drive. Drive's API has no "below this folder" condition. | "Searching all of My Drive", "Searching all of shared drive “Sales”" |
| Microsoft | The folder being browsed and everything below it. | "Searching in Documents" |

The words go to the provider, which matches them against names and, where
it indexes content, against content; the provider also decides the ranking.
A search of My Drive on Google also finds files shared with the user, since
Drive keeps them in the same corpus. The field is disabled above the drives
(the drives and the shared drives listings): a drive has to be open first.

Results arrive 100 at a time; the next page loads as the list is scrolled.
The providers give no total, so the search bar counts what has been loaded
("100 results so far", then "130 results"). The *Name* and *Date modified*
headers sort what has been loaded, in either direction; the other headers do
nothing, as in a listing.

A result's folder is not the one being browsed, so the list gains a
**Folder** column naming it; clicking the name opens that folder. Google
tells only the id of the folder, so the names of each result's folder are
looked up after the results are shown (one request per folder, remembered
for a minute); the column shows "…" until then. Microsoft names the folders
in the result itself. A Google file shared with the user has no folder of
the user's, and the column is empty. The Inspector's **Location** follows the
same look-up and says "Looking up…" meanwhile.

While a result is listed, the sidebar shows the facets the provider offers
in place of its other sections, without counts (the providers count
nothing):

| Facet | Choices | Providers |
| --- | --- | --- |
| Kind | Images, Videos, Audio, Documents, Folders (one at a time; choosing it again clears it) | both |
| Modified | Today, Past 7 days, Past 30 days, Date range (two dates, in the user's time zone) | both |
| Owner | Owned by me | Google |

*Clear* in the panel's header removes every choice. Google applies the
facets itself; Microsoft's search takes words only, so the server applies
the kind and the dates to the answers, reading on until a page is full.
A cloud search cannot be saved as a smart folder. Renaming or deleting a
result runs the search again; closing the search (the × in the search bar)
lists the folder again, with its breadcrumbs as they were.

## Where the pieces are

- `webtop/src/webtop/lib/cloud-location.ts`: the `cloud://<provider>/<driveId>/<folderId>` paths the Content Browser, the favourites and the sessions keep.
- `webtop/src/webtop/apps/content-browser/sources/cloud-source.ts`: the cloud listings (the two listings above the drives are made here), the crumbs, the location text.
- `/bin/cloud.cgi/<provider>/<driveId>/<id>/<name>`: the bytes of a cloud file, with `?thumbnail=` for the grid (see `linked-accounts.md`).
