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

The matching `run/prettify.bat` launcher provides the same interface on
Windows. Maven preparation is enabled by default so OpenRewrite can resolve
types correctly; `--no-prepare` skips that build when reactor outputs are
already current.
