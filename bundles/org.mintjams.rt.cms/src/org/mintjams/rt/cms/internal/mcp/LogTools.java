/*
 * Copyright (c) 2026 MintJams Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.mintjams.rt.cms.internal.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DateFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.jcr.Session;

import org.mintjams.jcr.cluster.ClusterCoordinator;
import org.mintjams.jcr.cluster.ClusterTransport;
import org.mintjams.rt.cms.internal.CmsService;
import org.mintjams.rt.cms.internal.mcp.McpTool.Args;
import org.mintjams.rt.cms.internal.mcp.McpTool.Schema;
import org.mintjams.tools.adapter.Adaptables;

import com.google.gson.JsonObject;

/**
 * {@code search_logs}: the entries of the server log, on every node of a
 * cluster, for administrators.
 *
 * <p>The log is the file the log writer keeps per day
 * ({@code ROOT.yyyy-MM-dd.log} under {@code org.mintjams.log.rootdir}); nothing
 * else on the file system can be named. An entry is what
 * {@code java.util.logging.SimpleFormatter} wrote: a line with the time and the
 * source, a line starting with the level, and any further lines (a stack
 * trace). The level names and the AM/PM marker are this JVM's, which is also
 * the JVM that wrote them.
 *
 * <p>The logs are not repository content, so the repository ACLs say nothing
 * about them: the tool is offered to every caller and refuses, with a message
 * the model can pass on, a caller who is not an administrator. In a cluster the
 * node that takes the call reads its own file and asks the other nodes for
 * theirs over the cluster connections; each answer fits one cluster frame, so
 * a node returns its most recent matching entries and says from which line to
 * ask for older ones.
 */
public final class LogTools {

	/** The cluster request a node answers with its own matching entries. */
	static final String SEARCH_REQUEST = "cms.logs.search";

	private static final long REQUEST_TIMEOUT_MS = 20_000;
	private static final int DEFAULT_LIMIT = 50;
	private static final int MAX_LIMIT = 200;
	private static final int MAX_PATTERN_CHARS = 1_000;
	/** Characters of one entry kept for matching; a longer entry is matched on its head. */
	private static final int MAX_SCANNED_ENTRY_CHARS = 64 * 1024;
	/** Characters of one entry returned. */
	private static final int MAX_ENTRY_CHARS = 4_000;
	/**
	 * UTF-8 bytes of entries one node returns. A remote node's answer travels
	 * in one cluster frame (64 KB with the envelope), and the local node keeps
	 * to the same size so that every node pages the same way.
	 */
	private static final int MAX_ANSWER_BYTES = 40 * 1024;
	/** Bytes an entry costs in the answer beyond its text (field names, line, time, level). */
	private static final int ENTRY_OVERHEAD_BYTES = 64;
	private static final int MAX_LISTED_DATES = 14;

	private static final String FILE_PREFIX = "ROOT.";
	private static final String FILE_SUFFIX = ".log";
	private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2}):(\\d{2})");
	private static final DateTimeFormatter TIME_OF_DAY = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final Map<String, Level> LEVELS = levelNames();
	private static final List<Level> LEVEL_CHOICES = List.of(Level.SEVERE, Level.WARNING, Level.INFO, Level.FINE);

	private LogTools() {}

	static List<McpTool> all() {
		return Collections.singletonList(searchLogs());
	}

	/**
	 * Lets the other nodes of the cluster search this node's log. Closing the
	 * returned handle removes the handler.
	 */
	public static AutoCloseable registerClusterHandler(ClusterTransport transport) {
		return transport.setRequestHandler(SEARCH_REQUEST, (fromNodeId, payload) -> searchLocal(Query.fromMap(payload)));
	}

	private static McpTool searchLogs() {
		return McpTool.named("search_logs").title("Search server logs")
				.description("Search the server log for administrators: the entries the server wrote on one day",
						"(one file per day), on every node of a cluster, each with its node, line number, time,",
						"level and text. Only administrators of the server may read it; for anyone else the call",
						"fails with a message saying so, which is the answer to give the user. Filter by a regular",
						"expression (matched against the whole entry, stack trace included; prefix (?i) to ignore",
						"case), by minimum level, and by a time range within the day. The most recent matching",
						"entries come back, oldest first; 'matches' counts all of them, so a search from a given time",
						"with 0 matches confirms that a message is no longer logged. When a node has more matches",
						"than it returned, its 'olderBefore' is the line to pass as 'before', together with 'node', to",
						"read the earlier ones. Times are the server's local time ('timeZone' and 'now' in the",
						"result). A node that is down or does not answer is listed with the reason, never left out.")
				.optional("date", Schema.string("Day of the log, YYYY-MM-DD in the server's time zone. Default: today"))
				.optional("pattern", Schema.string("Java regular expression an entry must contain"))
				.optional("level", Schema.choice("Lowest level to return: SEVERE (errors), WARNING, INFO, FINE (debug)"
						+ " or ALL", "ALL", "ALL", "SEVERE", "WARNING", "INFO", "FINE"))
				.optional("since", Schema.string("Earliest time of day, HH:mm or HH:mm:ss, inclusive"))
				.optional("until", Schema.string("Latest time of day, HH:mm or HH:mm:ss, exclusive"))
				.optional("node", Schema.string("Search only this cluster node, by node id or host name. Default: every"
						+ " node"))
				.optional("limit", Schema.integer("Most recent matching entries to return per node", DEFAULT_LIMIT, 1,
						MAX_LIMIT))
				.optional("before", Schema.integer("Only entries that start before this line (a node's olderBefore);"
						+ " requires node in a cluster", 0, 0, Integer.MAX_VALUE))
				.handler(LogTools::search).build();
	}

	private static McpToolResult search(JsonObject arguments, McpCallContext context) throws Exception {
		Query query = Query.fromArguments(arguments);
		String nodeArgument = Args.optString(arguments, "node", null);

		Map<String, Object> result = new LinkedHashMap<>();
		List<Map<String, Object>> nodes = new ArrayList<>();
		Session session = context.login();
		try {
			org.mintjams.jcr.Session jcrSession = Adaptables.getAdapter(session, org.mintjams.jcr.Session.class);
			if (jcrSession == null || !(jcrSession.isAdmin() || jcrSession.isService())) {
				throw new McpToolException("Reading the server logs requires administrator rights. This connection is"
						+ " signed in as '" + context.getUserId() + "', who is not an administrator of the server,"
						+ " so the logs cannot be read through it. An administrator can read them with this tool.");
			}

			ZonedDateTime now = ZonedDateTime.now();
			result.put("now", now.truncatedTo(ChronoUnit.SECONDS).toOffsetDateTime().toString());
			result.put("timeZone", ZoneId.systemDefault().getId());
			result.put("date", query.fDate.toString());

			ClusterCoordinator coordinator = Adaptables.getAdapter(session, ClusterCoordinator.class);
			if (coordinator == null || !coordinator.isClusterEnabled()) {
				if (nodeArgument != null && !nodeArgument.isEmpty()) {
					throw new McpToolException("This server does not run as a cluster; leave out node.");
				}
				Map<String, Object> answer = new LinkedHashMap<>();
				answer.putAll(searchLocal(query));
				nodes.add(answer);
			} else {
				nodes.addAll(searchCluster(coordinator, query, nodeArgument));
			}
		} finally {
			session.logout();
		}

		result.put("nodes", nodes);
		return McpToolResult.json(result);
	}

	private static List<Map<String, Object>> searchCluster(ClusterCoordinator coordinator, Query query,
			String nodeArgument) throws Exception {
		String self = coordinator.getNodeId();
		List<ClusterCoordinator.Member> members = new ArrayList<>();
		List<String> known = new ArrayList<>();
		for (ClusterCoordinator.Member member : coordinator.listMembers()) {
			known.add(member.getNodeId() + " (" + member.getHostName() + ")");
			if (nodeArgument == null || nodeArgument.isEmpty() || nodeArgument.equals(member.getNodeId())
					|| nodeArgument.equalsIgnoreCase(member.getHostName())) {
				members.add(member);
			}
		}
		if (members.isEmpty()) {
			throw new McpToolException("No cluster node is called '" + nodeArgument + "'. The nodes are: "
					+ String.join(", ", known) + ".");
		}
		if (query.fBefore > 0 && members.size() > 1) {
			throw new McpToolException("before is a line number in one node's log; give node as well.");
		}

		ClusterTransport transport = CmsService.getClusterTransport();
		List<Map<String, Object>> answers = new ArrayList<>();
		List<Future<Map<String, Object>>> pending = new ArrayList<>();
		int remote = 0;
		for (ClusterCoordinator.Member member : members) {
			if (!member.getNodeId().equals(self) && member.isAlive() && transport != null) {
				remote++;
			}
		}
		ExecutorService executor = (remote == 0) ? null : Executors.newFixedThreadPool(Math.min(remote, 8), r -> {
			Thread thread = new Thread(r, "search_logs");
			thread.setDaemon(true);
			return thread;
		});
		try {
			Map<String, Object> payload = query.toMap();
			for (ClusterCoordinator.Member member : members) {
				Map<String, Object> answer = new LinkedHashMap<>();
				answer.put("node", member.getNodeId());
				answer.put("host", member.getHostName());
				answers.add(answer);
				if (member.getNodeId().equals(self)) {
					answer.put("self", true);
					pending.add(null);
				} else if (!member.isAlive()) {
					answer.put("error", "The node is not running: its last heartbeat was at "
							+ ZonedDateTime.ofInstant(Instant.ofEpochMilli(member.getLastHeartbeat()), ZoneId.systemDefault())
									.truncatedTo(ChronoUnit.SECONDS).toOffsetDateTime()
							+ ". Its log cannot be read until it runs again.");
					pending.add(null);
				} else if (transport == null) {
					answer.put("error", "This node has no connection to the other nodes, so their logs cannot be read"
							+ " from here.");
					pending.add(null);
				} else {
					String nodeId = member.getNodeId();
					pending.add(executor.submit(() -> transport.request(nodeId, SEARCH_REQUEST, payload,
							REQUEST_TIMEOUT_MS)));
				}
			}

			for (int i = 0; i < members.size(); i++) {
				Map<String, Object> answer = answers.get(i);
				if (Boolean.TRUE.equals(answer.get("self"))) {
					answer.putAll(searchLocal(query));
					continue;
				}
				Future<Map<String, Object>> future = pending.get(i);
				if (future == null) {
					continue;
				}
				try {
					answer.putAll(wholeNumbers(future.get()));
				} catch (java.util.concurrent.ExecutionException ex) {
					Throwable cause = (ex.getCause() == null) ? ex : ex.getCause();
					String message = String.valueOf(cause.getMessage());
					if (message.contains("no handler")) {
						message += " The node probably runs an older version without log search.";
					}
					answer.put("error", "The node did not answer: " + message);
				}
			}
		} finally {
			if (executor != null) {
				executor.shutdownNow();
			}
		}
		return answers;
	}

	/** This node's matching entries, as one answer (also what a remote node sends back). */
	static Map<String, Object> searchLocal(Query query) throws IOException {
		Path directory = CmsService.getLogPath();
		Path file = directory.resolve(FILE_PREFIX + query.fDate + FILE_SUFFIX);
		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("file", file.getFileName().toString());
		if (!Files.isRegularFile(file)) {
			answer.put("error", "There is no log for " + query.fDate + " on this node.");
			answer.put("dates", listDates(directory));
			return answer;
		}
		answer.put("size", Files.size(file));

		Deque<Entry> kept = new ArrayDeque<>();
		long matches = 0;
		Entry last = null;
		boolean stopped = false;
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
			EntryReader entries = new EntryReader(reader);
			for (Entry entry; (entry = entries.next()) != null;) {
				if (query.fBefore > 0 && entry.fLine >= query.fBefore) {
					stopped = true;
					break;
				}
				last = entry;
				if (!query.accepts(entry)) {
					continue;
				}
				matches++;
				kept.addLast(entry);
				if (kept.size() > query.fLimit) {
					kept.removeFirst();
				}
			}
		}

		// The newest entries first, until the answer is full.
		List<Map<String, Object>> returned = new ArrayList<>();
		long bytes = 0;
		for (Iterator<Entry> i = kept.descendingIterator(); i.hasNext();) {
			Map<String, Object> entry = i.next().toMap();
			int size = ((String) entry.get("text")).getBytes(StandardCharsets.UTF_8).length + ENTRY_OVERHEAD_BYTES;
			if (!returned.isEmpty() && bytes + size > MAX_ANSWER_BYTES) {
				break;
			}
			bytes += size;
			returned.add(entry);
		}
		Collections.reverse(returned);

		answer.put("matches", matches);
		if (returned.size() < matches) {
			answer.put("olderBefore", returned.get(0).get("line"));
		}
		if (!stopped && last != null && last.fTime != null) {
			answer.put("lastEntryAt", TIME_OF_DAY.format(last.fTime));
		}
		answer.put("entries", returned);
		return answer;
	}

	private static List<String> listDates(Path directory) {
		List<String> dates = new ArrayList<>();
		if (!Files.isDirectory(directory)) {
			return dates;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, FILE_PREFIX + "*" + FILE_SUFFIX)) {
			for (Path file : files) {
				String name = file.getFileName().toString();
				String date = name.substring(FILE_PREFIX.length(), name.length() - FILE_SUFFIX.length());
				try {
					LocalDate.parse(date);
					dates.add(date);
				} catch (DateTimeParseException ignore) {}
			}
		} catch (IOException ignore) {}
		Collections.sort(dates, Collections.reverseOrder());
		return (dates.size() > MAX_LISTED_DATES) ? new ArrayList<>(dates.subList(0, MAX_LISTED_DATES)) : dates;
	}

	/** Remote answers come back from JSON with every number a double; whole ones are shown as integers. */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> wholeNumbers(Map<String, Object> map) {
		Map<String, Object> converted = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : map.entrySet()) {
			converted.put(e.getKey(), wholeNumber(e.getValue()));
		}
		return converted;
	}

	@SuppressWarnings("unchecked")
	private static Object wholeNumber(Object value) {
		if (value instanceof Number && !(value instanceof Long || value instanceof Integer)) {
			double d = ((Number) value).doubleValue();
			if (d == Math.rint(d) && Math.abs(d) < 1e15) {
				return (long) d;
			}
			return value;
		}
		if (value instanceof Map) {
			return wholeNumbers((Map<String, Object>) value);
		}
		if (value instanceof List) {
			List<Object> list = new ArrayList<>();
			for (Object item : (List<Object>) value) {
				list.add(wholeNumber(item));
			}
			return list;
		}
		return value;
	}

	private static Map<String, Level> levelNames() {
		Map<String, Level> names = new HashMap<>();
		for (Level level : new Level[] { Level.SEVERE, Level.WARNING, Level.INFO, Level.CONFIG, Level.FINE,
				Level.FINER, Level.FINEST }) {
			names.put(level.getName(), level);
			names.put(level.getLocalizedName(), level);
		}
		return Collections.unmodifiableMap(names);
	}

	/** The level an entry's second line starts with ({@code "INFO: ..."}), or {@code null}. */
	private static Level levelOf(String line) {
		int colon = line.indexOf(": ");
		if (colon <= 0 || colon > 32) {
			return null;
		}
		return LEVELS.get(line.substring(0, colon));
	}

	/**
	 * The time of day in an entry's first line. The default format writes it
	 * as a 12-hour clock with this JVM's AM/PM marker after it; a format
	 * without a marker is read as a 24-hour clock.
	 */
	private static LocalTime timeOf(String header) {
		Matcher m = TIME.matcher(header);
		if (!m.find()) {
			return null;
		}
		int hour = Integer.parseInt(m.group(1));
		int minute = Integer.parseInt(m.group(2));
		int second = Integer.parseInt(m.group(3));
		String rest = header.substring(m.end()).trim();
		Boolean pm = null;
		for (String[] markers : new String[][] {
				DateFormatSymbols.getInstance(Locale.getDefault(Locale.Category.FORMAT)).getAmPmStrings(),
				new String[] { "AM", "PM" } }) {
			if (rest.regionMatches(true, 0, markers[1], 0, markers[1].length())) {
				pm = Boolean.TRUE;
				break;
			}
			if (rest.regionMatches(true, 0, markers[0], 0, markers[0].length())) {
				pm = Boolean.FALSE;
				break;
			}
		}
		if (pm != null) {
			if (hour == 12) {
				hour = 0;
			}
			if (pm) {
				hour += 12;
			}
		}
		if (hour > 23 || minute > 59 || second > 59) {
			return null;
		}
		return LocalTime.of(hour, minute, second);
	}

	/** What to search for; travels to the other nodes as a map. */
	static final class Query {
		private LocalDate fDate;
		private Pattern fPattern;
		private Level fLevel;
		private LocalTime fSince;
		private LocalTime fUntil;
		private int fLimit = DEFAULT_LIMIT;
		private int fBefore;

		static Query fromArguments(JsonObject arguments) throws McpToolException {
			Map<String, Object> map = new HashMap<>();
			map.put("date", Args.optString(arguments, "date", null));
			map.put("pattern", Args.optString(arguments, "pattern", null));
			map.put("level", Args.optString(arguments, "level", null));
			map.put("since", Args.optString(arguments, "since", null));
			map.put("until", Args.optString(arguments, "until", null));
			map.put("limit", Args.optInt(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT));
			map.put("before", Args.optInt(arguments, "before", 0, 0, Integer.MAX_VALUE));
			return fromMap(map);
		}

		static Query fromMap(Map<String, Object> map) throws McpToolException {
			Query query = new Query();
			String date = string(map, "date");
			try {
				query.fDate = (date == null) ? LocalDate.now() : LocalDate.parse(date);
			} catch (DateTimeParseException ex) {
				throw new McpToolException("date must be a day written as YYYY-MM-DD, such as 2026-11-15.");
			}

			String pattern = string(map, "pattern");
			if (pattern != null) {
				if (pattern.length() > MAX_PATTERN_CHARS) {
					throw new McpToolException("pattern is longer than " + MAX_PATTERN_CHARS + " characters.");
				}
				try {
					query.fPattern = Pattern.compile(pattern);
				} catch (PatternSyntaxException ex) {
					throw new McpToolException("pattern is not a valid regular expression: " + ex.getDescription()
							+ " near index " + ex.getIndex() + ".");
				}
			}

			String level = string(map, "level");
			if (level != null && !level.equalsIgnoreCase("ALL")) {
				for (Level choice : LEVEL_CHOICES) {
					if (choice.getName().equalsIgnoreCase(level)) {
						query.fLevel = choice;
					}
				}
				if (query.fLevel == null) {
					throw new McpToolException("level must be one of ALL, SEVERE, WARNING, INFO, FINE.");
				}
			}

			query.fSince = time(map, "since");
			query.fUntil = time(map, "until");
			if (query.fSince != null && query.fUntil != null && !query.fSince.isBefore(query.fUntil)) {
				throw new McpToolException("since must be earlier than until.");
			}

			Object limit = map.get("limit");
			if (limit instanceof Number) {
				query.fLimit = Math.max(1, Math.min(MAX_LIMIT, ((Number) limit).intValue()));
			}
			Object before = map.get("before");
			if (before instanceof Number) {
				query.fBefore = Math.max(0, ((Number) before).intValue());
			}
			return query;
		}

		Map<String, Object> toMap() {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("date", fDate.toString());
			if (fPattern != null) {
				map.put("pattern", fPattern.pattern());
			}
			if (fLevel != null) {
				map.put("level", fLevel.getName());
			}
			if (fSince != null) {
				map.put("since", fSince.toString());
			}
			if (fUntil != null) {
				map.put("until", fUntil.toString());
			}
			map.put("limit", fLimit);
			if (fBefore > 0) {
				map.put("before", fBefore);
			}
			return map;
		}

		boolean accepts(Entry entry) {
			if (fLevel != null && (entry.fLevel == null || entry.fLevel.intValue() < fLevel.intValue())) {
				return false;
			}
			if (fSince != null || fUntil != null) {
				if (entry.fTime == null) {
					return false;
				}
				if (fSince != null && entry.fTime.isBefore(fSince)) {
					return false;
				}
				if (fUntil != null && !entry.fTime.isBefore(fUntil)) {
					return false;
				}
			}
			return fPattern == null || fPattern.matcher(entry.fText).find();
		}

		private static String string(Map<String, Object> map, String name) throws McpToolException {
			Object value = map.get(name);
			if (value == null) {
				return null;
			}
			if (!(value instanceof String)) {
				throw new McpToolException(name + " must be a string.");
			}
			String s = ((String) value).trim();
			return s.isEmpty() ? null : s;
		}

		private static LocalTime time(Map<String, Object> map, String name) throws McpToolException {
			String value = string(map, name);
			if (value == null) {
				return null;
			}
			try {
				return LocalTime.parse(value, DateTimeFormatter.ISO_LOCAL_TIME);
			} catch (DateTimeParseException ex) {
				throw new McpToolException(name + " must be a time of day written as HH:mm or HH:mm:ss, such as"
						+ " 14:30.");
			}
		}
	}

	/** One log entry: its first line's number, time and level, and its text (capped). */
	static final class Entry {
		private final int fLine;
		private final LocalTime fTime;
		private final Level fLevel;
		private final StringBuilder fText = new StringBuilder();
		private int fDroppedChars;

		private Entry(int line, LocalTime time, Level level) {
			fLine = line;
			fTime = time;
			fLevel = level;
		}

		private void append(String line) {
			int room = MAX_SCANNED_ENTRY_CHARS - fText.length();
			int length = line.length() + ((fText.length() == 0) ? 0 : 1);
			if (room >= length) {
				if (fText.length() > 0) {
					fText.append('\n');
				}
				fText.append(line);
			} else {
				fDroppedChars += length;
			}
		}

		Map<String, Object> toMap() {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("line", fLine);
			if (fTime != null) {
				map.put("time", TIME_OF_DAY.format(fTime));
			}
			if (fLevel != null) {
				map.put("level", fLevel.getName());
			}
			int omitted = fDroppedChars;
			String text = fText.toString();
			if (text.length() > MAX_ENTRY_CHARS) {
				omitted += text.length() - MAX_ENTRY_CHARS;
				text = text.substring(0, MAX_ENTRY_CHARS);
			}
			if (omitted > 0) {
				text += "\n… (" + omitted + " more characters)";
			}
			map.put("text", text);
			return map;
		}
	}

	/**
	 * Splits the file into entries. A line that starts with a level name
	 * begins an entry together with the line before it (the time and the
	 * source); every other line belongs to the entry being read. Lines before
	 * the first entry form an entry without a level or time.
	 */
	private static final class EntryReader {
		private final BufferedReader fReader;
		private int fLineNumber;
		private String fPending;
		private int fPendingLine;
		private Entry fCurrent;
		private boolean fEnd;

		private EntryReader(BufferedReader reader) {
			fReader = reader;
		}

		Entry next() throws IOException {
			while (!fEnd) {
				String line = fReader.readLine();
				if (line == null) {
					fEnd = true;
					if (fPending != null) {
						append(fPending, fPendingLine);
						fPending = null;
					}
					Entry done = fCurrent;
					fCurrent = null;
					return done;
				}
				fLineNumber++;

				Level level = (fPending == null) ? null : levelOf(line);
				if (level != null) {
					Entry done = fCurrent;
					fCurrent = new Entry(fPendingLine, timeOf(fPending), level);
					fCurrent.append(fPending);
					fCurrent.append(line);
					fPending = null;
					if (done != null) {
						return done;
					}
					continue;
				}

				if (fPending != null) {
					append(fPending, fPendingLine);
				}
				fPending = line;
				fPendingLine = fLineNumber;
			}
			return null;
		}

		private void append(String line, int lineNumber) {
			if (fCurrent == null) {
				fCurrent = new Entry(lineNumber, null, null);
			}
			fCurrent.append(line);
		}
	}

}
