package de.westarps.devtools.prettify;

/**
 * Reports an operational failure that the CLI can present without exposing an
 * implementation stack trace.
 */
final class PrettifyException extends RuntimeException {

	PrettifyException(final String message) {
		super(message);
	}

	PrettifyException(final String message, final Throwable cause) {
		super(message, cause);
	}

}
