# AGENTS.md

This document describes conventions and policies used for development agents in this repository.

---

## Coding Style

* **Indentation**:
  All source code files must use **tabs** (`\t`) for indentation. Do not use spaces.

* **File Encoding**:
  Use UTF-8 without BOM.

* **Line Endings**:
  Use LF (`\n`) for all files, regardless of operating system.

---

## Naming Conventions

* **Classes & Interfaces**: Use **PascalCase** (e.g., `AgentManager`, `RemoteAgentInterface`).

* **Variables & Methods**: Use **camelCase** (e.g., `startAgent()`, `agentList`).

* **Constants**: Use **UPPER\_SNAKE\_CASE** (e.g., `DEFAULT_TIMEOUT_MS`).

* **Directories and Files**: Use **lowercase** with hyphen separators (e.g., `remote-agents/`, `agent-config.json`).

---

## Field Naming Convention

All class fields must be named using PascalCase and prefixed with `f`.

### Examples

```java
private String fUserName;
private int fAge;
private boolean fIsActive;
```

### Purpose

This naming convention makes it easier to distinguish between:

* Local variables within methods
* Constructor or setter parameters
* Class-level fields

### Exception: `serialVersionUID`

The `serialVersionUID` field is a special field used by Java's `Serializable` interface.
It **must be declared with the exact name `serialVersionUID`** as required by the Java language specification.

```java
private static final long serialVersionUID = 1L;
```

Do **not** rename it to follow the `f` prefix rule (e.g., `fSerialVersionUID`), as doing so will break Java's serialization mechanism.

---

## Commit Messages

* **Language**:
  All commit messages must be written in **English**.
  The **title and description** of all Pull Requests must also be written in **English**.

* **Format**:
  Follow this structure whenever possible:

  ```
  <type>: <short summary>

  <optional body>
  ```

  Example:

  ```
  fix: handle null pointer in AgentManager

  This fixes a null pointer exception that occurred when the agent ID was missing.
  ```

  Common `<type>` values include: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`.

---

## Directory Structure

If applicable, describe the structure of agent-related code:

```
agents/
  ├── base/        # Base agent interfaces and shared logic
  ├── local/       # Local, non-networked agents
  └── remote/      # Agents using network APIs
```

---

## Testing and Validation

* All new agent code must include **unit tests**.
* Use consistent mocking/stubbing techniques for agent dependencies.
* Validate key behavior using assertions.

---

## OSGi Bundle Libraries

Each bundle may include a `lib/` directory containing JAR files required for proper execution within the OSGi runtime environment.

These JARs are not general-purpose dependencies but are **essential resources** specific to the corresponding bundle and are kept in version control.
For example, the `bundles/groovy/lib/` folder includes the full set of Apache Groovy runtime libraries needed by the Groovy bundle.

JAR files located under `bundles/**/lib/` are therefore explicitly allowed.

**Do not remove or alter these JAR files** unless you fully understand their role and have verified the impact on the OSGi runtime.
Removing them may cause runtime errors or prevent the bundle from starting correctly.

---

## Additional Guidelines

* Keep agent logic **modular** and **loosely coupled**.
* Add comments for any **non-obvious logic** or side effects.
* Do not commit build artifacts (e.g., `.class`, `.log`, `.jar`, etc.) outside of `bundles/**/lib/`.
* Prefer **composition over inheritance** when extending agent functionality.
