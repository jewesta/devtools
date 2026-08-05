package de.westarps.devtools.prettify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.westarps.devtools.prettify.MavenReactor.Module;

/**
 * Selects either explicit Java files or a {@link SourceScope} resolved through
 * Git, and assigns the result to Maven modules for type-aware cleanup.
 */
record SourceSelection(List<Module> modules, List<Path> files, boolean targeted) {

	SourceSelection {
		modules = List.copyOf(modules);
		files = List.copyOf(files);
	}

	static SourceSelection collect(final Path repo, final List<Module> reactorModules,
			final List<String> requestedModules, final List<Path> requestedFiles, final SourceScope scope,
			final String baseRef) {
		final MavenReactor reactor = new MavenReactor();
		final List<Module> moduleFilters = reactor.resolveRequested(requestedModules, reactorModules);
		final boolean explicitFiles = !requestedFiles.isEmpty();
		/*
		 * A narrowed selection leaves the rest of each module unanalysed, so
		 * the module's own compiled output has to stay on the classpath for
		 * type-aware cleanup. Only a repository-wide sweep rebuilds everything
		 * it needs from source.
		 */
		final boolean targeted = explicitFiles || scope != SourceScope.REPOSITORY;
		final List<Path> candidates = explicitFiles ? validateRequested(repo, requestedFiles)
				: new GitSources(repo).javaSources(scope, baseRef);
		final Set<Module> selectedModules = new LinkedHashSet<>();
		final List<Path> selectedFiles = new ArrayList<>();

		for (final Path file : candidates.stream().sorted().toList()) {
			final Module owner = reactor.ownerOf(file, reactorModules);
			if (!moduleFilters.isEmpty() && moduleFilters.stream().noneMatch(module -> module.contains(file))) {
				/*
				 * Naming a file that the module filter excludes is a
				 * contradiction worth reporting. A discovered file outside the
				 * filter is simply not selected, whichever scope discovered it.
				 */
				if (explicitFiles) {
					throw new PrettifyException(
							"Java source is outside the selected Maven module(s): " + repo.relativize(file) + ".");
				}
				continue;
			}
			selectedModules.add(owner);
			selectedFiles.add(file);
		}

		return new SourceSelection(selectedModules.stream().sorted(Comparator.comparing(Module::displayPath)).toList(),
				selectedFiles, targeted);
	}

	private static List<Path> validateRequested(final Path repo, final List<Path> requestedFiles) {
		final Set<Path> validated = new LinkedHashSet<>();
		for (final Path requestedFile : requestedFiles) {
			final Path file = requestedFile.toAbsolutePath().normalize();
			if (!file.startsWith(repo)) {
				throw new PrettifyException("Java source is outside the repository: " + requestedFile + ".");
			}
			if (!file.toString().endsWith(".java")) {
				throw new PrettifyException("Source argument does not end with .java: " + requestedFile + ".");
			}
			if (!Files.isRegularFile(file)) {
				throw new PrettifyException("Java source does not exist: " + requestedFile + ".");
			}
			if (containsPathElement(repo.relativize(file), "target")) {
				throw new PrettifyException("Java source is in Maven build output: " + requestedFile + ".");
			}
			validated.add(file);
		}
		return List.copyOf(validated);
	}

	private static boolean containsPathElement(final Path path, final String name) {
		for (final Path element : path) {
			if (name.equals(element.toString())) {
				return true;
			}
		}
		return false;
	}

}
