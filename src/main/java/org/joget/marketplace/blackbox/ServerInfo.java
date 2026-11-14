package org.joget.marketplace.blackbox;

import java.lang.management.ManagementFactory;
import java.io.IOException;
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
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class ServerInfo {

    static final String FILE = "server-info.json";

    static final String[] LICENSE_SECRETS = {"platform.systemKey", "platform.license.signature", "platform.net.address"};

    private ServerInfo() {
    }

    static JSONObject collect(HttpServletRequest request) throws JSONException {
        return collect(request, 0, new Secrets(null), true);
    }

    static JSONObject collect(HttpServletRequest request, int recentDays, Secrets secrets, boolean maskLicense) throws JSONException {
        Secrets dbSecrets = secrets.masks() ? secrets : Secrets.passwords();
        long now = System.currentTimeMillis();
        Runtime rt = Runtime.getRuntime();
        java.lang.management.RuntimeMXBean jvm = ManagementFactory.getRuntimeMXBean();
        Class<?> sysInfo = enterprise();

        JSONObject host = new JSONObject();
        put(host, "Hostname", hostname());
        put(host, "Node", ServerCapture.nodeName());
        put(host, "Processors", String.valueOf(rt.availableProcessors()));
        put(host, "Total RAM", mb(osAttribute("TotalPhysicalMemorySize")));
        put(host, "Free RAM", mb(osAttribute("FreePhysicalMemorySize")));
        put(host, "OS", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");

        JSONObject process = new JSONObject();
        String name = jvm.getName();
        put(process, "Process id", name.contains("@") ? name.substring(0, name.indexOf('@')) : name);
        put(process, "Started", utcDate("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(jvm.getStartTime())));
        put(process, "Uptime", duration(jvm.getUptime()));

        JSONObject java = new JSONObject();
        put(java, "Version", System.getProperty("java.version"));
        put(java, "Vendor", System.getProperty("java.vendor"));
        put(java, "VM", System.getProperty("java.vm.name") + " (" + System.getProperty("java.vm.version") + ")");
        put(java, "Heap used", (rt.totalMemory() - rt.freeMemory()) / MB + " MB");
        put(java, "Heap committed", rt.totalMemory() / MB + " MB");
        put(java, "Heap max", rt.maxMemory() / MB + " MB");
        java.util.List<String> args = new java.util.ArrayList<>();
        for (String a : jvm.getInputArguments()) {
            args.add(secrets.maskArgument(a));
        }
        put(java, "Arguments", String.join("\n", args));

        JSONObject joget = new JSONObject();
        String version;
        try {
            version = ResourceBundleUtil.getMessage("console.footer.label.revision");
        } catch (RuntimeException | LinkageError e) {
            version = null;
        }
        put(joget, "Version", version != null && !version.startsWith("console.") ? version : null);
        put(joget, "Edition", sysInfo != null ? "Enterprise" : "Community");
        put(joget, "Application server", request.getServletContext().getServerInfo());
        put(joget, "Time zone", zoneLabel(TimeZone.getDefault(), now));
        GlowrootClient g = GlowrootClient.find("");
        put(joget, "Glowroot", g != null ? g.base() : "not running");
        put(joget, "Glowroot version", GlowrootClient.agentVersion());

        JSONObject db = new JSONObject();
        Properties dsFile = null;
        try {
            dsFile = DynamicDataSourceManager.getProperties();
            db = database(DynamicDataSourceManager.getCurrentProfile(), dsFile, dbSecrets);
        } catch (RuntimeException | LinkageError e) {
        }
        JSONObject pool = null;
        try {
            pool = pool(AppUtil.getApplicationContext().getBean("setupDataSource"), dsFile, dbSecrets);
        } catch (Exception | LinkageError e) {
        }

        JSONObject cluster = null;
        try {
            cluster = cluster(request);
        } catch (Exception | LinkageError e) {
        }
        JSONObject plugins = null;
        try {
            plugins = plugins();
        } catch (Exception | LinkageError e) {
        }

        Map<?, ?> license = sysInfo != null ? call(sysInfo, "getLicenseProperties") : null;
        Map<?, ?> system = sysInfo != null ? call(sysInfo, "getSystemProperties") : null;
        if (system == null) {
            system = communitySystem();
        }

        JSONObject result = new JSONObject().put("takenAt", now)
                .put("host", host).put("process", process).put("java", java).put("joget", joget).put("database", db)
                .put("secretsMasked", secrets.masks())
                .put("licenseMasked", maskLicense);
        if (pool != null && pool.length() > 0) {
            result.put("connectionPool", pool);
        }
        if (recentDays > 0) {
            try {
                JSONArray recentApps = new JSONArray();
                result.put("recentChanges", recentChanges(recentDays, now, recentApps));
                result.put(RECENT_APPS, recentApps);
            } catch (Exception | LinkageError e) {
            }
        }
        if (cluster != null && cluster.length() > 0) {
            result.put("cluster", cluster);
        }
        if (plugins != null && plugins.length() > 0) {
            result.put("plugins", plugins);
        }
        if (license != null) {
            result.put("license", license(license, maskLicense));
        }
        return result.put("systemProperties", masked(system, secrets));
    }

    static java.text.SimpleDateFormat utcDate(String pattern) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(pattern + " 'UTC'", java.util.Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }

    static String zoneLabel(TimeZone tz, long at) {
        int min = tz.getOffset(at) / 60000;
        String off = (min < 0 ? "-" : "+") + Math.abs(min) / 60 + (min % 60 != 0 ? String.format(":%02d", Math.abs(min) % 60) : "");
        return tz.getID() + " (UTC" + off + ")";
    }

    private static final long MB = 1024 * 1024;

    static final String[] POOL_SETTINGS = {"initialSize", "maxActive", "maxIdle", "minIdle", "maxWait", "maxAge",
        "testOnBorrow", "testOnReturn", "testWhileIdle", "testOnConnect", "validationQuery", "validationQueryTimeout",
        "validationInterval", "validatorClassName", "initSQL", "timeBetweenEvictionRunsMillis",
        "minEvictableIdleTimeMillis", "numTestsPerEvictionRun", "removeAbandoned", "removeAbandonedTimeout",
        "logAbandoned", "abandonWhenPercentageFull", "suspectTimeout", "defaultAutoCommit", "defaultReadOnly",
        "defaultTransactionIsolation", "defaultCatalog", "connectionProperties", "jdbcInterceptors", "fairQueue",
        "jmxEnabled", "logValidationErrors", "propagateInterruptState", "useDisposableConnectionFacade",
        "commitOnReturn", "rollbackOnReturn", "alternateUsernameAllowed", "useEquals", "useLock",
        "ignoreExceptionOnPreLoad"};

    private static final String[][] POOL_STATE = {{"Pool size", "getSize"}, {"Active connections", "getActive"},
        {"Idle connections", "getIdle"}, {"Waiting threads", "getWaitCount"}};

    private static boolean isConnectionKey(String key) {
        return key.endsWith("Driver") || key.endsWith("Url") || key.endsWith("User") || key.endsWith("Password")
                || key.endsWith("profileName") || key.endsWith("encryption");
    }

    static JSONObject database(String profile, Properties file, Secrets secrets) throws JSONException {
        JSONObject db = new JSONObject();
        put(db, "Profile", profile);
        if (file == null) {
            return db;
        }
        put(db, "JDBC driver", file.getProperty("workflowDriver"));
        put(db, "JDBC URL", secrets.maskValue(maskPasswords(file.getProperty("workflowUrl"))));
        put(db, "User", file.getProperty("workflowUser"));
        for (String key : new TreeMap<>(propertyMap(file)).keySet()) {
            if (key.equals("workflowDriver") || key.equals("workflowUrl") || key.equals("workflowUser") || isSecret(key)) {
                continue;
            }
            put(db, key, secrets.mask(key, maskPasswords(file.getProperty(key))));
        }
        return db;
    }

    static JSONObject pool(Object ds, Properties file, Secrets secrets) throws JSONException {
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
            String used = v != null ? secrets.mask(name, maskPasswords(String.valueOf(v))) : null;
            String inFile = file != null ? file.getProperty(name) : null;
            if (inFile != null && !sameValue(inFile, v)) {
                used = (used != null ? used : "(not set)") + " (file says " + secrets.mask(name, maskPasswords(inFile.trim())) + ")";
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

    static JSONObject cluster(HttpServletRequest request) throws JSONException {
        JSONObject c = new JSONObject();
        String name = org.joget.commons.util.ServerUtil.getServerName();
        put(c, "Joget node name", name.isEmpty() ? "(not set: single server)" : name);
        put(c, "Cluster mode", "true".equals(System.getProperty("wflow.cluster")) ? "On (wflow-cluster agent)" : "Off");
        put(c, "Registered nodes", nodes(org.joget.commons.util.ServerUtil.getServerList()));
        put(c, "Active nodes", nodes(org.joget.commons.util.ServerUtil.getActiveServerList()));
        Object route = mbean("Catalina:type=Engine", "jvmRoute");
        put(c, "Tomcat jvmRoute", route != null ? String.valueOf(route) : "(not set)");
        put(c, "Session replication", replication(request.getContextPath()));
        String session = request.getRequestedSessionId();
        if (session != null && session.contains(".")) {
            put(c, "This session's route", session.substring(session.indexOf('.') + 1));
        }
        return c;
    }

    private static String replication(String contextPath) {
        try {
            javax.management.MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            if (server.queryNames(new javax.management.ObjectName("Catalina:type=Cluster,*"), null).isEmpty()) {
                return "Off";
            }
            String path = contextPath.isEmpty() ? "/" : contextPath;
            for (javax.management.ObjectName n : server.queryNames(new javax.management.ObjectName("Catalina:j2eeType=WebModule,*"), null)) {
                String name = n.getKeyProperty("name");
                int at = name != null && name.startsWith("//") ? name.indexOf('/', 2) : -1;
                if (at >= 0 && name.substring(at).equals(path)) {
                    return Boolean.TRUE.equals(server.getAttribute(n, "distributable")) ? "On (Tomcat cluster)"
                            : "Off (Tomcat cluster, but the web app isn't distributable)";
                }
            }
            return "Tomcat cluster (whether the web app is distributable is unknown)";
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    private static String nodes(String[] names) {
        if (names == null || names.length == 0) {
            return null;
        }
        String[] sorted = names.clone();
        java.util.Arrays.sort(sorted);
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String n : sorted) {
            String ip = org.joget.commons.util.ServerUtil.getIPAddress(n);
            lines.add(ip != null && !ip.isEmpty() ? n + " (" + ip + ")" : n);
        }
        return String.join("\n", lines);
    }

    private static Object mbean(String name, String attribute) {
        try {
            return ManagementFactory.getPlatformMBeanServer().getAttribute(new javax.management.ObjectName(name), attribute);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    private static JSONObject recentChanges(int days, long now, JSONArray recentApps) throws JSONException {
        long since = now - days * 24L * 3600 * 1000;
        org.joget.apps.app.dao.AppDefinitionDao dao = (org.joget.apps.app.dao.AppDefinitionDao) AppUtil.getApplicationContext()
                .getBean("appDefinitionDao");
        java.util.List<Object[]> apps = new java.util.ArrayList<>();
        java.util.Collection<org.joget.apps.app.model.AppDefinition> recent = dao.findByVersion(null, null, null, null, "dateModified", true, 0, 50);
        for (org.joget.apps.app.model.AppDefinition a : recent != null ? recent : java.util.Collections.<org.joget.apps.app.model.AppDefinition>emptyList()) {
            if (a.getDateModified() != null) {
                apps.add(new Object[]{a.getDateModified().getTime(), a.getAppId() + " v" + a.getVersion()
                        + (a.isPublished() ? " (published)" : "") + " " + a.getName()});
                if (a.getDateModified().getTime() >= since) {
                    recentApps.put(new JSONObject().put("id", a.getAppId()).put("version", a.getVersion())
                            .put("published", a.isPublished()).put("name", a.getName()).put("modified", a.getDateModified().getTime()));
                }
            }
        }
        return recentChanges(days, since, apps, new java.io.File(org.joget.commons.util.SetupManager.getBaseDirectory()), ManagementFactory.getRuntimeMXBean().getStartTime());
    }

    static JSONObject recentChanges(int days, long since, java.util.List<Object[]> apps, java.io.File wflowDir,
            long started) throws JSONException {
        JSONObject c = new JSONObject();
        java.text.SimpleDateFormat date = utcDate("yyyy-MM-dd HH:mm");
        put(c, "Period", "the last " + days + " day" + (days == 1 ? "" : "s"));
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (Object[] a : apps) {
            if ((Long) a[0] >= since) {
                lines.add(date.format(new java.util.Date((Long) a[0])) + "  " + a[1]);
            }
        }
        put(c, "App versions", lines.isEmpty() ? null : String.join("\n", lines));
        put(c, "wflow files", files(wflowDir, since, date, null));
        if (started >= since) {
            put(c, "Joget started", date.format(new java.util.Date(started)));
        }
        if (c.length() == 1) {
            put(c, "Changes", "None found (apps, wflow files, restarts)");
        }
        return c;
    }

    private static final java.util.Set<String> JOGET_RUNTIME_FILES = new java.util.HashSet<>(java.util.Arrays.asList(
            "servers.json", "serversip.properties", "app_presence.json"));

    private static String files(java.io.File dir, long since, java.text.SimpleDateFormat date, String suffix) {
        java.io.File[] all = dir != null ? dir.listFiles() : null;
        java.util.List<java.io.File> changed = new java.util.ArrayList<>();
        for (java.io.File f : all != null ? all : new java.io.File[0]) {
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (f.isFile() && f.lastModified() >= since && (suffix == null || n.endsWith(suffix)) && !n.endsWith(".log")
                    && !JOGET_RUNTIME_FILES.contains(n)) {
                changed.add(f);
            }
        }
        changed.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (java.io.File f : changed) {
            lines.add(date.format(new java.util.Date(f.lastModified())) + "  " + f.getName());
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

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

    static JSONObject plugins(java.io.File dir, Map<String, java.util.List<String>> loaded) throws JSONException {
        Map<String, String> entries = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        java.io.File[] jars = dir.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"));
        java.text.SimpleDateFormat date = utcDate("yyyy-MM-dd HH:mm");
        for (java.io.File jar : jars != null ? jars : new java.io.File[0]) {
            String size = jar.length() >= MB ? String.format(java.util.Locale.ROOT, "%.1f MB", jar.length() / (double) MB)
                    : Math.max(1, jar.length() / 1024) + " KB";
            entries.put(jar.getName(), date.format(new java.util.Date(jar.lastModified())) + " · " + size
                    + (loaded.containsKey(jar.getName()) ? "" : " · not loaded"));
        }
        for (String jar : loaded.keySet()) {
            if (!entries.containsKey(jar)) {
                entries.put(jar, "Not in the plugin folder");
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

    static final String RECENT_APPS = "recentApps";

    interface AppTables {
        java.util.Set<String> of(String appId, String version);
    }

    static AppTables jogetTables() {
        org.joget.apps.app.service.AppService appService = (org.joget.apps.app.service.AppService) AppUtil.getApplicationContext()
                .getBean("appService");
        return (appId, version) -> {
            org.joget.apps.app.model.AppDefinition def = appService.loadAppDefinition(appId, version);
            return def != null ? SampleData.tables(def).keySet() : null;
        };
    }

    static void summarizeApps(Path infoFile, java.util.Collection<String> used, AppTables tables) throws IOException {
        if (infoFile == null || !Files.isRegularFile(infoFile)) {
            return;
        }
        try {
            JSONObject info = new JSONObject(new String(Files.readAllBytes(infoFile), StandardCharsets.UTF_8));
            JSONArray all = info.optJSONArray(RECENT_APPS);
            JSONObject changes = info.optJSONObject("recentChanges");
            if (all == null || changes == null) {
                return;
            }
            java.util.Set<String> usedTables = new java.util.HashSet<>();
            for (String id : used) {
                java.util.Set<String> t = tables.of(id, null);
                if (t != null) {
                    usedTables.addAll(t);
                }
            }
            Map<String, java.util.List<JSONObject>> byApp = new java.util.LinkedHashMap<>();
            for (int i = 0; i < all.length(); i++) {
                JSONObject a = all.getJSONObject(i);
                byApp.computeIfAbsent(a.getString("id"), k -> new java.util.ArrayList<>()).add(a);
            }
            java.text.SimpleDateFormat date = utcDate("yyyy-MM-dd HH:mm");
            java.util.List<String> lines = new java.util.ArrayList<>();
            for (Map.Entry<String, java.util.List<JSONObject>> e : byApp.entrySet()) {
                String shares = "";
                if (!used.contains(e.getKey())) {
                    java.util.Set<String> common = new java.util.TreeSet<>();
                    for (JSONObject v : e.getValue()) {
                        java.util.Set<String> t = tables.of(e.getKey(), String.valueOf(v.optLong("version")));
                        if (t != null) {
                            for (String table : t) {
                                if (usedTables.contains(table)) {
                                    common.add(table);
                                }
                            }
                        }
                    }
                    if (common.isEmpty()) {
                        continue;
                    }
                    shares = " · shares " + String.join(", ", common);
                }
                JSONObject latest = e.getValue().get(0);
                int n = e.getValue().size();
                lines.add(date.format(new java.util.Date(latest.optLong("modified"))) + "  " + e.getKey() + " v" + latest.optLong("version")
                        + (latest.optBoolean("published") ? " (published)" : "") + " " + latest.optString("name")
                        + " · " + n + " version" + (n == 1 ? "" : "s") + " changed" + shares);
            }
            if (lines.isEmpty()) {
                changes.remove("App versions");
            } else {
                changes.put("App versions", String.join("\n", lines));
            }
            Files.write(infoFile, info.toString().getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException(e);
        }
    }

    static void save(Path captureDir, HttpServletRequest request) {
        save(captureDir, request, 0, new Secrets(null), true);
    }

    static void save(Path captureDir, HttpServletRequest request, int recentDays, Secrets secrets, boolean maskLicense) {
        try {
            Files.write(captureDir.resolve(FILE), collect(request, recentDays, secrets, maskLicense).toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            LogUtil.warn(ServerInfo.class.getName(), "Could not save the server info of a recording: " + e.getMessage());
        }
    }

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

    static JSONObject license(Map<?, ?> map, boolean mask) throws JSONException {
        JSONObject o = sorted(map);
        if (mask) {
            for (java.util.Iterator<?> it = o.keys(); it.hasNext();) {
                String k = String.valueOf(it.next());
                for (String p : LICENSE_SECRETS) {
                    if (k.startsWith(p) && !o.optString(k).isEmpty()) {
                        o.put(k, Secrets.MASK);
                    }
                }
            }
        }
        return o;
    }

    static JSONObject masked(Map<?, ?> map, Secrets secrets) throws JSONException {
        JSONObject o = new JSONObject();
        for (Map.Entry<?, ?> e : new TreeMap<>(map).entrySet()) {
            String k = String.valueOf(e.getKey());
            o.put(k, e.getValue() == null ? "" : secrets.mask(k, String.valueOf(e.getValue())));
        }
        return o;
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

    static boolean hasLicense() {
        return enterprise() != null;
    }

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
