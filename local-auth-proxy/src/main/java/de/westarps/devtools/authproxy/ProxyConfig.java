package de.westarps.devtools.authproxy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

record ProxyConfig(String host, int upstreamPort, String username, int listenPort, Path caFile, String nginx) {

	static ProxyConfig load(final Path path) throws IOException {
		final var properties = new Properties();
		try (var reader = Files.newBufferedReader(path)) {
			properties.load(reader);
		}
		final var allowed = Set.of("imap.host", "imap.port", "imap.username", "proxy.port", "tls.ca-file",
				"nginx.executable");
		if (!allowed.containsAll(properties.stringPropertyNames())) {
			throw new IllegalArgumentException(
					"Unknown configuration key. Passwords must only be entered at the terminal prompt.");
		}
		return new ProxyConfig(required(properties, "imap.host"), port(properties, "imap.port", 993),
				required(properties, "imap.username"), port(properties, "proxy.port", 1143),
				Path.of(required(properties, "tls.ca-file")), properties.getProperty("nginx.executable", "nginx"));
	}

	ProxyConfig {
		if (host == null || host.length() > 253 || !host.matches("[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*")
				|| host.endsWith(".invalid")) {
			throw new IllegalArgumentException("imap.host must be a real DNS hostname, without a scheme or port.");
		}
		if (upstreamPort < 1 || upstreamPort > 65535 || listenPort < 1024 || listenPort > 65535) {
			throw new IllegalArgumentException(
					"Invalid port. The local proxy requires an unprivileged port (1024–65535).");
		}
		if (username == null || username.isEmpty() || username.length() > 512 || !username.matches("[!-~]+")) {
			throw new IllegalArgumentException("imap.username currently requires printable ASCII without spaces.");
		}
		if (caFile == null || !caFile.isAbsolute() || !Files.isRegularFile(caFile) || !Files.isReadable(caFile)) {
			throw new IllegalArgumentException("tls.ca-file must name a readable absolute PEM CA bundle.");
		}
		nginxString(caFile.toString());
		if (nginx == null || nginx.isBlank()) {
			throw new IllegalArgumentException("nginx.executable is required.");
		}
	}

	static String nginxString(final String value) {
		if (value.chars().anyMatch(c -> c < 32 || c == 127 || c == '$')) {
			throw new IllegalArgumentException("nginx paths must not contain control characters or dollar signs.");
		}
		return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
	}

	private static String required(final Properties properties, final String key) {
		final var value = properties.getProperty(key);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Missing required configuration: " + key);
		}
		return value;
	}

	private static int port(final Properties properties, final String key, final int defaultValue) {
		try {
			return Integer.parseInt(properties.getProperty(key, Integer.toString(defaultValue)));
		} catch (final NumberFormatException ignored) {
			throw new IllegalArgumentException("Invalid numeric port: " + key);
		}
	}
}
