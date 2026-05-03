# AGENTS.md — Quarkus Project Instructions

This is a Quarkus application. Follow these rules when working on this project.

## CRITICAL — Extension-First Rule (NEVER skip this)

**STOP before writing ANY code.** For every feature or capability the user requests:

1. **Search for Quarkus extensions** that provide the capability using `quarkus/searchDocs` and `quarkus/searchTools query='extension'`.
2. **Present ALL matching options to the user** with a recommended default marked. Examples:
   - User asks for REST → present: **quarkus-rest** (recommended), resteasy-classic, spring-web
   - User asks for web UI → present: **Qute** (recommended), Web Bundler, Quinoa, Web Dependency Locator
   - User asks for persistence → present: **Hibernate ORM with Panache** (recommended), Hibernate Reactive, JDBC directly
   - User asks for security → present: **OIDC** (recommended), Security JDBC, Security JPA, Security Properties
3. **Wait for the user to choose** before proceeding. Do NOT silently pick an extension.
4. **Load skills** with `quarkus/skills` for the chosen extension BEFORE writing any code.

Skipping any of these steps is a violation. NEVER implement a feature by hand-coding HTML, JavaScript, REST endpoints, or other functionality when a Quarkus extension exists for it.

## Required Workflow

1. **Use quarkus/update (via subagent) when returning to this project** — checks if the Quarkus version is up-to-date and suggests upgrades.
2. **Use quarkus/skills BEFORE writing any code or tests** — it contains extension-specific patterns, testing approaches, and common pitfalls that prevent mistakes. Call this EVERY time you are about to add or modify a feature, not just at project creation.
3. **Use quarkus/searchDocs for Quarkus documentation** — do NOT use generic documentation tools (Context7, web search). The Quarkus doc search is version-aware and more accurate.
4. **Use quarkus/searchTools to discover Dev MCP tools** on the running app for testing, config changes, and extension management.
5. **Use quarkus/callTool to invoke Dev MCP tools** — run tests, add extensions, update configuration. Do NOT run Maven/Gradle commands manually.
6. **After code changes, trigger a reload** via `quarkus/callTool` with toolName `devui-logstream_forceRestart`. Do NOT restart the app manually.
7. **After pom.xml / build.gradle changes** (adding dependencies or extensions), you MUST do a full `quarkus/stop` + `quarkus/start` cycle. A `forceRestart` only recompiles source files — it does NOT re-resolve dependencies.

## Rules

- NEVER implement features manually when a Quarkus extension exists — search for and add the right extension first.
- NEVER silently pick an extension when multiple options exist — ALWAYS present options to the user and wait for their choice.
- NEVER write code for a feature without first loading its skill via `quarkus/skills`.
- ALWAYS write tests for every feature — no exceptions.
- ALWAYS keep README.md updated with app description, features, endpoints, and Quarkus guide links.
- ALWAYS summarize after completing work — when you finish building an app, adding a feature, or completing a task, provide a clear summary of what was done (files created/modified, endpoints added, extensions used, etc.) and suggest logical next steps the user might want to take (e.g. adding security, observability, persistence, testing improvements, deployment).
- Use `@QuarkusTest` for integration tests — Dev Services auto-starts backing services (databases, messaging, etc.).
- Use `%dev.` and `%test.` profile prefixes for dev/test configuration — never hardcode connection URLs without a profile prefix.

## Testing

ALWAYS run tests using a **subagent** so the main conversation stays responsive:

```
Use the Agent tool to launch a subagent with this prompt:
  "Run the Quarkus tests for project <projectDir> using quarkus/callTool
   with toolName 'devui-testing_runTests'. Analyze the results and report
   which tests passed, failed, or errored. If tests fail, include the
   failure messages and suggest fixes."
```

- Use `devui-testing_runTests` to run all tests.
- Use `devui-testing_runTest` with arguments `{"className":"com.example.MyTest"}` to run a specific test class.
- Do NOT run Maven/Gradle test commands manually — the Dev MCP test tools handle compilation, hot reload, and result reporting.
- After fixing test failures, re-run tests with a subagent to verify the fix.

## Error Handling

When something goes wrong (compilation error, deployment failure, runtime exception):

1. Use `quarkus/callTool` with toolName `devui-exceptions_getLastException` to get structured exception details (class, message, stack trace, user code location).
2. Fix the issue based on the exception details.
3. Call `devui-exceptions_clearLastException` to clear the recorded exception.
4. Use `quarkus/logs` only when you need broader log context beyond the exception itself.

**Note:** If the app fails on its very first deploy (before the Dev MCP handler is registered), the exception endpoint won't exist yet — fall back to `quarkus/logs` in that case. For hot-reload failures (the common case), the endpoint is always available from the prior successful deploy.

## Customizing Skills

Extension skills can be overridden per-project by placing SKILL.md files under
`src/main/resources/META-INF/skills/<extension-name>/SKILL.md`. Project-level
skills take precedence over the built-in defaults. This is useful for enforcing
team conventions or adjusting patterns for specific project requirements.
