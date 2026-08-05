# Issue 1: Let Prettify Select Its Own Sources

## Intent

Prettify is primarily used by AI agents. Every caller was therefore repeating
the same preparation: run Git or Maven to work out which Java files changed,
then pass that list to prettify. That work is error-prone, differs between
callers, and is exactly the kind of thing the tool can do correctly once.

Reduce the common cases to one fixed command with one parameter.

## Selection Scopes

`--select <scope>` derives the source list from Git:

| Scope | Contents |
| --- | --- |
| `repository` | Every tracked Java source. The previous no-argument behaviour. |
| `branch` | Everything differing from the base branch, committed or not. |
| `uncommitted` | Staged, unstaged, and untracked changes. |

`uncommitted` is the everyday choice while working. `branch` is the check before
handing work over.

### Decisions

1. One valued option rather than separate boolean flags. Scopes are mutually
   exclusive by construction, the set can grow without new flags, and the name
   stays honest under both `--assert` and `--apply`.
2. `branch` diffs the working tree against the **merge base** with the base
   branch, not against its tip. Commits that landed on the base after forking
   are somebody else's work and must not appear in the result. Comparing the
   working tree rather than `HEAD` covers committed and uncommitted work in one
   comparison, which is what "everything this branch changed" means to a caller.
3. Base branch detection reads `origin/HEAD`, then falls back to `origin/main`,
   `origin/master`, `main`, and `master`. `--base <ref>` overrides it, and an
   unresolvable base is reported rather than guessed. `--base` outside `branch`
   scope is rejected, because silently ignoring it would hide a mistake.
4. `--select` and explicit file arguments are refused together. They are two
   answers to the same question and letting either win would obscure a
   mistaken invocation.
5. Deleted sources leave every scope. A removed file cannot be formatted, and
   the existing "path must be a regular file" filter already expresses this.
6. Git is invoked as a subprocess rather than through a library, so prettify
   keeps working against whatever Git the surrounding checkout uses, including
   linked worktrees and alternate object stores.

### Structure

Git invocation moved out of `SourceSelection` into `GitSources`, which owns
every scope query. `SourceSelection` keeps module assignment and the validation
of explicitly named files.

A narrowed scope now counts as `targeted`, which was previously true only for
explicit files. `targeted` controls whether a module's own compiled output stays
on the classpath during type-aware cleanup; any selection that leaves part of a
module unanalysed needs it. Only a repository-wide sweep rebuilds everything it
needs from source.

A file discovered by a scope but excluded by `--module` is skipped. Only an
explicitly named file in that position is an error, because naming a file the
module filter excludes is a contradiction.

## Worktree Support

A repository root is now `pom.xml` plus either a `.git` directory or a `.git`
file containing a `gitdir:` pointer. The second form is a linked worktree.

The previous check tested only for a directory, so prettify aborted in every
worktree with "Repository root must contain .git and pom.xml". This made the
canonical formatter unusable in repositories whose own guidelines mandate
worktree-per-issue — the formatter could not run where the work happened.

## Unparseable Sources No Longer Abort a Run

A repository-wide sweep of RetroCrawler failed outright with
`IllegalStateException: Expected to be able to find @exception` while parsing
`retro-crawler-core`. The failure predates `--select`; the previous
no-argument path reproduces it exactly.

The cause is upstream. `ReloadableJava21JavadocVisitor.visitThrows` in
OpenRewrite 8.85.0 chooses which tag to look for with an exact position test:

```java
boolean throwsKeyword = source.startsWith("@throws", cursor);
sourceBefore(throwsKeyword ? "@throws" : "@exception");
```

`sourceBefore` then searches *forward* from the cursor. When the cursor still
trails the preceding tag's description the position test fails, so the visitor
searches for an `@exception` that was never written and throws. The Javadoc is
valid; nothing in the consuming project can be corrected to satisfy it.

The prettify-level defect is that this aborted everything. Cleanup is an
enrichment, while Eclipse formatting is the guarantee prettify actually makes,
so one source tripping a parser bug must not stop a 391-file sweep.

`RewriteCleaner` now recovers. When a module fails to parse as a batch, each of
its sources is parsed alone to identify the offenders, those are reported on
stderr, and the remaining sources are parsed as one batch so they keep full
type resolution. Skipped sources still get formatted. If every source parses
alone, the batch failure is not attributable to one file and the original error
is raised rather than an invented explanation.

Two `@throws` tags alone do not reproduce the upstream bug; the wrapped
`@return` description preceding them is part of the trigger. The minimal shape
was not isolated, so the regression test keeps a sample close to the real
source that surfaced it.

### Recovery is not success

Skipping is recovery, not forgiveness. Cleanup did not run for the skipped
sources, so a passing assertion would be claiming something prettify never
checked.

Only a run that cleaned up and formatted every selected source exits `0`. A
skipped source makes the run incomplete and both modes exit `2` with the
sources named. `--apply` still writes everything it could before reporting.
This is deliberately louder than the alternative: an assertion whose job is to
verify must not stay silent about work it did not do.

The consequence is that a repository containing a source OpenRewrite cannot
parse cannot get a green repository-wide assertion until the source is reworded
or the upstream defect is fixed. That is the honest result.

## Verification

- `mvn test` passed for the reactor: 21 tests, 0 failures, 0 errors.
- A repository-wide sweep of RetroCrawler now runs to completion instead of
  aborting: 391 sources, 0 would change, one unparseable source named. It exits
  `2` because that source was never cleaned up.
- The same repository at `--select uncommitted`, which touches no unparseable
  source, exits `0` with the assertion passed.
- End-to-end from a RetroCrawler issue worktree: `--select uncommitted` found an
  untracked source, formatted it, and applied the OpenRewrite `final` cleanup.
- Prettify checked itself through the new option. `--select uncommitted` flagged
  two of this issue's own test files; after `--apply` the assertion passed.
- The consuming RetroCrawler reactor stayed green at 314 tests.

## Open Questions

- The OpenRewrite `visitThrows` defect is worth reporting upstream. Isolating
  the minimal Javadoc that triggers it is a prerequisite for a useful report.
- No opt-out exists for a known-unparseable source. A project blocked on an
  upstream defect currently cannot get a green repository-wide assertion. An
  explicit allowance would restore that at the cost of reintroducing a way to
  hide skipped work, so it should only be added if a real workflow needs it.
- A `staged` scope was considered and left out. It suits a pre-commit hook more
  than an agent, and no caller needs it yet.
