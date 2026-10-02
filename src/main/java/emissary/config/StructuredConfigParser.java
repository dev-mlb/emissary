package emissary.config;

import emissary.util.io.ResourceReader;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses YAML and TOML configs into {@link Configurator} entries. See {@code Sample.yaml} and {@code Sample.toml} for
 * mapping examples.
 * <p>
 * Note:
 * <ul>
 * <li>Keys become strings</li>
 * <li>YAML keeps the last value on duplicates (silent)</li>
 * <li>TOML reports duplicates as a parse error</li>
 * </ul>
 */
public final class StructuredConfigParser {

    private static final Logger logger = LoggerFactory.getLogger(StructuredConfigParser.class);

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper TOML_MAPPER = new ObjectMapper(new TomlFactory());

    private final ServiceConfigGuide configG;

    /**
     * Create a parser that feeds the given guide.
     *
     * @param configG the service config guide
     */
    public StructuredConfigParser(final ServiceConfigGuide configG) {
        this.configG = configG;
    }

    /**
     * Supported structured config formats.
     */
    enum Format {
        /** YAML files. */
        YAML,
        /** TOML files. */
        TOML;

        /**
         * Collection kind name for error messages: TOML calls them arrays.
         *
         * @return {@code array} for TOML, else {@code sequence}
         */
        String collectionKind() {
            return this == TOML ? "array" : "sequence";
        }
    }

    /**
     * Whether the named config file is YAML.
     *
     * @param filename the config name to check
     * @return true for {@code .yaml} and {@code .yml} names
     */
    public static boolean isYamlFile(final String filename) {
        final String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(ResourceReader.YAML_SUFFIX) || lower.endsWith(ResourceReader.YML_SUFFIX);
    }

    /**
     * Whether the named config file is TOML.
     *
     * @param filename the config name to check
     * @return true for {@code .toml} names
     */
    public static boolean isTomlFile(final String filename) {
        return filename.toLowerCase(Locale.ROOT).endsWith(ResourceReader.TOML_SUFFIX);
    }

    /**
     * Whether the named config file is YAML or TOML.
     *
     * @param filename the config name to check
     * @return true for structured config names
     */
    public static boolean isStructuredFile(final String filename) {
        return isYamlFile(filename) || isTomlFile(filename);
    }

    /**
     * Parse structured config data into the guide, closing the stream.
     *
     * @param is the stream to read, closed on return
     * @param filename the config name, used for parser dispatch and error messages
     * @throws IOException on syntax errors or unsupported structure
     */
    public void read(final InputStream is, final String filename) throws IOException {
        if (isTomlFile(filename)) {
            readToml(is, filename);
        } else if (isYamlFile(filename)) {
            readYaml(is, filename);
        } else {
            is.close();
            throw new IOException("Cannot parse " + filename + ": unknown structured config suffix");
        }
    }

    /**
     * Parse YAML config data into entries.
     *
     * @param is the stream to read, closed on return
     * @param filename the config name for error messages
     * @throws IOException on syntax errors or unsupported structure
     */
    private void readYaml(final InputStream is, final String filename) throws IOException {
        try {
            flattenParsed(YAML_MAPPER.readValue(is, Object.class), filename, Format.YAML);
        } catch (JsonProcessingException e) {
            throw parseFailure(Format.YAML, filename, e);
        } finally {
            is.close();
        }
    }

    /**
     * Parse failure for the given format and location.
     *
     * @param format the config format
     * @param filename the config name for error messages
     * @param e Jackson parse failure
     * @return the failure to throw
     */
    private static IOException parseFailure(final Format format, final String filename, final JsonProcessingException e) {
        final IOException failure = new IOException("Cannot parse " + format + " config " + parseLocation(filename, e)
                + ": " + e.getOriginalMessage(), e);
        logger.error("{}", failure.getMessage());
        return failure;
    }

    /**
     * Parsed TOML config. Duplicate keys are always an error. TOML has no null literal; the {@code "<null>"} value still
     * nulls an entry.
     *
     * @param is the stream to read, closed on return
     * @param filename the config name for error messages
     * @throws IOException on syntax errors or unsupported structure
     */
    private void readToml(final InputStream is, final String filename) throws IOException {
        try {
            flattenParsed(TOML_MAPPER.readValue(is, Object.class), filename, Format.TOML);
        } catch (JsonProcessingException e) {
            throw parseFailure(Format.TOML, filename, e);
        } finally {
            is.close();
        }
    }

    /**
     * Flatten a parsed top-level value into entries.
     *
     * @param parsed the parsed document
     * @param filename the config name for error messages
     * @param format the config format
     * @throws IOException when the top level is not a mapping
     */
    @SuppressWarnings("unchecked")
    private void flattenParsed(final Object parsed, final String filename, final Format format) throws IOException {
        if (parsed instanceof Map) {
            flattenEntries("", "$", (Map<String, Object>) parsed, filename, format, "=", new LinkedHashMap<>());
        } else if (parsed != null) {
            final String kind = parsed instanceof List ? format.collectionKind() : "scalar";
            throw new IOException(format + " config " + filename + " must be a mapping at the top level, found " + kind);
        } else {
            logger.debug("{} config {} is empty, no entries loaded", format, filename);
        }
    }

    /**
     * Jackson failure location as {@code filename:line:column}, or filename when unavailable.
     *
     * @param filename the config name
     * @param e Jackson parse failure
     * @return failure as {@code filename:line:column}, or filename when no location is available.
     */
    private static String parseLocation(final String filename, final JsonProcessingException e) {
        if (e.getLocation() == null || e.getLocation().getLineNr() < 1) {
            return filename;
        }
        return filename + ":" + e.getLocation().getLineNr() + ":" + e.getLocation().getColumnNr();
    }

    /**
     * Top-level operator keys. Nested occurrences are rejected.
     *
     * @param rawKey the config key
     * @return true when the key is an operator key
     */
    private static boolean isOperatorKey(final String rawKey) {
        return "!remove".equals(rawKey) || "!import".equals(rawKey) || "!opt-import".equals(rawKey)
                || "!=".equals(rawKey);
    }

    /**
     * Config entries for one mapping level.
     *
     * @param prefix flattened key prefix, empty at the top level
     * @param sourcePath dotted source path for error messages
     * @param map the parsed mapping
     * @param filename the config name for error messages
     * @param format the config format
     * @param operatorArg the entry operator ({@code =} or {@code !=})
     * @param emittedKeys flattened keys already emitted, with their source paths, for collision detection
     * @throws IOException on unsupported structure or entry failures
     */
    @SuppressWarnings("unchecked")
    private void flattenEntries(final String prefix, final String sourcePath, final Map<String, Object> map, final String filename,
            final Format format, final String operatorArg, final Map<String, String> emittedKeys)
            throws IOException {
        for (final Map.Entry<String, Object> e : map.entrySet()) {
            final String rawKey = e.getKey();
            final Object v = e.getValue();
            final String itemPath = sourcePath + "." + rawKey;
            if (!prefix.isEmpty() && isOperatorKey(rawKey)) {
                throw new IOException(format + " " + filename + " key \"" + rawKey + "\" at " + itemPath
                        + " is only allowed at the top level");
            }
            if (prefix.isEmpty() && "!=".equals(rawKey)) {
                throw new IOException(format + " " + filename + " key \"" + rawKey + "\" at " + itemPath
                        + " is an operator, not a key");
            }
            if (prefix.isEmpty() && "!remove".equals(rawKey)) {
                if (!(v instanceof Map)) {
                    throw new IOException(
                            format + " " + filename + " key \"!remove\" at " + itemPath + " must be a mapping, found " + valueKind(v, format));
                }
                flattenEntries("", itemPath, (Map<String, Object>) v, filename, format, "!=", emittedKeys);
                continue;
            }
            if (prefix.isEmpty() && ("!import".equals(rawKey) || "!opt-import".equals(rawKey))) {
                final String importKey = "!import".equals(rawKey) ? "IMPORT_FILE" : "OPT_IMPORT_FILE";
                if (v instanceof List) {
                    int i = 0;
                    for (final Object item : (List<Object>) v) {
                        final String elementPath = itemPath + "[" + i++ + "]";
                        if (item instanceof Map || item instanceof List) {
                            throw new IOException(format + " " + filename + " key \"" + rawKey + "\" at " + elementPath
                                    + " must be a scalar or " + format.collectionKind() + " of scalars, found nested " + valueKind(item, format));
                        }
                        addMappedEntry(importKey, item, "=", filename, format, elementPath);
                    }
                } else {
                    if (v instanceof Map) {
                        throw new IOException(format + " " + filename + " key \"" + rawKey + "\" at " + itemPath
                                + " must be a scalar or " + format.collectionKind() + " of scalars, found mapping");
                    }
                    addMappedEntry(importKey, v, "=", filename, format, itemPath);
                }
                continue;
            }
            final String key = prefix.isEmpty() ? rawKey : prefix + "_" + rawKey;
            if (v instanceof Map) {
                flattenEntries(key, itemPath, (Map<String, Object>) v, filename, format, operatorArg, emittedKeys);
            } else if (v instanceof List) {
                int i = 0;
                for (final Object item : (List<Object>) v) {
                    final String elementPath = itemPath + "[" + i++ + "]";
                    if (item instanceof List) {
                        throw new IOException(format + " " + filename + " key \"" + key + "\" at " + elementPath
                                + " must be a scalar or " + format.collectionKind() + " of scalars, found nested " + valueKind(item, format));
                    }
                    if (item instanceof Map) {
                        applySequenceOp(key, elementPath, item, filename, format);
                        continue;
                    }
                    checkKeyCollision(key, itemPath, filename, format, operatorArg, emittedKeys);
                    addMappedEntry(key, item, operatorArg, filename, format, elementPath);
                }
            } else {
                checkKeyCollision(key, itemPath, filename, format, operatorArg, emittedKeys);
                addMappedEntry(key, v, operatorArg, filename, format, itemPath);
            }
        }
    }

    /**
     * Removal of one sequence element. Only single-entry {@code {"!remove": v}} maps are supported.
     *
     * @param key the flattened config key owning the sequence
     * @param elementPath dotted source path of this item
     * @param item the map item
     * @param filename the config name for error messages
     * @param format the config format
     * @throws IOException on unsupported operations
     */
    @SuppressWarnings("unchecked")
    private void applySequenceOp(final String key, final String elementPath, final Object item,
            final String filename, final Format format) throws IOException {
        final Map<String, Object> op = (Map<String, Object>) item;
        if (op.size() == 1 && op.containsKey("!remove")) {
            final Object target = op.get("!remove");
            if (target instanceof List) {
                int i = 0;
                for (final Object sub : (List<Object>) target) {
                    if (sub instanceof Map || sub instanceof List) {
                        throw new IOException(format + " " + filename + " key \"" + key + "\" at " + elementPath + "[" + i + "]"
                                + " must be a scalar or " + format.collectionKind() + " of scalars, found nested " + valueKind(sub, format));
                    }
                    addMappedEntry(key, sub, "!=", filename, format, elementPath + "[" + i++ + "]");
                }
            } else if (!(target instanceof Map)) {
                addMappedEntry(key, target, "!=", filename, format, elementPath);
            } else {
                throw new IOException(format + " " + filename + " key \"" + key + "\" at " + elementPath
                        + " has an unsupported positional operation;"
                        + " sequence maps must be single-entry {\"!remove\": scalar-or-sequence}.");
            }
            return;
        }
        throw new IOException(format + " " + filename + " key \"" + key + "\" at " + elementPath
                + " must be a scalar or " + format.collectionKind() + " of scalars, found nested " + valueKind(item, format)
                + "; sequence maps must be single-entry {\"!remove\": scalar-or-sequence}.");
    }

    /**
     * Duplicate flattened key from distinct source locations. Both entries are kept.
     *
     * @param key the flattened config key
     * @param itemPath dotted source path of this occurrence
     * @param filename the config name for error messages
     * @param format the config format
     * @param operatorArg the entry operator ({@code =} or {@code !=})
     * @param emittedKeys flattened keys already emitted, with their source paths
     */
    private static void checkKeyCollision(final String key, final String itemPath, final String filename, final Format format,
            final String operatorArg, final Map<String, String> emittedKeys) {
        if ("!=".equals(operatorArg)) {
            return;
        }
        final String firstPath = emittedKeys.putIfAbsent(key, itemPath);
        if (firstPath != null && !firstPath.equals(itemPath)) {
            logger.warn("{} {} key '{}' from {} collides with the same key from {}. Both entries are kept;"
                    + " lookups return the first while substitution sees the last, so rename one side.",
                    format, filename, key, itemPath, firstPath);
        }
    }

    /**
     * Single flattened value as a config entry.
     *
     * @param key the flattened config key
     * @param value the raw value
     * @param operatorArg the entry operator ({@code =} or {@code !=})
     * @param filename the config name for error messages
     * @param format the config format
     * @param sourcePath dotted source path for error messages
     * @throws IOException when the value is null, or wrapping the entry failure
     */
    private void addMappedEntry(final String key, final Object value, final String operatorArg, final String filename,
            final Format format, final String sourcePath) throws IOException {
        if (value == null) {
            throw new IOException(format + " " + filename + " key '" + key + "' at " + sourcePath
                    + " has no value; quote an empty string to set a blank value,"
                    + " and use " + ServiceConfigGuide.NULL_VALUE + " to null the entry.");
        }
        final String sval = String.valueOf(value);
        try {
            configG.handleNewEntry(key, sval, operatorArg, filename, 0, false);
        } catch (IOException e) {
            throw new IOException(
                    format + " " + filename + " entry '" + key + "' at " + sourcePath + " failed: " + e.getMessage(), e);
        }
    }

    /** Parsed value kind for error messages. */
    private static String valueKind(final Object v, final Format format) {
        if (v instanceof Map) {
            return "mapping";
        } else if (v instanceof List) {
            return format.collectionKind();
        } else if (v == null) {
            return "null";
        }
        return "scalar";
    }
}
