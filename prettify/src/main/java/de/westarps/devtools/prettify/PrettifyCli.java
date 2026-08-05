package de.westarps.devtools.prettify;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import de.westarps.devtools.prettify.Prettifier.Plan;

/**
 * Command-line entry point for asserting or applying Java cleanup and
 * formatting across a Maven repository.
 * <p>
 * Exit code {@code 0} means success, {@code 1} means an assertion found files
 * that would change, and {@code 2} means the operation could not be completed.
 * <p>
 * Only a run that cleaned up and formatted every selected source exits
 * {@code 0}. A source OpenRewrite could not parse is formatted but not cleaned
 * up, which makes the run incomplete: the assertion cannot vouch for that
 * source, so both modes report {@code 2} rather than let a partial result look
 * like a passing check.
 */
public final class PrettifyCli {

	private enum Mode {
		ASSERT,
		APPLY
	}

	private static final class Arguments {

		private final List<String> modules = new ArrayList<>();

		private final List<Path> files = new ArrayList<>();

		private Mode mode = Mode.ASSERT;

		private boolean modeSet;

		private boolean prepare = true;

		private boolean help;

		private Path repo;

		private SourceScope scope = SourceScope.REPOSITORY;

		private boolean scopeSet;

		private String baseRef;

		void setScope(final SourceScope scope) {
			this.scope = scope;
			scopeSet = true;
		}

		void setMode(final Mode mode) {
			if (modeSet && this.mode != mode) {
				throw new PrettifyException("Use only one of --assert and --apply.");
			}
			this.mode = mode;
			modeSet = true;
		}

	}

	private PrettifyCli() {
	}

	public static void main(final String[] args) {
		System.exit(run(args));
	}

	static int run(final String[] args) {
		try {
			final Arguments arguments = parse(args);
			if (arguments.help) {
				printUsage();
				return 0;
			}
			final Path repo = resolveRepo(arguments.repo);
			final List<Path> files = arguments.files.stream().map(file -> resolveFile(repo, file)).toList();
			final Plan plan = new Prettifier().createPlan(repo, arguments.prepare, arguments.modules, files,
					arguments.scope, arguments.baseRef);
			printReport(plan, arguments.prepare, files.isEmpty() ? arguments.scope.option() : "explicit files");
			if (arguments.mode == Mode.APPLY) {
				plan.persist();
				System.out.println("Prettify applied " + plan.changedFiles().size() + " file(s).");
				return plan.isComplete() ? 0 : reportIncomplete(plan);
			}
			/*
			 * An incomplete run cannot support an assertion. Cleanup never ran
			 * for the skipped sources, so "no file would change" says nothing
			 * about them and must not be reported as a passing check.
			 */
			if (!plan.isComplete()) {
				if (!plan.changedFiles().isEmpty()) {
					System.err.println(
							"Prettify assertion failed: " + plan.changedFiles().size() + " file(s) would change.");
				}
				return reportIncomplete(plan);
			}
			if (plan.changedFiles().isEmpty()) {
				System.out.println("Prettify assertion passed.");
				return 0;
			}
			System.err.println("Prettify assertion failed: " + plan.changedFiles().size() + " file(s) would change.");
			return 1;
		} catch (final PrettifyException | IllegalArgumentException e) {
			System.err.println("Prettify failed: " + failureMessage(e));
			return 2;
		}
	}

	private static String failureMessage(final RuntimeException failure) {
		Throwable cause = failure;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		if (cause == failure || cause.getMessage() == null) {
			return failure.getMessage();
		}
		return failure.getMessage() + " " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
	}

	private static Arguments parse(final String[] args) {
		final Arguments arguments = new Arguments();
		for (int i = 0; i < args.length; i++) {
			final String argument = args[i];
			switch (argument) {
			case "--help", "-h" -> arguments.help = true;
			case "--assert" -> arguments.setMode(Mode.ASSERT);
			case "--apply" -> arguments.setMode(Mode.APPLY);
			case "--no-prepare" -> arguments.prepare = false;
			case "--module" -> arguments.modules.add(requiredValue(args, ++i, argument));
			case "--repo" -> arguments.repo = Path.of(requiredValue(args, ++i, argument));
			case "--select" -> arguments.setScope(SourceScope.parse(requiredValue(args, ++i, argument)));
			case "--base" -> arguments.baseRef = requiredValue(args, ++i, argument);
			default -> {
				if (argument.startsWith("--")) {
					throw new PrettifyException("Unknown option: " + argument + ".");
				}
				arguments.files.add(Path.of(argument));
			}
			}
		}
		validate(arguments);
		return arguments;
	}

	private static void validate(final Arguments arguments) {
		/*
		 * Explicit files and a scope are two answers to the same question.
		 * Silently letting one win would hide a mistaken invocation.
		 */
		if (arguments.scopeSet && !arguments.files.isEmpty()) {
			throw new PrettifyException("Use either --select or explicit Java files, not both.");
		}
		if (arguments.baseRef != null && arguments.scope != SourceScope.BRANCH) {
			throw new PrettifyException("--base only applies to --select " + SourceScope.BRANCH.option() + ".");
		}
	}

	private static String requiredValue(final String[] args, final int index, final String option) {
		if (index >= args.length || args[index].startsWith("--")) {
			throw new PrettifyException("Missing value for " + option + ".");
		}
		return args[index];
	}

	private static Path resolveRepo(final Path configuredRepo) {
		if (configuredRepo != null) {
			return validateRepo(configuredRepo.toAbsolutePath().normalize());
		}
		Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
		while (candidate != null) {
			if (isRepositoryRoot(candidate)) {
				return candidate;
			}
			candidate = candidate.getParent();
		}
		throw new PrettifyException("Could not locate a Maven repository root from the current directory.");
	}

	private static Path validateRepo(final Path repo) {
		if (!isRepositoryRoot(repo)) {
			throw new PrettifyException("Repository root must contain .git and pom.xml: " + repo + ".");
		}
		return repo;
	}

	// package-private so the checkout, worktree, and neither cases stay testable
	static boolean isRepositoryRoot(final Path candidate) {
		return isGitRoot(candidate) && Files.isRegularFile(candidate.resolve("pom.xml"));
	}

	/**
	 * Recognises both a normal checkout and a linked worktree. A worktree's
	 * {@code .git} is a regular file holding a {@code gitdir:} pointer rather
	 * than a directory, so testing only for a directory would reject every
	 * worktree.
	 */
	private static boolean isGitRoot(final Path candidate) {
		final Path git = candidate.resolve(".git");
		if (Files.isDirectory(git)) {
			return true;
		}
		if (!Files.isRegularFile(git)) {
			return false;
		}
		try {
			return Files.readString(git, UTF_8).stripLeading().startsWith("gitdir:");
		} catch (final IOException e) {
			return false;
		}
	}

	private static Path resolveFile(final Path repo, final Path file) {
		if (file.isAbsolute()) {
			return file.normalize();
		}
		final Path fromWorkingDirectory = Path.of(System.getProperty("user.dir")).resolve(file).normalize();
		return Files.exists(fromWorkingDirectory) ? fromWorkingDirectory : repo.resolve(file).normalize();
	}

	/**
	 * Reports sources that were formatted but never cleaned up, and yields the
	 * "could not complete" exit code. Prettify did not do everything it was
	 * asked to do, so no mode may exit successfully.
	 */
	private static int reportIncomplete(final Plan plan) {
		System.err.println("Prettify did not complete: OpenRewrite cleanup was skipped for "
				+ plan.skippedFiles().size() + " source(s). They were formatted but not cleaned up.");
		plan.skippedFiles()
				.forEach((file, reason) -> System.err.println(" - " + plan.repo().relativize(file) + ": " + reason));
		return 2;
	}

	private static void printReport(final Plan plan, final boolean prepared, final String selection) {
		System.out.println("Repository: " + plan.repo());
		System.out.println("Selection: " + selection);
		System.out.println("Prepared Maven reactor: " + prepared);
		System.out.println("Selected modules: " + plan.modules().size());
		System.out.println("Source files: " + plan.sourceFiles().size());
		System.out.println("Cleanup changed files: " + plan.cleanupChangedFiles().size());
		System.out.println("Changed files: " + plan.changedFiles().size());
		for (final Path changedFile : plan.changedFiles()) {
			System.out.println(" - " + plan.repo().relativize(changedFile));
		}
	}

	private static void printUsage() {
		System.out.println("Usage:");
		System.out.println("  prettify [--assert|--apply] [options] [java-file...]");
		System.out.println();
		System.out.println("Options:");
		System.out.println("  --assert             Check only. This is the default.");
		System.out.println("  --apply              Write cleanup and formatting changes.");
		System.out.println("  --select <scope>     Let prettify find the sources itself. One of:");
		System.out.println("                         repository   every tracked Java source (default)");
		System.out.println("                         branch       everything differing from the base branch,");
		System.out.println("                                      committed or not");
		System.out.println("                         uncommitted  staged, unstaged, and untracked changes");
		System.out.println("                       Cannot be combined with explicit java-file arguments.");
		System.out.println("  --base <ref>         Base branch for --select branch. Detected when omitted.");
		System.out.println("  --module <module>    Restrict to a Maven module path or artifactId. Repeatable.");
		System.out.println("  --no-prepare         Skip Maven compilation before resolving types.");
		System.out.println("  --repo <repo-root>   Repository root. Normally supplied by the launcher.");
		System.out.println("  --help               Show this help.");
		System.out.println();
		System.out.println("Examples:");
		System.out.println("  prettify --apply --select uncommitted    Format what you just changed.");
		System.out.println("  prettify --assert --select branch        Check the whole branch before a PR.");
	}

}
