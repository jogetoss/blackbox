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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.joget.commons.util.DynamicDataSourceManager;
import org.joget.commons.util.HostManager;
import org.joget.commons.util.LogUtil;
import org.joget.commons.util.SetupManager;
import org.json.JSONException;
import org.json.JSONObject;

final class ServerCapture {

    static final String HEADER = "X-BlackBox-Id";
    static final String TIME_HEADER = "X-BlackBox-Time";
    static final String PROFILE = "profile";

    private static final long FLUSH_MS = 2000;
    private static final long IDLE_MS = 30 * 60 * 1000L;
    private static final long MISS_MS = 10 * 1000L;
    private static final long SWEEP_MS = 60 * 60 * 1000L;
    private static final String NODE = nodeName();

    private static final ThreadLocal<Request> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> APPENDING = new ThreadLocal<>();
    private static final ThreadLocal<String> REQUEST_PROFILE = new ThreadLocal<>();
    private static final Map<String, Capture> OPEN = new ConcurrentHashMap<>();
    private static final Map<String, Long> MISSES = new ConcurrentHashMap<>();
    private static volatile Capture[] allServer = new Capture[0];

    private static volatile boolean running;
    private static ScheduledExecutorService timer;
    private static RootAppender appender;

    private ServerCapture() {
    }

    static java.util.function.Supplier<ServerCaptureStore> stores = () -> new ServerCaptureStore(ProfileDir.current().resolve("pending"));

    static ServerCaptureStore store() {
        return stores.get();
    }

    static Supplier<List<Path>> checkDirs = () -> blackboxDirs(HostManager.isVirtualHostEnabled(),
            SetupManager.getBaseDirectory(), SetupManager.getBaseSharedDirectory());

    static List<Path> blackboxDirs(boolean virtualHosts, String base, String shared) {
        if (!virtualHosts) {
            return Collections.singletonList(Paths.get(base, "blackbox"));
        }
        List<Path> dirs = new ArrayList<>();
        try (java.nio.file.DirectoryStream<Path> profiles = Files.newDirectoryStream(Paths.get(shared, SetupManager.DIRECTORY_PROFILES))) {
            for (Path p : profiles) {
                if (Files.isDirectory(p)) {
                    dirs.add(p.resolve("blackbox"));
                }
            }
        } catch (IOException e) {
            LogUtil.warn(ServerCapture.class.getName(), "Could not list the profiles: " + e.getMessage());
        }
        return dirs;
    }

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
            Thread t = new Thread(r, "BlackBox server capture");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(ServerCapture::flushAll, FLUSH_MS, FLUSH_MS, TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(ServerCapture::sweepAll, 60 * 1000L, SWEEP_MS, TimeUnit.MILLISECONDS);
        scheduleCheck(timer);
        TraceCollector.start();
        GaugeSampler.start();
        GlowrootPackager.start();
        ExportJobs.start();
        running = true;
    }

    static synchronized void stop() {
        running = false;
        TraceCollector.stop();
        GaugeSampler.stop();
        GlowrootPackager.stop();
        ExportJobs.stop();
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
        allServer = new Capture[0];
        MISSES.clear();
    }

    static Capture find(String id) {
        if (!running || !ServerCaptureStore.isValidId(id)) {
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
        ServerCaptureStore store = store();
        JSONObject meta = store.readMeta(id);
        if (meta == null || meta.has("stoppedAt") || meta.optLong("expiresAt") < now) {
            MISSES.put(id, now);
            return null;
        }
        MISSES.remove(id);
        Capture opened = OPEN.computeIfAbsent(id, k -> new Capture(id, store.nodeFile(id, NODE), meta));
        updateAllServer();
        GaugeSampler.watch(opened);
        GlowrootPackager.watch(opened);
        TraceCollector.watchOthers(opened);
        return opened;
    }

    static void close(String id) {
        Capture capture = OPEN.remove(id);
        if (capture != null) {
            capture.close();
            updateAllServer();
        }
        MISSES.put(id, System.currentTimeMillis());
    }

    private static synchronized void updateAllServer() {
        allServer = OPEN.values().stream().filter(c -> c.allServer).toArray(Capture[]::new);
    }

    static String enterRequest() {
        String prev = REQUEST_PROFILE.get();
        if (BlackBoxPlugin.virtualHosts()) {
            String profile = null;
            try {
                profile = requestProfiles.get();
            } catch (RuntimeException e) {
            }
            if (profile == null) {
                REQUEST_PROFILE.remove();
            } else {
                REQUEST_PROFILE.set(profile);
            }
        }
        return prev;
    }

    static Supplier<String> requestProfiles = DynamicDataSourceManager::getCurrentProfile;

    static String requestProfile() {
        return REQUEST_PROFILE.get();
    }

    static void leaveRequest(String prev) {
        if (prev == null) {
            REQUEST_PROFILE.remove();
        } else {
            REQUEST_PROFILE.set(prev);
        }
    }

    static Object begin(Capture capture, String request) {
        Request prev = CURRENT.get();
        CURRENT.set(new Request(capture, request));
        return prev;
    }

    static boolean loggedError() {
        Request r = CURRENT.get();
        return r != null && r.loggedError;
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
            a.ensureAttached();
        }
        for (Map.Entry<String, Capture> e : OPEN.entrySet()) {
            Capture c = e.getValue();
            c.flush();
            if (now - c.lastWrite > IDLE_MS && OPEN.remove(e.getKey(), c)) {
                c.close();
                updateAllServer();
            }
        }
        MISSES.entrySet().removeIf(e -> now - e.getValue() > MISS_MS);
    }

    private static void scheduleCheck(ScheduledExecutorService t) {
        long delay;
        try {
            delay = checkMs(checkDirs.get());
        } catch (Exception e) {
            delay = LivenessKnobs.CHECK_MS;
        }
        try {
            t.schedule(() -> {
                try {
                    checkLost(System.currentTimeMillis());
                } catch (Exception e) {
                    LogUtil.warn(ServerCapture.class.getName(), "Could not check server captures for a lost browser: " + e.getMessage());
                }
                scheduleCheck(t);
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
        }
    }

    static long checkMs(List<Path> dirs) {
        long ms = LivenessKnobs.CHECK_MS;
        for (Path dir : dirs) {
            ms = Math.min(ms, LivenessKnobs.checkMs(dir.resolve(LivenessKnobs.FILE)));
        }
        return ms;
    }

    static void checkLost(long now) {
        for (Path dir : checkDirs.get()) {
            checkLost(now, new ServerCaptureStore(dir.resolve("pending")), LivenessKnobs.lostMs(dir.resolve(LivenessKnobs.FILE)));
        }
    }

    static void checkLost(long now, ServerCaptureStore store, long lostMs) {
        Set<String> ids = new HashSet<>();
        for (Capture c : OPEN.values()) {
            if (c.file.startsWith(store.root())) {
                ids.add(c.id);
            }
        }
        try {
            ids.addAll(store.ids());
        } catch (IOException e) {
            LogUtil.warn(ServerCapture.class.getName(), "Could not list server captures: " + e.getMessage());
        }
        for (String id : ids) {
            JSONObject meta = store.readMeta(id);
            if (meta == null) {
                continue;
            }
            if (!meta.has("stoppedAt")) {
                try {
                    if (store.stopIfLost(id, now, lostMs)) {
                        LogUtil.info(ServerCapture.class.getName(), "Stopped server capture " + id + ": no ping from the browser for " + lostMs / 1000 + " s");
                    } else if (store.stopIfOverLimit(id, now)) {
                        LogUtil.info(ServerCapture.class.getName(), "Stopped server capture " + id + ": past its time limit");
                    } else {
                        continue;
                    }
                } catch (IOException e) {
                    LogUtil.warn(ServerCapture.class.getName(), "Could not stop server capture " + id + ": " + e.getMessage());
                    continue;
                }
            }
            if (OPEN.containsKey(id)) {
                close(id);
            }
        }
    }

    private static void sweepAll() {
        try {
            for (Path dir : checkDirs.get()) {
                sweep(new ServerCaptureStore(dir.resolve("pending")));
            }
        } catch (Exception e) {
            LogUtil.warn(ServerCapture.class.getName(), "Could not clean up server captures: " + e.getMessage());
        }
    }

    private static void sweep(ServerCaptureStore store) {
        try {
            int n = store.sweep(System.currentTimeMillis());
            if (n > 0) {
                LogUtil.info(ServerCapture.class.getName(), "Deleted " + n + " server capture(s) that were never uploaded");
            }
        } catch (Exception e) {
            LogUtil.warn(ServerCapture.class.getName(), "Could not clean up server captures: " + e.getMessage());
        }
    }

    static String nodeName() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = null;
        }
        return nodeName(org.joget.commons.util.ServerUtil.getServerName(), host);
    }

    static String nodeName(String jogetName, String host) {
        String name = jogetName != null && !jogetName.isEmpty() ? jogetName : host != null ? host : "";
        name = name.replaceAll("[^A-Za-z0-9.-]", "-");
        return name.isEmpty() ? "node" : name;
    }

    private static final class Request {
        final Capture capture;
        final String request;
        boolean loggedError;

        Request(Capture capture, String request) {
            this.capture = capture;
            this.request = request;
        }
    }

    static boolean sameProfile(String capture, String here) {
        return capture != null && !capture.isEmpty() && capture.equals(here);
    }

    static final class Capture {
        final String id;
        final boolean traces;
        final String glowrootUrl;
        final boolean gauges;
        final boolean glowrootData;
        final boolean allServer;
        final boolean otherTraces;
        final String profile;
        private final Path file;
        private final long maxBytes;
        private Writer out;
        private long bytes;
        private boolean full;
        private boolean closed;
        volatile long lastWrite = System.currentTimeMillis();

        Capture(String id, Path file, JSONObject meta) {
            this.id = id;
            this.traces = meta.optBoolean("traces");
            this.glowrootUrl = meta.optString("glowrootUrl");
            this.gauges = meta.optBoolean("gauges");
            this.glowrootData = meta.optBoolean("glowrootData");
            this.allServer = meta.optBoolean("allServer");
            this.otherTraces = meta.optBoolean("otherTraces");
            this.profile = meta.optString(PROFILE, null);
            this.file = file;
            this.maxBytes = meta.optLong("maxBytes");
            try {
                bytes = Files.exists(file) ? Files.size(file) : 0;
            } catch (IOException e) {
                bytes = 0;
            }
        }

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
            }
            out = null;
        }
    }

    private static final class RootAppender implements InvocationHandler {
        private static final String NAME = "BlackBoxSystemLog";

        private final Object context;
        private final Object proxy;
        private final Object startedState;
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
            }
        }

        synchronized void detach() {
            try {
                Object root = getRootLogger.invoke(getConfiguration.invoke(context));
                removeAppender.invoke(root, NAME);
                updateLoggers.invoke(context);
            } catch (ReflectiveOperationException e) {
                LogUtil.warn(ServerCapture.class.getName(), "Could not remove the BlackBox appender: " + e);
            }
        }

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
                    return null;
            }
        }

        private void append(Object event) {
            Request r = CURRENT.get();
            Capture[] all = allServer;
            if ((r == null && all.length == 0) || APPENDING.get() != null) {
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
                        .put("message", msg != null ? formattedMessage.invoke(msg) : "");
                Throwable t = (Throwable) thrown.invoke(event);
                if (t != null) {
                    StringWriter trace = new StringWriter();
                    t.printStackTrace(new PrintWriter(trace));
                    e.put("thrown", trace.toString());
                }
                if (r != null) {
                    r.capture.write(new JSONObject(e.toString()).put("req", r.request));
                    String lvl = e.getString("level");
                    if (!r.loggedError && ("ERROR".equals(lvl) || "FATAL".equals(lvl))) {
                        r.loggedError = true;
                        GlowrootTag.keep();
                    }
                }
                boolean virtualHosts = all.length > 0 && BlackBoxPlugin.virtualHosts();
                String here = virtualHosts ? requestProfile() : null;
                for (Capture c : all) {
                    if ((r == null || c != r.capture) && (!virtualHosts || sameProfile(c.profile, here))) {
                        c.write(new JSONObject(e.toString()).put("other", true));
                    }
                }
            } catch (Throwable t) {
            } finally {
                APPENDING.remove();
            }
        }
    }
}
