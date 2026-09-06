# devtools

Reusable development tools for Westarps projects.

## Prettify

`prettify` is a headless Java cleanup and formatting CLI for Maven reactors. It
applies a conservative OpenRewrite cleanup and then formats the result with the
bundled Eclipse JDT profile. The canonical profile is
`prettify/formatting-rules.xml` and can also be imported into Eclipse or STS.

The tool requires Java 21, Apache Maven, and Git. Run it from the root of the
Maven repository to format:

```sh
/path/to/devtools/run/prettify.sh --assert
/path/to/devtools/run/prettify.sh --apply path/to/Source.java
```

### Selecting sources

Rather than assembling a file list yourself, let prettify derive one from Git
with `--select`:

```sh
run/prettify.sh --apply  --select uncommitted   # staged, unstaged, and untracked
run/prettify.sh --assert --select branch        # everything differing from the base branch
run/prettify.sh --assert --select repository    # every tracked Java source (the default)
```

`uncommitted` is the everyday choice while working; `branch` is the check to run
before opening a pull request. `branch` compares the working tree against the
merge base with the base branch, so it covers committed and uncommitted work
while ignoring commits that landed on the base after the fork. The base branch is
detected from `origin/HEAD` with `origin/main`, `origin/master`, `main`, and
`master` as fallbacks; override it with `--base <ref>`.

Deleted sources drop out of every scope. `--select` cannot be combined with
explicit file arguments, because those are two answers to the same question.

Use `--repo` when the current working directory is not the target repository:

```sh
run/prettify.sh --repo /path/to/project --assert
```

Both a normal checkout and a linked Git worktree are accepted as a repository
root.

### Exit codes

`0` means success, `1` means an assertion found files that would change, and
`2` means the run could not be completed.

Only a run that cleaned up and formatted every selected source exits `0`. If
OpenRewrite cannot parse a source, that source is formatted but not cleaned up;
prettify names it and exits `2` in both modes. An assertion that skipped work
cannot vouch for the sources it skipped, so it does not report success.

The matching `run/prettify.bat` launcher provides the same interface on
Windows. Maven preparation is enabled by default so OpenRewrite can resolve
types correctly; `--no-prepare` skips that build when reactor outputs are
already current.

## Local auth proxy for AI

`local-auth-proxy` lets local applications and AI tools access a configured service
using temporary local credentials while the broker retains the real credential.
The first implementation supports IMAP through nginx; HTTP support is future work.
It is independent of Spring and any consuming project.

```sh
mvn -pl local-auth-proxy -am clean verify
run/local-auth-proxy.sh imap /absolute/path/to/mail-proxy.properties
```

Read the [setup and security boundaries](local-auth-proxy/README.md) before use.
The launcher prompts in the user's terminal and leaves the proxy running until
stopped. Use `-Dnginx.tests=true` to enable synthetic local TLS integration tests.
