# Repository Guidelines

## Project Structure & Module Organization
- `src/main/java/lab/springlab`: Application source code and Spring Boot entry point.
- `src/main/resources`: Configuration and app resources.
  - `application.properties`: Runtime configuration.
  - `static/` and `templates/`: Web assets and server-rendered templates (if used).
- `src/test/java/lab/springlab`: JUnit tests.
- Gradle build files: `build.gradle`, `settings.gradle`, `gradlew`.

## Build, Test, and Development Commands
- `./gradlew build`: Compiles code and runs the full test suite.
- `./gradlew test`: Runs unit/integration tests with JUnit Platform.
- `./gradlew bootRun`: Starts the Spring Boot app locally.
- `./gradlew clean`: Removes build outputs in `build/`.

## Coding Style & Naming Conventions
- Java 21 (toolchain configured in `build.gradle`).
- Indentation: 4 spaces, no tabs.
- Package naming follows Java conventions: lowercase reverse-DNS (e.g., `lab.springlab`).
- Class names use UpperCamelCase; methods/fields use lowerCamelCase.
- No formatting or lint tools are configured yet; keep formatting consistent with existing files.

## Testing Guidelines
- Framework: JUnit 5 (Jupiter) via Spring Boot test starters.
- Test location: `src/test/java` mirroring main package paths.
- Naming: use `*Tests` or descriptive names like `UserServiceTests`.
- Run tests with `./gradlew test` (uses JUnit Platform).
- No explicit coverage thresholds are configured.

## Commit & Pull Request Guidelines
- Git history is empty; no commit message convention is established yet.
- Suggested convention: `type(scope): summary` (e.g., `feat(api): add health endpoint`).
- PRs should include: a clear description, linked issue/ticket if applicable, and test evidence (command output or notes). Add screenshots only for UI changes.

## Configuration & Local Tips
- Override settings in `src/main/resources/application.properties` or via environment variables.
- If adding data stores or external services, document required env vars in the PR description.
