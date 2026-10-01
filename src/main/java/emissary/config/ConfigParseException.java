package emissary.config;

import java.io.IOException;

/**
 * A config file was found but could not be parsed or fully loaded. Unlike a missing file, this aborts preference and
 * fallback lookups instead of silently moving on.
 */
public class ConfigParseException extends IOException {

    /**
     * provide uid for serialization
     */
    private static final long serialVersionUID = 1L;

    /**
     * Create a parse exception
     *
     * @param message a string to go along with the exception
     * @param cause the wrapped exception
     */
    public ConfigParseException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
