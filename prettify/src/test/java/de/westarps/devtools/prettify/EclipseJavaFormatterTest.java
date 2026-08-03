package de.westarps.devtools.prettify;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EclipseJavaFormatterTest {

	@Test
	void bundledProfileFormatsJavaSource() {
		final String source = "package example;\n\nclass Sample{void run(){System.out.println(\"hello\");}}\n";

		final EclipseJavaFormatter.FormattedSource formatted = new EclipseJavaFormatter().format(source, source);

		assertTrue(formatted.changed());
		assertTrue(formatted.content().contains("class Sample {"));
		assertTrue(formatted.content().contains("void run() {"));
	}

	@Test
	void formattingPreservesWindowsLineEndings() {
		final String source = "package example;\r\n\r\nclass Sample{}\r\n";

		final String formatted = new EclipseJavaFormatter().format(source, source).content();

		assertTrue(formatted.contains("\r\n"));
		assertFalse(formatted.replace("\r\n", "").contains("\n"));
	}

}
