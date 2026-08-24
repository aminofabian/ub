package zelisline.ub.platform.loadtest;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;

import javax.sql.DataSource;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import zelisline.ub.platform.realtime.RealtimeTicketService;
import zelisline.ub.platform.realtime.SessionRegistry;

/**
 * Reads the instance's live capacity gauges for the Super Admin load-test
 * console: embedded Tomcat worker pool, Hikari connection pool, JVM heap/CPU,
 * active WebSocket sessions and the realtime ticket store.
 *
 * <p>All reads are defensive — an unreadable gauge degrades to zero/unknown
 * rather than failing the console page.
 */
@Component
public class LoadTestCapacityService {

    private static final Logger log = LoggerFactory.getLogger(LoadTestCapacityService.class);

    private final ApplicationContext applicationContext;
    private final DataSource dataSource;
    private final SessionRegistry sessionRegistry;
    private final RealtimeTicketService ticketService;
    private final JdbcTemplate jdbcTemplate;
    private final int wsMaxPerUser;
    private final int wsMaxPerBusiness;
    private final String selfTestBaseUrl;

    public LoadTestCapacityService(
            ApplicationContext applicationContext,
            DataSource dataSource,
            SessionRegistry sessionRegistry,
            RealtimeTicketService ticketService,
            @Autowired(required = false) JdbcTemplate jdbcTemplate,
            @Value("${app.realtime.max-connections-per-user:5}") int wsMaxPerUser,
            @Value("${app.realtime.max-connections-per-business:50}") int wsMaxPerBusiness,
            @Value("${app.load-test.base-url:http://127.0.0.1:${server.port:5050}}") String selfTestBaseUrl
    ) {
        this.applicationContext = applicationContext;
        this.dataSource = dataSource;
        this.sessionRegistry = sessionRegistry;
        this.ticketService = ticketService;
        this.jdbcTemplate = jdbcTemplate;
        this.wsMaxPerUser = wsMaxPerUser;
        this.wsMaxPerBusiness = wsMaxPerBusiness;
        this.selfTestBaseUrl = selfTestBaseUrl;
    }

    public LoadTestModels.CapacitySnapshot snapshot() {
        TomcatGauges tomcat = readTomcat();
        HikariGauges hikari = readHikari();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long heapUsedMb = bytesToMb(heap.getUsed());
        long heapMaxMb = heap.getMax() > 0 ? bytesToMb(heap.getMax()) : 0;
        double cpu = readProcessCpuLoad();
        long dbMs = measureDbRoundTrip();
        String ticketStore = ticketService.activeTicketStore();
        boolean redis = ticketService.isRedisConfigured();
        int wsActive = sessionRegistry.activeSessionCount();

        String hint = buildHint(ticketStore, redis, wsActive, tomcat, hikari, cpu, dbMs);

        return new LoadTestModels.CapacitySnapshot(
                ticketStore,
                redis,
                wsActive,
                wsMaxPerUser,
                wsMaxPerBusiness,
                tomcat.maxThreads,
                tomcat.activeThreads,
                tomcat.queued,
                tomcat.openConnections,
                hikari.maxPool,
                hikari.active,
                hikari.idle,
                hikari.awaiting,
                dbMs,
                heapUsedMb,
                heapMaxMb,
                cpu,
                selfTestBaseUrl,
                hint
        );
    }

    private String buildHint(
            String ticketStore,
            boolean redis,
            int wsActive,
            TomcatGauges tomcat,
            HikariGauges hikari,
            double cpu,
            long dbMs
    ) {
        if ("in-memory".equals(ticketStore) && !redis) {
            return "Ticket store is in-memory — safe only for a single API replica. Set REDIS_URL or apply V129 before scaling out.";
        }
        if (hikari.awaiting > 0) {
            return "Database pool is saturated — " + hikari.awaiting + " request(s) waiting for a connection.";
        }
        if (tomcat.queued > 0) {
            return "Requests are queueing behind the Tomcat worker pool (" + tomcat.queued + " queued).";
        }
        if (cpu > 85) {
            return "Process CPU is high (" + cpu + "%) — the instance is close to compute-bound.";
        }
        if (dbMs >= 100) {
            return "DB round-trip is slow (" + dbMs + " ms) — check slow queries before load testing.";
        }
        if (wsActive >= tomcat.maxThreads / 2 && tomcat.maxThreads > 0) {
            return "Many open WebSocket sessions on this instance — they hold Tomcat threads.";
        }
        return "Healthy baseline — run a load test to find the ceiling.";
    }

    private TomcatGauges readTomcat() {
        try {
            if (applicationContext instanceof ServletWebServerApplicationContext servletCtx
                    && servletCtx.getWebServer() instanceof TomcatWebServer tomcatWebServer) {
                Connector connector = tomcatWebServer.getTomcat().getConnector();
                if (connector != null && connector.getProtocolHandler() instanceof AbstractProtocol<?> protocol) {
                    int maxThreads = protocol.getMaxThreads();
                    long open = protocol.getConnectionCount();
                    int active = 0;
                    int queued = 0;
                    if (protocol.getExecutor() instanceof ThreadPoolExecutor executor) {
                        active = executor.getActiveCount();
                        queued = executor.getQueue().size();
                    }
                    return new TomcatGauges(maxThreads, active, queued, open);
                }
            }
        } catch (Exception ex) {
            log.debug("Could not read Tomcat gauges: {}", ex.getMessage());
        }
        return new TomcatGauges(0, 0, 0, 0);
    }

    private HikariGauges readHikari() {
        try {
            if (dataSource instanceof HikariDataSource hikari) {
                HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
                return new HikariGauges(
                        hikari.getMaximumPoolSize(),
                        pool.getActiveConnections(),
                        pool.getIdleConnections(),
                        pool.getThreadsAwaitingConnection()
                );
            }
        } catch (Exception ex) {
            log.debug("Could not read Hikari gauges: {}", ex.getMessage());
        }
        return new HikariGauges(0, 0, 0, 0);
    }

    private double readProcessCpuLoad() {
        try {
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
                double load = sunOs.getProcessCpuLoad();
                return load < 0 ? 0 : Math.round(load * 1000) / 10.0;
            }
        } catch (Exception ex) {
            log.debug("Could not read process CPU load: {}", ex.getMessage());
        }
        return 0;
    }

    private long measureDbRoundTrip() {
        if (jdbcTemplate == null) {
            return -1;
        }
        try {
            long start = System.nanoTime();
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return (System.nanoTime() - start) / 1_000_000;
        } catch (Exception ex) {
            return -1;
        }
    }

    private static long bytesToMb(long bytes) {
        return bytes > 0 ? Math.round(bytes / 1024.0 / 1024.0) : 0;
    }

    private record TomcatGauges(int maxThreads, int activeThreads, int queued, long openConnections) {
    }

    private record HikariGauges(int maxPool, int active, int idle, int awaiting) {
    }
}
