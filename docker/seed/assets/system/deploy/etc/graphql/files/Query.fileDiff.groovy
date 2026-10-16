// Query.fileDiff (files.graphqls): what changed in a text file between two of
// its versions, or between a version and the content as it is now — the
// diff_versions MCP tool (tools.yml).
//
// `from` and `to` are version names from the version history (1.0, 1.1, …).
// Without `to` the comparison is with the current content; without `from`
// it starts from the base version, so a call with only the path answers
// "what changed since the last check-in". The result is a unified diff with
// three lines of context, as `diff -u` would print it, plus the counts.
//
// Lines are compared after CRLF is normalised to LF, so a change of line
// endings alone shows no difference. The edit script is Myers' algorithm
// (the one git uses); for two files that differ very widely it falls back to
// one hunk covering everything between the common head and tail, so memory
// stays bounded whatever the files are.
//
// Runs as the caller: a file the caller cannot read does not exist.
import groovy.transform.CompileStatic
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

final int DEFAULT_LIMIT = 100_000
final int CONTEXT = 3

@CompileStatic
class FileDiffLines {
	/** An edit: ' ' kept, '-' removed, '+' added, with the line's text. */
	static List<Object[]> diff(List<String> a, List<String> b) {
		int n = a.size()
		int m = b.size()
		// Common head and tail are never part of an edit; trimming them first
		// keeps the search small for the usual edit in the middle of a file.
		int head = 0
		while (head < n && head < m && a[head] == b[head]) {
			head++
		}
		int tail = 0
		while (tail < n - head && tail < m - head && a[n - 1 - tail] == b[m - 1 - tail]) {
			tail++
		}
		List<String> ma = a.subList(head, n - tail)
		List<String> mb = b.subList(head, m - tail)
		List<Object[]> middle = myers(ma, mb)
		if (middle == null) {
			middle = new ArrayList<Object[]>()
			for (String line : ma) {
				middle.add([('-' as char), line] as Object[])
			}
			for (String line : mb) {
				middle.add([('+' as char), line] as Object[])
			}
		}
		List<Object[]> edits = new ArrayList<Object[]>(n + m)
		for (int i = 0; i < head; i++) {
			edits.add([(' ' as char), a[i]] as Object[])
		}
		edits.addAll(middle)
		for (int i = n - tail; i < n; i++) {
			edits.add([(' ' as char), a[i]] as Object[])
		}
		return edits
	}

	/** Myers' shortest edit script, or null when it would need more memory than allowed. */
	static List<Object[]> myers(List<String> a, List<String> b) {
		int n = a.size()
		int m = b.size()
		int max = n + m
		if (max == 0) {
			return new ArrayList<Object[]>()
		}
		int width = 2 * max + 2
		int[] v = new int[width]
		List<int[]> trace = new ArrayList<int[]>()
		long budget = 16_000_000L
		int dFound = -1
		for (int d = 0; d <= max; d++) {
			if ((long) (d + 1) * width > budget) {
				return null
			}
			trace.add(v.clone())
			for (int k = -d; k <= d; k += 2) {
				int x
				if (k == -d || (k != d && v[k - 1 + max] < v[k + 1 + max])) {
					x = v[k + 1 + max]
				} else {
					x = v[k - 1 + max] + 1
				}
				int y = x - k
				while (x < n && y < m && a[x] == b[y]) {
					x++
					y++
				}
				v[k + max] = x
				if (x >= n && y >= m) {
					dFound = d
					break
				}
			}
			if (dFound >= 0) {
				break
			}
		}
		// Backtrack from (n, m) through the recorded furthest-reaching points.
		LinkedList<Object[]> edits = new LinkedList<Object[]>()
		int x = n
		int y = m
		for (int d = dFound; d > 0; d--) {
			int[] vd = trace.get(d)
			int k = x - y
			int prevK
			if (k == -d || (k != d && vd[k - 1 + max] < vd[k + 1 + max])) {
				prevK = k + 1
			} else {
				prevK = k - 1
			}
			int prevX = vd[prevK + max]
			int prevY = prevX - prevK
			while (x > prevX && y > prevY) {
				x--
				y--
				edits.addFirst([(' ' as char), a[x]] as Object[])
			}
			if (x == prevX) {
				y--
				edits.addFirst([('+' as char), b[y]] as Object[])
			} else {
				x--
				edits.addFirst([('-' as char), a[x]] as Object[])
			}
		}
		while (x > 0 && y > 0) {
			x--
			y--
			edits.addFirst([(' ' as char), a[x]] as Object[])
		}
		return new ArrayList<Object[]>(edits)
	}
}

String path = args.path as String
String fromName = args.from ? (args.from as String) : null
String toName = args.to ? (args.to as String) : null
int limit = (args.limit == null) ? DEFAULT_LIMIT : (args.limit as int)

if (!path || !path.startsWith('/') || path.contains('/../') || path.endsWith('/..') || path.contains('/./')) {
	throw new IllegalArgumentException('path must be an absolute repository path.')
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
if (!resource.isVersionControlled()) {
	throw new IllegalArgumentException("${path} is not under version control, so no earlier content is kept for it. Make it versionable with addVersionControl, or compare it with another file by reading both.".toString())
}

def history = resource.getVersionHistory()
def versionOf = { String name ->
	try {
		return history.getVersion(name)
	} catch (Exception ex) {
		throw new IllegalArgumentException("${path} has no version named ${name}. version_history lists its versions.".toString())
	}
}
def fromVersion = fromName ? versionOf(fromName) : resource.getBaseVersion()
if (fromVersion == null || fromVersion.getName() == 'jcr:rootVersion') {
	throw new IllegalArgumentException("${path} has no version to compare with yet: its history holds only the root version. Create one with checkpoint or checkin.".toString())
}
def toVersion = toName ? versionOf(toName) : null

def textOf = { def res, String label ->
	String mimeType = (res.getContentType() ?: 'application/octet-stream').toLowerCase(Locale.ROOT)
	if (mimeType.startsWith('image/') || mimeType.startsWith('audio/') || mimeType.startsWith('video/') || mimeType.startsWith('font/')) {
		throw new IllegalArgumentException("${path} (${label}) is ${mimeType}, not text: there is no line-by-line difference to show.".toString())
	}
	byte[] bytes = res.getContentAsByteArray()
	int probe = Math.min(bytes.length, 4096)
	for (int i = 0; i < probe; i++) {
		if (bytes[i] == 0) {
			throw new IllegalArgumentException("${path} (${label}) is binary, not text: there is no line-by-line difference to show.".toString())
		}
	}
	Charset charset = StandardCharsets.UTF_8
	String encoding = res.getContentEncoding()
	if (encoding && Charset.isSupported(encoding)) {
		charset = Charset.forName(encoding)
	}
	return new String(bytes, charset).replace('\r\n', '\n')
}

String fromLabel = "version ${fromVersion.getName()}".toString()
String toLabel = toVersion ? "version ${toVersion.getName()}".toString() : 'current'
String fromText = textOf(fromVersion.getFrozen(), fromLabel)
String toText = textOf(toVersion ? toVersion.getFrozen() : resource, toLabel)

def splitLines = { String text ->
	if (text.isEmpty()) {
		return [lines: [], newline: true]
	}
	boolean newline = text.endsWith('\n')
	String body = newline ? text.substring(0, text.length() - 1) : text
	return [lines: body.split('\n', -1) as List<String>, newline: newline]
}
Map a = splitLines(fromText)
Map b = splitLines(toText)

Map result = [
	path: path,
	from: fromVersion.getName(),
	fromCreated: fromVersion.getCreated(),
	to: toVersion ? toVersion.getName() : 'current',
	toCreated: toVersion ? toVersion.getCreated() : resource.getLastModified(),
	identical: fromText == toText,
	added: 0,
	removed: 0,
	hunks: 0,
	diff: '',
	truncated: false,
]
if (result.identical) {
	return result
}

List<Object[]> edits = FileDiffLines.diff(a.lines as List<String>, b.lines as List<String>)

// Group the edits into hunks: runs of changes with CONTEXT kept lines around
// them, as diff -u prints them.
List<List<Integer>> hunks = []
int i = 0
while (i < edits.size()) {
	if ((edits[i][0] as char) == (' ' as char)) {
		i++
		continue
	}
	int start = Math.max(0, i - CONTEXT)
	int end = i
	int j = i
	while (j < edits.size()) {
		if ((edits[j][0] as char) != (' ' as char)) {
			end = j + 1
			j++
			continue
		}
		// A kept run shorter than twice the context joins the hunks around it.
		int k = j
		while (k < edits.size() && (edits[k][0] as char) == (' ' as char)) {
			k++
		}
		if (k < edits.size() && k - j <= 2 * CONTEXT) {
			j = k
			continue
		}
		break
	}
	hunks << [start, Math.min(edits.size(), end + CONTEXT)]
	i = j
}

StringBuilder out = new StringBuilder()
out.append('--- ').append(path).append(' (').append(fromLabel).append(')\n')
out.append('+++ ').append(path).append(' (').append(toLabel).append(')\n')
int aLine = 0
int bLine = 0
int added = 0
int removed = 0
// Line numbers of each edit in a and b, for the hunk headers.
int[] aAt = new int[edits.size() + 1]
int[] bAt = new int[edits.size() + 1]
for (int e = 0; e < edits.size(); e++) {
	aAt[e] = aLine
	bAt[e] = bLine
	char op = edits[e][0] as char
	if (op == (' ' as char)) {
		aLine++
		bLine++
	} else if (op == ('-' as char)) {
		aLine++
		removed++
	} else {
		bLine++
		added++
	}
}
aAt[edits.size()] = aLine
bAt[edits.size()] = bLine

boolean truncated = false
for (List<Integer> hunk : hunks) {
	int start = hunk[0]
	int end = hunk[1]
	int aCount = aAt[end] - aAt[start]
	int bCount = bAt[end] - bAt[start]
	StringBuilder text = new StringBuilder()
	text.append('@@ -').append(aCount == 0 ? aAt[start] : aAt[start] + 1).append(',').append(aCount)
		.append(' +').append(bCount == 0 ? bAt[start] : bAt[start] + 1).append(',').append(bCount).append(" @@\n")
	for (int e = start; e < end; e++) {
		text.append(edits[e][0] as char).append(edits[e][1] as String).append('\n')
		// The last line of either side without a newline is marked as diff does.
		boolean lastOfA = (edits[e][0] as char) != ('+' as char) && aAt[e] == (a.lines as List).size() - 1 && !(a.newline as boolean)
		boolean lastOfB = (edits[e][0] as char) != ('-' as char) && bAt[e] == (b.lines as List).size() - 1 && !(b.newline as boolean)
		if (lastOfA || lastOfB) {
			text.append('\\ No newline at end of file\n')
		}
	}
	if (out.length() + text.length() > limit) {
		truncated = true
		break
	}
	out.append(text)
	result.hunks = (result.hunks as int) + 1
}

result.added = added
result.removed = removed
result.diff = out.toString()
result.truncated = truncated
result.totalHunks = hunks.size()
return result
