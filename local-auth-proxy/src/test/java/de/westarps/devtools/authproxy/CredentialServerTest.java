package de.westarps.devtools.authproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class CredentialServerTest {

	private static final String PASSWORD = "synthetic-ü-密-🔑-$:\"\\ end";
	private static final String HEADERS = "Auth-Method: plain\r\nAuth-Protocol: imap\r\nAuth-User: local-auth-proxy\r\n";

	@Test
	void disclosesUpstreamCredentialsOnlyWithBothIndependentTokens() throws Exception {
		try (var server = new CredentialServer("remote-user", PASSWORD.toCharArray(), "helper-key", "client-token",
				12345)) {
			final var allowed = request(server, HEADERS + "X-Proxy-Key: helper-key\r\nAuth-Pass: client-token\r\n");
			assertThat(allowed).contains("Auth-Status: OK\r\n", "Auth-User: remote-user\r\n",
					"Auth-Pass: " + PASSWORD + "\r\n");
			assertThat(request(server, HEADERS + "Auth-Pass: client-token\r\n"))
					.doesNotContain(PASSWORD, "remote-user", "Auth-Server:").contains("Authentication rejected");
			assertThat(request(server, HEADERS + "X-Proxy-Key: helper-key\r\nAuth-Pass: wrong\r\n"))
					.doesNotContain(PASSWORD).contains("Authentication rejected");
			assertThat(request(server, HEADERS + "X-Proxy-Key: client-token\r\nAuth-Pass: client-token\r\n"))
					.doesNotContain(PASSWORD);
		}
	}

	@Test
	void rejectsDuplicateOversizedAndUnsupportedRequests() throws Exception {
		try (var server = new CredentialServer("remote-user", PASSWORD.toCharArray(), "helper-key", "client-token",
				12345)) {
			final var valid = HEADERS + "X-Proxy-Key: helper-key\r\nAuth-Pass: client-token\r\n";
			assertThat(request(server, valid + "auth-pass: client-token\r\n")).isEmpty();
			assertThat(request(server, valid.replace("plain", "cram-md5"))).doesNotContain(PASSWORD);
			assertThat(request(server, valid + "Content-Length: 20\r\n")).isEmpty();
			assertThat(request(server, valid + "Extra: " + "x".repeat(16400) + "\r\n")).isEmpty();
		}
	}

	@Test
	void rejectsCredentialsThatCannotRoundTripWithoutPuttingThemInErrors() {
		for (final var invalid : new String[] {
				"", "secret\r\nInjected: yes", " padded", "padded ", "nul\0here", "unpaired\uD800"
		}) {
			assertThatThrownBy(() -> CredentialServer.validatePassword(invalid.toCharArray()))
					.isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("Injected")
					.hasMessageNotContaining("padded").hasMessageNotContaining("unpaired");
		}
	}

	private static String request(final CredentialServer server, final String headers) throws Exception {
		try (var socket = new Socket("127.0.0.1", server.port())) {
			socket.setSoTimeout(6000);
			socket.getOutputStream()
					.write(("GET /auth HTTP/1.0\r\n" + headers + "\r\n").getBytes(StandardCharsets.US_ASCII));
			try {
				return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			} catch (final SocketException rejected) {
				if (headers.length() < 16384) {
					throw rejected;
				}
				return ""; // Closing with unread oversized input can reset TCP.
			}
		}
	}
}
