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
        assertTrue(s.getJSONObject("host").getString("Physical memory").endsWith(" MB"));
        assertTrue(s.getJSONObject("process").has("Process id"));
        assertEquals("one argument per line", java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().size(),
                s.getJSONObject("java").optString("Arguments").isEmpty() ? 0 : s.getJSONObject("java").getString("Arguments").split("\n").length);
        assertTrue(s.getLong("takenAt") > 0);
        assertFalse("no pool outside Joget", s.has("connectionPool"));
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
}
