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
 * Saved as app/&lt;appId&gt;-&lt;version&gt;.jwa plus app/apps.json (what was found and exported, or why not).
 * App definitions can hold plugin settings and environment variables, so they count as server data.
 */
final class AppExport {

    static final String DIR = "app";
    static final String LIST = "apps.json";
    static final int MAX_APPS = 5;
    static final String FILE_NAME = "[A-Za-z0-9_-]+-\\d+\\.jwa";

    /** Joget's own apps: in most recordings and rarely what the issue is about. */
    private static final java.util.Set<String> SKIPPED = java.util.Collections.singleton("appcenter");

    private static final String ID = "([A-Za-z0-9_-]+)";
    private static final Pattern[] PATHS = {
        // /web/userview/app/userview/..., /web/embed/userview/..., /web/ulogin/..., mobile
        Pattern.compile("/web/(?:embed/)?(?:userview|ulogin|mobile|mapp)/" + ID + "(?:/|$|\\?)"),
        // /web/app/app/1/form/..., /web/client/app/app/1/..., /web/console/app/app/1/..., /web/json/app/app/1/...
        Pattern.compile("/web/(?:client/|console/|json/(?:console/)?)?app/" + ID + "/(\\d+|latest|_)?"),
        Pattern.compile("/web/json/data/(?:form|list|app)/(?:\\w+/)?" + ID + "/(\\d+)/"),
    };
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

        Seen(String appId) {
            this.appId = appId;
        }
    }

    /** The apps in these URLs, most used first, Joget's own left out. */
    static List<Seen> find(Iterable<String> urls) {
        Map<String, Seen> seen = new LinkedHashMap<>();
        for (String url : urls) {
            if (url == null) {
                continue;
            }
            for (Pattern p : PATHS) {
                Matcher m = p.matcher(url);
                if (m.find()) {
                    note(seen, m.group(1), m.groupCount() > 1 ? m.group(2) : null);
                }
            }
            Matcher m = PROCESS.matcher(url);
            while (m.find()) {
                note(seen, m.group(1), m.group(2));
            }
            m = APP_PARAM.matcher(url);
            if (m.find()) {
                Matcher v = VERSION_PARAM.matcher(url);
                note(seen, m.group(1), v.find() ? v.group(1) : null);
            }
        }
        List<Seen> list = new ArrayList<>(seen.values());
        list.sort((a, b) -> b.count - a.count);
        return list;
    }

    private static void note(Map<String, Seen> seen, String appId, String version) {
        if (SKIPPED.contains(appId)) {
            return;
        }
        Seen s = seen.computeIfAbsent(appId, Seen::new);
        s.count++;
        if (version != null && version.matches("\\d+") && s.version == null) {
            s.version = version; // the first numbered version wins; "latest" and "_" mean the published one
        }
    }

    /** URLs of the recording: the browser's requests in session.json and the server's in the system log. */
    static List<String> urls(Path session, Path systemLog) {
        List<String> urls = new ArrayList<>();
        addUrls(urls, session, "network");
        addUrls(urls, systemLog, "entries");
        List<String> decoded = new ArrayList<>(urls.size());
        for (String u : urls) {
            try {
                decoded.add(URLDecoder.decode(u.replace("+", "%2B"), "UTF-8"));
            } catch (IllegalArgumentException | java.io.UnsupportedEncodingException e) {
                decoded.add(u);
            }
        }
        return decoded;
    }

    private static void addUrls(List<String> urls, Path file, String key) {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            JSONArray list = new JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).optJSONArray(key);
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject e = list.optJSONObject(i);
                if (e != null && !e.optString("url").isEmpty()) {
                    urls.add(e.optString("url"));
                }
            }
        } catch (IOException | JSONException e) {
            // unreadable: no apps from it
        }
    }

    /** Exports one app version into out; returns its definition, or null when there is no such app. */
    interface Exporter {
        AppDefinition export(String appId, String version, OutputStream out) throws IOException;
    }

    /** Joget's own export: the published version when the URLs named none, else the latest. */
    static Exporter joget() {
        AppService appService = (AppService) AppUtil.getApplicationContext().getBean("appService");
        return (appId, version, out) -> {
            AppDefinition def = version != null ? appService.loadAppDefinition(appId, version) : appService.getPublishedAppDefinition(appId);
            if (def == null) {
                def = appService.loadAppDefinition(appId, null); // never published: the latest
            }
            if (def == null) {
                return null;
            }
            appService.exportApp(def.getAppId(), String.valueOf(def.getVersion()), out);
            return def;
        };
    }

    /** Runs on each saved .jwa (e.g. adds sample data); returns what to note in apps.json as "sample", or null. */
    interface AfterExport {
        JSONObject run(Path jwa, AppDefinition def) throws IOException;
    }

    static JSONArray save(Path recordingDir, List<Seen> apps, Exporter exporter) throws IOException {
        return save(recordingDir, apps, exporter, null);
    }

    /**
     * Exports the apps (at most MAX_APPS) into recordingDir/app/ and writes apps.json. Returns the summary kept in
     * meta.json: [{id, name, version}] of the exported ones.
     */
    static JSONArray save(Path recordingDir, List<Seen> apps, Exporter exporter, AfterExport after) throws IOException {
        JSONArray list = new JSONArray();
        JSONArray summary = new JSONArray();
        if (apps.isEmpty()) {
            return summary;
        }
        Path dir = recordingDir.resolve(DIR);
        Files.createDirectories(dir);
        try {
            for (int i = 0; i < apps.size(); i++) {
                Seen s = apps.get(i);
                JSONObject a = new JSONObject().put("id", s.appId).put("requests", s.count);
                if (s.version != null) {
                    a.put("urlVersion", s.version);
                }
                if (i >= MAX_APPS) {
                    list.put(a.put("state", "skipped").put("reason", "Only the " + MAX_APPS + " most used apps are exported."));
                    continue;
                }
                Path tmp = dir.resolve(s.appId + ".part");
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
                            JSONObject sample = after.run(dir.resolve(file), def);
                            if (sample != null) {
                                a.put("sample", sample);
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
                    summary.put(new JSONObject().put("id", s.appId).put("name", def.getName()).put("version", def.getVersion()));
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
