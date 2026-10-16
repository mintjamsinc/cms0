// Query.grepFiles (files.graphqls): the lines of the text files under a
// folder that match a pattern, with their paths and line numbers — the
// grep_files MCP tool (tools.yml).
//
// Full-text search finds documents by words; this finds places. It is what
// a person does with grep: an exact expression, every text file under a
// folder, the line it is on. Files are found with an XPath query and read
// one at a time; a file that is binary, a media type, or larger than
// maxFileSize is skipped and counted, never read into memory.
//
// Runs as the caller: a file the caller cannot read is not scanned.
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

final int DEFAULT_MAX_MATCHES = 200
final int DEFAULT_MAX_FILE_SIZE = 4 * 1024 * 1024
final int MAX_LINE_CHARS = 500
final int PAGE = 500

String path = args.path as String
String patternText = args.pattern as String
boolean regex = (args.regex == null) ? true : (args.regex as boolean)
boolean ignoreCase = args.ignoreCase ? true : false
String namePattern = args.namePattern ? (args.namePattern as String) : null
int maxMatches = (args.maxMatches == null) ? DEFAULT_MAX_MATCHES : (args.maxMatches as int)
int maxFileSize = (args.maxFileSize == null) ? DEFAULT_MAX_FILE_SIZE : (args.maxFileSize as int)

if (!path || !path.startsWith('/') || path.contains('/../') || path.endsWith('/..') || path.contains('/./')) {
	throw new IllegalArgumentException('path must be an absolute repository path.')
}
if (!patternText) {
	throw new IllegalArgumentException('pattern must not be empty.')
}
if (maxMatches < 1) {
	throw new IllegalArgumentException('maxMatches must be 1 or more.')
}

int flags = ignoreCase ? (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) : 0
Pattern pattern
try {
	pattern = Pattern.compile(regex ? patternText : Pattern.quote(patternText), flags)
} catch (PatternSyntaxException ex) {
	throw new IllegalArgumentException("pattern is not a valid regular expression: ${ex.description} at index ${ex.index}. Set regex to false to search for the text literally.".toString())
}

// A file-name glob: * any run, ? one character, the rest literal; case-insensitive.
Pattern namePat = null
if (namePattern) {
	StringBuilder sb = new StringBuilder()
	for (char c : namePattern.toCharArray()) {
		if (c == ('*' as char)) {
			sb.append('.*')
		} else if (c == ('?' as char)) {
			sb.append('.')
		} else {
			sb.append(Pattern.quote(c.toString()))
		}
	}
	namePat = Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
}

def resolver = context.getResourceResolver()
def folder = resolver.getResource(path)
if (!folder.exists()) {
	throw new IllegalArgumentException("There is no node at ${path}, or the signed-in user is not allowed to read it.".toString())
}
if (!folder.isCollection()) {
	throw new IllegalArgumentException("${path} is a file, not a folder. Read it with read_file.".toString())
}

String statement = ((path == '/') ? '/jcr:root' : '/jcr:root' + XPath.encodeJCR(path)) + '//element(*, nt:file)'

List<Map> matches = []
int filesScanned = 0
int filesSkipped = 0
int filesMatched = 0
boolean truncated = false
long offset = 0
outer:
while (true) {
	def result = XPath.createQuery(statement).offset(offset).limit(PAGE).execute()
	List files = result.getResources()
	if (files.isEmpty()) {
		break
	}
	for (def file : files) {
		String name = file.getName()
		if (namePat != null && !namePat.matcher(name).matches()) {
			continue
		}
		String mimeType = (file.getContentType() ?: 'application/octet-stream').toLowerCase(Locale.ROOT)
		if (mimeType.startsWith('image/') || mimeType.startsWith('audio/') || mimeType.startsWith('video/') || mimeType.startsWith('font/')) {
			filesSkipped++
			continue
		}
		if (file.getContentLength() > maxFileSize) {
			filesSkipped++
			continue
		}
		boolean binary = false
		file.getContentAsStream().withCloseable { InputStream input ->
			byte[] head = input.readNBytes(4096)
			for (byte b : head) {
				if (b == 0) {
					binary = true
					break
				}
			}
		}
		if (binary) {
			filesSkipped++
			continue
		}
		filesScanned++
		boolean matchedFile = false
		String filePath = file.getPath()
		new BufferedReader(file.getContentAsReader()).withCloseable { BufferedReader reader ->
			int lineNo = 0
			String line
			while ((line = reader.readLine()) != null) {
				lineNo++
				if (!pattern.matcher(line).find()) {
					continue
				}
				matchedFile = true
				String text = (line.length() > MAX_LINE_CHARS) ? (line.substring(0, MAX_LINE_CHARS) + '…') : line
				matches << [path: filePath, line: lineNo, text: text]
				if (matches.size() >= maxMatches) {
					truncated = true
					break
				}
			}
		}
		if (matchedFile) {
			filesMatched++
		}
		if (truncated) {
			break outer
		}
	}
	if (!result.hasMore()) {
		break
	}
	offset += files.size()
}

return [
	path: path,
	pattern: patternText,
	filesScanned: filesScanned,
	filesSkipped: filesSkipped,
	filesMatched: filesMatched,
	matches: matches,
	truncated: truncated,
]
