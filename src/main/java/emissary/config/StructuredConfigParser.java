package emissary.config;

import emissary.util.io.ResourceReader;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses YAML and TOML configs into {@link Configurator} entries. See {@code Sample.yaml} and {@code Sample.toml} for
 * mapping examples.
 */
public final class StructuredConfigParser {

    private static final Logger logger = LoggerFactory.getLogger(StructuredConfigParser.class);

    private final ServiceConfigGuide configG;

    /**
     * Create the yaml/toml parser
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
        String collectionWord() {
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
        final String lower = filename.toLowerCase(Locale.getDefault());
        return lower.endsWith(ResourceReader.YAML_SUFFIX) || lower.endsWith(ResourceReader.YML_SUFFIX);
    }

    /**
     * Whether the named config file is TOML.
     *
     * @param filename the config name to check
     * @return true for {@code .toml} names
     */
    public static boolean isTomlFile(final String filename) {
        return filename.toLowerCase(Locale.getDefault()).endsWith(ResourceReader.TOML_SUFFIX);
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
        } else {
            readYaml(is, filename);
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
            flattenParsed(parseYamlDocument(is.readAllBytes(), filename), filename, Format.YAML);
        } catch (JsonProcessingException e) {
            throw new IOException("Cannot parse YAML configuration " + parseLocation(filename, e) + ": " + e.getOriginalMessage(), e);
        } finally {
            is.close();
        }
    }

    /**
     * Parse YAML bytes, retrying leniently when only duplicate keys fail strict parsing.
     *
     * @param data the raw document
     * @param filename the config name for error messages
     * @return the parsed document
     * @throws IOException on strict-mode duplicates, or any JsonProcessingException otherwise
     */
    private static Object parseYamlDocument(final byte[] data, final String filename) throws IOException {
        final ObjectMapper strictMapper = new ObjectMapper(new YAMLFactory());
        strictMapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        try {
            return strictMapper.readValue(data, Object.class);
        } catch (JsonProcessingException e) {
            if (e.getOriginalMessage() == null || !e.getOriginalMessage().contains("Duplicate field")) {
                throw e;
            }
            // Mappings can't repeat keys; keep the last value with a warning (strict mode fails instead).
            final String detail = "YAML " + parseLocation(filename, e) + " contains duplicate keys ("
                    + e.getOriginalMessage() + "); use an array for multi-valued entries.";
            if (ConfigUtil.isStrictMode()) {
                throw new IOException(detail + " Failing because strict startup mode is enabled.", e);
            }
            logger.warn("{}, keeping the last value for each.", detail);
            try {
                return new ObjectMapper(new YAMLFactory()).readValue(data, Object.class);
            } catch (JsonProcessingException retryFailure) {
                throw new IOException("Cannot parse YAML configuration " + parseLocation(filename, retryFailure)
                        + ": " + retryFailure.getOriginalMessage(), retryFailure);
            }
        }
    }

    /**
     * Parse TOML config data into entries. Duplicate keys are always an error -- the TOML spec forbids them, so use arrays
     * for multi-valued entries. TOML has no null literal; the {@code "<null>"} value still nulls an entry.
     *
     * @param is the stream to read, closed on return
     * @param filename the config name for error messages
     * @throws IOException on syntax errors or unsupported structure
     */
    private void readToml(final InputStream is, final String filename) throws IOException {
        try {
            final ObjectMapper mapper = new ObjectMapper(new TomlFactory());
            flattenParsed(mapper.readValue(is.readAllBytes(), Object.class), filename, Format.TOML);
        } catch (JsonProcessingException e) {
            throw new IOException("Cannot parse TOML configuration " + parseLocation(filename, e) + ": " + e.getOriginalMessage(), e);
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
            flattenMap("", "$", (Map<String, Object>) parsed, filename, format, "=");
        } else if (parsed != null) {
            final String kind = parsed instanceof List ? (format == Format.TOML ? "array" : "sequence") : "scalar";
            throw new IOException(format + " config " + filename + " must be a mapping at the top level, found " + kind);
        } else {
            logger.debug("{} config {} is empty, no entries loaded", format, filename);
        }
    }

    /**
     * Format a Jackson parse failure
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
     * Flatten a parsed mapping into config entries. Quoted top-level {@code !} keys are operators: {@code "!remove"},
     * {@code "!import"}, {@code "!opt-import"}, and {@code "!flavor-NAME"} (or grouped {@code "!flavor": {NAME: ...}}). See
     * {@code Sample.yaml} for the full mapping.
     *
     * @param prefix flattened key prefix, empty at the top level
     * @param sourcePath dotted source path for error messages, starting at {@code $}
     * @param map the parsed mapping
     * @param filename the config name for error messages
     * @param format the config format
     * @param operatorArg the entry operator ({@code =} or {@code !=})
     * @throws IOException on unsupported structure or entry failures
     */
    @SuppressWarnings("unchecked")
    private void flattenMap(final String prefix, final String sourcePath, final Map<String, Object> map, final String filename,
            final Format format, final String operatorArg)
            throws IOException {
        flattenEntries(prefix, sourcePath, map, filename, format, operatorArg, false, new LinkedHashMap<>());
        if (!prefix.isEmpty()) {
            return;
        }
        // Second pass: active flavor sections override the base entries flattened above.
        final Set<String> activeFlavors = ConfigUtil.getUniqueFlavors();
        for (final Map.Entry<String, Object> e : map.entrySet()) {
            final String rawKey = e.getKey();
            final String itemPath = sourcePath + "." + rawKey;
            if ("!flavor".equals(rawKey)) {
                if (!(e.getValue() instanceof Map)) {
                    throw new IOException(format + " " + filename + " key \"" + rawKey + "\" at " + itemPath
                            + " must be a mapping, found " + valueKind(e.getValue()));
                }
                for (final Map.Entry<String, Object> group : ((Map<String, Object>) e.getValue()).entrySet()) {
                    applyFlavorSection(group.getKey(), group.getValue(), itemPath + "." + group.getKey(), filename,
                            format, activeFlavors);
                }
            } else {
                final String flavorName = flavorSectionName(rawKey);
                if (flavorName != null) {
                    applyFlavorSection(flavorName, e.getValue(), itemPath, filename, format, activeFlavors);
                }
            }
        }
    }

    /**
     * Apply one flavor section, skipping inactive flavors entirely. Entries are prepended like a file-based flavor merge so
     * flavored values win lookups.
     *
     * @param flavorName the flavor name
     * @param value the section mapping
     * @param itemPath dotted source path for error messages
     * @param filename the config name for error messages
     * @param format the config format
     * @param activeFlavors the enabled flavors
     * @throws IOException on unsupported structure
     */
    @SuppressWarnings("unchecked")
    private void applyFlavorSection(final String flavorName, final Object value, final String itemPath, final String filename,
            final Format format, final Set<String> activeFlavors) throws IOException {
        if (!(value instanceof Map)) {
            throw new IOException(format + " " + filename + " flavor \"" + flavorName + "\" at " + itemPath
                    + " must be a mapping, found " + valueKind(value));
        }
        if (activeFlavors.contains(flavorName)) {
            flattenEntries("", itemPath, (Map<String, Object>) value, filename, format, "=", true, new LinkedHashMap<>());
        } else {
            logger.debug("Skipping inactive {} flavor section {} in {}", format, flavorName, filename);
        }
    }

    /**
     * Flavor name for a top-level {@code !flavor} section key, or null for ordinary keys. Only valid at the top level.
     *
     * @param rawKey the config key
     * @return the parsed flavor from {@code !flavor} section, or null for ordinary keys
     */
    @Nullable
    private static String flavorSectionName(final String rawKey) {
        if ("!flavor".equals(rawKey)) {
            return "!flavor";
        }
        if (rawKey.startsWith("!flavor-")) {
            return rawKey.substring("!flavor-".length());
        }
        if (rawKey.startsWith("!flavor ")) {
            return rawKey.substring("!flavor ".length());
        }
        return null;
    }

    /**
     * Flatten one mapping level into entries.
     *
     * @param prefix flattened key prefix, empty at the top level
     * @param sourcePath dotted source path for error messages
     * @param map the parsed mapping
     * @param filename the config name for error messages
     * @param format the config format
     * @param operatorArg the entry operator ({@code =} or {@code !=})
     * @param prepend true to insert entries at the top, for flavor overrides
     * @param emittedKeys flattened keys already emitted, with their source paths, for collision detection
     * @throws IOException on unsupported structure or entry failures
     */
    @SuppressWarnings("unchecked")
    private void flattenEntries(final String prefix, final String sourcePath, final Map<String, Object> map, final String filename,
            final Format format, final String operatorArg, final boolean prepend, final Map<String, String> emittedKeys)
            throws IOException {
        for (final Map.Entry<String, Object> e : map.entrySet()) {
            final String rawKey = e.getKey();
            final Object v = e.getValue();
            final String itemPath = sourcePath + "." + rawKey;
            if (!prefix.isEmpty() && flavorSectionName(rawKey) != null) {
                throw new IOException(format + " " + filename + " key \"" + rawKey + "\" at " + itemPath
                        + " is only allowed at the top level");
            }
            if (prefix.isEmpty() && flavorSectionName(rawKey) != null) {
                // Handled in the flavor second pass of flattenMap.
                continue;
            }
            if (prefix.isEmpty() && "!remove".equals(rawKey)) {
                if (!(v instanceof Map)) {
                    throw new IOException(
                            format + " " + filename + " key \"!remove\" at " + itemPath + " must be a mapping, found " + valueKind(v));
                }
                flattenEntries("", itemPath, (Map<String, Object>) v, filename, format, "!=", prepend, emittedKeys);
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
                                    + " must be a scalar or " + format.collectionWord() + " of scalars, found nested " + valueKind(item));
                        }
                        addMappedEntry(importKey, item, "=", filename, format, elementPath, false);
                    }
                } else {
                    if (v instanceof Map) {
                        throw new IOException(format + " " + filename + " key \"" + rawKey + "\" at " + itemPath
                                + " must be a scalar or " + format.collectionWord() + " of scalars, found mapping");
                    }
                    addMappedEntry(importKey, v, "=", filename, format, itemPath, false);
                }
                continue;
            }
            final String key = prefix.isEmpty() ? rawKey : prefix + "_" + rawKey;
            if (v instanceof Map) {
                flattenEntries(key, itemPath, (Map<String, Object>) v, filename, format, operatorArg, prepend, emittedKeys);
            } else if (v instanceof List) {
                int i = 0;
                for (final Object item : (List<Object>) v) {
                    final String elementPath = itemPath + "[" + i++ + "]";
                    if (item instanceof List) {
                        throw new IOException(format + " " + filename + " key \"" + key + "\" at " + elementPath
                                + " must be a scalar or " + format.collectionWord() + " of scalars, found nested " + valueKind(item));
                    }
                    if (item instanceof Map) {
                        applySequenceOp(key, elementPath, item, filename, format);
                        continue;
                    }
                    checkKeyCollision(key, itemPath, filename, format, operatorArg, prepend, emittedKeys);
                    addMappedEntry(key, item, operatorArg, filename, format, elementPath, prepend);
                }
            } else {
                checkKeyCollision(key, itemPath, filename, format, operatorArg, prepend, emittedKeys);
                addMappedEntry(key, v, operatorArg, filename, format, itemPath, prepend);
            }
        }
    }

    /**
     * Apply a positional operation inside a sequence. A single-entry {@code {"!remove": v}} map removes {@code v} from the
     * sequence's key at that position; anything else is unsupported.
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
                                + " must be a scalar or " + format.collectionWord() + " of scalars, found nested " + valueKind(sub));
                    }
                    addMappedEntry(key, sub, "!=", filename, format, elementPath + "[" + i++ + "]", false);
                }
            } else if (!(target instanceof Map)) {
                addMappedEntry(key, target, "!=", filename, format, elementPath, false);
            } else {
                throw new IOException(format + " " + filename + " key \"" + key + "\" at " + elementPath
                        + " has an unsupported positional operation;"
                        + " sequence maps must be single-entry {\"!remove\": scalar-or-sequence}.");
            }
            return;
        }
        throw new IOException(format + " " + filename + " key \"" + key + "\" at " + elementPath
                + " must be a scalar or " + format.collectionWord() + " of scalars, found nested " + valueKind(item)
                + "; sequence maps must be single-entry {\"!remove\": scalar-or-sequence}.");
    }

    /**
     * Warn when two source locations flatten to the same key (lookups return the first, substitution sees the last). Skips
     * intentional overrides, removals, and repeated sequence items. Strict mode fails instead.
     *
     * @param key the flattened config key
     * @param itemPath dotted source path of this occurrence
     * @param filename the config name for error messages
     * @param format the config format
     * @param operatorArg the entry operator ({@code =} or {@code !=})
     * @param prepend true for flavor overrides, which never collide
     * @param emittedKeys flattened keys already emitted, with their source paths
     * @throws IOException in strict mode on collision
     */
    private static void checkKeyCollision(final String key, final String itemPath, final String filename, final Format format,
            final String operatorArg, final boolean prepend, final Map<String, String> emittedKeys) throws IOException {
        if (prepend || "!=".equals(operatorArg)) {
            return;
        }
        final String firstPath = emittedKeys.putIfAbsent(key, itemPath);
        if (firstPath != null && !firstPath.equals(itemPath)) {
            final String detail = format + " " + filename + " key '" + key + "' from " + itemPath
                    + " collides with the same key from " + firstPath + ".";
            if (ConfigUtil.isStrictMode()) {
                throw new IOException(detail + " Failing because strict startup mode is enabled.");
            }
            logger.warn("{} Both entries are kept; lookups return the first while substitution sees the last,"
                    + " so rename one side.", detail);
        }
    }

    /**
     * Feed one flattened value through the entry pipeline, wrapping failures with the key and path.
     *
     * @param key the flattened config key
     * @param value the raw value
     * @param operatorArg the entry operator ({@code =} or {@code !=})
     * @param filename the config name for error messages
     * @param format the config format
     * @param sourcePath dotted source path for error messages
     * @param prepend true to insert at the top, for flavor overrides
     * @throws IOException wrapping the entry failure
     */
    private void addMappedEntry(final String key, final Object value, final String operatorArg, final String filename,
            final Format format, final String sourcePath, final boolean prepend) throws IOException {
        final String sval = value == null ? ServiceConfigGuide.NULL_VALUE : String.valueOf(value);
        try {
            configG.handleNewEntry(key, sval, operatorArg, filename, 0, prepend);
        } catch (IOException e) {
            throw new IOException(
                    format + " " + filename + " entry '" + key + "' at " + sourcePath + " failed: " + e.getMessage(), e);
        }
    }

    /** Value kind name for error messages. */
    private static String valueKind(final Object v) {
        if (v instanceof Map) {
            return "mapping";
        } else if (v instanceof List) {
            return "sequence";
        } else if (v == null) {
            return "null";
        }
        return "scalar";
    }
}
