// Query.documentText (files.graphqls): the text of a document, for a reader
// that cannot open the file itself — an MCP client such as Claude, through
// the read_document tool (tools.yml).
//
// A text file is read as it is, in its recorded encoding. Anything else is
// handed to Apache Tika, which extracts the text of PDF, Office (Word, Excel,
// PowerPoint, old and new), OpenDocument, RTF, EPUB, e-mail and the other
// formats it parses. It is the extractor the search index uses, so what
// search finds in a document, this returns.
//
// The text is paged by character (offset, limit). Extraction stops at
// offset + limit characters, so a deep page of a long document costs a parse
// of the document up to that point, and no more than asked for is held in
// memory.
//
// Runs as the caller: a file the caller cannot read does not exist.
import java.nio.charset.Charset
import org.apache.tika.Tika
import org.apache.tika.exception.EncryptedDocumentException
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties

// A Tika instance is thread-safe and reusable; one per compiled script.
class DocumentTextTika {
	static final Tika TIKA = new Tika()
}

final int DEFAULT_LIMIT = 50_000

// Types read as text without extraction, in step with the MCP read_file tool:
// extraction would strip the markup of HTML and XML, which is what a CMS
// author edits.
final Set<String> TEXT_TYPES = [
	'application/json', 'application/xml', 'application/javascript', 'application/x-javascript',
	'application/ecmascript', 'application/yaml', 'application/x-yaml', 'application/graphql',
	'application/sql', 'application/x-sh', 'application/x-groovy', 'application/toml', 'image/svg+xml',
] as Set

String path = args.path as String
int offset = (args.offset == null) ? 0 : (args.offset as int)
int limit = (args.limit == null) ? DEFAULT_LIMIT : (args.limit as int)

if (!path || !path.startsWith('/') || path.contains('/../') || path.endsWith('/..') || path.contains('/./')) {
	throw new IllegalArgumentException('path must be an absolute repository path.')
}
if (offset < 0) {
	throw new IllegalArgumentException('offset must be 0 or more.')
}
if (limit < 1) {
	throw new IllegalArgumentException('limit must be 1 or more.')
}

def resource = context.getResourceResolver().getResource(path)
if (!resource.exists()) {
	throw new IllegalArgumentException("There is no node at ${path}, or the signed-in user is not allowed to read it.".toString())
}
if (resource.isCollection()) {
	throw new IllegalArgumentException("${path} is a folder, not a file.".toString())
}

String mimeType = (resource.getContentType() ?: 'application/octet-stream').toLowerCase(Locale.ROOT)
long size = resource.getContentLength()

Map result = [
	path: path,
	mimeType: mimeType,
	size: size,
	extracted: false,
	offset: offset,
	text: '',
	truncated: false,
]

boolean isText = mimeType.startsWith('text/') || mimeType.endsWith('+json') || mimeType.endsWith('+xml') || TEXT_TYPES.contains(mimeType)
boolean isMedia = mimeType.startsWith('image/') || mimeType.startsWith('audio/') || mimeType.startsWith('video/') || mimeType.startsWith('font/')

if (isText) {
	// Read the page straight from the file, in its encoding; never the whole file.
	StringBuilder text = new StringBuilder()
	boolean truncated = false
	resource.getContentAsReader().withCloseable { Reader reader ->
		long toSkip = offset
		while (toSkip > 0) {
			long skipped = reader.skip(toSkip)
			if (skipped <= 0) {
				break
			}
			toSkip -= skipped
		}
		char[] buffer = new char[8192]
		while (text.length() < limit) {
			int n = reader.read(buffer, 0, Math.min(buffer.length, limit - text.length()))
			if (n == -1) {
				break
			}
			text.append(buffer, 0, n)
		}
		truncated = (text.length() >= limit) && (reader.read() != -1)
	}
	result.text = text.toString()
	result.truncated = truncated
	if (truncated) {
		result.nextOffset = offset + text.length()
	}
	return result
}

if (isMedia) {
	throw new IllegalArgumentException("${path} is ${mimeType}: it has no text to extract. Images are returned by read_file.".toString())
}

// Everything else: Apache Tika. One more character than the page needs tells
// whether the document goes on.
int max = (offset > Integer.MAX_VALUE - limit - 1) ? Integer.MAX_VALUE : (offset + limit + 1)
Metadata metadata = new Metadata()
String all
try {
	resource.getContentAsStream().withCloseable { InputStream input ->
		all = DocumentTextTika.TIKA.parseToString(input, metadata, max) ?: ''
	}
} catch (EncryptedDocumentException ex) {
	throw new IllegalArgumentException("${path} is encrypted: its text cannot be extracted without the password.".toString())
} catch (Exception ex) {
	String reason = ex.message ?: ex.class.simpleName
	throw new IllegalArgumentException("The text of ${path} could not be extracted: ${reason}".toString())
}

int start = Math.min(offset, all.length())
int end = Math.min(offset + limit, all.length())
result.extracted = true
result.detectedType = metadata.get('Content-Type')
result.title = metadata.get(TikaCoreProperties.TITLE)
result.author = metadata.get(TikaCoreProperties.CREATOR)
String pages = metadata.get('xmpTPg:NPages') ?: metadata.get('meta:page-count') ?: metadata.get('meta:slide-count')
if (pages?.isInteger()) {
	result.pages = pages as int
}
result.text = all.substring(start, end)
result.truncated = all.length() > offset + limit
if (result.truncated) {
	result.nextOffset = end
}
if (all.trim().isEmpty()) {
	result.note = (offset == 0)
		? 'No text could be extracted: the document may hold only images (a scanned PDF, for example).'
		: 'There is no text at or after this offset.'
}
return result
