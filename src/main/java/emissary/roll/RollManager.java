package emissary.roll;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import emissary.config.ConfigUtil;
import emissary.config.Configurator;
import java.util.ArrayList;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * RollManager handles all incremental rolls for configured objects within the framework
 */
public class RollManager implements Observer {
    static final Logger log = LoggerFactory.getLogger(RollManager.class);
    public static final String CFG_ROLL_MANAGER_THREADS = "ROLL_MANAGER_THREADS";
    int executorThreadCount = 10;
    ScheduledThreadPoolExecutor exec;

    final HashSet<Roller> rollers = new HashSet<>();
    // SINGLETON
    private static final RollManager RM = new RollManager();

    protected RollManager() {
        init();
    }

    /**
     * Load the configurator
     */
    protected void init() {
        ArrayList<Roller> cfgRollers = new ArrayList<>();
        try {
            Configurator configG = ConfigUtil.getConfigInfo(this.getClass());
            executorThreadCount = configG.findIntEntry(CFG_ROLL_MANAGER_THREADS, executorThreadCount);
            for (String roller : configG.findEntries("ROLLABLE")) {
                try {
                    Map<String, String> map = configG.findStringMatchMap(roller + "_");
                    cfgRollers.add(RollUtil.buildRoller(map));
                } catch (Exception e) {
                    log.warn("Unable to configure Rollable for: " + roller);
                }
            }
        } catch (IOException ex) {
            if (ex.getMessage().startsWith("No config stream available")) {
                log.info("No Rollables configured in the default configuration");
            } else {
                log.warn("Unable to configure RollManager from Configurator.", ex);
            }
        } finally {
            exec = new RollScheduledExecutor(executorThreadCount, new RMThreadFactory());
            for (Roller r : cfgRollers) {
                addRoller(r);
            }
        }
    }

    public final void addRoller(Roller r) {
        boolean time = r.getTimeUnit() != null && r.getPeriod() > 0L;
        boolean progress = r.getMax() > 0;
        if (time) {
            log.info("Scheduling Rollable " + r.getRollable().getClass() + " at " + r.getPeriod() + " " + r.getTimeUnit().name());
            exec.scheduleAtFixedRate(r, r.getPeriod(), r.getPeriod(), r.getTimeUnit());
        }
        if (progress) {
            r.addObserver(this);
        }
        if (time || progress) {
            rollers.add(r);
        } else {
            log.error("Roller not scheduled. Time or progress must be set: Class={} Max={} Interval={} {}", r.getClass().getName(), r.getMax(),
                    r.getPeriod(), r.getTimeUnit());
        }
    }

    @Override
    public void update(Observable o, Object arg) {
        if (rollers.contains(o)) {
            Roller r = (Roller) o;
            // only schedule one time when we're notified
            if (r.setProgressScheduled()) {
                exec.execute((Roller) o);
            }

        }
    }

    public static RollManager getManager() {
        return RM;
    }

    public static void shutdown() {
        RM.exec.shutdown();
        log.info("Closing all rollers (" + RM.rollers.size() + ")");
        for (Roller roller : RM.rollers) {
            Rollable r = roller.getRollable();
            try {
                r.roll();
                r.close();
            } catch (IOException ex) {
                log.warn("Error while closing Rollable: " + r.getClass(), ex);
            }
        }
    }

    private static final class RMThreadFactory implements ThreadFactory {
        final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "RollManager-daemon-" + count.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
