追加ルールを反映した `AGENTS.md` 全文の最新版は以下のとおりです：

---

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

* **Java Fields**:
  Prefix all class-level fields with **`f`** followed by **PascalCase**.
  Example: `fCloseRequested`, `fAgentRegistry`.

* **Directories and Files**: Use **lowercase** with hyphen separators (e.g., `remote-agents/`, `agent-config.json`).

---

## Commit Messages

* **Language**:
  All commit messages must be written in **English**.

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

## Additional Guidelines

* Keep agent logic **modular** and **loosely coupled**.
* Add comments for any **non-obvious logic** or side effects.
* Do not commit build artifacts (e.g., `.class`, `.log`, `.jar`, etc.).
* Prefer **composition over inheritance** when extending agent functionality.
