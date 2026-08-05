package de.westarps.devtools.prettify;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.RecipeRun;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.java.JavaParser;

import de.westarps.devtools.prettify.MavenReactor.Module;

/**
 * Parses selected sources with module-specific classpaths and overlays only the
 * in-scope changes produced by the configured OpenRewrite recipe.
 */
final class RewriteCleaner {

	/**
	 * @param skippedFiles
	 *            sources OpenRewrite could not parse, mapped to the reason.
	 *            Cleanup did not run for them, so the caller must not report
	 *            the run as a completed check.
	 */
	record ResultOverlay(Map<Path, String> sources, List<Path> changedFiles, Map<Path, String> skippedFiles) {

		ResultOverlay {
			sources = Map.copyOf(sources);
			changedFiles = List.copyOf(changedFiles);
			skippedFiles = Map.copyOf(skippedFiles);
		}

	}

	ResultOverlay clean(final Path repo, final SourceSelection selection,
			final Map<Module, List<Path>> moduleClasspaths) {
		if (selection.files().isEmpty()) {
			return new ResultOverlay(Map.of(), List.of(), Map.of());
		}

		final InMemoryExecutionContext context = new InMemoryExecutionContext(throwable -> {
			throw new PrettifyException("OpenRewrite cleanup failed.", throwable);
		});
		final Map<Path, String> skippedFiles = new LinkedHashMap<>();
		final List<SourceFile> parsedSources = parseByModule(repo, selection, moduleClasspaths, context, skippedFiles);
		final Recipe recipe = new JavaCleanup();
		final RecipeRun recipeRun = recipe.run(new InMemoryLargeSourceSet(parsedSources), context);
		return collectResults(repo, selection.files(), recipeRun.getChangeset().getAllResults(), skippedFiles);
	}

	private static List<SourceFile> parseByModule(final Path repo, final SourceSelection selection,
			final Map<Module, List<Path>> moduleClasspaths, final InMemoryExecutionContext context,
			final Map<Path, String> skippedFiles) {
		final List<SourceFile> parsedSources = new ArrayList<>();
		for (final Module module : selection.modules()) {
			final List<Path> moduleFiles = selection.files().stream().filter(module::contains).toList();
			if (moduleFiles.isEmpty()) {
				continue;
			}
			final List<Path> classpath = moduleClasspaths.getOrDefault(module, List.of());
			try {
				parsedSources.addAll(parse(repo, moduleFiles, classpath, context));
			} catch (final RuntimeException e) {
				parsedSources.addAll(
						parseSkippingUnparseable(repo, module, moduleFiles, classpath, context, e, skippedFiles));
			}
		}
		return List.copyOf(parsedSources);
	}

	/**
	 * Recovers from a source OpenRewrite cannot parse.
	 * <p>
	 * A single source that trips a parser bug must not abort a whole repository
	 * sweep, so the offending sources are identified and excluded and the rest
	 * of the module is parsed as one batch, keeping full type resolution for
	 * the sources that survive.
	 * <p>
	 * Recovery is not forgiveness. The excluded sources are recorded so the CLI
	 * can report the run as incomplete; cleanup did not run for them and no
	 * caller may read success as "these sources are clean".
	 */
	private static List<SourceFile> parseSkippingUnparseable(final Path repo, final Module module,
			final List<Path> moduleFiles, final List<Path> classpath, final InMemoryExecutionContext context,
			final RuntimeException moduleFailure, final Map<Path, String> skippedFiles) {

		final List<Path> parseable = new ArrayList<>();
		final Map<Path, String> unparseable = new LinkedHashMap<>();
		for (final Path file : moduleFiles) {
			try {
				parse(repo, List.of(file), classpath, context);
				parseable.add(file);
			} catch (final RuntimeException e) {
				unparseable.put(file, rootMessage(e));
			}
		}

		if (unparseable.isEmpty()) {
			/*
			 * The module fails as a batch but every source parses alone, so the
			 * cause is not attributable to one file. Report the original
			 * failure rather than invent an explanation.
			 */
			throw new PrettifyException("OpenRewrite could not parse Maven module " + module.displayPath() + ".",
					moduleFailure);
		}

		skippedFiles.putAll(unparseable);

		if (parseable.isEmpty()) {
			return List.of();
		}
		try {
			return parse(repo, parseable, classpath, context);
		} catch (final RuntimeException e) {
			throw new PrettifyException("OpenRewrite could not parse Maven module " + module.displayPath()
					+ " even after excluding its unparseable sources.", e);
		}
	}

	private static List<SourceFile> parse(final Path repo, final List<Path> files, final List<Path> classpath,
			final InMemoryExecutionContext context) {
		return JavaParser.fromJavaVersion().classpath(classpath).build().parse(files, repo, context).toList();
	}

	private static String rootMessage(final RuntimeException failure) {
		Throwable cause = failure;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause.getClass().getSimpleName() + ": " + cause.getMessage();
	}

	private static ResultOverlay collectResults(final Path repo, final List<Path> writableFiles,
			final List<Result> results, final Map<Path, String> skippedFiles) {
		final Set<Path> writable = new LinkedHashSet<>(writableFiles);
		final Map<Path, String> sources = new LinkedHashMap<>();
		for (final Result result : results) {
			if (result.getAfter() == null) {
				throw new PrettifyException(
						"OpenRewrite attempted to delete " + result.getBefore().getSourcePath() + ".");
			}
			final Path file = repo.resolve(result.getAfter().getSourcePath()).normalize();
			final String after = result.getAfter().printAll();
			if (writable.contains(file) && !after.equals(result.getBefore().printAll())) {
				sources.put(file, after);
			}
		}
		return new ResultOverlay(sources, sources.keySet().stream().sorted(Comparator.naturalOrder()).toList(),
				skippedFiles);
	}

}
