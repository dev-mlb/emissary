package emissary.config;

import java.io.IOException;

/**
 * Unparseable config file. Aborts preference and fallback lookups; missing files remain lookup misses.
 */
public class ConfigParseException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Parse exception.
     *
     * @param message a string to go along with the exception
     * @param cause the wrapped exception
     */
    public ConfigParseException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
