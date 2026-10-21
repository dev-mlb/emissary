package emissary.core;

import emissary.config.Configurator;
import emissary.config.ServiceConfigGuide;
import emissary.test.core.junit5.UnitTest;

import com.codahale.metrics.Timer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsManagerTest extends UnitTest {
    @Test
    void testMetricsReporting() {
        Configurator conf = new ServiceConfigGuide();
        conf.addEntry("JVM_METRICS_ENABLED", "true");
        conf.addEntry("GRAPHITE_METRICS_ENABLED", "true");
        conf.addEntry("SLF4J_METRICS_ENABLED", "true");
        MetricsManager mm = new MetricsManager(conf);
        Map<String, Timer> stats = new HashMap<>();
        assertDoesNotThrow(() -> mm.logMetrics(stats));
        // need to assert some stuff here, but what?
    }

    @Test
    void testMicrometerDisabledByDefault() {
        MetricsManager mm = new MetricsManager(new ServiceConfigGuide());

        assertNotNull(mm.getMeterRegistry());
        assertTrue(mm.getMeterRegistry().getRegistries().isEmpty());
        assertNull(mm.getPrometheusRegistry());
        assertEquals("emissary", mm.getMetricsPrefix());

        mm.shutdown();
    }

    @Test
    void testMicrometerPrometheusEnabled() {
        Configurator conf = new ServiceConfigGuide();
        conf.addEntry("PROMETHEUS_METRICS_ENABLED", "true");
        conf.addEntry("METRICS_PREFIX", "test");
        conf.addEntry("COMMON_TAGS", "node=testnode");

        MetricsManager mm = new MetricsManager(conf);

        assertNotNull(mm.getMeterRegistry());
        assertTrue(mm.getMeterRegistry().getRegistries().stream().anyMatch(r -> r instanceof PrometheusMeterRegistry));
        assertNotNull(mm.getPrometheusRegistry());
        assertEquals("test", mm.getMetricsPrefix());

        // a meter recorded on the composite shows up in the prometheus registry with the common tags applied
        mm.getMeterRegistry().counter("test.counter").increment();

        String scrape = mm.getPrometheusRegistry().scrape();
        assertTrue(scrape.contains("test_counter_total"), scrape);
        assertTrue(scrape.contains("node=\"testnode\""), scrape);

        mm.shutdown();
    }

    @Test
    void testMicrometerCommonTagsAppliedToEveryRegistry() {
        Configurator conf = new ServiceConfigGuide();
        conf.addEntry("PROMETHEUS_METRICS_ENABLED", "true");
        conf.addEntry("MICROMETER_JMX_ENABLED", "true");
        conf.addEntry("COMMON_TAGS", "node=testnode");
        conf.addEntry("COMMON_TAGS", "env=test");

        MetricsManager mm = new MetricsManager(conf);

        assertEquals(2, mm.getMeterRegistry().getRegistries().size());
        mm.getMeterRegistry().counter("test.counter").increment();

        String scrape = mm.getPrometheusRegistry().scrape();
        assertTrue(scrape.contains("node=\"testnode\""), scrape);
        assertTrue(scrape.contains("env=\"test\""), scrape);

        mm.shutdown();
    }

    @Test
    void testMicrometerMalformedCommonTagIsIgnored() {
        Configurator conf = new ServiceConfigGuide();
        conf.addEntry("PROMETHEUS_METRICS_ENABLED", "true");
        conf.addEntry("COMMON_TAGS", "malformed");
        conf.addEntry("COMMON_TAGS", "node=testnode");

        MetricsManager mm = new MetricsManager(conf);

        mm.getMeterRegistry().counter("test.counter").increment();

        String scrape = mm.getPrometheusRegistry().scrape();
        assertTrue(scrape.contains("node=\"testnode\""), scrape);
        assertFalse(scrape.contains("malformed"), scrape);

        mm.shutdown();
    }

    @Test
    void testMicrometerInvalidDurationIsRejected() {
        Configurator conf = new ServiceConfigGuide();
        conf.addEntry("MICROMETER_GRAPHITE_ENABLED", "true");
        conf.addEntry("MICROMETER_GRAPHITE_STEP", "1min");

        assertThrows(IllegalArgumentException.class, () -> new MetricsManager(conf));
    }
}
