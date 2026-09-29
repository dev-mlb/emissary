package emissary.server.api;

import emissary.client.response.Config;
import emissary.client.response.ConfigsResponseEntity;
import emissary.config.ConfigUtil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigsTest {

    @Test
    void validate() {
        assertDoesNotThrow(() -> Configs.validate("some.random.config.PlaceConfig"));
        assertDoesNotThrow(() -> Configs.validate("some.random.config.PlaceConfig.yaml"));
        assertDoesNotThrow(() -> Configs.validate("some.random.config.PlaceConfig.toml"));
        assertEquals("some.random.config.PlaceConfig.yaml", Configs.validate("some.random.config.PlaceConfig.yaml"));
        assertEquals("some.random.config.PlaceConfig.toml", Configs.validate("some.random.config.PlaceConfig.toml"));
        assertEquals("some.random.config.PlaceConfig.cfg", Configs.validate("some.random.config.PlaceConfig"));
        assertThrows(IllegalArgumentException.class, () -> Configs.validate("/dev/some.random.config.PlaceConfig"));
        assertThrows(IllegalArgumentException.class, () -> Configs.validate("https://dev/some.random.config.PlaceConfig"));
        assertThrows(IllegalArgumentException.class, () -> Configs.validate("..some.random.config.PlaceConfig"));
        assertThrows(IllegalArgumentException.class, () -> Configs.validate("."));
        assertThrows(IllegalArgumentException.class, () -> Configs.validate("%2e%2e%2fsome.random.config.PlaceConfig"));
        assertThrows(IllegalArgumentException.class, () -> Configs.validate("%252e%252e%252fsome.random.config.PlaceConfig"));
        assertThrows(IllegalArgumentException.class, () -> Configs.validate("U+002Fsome.random.config.PlaceConfigU+002F."));
    }

    @Test
    void testYamlConfig(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("emissary.test.CmdPlace.yaml"), "PLACE_NAME: CmdYaml\n", UTF_8);
        final String orig = System.getProperty(ConfigUtil.CONFIG_DIR_PROPERTY);
        System.setProperty(ConfigUtil.CONFIG_DIR_PROPERTY, dir.toString());
        ConfigUtil.initialize();
        try {
            final ConfigsResponseEntity plain = Configs.getConfigsResponse("emissary.test.CmdPlace.cfg", false);
            assertTrue(plain.getErrors().isEmpty(), "Non-detailed should load the .yaml fallback");
            assertTrue(containsEntry(plain.getLocal().getConfigs(), "PLACE_NAME", "CmdYaml"));

            final ConfigsResponseEntity detailed = Configs.getConfigsResponse("emissary.test.CmdPlace.cfg", true);
            assertTrue(detailed.getErrors().isEmpty(), "Detailed should load the .yaml fallback");
            assertTrue(containsEntry(detailed.getLocal().getConfigs(), "PLACE_NAME", "CmdYaml"));

            final ConfigsResponseEntity explicit = Configs.getConfigsResponse("emissary.test.CmdPlace.yaml", true);
            assertTrue(containsEntry(explicit.getLocal().getConfigs(), "PLACE_NAME", "CmdYaml"));
        } finally {
            if (orig != null) {
                System.setProperty(ConfigUtil.CONFIG_DIR_PROPERTY, orig);
            }
            ConfigUtil.initialize();
        }
    }

    @Test
    void testYamlConfigFlavored(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("emissary.test.FlavoredCmdPlace.yaml"), "FOO: base\n", UTF_8);
        Files.writeString(dir.resolve("emissary.test.FlavoredCmdPlace-CMD.yaml"), "FOO: flavored\n", UTF_8);
        final String origDir = System.getProperty(ConfigUtil.CONFIG_DIR_PROPERTY);
        final String origFlav = System.getProperty(ConfigUtil.CONFIG_FLAVOR_PROPERTY);
        System.setProperty(ConfigUtil.CONFIG_DIR_PROPERTY, dir.toString());
        System.setProperty(ConfigUtil.CONFIG_FLAVOR_PROPERTY, "CMD");
        ConfigUtil.initialize();
        try {
            final ConfigsResponseEntity plain = Configs.getConfigsResponse("emissary.test.FlavoredCmdPlace.cfg", false);
            assertTrue(containsEntry(plain.getLocal().getConfigs(), "FOO", "flavored"));

            final ConfigsResponseEntity detailed = Configs.getConfigsResponse("emissary.test.FlavoredCmdPlace.cfg", true);
            // Base layer shows the unmerged base file, combined layer shows the flavor winning.
            assertTrue(containsEntry(detailed.getLocal().getConfigs(), "FOO", "base"));
            assertTrue(containsEntry(detailed.getLocal().getConfigs(), "FOO", "flavored"));
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

    private static boolean containsEntry(final List<Config> configs, final String key, final String value) {
        return configs.stream()
                .flatMap(c -> c.getEntries().stream())
                .anyMatch(e -> key.equals(e.getKey()) && value.equals(e.getValue()));
    }
}
