package org.joget.marketplace.blackbox;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;
import java.util.TreeMap;
import javax.servlet.http.HttpServletRequest;
import org.joget.apps.app.service.AppUtil;
import org.joget.commons.util.DynamicDataSourceManager;
import org.joget.commons.util.LogUtil;
import org.joget.commons.util.ResourceBundleUtil;
import org.joget.plugin.base.Plugin;
import org.joget.plugin.base.PluginManager;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A snapshot of the server at recording-start, shown in the replay page's Info tab. Grouped like Glowroot's JVM >
 * Environment page (host, process, java), then Joget and database, then everything Settings > System Info shows:
 * <ul>
 * <li>license: its license table (Enterprise: SysInfo.getLicenseProperties(), including the signature, system key and
 * MAC addresses; missing on Community)</li>
 * <li>systemProperties: its system table (SysInfo.getSystemProperties(): all Java system properties plus JVM memory
 * and processors; on Community the same values read directly)</li>
 * </ul>
 * Plugins lists every jar in app_plugins with its date, size and the plugins it loaded (none: it failed to load).
 * Database and connectionPool hold the current profile's app_datasource file and the pool settings Joget really uses.
 * Apart from the database password (left out, and masked in JDBC URLs), nothing is left out or masked, so it can hold
 * secrets passed as -D options: it is shown only to users allowed to view system logs, like the system log itself.
 */
final class ServerInfo {

    static final String FILE = "server-info.json";

    private ServerInfo() {
    }

    static JSONObject collect(HttpServletRequest request) throws JSONException {
        long now = System.currentTimeMillis();
        Runtime rt = Runtime.getRuntime();
        java.lang.management.RuntimeMXBean jvm = ManagementFactory.getRuntimeMXBean();
        Class<?> sysInfo = enterprise();

        JSONObject host = new JSONObject();
        put(host, "Hostname", hostname());
        put(host, "Node", SystemLogCapture.nodeName());
        put(host, "Processors", String.valueOf(rt.availableProcessors()));
        put(host, "Physical memory", mb(osAttribute("TotalPhysicalMemorySize")));
        put(host, "OS", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");

        JSONObject process = new JSONObject();
        String name = jvm.getName(); // "pid@host"
        put(process, "Process id", name.contains("@") ? name.substring(0, name.indexOf('@')) : name);
        put(process, "Started", new java.util.Date(jvm.getStartTime()).toString());
        put(process, "Uptime", duration(jvm.getUptime()));

        JSONObject java = new JSONObject();
        put(java, "Version", System.getProperty("java.version"));
        put(java, "Vendor", System.getProperty("java.vendor"));
        put(java, "VM", System.getProperty("java.vm.name") + " (" + System.getProperty("java.vm.version") + ")");
        put(java, "Heap used", (rt.totalMemory() - rt.freeMemory()) / MB + " MB");
        put(java, "Heap committed", rt.totalMemory() / MB + " MB");
        put(java, "Heap max", rt.maxMemory() / MB + " MB");
        put(java, "Arguments", String.join("\n", jvm.getInputArguments()));

        JSONObject joget = new JSONObject();
        String version;
        try {
            version = ResourceBundleUtil.getMessage("console.footer.label.revision");
        } catch (RuntimeException | LinkageError e) {
            version = null; // outside a running Joget
        }
        put(joget, "Version", version != null && !version.startsWith("console.") ? version : null);
        put(joget, "Edition", sysInfo != null ? "Enterprise" : "Community");
        put(joget, "Application server", request.getServletContext().getServerInfo());
        put(joget, "Time zone", TimeZone.getDefault().getID());
        GlowrootClient g = GlowrootClient.find("");
        put(joget, "Glowroot", g != null ? g.base() : "not running");
        put(joget, "Glowroot version", GlowrootClient.agentVersion());

        JSONObject db = new JSONObject();
        Properties dsFile = null;
        try {
            dsFile = DynamicDataSourceManager.getProperties();
            db = database(DynamicDataSourceManager.getCurrentProfile(), dsFile);
        } catch (RuntimeException | LinkageError e) {
            // not readable here
        }
        JSONObject pool = null;
        try {
            pool = pool(AppUtil.getApplicationContext().getBean("setupDataSource"), dsFile);
        } catch (Exception | LinkageError e) {
            // outside a running Joget
        }

        JSONObject plugins = null;
        try {
            plugins = plugins();
        } catch (Exception | LinkageError e) {
            // outside a running Joget
        }

        // Everything System Info shows, as it shows it
        Map<?, ?> license = sysInfo != null ? call(sysInfo, "getLicenseProperties") : null;
        Map<?, ?> system = sysInfo != null ? call(sysInfo, "getSystemProperties") : null;
        if (system == null) {
            system = communitySystem();
        }

        JSONObject result = new JSONObject().put("takenAt", now)
                .put("host", host).put("process", process).put("java", java).put("joget", joget).put("database", db);
        if (pool != null && pool.length() > 0) {
            result.put("connectionPool", pool);
        }
        if (plugins != null && plugins.length() > 0) {
            result.put("plugins", plugins);
        }
        if (license != null) {
            result.put("license", sorted(license));
        }
        return result.put("systemProperties", sorted(system));
    }

    private static final long MB = 1024 * 1024;

    /**
     * Pool settings in the order shown. Joget's DynamicDataSource (a Tomcat JDBC pool) copies every other key of
     * app_datasource-<profile>.properties onto itself with BeanUtils and ignores keys it can't set, so these are read
     * back from the pool to show what is really used.
     */
    static final String[] POOL_SETTINGS = {"initialSize", "maxActive", "maxIdle", "minIdle", "maxWait", "maxAge",
        "testOnBorrow", "testOnReturn", "testWhileIdle", "testOnConnect", "validationQuery", "validationQueryTimeout",
        "validationInterval", "validatorClassName", "initSQL", "timeBetweenEvictionRunsMillis",
        "minEvictableIdleTimeMillis", "numTestsPerEvictionRun", "removeAbandoned", "removeAbandonedTimeout",
        "logAbandoned", "abandonWhenPercentageFull", "suspectTimeout", "defaultAutoCommit", "defaultReadOnly",
        "defaultTransactionIsolation", "defaultCatalog", "connectionProperties", "jdbcInterceptors", "fairQueue",
        "jmxEnabled", "logValidationErrors", "propagateInterruptState", "useDisposableConnectionFacade",
        "commitOnReturn", "rollbackOnReturn", "alternateUsernameAllowed", "useEquals", "useLock",
        "ignoreExceptionOnPreLoad"};

    /** Pool state at recording start: label, getter. */
    private static final String[][] POOL_STATE = {{"Pool size", "getSize"}, {"Active connections", "getActive"},
        {"Idle connections", "getIdle"}, {"Waiting threads", "getWaitCount"}};

    /** Keys DynamicDataSource handles itself instead of setting them on the pool. */
    private static boolean isConnectionKey(String key) {
        return key.endsWith("Driver") || key.endsWith("Url") || key.endsWith("User") || key.endsWith("Password")
                || key.endsWith("profileName") || key.endsWith("encryption");
    }

    /** The datasource file of the current profile: profile, driver, URL, user, then its other keys. No password. */
    static JSONObject database(String profile, Properties file) throws JSONException {
        JSONObject db = new JSONObject();
        put(db, "Profile", profile);
        if (file == null) {
            return db;
        }
        put(db, "JDBC driver", file.getProperty("workflowDriver"));
        put(db, "JDBC URL", maskPasswords(file.getProperty("workflowUrl")));
        put(db, "User", file.getProperty("workflowUser"));
        for (String key : new TreeMap<>(propertyMap(file)).keySet()) {
            if (key.equals("workflowDriver") || key.equals("workflowUrl") || key.equals("workflowUser") || isSecret(key)) {
                continue;
            }
            put(db, key, maskPasswords(file.getProperty(key)));
        }
        return db;
    }

    /**
     * The pool's settings as it uses them, a value that differs from the file marked "(file says x)", the file's keys
     * the pool has no setting for (Joget ignores them without a word), and the pool state now.
     */
    static JSONObject pool(Object ds, Properties file) throws JSONException {
        JSONObject p = new JSONObject();
        if (ds == null) {
            return p;
        }
        for (String[] s : POOL_STATE) {
            Object v = getter(ds, s[1]);
            put(p, s[0], v != null ? String.valueOf(v) : null);
        }
        for (String name : POOL_SETTINGS) {
            Object v = bean(ds, name);
            String used = v != null ? maskPasswords(String.valueOf(v)) : null;
            String inFile = file != null ? file.getProperty(name) : null;
            if (inFile != null && !sameValue(inFile, v)) {
                used = (used != null ? used : "(not set)") + " (file says " + maskPasswords(inFile.trim()) + ")";
            }
            put(p, name, used);
        }
        if (file != null) {
            java.util.List<String> unused = new java.util.ArrayList<>();
            for (String key : new TreeMap<>(propertyMap(file)).keySet()) {
                if (!isConnectionKey(key) && !isSecret(key) && !hasSetter(ds, key)) {
                    unused.add(key);
                }
            }
            put(p, "Ignored file keys", unused.isEmpty() ? null : String.join(", ", unused)
                    + " (the pool has no such setting; check the spelling)");
        }
        return p;
    }

    /** The plugins Joget loaded from app_plugins, by jar file. */
    private static JSONObject plugins() throws JSONException {
        PluginManager pm = (PluginManager) AppUtil.getApplicationContext().getBean("pluginManager");
        Map<String, java.util.List<String>> loaded = new TreeMap<>();
        for (Plugin p : pm.listOsgiPlugin(null)) {
            String jar = pm.getJarFileName(p.getClass().getName());
            if (jar != null) {
                loaded.computeIfAbsent(jar, k -> new java.util.ArrayList<>())
                        .add(p.getName() + " " + p.getVersion() + " (" + p.getClass().getName() + ")");
            }
        }
        return plugins(new java.io.File(pm.getBaseDirectory()), loaded);
    }

    /**
     * One entry per jar in the plugin folder (jar file -> date, size, then one plugin per line), plus jars Joget loaded
     * from elsewhere. A jar in the folder with no loaded plugin failed to load (Joget logs why) or holds no plugin.
     */
    static JSONObject plugins(java.io.File dir, Map<String, java.util.List<String>> loaded) throws JSONException {
        Map<String, String> entries = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        java.io.File[] jars = dir.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"));
        java.text.SimpleDateFormat date = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (java.io.File jar : jars != null ? jars : new java.io.File[0]) {
            java.util.List<String> names = loaded.get(jar.getName());
            String size = jar.length() >= MB ? String.format(java.util.Locale.ROOT, "%.1f MB", jar.length() / (double) MB)
                    : Math.max(1, jar.length() / 1024) + " KB";
            java.util.List<String> sortedNames = names != null ? new java.util.ArrayList<>(names) : null;
            if (sortedNames != null) {
                java.util.Collections.sort(sortedNames);
            }
            entries.put(jar.getName(), "Modified " + date.format(new java.util.Date(jar.lastModified())) + " · " + size + "\n"
                    + (sortedNames != null ? String.join("\n", sortedNames) : "No plugin loaded (failed to load: check the Joget log)"));
        }
        for (Map.Entry<String, java.util.List<String>> e : loaded.entrySet()) {
            if (!entries.containsKey(e.getKey())) {
                java.util.List<String> names = new java.util.ArrayList<>(e.getValue());
                java.util.Collections.sort(names);
                entries.put(e.getKey(), "Not in the plugin folder\n" + String.join("\n", names));
            }
        }
        JSONObject o = new JSONObject();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            o.put(e.getKey(), e.getValue());
        }
        return o;
    }

    private static Map<String, String> propertyMap(Properties props) {
        Map<String, String> m = new TreeMap<>();
        for (String k : props.stringPropertyNames()) {
            m.put(k, props.getProperty(k));
        }
        return m;
    }

    private static boolean isSecret(String key) {
        return key.toLowerCase(java.util.Locale.ROOT).contains("password");
    }

    /** Hides password=... in a JDBC URL or driver property list. */
    static String maskPasswords(String s) {
        return s == null ? null : s.replaceAll("(?i)(password=)[^;&]*", "$1****");
    }

    private static boolean sameValue(String inFile, Object used) {
        String f = inFile.trim();
        if (used == null) {
            return f.isEmpty();
        }
        return used instanceof Boolean ? f.equalsIgnoreCase(String.valueOf(used)) : f.equals(String.valueOf(used));
    }

    private static Object bean(Object o, String name) {
        String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        Object v = getter(o, "get" + cap);
        return v != null ? v : getter(o, "is" + cap);
    }

    private static Object getter(Object o, String method) {
        try {
            return o.getClass().getMethod(method).invoke(o);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /** What BeanUtils.setProperty needs to apply a file key: a public one-argument setter. */
    private static boolean hasSetter(Object o, String key) {
        if (key.isEmpty()) {
            return false;
        }
        String setter = "set" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
        for (Method m : o.getClass().getMethods()) {
            if (m.getName().equals(setter) && m.getParameterCount() == 1) {
                return true;
            }
        }
        return false;
    }

    private static String hostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return null;
        }
    }

    private static Object osAttribute(String name) {
        try {
            return ManagementFactory.getPlatformMBeanServer().getAttribute(new javax.management.ObjectName("java.lang:type=OperatingSystem"), name);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    private static String mb(Object bytes) {
        return bytes instanceof Number ? ((Number) bytes).longValue() / MB + " MB" : null;
    }

    private static String duration(long ms) {
        long s = ms / 1000;
        return (s / 86400 > 0 ? s / 86400 + " d " : "") + (s / 3600 % 24) + " h " + (s / 60 % 60) + " min";
    }

    /** Writes the snapshot into the capture's pending folder; it moves into the recording with the system log. */
    static void save(Path captureDir, HttpServletRequest request) {
        try {
            Files.write(captureDir.resolve(FILE), collect(request).toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            LogUtil.warn(ServerInfo.class.getName(), "Could not save the server info of a recording: " + e.getMessage());
        }
    }

    /** What SysInfo.getSystemProperties() gives on Enterprise: all system properties plus the JVM's memory. */
    private static Map<String, String> communitySystem() {
        Map<String, String> m = new TreeMap<>();
        for (String k : System.getProperties().stringPropertyNames()) {
            m.put(k, System.getProperty(k));
        }
        Runtime rt = Runtime.getRuntime();
        m.put("platform.jvm.used", (rt.totalMemory() - rt.freeMemory()) / MB + " MB");
        m.put("platform.jvm.total", rt.totalMemory() / MB + " MB");
        m.put("platform.jvm.max", rt.maxMemory() / MB + " MB");
        m.put("platform.jvm.processors", String.valueOf(rt.availableProcessors()));
        return m;
    }

    private static JSONObject sorted(Map<?, ?> map) throws JSONException {
        JSONObject o = new JSONObject();
        for (Map.Entry<?, ?> e : new TreeMap<>(map).entrySet()) {
            o.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
        }
        return o;
    }

    private static void put(JSONObject info, String label, String value) throws JSONException {
        if (value != null && !value.trim().isEmpty()) {
            info.put(label, value.trim());
        }
    }

    /** Joget Enterprise's SysInfo class (Settings > System Info), from Joget's own classloader, or null on Community. */
    private static Class<?> enterprise() {
        try {
            return Class.forName("org.joget.commons.util.SysInfo", true, AppUtil.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private static Map<?, ?> call(Class<?> c, String method) {
        try {
            Method m = c.getMethod(method);
            Object v = m.invoke(null);
            return v instanceof Map ? (Map<?, ?>) v : null;
        } catch (Exception | LinkageError e) {
            LogUtil.warn(ServerInfo.class.getName(), "Could not read System Info (" + method + "): " + e);
            return null;
        }
    }
}
