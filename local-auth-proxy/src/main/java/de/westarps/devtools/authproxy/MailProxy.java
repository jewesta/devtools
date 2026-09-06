package de.westarps.devtools.authproxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/** Owns one foreground nginx process and its temporary runtime files. */
final class MailProxy implements AutoCloseable {

	private final Path runtime;
	private final CredentialServer credentials;
	private Process nginx;
	private boolean closed;
	private final Thread shutdown;

	private MailProxy(final Path runtime, final CredentialServer credentials) {
		this.runtime = runtime;
		this.credentials = credentials;
		shutdown = new Thread(() -> {
			try {
				close();
			} catch (final IOException ignored) {
				// Do not expose potentially sensitive runtime diagnostics.
			}
		}, "mail-proxy-shutdown");
		Runtime.getRuntime().addShutdownHook(shutdown);
	}

	static MailProxy start(final ProxyConfig config, final char[] password) throws IOException, InterruptedException {
		CredentialServer.validatePassword(password);
		final var runtime = Files.createTempDirectory("local-auth-proxy-",
				PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
		MailProxy proxy = null;
		try {
			final var key = token();
			final var clientToken = token();
			final int bridgePort;
			try (var reservation = reservePort(config.listenPort())) {
				bridgePort = reservation.getLocalPort();
				final var auth = new CredentialServer(config.username(), password, key, clientToken, bridgePort);
				proxy = new MailProxy(runtime, auth);
			}
			proxy.initialize(config, bridgePort, key, clientToken);
			return proxy;
		} catch (final IOException | InterruptedException | RuntimeException failure) {
			if (proxy != null) {
				proxy.close();
			} else {
				Files.deleteIfExists(runtime);
			}
			throw failure;
		}
	}

	private synchronized void initialize(final ProxyConfig config, final int bridgePort, final String key,
			final String clientToken) throws IOException, InterruptedException {
		if (closed) {
			throw new IOException("Proxy shutdown has begun");
		}
		final var configFile = privateFile(runtime.resolve("nginx.conf"),
				configuration(config, runtime, credentials.port(), bridgePort, key));
		nginx = command(config, runtime, configFile, true).start();
		if (!nginx.waitFor(15, TimeUnit.SECONDS)) {
			nginx.destroyForcibly().waitFor();
			throw new IOException("nginx configuration check timed out");
		}
		if (nginx.exitValue() != 0) {
			throw new IOException("nginx configuration check failed");
		}
		nginx = command(config, runtime, configFile, false).start();
		awaitListener(config.listenPort());
		// No reload support: remove the helper key's only on-disk copy after nginx reads it.
		Files.delete(configFile);
		privateFile(runtime.resolve("client.properties"), "proxy.host=127.0.0.1\nproxy.port=" + config.listenPort()
				+ "\nproxy.username=" + CredentialServer.LOCAL_USER + "\nproxy.password=" + clientToken + "\n");
	}

	Path clientConfig() {
		return runtime.resolve("client.properties");
	}

	int waitFor() throws InterruptedException {
		return nginx.waitFor();
	}

	private static ProcessBuilder command(final ProxyConfig config, final Path runtime, final Path configFile,
			final boolean check) {
		final var builder = new ProcessBuilder(config.nginx(), "-p", runtime + "/", "-c", configFile.toString(), "-e",
				"/dev/null");
		if (check) {
			builder.command().add("-t");
		}
		// Discard nginx diagnostics too: a hostile upstream could echo credentials in an error.
		builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
		return builder;
	}

	static String configuration(final ProxyConfig config, final Path runtime, final int authPort, final int bridgePort,
			final String key) {
		return """
			daemon off;
			master_process off;
			pid %s;
			error_log /dev/null crit;
			events { worker_connections 128; }
			mail {
			    server_name local-mail-proxy;
			    auth_http 127.0.0.1:%d/auth;
			    auth_http_header X-Proxy-Key %s;
			    auth_http_timeout 5s;
			    proxy_pass_error_message off;
			    timeout 30s;
			    proxy_timeout 10m;
			    server {
			        listen 127.0.0.1:%d;
			        protocol imap;
			        imap_auth plain;
			        imap_capabilities IMAP4rev1;
			    }
			}
			stream {
			    server {
			        listen 127.0.0.1:%d;
			        proxy_pass %s:%d;
			        proxy_connect_timeout 15s;
			        proxy_timeout 10m;
			        proxy_ssl on;
			        proxy_ssl_verify on;
			        proxy_ssl_verify_depth 5;
			        proxy_ssl_trusted_certificate %s;
			        proxy_ssl_server_name on;
			        proxy_ssl_name %s;
			        proxy_ssl_protocols TLSv1.2 TLSv1.3;
			    }
			}
			""".formatted(ProxyConfig.nginxString(runtime.resolve("nginx.pid").toString()), authPort, key,
				config.listenPort(), bridgePort, config.host(), config.upstreamPort(),
				ProxyConfig.nginxString(config.caFile().toString()), config.host());
	}

	private void awaitListener(final int port) throws IOException, InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (nginx.isAlive() && System.nanoTime() < deadline) {
			// The PID file belongs to this fresh directory, preventing an unrelated listener from being treated as ours.
			if (Files.isRegularFile(runtime.resolve("nginx.pid"))
					&& Files.readString(runtime.resolve("nginx.pid")).strip().equals(Long.toString(nginx.pid()))) {
				try (var socket = new Socket()) {
					socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
					socket.setSoTimeout(1000);
					if (socket.getInputStream().read() == '*') {
						return;
					}
				} catch (final IOException ignored) {
					// Wait briefly for this nginx process to finish startup.
				}
			}
			Thread.sleep(50);
		}
		throw new IOException("nginx did not start its local listener");
	}

	private static ServerSocket reservePort(final int excludedPort) throws IOException {
		final var socket = new ServerSocket();
		try {
			socket.bind(new InetSocketAddress("127.0.0.1", 0));
			if (socket.getLocalPort() == excludedPort) {
				try (socket) {
					return reservePort(excludedPort);
				}
			}
			return socket;
		} catch (final IOException failure) {
			socket.close();
			throw failure;
		}
	}

	private static String token() {
		final var bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

	private static Path privateFile(final Path path, final String content) throws IOException {
		Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		Files.writeString(path, content);
		return path;
	}

	@Override
	public synchronized void close() throws IOException {
		if (closed) {
			return;
		}
		closed = true;
		try {
			Runtime.getRuntime().removeShutdownHook(shutdown);
		} catch (final IllegalStateException ignored) {
			// JVM shutdown is already running this hook.
		}
		if (nginx != null && nginx.isAlive()) {
			nginx.destroy();
			try {
				if (!nginx.waitFor(5, TimeUnit.SECONDS)) {
					nginx.destroyForcibly().waitFor();
				}
			} catch (final InterruptedException ignored) {
				nginx.destroyForcibly();
				Thread.currentThread().interrupt();
			}
		}
		credentials.close();
		try (var paths = Files.walk(runtime)) {
			for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}
}
