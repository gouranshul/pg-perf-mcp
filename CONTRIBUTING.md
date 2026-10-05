# Contributing

Thanks for your interest! Issues and pull requests are welcome.

## Development

- Java 25 and Docker are required. `./mvnw verify` must pass: unit tests, Testcontainers
  integration tests and the JaCoCo coverage gate.
- `docker compose up --build` starts the demo stack; `./demo/smoke-test.sh` checks it end to end.
- Code style: follow the surrounding code (4-space indent, see `.editorconfig`).

## Ground rules

- **Read-only is non-negotiable.** Nothing may write to the database or execute DDL. Any change to
  `guard/` or `db/ReadOnlyExecutor` needs tests for the new behavior *and* for the bypass you
  thought about. See [ADR 0001](docs/adr/0001-read-only-defense-in-depth.md).
- Tool and parameter descriptions are read by the model: keep them precise and say what is safe.
- Errors returned to clients must stay free of stack traces and connection details.
- Never commit secrets. Run gitleaks before pushing:
  `docker run --rm -v "$PWD:/repo" zricethezav/gitleaks:v8.30.1 git /repo`
- Use [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `test:`,
  `docs:`, `ci:`, `chore:`) and keep commits small.
- New dependency versions must be verified against Maven Central or official docs.
