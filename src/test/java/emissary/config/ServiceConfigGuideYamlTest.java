package emissary.config;

import emissary.test.core.junit5.UnitTest;

import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceConfigGuideYamlTest extends UnitTest {

    private static ServiceConfigGuide parse(final String yaml, final String name) throws IOException {
        try {
            return new ServiceConfigGuide(new ByteArrayInputStream(yaml.getBytes(UTF_8)), name);
        } catch (IOException e) {
            throw e;
        }
    }

    @Test
    void testFlattening() throws IOException {
        final String yaml = "FOO: bar\nCOUNT: 42\nRENDEZVOUS_PEER:\n"
                + "  - \"*.*.*.http://h1:7001/DirectoryPlace\"\n"
                + "  - \"*.*.*.http://h2:8001/DirectoryPlace\"\n"
                + "NESTED:\n  ONE: AAA\n  TWO: BBB\n";
        final ServiceConfigGuide scg = parse(yaml, "test.yaml");
        assertEquals("bar", scg.findStringEntry("FOO"));
        assertEquals("42", scg.findStringEntry("COUNT"));
        assertEquals(2, scg.findEntries("RENDEZVOUS_PEER").size());
        assertEquals("AAA", scg.findStringEntry("NESTED_ONE"));
        assertEquals("BBB", scg.findStringEntry("NESTED_TWO"));
    }

    @Test
    void testSubstitution() throws IOException {
        final String yaml = "BASE: hello\nGREETING: \"@{BASE} world\"\n";
        final ServiceConfigGuide scg = parse(yaml, "test.yaml");
        assertEquals("hello world", scg.findStringEntry("GREETING"));
    }

    @Test
    void testBadYaml() {
        final String bad = "FOO: [unclosed\n";
        final IOException e = assertThrows(IOException.class, () -> parse(bad, "bad.yaml"));
        assertTrue(e.getMessage().contains("bad.yaml:1:"),
                "Syntax error should carry file:line:col, was: " + e.getMessage());
    }

    @Test
    void testNonMappingTopLevel() {
        final IOException e = assertThrows(IOException.class, () -> parse("- a\n- b\n", "list.yaml"));
        assertTrue(e.getMessage().contains("list.yaml") && e.getMessage().contains("sequence"),
                "Should name the file and the offending kind, was: " + e.getMessage());
    }

    @Test
    void testSuffixDetection() {
        assertTrue(ServiceConfigGuide.isYamlFile("foo.yaml"));
        assertTrue(ServiceConfigGuide.isYamlFile("foo.yml"));
        assertTrue(ServiceConfigGuide.isYamlFile("foo.YAML"));
        assertFalse(ServiceConfigGuide.isYamlFile("foo.cfg"));
        assertFalse(ServiceConfigGuide.isYamlFile("foo.ycfg"));
    }

    @Test
    void testCfgFallsBackToYaml(@TempDir final Path dir) throws Exception {
        final String base = "emissary.test.YamlFallbackPlace";
        Files.writeString(dir.resolve(base + ".yaml"), "FOO: from-yaml\n", UTF_8);
        final String orig = System.getProperty(ConfigUtil.CONFIG_DIR_PROPERTY);
        System.setProperty(ConfigUtil.CONFIG_DIR_PROPERTY, dir.toString());
        ConfigUtil.initialize();
        try {
            final Configurator cfg = ConfigUtil.getConfigInfo(base + ".cfg");
            assertEquals("from-yaml", cfg.findStringEntry("FOO"));
        } finally {
            if (orig != null) {
                System.setProperty(ConfigUtil.CONFIG_DIR_PROPERTY, orig);
            }
            ConfigUtil.initialize();
        }
    }

    @Test
    void testImportYamlFromCfg(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("shared.yaml"), "SHARED_KEY: shared-val\n", UTF_8);
        Files.writeString(dir.resolve("main.cfg"), "IMPORT_FILE = shared.yaml\nOWN_KEY = own\n", UTF_8);
        final String orig = System.getProperty(ConfigUtil.CONFIG_DIR_PROPERTY);
        System.setProperty(ConfigUtil.CONFIG_DIR_PROPERTY, dir.toString());
        ConfigUtil.initialize();
        try {
            final Configurator cfg = ConfigUtil.getConfigInfo("main.cfg");
            assertEquals("own", cfg.findStringEntry("OWN_KEY"));
            assertEquals("shared-val", cfg.findStringEntry("SHARED_KEY"));
            // IMPORT_FILE referencing a .cfg name resolves a .yaml on disk
            Files.writeString(dir.resolve("aliased.yaml"), "ALIASED: \"yes\"\n", UTF_8);
            Files.writeString(dir.resolve("uses-alias.cfg"), "IMPORT_FILE = aliased.cfg\n", UTF_8);
            final Configurator cfg2 = ConfigUtil.getConfigInfo("uses-alias.cfg");
            assertEquals("yes", cfg2.findStringEntry("ALIASED"));
        } finally {
            if (orig != null) {
                System.setProperty(ConfigUtil.CONFIG_DIR_PROPERTY, orig);
            }
            ConfigUtil.initialize();
        }
    }

    @Test
    void testCandidateNames() {
        final List<String> cands = ConfigUtil.candidateNames("foo.cfg");
        assertEquals(List.of("foo.cfg", "foo.yaml", "foo.yml", "foo.toml"), cands);
        assertEquals(List.of("foo.yaml"), ConfigUtil.candidateNames("foo.yaml"));
        assertEquals(List.of("foo.yml"), ConfigUtil.candidateNames("foo.yml"));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void withConfigDirAndFlavor(final Path dir, @Nullable final String flavor, final ThrowingRunnable test)
            throws Exception {
        final String origDir = System.getProperty(ConfigUtil.CONFIG_DIR_PROPERTY);
        final String origFlav = System.getProperty(ConfigUtil.CONFIG_FLAVOR_PROPERTY);
        System.setProperty(ConfigUtil.CONFIG_DIR_PROPERTY, dir.toString());
        if (flavor != null) {
            System.setProperty(ConfigUtil.CONFIG_FLAVOR_PROPERTY, flavor);
        } else {
            System.clearProperty(ConfigUtil.CONFIG_FLAVOR_PROPERTY);
        }
        ConfigUtil.initialize();
        try {
            test.run();
        } finally {
            if (origDir != null) {
                System.setProperty(ConfigUtil.CONFIG_DIR_PROPERTY, origDir);
            }
            if (origFlav != null) {
                System.setProperty(ConfigUtil.CONFIG_FLAVOR_PROPERTY, origFlav);
            } else {
                System.clearProperty(ConfigUtil.CONFIG_FLAVOR_PROPERTY);
            }
            ConfigUtil.initialize();
        }
    }

    @Test
    void testFlavorOverride(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("app.yaml"),
                "FOO: base\nSTABLE: keep\n\"!flavor-MYFLAV\":\n  FOO: flavored\n  EXTRA: 1\n", UTF_8);
        withConfigDirAndFlavor(dir, "MYFLAV", () -> {
            final Configurator cfg = ConfigUtil.getConfigInfo("app.yaml");
            assertEquals("flavored", cfg.findStringEntry("FOO"));
            assertEquals("keep", cfg.findStringEntry("STABLE"));
            assertEquals("1", cfg.findStringEntry("EXTRA"));
        });
    }

    @Test
    void testFlavorInactiveSkipped(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("app.yaml"),
                "FOO: base\n\"!flavor-MYFLAV\":\n  FOO: flavored\n  EXTRA: 1\n", UTF_8);
        withConfigDirAndFlavor(dir, null, () -> {
            final Configurator cfg = ConfigUtil.getConfigInfo("app.yaml");
            assertEquals("base", cfg.findStringEntry("FOO"));
            assertNull(cfg.findStringEntry("EXTRA"));
        });
        withConfigDirAndFlavor(dir, "OTHER", () -> {
            final Configurator cfg = ConfigUtil.getConfigInfo("app.yaml");
            assertEquals("base", cfg.findStringEntry("FOO"));
        });
    }

    @Test
    void testFlavorGroupedForm(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("app.yaml"),
                "FOO: base\n\"!flavor\":\n  MYFLAV:\n    FOO: grouped\n", UTF_8);
        withConfigDirAndFlavor(dir, "MYFLAV", () -> {
            assertEquals("grouped", ConfigUtil.getConfigInfo("app.yaml").findStringEntry("FOO"));
        });
        withConfigDirAndFlavor(dir, null, () -> {
            assertEquals("base", ConfigUtil.getConfigInfo("app.yaml").findStringEntry("FOO"));
        });
    }

    @Test
    void testFlavorRemoveAndImport(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("extra.yaml"), "FROM_EXTRA: \"yes\"\n", UTF_8);
        Files.writeString(dir.resolve("app.yaml"),
                "GONE: [a, b]\nKEPT: 1\n\"!flavor-MYFLAV\":\n  KEPT: 2\n  \"!remove\":\n    GONE: a\n  \"!import\": extra.yaml\n",
                UTF_8);
        withConfigDirAndFlavor(dir, "MYFLAV", () -> {
            final Configurator cfg = ConfigUtil.getConfigInfo("app.yaml");
            assertEquals("2", cfg.findStringEntry("KEPT"));
            assertEquals(List.of("b"), cfg.findEntries("GONE"));
            assertEquals("yes", cfg.findStringEntry("FROM_EXTRA"));
        });
    }

    @Test
    void testInactiveFlavorIgnoresImport(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("app.yaml"),
                "FOO: base\n\"!flavor-MYFLAV\":\n  \"!import\": no-such-file.yaml\n", UTF_8);
        withConfigDirAndFlavor(dir, null, () -> {
            assertEquals("base", ConfigUtil.getConfigInfo("app.yaml").findStringEntry("FOO"));
        });
    }

    @Test
    void testDuplicateKeysKeepLast() throws IOException {
        // Unlike legacy .cfg, YAML mappings cannot repeat keys: the last value wins (with a logged warning).
        // Multi-valued entries must use sequences.
        final ServiceConfigGuide scg = parse("FOO: a\nFOO: b\n", "test.yaml");
        assertEquals(List.of("b"), scg.findEntries("FOO"));
    }

    @Test
    void testDuplicateKeysStrict() {
        System.setProperty(ConfigUtil.STRICT_MODE_PROPERTY, "true");
        try {
            final IOException e = assertThrows(IOException.class, () -> parse("FOO: a\nFOO: b\n", "test.yaml"));
            assertTrue(e.getMessage().contains("strict"), "Was: " + e.getMessage());
        } finally {
            System.clearProperty(ConfigUtil.STRICT_MODE_PROPERTY);
        }
        // And loading works again once strict mode is off.
        assertDoesNotThrow(() -> parse("FOO: a\nFOO: b\n", "test.yaml"));
    }

    @Test
    void testCollisionKeepsBoth() throws IOException {
        // NESTED: {B_C} flattens to the same key as the literal NESTED_B_C: both are kept (with a logged warning),
        // lookups return the first.
        final ServiceConfigGuide scg = parse("NESTED:\n  B_C: nested\nNESTED_B_C: literal\n", "test.yaml");
        assertEquals(List.of("nested", "literal"), scg.findEntries("NESTED_B_C"));
        assertEquals("nested", scg.findStringEntry("NESTED_B_C"));
    }

    @Test
    void testCollisionStrict() {
        System.setProperty(ConfigUtil.STRICT_MODE_PROPERTY, "true");
        try {
            final IOException e = assertThrows(IOException.class,
                    () -> parse("NESTED:\n  B_C: nested\nNESTED_B_C: literal\n", "test.yaml"));
            assertTrue(e.getMessage().contains("collides"), "Was: " + e.getMessage());
        } finally {
            System.clearProperty(ConfigUtil.STRICT_MODE_PROPERTY);
        }
    }

    @Test
    void testSequenceItemsNotCollisions() throws IOException {
        final ServiceConfigGuide scg = parse("FOO:\n  - a\n  - b\n", "test.yaml");
        assertEquals(List.of("a", "b"), scg.findEntries("FOO"));
    }

    @Test
    void testCreateDirectives(@TempDir final Path dir) throws IOException {
        final Path subdir = dir.resolve("sub/dir");
        final Path file = dir.resolve("sub/file.txt");
        final String yaml = "CREATE_DIRECTORY: \"" + subdir + "\"\nCREATE_FILE: \"" + file + "\"\n";
        parse(yaml, "test.yaml");
        assertTrue(Files.isDirectory(subdir), "CREATE_DIRECTORY should create " + subdir);
        assertTrue(Files.isRegularFile(file), "CREATE_FILE should create " + file);
    }

    @Test
    void testFlavoredImportChain(@TempDir final Path dir) throws Exception {
        // app.cfg (legacy) -> mid.yaml (YAML, inline flavor + import) -> leaf.yaml (YAML, inline flavor).
        // Inline flavor sections compose through imports in both directions and formats.
        Files.writeString(dir.resolve("leaf.yaml"), "LEAF: base\n\"!flavor-CHAINFLAV\":\n  LEAF: flavored\n", UTF_8);
        Files.writeString(dir.resolve("mid.yaml"),
                "MID: 1\n\"!import\": leaf.yaml\n\"!flavor-CHAINFLAV\":\n  MID: 2\n", UTF_8);
        Files.writeString(dir.resolve("app.cfg"), "IMPORT_FILE = mid.yaml\nAPP = 0\n", UTF_8);
        withConfigDirAndFlavor(dir, "CHAINFLAV", () -> {
            final Configurator cfg = ConfigUtil.getConfigInfo("app.cfg");
            assertEquals("0", cfg.findStringEntry("APP"));
            assertEquals("2", cfg.findStringEntry("MID"), "Inline flavor override should win");
            assertEquals("flavored", cfg.findStringEntry("LEAF"), "Flavor in imported file should apply");
        });
        withConfigDirAndFlavor(dir, null, () -> {
            final Configurator cfg = ConfigUtil.getConfigInfo("app.cfg");
            assertEquals("base", cfg.findStringEntry("LEAF"));
            assertEquals("1", cfg.findStringEntry("MID"));
        });
    }

    @Test
    void testPreferenceFallback(@TempDir final Path dir) throws Exception {
        // Node-style prefs (cf. EmissaryNode.internalGetConfigurator): only the .yaml exists.
        Files.writeString(dir.resolve("peer-myhost-8001.yaml"), "RENDEZVOUS_PEER: peer-one\n", UTF_8);
        // Place-style prefs (cf. IServiceProviderPlace): only the .yaml exists.
        Files.writeString(dir.resolve("emissary.test.FooPlace.yaml"), "PLACE_NAME: FooYaml\n", UTF_8);
        withConfigDirAndFlavor(dir, null, () -> {
            final Configurator nodeCfg = ConfigUtil
                    .getConfigInfo(List.of("peer-myhost-8001.cfg", "peer-myhost.cfg", "peer.cfg"));
            assertEquals("peer-one", nodeCfg.findStringEntry("RENDEZVOUS_PEER"));

            final Configurator placeCfg = ConfigUtil.getConfigInfo("emissary.test.FooPlace.cfg");
            assertEquals("FooYaml", placeCfg.findStringEntry("PLACE_NAME"));
        });
    }

    @Test
    void testCfgPrecedence(@TempDir final Path dir) throws Exception {
        // Compatibility guarantee: a legacy .cfg is never shadowed by a same-named .yaml.
        Files.writeString(dir.resolve("contested.cfg"), "FOO = from-cfg\n", UTF_8);
        Files.writeString(dir.resolve("contested.yaml"), "FOO: from-yaml\n", UTF_8);
        withConfigDirAndFlavor(dir, null, () -> {
            assertEquals("from-cfg", ConfigUtil.getConfigInfo("contested.cfg").findStringEntry("FOO"));
        });
    }

    @Test
    void testPeerRoundTrip() throws IOException {
        // The real peer.cfg translated to YAML must load an identical entry multiset.
        final Path cfgPath = Path.of("src/main/config/peer.cfg");
        Assumptions.assumeTrue(Files.exists(cfgPath), "Needs repo checkout");
        final ServiceConfigGuide fromCfg;
        try (InputStream is = Files.newInputStream(cfgPath)) {
            fromCfg = new ServiceConfigGuide(is, "peer.cfg");
        }
        final String yaml = "RENDEZVOUS_PEER:\n"
                + "  - \"*.*.*.http://@{emissary.node.name}:7001/DirectoryPlace\"\n"
                + "  - \"*.*.*.http://@{emissary.node.name}:8001/DirectoryPlace\"\n"
                + "  - \"*.*.*.http://@{emissary.node.name}:9001/DirectoryPlace\"\n";
        final ServiceConfigGuide fromYaml = parse(yaml, "peer.yaml");
        assertEquals(entriesAsStrings(fromCfg), entriesAsStrings(fromYaml));
    }

    private static List<String> entriesAsStrings(final Configurator cfg) {
        final List<String> out = new ArrayList<>();
        for (final ConfigEntry e : cfg.getEntries()) {
            out.add(e.getKey() + "=" + e.getValue());
        }
        return out;
    }

    @Test
    void testNestedFlavorRejected() {
        final IOException e = assertThrows(IOException.class,
                () -> parse("TOP:\n  \"!flavor-X\":\n    FOO: bar\n", "test.yaml"));
        assertTrue(e.getMessage().contains("top level"), "Was: " + e.getMessage());
    }

    @Test
    void testFlavorBadValue() {
        final IOException e = assertThrows(IOException.class, () -> parse("\"!flavor-X\": [a]\n", "test.yaml"));
        assertTrue(e.getMessage().contains("must be a mapping"), "Was: " + e.getMessage());
    }

    @Test
    void testClasspathYaml() throws IOException {
        final Configurator cfg = ConfigUtil.getConfigInfo("emissary.config.TestYamlClasspath.yaml");
        assertEquals("from-classpath", cfg.findStringEntry("CLASSPATH_KEY"));
        assertEquals("AAA", cfg.findStringEntry("NESTED_ONE"));
    }

    @Test
    void testClasspathYml() throws IOException {
        final Configurator cfg = ConfigUtil.getConfigInfo("emissary.config.TestYmlClasspath.yml");
        assertEquals("from-yml-classpath", cfg.findStringEntry("YML_KEY"));
    }

    @Test
    void testSampleParses() throws IOException {
        final Configurator cfg = ConfigUtil.getConfigInfo("emissary.config.Sample.yaml");
        assertEquals("SamplePlace", cfg.findStringEntry("PLACE_NAME"));
        assertEquals(2, cfg.findEntries("RENDEZVOUS_PEER").size());
        assertEquals("AAA", cfg.findStringEntry("MYPROPS_ONE"));
        assertEquals("hello world", cfg.findStringEntry("GREETING"));
        assertEquals("from-classpath", cfg.findStringEntry("CLASSPATH_KEY"));
    }

    @Test
    void testClasspathCfgFallback() throws IOException {
        final Configurator cfg = ConfigUtil.getConfigInfo("emissary.config.TestYamlClasspath.cfg");
        assertEquals("from-classpath", cfg.findStringEntry("CLASSPATH_KEY"));
    }

    @Test
    void testBangRemove() throws IOException {
        final String yaml = "FOO:\n  - a\n  - b\nGONE:\n  - x\n  - y\n\"!remove\":\n  FOO: a\n  GONE: \"*\"\n";
        final ServiceConfigGuide scg = parse(yaml, "test.yaml");
        assertEquals(List.of("b"), scg.findEntries("FOO"));
        assertTrue(scg.findEntries("GONE").isEmpty());
    }

    @Test
    void testPositionalRemoveInSequence() throws IOException {
        // Add, remove, re-add in one sequence evaluates in order, like the legacy lines would.
        final String yaml = "FOO:\n  - a\n  - {\"!remove\": a}\n  - b\n";
        final ServiceConfigGuide scg = parse(yaml, "test.yaml");
        assertEquals(List.of("b"), scg.findEntries("FOO"));
    }

    @Test
    void testPositionalRemoveWildcardFirst() throws IOException {
        // Flavor-file pattern: clear inherited entries, then add new ones.
        final String yaml = "FOO:\n  - {\"!remove\": \"*\"}\n  - b\n  - c\n";
        final ServiceConfigGuide scg = parse(yaml, "test.yaml");
        assertEquals(List.of("b", "c"), scg.findEntries("FOO"));
    }

    @Test
    void testSequenceMapNotRemoveThrows() {
        final IOException e = assertThrows(IOException.class,
                () -> parse("FOO:\n  - {BAR: baz}\n", "test.yaml"));
        assertTrue(e.getMessage().contains("single-entry {\"!remove\""), "Was: " + e.getMessage());
    }

    @Test
    void testBangRemoveBadValue() {
        final IOException e = assertThrows(IOException.class, () -> parse("\"!remove\": [a]\n", "test.yaml"));
        assertTrue(e.getMessage().contains("\"!remove\"") && e.getMessage().contains("test.yaml"),
                "Was: " + e.getMessage());
    }

    @Test
    void testNestedCollectionError() {
        final IOException e = assertThrows(IOException.class,
                () -> parse("TOP:\n  NESTED:\n    - {A: b}\n", "test.yaml"));
        assertTrue(e.getMessage().contains("TOP_NESTED") && e.getMessage().contains("$.TOP.NESTED[0]"),
                "Was: " + e.getMessage());
    }

    @Test
    void testMissingImportError() {
        final IOException e = assertThrows(IOException.class,
                () -> parse("\"!import\": no-such-file.yaml\n", "test.yaml"));
        assertTrue(e.getMessage().contains("IMPORT_FILE") && e.getMessage().contains("$.!import"),
                "Was: " + e.getMessage());
    }

    @Test
    void testBangImport(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("shared.yaml"), "SHARED_KEY: shared-val\n", UTF_8);
        Files.writeString(dir.resolve("main.yaml"), "\"!import\": shared.yaml\nOWN_KEY: own\n", UTF_8);
        final String orig = System.getProperty(ConfigUtil.CONFIG_DIR_PROPERTY);
        System.setProperty(ConfigUtil.CONFIG_DIR_PROPERTY, dir.toString());
        ConfigUtil.initialize();
        try {
            final Configurator cfg = ConfigUtil.getConfigInfo("main.yaml");
            assertEquals("own", cfg.findStringEntry("OWN_KEY"));
            assertEquals("shared-val", cfg.findStringEntry("SHARED_KEY"));
        } finally {
            if (orig != null) {
                System.setProperty(ConfigUtil.CONFIG_DIR_PROPERTY, orig);
            }
            ConfigUtil.initialize();
        }
    }

    @Test
    void testOptImportMissingSilent(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("main.yaml"), "\"!opt-import\": no-such-file.yaml\nOWN_KEY: own\n", UTF_8);
        final String orig = System.getProperty(ConfigUtil.CONFIG_DIR_PROPERTY);
        System.setProperty(ConfigUtil.CONFIG_DIR_PROPERTY, dir.toString());
        ConfigUtil.initialize();
        try {
            final Configurator cfg = ConfigUtil.getConfigInfo("main.yaml");
            assertEquals("own", cfg.findStringEntry("OWN_KEY"));
        } finally {
            if (orig != null) {
                System.setProperty(ConfigUtil.CONFIG_DIR_PROPERTY, orig);
            }
            ConfigUtil.initialize();
        }
    }

    @Test
    void testFileFlavors(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("emissary.test.FlavoredPlace.yaml"), "FOO: base\nONLY_BASE: 1\n", UTF_8);
        Files.writeString(dir.resolve("emissary.test.FlavoredPlace-MYFLAV.yaml"), "FOO: flavored\nONLY_FLAV: 2\n", UTF_8);
        final String origDir = System.getProperty(ConfigUtil.CONFIG_DIR_PROPERTY);
        final String origFlav = System.getProperty(ConfigUtil.CONFIG_FLAVOR_PROPERTY);
        System.setProperty(ConfigUtil.CONFIG_DIR_PROPERTY, dir.toString());
        System.setProperty(ConfigUtil.CONFIG_FLAVOR_PROPERTY, "MYFLAV");
        ConfigUtil.initialize();
        try {
            final Configurator cfg = ConfigUtil.getConfigInfo("emissary.test.FlavoredPlace.yaml");
            // flavor merge prepends, so the flavored value wins findStringEntry
            assertEquals("flavored", cfg.findStringEntry("FOO"));
            assertEquals("1", cfg.findStringEntry("ONLY_BASE"));
            assertEquals("2", cfg.findStringEntry("ONLY_FLAV"));
        } finally {
            if (origDir != null) {
                System.setProperty(ConfigUtil.CONFIG_DIR_PROPERTY, origDir);
            }
            if (origFlav != null) {
                System.setProperty(ConfigUtil.CONFIG_FLAVOR_PROPERTY, origFlav);
            } else {
                System.clearProperty(ConfigUtil.CONFIG_FLAVOR_PROPERTY);
            }
            ConfigUtil.initialize();
        }
    }

    @Test
    void testShortName(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("ShortPlace.yml"), "FOO: short\n", UTF_8);
        final String orig = System.getProperty(ConfigUtil.CONFIG_DIR_PROPERTY);
        System.setProperty(ConfigUtil.CONFIG_DIR_PROPERTY, dir.toString());
        ConfigUtil.initialize();
        try {
            final Configurator cfg = ConfigUtil.getConfigInfo("com.example.ShortPlace.yml");
            assertEquals("short", cfg.findStringEntry("FOO"));
        } finally {
            if (orig != null) {
                System.setProperty(ConfigUtil.CONFIG_DIR_PROPERTY, orig);
            }
            ConfigUtil.initialize();
        }
    }

    @Test
    void testInventoryYaml(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("emissary.admin.ClassNameInventory.yaml"),
                "TestPlaceA: emissary.place.TestPlaceA\n", UTF_8);
        Files.writeString(dir.resolve("emissary.admin.ClassNameInventory-extra.yaml"),
                "TestPlaceB: emissary.place.TestPlaceB\n", UTF_8);
        final String orig = System.getProperty(ConfigUtil.CONFIG_DIR_PROPERTY);
        System.setProperty(ConfigUtil.CONFIG_DIR_PROPERTY, dir.toString());
        ConfigUtil.initialize();
        try {
            final Configurator cfg = ConfigUtil.getClassNameInventory();
            assertEquals("emissary.place.TestPlaceA", cfg.findStringEntry("TestPlaceA"));
            assertEquals("emissary.place.TestPlaceB", cfg.findStringEntry("TestPlaceB"));
        } finally {
            if (orig != null) {
                System.setProperty(ConfigUtil.CONFIG_DIR_PROPERTY, orig);
            }
            ConfigUtil.initialize();
        }
    }
}
