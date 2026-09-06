package de.westarps.devtools.authproxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Minimal, bounded implementation of nginx's auth_http protocol. Never logs
 * wire data.
 */
final class CredentialServer implements AutoCloseable {

	static final String LOCAL_USER = "local-auth-proxy";
	private final ServerSocket listener;
	private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
			new ArrayBlockingQueue<>(16), Thread.ofPlatform().daemon().factory());
	private final String helperKey;
	private final String clientToken;
	private final byte[] password;
	private final byte[] responsePrefix;
	private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();

	CredentialServer(final String username, final char[] secret, final String helperKey, final String clientToken,
			final int bridgePort) throws IOException {
		validatePassword(secret);
		this.helperKey = helperKey;
		this.clientToken = clientToken;
		responsePrefix = ("HTTP/1.0 200 OK\r\nAuth-Status: OK\r\nAuth-Server: 127.0.0.1\r\nAuth-Port: " + bridgePort
				+ "\r\nAuth-User: " + username + "\r\nAuth-Pass: ").getBytes(StandardCharsets.US_ASCII);
		listener = new ServerSocket();
		listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16);
		final var encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(secret));
		password = new byte[encoded.remaining()];
		encoded.get(password);
		if (encoded.hasArray()) {
			Arrays.fill(encoded.array(), (byte) 0);
		}
		Thread.ofPlatform().daemon().name("proxy-auth-accept").start(this::accept);
	}

	static void validatePassword(final char[] secret) {
		if (secret == null || secret.length == 0 || secret.length > 512) {
			throw new IllegalArgumentException("Enter a password of 1–512 characters.");
		}
		if (Character.isWhitespace(secret[0]) || Character.isWhitespace(secret[secret.length - 1])) {
			throw new IllegalArgumentException(
					"The nginx credential bridge cannot preserve leading or trailing whitespace in a password.");
		}
		for (int i = 0; i < secret.length; i++) {
			if (Character.isISOControl(secret[i])) {
				throw new IllegalArgumentException(
						"The nginx credential bridge cannot carry control characters in a password.");
			}
			if (Character.isSurrogate(secret[i])) {
				if (!Character.isHighSurrogate(secret[i]) || i + 1 == secret.length
						|| !Character.isLowSurrogate(secret[++i])) {
					throw new IllegalArgumentException("The password contains invalid Unicode.");
				}
			}
		}
	}

	int port() {
		return listener.getLocalPort();
	}

	private void accept() {
		while (!listener.isClosed()) {
			try {
				final var socket = listener.accept();
				sockets.add(socket);
				try {
					workers.execute(() -> serve(socket));
				} catch (final RejectedExecutionException ignored) {
					socket.close();
					sockets.remove(socket);
				}
			} catch (final IOException ignored) {
				// Closing the listener ends the accept loop. No request or credential diagnostics.
				break;
			}
		}
	}

	private void serve(final Socket socket) {
		try (socket) {
			socket.setSoTimeout(5000);
			final var headers = readHeaders(socket);
			final boolean allowed = same(helperKey, headers.get("x-proxy-key"))
					&& same(clientToken, headers.get("auth-pass")) && LOCAL_USER.equals(headers.get("auth-user"))
					&& "plain".equals(headers.get("auth-method")) && "imap".equals(headers.get("auth-protocol"));
			final var output = socket.getOutputStream();
			if (allowed) {
				synchronized (password) {
					output.write(responsePrefix);
					output.write(password);
					output.write(
							"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
				}
			} else {
				output.write(("HTTP/1.0 200 OK\r\nAuth-Status: Authentication rejected\r\n"
						+ "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
			}
		} catch (final IOException ignored) {
			// Invalid, oversized, stalled, and disconnected requests fail closed.
		} finally {
			sockets.remove(socket);
		}
	}

	private static Map<String, String> readHeaders(final Socket socket) throws IOException {
		final var input = socket.getInputStream();
		final var bytes = new ByteArrayOutputStream();
		int ending = 0;
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (bytes.size() < 16384) {
			final long remaining = deadline - System.nanoTime();
			if (remaining <= 0) {
				throw new IOException("Auth request timed out");
			}
			socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
			final int b = input.read();
			if (b < 0 || b > 127 || b == 0) {
				throw new IOException("Invalid auth request");
			}
			bytes.write(b);
			ending = ending << 8 | b;
			if (ending == 0x0d0a0d0a) {
				final var lines = bytes.toString(StandardCharsets.US_ASCII).split("\r\n");
				if (!lines[0].equals("GET /auth HTTP/1.0") && !lines[0].equals("GET /auth HTTP/1.1")) {
					throw new IOException("Invalid auth request");
				}
				final Map<String, String> headers = new HashMap<>();
				for (int i = 1; i < lines.length; i++) {
					final int colon = lines[i].indexOf(':');
					if (colon < 1 || !lines[i].substring(0, colon).matches("[A-Za-z0-9-]+")
							|| headers.putIfAbsent(lines[i].substring(0, colon).toLowerCase(Locale.ROOT),
									lines[i].substring(colon + 1).strip()) != null) {
						throw new IOException("Invalid auth request");
					}
				}
				if (headers.containsKey("transfer-encoding")
						|| !headers.getOrDefault("content-length", "0").equals("0")) {
					throw new IOException("Invalid auth request");
				}
				return headers;
			}
		}
		throw new IOException("Oversized auth request");
	}

	private static boolean same(final String expected, final String actual) {
		return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
				actual.getBytes(StandardCharsets.US_ASCII));
	}

	@Override
	public void close() throws IOException {
		listener.close();
		for (final var socket : sockets) {
			socket.close();
		}
		sockets.clear();
		workers.shutdownNow();
		synchronized (password) {
			Arrays.fill(password, (byte) 0);
		}
	}
}
