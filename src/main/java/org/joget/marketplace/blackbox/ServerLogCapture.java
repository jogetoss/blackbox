package org.joget.marketplace.blackbox;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.joget.commons.util.LogUtil;
import org.joget.commons.util.SetupManager;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Captures the log events of requests the extension tags with X-BlackBox-Id while a recording runs.
 *
 * {@link ServerLogFilter} marks the request thread; an appender on the root logger keeps only the events of marked
 * threads and appends them to the capture's pending file ({@link ServerLogStore}).
 *
 * Log4j 2 is not visible to plugin bundles (OSGi exports neither org.apache.logging.log4j nor org.apache.log4j), so
 * {@link RootAppender} reaches it through Joget's own classloader by reflection, and the appender is a
 * java.lang.reflect.Proxy of log4j's Appender interface.
 *
 * Not captured: work a request hands to other threads, and log levels below the root logger's level (info).
 */
final class ServerLogCapture {

    static final String HEADER = "X-BlackBox-Id";
    static final String TIME_HEADER = "X-BlackBox-Time";

    private static final long FLUSH_MS = 2000;
    private static final long IDLE_MS = 30 * 60 * 1000L; // close a capture's file after this long without events
    private static final long MISS_MS = 10 * 1000L;      // remember unknown ids this long, to skip the disk check
    private static final long SWEEP_MS = 60 * 60 * 1000L;
    private static final String NODE = nodeName();

    private static final ThreadLocal<Request> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> APPENDING = new ThreadLocal<>();
    private static final Map<String, Capture> OPEN = new ConcurrentHashMap<>();
    private static final Map<String, Long> MISSES = new ConcurrentHashMap<>();

    private static volatile boolean running;
    private static ScheduledExecutorService timer;
    private static RootAppender appender;

    private ServerLogCapture() {
    }

    static ServerLogStore store() {
        return new ServerLogStore(Paths.get(SetupManager.getBaseDirectory(), "blackbox", "pending"));
    }

    /** Called by the bundle activator. */
    static synchronized void start() {
        if (running) {
            return;
        }
        try {
            appender = new RootAppender(LogUtil.class.getClassLoader());
            appender.attach();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not add the BlackBox appender to log4j", e);
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BlackBox server log");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(ServerLogCapture::flushAll, FLUSH_MS, FLUSH_MS, TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(ServerLogCapture::sweep, 60 * 1000L, SWEEP_MS, TimeUnit.MILLISECONDS);
        running = true;
    }

    /** Called by the bundle activator. The filter may still run after this (cached chain); it then does nothing. */
    static synchronized void stop() {
        running = false;
        if (appender != null) {
            appender.detach();
            appender = null;
        }
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
        for (String id : OPEN.keySet()) {
            close(id);
        }
        MISSES.clear();
    }

    /** The open capture for a tagged request, or null when the id is unknown, stopped or expired. */
    static Capture find(String id) {
        if (!running || !ServerLogStore.isValidId(id)) {
            return null;
        }
        Capture capture = OPEN.get(id);
        if (capture != null) {
            return capture;
        }
        long now = System.currentTimeMillis();
        Long missedAt = MISSES.get(id);
        if (missedAt != null && now - missedAt < MISS_MS) {
            return null;
        }
        // Started on this node or another one (shared wflow folder)
        ServerLogStore store = store();
        JSONObject meta = store.readMeta(id);
        if (meta == null || meta.has("stoppedAt") || meta.optLong("expiresAt") < now) {
            MISSES.put(id, now);
            return null;
        }
        MISSES.remove(id);
        return OPEN.computeIfAbsent(id, k -> new Capture(store.nodeFile(id, NODE), meta.optLong("maxBytes")));
    }

    /** Closes this node's file of a capture (stop or upload). Later requests with the id are no longer captured. */
    static void close(String id) {
        Capture capture = OPEN.remove(id);
        if (capture != null) {
            capture.close();
        }
        MISSES.put(id, System.currentTimeMillis());
    }

    /** Marks the current thread as serving a tagged request. Returns what to pass to {@link #end}. */
    static Object begin(Capture capture, String request) {
        Request prev = CURRENT.get();
        CURRENT.set(new Request(capture, request));
        return prev;
    }

    static void end(Object prev) {
        if (prev == null) {
            CURRENT.remove();
        } else {
            CURRENT.set((Request) prev);
        }
    }

    private static void flushAll() {
        long now = System.currentTimeMillis();
        RootAppender a = appender;
        if (a != null && !OPEN.isEmpty()) {
            a.ensureAttached(); // a log4j reconfiguration replaces the root logger config and drops it
        }
        for (Map.Entry<String, Capture> e : OPEN.entrySet()) {
            Capture c = e.getValue();
            c.flush();
            if (now - c.lastWrite > IDLE_MS && OPEN.remove(e.getKey(), c)) {
                c.close();
            }
        }
        MISSES.entrySet().removeIf(e -> now - e.getValue() > MISS_MS);
    }

    private static void sweep() {
        try {
            int n = store().sweep(System.currentTimeMillis());
            if (n > 0) {
                LogUtil.info(ServerLogCapture.class.getName(), "Deleted " + n + " server log capture(s) that were never uploaded");
            }
        } catch (Exception e) {
            LogUtil.warn(ServerLogCapture.class.getName(), "Could not clean up server log captures: " + e.getMessage());
        }
    }

    private static String nodeName() {
        String name;
        try {
            name = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            name = "node";
        }
        name = name.replaceAll("[^A-Za-z0-9.-]", "-");
        return name.isEmpty() ? "node" : name;
    }

    private static final class Request {
        final Capture capture;
        final String request; // "GET /jw/web/..." for the log lines

        Request(Capture capture, String request) {
            this.capture = capture;
            this.request = request;
        }
    }

    /** One capture's file on this node. Lines are JSON objects; the file is closed when idle, stopped or attached. */
    static final class Capture {
        private final Path file;
        private final long maxBytes;
        private Writer out;
        private long bytes;
        private boolean full;
        private boolean closed;
        volatile long lastWrite = System.currentTimeMillis();

        Capture(Path file, long maxBytes) {
            this.file = file;
            this.maxBytes = maxBytes;
            try {
                bytes = Files.exists(file) ? Files.size(file) : 0; // this node may have written before (reopened after idle)
            } catch (IOException e) {
                bytes = 0;
            }
        }

        /** The tagged request has finished. */
        void request(long ts, String method, String url, int status, long ms, Throwable failure) {
            try {
                JSONObject e = new JSONObject()
                        .put("ts", ts)
                        .put("kind", "request")
                        .put("method", method)
                        .put("url", url)
                        .put("status", status)
                        .put("ms", ms);
                if (failure != null) {
                    e.put("error", failure.toString());
                }
                write(e);
            } catch (JSONException e) {
                // not reachable: keys and values are plain
            }
        }

        synchronized void write(JSONObject entry) {
            if (closed || full) {
                return;
            }
            try {
                String line = entry.toString() + "\n";
                int size = line.getBytes(StandardCharsets.UTF_8).length;
                if (bytes + size > maxBytes) {
                    full = true;
                    line = new JSONObject().put("kind", "truncated").put("ts", System.currentTimeMillis()) + "\n";
                }
                if (out == null) {
                    out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
                out.write(line);
                bytes += size;
                lastWrite = System.currentTimeMillis();
            } catch (IOException | JSONException e) {
                // The pending folder is gone (attached or swept) or the disk is full: stop writing.
                // Not logged: this runs inside the logging call of the request.
                full = true;
            }
        }

        synchronized void flush() {
            try {
                if (out != null) {
                    out.flush();
                }
            } catch (IOException e) {
                full = true;
            }
        }

        synchronized void close() {
            closed = true;
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException e) {
                // nothing more to write
            }
            out = null;
        }
    }

    /**
     * Keeps the events of threads marked by {@link #begin}. Added to log4j's root logger config, so it sees what
     * joget.log sees. Not synchronized: most events are from unmarked threads and return at once.
     */
    private static final class RootAppender implements InvocationHandler {
        private static final String NAME = "BlackBoxServerLog";

        private final Object context;      // org.apache.logging.log4j.core.LoggerContext of the Joget webapp
        private final Object proxy;        // implements org.apache.logging.log4j.core.Appender
        private final Object startedState; // LifeCycle.State.STARTED
        private final Method getConfiguration;
        private final Method getRootLogger;
        private final Method addAppender;
        private final Method removeAppender;
        private final Method getAppenders;
        private final Method updateLoggers;
        private final Method timeMillis;
        private final Method level;
        private final Method loggerName;
        private final Method threadName;
        private final Method message;
        private final Method formattedMessage;
        private final Method thrown;

        @SuppressWarnings({"unchecked", "rawtypes"})
        RootAppender(ClassLoader cl) throws ReflectiveOperationException {
            Class<?> logManager = Class.forName("org.apache.logging.log4j.LogManager", true, cl);
            Class<?> loggerContext = Class.forName("org.apache.logging.log4j.core.LoggerContext", true, cl);
            Class<?> configuration = Class.forName("org.apache.logging.log4j.core.config.Configuration", true, cl);
            Class<?> loggerConfig = Class.forName("org.apache.logging.log4j.core.config.LoggerConfig", true, cl);
            Class<?> appenderType = Class.forName("org.apache.logging.log4j.core.Appender", true, cl);
            Class<?> levelType = Class.forName("org.apache.logging.log4j.Level", true, cl);
            Class<?> filterType = Class.forName("org.apache.logging.log4j.core.Filter", true, cl);
            Class<?> logEvent = Class.forName("org.apache.logging.log4j.core.LogEvent", true, cl);
            Class<?> messageType = Class.forName("org.apache.logging.log4j.message.Message", true, cl);
            Class state = Class.forName("org.apache.logging.log4j.core.LifeCycle$State", true, cl);

            // The context of Joget's classloader, not a new one for this bundle
            context = logManager.getMethod("getContext", ClassLoader.class, boolean.class).invoke(null, cl, false);
            if (!loggerContext.isInstance(context)) {
                throw new ClassNotFoundException("log4j-core is not the log4j implementation");
            }
            getConfiguration = loggerContext.getMethod("getConfiguration");
            updateLoggers = loggerContext.getMethod("updateLoggers");
            getRootLogger = configuration.getMethod("getRootLogger");
            addAppender = loggerConfig.getMethod("addAppender", appenderType, levelType, filterType);
            removeAppender = loggerConfig.getMethod("removeAppender", String.class);
            getAppenders = loggerConfig.getMethod("getAppenders");
            timeMillis = logEvent.getMethod("getTimeMillis");
            level = logEvent.getMethod("getLevel");
            loggerName = logEvent.getMethod("getLoggerName");
            threadName = logEvent.getMethod("getThreadName");
            message = logEvent.getMethod("getMessage");
            formattedMessage = messageType.getMethod("getFormattedMessage");
            thrown = logEvent.getMethod("getThrown");
            startedState = Enum.valueOf(state, "STARTED");
            proxy = Proxy.newProxyInstance(cl, new Class<?>[]{appenderType}, this);
        }

        synchronized void attach() throws ReflectiveOperationException {
            Object root = getRootLogger.invoke(getConfiguration.invoke(context));
            addAppender.invoke(root, proxy, null, null);
            updateLoggers.invoke(context);
        }

        synchronized void ensureAttached() {
            try {
                Object root = getRootLogger.invoke(getConfiguration.invoke(context));
                if (!((Map<?, ?>) getAppenders.invoke(root)).containsKey(NAME)) {
                    addAppender.invoke(root, proxy, null, null);
                    updateLoggers.invoke(context);
                }
            } catch (ReflectiveOperationException e) {
                // try again on the next flush
            }
        }

        synchronized void detach() {
            try {
                Object root = getRootLogger.invoke(getConfiguration.invoke(context));
                removeAppender.invoke(root, NAME);
                updateLoggers.invoke(context);
            } catch (ReflectiveOperationException e) {
                LogUtil.warn(ServerLogCapture.class.getName(), "Could not remove the BlackBox appender: " + e);
            }
        }

        /** The Appender and LifeCycle methods log4j calls on the proxy. */
        @Override
        public Object invoke(Object self, Method method, Object[] args) {
            switch (method.getName()) {
                case "append":
                    append(args[0]);
                    return null;
                case "getName":
                    return NAME;
                case "getState":
                    return startedState;
                case "isStarted":
                case "ignoreExceptions":
                    return true;
                case "isStopped":
                    return false;
                case "stop":
                    return method.getReturnType() == boolean.class ? Boolean.TRUE : null;
                case "hashCode":
                    return System.identityHashCode(self);
                case "equals":
                    return self == args[0];
                case "toString":
                    return NAME;
                default:
                    return null; // getLayout, getHandler, setHandler, initialize, start
            }
        }

        private void append(Object event) {
            Request r = CURRENT.get();
            if (r == null || APPENDING.get() != null) {
                return;
            }
            APPENDING.set(Boolean.TRUE);
            try {
                Object msg = message.invoke(event);
                JSONObject e = new JSONObject()
                        .put("ts", timeMillis.invoke(event))
                        .put("kind", "log")
                        .put("level", String.valueOf(level.invoke(event)))
                        .put("logger", loggerName.invoke(event))
                        .put("thread", threadName.invoke(event))
                        .put("message", msg != null ? formattedMessage.invoke(msg) : "")
                        .put("req", r.request);
                Throwable t = (Throwable) thrown.invoke(event);
                if (t != null) {
                    StringWriter trace = new StringWriter();
                    t.printStackTrace(new PrintWriter(trace));
                    e.put("thrown", trace.toString());
                }
                r.capture.write(e);
            } catch (Throwable t) {
                // never break the application's logging call
            } finally {
                APPENDING.remove();
            }
        }
    }
}
