package de.westarps.devtools.prettify;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers argument handling and repository-root detection, which are the parts
 * of the CLI that run before any Maven or Git work begins.
 */
class PrettifyCliArgumentsTest {

	@TempDir
	private Path directory;

	@Test
	void rejectsAnUnknownSelectionScope() {
		assertEquals(2, PrettifyCli.run(new String[] {
				"--select", "everything"
		}));
	}

	@Test
	void rejectsCombiningAScopeWithExplicitFiles() {
		assertEquals(2, PrettifyCli.run(new String[] {
				"--select", "uncommitted", "Some.java"
		}));
	}

	@Test
	void rejectsABaseRefOutsideBranchScope() {
		assertEquals(2, PrettifyCli.run(new String[] {
				"--select", "uncommitted", "--base", "main"
		}));
	}

	@Test
	void helpSucceedsAndDocumentsEveryScope() {
		assertEquals(0, PrettifyCli.run(new String[] {
				"--help"
		}));
		for (final SourceScope scope : SourceScope.values()) {
			assertTrue(SourceScope.options().contains(scope.option()));
		}
	}

	@Test
	void scopeNamesRoundTripThroughParsing() {
		for (final SourceScope scope : SourceScope.values()) {
			assertEquals(scope, SourceScope.parse(scope.option()));
			assertEquals(scope, SourceScope.parse(scope.option().toUpperCase(java.util.Locale.ROOT)));
		}
	}

	@Test
	void aNormalCheckoutIsAcceptedAsARepositoryRoot() throws Exception {
		Files.createDirectory(directory.resolve(".git"));
		Files.writeString(directory.resolve("pom.xml"), "<project/>", UTF_8);

		assertTrue(PrettifyCli.isRepositoryRoot(directory));
	}

	@Test
	void aLinkedWorktreeIsAcceptedAsARepositoryRoot() throws Exception {
		/*
		 * A worktree's .git is a file pointing at the real Git directory. This
		 * is the shape that a directory-only check rejects.
		 */
		Files.writeString(directory.resolve(".git"), "gitdir: /elsewhere/.git/worktrees/example\n", UTF_8);
		Files.writeString(directory.resolve("pom.xml"), "<project/>", UTF_8);

		assertTrue(PrettifyCli.isRepositoryRoot(directory));
	}

	@Test
	void aGitFileWithoutAGitdirPointerIsRejected() throws Exception {
		Files.writeString(directory.resolve(".git"), "this is not a gitdir pointer\n", UTF_8);
		Files.writeString(directory.resolve("pom.xml"), "<project/>", UTF_8);

		assertFalse(PrettifyCli.isRepositoryRoot(directory));
	}

	@Test
	void aMissingPomIsRejected() throws Exception {
		Files.createDirectory(directory.resolve(".git"));

		assertFalse(PrettifyCli.isRepositoryRoot(directory));
	}

	@Test
	void aDirectoryWithoutGitIsRejected() throws Exception {
		Files.writeString(directory.resolve("pom.xml"), "<project/>", UTF_8);

		assertFalse(PrettifyCli.isRepositoryRoot(directory));
	}

}
