# Issue 3: Add local auth proxy for AI

## Intent

Move the working local credential proxy from imap-crawler into devtools so that
local applications and AI tools can use it independently of the mail crawler.

## Design and decisions

- Add `local-auth-proxy` as an independently buildable Java 21 Maven module in
  the devtools reactor, using the `de.westarps.devtools.authproxy` namespace.
- Package a standalone executable JAR with SLF4J only at runtime; Spring and MCP
  stay in consuming applications.
- Preserve the existing interactive IMAP credential broker, nginx supervision,
  verified upstream TLS, local tokens, private temporary files and cleanup.
- Expose `local-auth-proxy imap <configuration>` as the CLI. HTTP and other
  protocols remain future work, with no new protocol added by this extraction.
- Keep existing upstream and client configuration property names. New sessions
  use `local-auth-proxy` as their local username and temporary-directory prefix.
  Clients should always use the values in the generated client configuration.
- Retain only a forwarding launcher and client-facing guide in imap-crawler.
  Keep actual account configuration and private project context with its owner.
- Preserve already-running proxies during the move. They are independent
  processes; builds must not replace the artifact backing a running old instance.

## Progress

- Created `devtools-issue-3` on `issues/Issue_3` from current `main`.
- Extracted the Java helper, nginx supervision, nine synthetic tests, launcher,
  example configuration and setup/security guide.
- Added standalone CLI packaging and devtools reactor integration.
- Removed the implementation from imap-crawler, retained its forwarding launcher
  and client guide, and selected this worktree in its local devtools setting.
- Verified standalone packaging and both launch paths with synthetic credentials.
- The extraction is complete; changes are uncommitted in both repositories.

## Verification

- Java 21 `mvn -pl local-auth-proxy -am -Dnginx.tests=true clean verify`
  passed all nine tests, including the actual nginx loopback TLS fixture.
- Independent module verification with
  `mvn -f local-auth-proxy/pom.xml -Dnginx.tests=true clean verify` passed all
  nine tests and produced the executable JAR with its SLF4J runtime dependencies.
- Canonical `--apply --select uncommitted` and
  `--assert --no-prepare --select branch` passed for all seven Java files.
- Packaged-JAR checks confirmed no Spring/crawler classes. Direct and consumer
  forwarding launchers passed hidden-prompt, generated client configuration,
  help, noninteractive-input rejection, unsupported-mode rejection, and SIGTERM
  process/file cleanup checks. These used synthetic passwords and separate
  ephemeral loopback ports; no real credentials or mail account were used.
- An isolated imap-crawler `clean verify` passed; the new JAR contains no proxy
  classes. MCP initialization, empty tool listing and ping succeeded. The old
  crawler JAR's SHA-256 remained unchanged through the check.
- Both repositories passed shell syntax and whitespace checks.
- Full devtools reactor verification found one pre-existing failure:
  `RewriteCleanerTest.anUnparseableSourceIsSkippedSoTheRestOfTheModuleStillGetsCleanedUp`
  expects an unparseable source but receives no skipped sources. Reproduced the
  same failure on untouched `main` at `b366a2c` with Java 21 and
  `mvn -pl prettify -Dtest=RewriteCleanerTest test`. No prettify implementation
  or test was changed by this issue. The full reactor is therefore not green;
  the extracted proxy passes independently.

## Open questions

- HTTP credential injection and service-specific authorization policies can be
  added separately. The current IMAP broker forwards commands; it does not
  restrict mailbox operations or isolate hostile processes running as the user.
