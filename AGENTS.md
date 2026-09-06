# Devtools Agent Guidelines

These instructions apply to the entire repository.

## Structure

- The root `devtools-parent` Maven project aggregates independently usable
  development tools.
- `prettify` is a standalone Java cleanup and formatting CLI. It must not
  depend on any consuming project.
- `local-auth-proxy` is an independent credential broker, initially for IMAP.
  Do not couple it to Spring or to a consuming project.
- Java implementation packages use the `de.westarps.devtools` namespace.

## Prettify

- `prettify/formatting-rules.xml` is the canonical Java formatting profile and
  is bundled into the CLI. It can also be imported into Eclipse or STS.
- Configure OpenRewrite cleanup in Java. Do not introduce a YAML recipe.
- Preserve the `--assert` check-only mode and the `--apply` write mode.
- Preserve the `--select` scopes (`repository`, `branch`, `uncommitted`). They
  exist so a caller — especially an agent — can run one fixed command instead of
  computing a file list with separate Git or Maven invocations. Prefer adding a
  scope over pushing that work back onto callers.
- A repository root is a directory holding `pom.xml` plus either a `.git`
  directory or a `.git` file containing a `gitdir:` pointer. The second form is
  a linked worktree; do not reduce the check to a directory test.
- Only a run that cleaned up and formatted every selected source may exit `0`.
  A source OpenRewrite cannot parse is skipped, reported, and makes the run
  incomplete, so both modes exit `2`. An assertion must never report success
  for work prettify did not actually do.
- Keep Maven-reactor discovery and type-aware cleanup independent of any one
  consuming repository.
- Add comments where they preserve intent, constraints, or non-obvious design;
  avoid comments that merely restate individual Java statements.

## Issue Documentation

- Keep living issue notes in the repository-root `issues` directory.
- Use one Markdown file per issue, named exactly `Issue_<issue_number>.md`.
- Record the issue's intent, design, decisions, progress, open questions, and
  verification where relevant.
- Update the issue note as work progresses; it is a working design record, not
  only a summary written after implementation.

## Issue Worktrees

- Implement each issue in a dedicated sibling Git worktree unless the user
  explicitly requests a different workflow.
- Name the worktree directory `<repository-name>-issue-<issue_number>`, for
  example `devtools-issue-1`.
- Create the worktree branch as `issues/Issue_<issue_number>` from `main`.
- Keep the primary repository checkout on its existing branch; do not switch it
  to the issue branch merely to create the worktree.

## Build and Verification

- The project targets Java 21 and uses Maven.
- Use `run/prettify.sh --apply --select uncommitted` to clean up and format the
  work in progress, and `--assert --select branch` to check a branch before
  handing it over. Concrete `<java-file>...` arguments still work when a single
  file is the target.
- Run `mvn test` from the repository root for the normal reactor verification.
- Keep tools independently buildable.

## Change Discipline

- Preserve unrelated user changes in the working tree.
- Prefer small, reviewable changes and avoid unrelated refactoring in issue
  branches.

## Local Auth Proxy

- Read `local-auth-proxy/README.md` for the credential boundary and protocol scope.
- Real passwords are entered only in the owner's ordinary terminal. Never request
  them in a captured tool session, or put them in files, arguments, environment
  variables, logs, fixtures, or issue notes.
- Keep real account settings and service discovery results in the consuming
  project's canonical private store. Do not migrate that data into devtools.
- Keep proxy endpoints on loopback, upstream TLS verified, and local client
  authentication separate from the helper's own authentication.
- Do not contact real services in automated tests. Run
  `mvn -pl local-auth-proxy -am -Dnginx.tests=true clean verify` against synthetic
  local TLS fixtures when changing proxy behavior or packaging.
- Do not stop or replace an owner's running proxy as part of builds or tests.
  The proxy forwards authenticated commands and does not enforce read-only IMAP.
