package de.westarps.devtools.authproxy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standalone interactive entry point. Upstream credentials are never accepted
 * through command arguments.
 */
public final class LocalAuthProxyCli {

	private static final Logger LOG = LoggerFactory.getLogger(LocalAuthProxyCli.class);

	private LocalAuthProxyCli() {
	}

	public static void main(final String[] args) {
		System.exit(run(args));
	}

	public static int run(final String[] args) {
		if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
			LOG.info("Usage: local-auth-proxy imap /absolute/path/to/mail-proxy.properties");
			return 0;
		}
		if (args.length != 2 || !"imap".equals(args[0])) {
			LOG.error("Usage: local-auth-proxy imap /absolute/path/to/mail-proxy.properties");
			return 2;
		}
		final var console = System.console();
		if (console == null) {
			LOG.error("Start the proxy in your own interactive terminal. Piped password input is not accepted.");
			return 2;
		}
		char[] password = null;
		try {
			final var config = ProxyConfig.load(Path.of(args[1]));
			password = console.readPassword("IMAP password (hidden): ");
			try (var proxy = MailProxy.start(config, password)) {
				Arrays.fill(password, '\0');
				console.printf(
						"Local IMAP proxy: 127.0.0.1:%d%nClient configuration: %s%nLeave this terminal open. Ctrl-C stops the proxy.%n",
						config.listenPort(), proxy.clientConfig());
				return proxy.waitFor();
			}
		} catch (final IllegalArgumentException failure) {
			LOG.error("{}", failure.getMessage());
			return 2;
		} catch (final IOException ignored) {
			LOG.error(
					"Proxy startup or runtime failed. Check the configuration file, nginx modules, CA bundle, DNS and local port availability. Wire diagnostics are suppressed.");
			return 1;
		} catch (final InterruptedException ignored) {
			Thread.currentThread().interrupt();
			return 1;
		} finally {
			if (password != null) {
				Arrays.fill(password, '\0');
			}
		}
	}
}
