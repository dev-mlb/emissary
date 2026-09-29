package emissary.config;

import emissary.util.io.ResourceReader;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.Nullable;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Serializable;
import java.io.StreamTokenizer;
import java.io.Writer;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * This class implements the Configurator interface for services within the Emissary framework.
 */

public class ServiceConfigGuide implements Configurator, Serializable {

    static final long serialVersionUID = 3906838615422657150L;
    public static final char SLASH = '/';
    public static final char COLON = ':';
    public static final String DOUBLESLASH = "//";

    protected static final Logger logger = LoggerFactory.getLogger(ServiceConfigGuide.class);

    protected static final String DEFAULT_FILE_NAME = "default.cfg";
    protected static final String POST_FILE_NAME = "post.cfg";

    // Used on the RHS to make a null assignment
    // Obsolete, use @{NULL}
    protected static final String NULL_VALUE = "<null>";

    // Hold all service specific parameters in a list
    protected List<ConfigEntry> serviceParameters = new ArrayList<>();

    // Hold all remove config entries, operator of !=
    protected List<ConfigEntry> removeParameters = new ArrayList<>();

    protected String operator;

    // Start and end to a dynamic substitution
    protected static final String VSTART = "@{";
    protected static final String VEND = "}";

    // Shared map of all environment properties
    // Access them with @ENV{'os.name'} for example
    // This is obsolete, all values from properties and
    // environment are now in the main values map and available
    // for immediate substitution
    protected static final String ENVSTART = "@ENV{'";
    protected static final String ENVSTOP = "'}";

    // Map of last values seen
    protected Map<String, String> values = new HashMap<>();

    // Get this once per jvm
    private static final String hostname;

    // Grab the hostname for @{HOST} replacement
    static {
        String tmpHostname;
        try {
            tmpHostname = InetAddress.getLocalHost().getCanonicalHostName();
        } catch (UnknownHostException e) {
            logger.error("Error getting host name", e);
            tmpHostname = "localhost";
        }
        hostname = tmpHostname;
    }

    /**
     * Public default constructor
     */
    public ServiceConfigGuide() {
        initializeValues();
    }

    /**
     * Public constructor with dir and filename
     *
     * @param path the directory where config files are
     * @param file the name of te file in the directory
     */
    public ServiceConfigGuide(final String path, final String file) throws IOException {
        this(path + File.separator + file);
    }

    /**
     * Public default constructor with file name
     *
     * @param filename the name of the disk file
     */
    public ServiceConfigGuide(final String filename) throws IOException {
        this();
        try {
            readConfigData(filename);
        } catch (ConfigSyntaxException ex) {
            throw new IOException("Cannot parse configuration file " + ex.getMessage(), ex);
        }
    }

    /**
     * Public default constructor with InputStream
     *
     * @param is the InputStream
     */
    public ServiceConfigGuide(final InputStream is) throws IOException {
        this();
        try {
            readConfigData(is);
        } catch (ConfigSyntaxException ex) {
            throw new IOException("Cannot parse configuration file " + ex.getMessage(), ex);
        }
    }

    /**
     * Public default constructor with InputStream and name
     *
     * @param is the InputStream
     * @param name the name of the stream good for reporting errors
     */
    public ServiceConfigGuide(final InputStream is, final String name) throws IOException {
        this();
        try {
            readConfigData(is, name);
        } catch (ConfigSyntaxException ex) {
            logger.error("Caught ConfigSytaxException {}", ex.getMessage());
            throw new IOException("Cannot parse configuration file " + ex.getMessage(), ex);
        }
    }

    /**
     * Initialize the values map, which is used to replace stuff in the configs
     */
    protected void initializeValues() {
        this.values.clear();

        // TODO: see if we can stop adding all env variables and
        // system properties to the replace values

        // Add all the environment variables
        this.values.putAll(System.getenv());

        // Add all the system properties
        final Properties props = System.getProperties();
        for (Enumeration<?> e = props.propertyNames(); e.hasMoreElements();) {
            final String key = (String) e.nextElement();
            logger.trace("Adding {} to replaceable properties", key);
            this.values.put(key, props.getProperty(key));
        }

        // used for substitution when reading cfg files
        this.values.put("CONFIG_DIR", StringUtils.join(ConfigUtil.getConfigDirs(), ","));
        this.values.put("PRJ_BASE", ConfigUtil.getProjectBase());
        this.values.put("PROJECT_BASE", ConfigUtil.getProjectBase());
        this.values.put("OUTPUT_ROOT", ConfigUtil.getOutputRoot());
        this.values.put("BIN_DIR", ConfigUtil.getBinDir());
        this.values.put("HOST", hostname);
        this.values.put("/", File.separator);
        this.values.put("TMPDIR", System.getProperty("java.io.tmpdir"));
        this.values.put("NULL", null);
        this.values.put("OS.NAME", System.getProperty("os.name").replace(' ', '_'));
        this.values.put("OS.VER", System.getProperty("os.version").replace(' ', '_'));
        this.values.put("OS.ARCH", System.getProperty("os.arch").replace(' ', '_'));
    }

    /**
     * Reads the configuration file specified in the argument and sets the mandatory parameters.
     */
    protected void readConfigData(final String filename) throws IOException, ConfigSyntaxException {
        readSingleConfigFile(filename);
    }

    public void readConfigData(final InputStream is) throws IOException, ConfigSyntaxException {
        readConfigData(is, "UNKNOWN");
    }


    protected void readConfigData(final InputStream is, final String filename) throws IOException, ConfigSyntaxException {
        if (isYamlFile(filename)) {
            readYamlConfigData(is, filename);
            return;
        }
        if (isTomlFile(filename)) {
            readTomlConfigData(is, filename);
            return;
        }
        final Reader r = new BufferedReader(new InputStreamReader(is, UTF_8));
        final StreamTokenizer in = new StreamTokenizer(r);
        int nextToken = StreamTokenizer.TT_WORD;
        String parmName;
        String sval;

        in.commentChar('#');
        in.wordChars(33, 33);
        in.wordChars(36, 47);
        in.wordChars(58, 64);
        in.wordChars(91, 96);
        in.wordChars(123, 65536);

        while (nextToken != StreamTokenizer.TT_EOF) {
            // Read three tokens at a time (X = Y)
            nextToken = in.nextToken();

            // Make sure the first token in the tuple is a word
            if (nextToken == StreamTokenizer.TT_EOF) {
                break;
            }
            if (nextToken == StreamTokenizer.TT_NUMBER) {
                throw new ConfigSyntaxException("Illegal token " + in.sval + ", missing quote on line " + in.lineno() + "?");
            }

            parmName = in.sval;

            nextToken = in.nextToken();
            this.operator = in.sval;

            nextToken = in.nextToken();
            if (nextToken == StreamTokenizer.TT_NUMBER) {
                sval = BigDecimal.valueOf(in.nval).stripTrailingZeros().toPlainString();
            } else {
                sval = in.sval;
            }

            if (sval == null) {
                // Problem is likely on previous line
                throw new ConfigSyntaxException("Illegal token " + parmName + ", missing space or value on line " + (in.lineno() - 1) + "?");
            }

            handleNewEntry(parmName, sval, this.operator, filename, in.lineno() - 1, false);
        }
        r.close();
        is.close();
    }

    protected void readSingleConfigFile(final String filename) throws IOException, ConfigSyntaxException {
        logger.debug("Reading config file {}", filename);
        final InputStream is = ConfigUtil.getConfigData(filename);
        readConfigData(is, filename);
    }

    /**
     * Whether the named config file is YAML.
     *
     * @param filename the config name to check
     * @return true for {@code .yaml} and {@code .yml} names
     */
    static boolean isYamlFile(final String filename) {
        final String lower = filename.toLowerCase(Locale.getDefault());
        return lower.endsWith(ResourceReader.YAML_SUFFIX) || lower.endsWith(ResourceReader.YML_SUFFIX);
    }

    /**
     * Whether the named config file is TOML.
     *
     * @param filename the config name to check
     * @return true for {@code .toml} names
     */
    static boolean isTomlFile(final String filename) {
        return filename.toLowerCase(Locale.getDefault()).endsWith(ResourceReader.TOML_SUFFIX);
    }

    /**
     * Parse YAML config data into entries.
     *
     * @param is the stream to read, closed on return
     * @param filename the config name, used for parser dispatch context and error messages
     * @throws IOException on syntax errors or unsupported structure
     */
    @SuppressWarnings("unchecked")
    protected void readYamlConfigData(final InputStream is, final String filename) throws IOException {
        try {
            final byte[] data = is.readAllBytes();
            final ObjectMapper strictMapper = new ObjectMapper(new YAMLFactory());
            strictMapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            Object parsed;
            try {
                parsed = strictMapper.readValue(data, Object.class);
            } catch (JsonProcessingException e) {
                if (e.getOriginalMessage() != null && e.getOriginalMessage().contains("Duplicate field")) {
                    // Mappings can't repeat keys; keep the last value with a warning (strict mode fails instead).
                    final String detail = "YAML " + parseLocation(filename, e) + " contains duplicate keys ("
                            + e.getOriginalMessage() + "); use an array for multi-valued entries.";
                    if (ConfigUtil.isStrictMode()) {
                        throw new IOException(detail + " Failing because strict startup mode is enabled.", e);
                    }
                    logger.warn("{}, keeping the last value for each.", detail);
                    parsed = new ObjectMapper(new YAMLFactory()).readValue(data, Object.class);
                } else {
                    throw e;
                }
            }
            flattenParsed(parsed, filename, "YAML");
        } catch (JsonProcessingException e) {
            throw new IOException("Cannot parse YAML configuration " + parseLocation(filename, e) + ": " + e.getOriginalMessage(), e);
        } finally {
            is.close();
        }
    }

    /**
     * Parse TOML config data into entries. Duplicate keys are always an error -- the TOML spec forbids them, so use arrays
     * for multi-valued entries. TOML has no null value; use {@code "@{NULL}"} for null assignments.
     *
     * @param is the stream to read, closed on return
     * @param filename the config name, used for parser dispatch context and error messages
     * @throws IOException on syntax errors or unsupported structure
     */
    protected void readTomlConfigData(final InputStream is, final String filename) throws IOException {
        try {
            final ObjectMapper mapper = new ObjectMapper(new TomlFactory());
            flattenParsed(mapper.readValue(is.readAllBytes(), Object.class), filename, "TOML");
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
     * @param format the format label for error messages ({@code YAML} or {@code TOML})
     * @throws IOException when the top level is not a mapping
     */
    @SuppressWarnings("unchecked")
    private void flattenParsed(final Object parsed, final String filename, final String format) throws IOException {
        if (parsed instanceof Map) {
            flattenMap("", "$", (Map<String, Object>) parsed, filename, format);
        } else if (parsed != null) {
            final String kind = parsed instanceof List ? ("TOML".equals(format) ? "array" : "sequence") : "scalar";
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
     * Flatten a parsed mapping into entries with the default operator.
     *
     * @param prefix flattened key prefix, empty at the top level
     * @param sourcePath dotted source path for error messages, starting at {@code $}
     * @param map the parsed mapping
     * @param filename the config name for error messages
     * @param format the format label for error messages ({@code YAML} or {@code TOML})
     * @throws IOException on unsupported structure or entry failures
     */
    @SuppressWarnings("unchecked")
    private void flattenMap(final String prefix, final String sourcePath, final Map<String, Object> map, final String filename,
            final String format)
            throws IOException {
        flattenMap(prefix, sourcePath, map, filename, format, "=");
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
     * @param format the format label for error messages ({@code YAML} or {@code TOML})
     * @param operatorArg the entry operator ({@code =} or {@code !=})
     * @throws IOException on unsupported structure or entry failures
     */
    @SuppressWarnings("unchecked")
    private void flattenMap(final String prefix, final String sourcePath, final Map<String, Object> map, final String filename,
            final String format, final String operatorArg)
            throws IOException {
        flattenEntries(prefix, sourcePath, map, filename, format, operatorArg, false, new LinkedHashMap<>());
        if (prefix.isEmpty()) {
            // Second pass: active flavor sections override the base entries flattened above.
            final Set<String> activeFlavors = ConfigUtil.getUniqueFlavors();
            for (final Map.Entry<String, Object> e : map.entrySet()) {
                final String rawKey = e.getKey();
                final String flavorName = flavorSectionName(rawKey);
                if (flavorName == null) {
                    continue;
                }
                final String itemPath = sourcePath + "." + rawKey;
                final Object v = e.getValue();
                if (!(v instanceof Map)) {
                    throw new IOException(format + " " + filename + " key \"" + rawKey + "\" at " + itemPath
                            + " must be a mapping, found " + valueKind(v));
                }
                if ("!flavor".equals(rawKey)) {
                    for (final Map.Entry<String, Object> group : ((Map<String, Object>) v).entrySet()) {
                        applyInlineFlavor(group.getKey(), group.getValue(), itemPath + "." + group.getKey(), filename,
                                format, activeFlavors, new LinkedHashMap<>());
                    }
                } else if (activeFlavors.contains(flavorName)) {
                    flattenEntries("", itemPath, (Map<String, Object>) v, filename, format, "=", true, new LinkedHashMap<>());
                } else {
                    logger.debug("Skipping inactive {} flavor section {} in {}", format, rawKey, filename);
                }
            }
        }
    }

    /**
     * Apply one grouped {@code "!flavor": {NAME: ...}} section, skipping inactive flavors entirely.
     *
     * @param flavorName the flavor name
     * @param value the section mapping
     * @param itemPath dotted source path for error messages
     * @param filename the config name for error messages
     * @param format the format label for error messages ({@code YAML} or {@code TOML})
     * @param activeFlavors the enabled flavors
     * @param emittedKeys flattened keys already emitted, for collision detection
     * @throws IOException on unsupported structure
     */
    @SuppressWarnings("unchecked")
    private void applyInlineFlavor(final String flavorName, final Object value, final String itemPath, final String filename,
            final String format, final Set<String> activeFlavors, final Map<String, String> emittedKeys) throws IOException {
        if (!(value instanceof Map)) {
            throw new IOException(format + " " + filename + " flavor \"" + flavorName + "\" at " + itemPath
                    + " must be a mapping, found " + valueKind(value));
        }
        if (activeFlavors.contains(flavorName)) {
            flattenEntries("", itemPath, (Map<String, Object>) value, filename, format, "=", true, emittedKeys);
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
     * @param format the format label for error messages ({@code YAML} or {@code TOML})
     * @param operatorArg the entry operator ({@code =} or {@code !=})
     * @param prepend true to insert entries at the top, for flavor overrides
     * @param emittedKeys flattened keys already emitted, with their source paths, for collision detection
     * @throws IOException on unsupported structure or entry failures
     */
    @SuppressWarnings("unchecked")
    private void flattenEntries(final String prefix, final String sourcePath, final Map<String, Object> map, final String filename,
            final String format, final String operatorArg, final boolean prepend, final Map<String, String> emittedKeys)
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
                                    + " must be a scalar or " + sequenceWord(format) + " of scalars, found nested " + valueKind(item));
                        }
                        addMappedEntry(importKey, item, "=", filename, format, elementPath, false);
                    }
                } else {
                    if (v instanceof Map) {
                        throw new IOException(format + " " + filename + " key \"" + rawKey + "\" at " + itemPath
                                + " must be a scalar or " + sequenceWord(format) + " of scalars, found mapping");
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
                                + " must be a scalar or " + sequenceWord(format) + " of scalars, found nested " + valueKind(item));
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
     * @param format the format label for error messages ({@code YAML} or {@code TOML})
     * @throws IOException on unsupported operations
     */
    @SuppressWarnings("unchecked")
    private void applySequenceOp(final String key, final String elementPath, final Object item,
            final String filename, final String format) throws IOException {
        final Map<String, Object> op = (Map<String, Object>) item;
        if (op.size() == 1 && op.containsKey("!remove")) {
            final Object target = op.get("!remove");
            if (target instanceof List) {
                int i = 0;
                for (final Object sub : (List<Object>) target) {
                    if (sub instanceof Map || sub instanceof List) {
                        throw new IOException(format + " " + filename + " key \"" + key + "\" at " + elementPath + "[" + i + "]"
                                + " must be a scalar or " + sequenceWord(format) + " of scalars, found nested " + valueKind(sub));
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
                + " must be a scalar or " + sequenceWord(format) + " of scalars, found nested " + valueKind(item)
                + "; sequence maps must be single-entry {\"!remove\": scalar-or-sequence}.");
    }

    /**
     * Warn when two source locations flatten to the same key (lookups return the first, substitution sees the last). Skips
     * intentional overrides, removals, and repeated sequence items. Strict mode fails instead.
     *
     * @param key the flattened config key
     * @param itemPath dotted source path of this occurrence
     * @param filename the config name for error messages
     * @param format the format label for error messages ({@code YAML} or {@code TOML})
     * @param operatorArg the entry operator ({@code =} or {@code !=})
     * @param prepend true for flavor overrides, which never collide
     * @param emittedKeys flattened keys already emitted, with their source paths
     * @throws IOException in strict mode on collision
     */
    private static void checkKeyCollision(final String key, final String itemPath, final String filename, final String format,
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
     * @param format the format label for error messages ({@code YAML} or {@code TOML})
     * @param sourcePath dotted source path for error messages
     * @param prepend true to insert at the top, for flavor overrides
     * @throws IOException wrapping the entry failure
     */
    private void addMappedEntry(final String key, final Object value, final String operatorArg, final String filename,
            final String format, final String sourcePath, final boolean prepend) throws IOException {
        final String sval = value == null ? NULL_VALUE : String.valueOf(value);
        try {
            handleNewEntry(key, sval, operatorArg, filename, 0, prepend);
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

    /**
     * Collection kind name for error messages: TOML calls them arrays.
     *
     * @param format the format label ({@code YAML} or {@code TOML})
     * @return {@code array} for TOML, else {@code sequence}
     */
    private static String sequenceWord(final String format) {
        return "TOML".equals(format) ? "array" : "sequence";
    }

    /**
     * Handle a newly parsed or passed in entry. Substitutions are handled on both the LHS and RHS, then the values are
     * stored as a ConfigEntry in our local list and map. Only the last value in the map is available for substitutions. LHS
     * is analyzed before RHS and in L to R order.
     *
     * @param parmNameArg the LHS
     * @param svalArg the raw RHS
     * @param operatorArg the equation
     * @param filename the filename we are parsing for error reporting
     * @param lineno the line number we are currently reporting the error on
     * @param merge true when adding in from a merge
     * @return a new config entry with the expanded key and value
     * @throws IOException when the key or value is malformed
     */
    protected ConfigEntry handleNewEntry(final String parmNameArg, final String svalArg, final String operatorArg, final String filename,
            final int lineno, final boolean merge) throws IOException {
        final String parmName = handleReplacements(parmNameArg, filename, lineno);
        final String sval = handleReplacements(svalArg, filename, lineno);

        // Create a config entry from this
        final ConfigEntry anEntry = new ConfigEntry(parmName, sval);

        if ("!=".equals(operatorArg)) {
            if ("*".equals(sval)) {
                removeAllEntries(parmName);
                this.values.remove(parmName);
            } else {
                removeEntry(anEntry);
                if (sval.equals(this.values.get(parmName))) {
                    this.values.remove(parmName);
                }
            }
            this.removeParameters.add(anEntry);
        } else {
            // Save the entry in the list
            if (merge) {
                this.serviceParameters.add(0, anEntry);
            } else {
                this.serviceParameters.add(anEntry);
            }

            // Save this pair in the map
            this.values.put(parmName, sval);
        }

        if ("IMPORT_FILE".equals(parmName) || "OPT_IMPORT_FILE".equals(parmName)) {
            final List<String> fileFlavorList = new ArrayList<>();
            // Add the base file and then add all the flavor versions
            fileFlavorList.add(sval);
            final String[] fileFlavors = ConfigUtil.addFlavors(sval);
            if (ArrayUtils.isNotEmpty(fileFlavors)) {
                fileFlavorList.addAll(Arrays.asList(fileFlavors));
            }
            logger.debug("ServiceConfigGuide::handleNewEntry -- FileFlavorList = {}", fileFlavorList);

            // loop through the files and attempt to read/merger the configurations.
            for (int i = 0; i < fileFlavorList.size(); i++) {
                final String fileFlavor = fileFlavorList.get(i);
                boolean loaded = false;
                IOException lastError = null;
                // recursion alert: This could lead to getFile being called
                for (final String candidate : ConfigUtil.candidateNames(fileFlavor)) {
                    try {
                        readConfigData(ConfigUtil.getConfigStreamExact(candidate), candidate);
                        loaded = true;
                        break;
                    } catch (ConfigSyntaxException e) {
                        throw new IOException(parmName + " = " + sval + " from " + filename + " failed " + e.getMessage(), e);
                    } catch (IOException e) {
                        lastError = e;
                    }
                }
                if (!loaded && "IMPORT_FILE".equals(parmName) && i == 0) {
                    // Throw exception if it is an IMPORT_FILE and the base file is not found
                    String importFileName = Path.of(svalArg).getFileName().toString();
                    throw new IOException("In " + filename + ", cannot find IMPORT_FILE: " + sval
                            + " on the specified path. Make sure IMPORT_FILE (" + importFileName + ") exists, and the file path is correct.",
                            lastError);
                }
            }
            return anEntry;
        } else if ("CREATE_DIRECTORY".equals(parmName) && !createDirectory(sval)) {
            logger.warn("{}: Cannot create directory {}", filename, sval);
        } else if ("CREATE_FILE".equals(parmName) && !createFile(sval)) {
            logger.warn("{}: Cannot create file {}", filename, sval);
        }

        return anEntry;
    }

    /**
     * Handle all the possible replacements in a string value
     *
     * @param svalArg the raw value
     * @param filename the filename we are parsing for error reporting
     * @param lineno the line number we are currently reporting the error on
     * @return the expanded value with all legal @{..} values replaced
     * @throws IOException when the value is malformed
     */
    protected String handleReplacements(final String svalArg, final String filename, final int lineno) throws IOException {
        String sval = svalArg;
        int startpos = 0;
        while (sval != null && sval.indexOf(VSTART, startpos) > -1) {
            final int ndx = sval.indexOf(VSTART, startpos);
            final int edx = sval.indexOf(VEND, ndx + VSTART.length());
            if (ndx == -1 && ndx >= edx) {
                throw new IOException("Problem parsing line " + lineno + " " + sval);
            }
            final String tok = sval.substring(ndx + VSTART.length(), edx);
            logger.debug("Replacement token is {}", tok);
            final String mapval = this.values.get(tok);
            if (mapval != null) {
                sval = sval.substring(0, ndx) + mapval + sval.substring(edx + VEND.length());
            } else {
                logger.warn("Did not find replacement for '{}' in file {} at line {}", tok, filename, lineno);
                startpos = edx + VEND.length();
            }
        }

        // This is obsolete
        if (sval != null && sval.contains(ENVSTART)) {
            sval = substituteEnvProps(sval, filename, lineno);
        }

        // Do unicode stuff
        if (sval != null && (sval.contains("\\u") || sval.contains("\\U"))) {
            sval = substituteUtfChars(sval, filename, lineno);
        }

        // This is obsolete
        if (sval != null && sval.equals(NULL_VALUE)) {
            sval = null;
            logger.debug("Using {} is deprecated, please just use {}NULL{}", NULL_VALUE, VSTART, VEND);
        }
        return sval;
    }

    /**
     * Substitute any java unicode character values: \\uxxxx
     *
     * @param s the string to process
     * @param filename the name of the file we are in for error reporting
     * @param lnum the current line number for error reporting
     * @return string with character values replaced
     */
    protected String substituteUtfChars(final String s, final String filename, final int lnum) throws IOException {
        final int slen = s.length();
        final StringBuilder sb = new StringBuilder(slen);
        for (int i = 0; i < slen; i++) {
            if (s.charAt(i) != '\\') {
                sb.append(s.charAt(i));
            } else if ((i + 4) < slen && (s.charAt(i + 1) == 'u' || s.charAt(i + 1) == 'U')) {
                int epos = i + 2;
                final int max = (s.charAt(epos) == '1' || s.charAt(epos) == '0') ? (i + 7) : (i + 6);
                while (epos < slen
                        && epos < max
                        && ((s.charAt(epos) >= '0' && s.charAt(epos) <= '9') || (s.charAt(epos) >= 'A' && s.charAt(epos) <= 'F')
                                || (s.charAt(epos) >= 'a' && s
                                        .charAt(epos) <= 'f'))) {
                    epos++;
                }
                if (epos <= slen) {
                    try {
                        final int digit = Integer.parseInt(s.substring(i + 2, epos), 16);
                        sb.appendCodePoint(digit);
                        i = epos - 1;
                    } catch (RuntimeException ex) {
                        throw new IOException("Unable to convert characters in " + s + ", from filename=" + filename + " line " + lnum, ex);
                    }
                }
            } else {
                sb.append(s.charAt(i));
            }
        }

        return sb.toString();
    }

    /**
     * Substitute any referenced env properties with their values. Look for @ENV{'foo'} and replace foo with
     * System.getProperty("foo") or System.getenv("foo") in that order.
     *
     * @param str the string to process
     * @param filename the name of the file we are in for error reporting
     * @param lnum the current line number for error reporting
     * @return string with env values replaced
     */
    protected String substituteEnvProps(final String str, final String filename, final int lnum) throws IOException {
        int lastPos = -1;
        int thisPos = 0;
        int count = 0;

        logger.debug("{}{} style substitution is deprecated. Please just use {}yourvalue{}", ENVSTART, ENVSTOP, VSTART, VEND);

        String currentStr = str;
        while ((thisPos = currentStr.indexOf(ENVSTART, thisPos)) > lastPos) {
            final int start = thisPos + ENVSTART.length();
            final int stop = currentStr.indexOf(ENVSTOP, thisPos);
            count++;
            // Pull out the env name they specified
            if (stop > start) {
                final String envName = currentStr.substring(start, stop);
                String envVal = System.getProperty(envName);
                if (envVal == null) {
                    envVal = System.getenv(envName);
                }
                // We got a replacement, do the subst
                if (envVal != null) {
                    currentStr = currentStr.substring(0, thisPos) + // before
                            envVal + // replacement value
                            currentStr.substring(stop + ENVSTOP.length()); // tail
                    logger.debug("Replaced {} with {} at {}: {}", envName, envVal, filename, lnum);
                } else {
                    logger.debug("No env value for {} at {}: {}", envName, filename, lnum);
                }
            } else {
                throw new IOException("Runaway string on line ->" + currentStr + "<- at " + filename + ": " + lnum);
            }

            lastPos = thisPos;
        }
        logger.debug("Found {} env vars to subst --> {}", count, currentStr);
        return currentStr;
    }

    /**
     * Create a directory as specified by the config driver
     */
    protected boolean createDirectory(final String sval) {
        final String fixedSval = sval.replace('\\', '/');
        logger.debug("Trying to create dir {}", fixedSval);
        final File d = new File(fixedSval);
        if (!d.exists() && !d.mkdirs()) {
            logger.debug("Failed to create directory {}", fixedSval);
            return false;
        }
        return true;
    }

    /**
     * Create a file as specified by the config driver
     */
    protected boolean createFile(final String sval) {

        final String fixedSval = sval.replace('\\', '/');
        logger.debug("Trying to create file {}", fixedSval);
        final File d = new File(fixedSval);
        Writer newFile = null;
        if (!d.exists()) {
            try {
                // Ensure the directory exists to hold the file
                final File parent = new File(new File(d.getCanonicalPath()).getParent());
                if (!parent.exists() && !createDirectory(parent.toString())) {
                    logger.debug("Failed to create parent directory for {}", fixedSval);
                    return false;
                }
                // Create the file in the directory
                newFile = Files.newBufferedWriter(d.toPath(), UTF_8);
            } catch (IOException e) {
                logger.debug("Failed to create file {}", fixedSval, e);
                return false;
            } finally {
                if (newFile != null) {
                    try {
                        newFile.close();
                    } catch (IOException ioe) {
                        logger.debug("Error closing file", ioe);
                    }
                }
            }
        }
        return true;
    }

    /**
     * Get the names of all entries for this config This set is not backed by the configuration and any changes to it are
     * not reflected in the configuration.
     */
    @Override
    public Set<String> entryKeys() {
        final Set<String> set = new HashSet<>();
        for (final ConfigEntry curEntry : this.serviceParameters) {
            set.add(curEntry.getKey());
        }
        return set;
    }

    /**
     * Get all the entries for this config This is a copy and changes to it are not reflected in the configuration
     */
    @Override
    public List<ConfigEntry> getEntries() {
        return new ArrayList<>(this.serviceParameters);
    }

    /**
     * Remove entries, those with operators of '!=' are stored and can be retrieved for replay during merge. This method is
     * not part of the Configurator interface.
     */
    protected List<ConfigEntry> getRemoveEntries() {
        return new ArrayList<>(this.removeParameters);
    }

    /**
     * Add an entry to this config
     *
     * @param key the name of the entry
     * @param value the value
     * @return the new entry or null if it fails
     */
    @Override
    public ConfigEntry addEntry(final String key, final String value) {
        ConfigEntry entry = null;
        try {
            entry = handleNewEntry(key, value, "=", "<user>", 1, false);
        } catch (IOException ex) {
            logger.error("Could not add entry for {}", key, ex);
        }
        return entry;
    }

    /**
     * Add a list of entries for the same key
     *
     * @param key the name of the entry
     * @param values the values
     * @return the new entries or null if it fails
     */
    @Override
    public List<ConfigEntry> addEntries(final String key, final List<String> values) {
        final List<ConfigEntry> list = new ArrayList<>();
        try {
            int i = 1;
            for (final String value : values) {
                final ConfigEntry entry = handleNewEntry(key, value, "=", "<user>", i++, false);
                list.add(entry);
            }
        } catch (IOException ex) {
            logger.error("Error adding entries for {}", key, ex);
        }
        return list;
    }

    /**
     * Remove all entries by the given name
     *
     * @param key the name of the entry or entries
     * @param value the value
     */
    @Override
    public void removeEntry(final String key, final String value) {
        try {
            handleNewEntry(key, value, "!=", "<user>", 1, false);
        } catch (IOException ex) {
            logger.warn("Cannot remove entry", ex);
        }
    }

    /**
     * Remove an entry from the list of parameters matching the ConfigEntry argument passed in.
     *
     * @param anEntry the entry to remove
     */
    public void removeEntry(final ConfigEntry anEntry) {
        // NB: enhanced for loop does not support remove
        for (final Iterator<ConfigEntry> i = this.serviceParameters.iterator(); i.hasNext();) {
            final ConfigEntry curEntry = i.next();
            if (anEntry.getKey().equals(curEntry.getKey())
                    && ((anEntry.getValue() == null && curEntry.getValue() == null) || (anEntry.getValue() != null && anEntry.getValue().equals(
                            curEntry.getValue())))) {
                logger.debug("Removing {} = {}", curEntry.getKey(), curEntry.getValue());
                i.remove();
            }
        }
    }

    /**
     * Return a list containing all the parameter values matching the key argument passed in.
     *
     * @param theParameter the key to match
     * @param defaultString value for list when no matches are found
     * @return the list with all matching entries or the default value supplied
     */
    @Override
    public List<String> findEntries(final String theParameter, final String defaultString) {
        final List<String> result = findEntries(theParameter);
        if (result.isEmpty()) {
            result.add(defaultString);
        }
        return result;
    }

    /**
     * Return a list containing all the parameter values matching the key argument passed in
     *
     * @param theParameter the key to match
     * @return list with all matching entries, or empty list if none
     */
    @Override
    public List<String> findEntries(final String theParameter) {
        final List<String> matchingEntries = new ArrayList<>();

        for (final ConfigEntry curEntry : this.serviceParameters) {
            if (theParameter.equals(curEntry.getKey())) {
                matchingEntries.add(curEntry.getValue());
            }
        }
        return matchingEntries;
    }

    /**
     * Remove all entries from the list of parameters matching the String argument passed in.
     *
     * @param theParameter key name to match, all matching will be removed
     */
    public void removeAllEntries(final String theParameter) {
        // NB: enhanced for loop does not support remove
        for (final Iterator<ConfigEntry> i = this.serviceParameters.iterator(); i.hasNext();) {
            final ConfigEntry curEntry = i.next();
            if (theParameter.equals(curEntry.getKey())) {
                logger.debug("Removing {} = {}", curEntry.getKey(), curEntry.getValue());
                i.remove();
            }
        }
    }

    /**
     * Return a set with all parameter values as members
     *
     * @param theParameter key value to match
     * @return set of all entries found or empty set if none
     */
    @Override
    public Set<String> findEntriesAsSet(final String theParameter) {

        final Set<String> matchingEntries = new HashSet<>();

        for (final ConfigEntry curEntry : this.serviceParameters) {
            if (theParameter.equals(curEntry.getKey())) {
                matchingEntries.add(curEntry.getValue());
            }
        }
        return matchingEntries;
    }

    /**
     * Find entries beginning with the specified string
     *
     * @param theParameter key to match with a startsWith
     * @return list of entries matching specified value or empty list if none
     */
    @Override
    public List<ConfigEntry> findStringMatchEntries(final String theParameter) {

        final List<ConfigEntry> matchingEntries = new ArrayList<>();

        for (final ConfigEntry curEntry : this.serviceParameters) {
            if (curEntry.getKey().startsWith(theParameter)) {
                matchingEntries.add(curEntry);
            }
        }
        return matchingEntries;
    }

    /**
     * Find entries beginning with the specified string and put them into a list with the specified part of the name
     * stripped off like #findStringMatchMap
     *
     * @param theParameter key to match with a startsWith
     * @return list of ConfigEntry
     */
    @Override
    public List<ConfigEntry> findStringMatchList(final String theParameter) {
        final List<ConfigEntry> list = findStringMatchEntries(theParameter);
        for (final ConfigEntry entry : list) {
            entry.setKey(entry.getKey().substring(theParameter.length()));
        }
        return list;
    }

    /**
     * Find entries beginning with the specified string and return a hash keyed on the remainder of the string with the
     * value of the config line as the value of the hash
     *
     * @param theParameter the key to look for in the config file
     */
    @Override
    public Map<String, String> findStringMatchMap(final String theParameter) {
        return findStringMatchMap(theParameter, false);
    }

    /**
     * Find entries beginning with the specified string and return a hash keyed on the remainder of the string with the
     * value of the config line as the value of the hash
     *
     * <pre>
     * {@code
     * Example config entries
     *    FOO_ONE: AAA
     *    FOO_TWO: BBB
     * Calling findStringMatchMap("FOO_",true)
     * will yield a map with
     *     ONE -> AAA
     *     TWO -> BBB
     * }
     * </pre>
     *
     * @param theParameter the key to look for in the config file
     * @param preserveCase if false all keys will be upcased
     * @return map where key is remainder after match and value is the config value, or an empty map if none found
     */
    @Override
    public Map<String, String> findStringMatchMap(final String theParameter, final boolean preserveCase) {
        return findStringMatchMap(theParameter, preserveCase, false);
    }

    /**
     * Find entries beginning with the specified string and return a hash keyed on the remainder of the string with the
     * value of the config line as the value of the hash
     *
     * <pre>
     * {@code
     * Example config entries
     *    FOO_ONE: AAA
     *    FOO_TWO: BBB
     * Calling findStringMatchMap("FOO_",true)
     * will yield a map with
     *     ONE -> AAA
     *     TWO -> BBB
     * }
     * </pre>
     *
     * @param theParameter the key to look for in the config file
     * @param preserveCase if false all keys will be upcased
     * @param preserveOrder if true key ordering is preserved
     * @return map where key is remainder after match and value is the config value, or an empty map if none found
     */
    @Override
    public Map<String, String> findStringMatchMap(@Nullable final String theParameter, final boolean preserveCase, final boolean preserveOrder) {
        if (theParameter == null) {
            return Collections.emptyMap();
        }

        final Map<String, String> theHash = preserveOrder ? new LinkedHashMap<>() : new HashMap<>();
        final List<ConfigEntry> parameters = this.findStringMatchEntries(theParameter);

        for (final ConfigEntry el : parameters) {
            String key = el.getKey();
            key = key.substring(theParameter.length());
            if (!preserveCase) {
                key = key.toUpperCase(Locale.getDefault());
            }
            theHash.put(key, el.getValue());
        }
        return theHash;
    }

    /**
     * Find entries beginning with the specified string and return a hash keyed on the remainder of the string with the
     * value of the config line as the value of the hash.
     * <p>
     * Multiple values for the same hash are allowed and returned as a Set.
     *
     * <pre>
     * {@code
     * Example config entries
     *    FOO_ONE: AAA
     *    FOO_TWO: BBB
     *    FOO_TWO: CCC
     * Calling findStringMatchMap("FOO_",true)
     * will yield a map with Sets
     *     ONE -> {AAA}
     *     TWO -> {BBB,CCC}
     * }
     * </pre>
     *
     * @param param the key to look for in the config file
     * @return map where key is remainder after match and value is a Set of all found config values, or an empty map if none
     *         found
     */
    @Override
    public Map<String, Set<String>> findStringMatchMultiMap(@Nullable final String param) {
        if (param == null) {
            return Map.of();
        }

        return findStringMatchMultiMap(param, false);
    }

    /**
     * Find entries beginning with the specified string and return a hash keyed on the remainder of the string with the
     * value of the config line as the value of the hash.
     * <p>
     * Multiple values for the same hash are allowed and returned as a Set.
     *
     * <pre>
     * {@code
     * Example config entries
     *    FOO_one: AAA
     *    FOO_TWO: BBB
     *    FOO_TWO: CCC
     * Calling findStringMatchMap("FOO_",true)
     * will yield a map with Sets
     *     ONE -> {AAA}
     *     TWO -> {BBB,CCC}
     * }
     * </pre>
     *
     * @param param the key to look for in the config file
     * @param preserveOrder ordering of keys is preserved
     * @return map where key is remainder after match and value is a Set of all found config values, or an empty map if none
     *         found
     */
    @Override
    public Map<String, Set<String>> findStringMatchMultiMap(@Nullable final String param, final boolean preserveOrder) {
        if (param == null) {
            return Map.of();
        }

        return findStringMatchMultiMap(param, false, preserveOrder);
    }

    /**
     * Find entries beginning with the specified string and return a hash keyed on the remainder of the string with the
     * value of the config line as the value of the hash.
     * <p>
     * Multiple values for the same hash are allowed and returned as a Set.
     *
     * <pre>
     * {@code
     * Example config entries
     *    FOO_one: AAA
     *    FOO_TWO: BBB
     *    FOO_TWO: CCC
     * Calling findStringMatchMap("FOO_", true, true)
     * will yield a map with Sets
     *     one -> {AAA}
     *     TWO -> {BBB,CCC}
     * }
     * </pre>
     *
     * @param param the key to look for in the config file
     * @param preserveCase if false all keys will be uppercased
     * @param preserveOrder ordering of keys is preserved
     * @return map where key is remainder after match and value is a Set of all found config values, or an empty map if none
     *         found
     */
    @Override
    public Map<String, Set<String>> findStringMatchMultiMap(@Nullable final String param, final boolean preserveCase, final boolean preserveOrder) {
        if (param == null) {
            return Map.of();
        }

        final Map<String, Set<String>> theHash = preserveOrder ? new LinkedHashMap<>() : new HashMap<>();
        final List<ConfigEntry> parameters = this.findStringMatchEntries(param);

        for (final ConfigEntry el : parameters) {
            String keyBase = el.getKey().substring(param.length());
            final String key = preserveCase ? keyBase : keyBase.toUpperCase(Locale.getDefault());

            if (theHash.containsKey(key)) {
                theHash.get(key).add(el.getValue());
            } else {
                final Set<String> values = preserveOrder ? new LinkedHashSet<>() : new HashSet<>();
                values.add(el.getValue());
                theHash.put(key, values);
            }
        }
        return theHash;

    }

    /**
     * Return the first string entry matching the key parameter
     *
     * @param theParameter key to match
     * @return the first matching value
     * @throws IllegalArgumentException if no non-blank value is found
     */
    @Override
    public String findRequiredStringEntry(final String theParameter) {
        String value = findStringEntry(theParameter, null);
        Validate.notBlank(value, "Missing required parameter [%s]", theParameter);
        return value;
    }

    /**
     * Return the first string entry matching the key parameter or the default if no match is found
     *
     * @param theParameter the key to match
     * @param dflt string to use when no matches are found
     * @return the first matching entry of the default if none found
     */
    @Override
    public String findStringEntry(final String theParameter, @Nullable final String dflt) {
        final List<String> matchingEntries = findEntries(theParameter);
        for (final String entry : matchingEntries) {
            if (entry != null) {
                return entry;
            }
        }
        return dflt;
    }

    /**
     * Return the first string entry matching the key parameter or null if no match is found
     *
     * @param theParameter key to match
     * @return the first matching value or null if none
     */
    @Override
    public String findStringEntry(final String theParameter) {
        return findStringEntry(theParameter, null);
    }

    /**
     * Return the last (newest) string entry matching the key parameter or an empty string if no match is found
     *
     * @param theParameter the key to match
     * @return the last matching value or empty string if none found
     */
    @Override
    public String findLastStringEntry(final String theParameter) {
        String result = "";
        for (final ConfigEntry curEntry : this.serviceParameters) {
            if (theParameter.equals(curEntry.getKey())) {
                result = curEntry.getValue();
            }
        }
        return result;
    }

    /**
     * Return a long from a string entry representing either an int, a long, a double, with or without a final letter
     * designation such as "m" or "M" for megabytes, "g" or "G" for gigabytes, etc. Legal designations are bBkKmMgGTt or
     * just a number.
     *
     * @param theParameter the config entry name
     * @param dflt the default value when nothing found in config
     * @return the long value of the size parameter
     */
    @Override
    public long findSizeEntry(final String theParameter, final long dflt) {
        final List<String> matchingEntries = findEntries(theParameter);
        if (!matchingEntries.isEmpty()) {
            long val = dflt;
            final String s = matchingEntries.get(0);
            final char c = Character.toUpperCase(s.charAt(s.length() - 1));
            final String ss = s.substring(0, s.length() - 1);
            boolean broken = false;
            switch (c) {
                case 'T':
                    val = Long.parseLong(ss) * 1024 * 1024 * 1024 * 1024;
                    break;
                case 'G':
                    val = Long.parseLong(ss) * 1024 * 1024 * 1024;
                    break;
                case 'M':
                    val = Long.parseLong(ss) * 1024 * 1024;
                    break;
                case 'K':
                    val = Long.parseLong(ss) * 1024;
                    break;
                case 'B':
                    val = Long.parseLong(ss);
                    break;
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                    val = Long.parseLong(s);
                    break;
                default:
                    broken = true;
            }

            if (!broken) {
                return val;
            }
            return dflt;
        }
        return dflt;
    }

    /**
     * Return the string canonical file name of a matching entry
     *
     * @param theParameter the key to match
     * @param dflt the string to use as a default when no matches are found
     * @return the first matching value run through File.getCanonicalPath
     */
    @Override
    public String findCanonicalFileNameEntry(final String theParameter, final String dflt) {
        final String fn = findStringEntry(theParameter, dflt);
        if (fn != null && fn.length() > 0) {
            try {
                return new File(fn).getCanonicalPath();
            } catch (IOException ex) {
                logger.error("Cannot compute canonical path on {}", fn, ex);
            }
        }
        return fn;
    }

    /**
     * Return an int of the first entry matching the key parameter or the default if no match is found
     *
     * @param theParameter the key to match
     * @param dflt the int to use when no matches are found
     * @return the first matching value or the default when none found
     */
    @Override
    public int findIntEntry(final String theParameter, final int dflt) {
        final List<String> matchingEntries = findEntries(theParameter);

        if (!matchingEntries.isEmpty()) {
            try {
                return Integer.parseInt(matchingEntries.get(0));
            } catch (NumberFormatException e) {
                logger.warn("{} is non-numeric returning default value: {}", theParameter, dflt);
            }
        }
        return dflt;
    }

    /**
     * Return a long of the first entry matching the key parameter or the default if no match is found
     *
     * @param theParameter the key to match
     * @param dflt the value to use when no matches are found
     * @return the first matching value or the default when none found
     */
    @Override
    public long findLongEntry(final String theParameter, final long dflt) {
        final List<String> matchingEntries = findEntries(theParameter);

        if (!matchingEntries.isEmpty()) {
            try {
                return Long.parseLong(matchingEntries.get(0));
            } catch (NumberFormatException e) {
                logger.warn("{} is non-numeric returning default value: {}", theParameter, dflt);
            }
        }
        return dflt;
    }

    /**
     * Return a double of the first entry matching the key parameter or the default if no match is found
     *
     * @param theParameter the key to match
     * @param dflt the value to use when no matches are found
     * @return the first matching value or the default when none found
     */
    @Override
    public double findDoubleEntry(final String theParameter, final double dflt) {
        final List<String> matchingEntries = findEntries(theParameter);

        if (!matchingEntries.isEmpty()) {
            try {
                return Double.parseDouble(matchingEntries.get(0));
            } catch (NumberFormatException e) {
                logger.warn("{} is non-numeric returning default value: {}", theParameter, dflt);
            }
        }
        return dflt;
    }

    /**
     * Return boolean of the first entry matching the key parameter or the default if no match is found
     *
     * @param theParameter the key to match
     * @param dflt the value to use when no matches are found
     * @return the first matching value or the default when none found
     */
    @Override
    public boolean findBooleanEntry(final String theParameter, final boolean dflt) {
        final List<String> matchingEntries = findEntries(theParameter);

        if (!matchingEntries.isEmpty()) {
            String el = matchingEntries.get(0);
            el = el.toUpperCase(Locale.getDefault());
            if (el.startsWith("F")) {
                return false;
            } else if (el.startsWith("T")) {
                return true;
            }
        }
        return dflt;
    }

    /**
     * Return boolean of the first entry matching the key parameter or the default if no match is found
     *
     * @param theParameter the key to match
     * @param dflt the value to use when no matches are found
     * @return the first matching value or the default when none found
     */
    @Override
    public boolean findBooleanEntry(final String theParameter, final String dflt) {
        return findBooleanEntry(theParameter, Boolean.parseBoolean(dflt));
    }

    /**
     * Return the first entry matching the key parameter or the default if no match is found
     *
     * @param theParameter the key to match
     * @param parser method to turn the matched value from a String into the correct type
     * @param dflt the value to use when no matches are found
     * @return the first matching value or the default when none found
     * @param <T> the expected return type
     */
    @Override
    public <T> T findObjectEntry(String theParameter, Function<String, T> parser, T dflt) {
        final List<String> matchingEntries = findEntries(theParameter);
        if (!matchingEntries.isEmpty()) {
            String el = matchingEntries.get(0);
            return parser.apply(el);
        }
        return dflt;
    }

    /**
     * Get the value of a parameter that is purported to be numeric
     *
     * @param name the name of the parameter
     */
    protected int getNumericParameter(final String name) {
        final String val = this.values.get(name);
        int i = -1;
        if (val != null) {
            try {
                i = Integer.parseInt(val);
            } catch (NumberFormatException ex) {
                logger.warn("{} is non-numeric: {}", name, val);
            }
        }
        return i;
    }

    public boolean debug() {
        return "TRUE".equalsIgnoreCase(this.values.get("DEBUG"));
    }

    /**
     * Merge in a new configuration set with this one. New things are supposed to override older things in the sense of
     * findStringEntry which only picks the top of the list, the new things should get added to the top. If the merged in
     * Configurator contains remove entries (operator of '!=') then it only applies to entries in this instance, not in
     * "other". This is slightly different than when a config is read in directly, but without that there would be no way to
     * remove entries from a super-config and continue to supply entries here and still be able to use the wildcard remove
     * (value of '*') The order in the merged config file is important. Any '!= "*"' operations must precede the new value
     * being supplied since normal remove operations take place in each config before the merge.
     *
     * @param other the new entries to merge in
     */
    @Override
    public void merge(final Configurator other) throws IOException {
        int i = 1;

        // First handle the remove entries from "other"
        if (other instanceof ServiceConfigGuide) {
            for (final ConfigEntry entry : ((ServiceConfigGuide) other).getRemoveEntries()) {
                handleNewEntry(entry.getKey(), entry.getValue(), "!=", "<merge>", i++, true);
            }
        }

        // Add in new entries from "other" at the top of the list
        for (final ConfigEntry entry : other.getEntries()) {
            handleNewEntry(entry.getKey(), entry.getValue(), "=", "<merge>", i++, true);
        }
    }

    /**
     * Public main used to verify config file construction off-line
     */
    public static void main(final String[] args) {
        if (args.length < 1) {
            logger.error("usage: java ServiceConfigGuide configfile");
            return;
        }

        for (String arg : args) {
            try {
                final ServiceConfigGuide sc = new ServiceConfigGuide(arg);
                logger.info("Config File:{} ", arg);
                for (int i = 0; i < sc.serviceParameters.size(); i++) {
                    final ConfigEntry c = sc.serviceParameters.get(i);
                    logger.info("{}: {}", c.getKey(), c.getValue());
                }
                logger.info("---");
            } catch (IOException e) {
                logger.info("Cannot process {}:{}", arg, e.getLocalizedMessage());
            }
        }
    }
}
