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

package org.mintjams.cms.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.mintjams.cms.config.ConfigValues;
import org.mintjams.cms.config.RepositoryLocation;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/**
 * Moves the workspace databases of a stopped installation from the embedded
 * H2 databases to PostgreSQL: the content repository
 * ({@code <workspace>/var/jcr/data}) to the database named by
 * {@code jcr.yml#datasource}, and the process engine
 * ({@code <workspace>/var/bpm/data}) to the one named by {@code bpm.yml}.
 *
 * <p>The destination is read from the configuration the server itself will
 * use after the move, with the same {@code ${env.NAME}},
 * {@code ${secrets.KEY}} and {@code ENC[...]} resolution: the operator edits
 * {@code jcr.yml} and {@code bpm.yml} first, and this copies into exactly
 * the databases the server will open.
 *
 * <p>Each destination database must exist and hold no tables. The tables are
 * created with the platform's own definitions, read from the installed
 * bundles ({@code workspace-prepare.sql} of the repository runtime and the
 * PostgreSQL scripts of the bundled process engine), so they are the tables
 * the server would have created itself. Everything for one database happens
 * in one transaction: a failure leaves it as empty as it was, and the H2
 * databases are opened read-only and never changed.
 *
 * <p>The container image wraps this as {@code cms-migrate-db}; see
 * {@code documents/database-migration.md}.
 */
public final class DatabaseMigrationTool {

	private static final String USAGE = String.join("\n",
			"usage: cms-migrate-db [--workspace NAME]... [--dry-run]",
			"                      [--repository DIR] [--felix-home DIR]",
			"",
			"Copies the H2 databases of the given workspaces (default: every workspace)",
			"into the PostgreSQL databases named by their jcr.yml and bpm.yml.",
			"Stop the CMS and edit jcr.yml / bpm.yml first.",
			"",
			"  --workspace NAME  a workspace to migrate; repeat for several",
			"  --dry-run         do the whole copy, then roll it back",
			"  --repository DIR  the repository directory (default: the",
			"                    org.mintjams.jcr.repository.rootdir property,",
			"                    else ./repository)",
			"  --felix-home DIR  the runtime directory holding bundle/ (default:",
			"                    $FELIX_HOME, else the current directory)");

	/** Rows of these tables describe the running nodes, not content: never copied. */
	private static final Set<String> RUNTIME_TABLES = Set.of("jcr_cluster_nodes", "jcr_cluster_locks");

	/**
	 * The unique index the repository runtime adds after
	 * {@code workspace-prepare.sql} (JcrWorkspaceProvider#prepareInitialData).
	 */
	private static final String JCR_ACTIVE_PATH_INDEX =
			"CREATE UNIQUE INDEX IF NOT EXISTS jcr_items_active_path ON jcr_items (active_path)";

	/**
	 * The process engine's schema parts in the order the engine creates
	 * them, each with a table that exists only when that part was created.
	 */
	private static final String[][] BPM_SCHEMA_PARTS = {
			{ "engine", "act_ge_property" },
			{ "history", "act_hi_procinst" },
			{ "identity", "act_id_user" },
			{ "case.engine", "act_re_case_def" },
			{ "case.history", "act_hi_caseinst" },
			{ "decision.engine", "act_re_decision_def" },
			{ "decision.history", "act_hi_decinst" } };

	private static final int BATCH_SIZE = 1000;

	private final Path fRepositoryPath;
	private final Path fBundlePath;
	private final boolean fDryRun;
	private Driver fH2Driver;
	private Driver fPostgreSqlDriver;

	private DatabaseMigrationTool(Path repositoryPath, Path felixHome, boolean dryRun) {
		fRepositoryPath = repositoryPath;
		fBundlePath = felixHome.resolve("bundle");
		fDryRun = dryRun;
	}

	public static void main(String[] args) {
		List<String> workspaces = new ArrayList<>();
		Path repositoryPath = null;
		Path felixHome = null;
		boolean dryRun = false;
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			if ("--help".equals(arg) || "-h".equals(arg)) {
				System.out.println(USAGE);
				return;
			}
			if ("--dry-run".equals(arg)) {
				dryRun = true;
				continue;
			}
			if (i + 1 >= args.length) {
				usage("Missing value for " + arg + ".");
			}
			if ("--workspace".equals(arg)) {
				workspaces.add(args[++i]);
			} else if ("--repository".equals(arg)) {
				repositoryPath = Path.of(args[++i]);
			} else if ("--felix-home".equals(arg)) {
				felixHome = Path.of(args[++i]);
			} else {
				usage("Unknown option: " + arg);
			}
		}

		if (repositoryPath != null) {
			// Secrets (etc/secrets.yml) are found through the same location.
			RepositoryLocation.set(repositoryPath.toAbsolutePath());
		}
		repositoryPath = RepositoryLocation.get().toAbsolutePath().normalize();
		if (felixHome == null) {
			String env = System.getenv("FELIX_HOME");
			felixHome = Path.of((env == null || env.isBlank()) ? "." : env.trim());
		}

		try {
			DatabaseMigrationTool tool = new DatabaseMigrationTool(repositoryPath, felixHome.toAbsolutePath().normalize(), dryRun);
			System.exit(tool.run(workspaces) ? 0 : 1);
		} catch (MigrationException | IllegalArgumentException | IllegalStateException ex) {
			// Written for the operator (configuration and connection problems):
			// the message is the whole story.
			System.err.println("ERROR: " + describe(ex));
			System.exit(1);
		} catch (Throwable ex) {
			System.err.println("ERROR: " + ex);
			ex.printStackTrace();
			System.exit(1);
		}
	}

	private static void usage(String message) {
		System.err.println(message);
		System.err.println(USAGE);
		System.exit(2);
	}

	private boolean run(List<String> workspaceNames) throws Exception {
		Path workspacesPath = fRepositoryPath.resolve("workspaces");
		if (!Files.isDirectory(workspacesPath)) {
			throw new MigrationException("No workspaces under " + workspacesPath
					+ ". Point --repository at the repository directory.");
		}
		if (workspaceNames.isEmpty()) {
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(workspacesPath, Files::isDirectory)) {
				for (Path p : stream) {
					workspaceNames.add(p.getFileName().toString());
				}
			}
			workspaceNames.sort(null);
		}

		loadDrivers();

		// Everything is planned and checked before anything is copied, so a
		// mistake in the second workspace's configuration does not leave the
		// first one half done.
		List<Job> jobs = new ArrayList<>();
		for (String workspaceName : workspaceNames) {
			Path workspacePath = workspacesPath.resolve(workspaceName);
			if (!Files.isDirectory(workspacePath)) {
				throw new MigrationException("No workspace '" + workspaceName + "' under " + workspacesPath + ".");
			}
			System.out.println("Workspace " + workspaceName);
			Job jcr = planJcr(workspaceName, workspacePath);
			if (jcr != null) {
				jobs.add(jcr);
			}
			Job bpm = planBpm(workspaceName, workspacePath);
			if (bpm != null) {
				jobs.add(bpm);
			}
		}
		System.out.println();

		if (jobs.isEmpty()) {
			System.out.println("Nothing to migrate.");
			return true;
		}

		for (Job job : jobs) {
			job.preflight();
		}

		// Every database is copied in its own transaction, and none is
		// committed until all of them succeeded: a failure in the last
		// workspace leaves the first one empty as well, ready for another run.
		List<Job> copied = new ArrayList<>();
		try {
			for (Job job : jobs) {
				job.migrate();
				copied.add(job);
			}
		} catch (Throwable ex) {
			for (Job job : copied) {
				job.close(false);
			}
			if (ex instanceof MigrationException) {
				throw new MigrationException(ex.getMessage() + " Nothing was committed:"
						+ " every PostgreSQL database is still empty.", ex);
			}
			throw ex;
		}

		List<String> committed = new ArrayList<>();
		for (Job job : copied) {
			try {
				job.close(!fDryRun);
				committed.add(job.fLabel);
			} catch (Throwable ex) {
				for (Job other : copied) {
					if (!committed.contains(other.fLabel)) {
						other.close(false);
					}
				}
				throw new MigrationException(job.fLabel + ": the commit failed: " + describe(ex)
						+ (committed.isEmpty() || fDryRun ? "" : " Already committed: " + String.join(", ", committed)
								+ "; drop and recreate those databases before running again."), ex);
			}
		}

		System.out.println();
		if (fDryRun) {
			System.out.println("Dry run complete: every copy succeeded and was rolled back."
					+ " The PostgreSQL databases are still empty.");
		} else {
			System.out.println("Migration complete. Start the CMS: it now uses PostgreSQL."
					+ " The H2 files were not changed; keep them until the CMS runs well.");
		}
		return true;
	}

	// --- planning -----------------------------------------------------------

	private Job planJcr(String workspaceName, Path workspacePath) throws Exception {
		Path h2File = workspacePath.resolve("var/jcr/data/data.mv.db");
		Path jcrYml = workspacePath.resolve("etc/jcr/jcr.yml");
		Map<String, Object> config = loadYaml(jcrYml);
		Object datasource = (config == null) ? null : config.get("datasource");
		if (!(datasource instanceof Map)) {
			throw new MigrationException(jcrYml + " has no datasource. Set datasource.jdbcURL / username /"
					+ " password to the PostgreSQL database first (documents/database-migration.md).");
		}
		Map<?, ?> ds = (Map<?, ?>) datasource;
		Function<String, String> variables = workspaceVariables(workspaceName, workspacePath);
		Target target = new Target(
				resolve(ds.get("jdbcURL"), "jcr.yml#datasource.jdbcURL", variables),
				resolve(ds.get("username"), "jcr.yml#datasource.username", variables),
				resolve(ds.get("password"), "jcr.yml#datasource.password", variables),
				resolve(ds.get("driverClassName"), "jcr.yml#datasource.driverClassName", variables),
				"jcr.yml#datasource");

		if (!Files.isRegularFile(h2File)) {
			throw new MigrationException("No H2 database at " + h2File + ". Is --repository right?");
		}

		System.out.println("  JCR  " + h2File);
		System.out.println("    -> " + target.describe());
		return new Job(workspaceName + " / JCR", h2File, target, JobKind.JCR);
	}

	private Job planBpm(String workspaceName, Path workspacePath) throws Exception {
		Path h2File = workspacePath.resolve("var/bpm/data.mv.db");
		Path bpmYml = workspacePath.resolve("etc/bpm/bpm.yml");
		Map<String, Object> config = loadYaml(bpmYml);
		if (config != null && isFalse(config.get("enabled"))) {
			System.out.println("  BPM  skipped: bpm.yml#enabled is false");
			return null;
		}
		if (!Files.isRegularFile(h2File)) {
			System.out.println("  BPM  skipped: no H2 database at " + h2File
					+ " (the engine creates its tables on first start)");
			return null;
		}
		if (config == null || config.get("jdbcURL") == null) {
			throw new MigrationException(bpmYml + " names no jdbcURL. Set jdbcURL / username / password to the"
					+ " PostgreSQL database first (documents/database-migration.md).");
		}

		Function<String, String> variables = workspaceVariables(workspaceName, workspacePath);
		Target target = new Target(
				resolve(config.get("jdbcURL"), "bpm.yml#jdbcURL", variables),
				resolve(config.get("username"), "bpm.yml#username", variables),
				resolve(config.get("password"), "bpm.yml#password", variables),
				resolve(config.get("driverClassName"), "bpm.yml#driverClassName", variables),
				"bpm.yml");

		System.out.println("  BPM  " + h2File);
		System.out.println("    -> " + target.describe());
		return new Job(workspaceName + " / BPM", h2File, target, JobKind.BPM);
	}

	private Function<String, String> workspaceVariables(String workspaceName, Path workspacePath) {
		return name -> {
			switch (name) {
			case "workspace.name":
				return workspaceName;
			case "workspace.home":
				return workspacePath.toString();
			case "repository.home":
				return fRepositoryPath.toString();
			default:
				return System.getProperty(name);
			}
		};
	}

	private static String resolve(Object value, String source, Function<String, String> variables) {
		if (value == null) {
			return null;
		}
		String text = value.toString().trim();
		String resolved = ConfigValues.resolve(text, source, variables);
		if (resolved != null && resolved.contains("${")) {
			throw new MigrationException(source + " uses a variable this tool cannot resolve"
					+ " (only ${env.*}, ${secrets.*}, ${workspace.name}, ${workspace.home}, ${repository.home}"
					+ " and system properties are available).");
		}
		return resolved;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> loadYaml(Path path) throws IOException {
		if (!Files.isRegularFile(path)) {
			return null;
		}
		try (InputStream in = Files.newInputStream(path)) {
			Object loaded = new Load(LoadSettings.builder().build()).loadFromInputStream(in);
			return (loaded instanceof Map) ? (Map<String, Object>) loaded : null;
		}
	}

	private static boolean isFalse(Object value) {
		if (value instanceof Boolean) {
			return !((Boolean) value);
		}
		return value instanceof String && "false".equalsIgnoreCase(((String) value).trim());
	}

	// --- drivers and schema scripts -----------------------------------------

	private void loadDrivers() throws Exception {
		Path h2Jar = findBundle("h2-", "H2 database");
		Path postgreSqlJar = findBundle("postgresql-", "PostgreSQL driver");
		// The drivers are the bundles the server runs, so the H2 files are
		// read by the H2 version that wrote them.
		URLClassLoader loader = new URLClassLoader(
				new URL[] { h2Jar.toUri().toURL(), postgreSqlJar.toUri().toURL() },
				DatabaseMigrationTool.class.getClassLoader());
		fH2Driver = (Driver) Class.forName("org.h2.Driver", true, loader).getConstructor().newInstance();
		fPostgreSqlDriver = (Driver) Class.forName("org.postgresql.Driver", true, loader).getConstructor()
				.newInstance();
	}

	private Path findBundle(String prefix, String what) throws IOException {
		if (!Files.isDirectory(fBundlePath)) {
			throw new MigrationException("No bundle directory at " + fBundlePath
					+ ". Point --felix-home (or FELIX_HOME) at the CMS runtime directory.");
		}
		List<Path> found = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(fBundlePath, prefix + "*.jar")) {
			for (Path p : stream) {
				found.add(p);
			}
		}
		if (found.size() != 1) {
			throw new MigrationException((found.isEmpty() ? "No " : "More than one ") + what + " bundle ("
					+ prefix + "*.jar) in " + fBundlePath + ".");
		}
		return found.get(0);
	}

	private List<String> jcrSchemaStatements() throws IOException {
		Path jar = findBundle("org.mintjams.rt.jcr_", "repository runtime");
		List<String> statements = new ArrayList<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (String name : new String[] { "org/mintjams/rt/jcr/internal/workspace-prepare.sql",
					"org/mintjams/rt/jcr/internal/cluster/workspace-cluster-prepare.sql" }) {
				ZipEntry entry = zip.getEntry(name);
				if (entry == null) {
					throw new MigrationException(jar + " has no " + name + ".");
				}
				String text;
				try (InputStream in = zip.getInputStream(entry)) {
					text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
				}
				// Split exactly as the runtime does.
				for (String statement : text.split(";")) {
					statement = statement.trim();
					if (!statement.isEmpty()) {
						statements.add(statement);
					}
				}
			}
		}
		statements.add(JCR_ACTIVE_PATH_INDEX);
		return statements;
	}

	/** The PostgreSQL create scripts of the bundled process engine, by schema part. */
	private Map<String, String> bpmCreateScripts() throws IOException {
		Path jar = findBundle("org.camunda.bpm_", "process engine");
		Map<String, String> scripts = new LinkedHashMap<>();
		Pattern engineJar = Pattern.compile("lib/camunda-engine-\\d[^/]*\\.jar");
		Pattern script = Pattern.compile("org/camunda/bpm/engine/db/create/activiti\\.postgres\\.create\\.(.+)\\.sql");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry nested = zip.stream().filter(e -> engineJar.matcher(e.getName()).matches()).findFirst()
					.orElseThrow(() -> new MigrationException(jar + " has no lib/camunda-engine-*.jar."));
			try (ZipInputStream in = new ZipInputStream(zip.getInputStream(nested))) {
				for (ZipEntry e; (e = in.getNextEntry()) != null;) {
					Matcher m = script.matcher(e.getName());
					if (m.matches()) {
						scripts.put(m.group(1), new String(readAll(in), StandardCharsets.UTF_8));
					}
				}
			}
		}
		if (!scripts.containsKey("engine")) {
			throw new MigrationException(jar + " has no PostgreSQL create scripts.");
		}
		return scripts;
	}

	private static byte[] readAll(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		in.transferTo(out);
		return out.toByteArray();
	}

	/** Splits a process engine script: comment lines dropped, statements end with {@code ;}. */
	private static List<String> splitEngineScript(String text) {
		StringBuilder sb = new StringBuilder();
		for (String line : text.split("\\R")) {
			String trimmed = line.trim();
			if (trimmed.startsWith("--") || trimmed.startsWith("#")) {
				continue;
			}
			sb.append(line).append('\n');
		}
		List<String> statements = new ArrayList<>();
		for (String statement : sb.toString().split(";")) {
			statement = statement.trim();
			if (!statement.isEmpty()) {
				statements.add(statement);
			}
		}
		return statements;
	}

	private static final Pattern SCHEMA_LOG_VERSION = Pattern.compile(
			"insert\\s+into\\s+ACT_GE_SCHEMA_LOG\\s+values\\s*\\([^)]*'(\\d+\\.\\d+\\.\\d+)[^']*'\\s*\\)",
			Pattern.CASE_INSENSITIVE);

	private static String engineSchemaVersion(String engineScript) {
		Matcher m = SCHEMA_LOG_VERSION.matcher(engineScript);
		if (!m.find()) {
			throw new MigrationException("The process engine's create script names no schema version.");
		}
		return m.group(1);
	}

	private static int compareVersions(String a, String b) {
		String[] x = a.split("[.\\-]");
		String[] y = b.split("[.\\-]");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int p = (i < x.length) ? parseIntOrZero(x[i]) : 0;
			int q = (i < y.length) ? parseIntOrZero(y[i]) : 0;
			if (p != q) {
				return Integer.compare(p, q);
			}
		}
		return 0;
	}

	private static int parseIntOrZero(String s) {
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException ex) {
			return 0;
		}
	}

	// --- connections ---------------------------------------------------------

	private Connection openSource(Path h2File) throws SQLException {
		String base = h2File.toAbsolutePath().toString();
		base = base.substring(0, base.length() - ".mv.db".length());
		// Read-only: the H2 files stay as they were, as the way back. Lazy
		// execution streams large tables instead of buffering them.
		String url = "jdbc:h2:file:" + base + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;LAZY_QUERY_EXECUTION=TRUE";
		Properties props = new Properties();
		props.setProperty("user", "sa");
		props.setProperty("password", "");
		try {
			Connection connection = fH2Driver.connect(url, props);
			connection.setAutoCommit(false);
			return connection;
		} catch (SQLException ex) {
			if (ex.getErrorCode() == 90020 || String.valueOf(ex.getMessage()).contains("already in use")
					|| String.valueOf(ex.getMessage()).contains("locked")) {
				throw new MigrationException(h2File + " is in use. Stop the CMS before migrating.");
			}
			throw ex;
		}
	}

	private Connection openTarget(Target target) throws SQLException {
		Properties props = new Properties();
		if (target.username != null) {
			props.setProperty("user", target.username);
		}
		if (target.password != null) {
			props.setProperty("password", target.password);
		}
		props.setProperty("reWriteBatchedInserts", "true");
		props.setProperty("ApplicationName", "cms-migrate-db");
		Connection connection = fPostgreSqlDriver.connect(target.jdbcUrl, props);
		if (connection == null) {
			throw new MigrationException(target.source + ": not a PostgreSQL JDBC URL.");
		}
		connection.setAutoCommit(false);
		return connection;
	}

	// --- jobs ----------------------------------------------------------------

	private enum JobKind {
		JCR, BPM
	}

	private static final class Target {
		final String jdbcUrl;
		final String username;
		final String password;
		final String source;

		Target(String jdbcUrl, String username, String password, String driverClassName, String source) {
			if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql:")) {
				throw new MigrationException(source + " does not name a PostgreSQL database (jdbc:postgresql:...)."
						+ " Edit it to the destination first.");
			}
			if (driverClassName != null && !driverClassName.isEmpty()
					&& !"org.postgresql.Driver".equals(driverClassName)) {
				throw new MigrationException(source + ": driverClassName must be org.postgresql.Driver.");
			}
			this.jdbcUrl = jdbcUrl;
			this.username = username;
			this.password = password;
			this.source = source;
		}

		String describe() {
			// Never print a password, even one written into the URL.
			String url = jdbcUrl.replaceAll("(?i)(password=)[^&]*", "$1***");
			return url + ((username == null) ? "" : " (user " + username + ")");
		}
	}

	private final class Job {
		final String fLabel;
		final Path fH2File;
		final Target fTarget;
		final JobKind fKind;
		List<String> fSchemaStatements;
		Connection fConnection;

		Job(String label, Path h2File, Target target, JobKind kind) {
			fLabel = label;
			fH2File = h2File;
			fTarget = target;
			fKind = kind;
		}

		/** Connects to both sides and checks everything that can be checked without writing. */
		void preflight() throws Exception {
			try {
				check();
			} catch (SQLException ex) {
				throw new MigrationException(fLabel + ": " + describe(ex));
			}
		}

		private void check() throws Exception {
			try (Connection source = openSource(fH2File); Connection target = openTarget(fTarget)) {
				String schema = currentSchema(target);
				try (Statement st = target.createStatement();
						ResultSet rs = st.executeQuery("SELECT current_setting('server_encoding'),"
								+ " has_schema_privilege(current_schema(), 'CREATE'), current_user")) {
					rs.next();
					if (!"UTF8".equalsIgnoreCase(rs.getString(1))) {
						throw new MigrationException(fLabel + ": " + fTarget.describe() + " uses the "
								+ rs.getString(1) + " encoding. Create it with ENCODING 'UTF8'"
								+ " (TEMPLATE template0).");
					}
					if (!rs.getBoolean(2)) {
						throw new MigrationException(fLabel + ": " + rs.getString(3) + " may not create tables in"
								+ " the schema " + schema + " of " + fTarget.describe() + ". Make the user the"
								+ " database owner, or GRANT CREATE ON SCHEMA " + schema + ".");
					}
				}

				List<String> existing = listTables(target, schema);
				if (!existing.isEmpty()) {
					throw new MigrationException(fLabel + ": " + fTarget.describe() + " already has tables ("
							+ String.join(", ", existing.subList(0, Math.min(5, existing.size())))
							+ (existing.size() > 5 ? ", ..." : "") + "). Migrate into an empty database:"
							+ " drop and recreate it to start over.");
				}

				Map<String, String> sourceTables = tableMap(source, "PUBLIC");
				if (fKind == JobKind.JCR) {
					if (!sourceTables.containsKey("jcr_items")) {
						throw new MigrationException(fLabel + ": " + fH2File + " holds no JCR tables.");
					}
					fSchemaStatements = jcrSchemaStatements();
				} else {
					if (!sourceTables.containsKey("act_ge_property")) {
						throw new MigrationException(fLabel + ": " + fH2File + " holds no process engine tables.");
					}
					Map<String, String> scripts = bpmCreateScripts();
					String engineVersion = engineSchemaVersion(scripts.get("engine"));
					String dataVersion = sourceSchemaVersion(source, sourceTables);
					if (compareVersions(engineVersion, dataVersion) != 0) {
						throw new MigrationException(fLabel + ": the H2 database is at process engine schema "
								+ dataVersion + ", the installed engine creates " + engineVersion
								+ ". Start the CMS once on H2 with this version so the engine updates its"
								+ " schema, stop it, and run this again.");
					}
					fSchemaStatements = new ArrayList<>();
					for (String[] part : BPM_SCHEMA_PARTS) {
						if (!sourceTables.containsKey(part[1])) {
							continue;
						}
						String script = scripts.get(part[0]);
						if (script == null) {
							throw new MigrationException(fLabel + ": the process engine has no PostgreSQL"
									+ " script for '" + part[0] + "'.");
						}
						fSchemaStatements.addAll(splitEngineScript(script));
					}
				}
				target.rollback();
			}
		}

		private String sourceSchemaVersion(Connection source, Map<String, String> sourceTables) throws SQLException {
			String table = sourceTables.get("act_ge_schema_log");
			if (table == null) {
				throw new MigrationException(fLabel + ": the H2 database has no ACT_GE_SCHEMA_LOG table;"
						+ " its process engine schema predates 7.11. Upgrade on H2 first.");
			}
			String version = null;
			try (Statement st = source.createStatement();
					ResultSet rs = st.executeQuery("SELECT VERSION_ FROM " + quote(table))) {
				while (rs.next()) {
					String v = rs.getString(1);
					if (v != null && (version == null || compareVersions(v, version) > 0)) {
						version = v;
					}
				}
			}
			if (version == null) {
				throw new MigrationException(fLabel + ": ACT_GE_SCHEMA_LOG is empty.");
			}
			return version;
		}

		/**
		 * Copies into the destination and leaves its transaction open;
		 * {@link #close(boolean)} commits or rolls it back.
		 */
		void migrate() throws Exception {
			System.out.println(fLabel + (fDryRun ? " (dry run)" : ""));
			long started = System.currentTimeMillis();
			Connection target;
			try {
				target = openTarget(fTarget);
			} catch (SQLException ex) {
				throw new MigrationException(fLabel + ": " + describe(ex), ex);
			}
			fConnection = target;
			try (Connection source = openSource(fH2File)) {
				String schema = currentSchema(target);
				try (Statement st = target.createStatement()) {
					for (String statement : fSchemaStatements) {
						st.execute(statement);
					}
				}

				Map<String, String> sourceTables = tableMap(source, "PUBLIC");
				Map<String, String> targetTables = tableMap(target, schema);
				List<String> unknown = new ArrayList<>();
				for (String name : sourceTables.keySet()) {
					if (!targetTables.containsKey(name) && !RUNTIME_TABLES.contains(name)) {
						unknown.add(sourceTables.get(name));
					}
				}
				if (!unknown.isEmpty()) {
					throw new MigrationException(fLabel + ": the H2 database has tables the platform does not"
							+ " create: " + String.join(", ", unknown) + ".");
				}

				// The engine's foreign keys are dropped for the copy and
				// added back after it, which also checks every row.
				List<String[]> foreignKeys = dropForeignKeys(target, schema);

				long total = 0;
				for (Map.Entry<String, String> e : sourceTables.entrySet()) {
					if (RUNTIME_TABLES.contains(e.getKey())) {
						continue;
					}
					total += copyTable(source, e.getValue(), target, schema, targetTables.get(e.getKey()));
				}

				try (Statement st = target.createStatement()) {
					for (String[] fk : foreignKeys) {
						st.execute("ALTER TABLE " + fk[0] + " ADD CONSTRAINT " + fk[1] + " " + fk[2]);
					}
				}
				resetSequences(target, schema);

				System.out.printf(Locale.ROOT, "  %,d rows in %d tables, %.1f s%n", total,
						sourceTables.size(), (System.currentTimeMillis() - started) / 1000.0);
			} catch (Throwable ex) {
				close(false);
				String message = describe(ex);
				throw new MigrationException(message.startsWith(fLabel) ? message : fLabel + ": " + message, ex);
			}
		}

		/** Ends the open transaction: commits it, or rolls it back. */
		void close(boolean commit) {
			Connection connection = fConnection;
			fConnection = null;
			if (connection == null) {
				return;
			}
			try {
				if (commit) {
					connection.commit();
				} else {
					connection.rollback();
				}
			} catch (SQLException ex) {
				throw new MigrationException(describe(ex), ex);
			} finally {
				try {
					connection.close();
				} catch (Throwable ignore) {}
			}
		}
	}

	// --- copying -------------------------------------------------------------

	private long copyTable(Connection source, String sourceTable, Connection target, String schema,
			String targetTable) throws SQLException {
		long started = System.currentTimeMillis();
		Map<String, Integer> targetTypes = new LinkedHashMap<>();
		Map<String, String> targetTypeNames = new LinkedHashMap<>();
		Map<String, String> targetColumns = new LinkedHashMap<>();
		try (ResultSet rs = target.getMetaData().getColumns(null, schema, targetTable, "%")) {
			while (rs.next()) {
				String name = rs.getString("COLUMN_NAME");
				targetColumns.put(name.toLowerCase(Locale.ROOT), name);
				targetTypes.put(name.toLowerCase(Locale.ROOT), rs.getInt("DATA_TYPE"));
				targetTypeNames.put(name.toLowerCase(Locale.ROOT), rs.getString("TYPE_NAME"));
			}
		}

		// Rows the create scripts seed (the engine's properties and schema
		// log) are replaced by the ones of the database being moved.
		try (Statement st = target.createStatement()) {
			st.executeUpdate("DELETE FROM " + quote(targetTable));
		}

		long count = 0;
		try (Statement select = source.createStatement()) {
			select.setFetchSize(BATCH_SIZE);
			try (ResultSet rs = select.executeQuery("SELECT * FROM " + quote(sourceTable))) {
				ResultSetMetaData md = rs.getMetaData();
				int n = md.getColumnCount();
				String[] names = new String[n];
				int[] types = new int[n];
				int[] sourceTypes = new int[n];
				String[] elementTypes = new String[n];
				StringBuilder columns = new StringBuilder();
				StringBuilder params = new StringBuilder();
				for (int i = 0; i < n; i++) {
					String key = md.getColumnName(i + 1).toLowerCase(Locale.ROOT);
					String targetColumn = targetColumns.get(key);
					if (targetColumn == null) {
						throw new MigrationException(sourceTable + "." + md.getColumnName(i + 1)
								+ " has no column in the PostgreSQL table.");
					}
					names[i] = md.getColumnName(i + 1);
					types[i] = targetTypes.get(key);
					sourceTypes[i] = md.getColumnType(i + 1);
					String typeName = targetTypeNames.get(key);
					elementTypes[i] = (typeName != null && typeName.startsWith("_")) ? typeName.substring(1) : typeName;
					if (i > 0) {
						columns.append(", ");
						params.append(", ");
					}
					columns.append(quote(targetColumn));
					params.append('?');
				}

				String insert = "INSERT INTO " + quote(targetTable) + " (" + columns + ") VALUES (" + params + ")";
				try (PreparedStatement ps = target.prepareStatement(insert)) {
					int pending = 0;
					while (rs.next()) {
						for (int i = 0; i < n; i++) {
							Object value = read(rs, i + 1, sourceTypes[i]);
							try {
								bind(target, ps, i + 1, types[i], elementTypes[i], value);
							} catch (MigrationException ex) {
								throw new MigrationException(sourceTable + "." + names[i] + ", row " + (count + 1)
										+ ": " + ex.getMessage());
							}
						}
						ps.addBatch();
						count++;
						if (++pending >= BATCH_SIZE) {
							executeBatch(ps, sourceTable);
							pending = 0;
						}
						if (count % 100000 == 0) {
							System.out.printf(Locale.ROOT, "    %s: %,d rows...%n", targetTable, count);
						}
					}
					if (pending > 0) {
						executeBatch(ps, sourceTable);
					}
				}
			}
		}

		long copied;
		try (Statement st = target.createStatement();
				ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + quote(targetTable))) {
			rs.next();
			copied = rs.getLong(1);
		}
		if (copied != count) {
			throw new MigrationException(targetTable + ": read " + count + " rows from H2 but the PostgreSQL table"
					+ " holds " + copied + ".");
		}

		System.out.printf(Locale.ROOT, "  %-32s %,12d rows  %6.1f s%n", targetTable, count,
				(System.currentTimeMillis() - started) / 1000.0);
		return count;
	}

	private static void executeBatch(PreparedStatement ps, String table) throws SQLException {
		try {
			ps.executeBatch();
		} catch (SQLException ex) {
			SQLException cause = (ex.getNextException() != null) ? ex.getNextException() : ex;
			String hint = "";
			if ("54000".equals(cause.getSQLState())) {
				// program_limit_exceeded: typically an index entry over the
				// B-tree limit (about 2.7 KB after compression), which H2
				// does not have.
				hint = " A value is too large for a PostgreSQL index entry (about 2.7 KB after compression);"
						+ " H2 has no such limit, so this row cannot move as it is.";
			}
			throw new MigrationException(table + ": " + describe(cause) + hint);
		}
	}

	private static Object read(ResultSet rs, int index, int sourceType) throws SQLException {
		// Dates and times move as their local values: going through
		// java.sql.Timestamp would pass them through the JVM's time zone,
		// which shifts a time that falls into a daylight saving gap.
		switch (sourceType) {
		case Types.TIMESTAMP:
			return rs.getObject(index, LocalDateTime.class);
		case Types.TIMESTAMP_WITH_TIMEZONE:
			return rs.getObject(index, OffsetDateTime.class);
		case Types.DATE:
			return rs.getObject(index, LocalDate.class);
		case Types.TIME:
			return rs.getObject(index, LocalTime.class);
		default:
			break;
		}

		Object value = rs.getObject(index);
		if (value instanceof Array) {
			Array array = (Array) value;
			try {
				return array.getArray();
			} finally {
				array.free();
			}
		}
		if (value instanceof Blob) {
			Blob blob = (Blob) value;
			try {
				long length = blob.length();
				if (length > Integer.MAX_VALUE) {
					throw new MigrationException("a binary value over 2 GB");
				}
				return blob.getBytes(1, (int) length);
			} finally {
				blob.free();
			}
		}
		if (value instanceof Clob) {
			Clob clob = (Clob) value;
			try {
				long length = clob.length();
				if (length > Integer.MAX_VALUE) {
					throw new MigrationException("a text value over 2 G characters");
				}
				return clob.getSubString(1, (int) length);
			} finally {
				clob.free();
			}
		}
		return value;
	}

	private static void bind(Connection target, PreparedStatement ps, int index, int type, String elementType,
			Object value) throws SQLException {
		if (value == null) {
			ps.setNull(index, type);
			return;
		}

		switch (type) {
		case Types.ARRAY: {
			Object[] elements;
			if (value instanceof Object[]) {
				elements = (Object[]) value;
			} else {
				throw new MigrationException("expected an array, found " + value.getClass().getName());
			}
			for (Object element : elements) {
				if (element instanceof String) {
					checkText((String) element);
				}
			}
			ps.setArray(index, target.createArrayOf(elementType, elements));
			return;
		}
		case Types.BINARY:
		case Types.VARBINARY:
		case Types.LONGVARBINARY:
		case Types.BLOB:
			if (value instanceof byte[]) {
				ps.setBytes(index, (byte[]) value);
				return;
			}
			throw new MigrationException("expected binary data, found " + value.getClass().getName());
		case Types.BIT:
		case Types.BOOLEAN:
			if (value instanceof Boolean) {
				ps.setBoolean(index, (Boolean) value);
			} else if (value instanceof Number) {
				ps.setBoolean(index, ((Number) value).intValue() != 0);
			} else {
				ps.setBoolean(index, Boolean.parseBoolean(value.toString().trim()));
			}
			return;
		case Types.CHAR:
		case Types.VARCHAR:
		case Types.LONGVARCHAR:
		case Types.NCHAR:
		case Types.NVARCHAR:
		case Types.LONGNVARCHAR:
		case Types.CLOB:
		case Types.NCLOB: {
			String text = (value instanceof byte[]) ? new String((byte[]) value, StandardCharsets.UTF_8)
					: value.toString();
			checkText(text);
			ps.setString(index, text);
			return;
		}
		case Types.TINYINT:
		case Types.SMALLINT:
		case Types.INTEGER:
		case Types.BIGINT:
			if (value instanceof Boolean) {
				ps.setInt(index, ((Boolean) value) ? 1 : 0);
				return;
			}
			ps.setObject(index, value, type);
			return;
		default:
			ps.setObject(index, value);
		}
	}

	/** PostgreSQL text cannot hold U+0000; say where it is instead of failing the batch. */
	private static void checkText(String text) {
		if (text.indexOf('\0') >= 0) {
			throw new MigrationException("the text holds a NUL character (U+0000), which PostgreSQL cannot store");
		}
	}

	private static List<String[]> dropForeignKeys(Connection target, String schema) throws SQLException {
		List<String[]> foreignKeys = new ArrayList<>();
		try (PreparedStatement ps = target.prepareStatement(
				"SELECT c.conrelid::regclass::text, quote_ident(c.conname), pg_get_constraintdef(c.oid)"
						+ " FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace"
						+ " WHERE c.contype = 'f' AND n.nspname = ? ORDER BY 1, 2")) {
			ps.setString(1, schema);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					foreignKeys.add(new String[] { rs.getString(1), rs.getString(2), rs.getString(3) });
				}
			}
		}
		try (Statement st = target.createStatement()) {
			for (String[] fk : foreignKeys) {
				st.execute("ALTER TABLE " + fk[0] + " DROP CONSTRAINT " + fk[1]);
			}
		}
		return foreignKeys;
	}

	/** Moves identity and serial sequences past the copied values. */
	private static void resetSequences(Connection target, String schema) throws SQLException {
		List<String[]> columns = new ArrayList<>();
		try (PreparedStatement ps = target.prepareStatement(
				"SELECT table_name, column_name FROM information_schema.columns WHERE table_schema = ?"
						+ " AND (is_identity = 'YES' OR column_default LIKE 'nextval(%')")) {
			ps.setString(1, schema);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					columns.add(new String[] { rs.getString(1), rs.getString(2) });
				}
			}
		}
		for (String[] c : columns) {
			String table = quote(schema) + "." + quote(c[0]);
			try (PreparedStatement ps = target.prepareStatement(
					"SELECT setval(pg_get_serial_sequence(?, ?), (SELECT COALESCE(MAX(" + quote(c[1]) + "), 0) FROM "
							+ table + ") + 1, false)")) {
				ps.setString(1, table);
				ps.setString(2, c[1]);
				ps.execute();
			}
		}
	}

	// --- metadata helpers -------------------------------------------------------

	private static String currentSchema(Connection connection) throws SQLException {
		try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT current_schema()")) {
			rs.next();
			String schema = rs.getString(1);
			if (schema == null) {
				throw new MigrationException("The PostgreSQL user has no schema to create tables in"
						+ " (current_schema() is null). Grant it one, e.g. the database's public schema.");
			}
			return schema;
		}
	}

	private static List<String> listTables(Connection connection, String schema) throws SQLException {
		return new ArrayList<>(tableMap(connection, schema).values());
	}

	/** Lower-cased table name to the name as the database spells it, sorted. */
	private static Map<String, String> tableMap(Connection connection, String schema) throws SQLException {
		Map<String, String> tables = new TreeMap<>();
		DatabaseMetaData md = connection.getMetaData();
		try (ResultSet rs = md.getTables(null, schema, "%", new String[] { "TABLE" })) {
			while (rs.next()) {
				String name = rs.getString("TABLE_NAME");
				tables.put(name.toLowerCase(Locale.ROOT), name);
			}
		}
		return tables;
	}

	private static String quote(String identifier) {
		return "\"" + identifier.replace("\"", "\"\"") + "\"";
	}

	private static String describe(Throwable ex) {
		String message = ex.getMessage();
		return (message == null || message.isBlank()) ? ex.toString() : message;
	}

	/** A failure with a message written for the operator. */
	private static final class MigrationException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		MigrationException(String message) {
			super(message);
		}

		MigrationException(String message, Throwable cause) {
			super(message, cause);
		}
	}

}
