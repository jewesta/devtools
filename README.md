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

Use `--repo` when the current working directory is not the target repository:

```sh
run/prettify.sh --repo /path/to/project --assert
```

The matching `run/prettify.bat` launcher provides the same interface on
Windows. Maven preparation is enabled by default so OpenRewrite can resolve
types correctly; `--no-prepare` skips that build when reactor outputs are
already current.
