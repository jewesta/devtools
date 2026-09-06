package de.westarps.devtools.authproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Real nginx + generated TLS certificates + a synthetic loopback IMAP server.
 * No live account.
 */
@EnabledIfSystemProperty(named = "nginx.tests", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NginxProxyTest {

	private static final String PASSWORD = "synthetic-ü-密-🔑-$:\"\\ end";
	private static final String USER = "synthetic@example.org";
	private static final byte[] MESSAGE = {
			'X', ':', ' ', (byte) 0xff, '\r', '\n', '\r', '\n', 0, 1, 2, '\r', '\n'
	};
	@TempDir
	static Path directory;
	private SSLContext tls;
	private Path trustedCa;
	private Path unrelatedCa;

	@BeforeAll
	void certificates() throws Exception {
		trustedCa = certificate("server");
		unrelatedCa = certificate("unrelated");
		final var store = KeyStore.getInstance("PKCS12");
		try (var input = Files.newInputStream(directory.resolve("server.p12"))) {
			store.load(input, "synthetic-only".toCharArray());
		}
		final var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		keys.init(store, "synthetic-only".toCharArray());
		tls = SSLContext.getInstance("TLS");
		tls.init(keys.getKeyManagers(), null, null);
	}

	@Test
	void substitutesCredentialsPreservesRawBytesAndCleansUp() throws Exception {
		final int localPort = freePort();
		Path runtime;
		try (var upstream = upstream()) {
			final var remote = CompletableFuture.runAsync(() -> exchange(upstream));
			try (var proxy = MailProxy.start(config("localhost", localPort, upstream.getLocalPort(), trustedCa),
					PASSWORD.toCharArray())) {
				runtime = proxy.clientConfig().getParent();
				assertThat(Files.getPosixFilePermissions(runtime))
						.isEqualTo(PosixFilePermissions.fromString("rwx------"));
				assertThat(Files.getPosixFilePermissions(proxy.clientConfig()))
						.isEqualTo(PosixFilePermissions.fromString("rw-------"));
				assertThat(runtime.resolve("nginx.conf")).doesNotExist();
				final var client = clientConfig(proxy);
				try (var socket = client(localPort)) {
					write(socket.getOutputStream(), "a1 LOGIN local-auth-proxy wrong-token\r\n");
					assertThat(line(socket.getInputStream())).contains("NO").doesNotContain(PASSWORD);
				}
				try (var socket = client(localPort)) {
					final var input = socket.getInputStream();
					final var output = socket.getOutputStream();
					write(output, "a1 LOGIN local-auth-proxy " + client.getProperty("proxy.password") + "\r\n");
					assertThat(line(input)).isEqualTo("a1 OK authenticated\r\n");
					write(output, "a2 CAPABILITY\r\n");
					assertThat(line(input)).contains("SAVEDATE");
					assertThat(line(input)).isEqualTo("a2 OK done\r\n");
					write(output, "a3 UID FETCH 7 (BODY.PEEK[] SAVEDATE)\r\n");
					assertThat(line(input))
							.isEqualTo("* 1 FETCH (UID 7 SAVEDATE \"06-Sep-2026 12:00:00 +0000\" BODY[] {"
									+ MESSAGE.length + "}\r\n");
					assertThat(input.readNBytes(MESSAGE.length)).isEqualTo(MESSAGE);
					assertThat(line(input)).isEqualTo(")\r\n");
					assertThat(line(input)).isEqualTo("a3 OK done\r\n");
				}
				remote.get(10, TimeUnit.SECONDS);
				try (var files = Files.list(runtime)) {
					for (final var file : files.toList()) {
						assertThat(Files.readString(file)).doesNotContain(PASSWORD, USER);
					}
				}
			}
		}
		assertThat(runtime).doesNotExist();
		assertThatThrownBy(() -> new Socket("127.0.0.1", localPort)).isInstanceOf(IOException.class);
	}

	@ParameterizedTest
	@ValueSource(booleans = {
			false, true
	})
	void rejectsUntrustedCertificatesAndWrongHostnamesBeforeSendingLogin(final boolean wrongHostname) throws Exception {
		final int localPort = freePort();
		try (var upstream = upstream()) {
			final var received = CompletableFuture.supplyAsync(() -> {
				try (var socket = upstream.accept()) {
					socket.setSoTimeout(5000);
					write(socket.getOutputStream(), "* OK synthetic server\r\n");
					return socket.getInputStream().read();
				} catch (final IOException expected) {
					return -1;
				}
			});
			try (var proxy = MailProxy.start(config(wrongHostname ? "127.0.0.1" : "localhost", localPort,
					upstream.getLocalPort(), wrongHostname ? trustedCa : unrelatedCa), PASSWORD.toCharArray());
					var socket = client(localPort)) {
				write(socket.getOutputStream(),
						"a1 LOGIN local-auth-proxy " + clientConfig(proxy).getProperty("proxy.password") + "\r\n");
				assertThat(line(socket.getInputStream())).doesNotContain("a1 OK", PASSWORD);
				assertThat(received.get(10, TimeUnit.SECONDS)).isEqualTo(-1);
			}
		}
	}

	@Test
	void refusesAnOccupiedPortWithoutStoppingItsOwner() throws Exception {
		try (var owner = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
			assertThatThrownBy(() -> MailProxy.start(config("localhost", owner.getLocalPort(), 993, trustedCa),
					PASSWORD.toCharArray())).isInstanceOf(IOException.class);
			assertThat(owner.isClosed()).isFalse();
		}
	}

	private void exchange(final SSLServerSocket upstream) {
		try (var socket = upstream.accept()) {
			socket.setSoTimeout(10000);
			final var input = socket.getInputStream();
			final var output = socket.getOutputStream();
			write(output, "* OK synthetic server\r\n");
			assertThat(line(input)).isEqualTo("a1 LOGIN {" + USER.length() + "}\r\n");
			write(output, "+ ready\r\n");
			assertThat(line(input)).isEqualTo(USER + " {" + PASSWORD.getBytes(StandardCharsets.UTF_8).length + "}\r\n");
			write(output, "+ ready\r\n");
			assertThat(line(input)).isEqualTo(PASSWORD + "\r\n");
			write(output, "a1 OK authenticated\r\n");
			assertThat(line(input)).isEqualTo("a2 CAPABILITY\r\n");
			write(output, "* CAPABILITY IMAP4rev1 SAVEDATE\r\na2 OK done\r\n");
			assertThat(line(input)).isEqualTo("a3 UID FETCH 7 (BODY.PEEK[] SAVEDATE)\r\n");
			write(output,
					"* 1 FETCH (UID 7 SAVEDATE \"06-Sep-2026 12:00:00 +0000\" BODY[] {" + MESSAGE.length + "}\r\n");
			output.write(MESSAGE);
			write(output, ")\r\na3 OK done\r\n");
		} catch (final IOException failure) {
			throw new IllegalStateException(failure);
		}
	}

	private SSLServerSocket upstream() throws IOException {
		final var socket = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket(0, 5,
				InetAddress.getByName("127.0.0.1"));
		socket.setSoTimeout(10000);
		return socket;
	}

	private ProxyConfig config(final String host, final int localPort, final int upstreamPort, final Path ca) {
		return new ProxyConfig(host, upstreamPort, USER, localPort, ca,
				System.getProperty("nginx.executable", "nginx"));
	}

	private static Properties clientConfig(final MailProxy proxy) throws IOException {
		final var properties = new Properties();
		try (var reader = Files.newBufferedReader(proxy.clientConfig())) {
			properties.load(reader);
		}
		return properties;
	}

	private static int freePort() throws IOException {
		try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
			return socket.getLocalPort();
		}
	}

	private static Socket client(final int port) throws IOException {
		final var socket = new Socket("127.0.0.1", port);
		socket.setSoTimeout(10000);
		assertThat(line(socket.getInputStream())).startsWith("* OK");
		return socket;
	}

	private static void write(final OutputStream output, final String value) throws IOException {
		output.write(value.getBytes(StandardCharsets.UTF_8));
		output.flush();
	}

	private static String line(final InputStream input) throws IOException {
		final var buffer = new ByteArrayOutputStream();
		while (buffer.size() < 16384) {
			final int b = input.read();
			if (b == -1) {
				break;
			}
			buffer.write(b);
			if (b == '\n') {
				break;
			}
		}
		return buffer.toString(StandardCharsets.UTF_8);
	}

	private Path certificate(final String name) throws Exception {
		final var store = directory.resolve(name + ".p12");
		final var pem = directory.resolve(name + ".pem");
		keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname",
				"CN=localhost", "-ext", "SAN=dns:localhost", "-ext", "BC=ca:true", "-storetype", "PKCS12", "-keystore",
				store.toString(), "-storepass", "synthetic-only");
		keytool("-exportcert", "-rfc", "-alias", "server", "-keystore", store.toString(), "-storepass",
				"synthetic-only", "-file", pem.toString());
		return pem;
	}

	private static void keytool(final String... args) throws Exception {
		final var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
		builder.command().addAll(java.util.List.of(args));
		final var process = builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
		if (!process.waitFor(20, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IOException("Synthetic certificate generation timed out");
		}
		assertThat(process.exitValue()).isZero();
	}
}
