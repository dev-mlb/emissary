package emissary.core;

import emissary.config.ConfigUtil;
import emissary.config.Configurator;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Slf4jReporter;
import com.codahale.metrics.Timer;
import com.codahale.metrics.graphite.Graphite;
import com.codahale.metrics.graphite.GraphiteReporter;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.codahale.metrics.health.jvm.ThreadDeadlockHealthCheck;
import com.codahale.metrics.jmx.JmxReporter;
import com.codahale.metrics.jvm.FileDescriptorRatioGauge;
import com.codahale.metrics.jvm.GarbageCollectorMetricSet;
import com.codahale.metrics.jvm.MemoryUsageGaugeSet;
import com.codahale.metrics.jvm.ThreadStatesGaugeSet;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.graphite.GraphiteConfig;
import io.micrometer.graphite.GraphiteMeterRegistry;
import io.micrometer.graphite.GraphiteProtocol;
import io.micrometer.jmx.JmxConfig;
import io.micrometer.jmx.JmxMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages the interactions with CodaHale's Metrics package, including configuration
 */
public class MetricsManager {

    public static final String DEFAULT_NAMESPACE_NAME = "MetricsManager";

    /**
     * Default prefix used when naming metrics
     */
    public static final String DEFAULT_METRICS_PREFIX = "emissary";

    /**
     * Matches simple duration specifications like 10s, 5m, 1h, 2d
     */
    private static final Pattern SIMPLE_DURATION = Pattern.compile("^(\\d+)\\s*([smhd])$");

    @SuppressWarnings("rawtypes")
    public static final SortedMap<String, Gauge> EMPTY_GUAGES = new TreeMap<>();
    public static final SortedMap<String, Counter> EMPTY_COUNTERS = new TreeMap<>();
    public static final SortedMap<String, Histogram> EMPTY_HISTOGRAMS = new TreeMap<>();
    public static final SortedMap<String, Meter> EMPTY_METERS = new TreeMap<>();

    protected static final Logger logger = LoggerFactory.getLogger(MetricsManager.class);

    protected final MetricRegistry metrics = new MetricRegistry();
    protected final HealthCheckRegistry healthChecks = new HealthCheckRegistry();

    /**
     * Micrometer registry that meters are recorded into. Registries are only added to it when explicitly enabled in the
     * configuration, so an unconfigured composite exports nothing.
     */
    protected final CompositeMeterRegistry meters = new CompositeMeterRegistry();

    /**
     * The prometheus registry, when prometheus metrics are enabled
     */
    protected PrometheusMeterRegistry prometheusRegistry;

    /**
     * Prefix to use when naming metrics
     */
    protected String metricsPrefix = DEFAULT_METRICS_PREFIX;

    /**
     * Tags applied to every registry
     */
    protected Tags commonTags = Tags.empty();

    protected final Slf4jReporter reporter;
    protected Configurator conf;

    /**
     * Lookup the default ResourceWatcher in the Namespace
     */
    public static MetricsManager lookup() throws NamespaceException {
        return (MetricsManager) Namespace.lookup(DEFAULT_NAMESPACE_NAME);
    }

    public MetricsManager() {
        configure();
        init();
        this.reporter = Slf4jReporter.forRegistry(this.metrics).build();
    }

    /**
     * Create manager using specified configuration
     * 
     * @param conf the configuration to use
     */
    public MetricsManager(final Configurator conf) {
        this.conf = conf;
        init();
        this.reporter = Slf4jReporter.forRegistry(this.metrics).build();
    }

    public void logMetrics(final Map<String, Timer> stats) {
        final SortedMap<String, Timer> m = new TreeMap<>();
        m.putAll(stats);
        this.reporter.report(EMPTY_GUAGES, EMPTY_COUNTERS, EMPTY_HISTOGRAMS, EMPTY_METERS, m);
    }

    public MetricRegistry getMetricRegistry() {
        return this.metrics;
    }

    /**
     * Get the micrometer registry that meters should be recorded into
     *
     * @return the micrometer registry that meters should be recorded into
     */
    public CompositeMeterRegistry getMeterRegistry() {
        return this.meters;
    }

    /**
     * Get the prometheus registry when prometheus metrics are enabled
     *
     * @return the prometheus registry when prometheus metrics are enabled, otherwise null
     */
    @Nullable
    public PrometheusMeterRegistry getPrometheusRegistry() {
        return this.prometheusRegistry;
    }

    /**
     * Get the prefix used when naming metrics
     *
     * @return the prefix used when naming metrics
     */
    public String getMetricsPrefix() {
        return this.metricsPrefix;
    }

    public HealthCheckRegistry getHealthCheckRegistry() {
        return this.healthChecks;
    }

    protected void configure() {
        try {
            this.conf = ConfigUtil.getConfigInfo(MetricsManager.class);
        } catch (IOException e) {
            logger.warn("Cannot read MetricsManager.cfg, taking default values");
        }
    }

    protected void init() {
        Namespace.bind(DEFAULT_NAMESPACE_NAME, this);

        if (this.conf == null) {
            logger.warn("Configuration is null, skipping init()");
        }

        initMetrics();
        initMicrometer();
        initHealthChecks();
        initJmxReporter();
        initGraphiteReporter();
        initSlf4jReporter();
    }

    protected void initHealthChecks() {
        this.healthChecks.register(
                "healthcheck.filequeue",
                new FilePickUpPlaceHealthCheck(this.conf.findIntEntry("MAX_FILE_COUNT_BEFORE_UNHEALTHY", Integer.MAX_VALUE), this.conf.findLongEntry(
                        "MAX_AGGREGATE_FIZE_SIZE_BEFORE_UNHEALTHY_BYTES", Long.MAX_VALUE)));
        this.healthChecks.register("healthcheck.threaddeadlocks", new ThreadDeadlockHealthCheck());
        this.healthChecks.register("healthcheck.agentpool", new AgentPoolHealthCheck());
    }

    protected void initMetrics() {
        if (this.conf.findBooleanEntry("JVM_METRICS_ENABLED", false)) {
            logger.debug("JVM Metrics are enabled");
            this.metrics.registerAll(new MemoryUsageGaugeSet());
            this.metrics.registerAll(new GarbageCollectorMetricSet());
            this.metrics.registerAll(new ThreadStatesGaugeSet());
            this.metrics.register("file.descriptor.info", new FileDescriptorRatioGauge());
        } else {
            logger.debug("JVM Metrics are disabled");
        }
    }

    /**
     * Wire up the micrometer registries that meters may be recorded into. Nothing is registered unless explicitly enabled,
     * so a default configuration exports nothing.
     *
     * Note that this is called from the constructor, so an override must not depend on subclass state that is not yet
     * initialized.
     */
    protected void initMicrometer() {
        this.metricsPrefix = this.conf.findStringEntry("METRICS_PREFIX", DEFAULT_METRICS_PREFIX);
        this.commonTags = parseCommonTags();

        // tag the composite so that meters recorded directly into it carry the common tags
        applyCommonTags(this.meters);

        initPrometheusRegistry();
        initMicrometerJmxRegistry();
        initMicrometerGraphiteRegistry();
    }

    /**
     * Initialize the prometheus registry
     */
    protected void initPrometheusRegistry() {
        if (!this.conf.findBooleanEntry("PROMETHEUS_METRICS_ENABLED", false)) {
            logger.debug("Prometheus Metrics are disabled");
            return;
        }

        logger.debug("Prometheus Metrics are enabled");

        final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        this.prometheusRegistry = registry;
        addMeterRegistry(registry);
    }

    /**
     * Initialize the micrometer jmx registry
     */
    protected void initMicrometerJmxRegistry() {
        if (!this.conf.findBooleanEntry("MICROMETER_JMX_ENABLED", false)) {
            logger.debug("Micrometer JMX Metrics are disabled");
            return;
        }

        logger.debug("Micrometer JMX Metrics are enabled");

        final JmxMeterRegistry registry = new JmxMeterRegistry(JmxConfig.DEFAULT, Clock.SYSTEM);
        addMeterRegistry(registry);
    }

    /**
     * Initialize the micrometer graphite registry
     */
    protected void initMicrometerGraphiteRegistry() {
        if (!this.conf.findBooleanEntry("MICROMETER_GRAPHITE_ENABLED", false)) {
            logger.debug("Micrometer Graphite Metrics are disabled");
            return;
        }

        logger.debug("Micrometer Graphite Metrics are enabled");

        final String host = this.conf.findStringEntry("MICROMETER_GRAPHITE_HOST", "localhost");
        final int port = this.conf.findIntEntry("MICROMETER_GRAPHITE_PORT", 2003);
        final Duration step = parseDuration(this.conf.findStringEntry("MICROMETER_GRAPHITE_STEP", "1m"));
        final GraphiteProtocol protocol = parseGraphiteProtocol(this.conf.findStringEntry("MICROMETER_GRAPHITE_PROTOCOL", "plaintext"));

        if (step.getSeconds() < 1) {
            throw new IllegalArgumentException("MICROMETER_GRAPHITE_STEP must be at least one second, was " + step);
        }

        final GraphiteConfig config = new GraphiteConfig() {
            @Override
            @Nullable
            public String get(final String key) {
                return null;
            }

            @Override
            public String host() {
                return host;
            }

            @Override
            public int port() {
                return port;
            }

            @Override
            public Duration step() {
                return step;
            }

            @Override
            public GraphiteProtocol protocol() {
                return protocol;
            }
        };
        final GraphiteMeterRegistry registry = new GraphiteMeterRegistry(config, Clock.SYSTEM);
        addMeterRegistry(registry);
    }

    /**
     * Add a registry to the composite registry that meters are recorded into
     *
     * @param registry the registry to add
     */
    protected void addMeterRegistry(final MeterRegistry registry) {
        applyCommonTags(registry);
        this.meters.add(registry);
    }

    /**
     * Parse the repeatable COMMON_TAGS configuration entries, each of the form key=value
     *
     * @return the tags to apply to every registry, empty when none are configured
     */
    protected Tags parseCommonTags() {
        Tags tags = Tags.empty();

        for (final String entry : this.conf.findEntries("COMMON_TAGS")) {
            final int index = entry.indexOf('=');

            if (index <= 0) {
                logger.warn("Ignoring malformed COMMON_TAGS entry: {}", entry);
                continue;
            }

            tags = tags.and(entry.substring(0, index).trim(), entry.substring(index + 1).trim());
        }

        return tags;
    }

    /**
     * Apply the common tags to a registry
     *
     * @param registry the registry to tag
     */
    protected void applyCommonTags(final MeterRegistry registry) {
        for (final Tag tag : this.commonTags) {
            logger.debug("Applying common tag {}={}", tag.getKey(), tag.getValue());
        }
        registry.config().commonTags(this.commonTags);
    }

    /**
     * Parse a duration expressed either in simple form (10s, 5m, 1h, 2d) or in ISO-8601 form
     *
     * @param value the duration to parse
     * @return the parsed duration
     */
    protected Duration parseDuration(final String value) {
        final Matcher matcher = SIMPLE_DURATION.matcher(value.trim());

        if (matcher.matches()) {
            final long amount = Long.parseLong(matcher.group(1));
            switch (matcher.group(2)) {
                case "s":
                    return Duration.ofSeconds(amount);
                case "m":
                    return Duration.ofMinutes(amount);
                case "h":
                    return Duration.ofHours(amount);
                case "d":
                    return Duration.ofDays(amount);
                default:
                    break;
            }
        }

        try {
            return Duration.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid duration '" + value + "', expected a value like 30s, 5m, 1h, 2d or an ISO-8601 duration", e);
        }
    }

    /**
     * Parse the graphite protocol to use, which must be one of plaintext, udp or pickled
     *
     * @param value the protocol name
     * @return the parsed protocol
     */
    protected GraphiteProtocol parseGraphiteProtocol(final String value) {
        try {
            return GraphiteProtocol.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid graphite protocol '" + value + "', expected one of plaintext, udp or pickled", e);
        }
    }

    protected void initJmxReporter() {
        if (!this.conf.findBooleanEntry("JMX_METRICS_ENABLED", false)) {
            logger.debug("JMX Metrics are disabled");
            return;
        }

        logger.debug("JMX Metrics are enabled");

        final String domain = this.conf.findStringEntry("JMX_METRICS_DOMAIN", "emissary-metrics");
        final TimeUnit rateUnit = TimeUnit.valueOf(this.conf.findStringEntry("JMX_METRICS_RATE_UNIT", TimeUnit.SECONDS.name()));
        final TimeUnit durationUnit = TimeUnit.valueOf(this.conf.findStringEntry("JMX_METRICS_DURATION_UNIT", TimeUnit.MILLISECONDS.name()));


        final JmxReporter jmxReporter =
                JmxReporter.forRegistry(this.metrics).inDomain(domain).convertRatesTo(rateUnit).convertDurationsTo(durationUnit).build();

        jmxReporter.start();
    }

    protected void initSlf4jReporter() {
        if (!this.conf.findBooleanEntry("SLF4J_METRICS_ENABLED", false)) {
            logger.debug("Slf4j Metrics are disabled");
            return;
        }

        logger.debug("Slf4j Metrics are enabled");

        final String loggerStr = this.conf.findStringEntry("SLF4J_METRICS_LOGGER", "emissary.core.metrics");
        final int interval = this.conf.findIntEntry("SL4J_METRICS_INTERVAL", -1);

        final TimeUnit intervalUnit = TimeUnit.valueOf(this.conf.findStringEntry("SL4J_METRICS_INTERVAL_UNIT", TimeUnit.MINUTES.name()));
        final TimeUnit rateUnit = TimeUnit.valueOf(this.conf.findStringEntry("SLF4J_METRICS_RATE_UNIT", TimeUnit.SECONDS.name()));
        final TimeUnit durationUnit = TimeUnit.valueOf(this.conf.findStringEntry("SLF4J_METRICS_DURATION_UNIT", TimeUnit.MILLISECONDS.name()));

        final Slf4jReporter slf4jReporter =
                Slf4jReporter.forRegistry(this.metrics).outputTo(LoggerFactory.getLogger(loggerStr)).convertRatesTo(rateUnit)
                        .convertDurationsTo(durationUnit).build();

        if (interval > 0) {
            slf4jReporter.start(interval, intervalUnit);
        }
    }

    protected void initGraphiteReporter() {
        if (!this.conf.findBooleanEntry("GRAPHITE_METRICS_ENABLED", false)) {
            logger.debug("Graphite Metrics are disabled");
            return;
        }

        logger.debug("Graphite Metrics are enabled");

        final String prefix = this.conf.findStringEntry("GRAPHITE_METRICS_PREFIX", "emissary");
        final String host = this.conf.findStringEntry("GRAPHITE_METRICS_HOST", "localhost");
        final int port = this.conf.findIntEntry("GRAPHITE_METRICS_PORT", 2003);
        final int interval = this.conf.findIntEntry("GRAPHITE_METRICS_INTERVAL", -1);

        final TimeUnit intervalUnit = TimeUnit.valueOf(this.conf.findStringEntry("GRAPHITE_METRICS_INTERVAL_UNIT", TimeUnit.MINUTES.name()));
        final TimeUnit rateUnit = TimeUnit.valueOf(this.conf.findStringEntry("GRAPHITE_RATE_UNIT", TimeUnit.SECONDS.name()));
        final TimeUnit durationUnit = TimeUnit.valueOf(this.conf.findStringEntry("GRAPHITE_DURATION_UNIT", TimeUnit.MILLISECONDS.name()));

        final Graphite graphite = new Graphite(new InetSocketAddress(host, port));
        final GraphiteReporter graphiteReporter =
                GraphiteReporter.forRegistry(this.metrics).prefixedWith(prefix).convertRatesTo(rateUnit).convertDurationsTo(durationUnit)
                        .filter(MetricFilter.ALL).build(graphite);

        if (interval > 0) {
            graphiteReporter.start(interval, intervalUnit);
        }
    }

    public void shutdown() {
        this.healthChecks.shutdown();
        this.meters.close();
        this.metrics.getMetrics().keySet().forEach(metrics::remove);
        this.metrics.getTimers().keySet().forEach(metrics::remove);
        this.metrics.getCounters().keySet().forEach(metrics::remove);
        this.metrics.getGauges().keySet().forEach(metrics::remove);
        this.metrics.getHistograms().keySet().forEach(metrics::remove);
        this.metrics.getMeters().keySet().forEach(metrics::remove);
        Namespace.unbind(DEFAULT_NAMESPACE_NAME);
    }
}
