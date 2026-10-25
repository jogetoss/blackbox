package org.joget.marketplace.blackbox;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.userview.model.Permission;
import org.joget.commons.util.LogUtil;
import org.joget.commons.util.SecurityUtil;
import org.joget.commons.util.SetupManager;
import org.joget.directory.model.User;
import org.joget.plugin.base.ExtDefaultPlugin;
import org.joget.plugin.base.PluginManager;
import org.joget.plugin.base.SystemConfigurablePlugin;
import org.joget.workflow.model.service.WorkflowUserManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Stores recordings made by the BlackBox Chrome extension (recording.webm + session.json).
 *
 * Settings: Settings > Manage Plugins > BlackBox > Configure (SystemConfigurablePlugin).
 * API:      /api/blackbox?action=... (served by {@link BlackBoxApiFilter}, used by the extension and the pages)
 * Pages:    /blackbox/ (served by {@link BlackBoxPageFilter})
 */
public class BlackBoxPlugin extends ExtDefaultPlugin implements SystemConfigurablePlugin {

    static final String VERSION = "8.2-SNAPSHOT";
    private static final long DEFAULT_MAX_MB = 500;
    private static final long DEFAULT_SYSTEM_LOG_MB = 20;
    private static final long DEFAULT_KEEP_DAYS = 7;
    private static final Pattern RANGE = Pattern.compile("^bytes=(\\d*)-(\\d*)$");
    private static final long RETRY_COOLDOWN_MS = 20 * 1000L;
    private static final Map<String, Long> RETRIED_AT = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "BlackBox";
    }

    @Override
    public String getVersion() {
        return VERSION;
    }

    @Override
    public String getDescription() {
        return "Stores and replays browser recordings (video, console and network log)";
    }

    @Override
    public String getLabel() {
        return "BlackBox";
    }

    @Override
    public String getClassName() {
        return getClass().getName();
    }

    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(getClassName(), "/properties/blackboxPlugin.json", null, true, null);
    }

    static RecordingStore store() {
        return new RecordingStore(Paths.get(SetupManager.getBaseDirectory(), "blackbox"));
    }

    // ---------- API ----------

    /** Handles /api/blackbox. Spring Security has run, so the current user is known. */
    public void handle(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String action = request.getParameter("action");
        boolean post = "POST".equalsIgnoreCase(request.getMethod());
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");

        WorkflowUserManager wum = (WorkflowUserManager) AppUtil.getApplicationContext().getBean("workflowUserManager");
        User user = wum.isCurrentUserAnonymous() ? null : wum.getCurrentUser();
        if (user == null) {
            error(response, HttpServletResponse.SC_UNAUTHORIZED, "Log in first.");
            return;
        }
        // POSTs come from the extension (an Origin no web page can send) or from the pages (CSRF token)
        String origin = request.getHeader("Origin");
        boolean fromExtension = origin != null && origin.startsWith("chrome-extension://");
        if (post && !fromExtension && !validCsrfToken(request)) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "Invalid or missing CSRF token. Reload the page and try again.");
            return;
        }

        Access access = new Access(request, user, wum.isCurrentUserInRole(WorkflowUserManager.ROLE_ADMIN));
        RecordingStore store = store();
        String id = request.getParameter("id");
        try {
            if ("me".equals(action) && !post) {
                json(response, new JSONObject()
                        .put("user", new JSONObject().put("username", user.getUsername()).put("name", fullName(user)))
                        .put("canUpload", access.canUpload())
                        .put("maxSizeMb", maxSizeMb())
                        .put("sampleData", new JSONObject() // what the upload form may offer
                                .put("enabled", !"false".equals(getPropertyString("includeAppExport"))
                                        && !"false".equals(getPropertyString("sampleData")))
                                .put("real", "true".equals(getPropertyString("allowRealSampleData")) && access.canViewSystemLog())));
            } else if ("list".equals(action) && !post) {
                list(response, store, access);
            } else if ("create".equals(action) && post) {
                create(request, response, store, access);
            } else if ("video".equals(action) && post) {
                uploadVideo(request, response, store, access, id);
            } else if ("delete".equals(action) && post) {
                delete(response, store, access, id);
            } else if ("meta".equals(action) && !post) {
                JSONObject meta = viewable(response, store, access, id);
                if (meta != null) {
                    meta.put("canDelete", access.canDelete(meta));
                    meta.put("canViewSystemLog", access.canViewSystemLog(meta));
                    if (!access.canViewSystemLog(meta)) {
                        meta.remove("apps");
                    }
                    json(response, meta);
                }
            } else if ("systemlog".equals(action) && !post) {
                if (viewable(response, store, access, id) != null) {
                    if (!access.canViewSystemLog(store.readMeta(id))) {
                        error(response, HttpServletResponse.SC_FORBIDDEN, "You are not allowed to view system logs.");
                    } else {
                        sendFile(request, response, store.file(id, RecordingStore.SYSTEM_LOG), "application/json;charset=UTF-8", id + "-system-log.json");
                    }
                }
            } else if ("download".equals(action) && !post) {
                // The whole recording as one file: format=zip (to import elsewhere) or html (replays anywhere)
                String format = request.getParameter("format");
                if (!"zip".equals(format) && !"html".equals(format)) {
                    error(response, HttpServletResponse.SC_BAD_REQUEST, "Unknown format: use zip or html.");
                } else {
                    JSONObject meta = viewable(response, store, access, id);
                    if (meta != null && "zip".equals(format)) {
                        downloadZip(response, store, access, id, meta);
                    } else if (meta != null) {
                        downloadHtml(response, store, access, id, meta);
                    }
                }
            } else if ("import".equals(action) && post) {
                importZip(request, response, store, access);
            } else if ("recording-start".equals(action) && post) {
                startSystemLog(request, response, access, id);
            } else if ("recording-stop".equals(action) && post) {
                stopSystemLog(response, access, id);
            } else if ("traces".equals(action) && !post) {
                traces(response, store, access, id);
            } else if ("trace".equals(action) && !post) {
                trace(request, response, store, access, id);
            } else if ("serverinfo".equals(action) && !post) {
                if (viewable(response, store, access, id) != null) {
                    if (!access.canViewSystemLog(store.readMeta(id))) {
                        error(response, HttpServletResponse.SC_FORBIDDEN, "You are not allowed to view system logs.");
                    } else {
                        sendFile(request, response, store.file(id, ServerInfo.FILE), "application/json;charset=UTF-8", id + "-server-info.json");
                    }
                }
            } else if ("apps".equals(action) && !post) {
                if (viewable(response, store, access, id) != null) {
                    if (!access.canViewSystemLog(store.readMeta(id))) {
                        error(response, HttpServletResponse.SC_FORBIDDEN, "You are not allowed to view server data.");
                    } else {
                        sendFile(request, response, AppExport.list(store.dir(id)), "application/json;charset=UTF-8", id + "-apps.json");
                    }
                }
            } else if ("appfile".equals(action) && !post) {
                if (viewable(response, store, access, id) != null) {
                    if (!access.canViewSystemLog(store.readMeta(id))) {
                        error(response, HttpServletResponse.SC_FORBIDDEN, "You are not allowed to view server data.");
                    } else {
                        Path f = AppExport.file(store.dir(id), request.getParameter("file"));
                        sendFile(request, response, f, "application/zip", f != null ? f.getFileName().toString() : "app.jwa");
                    }
                }
            } else if ("gauges".equals(action) && !post) {
                gauges(response, store, access, id);
            } else if ("glowroot".equals(action) && !post) {
                glowrootData(request, response, store, access, id);
            } else if ("trace-retry".equals(action) && post) {
                retryTraces(request, response, store, access, id);
            } else if ("session".equals(action) && !post) {
                if (viewable(response, store, access, id) != null) {
                    sendFile(request, response, store.file(id, RecordingStore.SESSION), "application/json;charset=UTF-8", id + "-session.json");
                }
            } else if ("video".equals(action) && !post) {
                if (viewable(response, store, access, id) != null) {
                    sendFile(request, response, store.file(id, RecordingStore.VIDEO), "video/webm", id + "-recording.webm");
                }
            } else {
                error(response, HttpServletResponse.SC_BAD_REQUEST, "Unknown action.");
            }
        } catch (RecordingStore.InvalidUpload e) {
            error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (JSONException e) {
            LogUtil.error(getClassName(), e, "BlackBox request failed");
            error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Server error.");
        }
    }

    private void list(HttpServletResponse response, RecordingStore store, Access access) throws IOException, JSONException {
        JSONArray rows = new JSONArray();
        for (JSONObject meta : store.list()) {
            if (access.canView(meta)) {
                meta.put("canDelete", access.canDelete(meta));
                boolean server = access.canViewSystemLog(meta);
                meta.put("canViewSystemLog", server);
                if (!server) {
                    meta.remove("systemLog"); // its error count is server data too
                    meta.remove("serverData");
                    meta.remove("apps");
                }
                rows.put(meta);
            }
        }
        JSONObject result = new JSONObject();
        result.put("user", new JSONObject().put("username", access.user.getUsername()).put("name", fullName(access.user)));
        result.put("canUpload", access.canUpload());
        result.put("maxSizeMb", maxSizeMb());
        result.put("recordings", rows);
        json(response, result);
    }

    private void create(HttpServletRequest request, HttpServletResponse response, RecordingStore store, Access access)
            throws IOException, JSONException, RecordingStore.InvalidUpload {
        if (!access.canUpload()) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "You are not allowed to upload recordings.");
            return;
        }
        long max = maxSizeMb() * 1024 * 1024;
        if (request.getContentLengthLong() > max) {
            throw new RecordingStore.InvalidUpload("session.json is larger than the limit (" + maxSizeMb() + " MB).");
        }
        JSONObject meta;
        try (InputStream in = request.getInputStream()) {
            meta = store.create(in, max, access.user.getUsername(), fullName(access.user),
                    request.getParameter("title"), request.getParameter("notes"));
        }
        String recId = meta.getString("id");
        String systemLogId = (String) meta.remove("systemLogId");
        if (systemLogId != null) {
            meta.put("systemLog", attachSystemLog(store, recId, systemLogId, access.user.getUsername()));
            if ("attached".equals(meta.getJSONObject("systemLog").optString("status"))) {
                try {
                    meta.put("serverData", ServerData.of(store.dir(recId), slowMs()));
                } catch (IOException e) {
                    LogUtil.warn(getClassName(), "Could not sum up the server data of recording " + recId + ": " + e.getMessage());
                }
            }
            store.updateMeta(recId, meta);
        }
        if (!"false".equals(getPropertyString("includeAppExport"))) {
            exportApps(store, recId, meta, sampleMode(request, access, meta));
        }
        LogUtil.info(getClassName(), "Recording " + recId + " uploaded by " + access.user.getUsername());
        // url: where the extension's "Open uploaded" link points
        meta.put("url", request.getContextPath() + "/blackbox/view?id=" + meta.getString("id"));
        json(response, meta);
    }

    /**
     * Sample data for this upload, or null for none. The upload's sampleData parameter (none, masked, real) picks
     * within what the settings allow: only for uploaders who may see the recording's server data; real values only
     * for those allowed by "Who can view system logs" (not the own-recordings rule) and when the settings allow them.
     */
    private SampleData.Mode sampleMode(HttpServletRequest request, Access access, JSONObject meta) {
        String asked = request.getParameter("sampleData");
        if ("false".equals(getPropertyString("sampleData")) || "none".equals(asked) || !access.canViewSystemLog(meta)) {
            return null;
        }
        boolean real = "real".equals(asked) && "true".equals(getPropertyString("allowRealSampleData")) && access.canViewSystemLog();
        return real ? SampleData.Mode.REAL : SampleData.Mode.MASKED;
    }

    /** The apps the recording used, exported next to it (AppExport); meta.apps lists them for the recordings list. */
    private void exportApps(RecordingStore store, String recId, JSONObject meta, SampleData.Mode sample) {
        try {
            Path session = store.file(recId, RecordingStore.SESSION);
            Path systemLog = store.file(recId, RecordingStore.SYSTEM_LOG);
            List<String> urls = AppExport.urls(session, systemLog);
            List<AppExport.Seen> apps = AppExport.find(AppExport.hits(session, systemLog));
            AppExport.AfterExport after = null;
            boolean keepIds = "true".equals(getPropertyString("sampleKeepIds"));
            Masker.IdMap ids = new Masker.IdMap(keepIds);
            if (sample != null) {
                List<String> texts = new java.util.ArrayList<>(urls);
                texts.addAll(SampleData.postData(session));
                java.util.Set<String> candidates = SampleData.candidateIds(texts);
                String sensitive = getPropertyString("sensitiveColumns");
                Masker masker = new Masker(sensitive == null || sensitive.trim().isEmpty() ? Masker.DEFAULT_SENSITIVE : sensitive);
                SampleData.Source src = SampleData.joget();
                ProcessData.Source psrc = ProcessData.joget();
                int newest = (int) Math.min(positive("sampleRows", 20), 500);
                after = (jwa, def) -> {
                    masker.setDefinition(SampleData.definitionText(jwa));
                    List<SampleData.Table> tables = SampleData.build(SampleData.tables(def), candidates, src, sample, masker, ids, newest);
                    SampleData.addTo(jwa, tables);
                    try {
                        JSONObject extra = new JSONObject().put("sample", SampleData.summary(tables, sample));
                        // Processes: recorded process / activity ids, and those in the sample rows (record ids, links)
                        java.util.Set<String> refs = new java.util.LinkedHashSet<>(candidates);
                        refs.addAll(ids.all().keySet());
                        refs.addAll(SampleData.valuesIn(tables));
                        JSONArray processes = ProcessData.collect(def.getAppId(), ProcessData.processIds(refs, psrc), psrc, sample,
                                masker, ids);
                        if (processes.length() > 0) {
                            extra.put("processes", processes);
                        }
                        return extra;
                    } catch (JSONException e) {
                        throw new IOException(e);
                    }
                };
            }
            String max = getPropertyString("maxApps");
            int maxApps = max != null && max.trim().matches("\\d{1,4}") ? Integer.parseInt(max.trim()) : AppExport.MAX_APPS;
            JSONArray saved = AppExport.save(store.dir(recId), apps, AppExport.joget(), after, maxApps);
            SampleData.writeIdMap(store.dir(recId), ids, keepIds);
            if (saved.length() > 0) {
                meta.put("apps", saved);
                store.updateMeta(recId, meta);
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            LogUtil.warn(getClassName(), "Could not export the apps of recording " + recId + ": " + e);
        }
    }

    private void uploadVideo(HttpServletRequest request, HttpServletResponse response, RecordingStore store, Access access, String id)
            throws IOException, RecordingStore.InvalidUpload {
        JSONObject meta = store.readMeta(id);
        // Only the uploader adds the video to their own recording
        if (meta == null || !access.user.getUsername().equals(meta.optString("owner"))) {
            error(response, HttpServletResponse.SC_NOT_FOUND, "Recording not found.");
            return;
        }
        long max = maxSizeMb() * 1024 * 1024;
        if (request.getContentLengthLong() > max) {
            throw new RecordingStore.InvalidUpload("The video is larger than the limit (" + maxSizeMb() + " MB).");
        }
        try (InputStream in = request.getInputStream()) {
            meta = store.writeVideo(id, in, max);
        }
        json(response, meta);
    }

    private void delete(HttpServletResponse response, RecordingStore store, Access access, String id) throws IOException, JSONException {
        JSONObject meta = store.readMeta(id);
        if (meta == null || !access.canView(meta)) {
            error(response, HttpServletResponse.SC_NOT_FOUND, "Recording not found.");
            return;
        }
        if (!access.canDelete(meta)) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "You are not allowed to delete this recording.");
            return;
        }
        store.delete(id);
        LogUtil.info(getClassName(), "Recording " + id + " deleted by " + access.user.getUsername());
        json(response, new JSONObject().put("deleted", id));
    }

    /**
     * One .html file that replays the recording in any browser (see {@link RecordingHtml}). The system log is included
     * only for users allowed to view it.
     */
    private void downloadHtml(HttpServletResponse response, RecordingStore store, Access access, String id, JSONObject meta)
            throws IOException {
        Path session = store.file(id, RecordingStore.SESSION);
        if (session == null) {
            error(response, HttpServletResponse.SC_NOT_FOUND, "File not found.");
            return;
        }
        Path systemLog = access.canViewSystemLog(meta) ? store.file(id, RecordingStore.SYSTEM_LOG) : null;
        if (systemLog == null) {
            meta.remove("systemLog"); // the summary would point at data that is not in the file
        }
        RecordingHtml export = RecordingHtml.fromResources();
        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"blackbox-" + id + ".html\"");
        String traces = null;
        if (systemLog != null) {
            JSONArray list = TraceStore.merge(store.dir(id), System.currentTimeMillis());
            traces = list.length() > 0 ? list.toString() : null;
        }
        JSONObject gauges = systemLog != null ? GaugeStore.merge(store.dir(id)) : null;
        Path info = systemLog != null ? store.file(id, ServerInfo.FILE) : null;
        Path apps = access.canViewSystemLog(meta) ? AppExport.list(store.dir(id)) : null;
        export.write(response.getOutputStream(), meta.toString(), session, systemLog, store.file(id, RecordingStore.VIDEO), traces,
                gauges != null ? gauges.toString() : null, info, apps);
        LogUtil.info(getClassName(), "Recording " + id + " downloaded as HTML by " + access.user.getUsername());
    }

    /** The recording as one ZIP for {@link #importZip} on another server. The system log only for users allowed to view it. */
    private void downloadZip(HttpServletResponse response, RecordingStore store, Access access, String id, JSONObject meta)
            throws IOException {
        Path session = store.file(id, RecordingStore.SESSION);
        if (session == null) {
            error(response, HttpServletResponse.SC_NOT_FOUND, "File not found.");
            return;
        }
        Path systemLog = access.canViewSystemLog(meta) ? store.file(id, RecordingStore.SYSTEM_LOG) : null;
        try {
            // the ZIP carries the Glowroot data for these users: wait until every node has built it
            if (systemLog != null && "building".equals(GlowrootData.overall(GlowrootData.statuses(store.dir(id))))) {
                error(response, HttpServletResponse.SC_CONFLICT, "The Glowroot data is still being built (about a minute after the"
                        + " recording stopped). Download the ZIP once it is ready.");
                return;
            }
        } catch (IOException e) {
            // unreadable statuses: the ZIP has whatever packages are there
        }
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"blackbox-" + id + ".zip\"");
        RecordingZip.write(response.getOutputStream(), meta, session, systemLog, store.file(id, RecordingStore.VIDEO),
                systemLog != null ? store.dir(id) : null, access.canViewSystemLog(meta) ? store.dir(id) : null);
    }

    /** A ZIP from {@link #downloadZip} (raw request body) becomes a new recording owned by the user. */
    private void importZip(HttpServletRequest request, HttpServletResponse response, RecordingStore store, Access access)
            throws IOException, JSONException, RecordingStore.InvalidUpload {
        if (!access.canUpload()) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "You are not allowed to upload recordings.");
            return;
        }
        long max = maxSizeMb() * 1024 * 1024;
        long maxZip = 2 * max + 64 * 1024 * 1024; // video + session.json + system log and meta
        if (request.getContentLengthLong() > maxZip) {
            throw new RecordingStore.InvalidUpload("The ZIP is larger than the limit.");
        }
        Path tmp = Files.createTempFile("blackbox-import-", ".zip");
        try {
            try (InputStream in = request.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                long size = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    size += n;
                    if (size > maxZip) {
                        throw new RecordingStore.InvalidUpload("The ZIP is larger than the limit.");
                    }
                    out.write(buf, 0, n);
                }
            }
            JSONObject meta = RecordingZip.importZip(store, tmp, max, access.canViewSystemLog(),
                    access.user.getUsername(), fullName(access.user));
            if (meta.has("systemLog")) {
                // the list's server data tags (traces, load, info, Glowroot data), as after an upload
                meta.put("serverData", ServerData.of(store.dir(meta.getString("id")), slowMs()));
                store.updateMeta(meta.getString("id"), meta);
            }
            LogUtil.info(getClassName(), "Recording " + meta.getString("id") + " imported by " + access.user.getUsername());
            meta.put("url", request.getContextPath() + "/blackbox/view?id=" + meta.getString("id"));
            json(response, meta);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ---------- system log ----------

    /** The extension starts capturing when a recording starts; id is its random UUID, sent on the tab's requests. */
    private void startSystemLog(HttpServletRequest request, HttpServletResponse response, Access access, String id)
            throws IOException, JSONException {
        if (!"true".equals(getPropertyString("captureSystemLogs"))) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "System log capture is turned off in the BlackBox plugin settings.");
            return;
        }
        if (!access.canUpload()) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "You are not allowed to upload recordings.");
            return;
        }
        if (!SystemLogStore.isValidId(id)) {
            error(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid system log id.");
            return;
        }
        long maxBytes = positive("systemLogMaxMb", DEFAULT_SYSTEM_LOG_MB) * 1024 * 1024;
        long keepMs = positive("systemLogKeepDays", DEFAULT_KEEP_DAYS) * 24 * 3600 * 1000;
        boolean traces = !"false".equals(getPropertyString("captureTraces"));
        boolean gauges = !"false".equals(getPropertyString("captureGauges"));
        boolean glowrootData = "true".equals(getPropertyString("captureGlowrootData"));
        // Other users' log lines: only for recorders who may see every recording's system logs
        boolean allServer = "true".equals(getPropertyString("captureAllServerLogs")) && access.canViewSystemLog();
        if (!SystemLogCapture.store().start(id, access.user.getUsername(), maxBytes, keepMs, traces, getPropertyString("glowrootUrl"), gauges,
                glowrootData, allServer)) {
            error(response, HttpServletResponse.SC_CONFLICT, "This system log id is already in use.");
            return;
        }
        SystemLogCapture.find(id); // this node samples the server load from now (GaugeSampler)
        ServerInfo.save(SystemLogCapture.store().dir(id), request, (int) Math.min(positive("recentChangesDays", 7), 365)); // System Info as it is now
        JSONObject started = new JSONObject().put("id", id).put("maxMb", maxBytes / (1024 * 1024))
                .put("traces", traces).put("gauges", gauges).put("glowrootData", glowrootData).put("allServer", allServer)
                .put("slowThresholdMs", slowMs());
        json(response, new JSONObject().put("systemLog", started));
    }

    /** "Slow" for the recordings lists: Glowroot's slow threshold (ServerData.slowMs), or 2 s without Glowroot. */
    private long slowMs() {
        GlowrootClient g = GlowrootClient.find(getPropertyString("glowrootUrl"));
        if (g == null) {
            return ServerData.DEFAULT_SLOW_MS;
        }
        try {
            return ServerData.slowMs(g.settings().mainThresholdMs);
        } catch (IOException e) {
            return ServerData.DEFAULT_SLOW_MS;
        }
    }

    private void stopSystemLog(HttpServletResponse response, Access access, String id) throws IOException, JSONException {
        if (!SystemLogStore.isValidId(id) || !SystemLogCapture.store().stop(id, access.user.getUsername())) {
            error(response, HttpServletResponse.SC_NOT_FOUND, "System log not found.");
            return;
        }
        SystemLogCapture.close(id);
        json(response, new JSONObject().put("stopped", id));
    }

    // ---------- Glowroot traces ----------

    /** The recording's traces (TraceStore.merge): the Traces tab polls this while some are pending. */
    private void traces(HttpServletResponse response, RecordingStore store, Access access, String id) throws IOException, JSONException {
        if (viewable(response, store, access, id) == null) {
            return;
        }
        if (!access.canViewSystemLog(store.readMeta(id))) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "You are not allowed to view system logs.");
            return;
        }
        JSONArray list = TraceStore.merge(store.dir(id), System.currentTimeMillis());
        json(response, new JSONObject().put("traces", list).put("pending", TraceStore.pending(list)));
    }

    /** The server load samples of each node (GaugeStore.merge), or {} when there are none. */
    private void gauges(HttpServletResponse response, RecordingStore store, Access access, String id) throws IOException {
        if (viewable(response, store, access, id) == null) {
            return;
        }
        if (!access.canViewSystemLog(store.readMeta(id))) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "You are not allowed to view system logs.");
            return;
        }
        JSONObject merged = GaugeStore.merge(store.dir(id));
        json(response, merged != null ? merged : new JSONObject());
    }

    /**
     * The Glowroot data packages (GlowrootPackager): without node, each node's status {nodes: [...]}; with node, that
     * node's ZIP. For the same users as the system log.
     */
    private void glowrootData(HttpServletRequest request, HttpServletResponse response, RecordingStore store, Access access, String id)
            throws IOException, JSONException {
        if (viewable(response, store, access, id) == null) {
            return;
        }
        if (!access.canViewSystemLog(store.readMeta(id))) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "You are not allowed to view system logs.");
            return;
        }
        String node = request.getParameter("node");
        if (node == null || node.isEmpty()) {
            json(response, new JSONObject().put("nodes", GlowrootData.statuses(store.dir(id))));
            return;
        }
        java.nio.file.Path zip = GlowrootData.zipFile(store.dir(id), node);
        if (zip == null) {
            error(response, HttpServletResponse.SC_NOT_FOUND, "No Glowroot data from that node.");
            return;
        }
        sendFile(request, response, zip, "application/zip", id + "-glowroot-" + node + ".zip");
    }

    /**
     * Retry button: the failed traces (keys=k1,k2 or all) go back to pending, and the nodes that served them look in
     * their Glowroot again (TraceCollector.requestRetry). Once per 20 seconds per recording, so Glowroot isn't hammered.
     */
    private void retryTraces(HttpServletRequest request, HttpServletResponse response, RecordingStore store, Access access, String id)
            throws IOException, JSONException {
        if (viewable(response, store, access, id) == null) {
            return;
        }
        if (!access.canViewSystemLog(store.readMeta(id))) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "You are not allowed to view system logs.");
            return;
        }
        long now = System.currentTimeMillis();
        Long last = RETRIED_AT.get(id);
        if (last != null && now - last < RETRY_COOLDOWN_MS) {
            error(response, 429, "Retried a moment ago. Try again in " + ((RETRY_COOLDOWN_MS - (now - last)) / 1000 + 1) + " s.");
            return;
        }
        RETRIED_AT.put(id, now);
        RETRIED_AT.entrySet().removeIf(e -> now - e.getValue() > RETRY_COOLDOWN_MS);
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        String param = request.getParameter("keys");
        if (param != null) {
            for (String k : param.split(",")) {
                if (!k.trim().isEmpty()) {
                    keys.add(k.trim());
                }
            }
        }
        int n = TraceCollector.requestRetry(id, store.dir(id), keys, getPropertyString("glowrootUrl"));
        JSONArray list = TraceStore.merge(store.dir(id), System.currentTimeMillis());
        json(response, new JSONObject().put("retried", n).put("traces", list).put("pending", TraceStore.pending(list)));
    }

    /**
     * One trace file: Glowroot's export page. It runs Glowroot's own script, so it is served sandboxed (no access to
     * this site's cookies or pages).
     */
    private void trace(HttpServletRequest request, HttpServletResponse response, RecordingStore store, Access access, String id)
            throws IOException {
        if (viewable(response, store, access, id) == null) {
            return;
        }
        if (!access.canViewSystemLog(store.readMeta(id))) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "You are not allowed to view system logs.");
            return;
        }
        String name = request.getParameter("file");
        response.setHeader("Content-Security-Policy", "sandbox allow-scripts allow-popups");
        response.setHeader("Referrer-Policy", "no-referrer");
        sendFile(request, response, store.traceFile(id, name), "text/html;charset=UTF-8", name);
    }

    /** Moves the system log captured under systemLogId into the recording. Returns the summary for meta.json. */
    private JSONObject attachSystemLog(RecordingStore store, String recId, String systemLogId, String owner) throws JSONException {
        SystemLogCapture.close(systemLogId); // flush this node's file
        try {
            JSONObject summary = SystemLogCapture.store().attach(systemLogId, owner, store.path(recId, RecordingStore.SYSTEM_LOG));
            if (summary != null) {
                return summary;
            }
        } catch (IOException e) {
            LogUtil.error(getClassName(), e, "Could not attach the system log of recording " + recId);
        }
        // Never started, deleted after the keep period, or another user's
        return new JSONObject().put("status", "unavailable");
    }

    /** Returns the meta when the user may view the recording, otherwise sends 404 and returns null. */
    private JSONObject viewable(HttpServletResponse response, RecordingStore store, Access access, String id) throws IOException {
        JSONObject meta = store.readMeta(id);
        if (meta == null || !access.canView(meta)) {
            // 404 for both, so ids of other people's recordings can't be probed
            error(response, HttpServletResponse.SC_NOT_FOUND, "Recording not found.");
            return null;
        }
        return meta;
    }

    // ---------- files ----------

    /** Streams a file, with HTTP Range support so the video can seek. */
    static void sendFile(HttpServletRequest request, HttpServletResponse response, Path file, String contentType, String downloadName)
            throws IOException {
        if (file == null) {
            error(response, HttpServletResponse.SC_NOT_FOUND, "File not found.");
            return;
        }
        long length = Files.size(file);
        long start = 0;
        long end = length - 1;
        String range = request.getHeader("Range");
        if (range != null) {
            long[] r = parseRange(range, length);
            if (r == null) {
                response.setHeader("Content-Range", "bytes */" + length);
                error(response, HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE, "Range not satisfiable.");
                return;
            }
            start = r[0];
            end = r[1];
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + length);
        }
        response.setContentType(contentType);
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Content-Length", String.valueOf(end - start + 1));
        boolean download = "1".equals(request.getParameter("download"));
        response.setHeader("Content-Disposition", (download ? "attachment" : "inline") + "; filename=\"" + downloadName + "\"");

        try (InputStream in = Files.newInputStream(file)) {
            OutputStream out = response.getOutputStream();
            long skipped = 0;
            while (skipped < start) {
                long n = in.skip(start - skipped);
                if (n <= 0) {
                    throw new IOException("Could not seek in " + file);
                }
                skipped += n;
            }
            byte[] buf = new byte[64 * 1024];
            long remaining = end - start + 1;
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n == -1) {
                    break;
                }
                out.write(buf, 0, n);
                remaining -= n;
            }
            out.flush();
        }
    }

    /** Parses a single "bytes=a-b", "bytes=a-" or "bytes=-n" range; null when not satisfiable. */
    static long[] parseRange(String header, long length) {
        Matcher m = RANGE.matcher(header.trim());
        if (!m.matches() || length == 0) {
            return null;
        }
        String a = m.group(1);
        String b = m.group(2);
        try {
            long start;
            long end;
            if (a.isEmpty()) {
                if (b.isEmpty()) {
                    return null;
                }
                long suffix = Long.parseLong(b);
                if (suffix == 0) {
                    return null;
                }
                start = Math.max(0, length - suffix);
                end = length - 1;
            } else {
                start = Long.parseLong(a);
                end = b.isEmpty() ? length - 1 : Math.min(Long.parseLong(b), length - 1);
            }
            if (start >= length || start > end) {
                return null;
            }
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------- helpers ----------

    /**
     * CSRFGuard only covers /web/*, so POSTs from the pages check the token here.
     * The pages send it as a header, using the name and value from /web/json/directory/user/csrfToken.
     */
    private static boolean validCsrfToken(HttpServletRequest request) {
        try {
            String name = SecurityUtil.getCsrfTokenName();
            String expected = SecurityUtil.getCsrfTokenValue(request);
            String submitted = request.getHeader(name);
            if (expected == null || expected.isEmpty() || submitted == null || submitted.isEmpty()) {
                return false;
            }
            return MessageDigest.isEqual(submitted.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false; // CSRFGuard not ready: fail closed
        }
    }

    long maxSizeMb() {
        return positive("maxSizeMb", DEFAULT_MAX_MB);
    }

    private long positive(String property, long fallback) {
        try {
            long n = Long.parseLong(getPropertyString(property).trim());
            return n > 0 ? n : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String fullName(User user) {
        String name = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return name.isEmpty() ? user.getUsername() : name;
    }

    static void json(HttpServletResponse response, JSONObject body) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(body.toString());
    }

    /** Error body: {"error":{"code":"401","message":"..."}}. */
    static void error(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        try {
            json(response, new JSONObject().put("error", new JSONObject().put("code", String.valueOf(status)).put("message", message)));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** Permission checks for one request. Admins can always do everything. */
    class Access {
        final HttpServletRequest request;
        final User user;
        final boolean admin;
        final boolean ownerAccess;

        Access(HttpServletRequest request, User user, boolean admin) {
            this.request = request;
            this.user = user;
            this.admin = admin;
            this.ownerAccess = !"false".equals(getPropertyString("ownerAccess"));
        }

        boolean canUpload() {
            return admin || allowed("uploadPermission", true);
        }

        boolean canView(JSONObject meta) {
            return admin || (ownerAccess && isOwner(meta)) || allowed("viewPermission", false);
        }

        /** Server data in general: imports, and recordings without an owner check. */
        boolean canViewSystemLog() {
            return admin || allowed("systemLogPermission", false);
        }

        /** Server data of one recording: also its uploader, when the setting allows it (default: no). */
        boolean canViewSystemLog(JSONObject meta) {
            return canViewSystemLog() || (meta != null && "true".equals(getPropertyString("ownServerData")) && isOwner(meta));
        }

        boolean canDelete(JSONObject meta) {
            return admin || (ownerAccess && isOwner(meta)) || allowed("deletePermission", false);
        }

        private boolean isOwner(JSONObject meta) {
            return user.getUsername().equals(meta.optString("owner"));
        }

        /** Runs the permission plugin chosen in the settings; an empty choice gives the default. */
        private boolean allowed(String property, boolean whenNotSet) {
            Object value = getProperty(property);
            if (!(value instanceof Map)) {
                return whenNotSet;
            }
            Map setting = (Map) value;
            Object className = setting.get("className");
            if (className == null || className.toString().trim().isEmpty()) {
                return whenNotSet;
            }
            PluginManager pm = (PluginManager) AppUtil.getApplicationContext().getBean("pluginManager");
            Object plugin = pm.getPlugin(className.toString());
            if (!(plugin instanceof Permission)) {
                return false; // the chosen plugin is gone: deny
            }
            Permission permission = (Permission) plugin;
            if (setting.get("properties") instanceof Map) {
                permission.setProperties((Map) setting.get("properties"));
            }
            permission.setRequestParameters(request.getParameterMap());
            permission.setCurrentUser(user);
            try {
                return permission.isAuthorize();
            } catch (Exception e) {
                LogUtil.error(getClassName(), e, "Permission check " + className + " failed");
                return false;
            }
        }
    }
}
