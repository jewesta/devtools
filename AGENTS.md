# Devtools Agent Guidelines

These instructions apply to the entire repository.

## Structure

- The root `devtools-parent` Maven project aggregates independently usable
  development tools.
- `prettify` is a standalone Java cleanup and formatting CLI. It must not
  depend on any consuming project.
- Java implementation packages use the `de.westarps.devtools` namespace.

## Prettify

- `prettify/formatting-rules.xml` is the canonical Java formatting profile and
  is bundled into the CLI. It can also be imported into Eclipse or STS.
- Configure OpenRewrite cleanup in Java. Do not introduce a YAML recipe.
- Preserve the `--assert` check-only mode and the `--apply` write mode.
- Keep Maven-reactor discovery and type-aware cleanup independent of any one
  consuming repository.
- Add comments where they preserve intent, constraints, or non-obvious design;
  avoid comments that merely restate individual Java statements.

## Build and Verification

- The project targets Java 21 and uses Maven.
- Use `run/prettify.sh --apply <java-file>...` to clean up and format concrete
  Java files. Use `--assert` for a check-only run; omitting file paths selects
  all tracked Java sources.
- Run `mvn test` from the repository root for the normal reactor verification.
- Preserve unrelated changes and keep tools independently buildable.
