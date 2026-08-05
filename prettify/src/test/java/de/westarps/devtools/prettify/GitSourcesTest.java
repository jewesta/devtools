package de.westarps.devtools.prettify;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitSourcesTest {

	@TempDir
	private Path repo;

	@Test
	void repositoryScopeListsEveryTrackedJavaSource() throws Exception {
		initRepository();
		write("src/Kept.java", "class Kept {}");
		write("notes.txt", "not java");
		commit("initial");

		final List<Path> sources = new GitSources(repo).javaSources(SourceScope.REPOSITORY, null);

		assertEquals(List.of(repo.resolve("src/Kept.java")), sources);
	}

	@Test
	void uncommittedScopeCoversStagedUnstagedAndUntrackedSources() throws Exception {
		initRepository();
		write("src/Committed.java", "class Committed {}");
		write("src/Staged.java", "class Staged {}");
		commit("initial");

		write("src/Staged.java", "class Staged { int a; }");
		git("add", "src/Staged.java");
		write("src/Committed.java", "class Committed { int b; }");
		write("src/Untracked.java", "class Untracked {}");

		final List<Path> sources = new GitSources(repo).javaSources(SourceScope.UNCOMMITTED, null);

		assertEquals(3, sources.size());
		assertTrue(sources.contains(repo.resolve("src/Staged.java")));
		assertTrue(sources.contains(repo.resolve("src/Committed.java")));
		assertTrue(sources.contains(repo.resolve("src/Untracked.java")));
	}

	@Test
	void uncommittedScopeIgnoresAnUnchangedSource() throws Exception {
		initRepository();
		write("src/Untouched.java", "class Untouched {}");
		commit("initial");

		assertEquals(List.of(), new GitSources(repo).javaSources(SourceScope.UNCOMMITTED, null));
	}

	@Test
	void branchScopeCoversCommittedAndUncommittedWorkSinceTheBase() throws Exception {
		initRepository();
		write("src/Base.java", "class Base {}");
		commit("initial");
		git("branch", "main");

		git("checkout", "-b", "feature");
		write("src/CommittedOnBranch.java", "class CommittedOnBranch {}");
		commit("branch work");
		write("src/StillDirty.java", "class StillDirty {}");

		final List<Path> sources = new GitSources(repo).javaSources(SourceScope.BRANCH, "main");

		assertEquals(2, sources.size());
		assertTrue(sources.contains(repo.resolve("src/CommittedOnBranch.java")));
		assertTrue(sources.contains(repo.resolve("src/StillDirty.java")));
		assertFalse(sources.contains(repo.resolve("src/Base.java")));
	}

	@Test
	void branchScopeIgnoresCommitsThatLandedOnTheBaseAfterForking() throws Exception {
		initRepository();
		write("src/Base.java", "class Base {}");
		commit("initial");
		git("branch", "main");

		git("checkout", "-b", "feature");
		write("src/OnBranch.java", "class OnBranch {}");
		commit("branch work");

		git("checkout", "main");
		write("src/LaterOnMain.java", "class LaterOnMain {}");
		commit("later main work");
		git("checkout", "feature");

		final List<Path> sources = new GitSources(repo).javaSources(SourceScope.BRANCH, "main");

		assertEquals(List.of(repo.resolve("src/OnBranch.java")), sources);
	}

	@Test
	void deletedSourcesLeaveEveryScope() throws Exception {
		initRepository();
		write("src/Doomed.java", "class Doomed {}");
		commit("initial");
		git("rm", "src/Doomed.java");

		assertEquals(List.of(), new GitSources(repo).javaSources(SourceScope.UNCOMMITTED, null));
		assertEquals(List.of(), new GitSources(repo).javaSources(SourceScope.REPOSITORY, null));
	}

	@Test
	void anAbsentBaseRefIsReportedRatherThanSilentlyIgnored() throws Exception {
		initRepository();
		write("src/Any.java", "class Any {}");
		commit("initial");

		final GitSources sources = new GitSources(repo);
		final PrettifyException failure = assertThrows(PrettifyException.class,
				() -> sources.javaSources(SourceScope.BRANCH, "no-such-branch"));

		assertTrue(failure.getMessage().contains("no-such-branch"));
	}

	private void initRepository() throws Exception {
		git("init", "--initial-branch=work");
		git("config", "user.email", "prettify@example.com");
		git("config", "user.name", "Prettify Test");
	}

	private void write(final String relativePath, final String content) throws IOException {
		final Path file = repo.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content + "\n", UTF_8);
	}

	private void commit(final String message) throws Exception {
		git("add", "-A");
		git("commit", "-m", message);
	}

	private void git(final String... arguments) throws Exception {
		final List<String> command = new java.util.ArrayList<>();
		command.add("git");
		command.addAll(List.of(arguments));
		final Process process = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();
		final String output = new String(process.getInputStream().readAllBytes(), UTF_8);
		final int exitCode = process.waitFor();
		if (exitCode != 0) {
			throw new IllegalStateException(String.join(" ", command) + " failed: " + output);
		}
	}

}
