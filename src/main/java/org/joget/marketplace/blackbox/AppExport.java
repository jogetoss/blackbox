package org.joget.marketplace.blackbox;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.service.AppService;
import org.joget.apps.app.service.AppUtil;
import org.joget.commons.util.LogUtil;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class AppExport {

    static final String DIR = "app";
    static final String LIST = "apps.json";
    static final int MAX_APPS = 3;
    static final String FILE_NAME = "[A-Za-z0-9_-]+-\\d+\\.jwa";

    static final String APP_CENTER = "appcenter";

    private static final String ID = "([A-Za-z0-9_-]+)";
    private static final Pattern[] PATHS = {
        Pattern.compile("/web/(?:embed/)?(?:userview|ulogin|mobile|mapp)/" + ID + "(?:/|$|\\?)"),
        Pattern.compile("/web/(?:client/|console/|json/(?:console/)?)?app/" + ID + "/(\\d+|latest|_)?"),
        Pattern.compile("/web/json/data/(?:form|list|app)/(?:\\w+/)?" + ID + "/(\\d+)/"),
    };
    private static final Pattern RESOURCE = Pattern.compile("/web/(?:app/[A-Za-z0-9_-]+/(?:\\d+/)?resources/|userview/screenshot/)");
    private static final Pattern PROCESS = Pattern.compile("(?:^|[/=&?])" + ID + "(?:#|%23)(\\d+|latest)(?:#|%23)\\w+");
    private static final Pattern APP_PARAM = Pattern.compile("[?&]appId=" + ID + "(?:&|$)");
    private static final Pattern VERSION_PARAM = Pattern.compile("[?&]appVersion=(\\d+)(?:&|$)");

    private AppExport() {
    }

    static final class Seen {
        final String appId;
        String version;
        int count;
        int problems;

        Seen(String appId) {
            this.appId = appId;
        }
    }

    static final class Hit {
        final String url;
        final String page;
        final boolean problem;
        final boolean browser;

        Hit(String url, String page, boolean problem, boolean browser) {
            this.url = url;
            this.page = page;
            this.problem = problem;
            this.browser = browser;
        }
    }

    static List<Seen> find(Iterable<String> urls) {
        List<Hit> hits = new ArrayList<>();
        for (String url : urls) {
            hits.add(new Hit(url, null, false, true));
        }
        return find(hits);
    }

    static List<Seen> find(List<Hit> hits) {
        boolean browserNamesApps = false;
        for (Hit h : hits) {
            if (h.browser && h.url != null && appOf(h.url) != null) {
                browserNamesApps = true;
                break;
            }
        }
        Map<String, Seen> seen = new LinkedHashMap<>();
        for (Hit h : hits) {
            String[] app = h.url != null ? appOf(h.url) : null;
            if (app != null && (h.browser || !browserNamesApps || seen.containsKey(app[0]))) {
                Seen s = seen.computeIfAbsent(app[0], Seen::new);
                s.count++;
                if (app[1] != null && app[1].matches("\\d+") && s.version == null) {
                    s.version = app[1];
                }
            }
            if (h.problem) {
                String[] where = app != null ? app : h.page != null ? appOf(h.page) : null;
                if (where != null && (h.browser || !browserNamesApps || seen.containsKey(where[0]))) {
                    seen.computeIfAbsent(where[0], Seen::new).problems++;
                }
            }
        }
        Seen appCenter = seen.get(APP_CENTER);
        if (appCenter != null && appCenter.problems == 0 && seen.size() > 1) {
            seen.remove(APP_CENTER);
        }
        List<Seen> list = new ArrayList<>(seen.values());
        list.sort((a, b) -> a.problems != b.problems ? b.problems - a.problems : b.count - a.count);
        return list;
    }

    static String[] appOf(String url) {
        if (url == null || RESOURCE.matcher(url).find()) {
            return null;
        }
        for (Pattern p : PATHS) {
            Matcher m = p.matcher(url);
            if (m.find()) {
                return new String[]{m.group(1), m.groupCount() > 1 ? m.group(2) : null};
            }
        }
        Matcher m = PROCESS.matcher(url);
        if (m.find()) {
            return new String[]{m.group(1), m.group(2)};
        }
        m = APP_PARAM.matcher(url);
        if (m.find()) {
            Matcher v = VERSION_PARAM.matcher(url);
            return new String[]{m.group(1), v.find() ? v.group(1) : null};
        }
        return null;
    }

    static List<String> urls(Path session, Path systemLog) {
        List<String> urls = new ArrayList<>();
        addUrls(urls, session, "network");
        addUrls(urls, systemLog, "entries");
        List<String> decoded = new ArrayList<>(urls.size());
        for (String u : urls) {
            decoded.add(decode(u));
        }
        return decoded;
    }

    private static String decode(String u) {
        if (u == null) {
            return null;
        }
        try {
            return URLDecoder.decode(u.replace("+", "%2B"), "UTF-8");
        } catch (IllegalArgumentException | java.io.UnsupportedEncodingException e) {
            return u;
        }
    }

    private static final java.util.Set<String> URL_FIELDS = new java.util.HashSet<>(java.util.Arrays.asList("url"));
    private static final java.util.Set<String> NETWORK_FIELDS = new java.util.HashSet<>(java.util.Arrays.asList("t", "url", "type", "status", "failed", "requestHeaders"));
    private static final java.util.Set<String> CONSOLE_FIELDS = new java.util.HashSet<>(java.util.Arrays.asList("t", "level"));
    private static final java.util.Set<String> LOG_FIELDS = new java.util.HashSet<>(java.util.Arrays.asList("kind", "url", "status", "error", "level", "req", "other"));

    static final int MAX_URL_CHARS = 8 * 1024;
    static final int MAX_HITS = 200_000;

    private static String cutUrl(String u) {
        return u.length() > MAX_URL_CHARS ? u.substring(0, MAX_URL_CHARS) : u;
    }

    private static void addUrls(List<String> urls, Path file, String key) {
        try {
            SessionFile.each(file, key, URL_FIELDS, e -> {
                if (!e.optString("url").isEmpty() && urls.size() < MAX_HITS) {
                    urls.add(cutUrl(e.optString("url")));
                }
            });
        } catch (IOException e) {
        }
    }

    static List<Hit> hits(Path session, Path systemLog) {
        List<Hit> hits = new ArrayList<>();
        java.util.TreeMap<Long, String> pages = new java.util.TreeMap<>();
        try {
            SessionFile.each(session, "network", NETWORK_FIELDS, n -> {
                if (n.optString("url").isEmpty() || hits.size() >= MAX_HITS) {
                    return;
                }
                String url = decode(cutUrl(n.optString("url")));
                String referer = header(n.optJSONObject("requestHeaders"), "referer");
                String page = "Document".equals(n.optString("type")) ? url : decode(referer != null ? cutUrl(referer) : null);
                if (page != null && page.contains("/web/")) {
                    pages.put(n.optLong("t"), page);
                }
                hits.add(new Hit(url, page, n.optBoolean("failed") || n.optInt("status") >= 400, true));
            });
            SessionFile.each(session, "console", CONSOLE_FIELDS, c -> {
                if ("error".equals(c.optString("level")) && hits.size() < MAX_HITS) {
                    Map.Entry<Long, String> page = pages.floorEntry(c.optLong("t"));
                    hits.add(new Hit(null, page != null ? page.getValue() : null, true, true));
                }
            });
            SessionFile.each(systemLog, "entries", LOG_FIELDS, e -> {
                if (e.optBoolean("other") || hits.size() >= MAX_HITS) {
                    return;
                }
                if ("request".equals(e.optString("kind")) && !e.optString("url").isEmpty()) {
                    hits.add(new Hit(decode(cutUrl(e.optString("url"))), null, e.has("error") || e.optInt("status") >= 500, false));
                } else if (e.optString("level").matches("ERROR|FATAL") && e.optString("req").contains(" ")) {
                    String req = cutUrl(e.optString("req"));
                    hits.add(new Hit(null, decode(req.substring(req.indexOf(' ') + 1)), true, false));
                }
            });
        } catch (IOException e) {
        }
        return hits;
    }

    private static String header(JSONObject headers, String name) {
        if (headers != null) {
            for (java.util.Iterator<?> it = headers.keys(); it.hasNext();) {
                String k = String.valueOf(it.next());
                if (k.equalsIgnoreCase(name)) {
                    return headers.optString(k);
                }
            }
        }
        return null;
    }

    interface Exporter {
        AppDefinition export(String appId, String version, OutputStream out) throws IOException;
    }

    static class Denied extends IOException {
        Denied(String reason) {
            super(reason);
        }
    }

    static Exporter joget() {
        return joget(null);
    }

    static AppDefinition resolve(String appId, String version) {
        AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
        AppDefinition def = version != null ? appService.loadAppDefinition(appId, version) : appService.getPublishedAppDefinition(appId);
        if (def == null) {
            def = appService.loadAppDefinition(appId, null);
        }
        return def;
    }

    static String key(AppDefinition def) {
        return def.getAppId() + "@" + def.getVersion();
    }

    static Exporter joget(java.util.function.Function<AppDefinition, String> deny) {
        AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
        return (appId, version, out) -> {
            AppDefinition def = resolve(appId, version);
            if (def == null) {
                return null;
            }
            String why = deny != null ? deny.apply(def) : null;
            if (why != null) {
                throw new Denied(why);
            }
            appService.exportApp(def.getAppId(), String.valueOf(def.getVersion()), out);
            return def;
        };
    }

    interface AfterExport {
        JSONObject run(Path jwa, AppDefinition def) throws IOException;
    }

    static JSONArray save(Path recordingDir, List<Seen> apps, Exporter exporter) throws IOException {
        return save(recordingDir, apps, exporter, null, MAX_APPS);
    }

    static JSONArray save(Path recordingDir, List<Seen> apps, Exporter exporter, AfterExport after, int max) throws IOException {
        JSONArray list = new JSONArray();
        JSONArray summary = new JSONArray();
        if (apps.isEmpty()) {
            return summary;
        }
        if (!Files.isDirectory(recordingDir)) {
            throw new IOException("Gone: " + recordingDir.getFileName());
        }
        Path dir = recordingDir.resolve(DIR);
        Files.createDirectories(dir);
        try {
            int taken = 0;
            for (int i = 0; i < apps.size(); i++) {
                Seen s = apps.get(i);
                JSONObject a = new JSONObject().put("id", s.appId).put("requests", s.count);
                if (s.version != null) {
                    a.put("urlVersion", s.version);
                }
                if (s.problems > 0) {
                    a.put("problems", s.problems);
                }
                if (max > 0 && taken >= max) {
                    list.put(a.put("state", "skipped").put("reason", "Only " + max + " apps are exported (apps with problems first, "
                            + "then the most used): plugin setting \"Most apps to export\"."));
                    continue;
                }
                Path tmp = dir.resolve(s.appId + ".part");
                taken++;
                try {
                    AppDefinition def;
                    try (OutputStream out = Files.newOutputStream(tmp)) {
                        def = exporter.export(s.appId, s.version, out);
                    }
                    if (def == null || Files.size(tmp) == 0) {
                        list.put(a.put("state", "missing").put("reason", "No such app on this server."));
                        continue;
                    }
                    String file = s.appId + "-" + def.getVersion() + ".jwa";
                    Files.move(tmp, dir.resolve(file), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    if (after != null) {
                        try {
                            JSONObject extra = after.run(dir.resolve(file), def);
                            for (java.util.Iterator<?> it = extra != null ? extra.keys() : java.util.Collections.emptyIterator(); it.hasNext();) {
                                String k = String.valueOf(it.next());
                                a.put(k, extra.get(k));
                            }
                        } catch (Exception | LinkageError e) {
                            LogUtil.warn(AppExport.class.getName(), "Could not add sample data to app " + s.appId + ": " + e);
                            a.put("sample", new JSONObject().put("error", String.valueOf(e.getMessage())));
                        }
                    }
                    a.put("state", "saved").put("file", file).put("name", def.getName()).put("version", def.getVersion())
                            .put("published", def.isPublished()).put("bytes", Files.size(dir.resolve(file)));
                    if (def.getDateModified() != null) {
                        a.put("modified", def.getDateModified().getTime());
                    }
                    list.put(a);
                    JSONObject one = new JSONObject().put("id", s.appId).put("name", def.getName()).put("version", def.getVersion());
                    if (s.problems > 0) {
                        one.put("problems", s.problems);
                    }
                    summary.put(one);
                } catch (Denied e) {
                    taken--;
                    list.put(a.put("state", "denied").put("reason", e.getMessage()));
                } catch (Exception | LinkageError e) {
                    LogUtil.warn(AppExport.class.getName(), "Could not export app " + s.appId + ": " + e);
                    list.put(a.put("state", "failed").put("reason", String.valueOf(e.getMessage())));
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }
            JSONObject body = new JSONObject().put("exportedAt", System.currentTimeMillis()).put("apps", list);
            Files.write(dir.resolve(LIST), body.toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return summary;
    }

    static List<Path> files(Path recordingDir) throws IOException {
        List<Path> files = new ArrayList<>();
        Path dir = recordingDir.resolve(DIR);
        if (Files.isDirectory(dir)) {
            try (java.nio.file.DirectoryStream<Path> all = Files.newDirectoryStream(dir, "*.jwa")) {
                for (Path f : all) {
                    if (file(recordingDir, f.getFileName().toString()) != null) {
                        files.add(f);
                    }
                }
            }
        }
        return files;
    }

    static Path list(Path recordingDir) {
        Path f = recordingDir.resolve(DIR).resolve(LIST);
        return Files.isRegularFile(f) ? f : null;
    }

    static Path file(Path recordingDir, String name) {
        if (name == null || !name.matches(FILE_NAME)) {
            return null;
        }
        Path f = recordingDir.resolve(DIR).resolve(name);
        return Files.isRegularFile(f) ? f : null;
    }
}
