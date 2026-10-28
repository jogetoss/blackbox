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

/**
 * The Joget apps a recording used, exported at upload with Joget's own app export (the same .jwa as App Composer's
 * Export, without form data, plugins or groups). Apps are found in the recorded URLs: userview, embed, form/datalist,
 * builder and JSON API paths, appId / appVersion parameters and process definition ids (appId#version#process).
 * Apps with problems (failed requests, server errors, console errors on their pages) come first, then the most used.
 * Saved as app/&lt;appId&gt;-&lt;version&gt;.jwa plus app/apps.json (what was found and exported, or why not).
 * App definitions can hold plugin settings and environment variables, so they count as server data.
 */
final class AppExport {

    static final String DIR = "app";
    static final String LIST = "apps.json";
    static final int MAX_APPS = 5;
    static final String FILE_NAME = "[A-Za-z0-9_-]+-\\d+\\.jwa";

    /** Joget's own App Center: usually just the way into the app in question, so kept only when it had problems or was the only app. */
    static final String APP_CENTER = "appcenter";

    private static final String ID = "([A-Za-z0-9_-]+)";
    private static final Pattern[] PATHS = {
        // /web/userview/app/userview/..., /web/embed/userview/..., /web/ulogin/..., mobile
        Pattern.compile("/web/(?:embed/)?(?:userview|ulogin|mobile|mapp)/" + ID + "(?:/|$|\\?)"),
        // /web/app/app/1/form/..., /web/client/app/app/1/..., /web/console/app/app/1/..., /web/json/app/app/1/...
        Pattern.compile("/web/(?:client/|console/|json/(?:console/)?)?app/" + ID + "/(\\d+|latest|_)?"),
        Pattern.compile("/web/json/data/(?:form|list|app)/(?:\\w+/)?" + ID + "/(\\d+)/"),
    };
    /** App icons and userview screenshots (App Center loads them for every app): files, not the app in use. */
    private static final Pattern RESOURCE = Pattern.compile("/web/(?:app/[A-Za-z0-9_-]+/(?:\\d+/)?resources/|userview/screenshot/)");
    private static final Pattern PROCESS = Pattern.compile("(?:^|[/=&?])" + ID + "(?:#|%23)(\\d+|latest)(?:#|%23)\\w+");
    private static final Pattern APP_PARAM = Pattern.compile("[?&]appId=" + ID + "(?:&|$)");
    private static final Pattern VERSION_PARAM = Pattern.compile("[?&]appVersion=(\\d+)(?:&|$)");

    private AppExport() {
    }

    /** One app seen in the recording: its id, the version a URL named (null: the published one), how often seen. */
    static final class Seen {
        final String appId;
        String version;
        int count;
        int problems;

        Seen(String appId) {
            this.appId = appId;
        }
    }

    /**
     * One recorded thing that can name an app: a request (url), or a problem on a page (console error: page only).
     * page is the page the browser showed (a document's own URL, else the request's Referer); browser is false for
     * the server's system log, which can also hold requests of other tabs (service worker fetches carry no tab).
     */
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

    /** The apps in these browser URLs, most used first. */
    static List<Seen> find(Iterable<String> urls) {
        List<Hit> hits = new ArrayList<>();
        for (String url : urls) {
            hits.add(new Hit(url, null, false, true));
        }
        return find(hits);
    }

    /**
     * The apps of these hits: apps with problems first, then the most used. Apps come from the browser's requests; the
     * system log only adds to them (or names the apps when the browser recorded none). App Center is kept only when it
     * had problems or was the only app.
     */
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
                    s.version = app[1]; // the first numbered version wins; "latest" and "_" mean the published one
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

    /** {appId, version or null} of the app a URL names, or null. */
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

    /** URLs of the recording: the browser's requests in session.json and the server's in the system log. */
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

    private static void addUrls(List<String> urls, Path file, String key) {
        JSONArray list = array(file, key);
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject e = list.optJSONObject(i);
            if (e != null && !e.optString("url").isEmpty()) {
                urls.add(e.optString("url"));
            }
        }
    }

    private static JSONArray array(Path file, String key) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return new JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).optJSONArray(key);
        } catch (IOException | JSONException e) {
            return null; // unreadable: no apps from it
        }
    }

    /**
     * The hits of a recording: the browser's requests (failed or 4xx/5xx: a problem) and console errors (a problem on
     * the page shown then), then the system log's requests (5xx: a problem) and error lines (a problem on their
     * request). Other server threads' lines are left out.
     */
    static List<Hit> hits(Path session, Path systemLog) {
        List<Hit> hits = new ArrayList<>();
        JSONArray network = array(session, "network");
        java.util.TreeMap<Long, String> pages = new java.util.TreeMap<>(); // t -> page shown from then on
        for (int i = 0; network != null && i < network.length(); i++) {
            JSONObject n = network.optJSONObject(i);
            if (n == null || n.optString("url").isEmpty()) {
                continue;
            }
            String url = decode(n.optString("url"));
            String page = "Document".equals(n.optString("type")) ? url : decode(header(n.optJSONObject("requestHeaders"), "referer"));
            if (page != null && page.contains("/web/")) {
                pages.put(n.optLong("t"), page);
            }
            hits.add(new Hit(url, page, n.optBoolean("failed") || n.optInt("status") >= 400, true));
        }
        JSONArray console = array(session, "console");
        for (int i = 0; console != null && i < console.length(); i++) {
            JSONObject c = console.optJSONObject(i);
            if (c != null && "error".equals(c.optString("level"))) {
                Map.Entry<Long, String> page = pages.floorEntry(c.optLong("t"));
                hits.add(new Hit(null, page != null ? page.getValue() : null, true, true));
            }
        }
        JSONArray log = array(systemLog, "entries");
        for (int i = 0; log != null && i < log.length(); i++) {
            JSONObject e = log.optJSONObject(i);
            if (e == null || e.optBoolean("other")) {
                continue;
            }
            if ("request".equals(e.optString("kind")) && !e.optString("url").isEmpty()) {
                hits.add(new Hit(decode(e.optString("url")), null, e.has("error") || e.optInt("status") >= 500, false));
            } else if (e.optString("level").matches("ERROR|FATAL") && e.optString("req").contains(" ")) {
                // req is "METHOD /path": a problem on that request's app, not another request of it
                hits.add(new Hit(null, decode(e.optString("req").substring(e.optString("req").indexOf(' ') + 1)), true, false));
            }
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

    /** Exports one app version into out; returns its definition, or null when there is no such app. */
    interface Exporter {
        AppDefinition export(String appId, String version, OutputStream out) throws IOException;
    }

    /** An app the user may not have exported: listed as denied with the reason, and not counted against the cap. */
    static class Denied extends IOException {
        Denied(String reason) {
            super(reason);
        }
    }

    /** Joget's own export: the published version when the URLs named none, else the latest. */
    static Exporter joget() {
        return joget(null);
    }

    /** The version an export takes: the one the URLs named, else the published one, else the latest. Null: no app. */
    static AppDefinition resolve(String appId, String version) {
        AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
        AppDefinition def = version != null ? appService.loadAppDefinition(appId, version) : appService.getPublishedAppDefinition(appId);
        if (def == null) {
            def = appService.loadAppDefinition(appId, null); // never published: the latest
        }
        return def;
    }

    /** The key of an access decision: the app and the version it resolved to. */
    static String key(AppDefinition def) {
        return def.getAppId() + "@" + def.getVersion();
    }

    /** As {@link #joget()}; deny gives the reason an app's definition may not be exported (null: allowed). */
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

    /** Runs on each saved .jwa (e.g. adds sample data); returns keys to add to the app's apps.json entry, or null. */
    interface AfterExport {
        JSONObject run(Path jwa, AppDefinition def) throws IOException;
    }

    static JSONArray save(Path recordingDir, List<Seen> apps, Exporter exporter) throws IOException {
        return save(recordingDir, apps, exporter, null, MAX_APPS);
    }

    /**
     * Exports the apps (at most max, 0: all) into recordingDir/app/ and writes apps.json. Returns the summary kept in
     * meta.json: [{id, name, version, problems}] of the exported ones.
     */
    static JSONArray save(Path recordingDir, List<Seen> apps, Exporter exporter, AfterExport after, int max) throws IOException {
        JSONArray list = new JSONArray();
        JSONArray summary = new JSONArray();
        if (apps.isEmpty()) {
            return summary;
        }
        Path dir = recordingDir.resolve(DIR);
        Files.createDirectories(dir);
        try {
            int taken = 0; // apps tried, against the cap: denied ones don't count
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

    /** The recording's saved .jwa files. */
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

    /** The recording's apps.json, or null. */
    static Path list(Path recordingDir) {
        Path f = recordingDir.resolve(DIR).resolve(LIST);
        return Files.isRegularFile(f) ? f : null;
    }

    /** A saved .jwa of this recording, or null (only names apps.json lists, so no path tricks). */
    static Path file(Path recordingDir, String name) {
        if (name == null || !name.matches(FILE_NAME)) {
            return null;
        }
        Path f = recordingDir.resolve(DIR).resolve(name);
        return Files.isRegularFile(f) ? f : null;
    }
}
