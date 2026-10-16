// Query.findFiles (files.graphqls): the files and folders under a folder
// whose name matches a pattern — the find_files MCP tool (tools.yml).
//
// Files come from an XPath query, which the search index answers without
// walking the tree; the name pattern is a glob (* any run, ? one character,
// case-insensitive) matched here, since the repository's XPath has no
// wildcard on names. Folders are not in the index, so a search that includes
// them walks the tree, depth first, and stops as soon as the page is full.
// A query that names an exact file (element(.web.yml, nt:file)) is still
// the better tool for that; this one is for "every *.md under /content".
//
// Runs as the caller: a node the caller cannot read is not listed.
import java.util.regex.Pattern

final int DEFAULT_LIMIT = 100
final int PAGE = 500

String path = args.path as String
String namePattern = args.namePattern as String
String type = args.type ? (args.type as String) : 'file'
int offset = (args.offset == null) ? 0 : (args.offset as int)
int limit = (args.limit == null) ? DEFAULT_LIMIT : (args.limit as int)

if (!path || !path.startsWith('/') || path.contains('/../') || path.endsWith('/..') || path.contains('/./')) {
	throw new IllegalArgumentException('path must be an absolute repository path.')
}
if (!namePattern) {
	throw new IllegalArgumentException('namePattern must not be empty.')
}
if (!(type in ['file', 'folder', 'any'])) {
	throw new IllegalArgumentException('type must be file, folder or any.')
}
if (offset < 0) {
	throw new IllegalArgumentException('offset must be 0 or more.')
}
if (limit < 1) {
	throw new IllegalArgumentException('limit must be 1 or more.')
}

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
Pattern namePat = Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)

def folder = context.getResourceResolver().getResource(path)
if (!folder.exists()) {
	throw new IllegalArgumentException("There is no node at ${path}, or the signed-in user is not allowed to read it.".toString())
}
if (!folder.isCollection()) {
	throw new IllegalArgumentException("${path} is a file, not a folder.".toString())
}

List<Map> nodes = []
int scanned = 0
int matched = 0
boolean truncated = false

// Looks at one node; true when the page is full and the search can stop.
Closure consider = { def node ->
	scanned++
	if (!namePat.matcher(node.getName()).matches()) {
		return false
	}
	matched++
	if (matched <= offset) {
		return false
	}
	if (nodes.size() >= limit) {
		truncated = true
		return true
	}
	boolean isFolder = node.isCollection()
	Map info = [
		path: node.getPath(),
		nodeType: isFolder ? 'nt:folder' : 'nt:file',
		modified: node.getLastModified(),
		modifiedBy: node.getLastModifiedBy(),
	]
	if (!isFolder) {
		info.size = node.getContentLength()
		info.mimeType = node.getContentType()
	}
	nodes << info
	return false
}

if (type == 'file') {
	String statement = ((path == '/') ? '/jcr:root' : '/jcr:root' + XPath.encodeJCR(path)) + '//element(*, nt:file)'
	long queryOffset = 0
	outer:
	while (true) {
		def result = XPath.createQuery(statement).offset(queryOffset).limit(PAGE).execute()
		List found = result.getResources()
		if (found.isEmpty()) {
			break
		}
		for (def node : found) {
			if (consider(node)) {
				break outer
			}
		}
		if (!result.hasMore()) {
			break
		}
		queryOffset += found.size()
	}
} else {
	boolean files = (type == 'any')
	Closure walk
	walk = { def parent ->
		def it = parent.list()
		while (it.hasNext()) {
			def child = it.next()
			boolean isFolder = child.isCollection()
			if (isFolder || files) {
				if (consider(child)) {
					return true
				}
			}
			if (isFolder && walk(child)) {
				return true
			}
		}
		return false
	}
	walk(folder)
}

return [
	path: path,
	namePattern: namePattern,
	scanned: scanned,
	offset: offset,
	nodes: nodes,
	truncated: truncated,
	nextOffset: truncated ? (offset + nodes.size()) : null,
]
