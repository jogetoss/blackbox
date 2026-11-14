package org.joget.marketplace.blackbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class GlowrootClient {

    private static final int CONNECT_MS = 3000;
    private static final int READ_MS = 30000;
    private static final long SETTINGS_MS = 60 * 1000L;
    static final long MAX_EXPORT_BYTES = 20L * 1024 * 1024;

    private final String base;
    private Settings settings;
    private long settingsAt;

    GlowrootClient(String base) {
        this.base = base.replaceAll("/+$", "");
    }

    String base() {
        return base;
    }

    static GlowrootClient find(String configured) {
        if (configured != null && !configured.trim().isEmpty()) {
            return new GlowrootClient(configured.trim());
        }
        String url = detect();
        return url == null ? null : new GlowrootClient(url);
    }

    static Path agentJar() {
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (arg.startsWith("-javaagent:") && arg.contains("glowroot")) {
                String path = arg.substring("-javaagent:".length());
                int eq = path.indexOf('=');
                return Paths.get(eq < 0 ? path : path.substring(0, eq));
            }
        }
        return null;
    }

    static String agentVersion() {
        Path jar = agentJar();
        if (jar == null || !Files.isRegularFile(jar)) {
            return null;
        }
        try (java.util.jar.JarFile f = new java.util.jar.JarFile(jar.toFile())) {
            java.util.jar.Manifest m = f.getManifest();
            String v = m != null ? m.getMainAttributes().getValue("Implementation-Version") : null;
            String built = m != null ? m.getMainAttributes().getValue("Build-Time") : null;
            return v == null ? null : built == null ? v : v + ", built " + built;
        } catch (IOException e) {
            return null;
        }
    }

    private static String detect() {
        Path jar = agentJar();
        if (jar == null || jar.getParent() == null) {
            return null;
        }
        Path dir = jar.getParent();
        List<Path> candidates = new ArrayList<>();
        try {
            InetAddress local = InetAddress.getLocalHost();
            candidates.add(dir.resolve("agent-" + local.getHostName()).resolve("admin.json"));
            candidates.add(dir.resolve("agent-" + local.getCanonicalHostName()).resolve("admin.json"));
        } catch (IOException e) {
        }
        candidates.add(dir.resolve("admin.json"));
        for (Path f : candidates) {
            if (!Files.isRegularFile(f)) {
                continue;
            }
            try {
                JSONObject web = new JSONObject(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).optJSONObject("web");
                int port = web != null ? web.optInt("port", 4000) : 4000;
                String context = web != null ? web.optString("contextPath", "/") : "/";
                return "http://localhost:" + port + (context.startsWith("/") ? context : "/" + context);
            } catch (IOException | JSONException e) {
            }
        }
        return "http://localhost:4000";
    }

    static final class Settings {
        final long thresholdMs;
        final long mainThresholdMs;
        final boolean errorOn4xx;

        Settings(long thresholdMs, boolean errorOn4xx) {
            this(thresholdMs, thresholdMs, errorOn4xx);
        }

        Settings(long thresholdMs, long mainThresholdMs, boolean errorOn4xx) {
            this.thresholdMs = thresholdMs;
            this.mainThresholdMs = mainThresholdMs;
            this.errorOn4xx = errorOn4xx;
        }

        boolean keeps(long ms, int status, boolean failed) {
            return failed || status >= 500 || (errorOn4xx && status >= 400) || ms >= thresholdMs;
        }
    }

    synchronized Settings settings() throws IOException {
        long now = System.currentTimeMillis();
        if (settings != null && now - settingsAt < SETTINGS_MS) {
            return settings;
        }
        JSONObject tx = getJson("/backend/config/transaction?agent-id=").optJSONObject("config");
        long threshold = tx != null ? tx.optLong("slowThresholdMillis", 2000) : 2000;
        long main = threshold;
        JSONArray overrides = tx != null ? tx.optJSONArray("slowThresholdOverrides") : null;
        for (int i = 0; overrides != null && i < overrides.length(); i++) {
            JSONObject o = overrides.optJSONObject(i);
            if (o != null && o.has("thresholdMillis")) {
                threshold = Math.min(threshold, o.optLong("thresholdMillis"));
            }
        }
        boolean on4xx = false;
        JSONArray props = getJson("/backend/config/plugins?agent-id=&plugin-id=servlet").optJSONArray("properties");
        for (int i = 0; props != null && i < props.length(); i++) {
            JSONObject p = props.optJSONObject(i);
            if (p != null && "traceErrorOn4xxResponseCode".equals(p.optString("name"))) {
                on4xx = p.optBoolean("value");
            }
        }
        settings = new Settings(threshold, main, on4xx);
        settingsAt = now;
        return settings;
    }

    static final class Point {
        final String traceId;
        final long endMs;
        final long durationMs;

        Point(String traceId, long endMs, long durationMs) {
            this.traceId = traceId;
            this.endMs = endMs;
            this.durationMs = durationMs;
        }
    }

    List<Point> points(long from, long to) throws IOException {
        return points(from, to, "Web");
    }

    List<Point> points(long from, long to, String type) throws IOException {
        String query = "?agent-rollup-id=&transaction-type=" + enc(type) + "&from=" + from + "&to=" + to + "&duration-millis-low=0&limit=1000";
        Map<String, Point> byId = new LinkedHashMap<>();
        addPoints(getJson("/backend/transaction/points" + query), byId);
        addPoints(getJson("/backend/error/points" + query), byId);
        return new ArrayList<>(byId.values());
    }

    static void addPoints(JSONObject answer, Map<String, Point> byId) {
        for (String kind : new String[]{"normalPoints", "errorPoints", "partialPoints"}) {
            JSONArray list = answer.optJSONArray(kind);
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONArray p = list.optJSONArray(i);
                if (p != null && p.length() >= 4) {
                    byId.putIfAbsent(p.optString(3), new Point(p.optString(3), p.optLong(0), Math.round(p.optDouble(1))));
                }
            }
        }
    }

    JSONObject header(String traceId) throws IOException {
        return getJson("/backend/trace/header?agent-id=&trace-id=" + enc(traceId) + "&check-live-traces=false");
    }

    JSONObject queries(String traceId) throws IOException {
        return getJson("/backend/trace/queries?agent-id=&trace-id=" + enc(traceId));
    }

    byte[] exportHtml(String traceId) throws IOException {
        HttpURLConnection c = open("/export/trace?agent-id=&trace-id=" + enc(traceId) + "&check-live-traces=false");
        try (ZipInputStream zip = new ZipInputStream(c.getInputStream())) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.getName().endsWith(".html")) {
                    return read(zip, MAX_EXPORT_BYTES);
                }
            }
        } finally {
            c.disconnect();
        }
        throw new IOException("Glowroot's export of trace " + traceId + " has no HTML page");
    }

    private JSONObject getJson(String path) throws IOException {
        HttpURLConnection c = open(path);
        try (InputStream in = c.getInputStream()) {
            String body = new String(read(in, MAX_EXPORT_BYTES), StandardCharsets.UTF_8).trim();
            return body.isEmpty() ? new JSONObject() : new JSONObject(body);
        } catch (JSONException e) {
            throw new IOException("Glowroot answered " + path + " with something that is not JSON");
        } finally {
            c.disconnect();
        }
    }

    private HttpURLConnection open(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(READ_MS);
        c.setInstanceFollowRedirects(false);
        int status = c.getResponseCode();
        if (status == 401 || status == 403) {
            c.disconnect();
            throw new IOException("Glowroot at " + base + " needs a login (BlackBox can only read it as its anonymous user)");
        }
        if (status != 200) {
            c.disconnect();
            throw new IOException("Glowroot at " + base + " answered HTTP " + status);
        }
        return c;
    }

    private static byte[] read(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        long size = 0;
        for (int n; (n = in.read(buf)) != -1; ) {
            size += n;
            if (size > max) {
                throw new IOException("Glowroot's answer is larger than " + (max / (1024 * 1024)) + " MB");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
