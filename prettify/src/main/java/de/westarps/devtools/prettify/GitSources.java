package de.westarps.devtools.prettify;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves a {@link SourceScope} to concrete Java sources by asking Git.
 * <p>
 * Git is invoked as a subprocess rather than through a library so that prettify
 * keeps working against whatever Git the surrounding checkout already uses,
 * including linked worktrees and alternate object stores.
 */
final class GitSources {

	/**
	 * Candidate base branches, tried in order when Git does not publish
	 * {@code origin/HEAD}. A repository cloned without a remote head still
	 * usually has one of these.
	 */
	private static final List<String> FALLBACK_BASE_REFS = List.of("origin/main", "origin/master", "main", "master");

	private final Path repo;

	GitSources(final Path repo) {
		this.repo = repo;
	}

	List<Path> javaSources(final SourceScope scope, final String requestedBaseRef) {
		return switch (scope) {
		case REPOSITORY -> tracked();
		case UNCOMMITTED -> uncommitted();
		case BRANCH -> branch(requestedBaseRef);
		};
	}

	private List<Path> tracked() {
		return paths(git("ls-files", "-z", "--", "*.java"));
	}

	private List<Path> uncommitted() {
		final Set<Path> sources = new LinkedHashSet<>();
		/*
		 * A repository without any commit has no HEAD to diff against, so the
		 * staged set is the only committed-side information available.
		 */
		if (hasHead()) {
			sources.addAll(paths(git("diff", "--name-only", "-z", "HEAD", "--", "*.java")));
		} else {
			sources.addAll(paths(git("diff", "--name-only", "-z", "--cached", "--", "*.java")));
		}
		sources.addAll(untracked());
		return List.copyOf(sources);
	}

	private List<Path> branch(final String requestedBaseRef) {
		final String baseRef = resolveBaseRef(requestedBaseRef);
		final String mergeBase = mergeBase(baseRef);
		final Set<Path> sources = new LinkedHashSet<>();
		/*
		 * Diffing the working tree against the merge base rather than against
		 * the base branch tip keeps unrelated commits that landed on the base
		 * out of the result, and covers committed and uncommitted work in one
		 * comparison.
		 */
		sources.addAll(paths(git("diff", "--name-only", "-z", mergeBase, "--", "*.java")));
		sources.addAll(untracked());
		return List.copyOf(sources);
	}

	private List<Path> untracked() {
		return paths(git("ls-files", "-z", "--others", "--exclude-standard", "--", "*.java"));
	}

	private boolean hasHead() {
		return run(List.of("git", "rev-parse", "--verify", "--quiet", "HEAD")).exitCode() == 0;
	}

	private String resolveBaseRef(final String requestedBaseRef) {
		if (requestedBaseRef != null) {
			if (!refExists(requestedBaseRef)) {
				throw new PrettifyException("Base ref does not exist: " + requestedBaseRef + ".");
			}
			return requestedBaseRef;
		}

		final Result originHead = run(List.of("git", "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD"));
		if (originHead.exitCode() == 0 && !originHead.output().isBlank()) {
			return originHead.output().strip();
		}
		for (final String candidate : FALLBACK_BASE_REFS) {
			if (refExists(candidate)) {
				return candidate;
			}
		}
		throw new PrettifyException("Could not determine a base branch. Pass --base <ref> explicitly.");
	}

	private boolean refExists(final String ref) {
		return run(List.of("git", "rev-parse", "--verify", "--quiet", ref + "^{commit}")).exitCode() == 0;
	}

	private String mergeBase(final String baseRef) {
		final Result result = run(List.of("git", "merge-base", baseRef, "HEAD"));
		if (result.exitCode() != 0 || result.output().isBlank()) {
			/*
			 * An unrelated history has no merge base. Comparing against the
			 * base tip is still the most useful answer available.
			 */
			return baseRef;
		}
		return result.output().strip();
	}

	private byte[] git(final String... arguments) {
		final List<String> command = new ArrayList<>();
		command.add("git");
		command.addAll(List.of(arguments));
		final Result result = run(command);
		if (result.exitCode() != 0) {
			throw new PrettifyException(
					String.join(" ", command) + " failed with exit code " + result.exitCode() + ": " + result.output());
		}
		return result.bytes();
	}

	private Result run(final List<String> command) {
		try {
			final Process process = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true)
					.start();
			final ByteArrayOutputStream output = new ByteArrayOutputStream();
			process.getInputStream().transferTo(output);
			return new Result(process.waitFor(), output.toByteArray());
		} catch (final IOException e) {
			throw new PrettifyException("Failed to run " + String.join(" ", command) + ".", e);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new PrettifyException("Interrupted while running " + String.join(" ", command) + ".", e);
		}
	}

	/**
	 * Converts Git's NUL-separated output into existing regular files. Paths
	 * that no longer exist are dropped, which is how deletions leave every
	 * scope.
	 */
	private List<Path> paths(final byte[] output) {
		final List<Path> paths = new ArrayList<>();
		int start = 0;
		for (int i = 0; i < output.length; i++) {
			if (output[i] != 0) {
				continue;
			}
			if (i > start) {
				final Path path = repo.resolve(new String(output, start, i - start, UTF_8)).normalize();
				if (Files.isRegularFile(path)) {
					paths.add(path);
				}
			}
			start = i + 1;
		}
		return List.copyOf(paths);
	}

	private record Result(int exitCode, byte[] bytes) {

		String output() {
			return new String(bytes, UTF_8);
		}

	}

}
