package de.westarps.devtools.prettify;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.westarps.devtools.prettify.MavenReactor.Module;
import de.westarps.devtools.prettify.RewriteCleaner.ResultOverlay;

class RewriteCleanerTest {

	/**
	 * Valid Javadoc that OpenRewrite 8.85.0 cannot parse.
	 * <p>
	 * {@code ReloadableJava21JavadocVisitor.visitThrows} decides which tag to
	 * look for with {@code source.startsWith("@throws", cursor)}, an exact
	 * position test, and then searches forward from the cursor. When the cursor
	 * still trails the preceding tag's description the test fails, so it
	 * searches for a {@code @exception} that was never written and throws.
	 * <p>
	 * Two {@code @throws} tags alone do not reproduce it; the wrapped
	 * {@code @return} description in front of them is part of the trigger. The
	 * exact minimal shape has not been isolated, so this sample stays close to
	 * the real source that first surfaced the failure.
	 */
	private static final String UNPARSEABLE = """
		package example;

		import java.io.IOException;
		import java.util.Optional;

		public interface Unparseable {

			/**
			 * Synchronously inspects the content of an archive file.
			 * <p>
			 * The accessor receives an open stream that is closed as soon as it
			 * returns. Escaping streams must not be retained.
			 *
			 * @return the inspected value, or an empty optional if the archive source
			 *         cannot expose the file content
			 * @throws java.nio.file.NoSuchFileException
			 *             if the archive source has no file at the ARI
			 * @throws IllegalArgumentException
			 *             if the ARI belongs to another collection or an unknown
			 *             archive
			 */
			<T> Optional<T> inspect(String source) throws IOException;
		}
		""";

	private static final String CLEANABLE = """
		package example;

		public class Cleanable {
			public int run() {
				int value = 1;
				return value;
			}
		}
		""";

	@TempDir
	private Path repo;

	@Test
	void anUnparseableSourceIsSkippedSoTheRestOfTheModuleStillGetsCleanedUp() throws Exception {
		final Path unparseable = write("src/main/java/example/Unparseable.java", UNPARSEABLE);
		final Path cleanable = write("src/main/java/example/Cleanable.java", CLEANABLE);

		final ResultOverlay overlay = clean();

		/*
		 * The surviving source still receives cleanup, which is the point of
		 * recovering rather than aborting: OpenRewrite adds the missing final.
		 */
		assertEquals(List.of(cleanable), overlay.changedFiles());
		assertTrue(overlay.sources().get(cleanable).contains("final int value"));

		// The skip is recorded so the CLI can refuse to report success.
		assertEquals(Set.of(unparseable), overlay.skippedFiles().keySet());
		assertTrue(overlay.skippedFiles().get(unparseable).contains("@exception"), overlay.skippedFiles().toString());
	}

	@Test
	void aModuleWithoutUnparseableSourcesRecordsNoSkips() throws Exception {
		final Path cleanable = write("src/main/java/example/Cleanable.java", CLEANABLE);

		final ResultOverlay overlay = clean();

		assertTrue(overlay.skippedFiles().isEmpty(), overlay.skippedFiles().toString());
		assertEquals(List.of(cleanable), overlay.changedFiles());
	}

	private ResultOverlay clean() throws IOException {
		final Module module = new Module(repo, Path.of(""), "example");
		final SourceSelection selection = new SourceSelection(List.of(module), javaSourcesUnder(repo), true);
		return new RewriteCleaner().clean(repo, selection, Map.of(module, List.of()));
	}

	private static List<Path> javaSourcesUnder(final Path root) throws IOException {
		try (var paths = Files.walk(root)) {
			return paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
		}
	}

	private Path write(final String relativePath, final String content) throws IOException {
		final Path file = repo.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, UTF_8);
		return file;
	}

}
