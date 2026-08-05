package de.westarps.devtools.prettify;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Names a Git-derived set of Java sources so a caller can select the usual
 * working sets without assembling a file list first.
 * <p>
 * Every scope resolves against the repository's current state at invocation
 * time. Deleted sources drop out of every scope because a removed file cannot
 * be formatted.
 */
enum SourceScope {

	/** Every Git-tracked Java source in the repository. */
	REPOSITORY,

	/**
	 * Every Java source that differs from the base branch, whether the change
	 * is committed or still in the working tree.
	 */
	BRANCH,

	/**
	 * Every Java source changed in the working tree: staged, unstaged, and
	 * untracked.
	 */
	UNCOMMITTED;

	/** The value accepted on the command line. */
	String option() {
		return name().toLowerCase(Locale.ROOT);
	}

	static SourceScope parse(final String value) {
		for (final SourceScope scope : values()) {
			if (scope.option().equalsIgnoreCase(value)) {
				return scope;
			}
		}
		throw new PrettifyException("Unknown selection scope '" + value + "'. Expected one of: " + options() + ".");
	}

	static String options() {
		return Arrays.stream(values()).map(SourceScope::option).collect(Collectors.joining(", "));
	}

}
