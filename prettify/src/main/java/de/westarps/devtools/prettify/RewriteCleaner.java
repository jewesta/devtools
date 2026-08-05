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

	record ResultOverlay(Map<Path, String> sources, List<Path> changedFiles) {

		ResultOverlay {
			sources = Map.copyOf(sources);
			changedFiles = List.copyOf(changedFiles);
		}

	}

	ResultOverlay clean(final Path repo, final SourceSelection selection,
			final Map<Module, List<Path>> moduleClasspaths) {
		if (selection.files().isEmpty()) {
			return new ResultOverlay(Map.of(), List.of());
		}

		final InMemoryExecutionContext context = new InMemoryExecutionContext(throwable -> {
			throw new PrettifyException("OpenRewrite cleanup failed.", throwable);
		});
		final List<SourceFile> parsedSources = parseByModule(repo, selection, moduleClasspaths, context);
		final Recipe recipe = new JavaCleanup();
		final RecipeRun recipeRun = recipe.run(new InMemoryLargeSourceSet(parsedSources), context);
		return collectResults(repo, selection.files(), recipeRun.getChangeset().getAllResults());
	}

	private static List<SourceFile> parseByModule(final Path repo, final SourceSelection selection,
			final Map<Module, List<Path>> moduleClasspaths, final InMemoryExecutionContext context) {
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
				parsedSources.addAll(parseSkippingUnparseable(repo, module, moduleFiles, classpath, context, e));
			}
		}
		return List.copyOf(parsedSources);
	}

	/**
	 * Recovers from a source OpenRewrite cannot parse.
	 * <p>
	 * Cleanup is an enrichment; Eclipse formatting is the guarantee prettify
	 * actually makes. A single source that trips a parser bug therefore must
	 * not abort a whole repository sweep. The offending sources are identified,
	 * reported, and excluded, and the rest of the module is parsed as one batch
	 * so the remaining sources keep full type resolution.
	 */
	private static List<SourceFile> parseSkippingUnparseable(final Path repo, final Module module,
			final List<Path> moduleFiles, final List<Path> classpath, final InMemoryExecutionContext context,
			final RuntimeException moduleFailure) {

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

		System.err.println("Skipping OpenRewrite cleanup for " + unparseable.size() + " unparseable source(s) in "
				+ module.displayPath() + ". Formatting still applies to them.");
		unparseable.forEach((file, message) -> System.err.println(" - " + repo.relativize(file) + ": " + message));

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
			final List<Result> results) {
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
		return new ResultOverlay(sources, sources.keySet().stream().sorted(Comparator.naturalOrder()).toList());
	}

}
