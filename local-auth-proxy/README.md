# Local auth proxy for AI

A standalone credential broker for local applications and AI tools. It holds the
upstream credential while client processes can start and stop using a separate
local token. It has no dependency on imap-crawler or Spring.

The current protocol is IMAP. HTTP services and other protocols are future work;
the `imap` command makes that scope explicit.

## Start and stop

Requirements: macOS/POSIX, Java 21+, Maven, and nginx with compiled-in mail,
stream, and stream SSL modules. Verify the modules with `nginx -V`. Dynamic module
loading and Windows are not supported by this first launcher.

1. From the devtools root, build with `mvn -pl local-auth-proxy -am clean verify`.
   The module also builds directly with `mvn -f local-auth-proxy/pom.xml clean verify`.
2. Copy `local-auth-proxy/config/mail-proxy.example.properties` to your private project directory.
   Set the IMAP hostname, login name, and absolute path to a trusted PEM CA bundle.
   Use implicit IMAPS, normally port 993. There is no STARTTLS fallback.
3. In your own ordinary terminal, run:

   ```sh
   run/local-auth-proxy.sh imap /absolute/path/to/mail-proxy.properties
   ```

4. Enter the IMAP password at the hidden prompt. Do not put it in a command, pipe,
   environment variable, or configuration file. Unrecognized configuration keys,
   including password properties, are rejected.
5. Leave the terminal open. Ctrl-C stops this proxy and removes its temporary
   runtime directory. Restarting requires entering the password again.

The launcher prints the local listener and the path to a temporary
`client.properties` file. Its `proxy.host`, `proxy.port`, `proxy.username`, and
`proxy.password` values describe the **local** connection. The password in that
file is a new random access token, not the upstream password. Keep this file
private: its token grants mailbox access for the lifetime of this proxy. Clients
must read these values; they are not framework-specific properties.
The local username is `local-auth-proxy`.

The default listener is `127.0.0.1:1143`. The local IMAP connection uses plaintext
LOGIN or AUTHENTICATE PLAIN with the local token. Do not enable STARTTLS or require
TLS on this loopback hop. The remote hop always uses verified TLS.

Startup verifies nginx configuration and the local listener. It does not log in
to the remote account; remote login occurs when a client authenticates locally.
nginx resolves the configured remote hostname during startup. Restart the proxy
if the provider's IP addresses change or if settings need to change.

## Credential path

```text
client  -- local token --> nginx mail -- loopback --> nginx stream -- TLS --> IMAP
                              |
                      local credential helper
                       (upstream password)
```

The Java helper reads the password with `Console.readPassword`. nginx's
[auth_http interface](https://nginx.org/en/docs/mail/ngx_mail_auth_http_module.html)
checks a separate helper key and the client token, then supplies the upstream
login. Requests are bounded, time-limited, and never logged. The helper and all
nginx listeners bind to IPv4 loopback only.

The [stream TLS configuration](https://nginx.org/en/docs/stream/ngx_stream_proxy_module.html)
enables certificate-chain verification, hostname verification, SNI, and TLS
1.2/1.3. The mail module connects through that stream listener because its IMAP
upstream connection does not itself provide TLS.

The upstream password is never written to application files, process arguments,
or environment variables. The launcher disables core dumps, JVM attach, and
automatic heap dumps and removes inherited JVM option variables. nginx wire
diagnostics are discarded: even an error response from an upstream could contain
credential text. Startup failures therefore give a generic diagnostic; check
the executable/modules, hostname, CA file, and available local port first.

Each run uses an owner-only temporary directory (0700) and files (0600). The
generated nginx configuration briefly contains a separate helper key, and is
deleted after nginx has read it. It never contains the upstream password.
Normal shutdown removes the directory and clears the helper's retained password
buffer. This is **not isolation from other processes running as the same OS
user**, administrator access, or process-memory inspection. A process that
captures the temporary helper key and client token can request the credential.
Deleting a file or clearing a buffer is not a secure-erasure guarantee.

The proxy runs its own foreground, single-process nginx instance. It does not
change the system/Homebrew nginx configuration or signal other nginx processes.
It does not support reloads. A forced kill or machine crash can bypass cleanup;
inspect only this launcher's process and `local-auth-proxy-*` runtime directory
if manual cleanup is necessary.

## Protocol limits

- The helper supports printable ASCII login names without spaces. Passwords may
  contain Unicode and punctuation, but not control characters or leading/trailing
  whitespace, which cannot safely round-trip through nginx response headers.
  Unsupported input is rejected without printing its value.
- Before login, capabilities describe nginx. Issue CAPABILITY again after login
  to discover the upstream capabilities, including SAVEDATE when available.
- After login, nginx forwards raw protocol bytes. It does not parse messages,
  change MIME encodings, or enforce read-only access. Clients that require
  read-only access must use EXAMINE/read-only folders and BODY.PEEK[], and avoid
  modifying commands.
- The remote service must accept IMAP LOGIN over TLS. OAuth-only accounts are
  outside this implementation.

## Verification

`mvn -pl local-auth-proxy -am test` runs configuration and credential-helper
tests using synthetic values.
With nginx installed, run the additional integration tests:

```sh
mvn -pl local-auth-proxy -am -Dnginx.tests=true clean verify
```

These create temporary certificates with the JDK's keytool and a loopback TLS
IMAP server. They check credential substitution, rejection of unauthorized
clients, certificate-chain and hostname failures before LOGIN, preservation of
binary message bytes and SAVEDATE responses, and process/runtime cleanup. They
never connect to a real mail account. Override the integration-test nginx binary
with `-Dnginx.executable=/absolute/path/to/nginx` when necessary.

## Reuse from another repository

Use the launcher in a separately checked-out devtools repository, or run the
packaged JAR directly:

```sh
java -jar local-auth-proxy/target/local-auth-proxy-1.0-SNAPSHOT.jar --help
```

For actual credential entry, prefer the shell launcher because it also disables
core dumps, attach and automatic JVM memory dumps. Keep non-secret account
configuration in the consuming project's private directory. Credentials, real
account settings, runtime files and mail contents must not enter this repository.

The client configuration keys and upstream configuration keys are unchanged from
the initial imap-crawler implementation. An already-running old proxy can remain
in use; moving its source does not require restarting it. New sessions use the
devtools launcher and produce the generic local username and runtime prefix above.
