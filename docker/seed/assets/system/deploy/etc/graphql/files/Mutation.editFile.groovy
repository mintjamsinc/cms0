// Mutation.editFile (files.graphqls): replaces one piece of a text file and
// leaves the rest as it is — the edit_file MCP tool (tools.yml).
//
// write_file replaces a whole file, and a model asked to change one line of
// a file tends to write the file it imagines rather than the file that
// exists. This is the alternative: the caller names the text to replace and
// what to put there, the way a person edits. The match is exact — whitespace
// and indentation included — and has to be unique unless replaceAll is set,
// so a vague oldText changes nothing rather than the wrong place.
//
// Line endings are matched to the file's: an oldText with LF finds its text
// in a CRLF file and the replacement is written with CRLF. The file keeps its
// encoding and its MIME type; the content's modification metadata advances
// with the bytes, as it does for every content write.
//
// Runs as the caller: a file the caller cannot read does not exist, and the
// repository refuses a file the caller cannot write, a file locked by someone
// else, and a checked-in versionable file.
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

String path = args.path as String
String oldText = args.oldText as String
String newText = (args.newText == null) ? '' : (args.newText as String)
boolean replaceAll = args.replaceAll ? true : false

if (!path || !path.startsWith('/') || path.contains('/../') || path.endsWith('/..') || path.contains('/./')) {
	throw new IllegalArgumentException('path must be an absolute repository path.')
}
if (!oldText) {
	throw new IllegalArgumentException('oldText must not be empty. To create a file or replace its whole content, use write_file.')
}
if (oldText == newText) {
	throw new IllegalArgumentException('newText is the same as oldText: nothing to change.')
}

def resolver = context.getResourceResolver()
def resource = resolver.getResource(path)
if (!resource.exists()) {
	throw new IllegalArgumentException("There is no node at ${path}, or the signed-in user is not allowed to read it.".toString())
}
if (resource.isCollection()) {
	throw new IllegalArgumentException("${path} is a folder, not a file.".toString())
}

String mimeType = (resource.getContentType() ?: 'application/octet-stream').toLowerCase(Locale.ROOT)
if (mimeType.startsWith('image/') || mimeType.startsWith('audio/') || mimeType.startsWith('video/') || mimeType.startsWith('font/')) {
	throw new IllegalArgumentException("${path} is ${mimeType}, not a text file. Use write_file to replace it.".toString())
}

byte[] bytes = resource.getContentAsByteArray()
int probe = Math.min(bytes.length, 4096)
for (int i = 0; i < probe; i++) {
	if (bytes[i] == 0) {
		throw new IllegalArgumentException("${path} is binary, not a text file. Use write_file to replace it.".toString())
	}
}

Charset charset = StandardCharsets.UTF_8
String encoding = resource.getContentEncoding()
if (encoding && Charset.isSupported(encoding)) {
	charset = Charset.forName(encoding)
}
String content = new String(bytes, charset)

// Match the line endings of the file when the caller's text has none of its own.
if (content.contains('\r\n') && !oldText.contains('\r') && !newText.contains('\r')) {
	oldText = oldText.replace('\n', '\r\n')
	newText = newText.replace('\n', '\r\n')
}

int count = 0
int from = 0
while (true) {
	int at = content.indexOf(oldText, from)
	if (at < 0) {
		break
	}
	count++
	from = at + oldText.length()
}
if (count == 0) {
	throw new IllegalArgumentException("oldText was not found in ${path}. It has to match the file exactly, whitespace and indentation included: read the file and copy the text to replace from it.".toString())
}
if (count > 1 && !replaceAll) {
	throw new IllegalArgumentException("oldText occurs ${count} times in ${path}; nothing was changed. Include more of the surrounding text so it matches once, or set replaceAll to replace every occurrence.".toString())
}

int first = content.indexOf(oldText)
int line = 1
for (int i = 0; i < first; i++) {
	if (content.charAt(i) == ('\n' as char)) {
		line++
	}
}
String updated = replaceAll
	? content.replace(oldText, newText)
	: content.substring(0, first) + newText + content.substring(first + oldText.length())
byte[] updatedBytes = updated.getBytes(charset)

resource.write(new ByteArrayInputStream(updatedBytes))
resolver.getSession().commit()

log.info("editFile: user=${resolver.getSession().getUserID()} path=${path} replacements=${count} line=${line} bytes=${updatedBytes.length}".toString())

return [
	path: path,
	replacements: count,
	line: line,
	size: updatedBytes.length,
	modified: resource.getLastModified(),
	modifiedBy: resource.getLastModifiedBy(),
]
