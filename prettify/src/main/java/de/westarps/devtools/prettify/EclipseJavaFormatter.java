package de.westarps.devtools.prettify;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Applies the bundled Eclipse code-formatter profile to Java compilation units
 * while preserving each source file's original line-ending convention.
 */
final class EclipseJavaFormatter {

	private static final String PROFILE_RESOURCE = "/formatting-rules.xml";

	record FormattedSource(String content, boolean changed) {
	}

	private final CodeFormatter formatter;

	EclipseJavaFormatter() {
		try {
			final Map<String, String> options = new LinkedHashMap<>(loadOptions());
			options.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_21);
			options.put(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_21);
			options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_21);
			formatter = ToolFactory.createCodeFormatter(options);
		} catch (final Exception e) {
			throw new PrettifyException("Failed to load the bundled Eclipse formatter profile.", e);
		}
	}

	FormattedSource format(final String source, final String original) {
		try {
			final TextEdit edit = formatter.format(CodeFormatter.K_COMPILATION_UNIT | CodeFormatter.F_INCLUDE_COMMENTS,
					source, 0, source.length(), 0, "\n");
			if (edit == null) {
				throw new PrettifyException("Eclipse JDT could not format a Java compilation unit.");
			}
			final Document document = new Document(source);
			edit.apply(document);
			final String formatted = localizeLineEndings(document.get(), original);
			return new FormattedSource(formatted, !formatted.equals(original));
		} catch (final PrettifyException e) {
			throw e;
		} catch (final Exception e) {
			throw new PrettifyException("Eclipse JDT failed to format a Java compilation unit.", e);
		}
	}

	private static Map<String, String> loadOptions() throws Exception {
		final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		try (InputStream inputStream = EclipseJavaFormatter.class.getResourceAsStream(PROFILE_RESOURCE)) {
			if (inputStream == null) {
				throw new IOException("Missing classpath resource " + PROFILE_RESOURCE + ".");
			}
			final org.w3c.dom.Document document = factory.newDocumentBuilder().parse(inputStream);
			final NodeList settings = document.getElementsByTagName("setting");
			final Map<String, String> options = new LinkedHashMap<>();
			for (int i = 0; i < settings.getLength(); i++) {
				final Element setting = (Element) settings.item(i);
				options.put(setting.getAttribute("id"), setting.getAttribute("value"));
			}
			return Map.copyOf(options);
		}
	}

	private static String localizeLineEndings(final String formatted, final String original) {
		return original.contains("\r\n") ? formatted.replace("\n", "\r\n") : formatted;
	}

}
