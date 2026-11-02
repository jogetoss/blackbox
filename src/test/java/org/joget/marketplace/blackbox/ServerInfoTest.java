package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import org.json.JSONObject;
import org.junit.Test;

public class ServerInfoTest {

    @Test
    public void communitySnapshotHasAllSystemProperties() throws Exception {
        ServletContext ctx = (ServletContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ServletContext.class},
                (p, m, a) -> "getServerInfo".equals(m.getName()) ? "Apache Tomcat/9.0.test" : null);
        HttpServletRequest req = (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (p, m, a) -> "getServletContext".equals(m.getName()) ? ctx : null);
        JSONObject s = ServerInfo.collect(req);
        assertFalse("no Joget Enterprise here", s.has("license"));
        JSONObject system = s.getJSONObject("systemProperties");
        assertEquals("every system property", System.getProperty("java.version"), system.getString("java.version"));
        assertEquals(System.getProperties().stringPropertyNames().size() + 4, system.length());
        assertTrue(system.has("platform.jvm.max"));
        assertEquals("Community", s.getJSONObject("joget").getString("Edition"));
        assertEquals("Apache Tomcat/9.0.test", s.getJSONObject("joget").getString("Application server"));
        assertEquals(String.valueOf(Runtime.getRuntime().availableProcessors()), s.getJSONObject("host").getString("Processors"));
        assertTrue(s.getJSONObject("host").getString("Total RAM").endsWith(" MB"));
        assertTrue(s.getJSONObject("process").has("Process id"));
        assertEquals("one argument per line", java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().size(),
                s.getJSONObject("java").optString("Arguments").isEmpty() ? 0 : s.getJSONObject("java").getString("Arguments").split("\n").length);
        assertTrue(s.getLong("takenAt") > 0);
        assertFalse("no pool outside Joget", s.has("connectionPool"));
        JSONObject cluster = s.getJSONObject("cluster");
        assertEquals("(not set: single server)", cluster.getString("Joget node name"));
        assertEquals("Off", cluster.getString("Cluster mode"));
        assertEquals("no Tomcat here", "(not set)", cluster.getString("Tomcat jvmRoute"));
    }

    private static java.util.Properties datasourceFile() {
        java.util.Properties f = new java.util.Properties();
        f.setProperty("workflowDriver", "com.mysql.cj.jdbc.Driver");
        f.setProperty("workflowUrl", "jdbc:mysql://db:3306/jwdb?user=root&password=s3cret&useSSL=false");
        f.setProperty("workflowUser", "root");
        f.setProperty("workflowPassword", "s3cret");
        f.setProperty("maxActive", "200");
        f.setProperty("testOnBorrow", "TRUE");
        f.setProperty("maxactive", "300");
        return f;
    }

    @Test
    public void databaseShowsTheFileWithoutThePassword() throws Exception {
        JSONObject db = ServerInfo.database("default", datasourceFile());
        assertEquals("default", db.getString("Profile"));
        assertEquals("com.mysql.cj.jdbc.Driver", db.getString("JDBC driver"));
        assertEquals("jdbc:mysql://db:3306/jwdb?user=root&password=****&useSSL=false", db.getString("JDBC URL"));
        assertEquals("root", db.getString("User"));
        assertEquals("200", db.getString("maxActive"));
        assertFalse(db.has("workflowPassword"));
        assertFalse(db.has("workflowUrl"));
        assertFalse(db.toString().contains("s3cret"));
    }

    /** Stands in for the Tomcat JDBC pool: bean getters and setters, plus pool state. */
    public static class FakePool {
        public int getMaxActive() { return 500; }
        public void setMaxActive(int v) { }
        public boolean isTestOnBorrow() { return true; }
        public void setTestOnBorrow(boolean v) { }
        public String getConnectionProperties() { return "connectTimeout=5000;password=x"; }
        public void setConnectionProperties(String v) { }
        public Boolean getDefaultAutoCommit() { return null; }
        public int getSize() { return 3; }
        public int getActive() { return 1; }
        public int getIdle() { return 2; }
        public int getWaitCount() { return 0; }
    }

    @Test
    public void poolShowsSettingsInUseAndFlagsTheFile() throws Exception {
        JSONObject p = ServerInfo.pool(new FakePool(), datasourceFile());
        assertEquals("500 (file says 200)", p.getString("maxActive"));
        assertEquals("booleans compare ignoring case", "true", p.getString("testOnBorrow"));
        assertEquals("connectTimeout=5000;password=****", p.getString("connectionProperties"));
        assertFalse("unset values are left out", p.has("defaultAutoCommit"));
        assertEquals("3", p.getString("Pool size"));
        assertEquals("1", p.getString("Active connections"));
        assertEquals("maxactive (the pool has no such setting; check the spelling)", p.getString("Ignored file keys"));
    }

    @Test
    public void poolWithoutAFileHasNoFlags() throws Exception {
        JSONObject p = ServerInfo.pool(new FakePool(), null);
        assertEquals("500", p.getString("maxActive"));
        assertFalse(p.has("Ignored file keys"));
    }
    @Test
    public void pluginsListEveryJarWithDateAndSize() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("plugins").toFile();
        java.nio.file.Files.write(new java.io.File(dir, "b-plugin.jar").toPath(), new byte[2048]);
        java.nio.file.Files.write(new java.io.File(dir, "A-broken.jar").toPath(), new byte[10]);
        java.nio.file.Files.write(new java.io.File(dir, "notes.txt").toPath(), new byte[10]);
        java.util.Map<String, java.util.List<String>> loaded = new java.util.HashMap<>();
        loaded.put("b-plugin.jar", java.util.Arrays.asList("Zeta 1.0 (x.Zeta)", "Alpha 2.1 (x.Alpha)"));
        loaded.put("elsewhere.jar", java.util.Collections.singletonList("Other 1 (x.Other)"));
        JSONObject p = ServerInfo.plugins(dir, loaded);
        assertEquals(3, p.length());
        assertTrue(p.getString("b-plugin.jar"), p.getString("b-plugin.jar").matches("\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d UTC · 2 KB"));
        assertTrue(p.getString("A-broken.jar").endsWith(" · 1 KB · not loaded"));
        assertEquals("Not in the plugin folder", p.getString("elsewhere.jar"));
        assertFalse(p.has("notes.txt"));
    }
    @Test
    public void recentChangesListWhatChangedInThePeriod() throws Exception {
        long now = System.currentTimeMillis();
        long day = 24L * 3600 * 1000;
        java.io.File plugins = java.nio.file.Files.createTempDirectory("p").toFile();
        java.io.File wflow = java.nio.file.Files.createTempDirectory("w").toFile();
        java.io.File newJar = new java.io.File(plugins, "new.jar");
        java.nio.file.Files.write(newJar.toPath(), new byte[1]);
        java.io.File oldJar = new java.io.File(plugins, "old.jar");
        java.nio.file.Files.write(oldJar.toPath(), new byte[1]);
        assertTrue(oldJar.setLastModified(now - 30 * day));
        java.nio.file.Files.write(new java.io.File(wflow, "app_datasource-x.properties").toPath(), new byte[1]);
        java.nio.file.Files.write(new java.io.File(wflow, "joget.log").toPath(), new byte[1]);
        java.nio.file.Files.write(new java.io.File(wflow, "servers.json").toPath(), new byte[1]);
        java.util.List<Object[]> apps = java.util.Arrays.asList(new Object[]{now - day, "leave v3 (published) Leave"},
                new Object[]{now - 20 * day, "crm v1 CRM"});
        JSONObject c = ServerInfo.recentChanges(7, now - 7 * day, apps, wflow, now - 2 * day);
        assertEquals("the last 7 days", c.getString("Period"));
        assertTrue(c.getString("App versions").endsWith("  leave v3 (published) Leave"));
        assertFalse(c.getString("App versions").contains("crm"));
        assertFalse("plugin jars are not tracked", c.has("Plugins (app_plugins)"));
        assertTrue(c.getString("wflow files").endsWith("app_datasource-x.properties"));
        assertFalse("no logs", c.getString("wflow files").contains("joget.log"));
        assertFalse("not Joget's own runtime files", c.getString("wflow files").contains("servers.json"));
        assertTrue(c.getString("Joget started").endsWith(" UTC"));

        JSONObject none = ServerInfo.recentChanges(1, now + day, java.util.Collections.<Object[]>emptyList(), wflow, 0);
        assertTrue(none.getString("Changes").startsWith("None found"));
    }

    @Test
    public void summarizeAppsKeepsTheRecordingsAppsAndThoseSharingTheirTables() throws Exception {
        java.nio.file.Path f = java.nio.file.Files.createTempFile("bb-info", ".json");
        org.json.JSONArray all = new org.json.JSONArray();
        long t = 1_800_000_000_000L;
        all.put(app("leaveDemo", 12, true, "Leave Request", t)).put(app("agreesign", 1, true, "SigningDoc", t - 1))
                .put(app("leaveDemo", 11, false, "Leave Request", t - 2)).put(app("hr", 3, false, "HR", t - 3));
        java.nio.file.Files.write(f, new JSONObject().put("recentApps", all)
                .put("recentChanges", new JSONObject().put("Period", "the last 7 days").put("App versions", "all of them"))
                .toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.util.Map<String, java.util.Set<String>> tables = new java.util.HashMap<>();
        tables.put("leaveDemo", new java.util.HashSet<>(java.util.Arrays.asList("leave", "employee")));
        tables.put("hr", new java.util.HashSet<>(java.util.Arrays.asList("employee", "payroll")));
        tables.put("agreesign", new java.util.HashSet<>(java.util.Arrays.asList("doc")));

        ServerInfo.summarizeApps(f, java.util.Collections.singletonList("leaveDemo"), (id, v) -> tables.get(id));

        JSONObject info = new JSONObject(new String(java.nio.file.Files.readAllBytes(f), java.nio.charset.StandardCharsets.UTF_8));
        String[] lines = info.getJSONObject("recentChanges").getString("App versions").split("\n");
        assertEquals("one line per related app; agreesign shares no table", 2, lines.length);
        assertTrue(lines[0], lines[0].contains("leaveDemo v12 (published) Leave Request · 2 versions changed"));
        assertTrue(lines[1], lines[1].contains("hr v3 HR · 1 version changed · shares employee"));
        assertEquals("the full list stays in the file", 4, info.getJSONArray("recentApps").length());

        ServerInfo.summarizeApps(f, java.util.Collections.singletonList("other"), (id, v) -> tables.get(id));
        info = new JSONObject(new String(java.nio.file.Files.readAllBytes(f), java.nio.charset.StandardCharsets.UTF_8));
        assertFalse("none related: no row", info.getJSONObject("recentChanges").has("App versions"));
    }

    @Test
    public void timesAreUtcWhateverTheServerZone() {
        java.util.TimeZone was = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kuala_Lumpur"));
            assertEquals("2026-10-30 09:50:09 UTC", ServerInfo.utcDate("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(1793353809000L)));
        } finally {
            java.util.TimeZone.setDefault(was);
        }
    }

    @Test
    public void zoneLabelKeepsTheIdAndAddsTheOffset() {
        long t = 1793353809000L;
        assertEquals("GMT (UTC+0)", ServerInfo.zoneLabel(java.util.TimeZone.getTimeZone("GMT"), t));
        assertEquals("Asia/Kuala_Lumpur (UTC+8)", ServerInfo.zoneLabel(java.util.TimeZone.getTimeZone("Asia/Kuala_Lumpur"), t));
        assertEquals("Asia/Kolkata (UTC+5:30)", ServerInfo.zoneLabel(java.util.TimeZone.getTimeZone("Asia/Kolkata"), t));
        assertEquals("America/St_Johns (UTC-2:30)", ServerInfo.zoneLabel(java.util.TimeZone.getTimeZone("America/St_Johns"), 1783000000000L));
    }

    private static JSONObject app(String id, int version, boolean published, String name, long modified) throws Exception {
        return new JSONObject().put("id", id).put("version", version).put("published", published).put("name", name).put("modified", modified);
    }
}
