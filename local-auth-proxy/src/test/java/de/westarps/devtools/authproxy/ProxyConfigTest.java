package de.westarps.devtools.authproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProxyConfigTest {

	@TempDir
	Path directory;

	@Test
	void loadsNonSecretConfigurationAndRejectsPasswordProperties() throws Exception {
		final var ca = Files.writeString(directory.resolve("test ca.pem"), "synthetic");
		final var file = directory.resolve("proxy.properties");
		final var contents = "imap.host=mail.example.org\nimap.username=synthetic@example.org\ntls.ca-file=" + ca
				+ "\n";
		Files.writeString(file, contents);
		final var config = ProxyConfig.load(file);
		assertThat(config.listenPort()).isEqualTo(1143);
		assertThat(config.upstreamPort()).isEqualTo(993);
		Files.writeString(file, contents + "imap.password=do-not-echo-this\n");
		assertThatThrownBy(() -> ProxyConfig.load(file)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageNotContaining("do-not-echo-this");
	}

	@Test
	void rejectsConfigInjectionAndDoesNotEchoInvalidValues() throws Exception {
		final var ca = Files.writeString(directory.resolve("ca.pem"), "synthetic");
		assertThatThrownBy(() -> new ProxyConfig("mail.example; include stolen;", 993, "user", 1143, ca, "nginx"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("stolen");
		assertThatThrownBy(() -> new ProxyConfig("mail.example", 993, "user\r\nAuth-Status: OK", 1143, ca, "nginx"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ProxyConfig("mail.example", 993, "user", 143, ca, "nginx"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ProxyConfig.nginxString("/tmp/$variable.pem"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(ProxyConfig.nginxString("/tmp/a b\";#.pem")).isEqualTo("\"/tmp/a b\\\";#.pem\"");
	}
}
