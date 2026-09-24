# Webtop Content Browser

Notes on the parts of the **Content Browser** that are not obvious from its
source: how a search result is paged and sorted, the labels a user can put on a
file, the orientation of images and videos that a background route
maintains, and what the Inspector shows for a video or audio file.

## Search results

A search (the keyword box at the top, or the *Search* section of the sidebar)
is one XPath statement over `nt:file` nodes under the current folder. The
statement is sent through the platform's `xpath` GraphQL query and answered
**one page at a time** (`SEARCH_PAGE_SIZE` rows, 100). The first page is shown
as soon as it arrives; the next one is requested when the list is scrolled to
within `SEARCH_LOAD_MORE_MARGIN` of its end, and pages keep coming while the
list is too short to scroll. The count in the search bar is the index's exact
total; while pages remain it reads *showing N of M*.

Because only part of the result is loaded, **the server orders it**. The
statement carries an `order by` clause built from the list's sort column
(`SEARCH_SORT_FIELDS` in `app.ts`):

| Column | Orders on |
| --- | --- |
| Name | `@jcr:name` |
| Date modified | `@jcr:lastModified` |
| Modified by | `@jcr:lastModifiedBy` |
| Kind | `@jcr:mimeType` |
| Size | `@jcr:contentLength` |

Clicking one of these headers re-runs the search in the new order. The index
orders names by their bytes, so upper-case names sort before lower-case ones,
unlike the folder listing, which sorts on the client and ignores case. *Lock
owner* and *Version* are not indexed: those two headers sort what has been
loaded, on the client, as before.

A page that arrives after the search was closed, or after another search was
started, is dropped (`_xpathSearchSeq`).

The magnifier with an empty keyword box (and no schema condition) lists every
file under the current folder: the starting point for narrowing by facet.

## Facets

While a search result is listed, the sidebar shows **facets** in place of the
*Favorites*, *Smart folders* and *Search* sections; closing the search (the ×
in the search bar) brings those back. Each entry shows how many results
choosing it would leave.

| Facet | Values | Choice | Predicate |
| --- | --- | --- | --- |
| Kind | Images, Videos, Audio, Scripts, Documents | one | `jcr:like(@jcr:mimeType, 'image/%')` etc. for the media kinds; a script is a file of any script MIME type (`FACET_SCRIPT_FORMATS` in `app.ts`, `@jcr:mimeType='…'` over all of them); a document is anything that is neither a media kind nor a script |
| Orientation (with Images or Videos) | Portrait, Landscape, Square, Panorama | several, OR | `@mi:orientation='…'` |
| Format (with Documents) | PDF, Text, Markdown, Web, Memo, Rich text, Other | several, OR | `@jcr:mimeType='…'` over the MIME types of each format (`FACET_DOCUMENT_FORMATS` in `app.ts`); *Other* is `not(…)` of every listed MIME type, so templates, archives and the like land there |
| Format (with Scripts) | Groovy, TypeScript, JavaScript, Python, Shell | several, OR | `@jcr:mimeType='…'` over the MIME types of each language (`FACET_SCRIPT_FORMATS`); the languages together are what the Script kind matches, so there is no *Other* |
| Color | the swatch palette | several, OR | `@mi:color='…'` |
| Tags | the selected tags, then the 50 most frequent | several, AND | one `@mi:tags='…'` per tag |

Choosing a kind drops the orientation and format chosen under the previous
one. *Clear* in the panel's header removes every choice; closing the search
does the same.

*Save* in the panel's header saves the search as a smart folder, facets
included (the Search section's own save button is hidden while the facets
show). The folder stores the selection next to the keyword and conditions
(`facets` in its definition; folders saved earlier have none) and restores it
when run. Its name lists what it searches for, then where: the schema or the
keyword, each chosen facet, and the path, for example
*Images・Portrait・#event — /content/photos*.

### Counts

The counts come from the index's `facet accumulate` clause
([search-facets.md](search-facets.md)), fetched with no node through
`xpathFacets`. One statement carries every filter; it feeds the tag counts
(a tag narrows to files carrying all the chosen tags, so its count is taken
over the narrowed set) and every dimension with nothing chosen. Each
dimension with a choice gets one more statement with **its own filter left
out**, so an unchosen value still shows what choosing it instead would give:
with *Images* chosen, *Videos* still shows the number of videos. The kind's
statement also leaves out the orientation and format, which belong to it.

Kinds and formats are groups of MIME types: their counts are the
`jcr:mimeType` facet summed per group (`facetKindOf`, `facetFormatOf`; the
formats are those of the chosen kind). The
statements run alongside the list's first page; a result for an earlier
choice is dropped (`_facetSeq`).

## Labels: color and tags

A file can carry a **color** and any number of **tags**, shown in the list (a
dot before the name, up to three tags after it) and edited in the Inspector's
*Info* section. Folders have neither: the properties live on the file's
`jcr:content`, which is also where the search index reads them.

| Property | Type | Value |
| --- | --- | --- |
| `mi:color` | String | A key of the shared swatch palette (`lib/color-palette.ts`): `tomato`, `tangerine`, `banana`, `basil`, `sage`, `peacock`, `blueberry`, `lavender`, `grape`, `flamingo`, `graphite`. Absent when the file has no color. |
| `mi:tags` | String[] | The tags, in the order they were added. Absent when the file has none (the repository refuses an empty multi-value). At most 20 per file, each at most 50 characters, whitespace collapsed; the same limits as the Mail app's `mail:tags`. |

Both are written through the platform's `setProperties` mutation, as the MIME
type is. A cleared value deletes the property. The tag input suggests the tags
already in use: one count facet over every file the user can read,

```
//element(*, nt:file) facet accumulate top(@mi:tags, 200)
```

fetched with `first: 0` (no node is materialised; see
[search-facets.md](search-facets.md)), loaded once per Inspector and extended
with each tag the user adds.

Any String property is a keyword field and a facet dimension in the index, so
a search can filter on them (`@mi:color='tomato'`, `@mi:tags='draft'`, which
matches any value of the multi-value) and count them (`facet accumulate
@mi:color, top(@mi:tags, 20)`).

## Orientation of images and videos

The route `webtop-media-metadata`
(`etc/eip/routes/webtop/media-metadata.xml`, class
`webtop.media.MediaMetadata` under `/usr/local/classes`) keeps three
properties on every image and video file:

| Property | Type | Value |
| --- | --- | --- |
| `mi:orientation` | String | `portrait`, `landscape`, `square` (sides within 5 % of each other) or `panorama` (long side at least twice the short side). |
| `mi:width` | Long | Pixels, after the EXIF rotation is applied. |
| `mi:height` | Long | Pixels, after the EXIF rotation is applied. |

The Inspector shows them as an *Orientation* row once they exist.

### How the route works

The repository posts an OSGi EventAdmin event `javax/jcr/Node/<ADDED |
CHANGED | MOVED | REMOVED>` for every node change, with the node's `path`,
`type`, the `user_id` of the session that made the change and, for a CHANGED,
the `properties` that changed (a change to `jcr:content` is reported on the
file). The route consumes them through the `eventadmin:` component, filtered
to `nt:file`, and acts on **an added file, or a changed file whose `jcr:data`
or `jcr:mimeType` changed** (`MediaMetadata.isContentChange`).

That condition is also what keeps the route from looping: its own write
raises a CHANGED that names `mi:orientation`, `mi:width` and `mi:height` (and
the modification stamps), never `jcr:data`, so it is dropped. So is every
other property change to the file, such as a new color or tag.

The size comes from Tika (`AutoDetectParser`): the EXIF / TIFF tags of a JPEG,
the header of a PNG, GIF, BMP or WebP, the track header of an MP4 or
QuickTime file. EXIF orientations 5 to 8 are a quarter turn, so their sides
are swapped. A file the parser cannot read, or larger than
`MediaMetadata.MAX_BYTES` (512 MB), keeps whatever it had; a file whose MIME
type is no longer `image/*` or `video/*` loses the three properties.

The file is read and written **as the user who made the change**
(`ScriptAPI.createServiceUserContext(user_id)`), so the route never touches
what that user could not; a change without a repository user (a deployment, a
system job) runs as the system. Like the Mail route, the context is put on the
exchange as `mi:cms.context` and closed in `doFinally`.

The route is deployed with the seed into the `system` workspace and reacts to
that workspace's events (the component's default). A workspace that keeps its
own content needs the same route deployed there.

### Existing files

The route reacts to changes, so files that were already in the repository
have no orientation until their content is next written. To fill them in, run
the class over a query from a script or a route, for example

```
/jcr:root/content//element(*, nt:file)[jcr:like(@jcr:mimeType, 'image/%') and not(@mi:orientation)]
```

and call `webtop.media.MediaMetadata.create(context).update(path)` for each
path.

## Video and audio in the Inspector

The preview at the top of the Inspector shows an image as it is; a file whose
MIME type is `video/*` or `audio/*` gets the browser's own player instead
(`<video controls>`, or a spectrum view above an `<audio controls>`). The
player loads the same download URL the image preview uses, with
`preload="metadata"`, so selecting a file fetches only its header. The
download servlet answers Range requests, so seeking and playback stream the
part that is needed rather than the whole file.

Nothing is decoded on the server: the browser plays what it can. A file the
browser cannot play (an AVI, a WMV, a codec the platform lacks) raises the
element's `error` event and the preview falls back to the file icon, the same
way a broken image does.

The spectrum of the audio preview is drawn with the Web Audio API: on the
first play the `<audio>` element is routed through an `AnalyserNode`
(`lib/spectrum-canvas.ts` draws the bars; the Radio app draws its spectrum with
the same module). The context is created inside the play gesture, so it starts
running, and the element stays connected while the audio preview is shown.
Where Web Audio is unavailable the file plays without the spectrum.

Once the player has read the file's header, the *Info* section shows what the
browser knows:

| Row | Source | Shown when |
| --- | --- | --- |
| Duration | `HTMLMediaElement.duration`, as m:ss or h:mm:ss | finite and above zero (a live stream has none) |
| Dimensions | `videoWidth` × `videoHeight` | a video, unless the *Orientation* row already shows `mi:width` × `mi:height` |
| Bitrate | file size × 8 ÷ duration, as kbps or Mbps, marked *average* | duration and size are known |

The codec, the sample rate and the channel count are not available to the
browser. They would come from Tika through the `webtop-media-metadata` route,
the way the orientation does, in properties of their own.
