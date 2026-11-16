package org.joget.marketplace.blackbox;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.model.UserviewDefinition;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.userview.model.Permission;
import org.joget.apps.userview.model.UserviewSetting;
import org.joget.apps.userview.service.UserviewService;
import org.joget.directory.model.Group;
import org.joget.directory.model.service.DirectoryManager;
import org.joget.commons.util.DynamicDataSourceManager;
import org.joget.commons.util.HostManager;
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

public class BlackBoxPlugin extends ExtDefaultPlugin implements SystemConfigurablePlugin {

    static final String VERSION = "8.2-SNAPSHOT";
    private static final long DEFAULT_MAX_MB = 500;
    private static final long DEFAULT_SYSTEM_LOG_MB = 20;
    private static final long DEFAULT_KEEP_DAYS = 7;
    static final long DEFAULT_MAX_RECORDING_MIN = 30;
    static final long MIN_RECORDING_MIN = 5;
    static final long MAX_RECORDING_MIN = 120;
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

    static java.util.function.Supplier<RecordingStore> stores = () -> new RecordingStore(ProfileDir.current());

    static RecordingStore store() {
        return stores.get();
    }

    public void handle(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String action = request.getParameter("action");
        boolean post = "POST".equalsIgnoreCase(request.getMethod());
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");

        String origin = request.getHeader("Origin");
        Object allowedIds = getProperty("allowedExtensionIds");
        boolean fromExtension = fromAllowedExtension(origin, allowedIds == null ? null : allowedIds.toString());
        String ticket = "recording-start".equals(action) ? ArmTickets.ticketFrom(request.getHeader("Authorization")) : null;
        String secret = "recording-ping".equals(action) || "recording-stop".equals(action)
                ? request.getHeader(ArmTickets.SECRET_HEADER) : null;
        if (post && (ticket != null || secret != null)) {
            if (!fromExtension) {
                error(response, HttpServletResponse.SC_FORBIDDEN, "Only the BlackBox extension may do this.");
            } else if (!flag("allowHttpUpload", true) && !secureOrLocal(request)) {
                error(response, HttpServletResponse.SC_FORBIDDEN, "This server accepts uploads over HTTPS only. Change the Upload URL to https://.");
            } else {
                try {
                    handleArmed(request, response, action, request.getParameter("id"), ticket, secret);
                } catch (JSONException e) {
                    LogUtil.error(getClassName(), e, "BlackBox request failed");
                    error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Server error.");
                }
            }
            return;
        }

        WorkflowUserManager wum = (WorkflowUserManager) AppUtil.getApplicationContext().getBean("workflowUserManager");
        User user = wum.isCurrentUserAnonymous() ? null : wum.getCurrentUser();
        if (user == null) {
            error(response, HttpServletResponse.SC_UNAUTHORIZED, "Log in first.");
            return;
        }
        if (post && !fromExtension && !validCsrfToken(request)) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "Invalid or missing CSRF token. Reload the page and try again.");
            return;
        }

        if (post && UPLOAD_ACTIONS.contains(action) && !flag("allowHttpUpload", true) && !secureOrLocal(request)) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "This server accepts uploads over HTTPS only. Change the Upload URL to https://.");
            return;
        }

        Access access = new Access(request, user, wum.isCurrentUserInRole(WorkflowUserManager.ROLE_ADMIN));
        RecordingStore store = store();
        String id = request.getParameter("id");
        try {
            if ("me".equals(action) && !post) {
                JSONObject options = quickSettings(access, user);
                json(response, new JSONObject()
                        .put("user", new JSONObject().put("username", user.getUsername()).put("name", fullName(user)))
                        .put("canUpload", access.canUpload())
                        .put("maxSizeMb", maxSizeMb())
                        .put("sampleData", new JSONObject()
                                .put("enabled", options.getBoolean("sample"))
                                .put("real", options.getBoolean("sample")))
                        .put("options", options));
            } else if ("list".equals(action) && !post) {
                list(request, response, store, access);
            } else if ("create".equals(action) && post) {
                create(request, response, store, access);
            } else if ("video".equals(action) && post) {
                uploadVideo(request, response, store, access, id);
            } else if ("delete".equals(action) && post) {
                delete(response, store, access, id);
            } else if ("meta".equals(action) && !post) {
                JSONObject meta = viewable(response, store, access, id);
                if (meta != null) {
                    meta = settleAppExport(store, id, meta);
                    meta.put("canDelete", access.canDelete(meta));
                    meta.put("canViewServerData", access.canViewServerData(meta));
                    if (!access.canViewServerData(meta)) {
                        hideServerData(meta);
                    }
                    json(response, meta);
                }
            } else if ("systemlog".equals(action) && !post) {
                if (viewable(response, store, access, id) != null) {
                    if (!access.canViewServerData(store.readMeta(id))) {
                        error(response, HttpServletResponse.SC_FORBIDDEN, "You are not allowed to view this recording's server data.");
                    } else {
                        sendFile(request, response, store.file(id, RecordingStore.SYSTEM_LOG), "application/json;charset=UTF-8", id + "-system-log.json");
                    }
                }
            } else if ("download".equals(action) && !post) {
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
            } else if ("recording-arm".equals(action) && post) {
                armCapture(request, response, access);
            } else if ("recording-start".equals(action) && post) {
                startServerCapture(request, response, access, id);
            } else if ("recording-stop".equals(action) && post) {
                stopServerCapture(request, response, access, id);
            } else if ("recording-ping".equals(action) && post) {
                pingServerCapture(request, response, access, id);
            } else if ("app-export".equals(action) && !post) {
                JSONObject pending = ServerCaptureStore.isValidId(id) ? captureMeta(id) : null;
                if (pending == null || !access.user.getUsername().equals(pending.optString("owner"))) {
                    error(response, HttpServletResponse.SC_NOT_FOUND, "Server capture not found.");
                } else {
                    JSONObject state = pending.optJSONObject("appExport");
                    json(response, new JSONObject().put("appExport", state != null ? state : new JSONObject().put("state", "off")));
                }
            } else if ("traces".equals(action) && !post) {
                traces(response, store, access, id);
            } else if ("trace".equals(action) && !post) {
                trace(request, response, store, access, id);
            } else if ("serverinfo".equals(action) && !post) {
                if (viewable(response, store, access, id) != null) {
                    if (!access.canViewServerData(store.readMeta(id))) {
                        error(response, HttpServletResponse.SC_FORBIDDEN, "You are not allowed to view this recording's server data.");
                    } else {
                        sendFile(request, response, store.file(id, ServerInfo.FILE), "application/json;charset=UTF-8", id + "-server-info.json");
                    }
                }
            } else if ("apps".equals(action) && !post) {
                if (viewable(response, store, access, id) != null) {
                    if (!access.canViewServerData(store.readMeta(id))) {
                        error(response, HttpServletResponse.SC_FORBIDDEN, "You are not allowed to view server data.");
                    } else {
                        sendFile(request, response, AppExport.list(store.dir(id)), "application/json;charset=UTF-8", id + "-apps.json");
                    }
                }
            } else if ("appfile".equals(action) && !post) {
                if (viewable(response, store, access, id) != null) {
                    if (!access.canViewServerData(store.readMeta(id))) {
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
        } catch (RecordingStore.Busy e) {
            error(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, e.getMessage());
        } catch (RecordingStore.InvalidUpload e) {
            error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (JSONException e) {
            LogUtil.error(getClassName(), e, "BlackBox request failed");
            error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Server error.");
        }
    }

    static void hideServerData(JSONObject meta) {
        meta.remove(RecordingStore.CAPTURE);
        meta.remove("serverData");
        meta.remove("apps");
    }

    private void list(HttpServletRequest request, HttpServletResponse response, RecordingStore store, Access access)
            throws IOException, JSONException {
        JSONArray rows = new JSONArray();
        for (JSONObject meta : store.list()) {
            if (access.canView(meta)) {
                ServerData.listedGlowroot(store, meta);
                meta.put("canDelete", access.canDelete(meta));
                boolean server = access.canViewServerData(meta);
                meta.put("canViewServerData", server);
                if (!server) {
                    hideServerData(meta);
                }
                meta.put("url", request.getContextPath() + "/blackbox/view?id=" + meta.getString("id"));
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
        if (overQuota(response, store, access, request.getContentLengthLong())) {
            return;
        }
        JSONObject meta;
        try (InputStream in = request.getInputStream()) {
            meta = store.create(in, max, access.user.getUsername(), fullName(access.user),
                    request.getParameter("title"), request.getParameter("notes"));
        }
        String recId = meta.getString("id");
        String captureId = (String) meta.remove("captureId");
        JSONObject atStop = null;
        if (captureId != null) {
            atStop = appExportAtStop(captureId, access.user.getUsername());
            boolean appsAtStop = atStop != null && "done".equals(atStop.optString("state"));
            if (atStop != null && "exporting".equals(atStop.optString("state"))) {
                meta.put("appExport", ExportJobs.exporting().put("capture", captureId));
                store.updateMeta(recId, new JSONObject().put("appExport", meta.get("appExport")));
            }
            meta.put(RecordingStore.CAPTURE, attachServerCapture(store, recId, captureId, access.user.getUsername(), appsAtStop));
            if ("attached".equals(meta.getJSONObject(RecordingStore.CAPTURE).optString("status"))) {
                try {
                    meta.put("serverData", ServerData.of(store.dir(recId), slowMs()));
                } catch (IOException e) {
                    LogUtil.warn(getClassName(), "Could not sum up the server data of recording " + recId + ": " + e.getMessage());
                }
            }
            store.updateMeta(recId, new JSONObject().put(RecordingStore.CAPTURE, meta.get(RecordingStore.CAPTURE)).putOpt("serverData", meta.opt("serverData")));
        }
        String done = atStop != null ? atStop.optString("state") : "";
        if (!"done".equals(done) && !"off".equals(done) && !"exporting".equals(done)) {
            summarizeRecentApps(store.file(recId, ServerInfo.FILE), store.file(recId, RecordingStore.SESSION),
                    store.file(recId, RecordingStore.SYSTEM_LOG), recId);
        }
        if ("done".equals(done)) {
            JSONArray saved = atStop.optJSONArray("saved");
            if (saved != null && saved.length() > 0) {
                meta.put("apps", saved);
                store.updateMeta(recId, new JSONObject().put("apps", saved));
            }
        } else if ("exporting".equals(done)) {
            deliverStopExport(captureId);
        } else if (!"off".equals(done) && access.allows("allowAppExport", false) && !"false".equals(request.getParameter("apps"))) {
            java.util.concurrent.Callable<JSONArray> job = exportJob(store.dir(recId), store.file(recId, RecordingStore.SESSION),
                    store.file(recId, RecordingStore.SYSTEM_LOG), sampleMode(request, access), recId, access);
            meta.put("appExport", ExportJobs.exporting());
            store.updateMeta(recId, new JSONObject().put("appExport", meta.get("appExport")));
            ExportJobs.submit(() -> finishUploadExport(recId, job), () -> {
                try {
                    store().updateMeta(recId, new JSONObject().put("appExport", new JSONObject().put("state", "failed")));
                } catch (IOException | JSONException e) {
                    LogUtil.warn(getClassName(), "Could not save the dropped app export of " + recId + ": " + e);
                }
            });
        }
        LogUtil.info(getClassName(), "Recording " + recId + " uploaded by " + access.user.getUsername());
        meta.put("url", request.getContextPath() + "/blackbox/view?id=" + meta.getString("id"));
        json(response, meta);
    }

    private SampleData.Mode sampleMode(HttpServletRequest request, Access access) {
        String asked = request.getParameter("sampleData");
        if (!access.allows("allowCaptureAppsData", false) || "none".equals(asked)) {
            return null;
        }
        return "masked".equals(asked) ? SampleData.Mode.MASKED : SampleData.Mode.REAL;
    }

    private JSONArray exportApps(Path dir, Path session, Path systemLog, SampleData.Mode sample, String label, Access access) {
        try {
            return exportJob(dir, session, systemLog, sample, label, access).call();
        } catch (Exception | LinkageError e) {
            LogUtil.warn(getClassName(), "Could not export the apps of " + label + ": " + e);
            return new JSONArray();
        }
    }

    static final String NO_OTHER_APPS_DATA = "No apps data: you are not an admin of this app, and \"Allow apps data from "
            + "apps they don't administer\" is off. The app definition is exported without its rows and process data.";

    private static final String DENIED = "Not exported: you are not an admin of this app, and \"Who can export apps they "
            + "don't administer\" doesn't allow you or Joget doesn't let you access it.";

    private java.util.concurrent.Callable<JSONArray> exportJob(Path dir, Path session, Path systemLog, SampleData.Mode sample,
            String label, Access access) throws IOException {
        List<String> urls = AppExport.urls(session, systemLog);
        List<AppExport.Seen> apps = AppExport.find(AppExport.hits(session, systemLog));
        Map<String, String> decided = new java.util.HashMap<>();
        java.util.Set<String> dataAllowed = new java.util.HashSet<>();
        boolean otherAppsData = access.allows("allowOtherAppsData", false);
        for (AppExport.Seen s : apps) {
            AppDefinition def = AppExport.resolve(s.appId, s.version);
            if (def != null) {
                decided.put(AppExport.key(def), access.canExportApp(def) ? null : DENIED);
                if (otherAppsData || access.isAppAdmin(def)) {
                    dataAllowed.add(AppExport.key(def));
                }
            }
        }
        java.util.Set<String> candidates = null;
        if (sample != null) {
            List<String> texts = new java.util.ArrayList<>(urls);
            texts.addAll(SampleData.postData(session));
            candidates = SampleData.candidateIds(texts);
        }
        java.util.Set<String> ids0 = candidates;
        boolean keepIds = sample != SampleData.Mode.MASKED;
        String sensitive = getPropertyString("sensitiveColumns");
        int newest = (int) Math.min(positive("sampleRows", 20), 500);
        String max = getPropertyString("maxApps");
        int maxApps = max != null && max.trim().matches("\\d{1,4}") ? Integer.parseInt(max.trim()) : AppExport.MAX_APPS;
        return () -> {
            Masker.IdMap ids = new Masker.IdMap(keepIds);
            AppExport.AfterExport after = null;
            if (sample != null) {
                Masker masker = new Masker(sensitive == null || sensitive.trim().isEmpty() ? Masker.DEFAULT_SENSITIVE : sensitive);
                SampleData.Source src = SampleData.joget();
                ProcessData.Source psrc = ProcessData.joget();
                after = (jwa, def) -> {
                    if (!dataAllowed.contains(AppExport.key(def))) {
                        try {
                            return new JSONObject().put("sample", new JSONObject().put("skipped", NO_OTHER_APPS_DATA));
                        } catch (JSONException e) {
                            throw new IOException(e);
                        }
                    }
                    masker.setDefinition(SampleData.definitionText(jwa));
                    List<SampleData.Table> tables = SampleData.build(SampleData.tables(def), ids0, src, sample, masker, ids, newest);
                    SampleData.addTo(jwa, tables);
                    try {
                        JSONObject extra = new JSONObject().put("sample", SampleData.summary(tables, sample));
                        java.util.Set<String> refs = new java.util.LinkedHashSet<>(ids0);
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
            AppExport.Exporter exporter = AppExport.joget(def -> decided.containsKey(AppExport.key(def))
                    ? decided.get(AppExport.key(def)) : "Not exported: the app changed while it was being exported. Upload again.");
            JSONArray saved = AppExport.save(dir, apps, exporter, after, maxApps);
            SampleData.writeIdMap(dir, ids, keepIds);
            return saved;
        };
    }

    private void uploadVideo(HttpServletRequest request, HttpServletResponse response, RecordingStore store, Access access, String id)
            throws IOException, RecordingStore.InvalidUpload {
        JSONObject meta = store.readMeta(id);
        if (meta == null || !access.user.getUsername().equals(meta.optString("owner"))) {
            error(response, HttpServletResponse.SC_NOT_FOUND, "Recording not found.");
            return;
        }
        long max = maxSizeMb() * 1024 * 1024;
        if (request.getContentLengthLong() > max) {
            throw new RecordingStore.InvalidUpload("The video is larger than the limit (" + maxSizeMb() + " MB).");
        }
        if (overQuota(response, store, access, request.getContentLengthLong())) {
            return;
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

    private void downloadHtml(HttpServletResponse response, RecordingStore store, Access access, String id, JSONObject meta)
            throws IOException {
        Path session = store.file(id, RecordingStore.SESSION);
        if (session == null) {
            error(response, HttpServletResponse.SC_NOT_FOUND, "File not found.");
            return;
        }
        Path systemLog = access.canViewServerData(meta) ? store.file(id, RecordingStore.SYSTEM_LOG) : null;
        if (!access.canViewServerData(meta)) {
            hideServerData(meta);
        } else if (systemLog == null) {
            meta.remove(RecordingStore.CAPTURE);
        }
        meta.put("tracesSandboxed", !flag("allowUnsandboxedTraces", true));
        RecordingHtml export = RecordingHtml.fromResources();
        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"blackbox-" + id + ".html\"");
        String traces = null;
        java.util.Map<String, Path> traceFiles = new java.util.LinkedHashMap<>();
        if (systemLog != null) {
            JSONArray list = TraceStore.merge(store.dir(id), System.currentTimeMillis());
            traces = list.length() > 0 ? list.toString() : null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject t = list.optJSONObject(i);
                String file = t != null ? t.optString("file") : "";
                Path page = file.isEmpty() ? null : store.traceFile(id, file);
                if (page != null) {
                    traceFiles.put(file, page);
                }
            }
        }
        JSONObject gauges = systemLog != null ? GaugeStore.merge(store.dir(id)) : null;
        Path info = systemLog != null ? store.file(id, ServerInfo.FILE) : null;
        Path apps = access.canViewServerData(meta) ? AppExport.list(store.dir(id)) : null;
        export.write(response.getOutputStream(), meta.toString(), session, systemLog, store.file(id, RecordingStore.VIDEO), traces,
                gauges != null ? gauges.toString() : null, info, apps, traceFiles);
        LogUtil.info(getClassName(), "Recording " + id + " downloaded as HTML by " + access.user.getUsername());
    }

    private void downloadZip(HttpServletResponse response, RecordingStore store, Access access, String id, JSONObject meta)
            throws IOException {
        Path session = store.file(id, RecordingStore.SESSION);
        if (session == null) {
            error(response, HttpServletResponse.SC_NOT_FOUND, "File not found.");
            return;
        }
        Path systemLog = access.canViewServerData(meta) ? store.file(id, RecordingStore.SYSTEM_LOG) : null;
        try {
            if (systemLog != null && "building".equals(GlowrootData.overall(GlowrootData.statuses(store.dir(id))))) {
                error(response, HttpServletResponse.SC_CONFLICT, "The Glowroot data is still being built (about a minute after the"
                        + " recording stopped). Download the ZIP once it is ready.");
                return;
            }
        } catch (IOException e) {
        }
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"blackbox-" + id + ".zip\"");
        RecordingZip.write(response.getOutputStream(), meta, session, systemLog, store.file(id, RecordingStore.VIDEO),
                systemLog != null ? store.dir(id) : null, access.canViewServerData(meta) ? store.dir(id) : null);
    }

    private void importZip(HttpServletRequest request, HttpServletResponse response, RecordingStore store, Access access)
            throws IOException, JSONException, RecordingStore.InvalidUpload {
        if (!access.canUpload()) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "You are not allowed to upload recordings.");
            return;
        }
        long max = maxSizeMb() * 1024 * 1024;
        long maxZip = 2 * max + 64 * 1024 * 1024;
        if (request.getContentLengthLong() > maxZip) {
            throw new RecordingStore.InvalidUpload("The ZIP is larger than the limit.");
        }
        if (overQuota(response, store, access, request.getContentLengthLong())) {
            return;
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
            JSONObject meta = RecordingZip.importZip(store, tmp, max, access.canCaptureServerData(),
                    access.user.getUsername(), fullName(access.user), importMaxTotalBytes(max),
                    limit("importMaxEntries", RecordingZip.DEFAULT_MAX_ENTRIES));
            if (meta.has(RecordingStore.CAPTURE)) {
                meta.put("serverData", ServerData.of(store.dir(meta.getString("id")), slowMs()));
                store.updateMeta(meta.getString("id"), new JSONObject().put("serverData", meta.get("serverData")));
            }
            LogUtil.info(getClassName(), "Recording " + meta.getString("id") + " imported by " + access.user.getUsername());
            meta.put("url", request.getContextPath() + "/blackbox/view?id=" + meta.getString("id"));
            json(response, meta);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    static final String OFFICIAL_EXTENSION_ID = "anmfmkacddnmmgdcnkgkfjhagnilmkmp";

    static boolean fromAllowedExtension(String origin, String allowed) {
        if (origin == null || !origin.startsWith("chrome-extension://")) {
            return false;
        }
        String id = origin.substring("chrome-extension://".length());
        String list = allowed == null ? OFFICIAL_EXTENSION_ID : allowed.trim();
        if (list.isEmpty()) {
            return true;
        }
        for (String a : list.split(",")) {
            if (a.trim().equals(id)) {
                return true;
            }
        }
        return false;
    }

    static final int DEFAULT_STARTS_PER_MINUTE = 10;
    static final int DEFAULT_MAX_OPEN_CAPTURES = 3;
    static final String OTHER_TRACES = "allowCaptureOtherUsersTraces";
    static final String SHARED_GLOWROOT = "blackbox.sharedGlowroot";
    private static final List<String> START_ARGS = startArgs();

    private static List<String> startArgs() {
        try {
            return java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
        } catch (RuntimeException | LinkageError e) {
            LogUtil.warn(BlackBoxPlugin.class.getName(), "Could not read the server's start options: " + e);
            return Collections.emptyList();
        }
    }

    static String startArg(List<String> args, String key) {
        String value = null;
        for (String a : args) {
            if (a.startsWith("-D" + key + "=")) {
                value = a.substring(key.length() + 3);
            } else if (a.equals("-D" + key)) {
                value = "";
            }
        }
        return value;
    }

    private static boolean isTrue(String value) {
        return value != null && "true".equalsIgnoreCase(value.trim());
    }

    static boolean virtualHosts() {
        return VIRTUAL_HOSTS_STARTED || HostManager.isVirtualHostEnabled();
    }

    private static final boolean VIRTUAL_HOSTS_STARTED = isTrue(startArg(START_ARGS, HostManager.SYSTEM_PROPERTY_VIRTUALHOST));

    static java.util.function.BooleanSupplier glowrootShared = () -> glowrootShared(virtualHosts(),
            startArg(START_ARGS, SHARED_GLOWROOT));

    static boolean glowrootShared(boolean virtualHosts, String startValue) {
        return !virtualHosts || isTrue(startValue);
    }
    static final long DEFAULT_USER_QUOTA_MB = 10240;
    static final StartLimiter STARTS = new StartLimiter();

    int limit(String property, int dflt) {
        return (int) Math.min(Integer.MAX_VALUE, limitValue(getPropertyString(property), dflt));
    }

    static long limitValue(String saved, long dflt) {
        try {
            long n = Long.parseLong(saved == null ? "" : saved.trim());
            return n >= 0 ? n : dflt;
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    long importMaxTotalBytes(long maxFileBytes) {
        long mb = limitValue(getPropertyString("importMaxTotalMb"), -1);
        return mb < 0 ? RecordingZip.DEFAULT_TOTAL_FACTOR * maxFileBytes : mb * 1024 * 1024;
    }

    private boolean overQuota(HttpServletResponse response, RecordingStore store, Access access, long incoming) throws IOException {
        long quotaMb = limitValue(getPropertyString("userQuotaMb"), DEFAULT_USER_QUOTA_MB);
        if (access.admin || quotaMb == 0) {
            return false;
        }
        long used = store.usedBytes(access.user.getUsername());
        if (used + Math.max(0, incoming) <= quotaMb * 1024 * 1024) {
            return false;
        }
        error(response, 413, "Your recordings use " + used / (1024 * 1024) + " MB of your " + quotaMb
                + " MB on this server. Delete some recordings first.");
        return true;
    }

    static final class StartLimiter {
        private final Map<String, java.util.ArrayDeque<Long>> starts = new java.util.concurrent.ConcurrentHashMap<>();

        boolean allow(String user, int perMinute, long now) {
            java.util.ArrayDeque<Long> q = starts.computeIfAbsent(user, u -> new java.util.ArrayDeque<>());
            synchronized (q) {
                while (!q.isEmpty() && q.peekFirst() <= now - 60_000) {
                    q.pollFirst();
                }
                if (q.size() >= perMinute) {
                    return false;
                }
                q.addLast(now);
                return true;
            }
        }
    }

    static final java.util.Set<String> UPLOAD_ACTIONS = new java.util.HashSet<>(java.util.Arrays.asList(
            "create", "video", "import", "recording-arm", "recording-start", "recording-stop", "recording-ping"));

    static boolean secureOrLocal(HttpServletRequest request) {
        if (request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"))) {
            return true;
        }
        String host = request.getServerName();
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host);
    }

    boolean flag(String name, boolean dflt) {
        return flagValue(getProperty(name), dflt);
    }

    static boolean flagValue(Object saved, boolean dflt) {
        return saved == null ? dflt : "true".equals(saved.toString());
    }

    private void startServerCapture(HttpServletRequest request, HttpServletResponse response, Access access, String id)
            throws IOException, JSONException {
        if (!ServerCaptureStore.isValidId(id)) {
            error(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid server capture id.");
            return;
        }
        String user = access.user.getUsername();
        if (!access.admin && (!withinStartRate(response, user) || overOpenLimit(response, user, id))) {
            return;
        }
        openCapture(request, response, id, user, captureOptions(request, access), null);
    }

    private boolean withinStartRate(HttpServletResponse response, String user) throws IOException {
        int perMinute = limit("captureStartsPerMinute", DEFAULT_STARTS_PER_MINUTE);
        if (perMinute > 0 && !STARTS.allow(user, perMinute, System.currentTimeMillis())) {
            error(response, 429, "Too many recordings started in the last minute (limit " + perMinute + "). Wait a moment.");
            return false;
        }
        return true;
    }

    private boolean overOpenLimit(HttpServletResponse response, String user, String id) throws IOException {
        int maxOpen = limit("maxOpenCaptures", DEFAULT_MAX_OPEN_CAPTURES);
        if (maxOpen > 0 && ServerCapture.store().openCount(user, id) >= maxOpen) {
            error(response, 429, "You already have " + maxOpen + " recordings in progress on this server (the limit). "
                    + "Stop one first; a closed browser's recordings stop on their own after a few minutes.");
            return true;
        }
        return false;
    }

    private JSONObject captureOptions(HttpServletRequest request, Access access) throws JSONException {
        boolean traces = access.mayCapture("allowCaptureTraces") && !"false".equals(request.getParameter("traces"));
        return new JSONObject()
                .put("traces", traces)
                .put("gauges", access.mayCapture("allowCaptureGauges") && !"false".equals(request.getParameter("gauges")))
                .put("glowrootData", access.mayCapture("allowCaptureGlowrootData") && glowrootShared.getAsBoolean()
                        && !"false".equals(request.getParameter("glowrootData")))
                .put("allServer", access.canCaptureServerData() && "true".equals(request.getParameter("allServer")))
                .put("otherTraces", traces && access.mayCapture(OTHER_TRACES) && glowrootShared.getAsBoolean()
                        && "true".equals(request.getParameter("otherTraces")))
                .put("systemSecrets", access.allows("allowUnmaskedSystemInfo", false) && "true".equals(request.getParameter("systemSecrets")))
                .put("license", access.allows("allowUnmaskedLicense", false) && "true".equals(request.getParameter("license")))
                .put("admin", access.admin);
    }

    private void openCapture(HttpServletRequest request, HttpServletResponse response, String id, String owner, JSONObject o,
            String secret) throws IOException, JSONException {
        long maxRecordingMs = maxRecordingMs(getPropertyString("maxRecordingMinutes"));
        long maxBytes = positive("systemLogMaxMb", DEFAULT_SYSTEM_LOG_MB) * 1024 * 1024;
        long keepMs = positive("serverDataKeepDays", DEFAULT_KEEP_DAYS) * 24 * 3600 * 1000;
        boolean traces = o.optBoolean("traces");
        boolean gauges = o.optBoolean("gauges");
        boolean glowrootData = o.optBoolean("glowrootData");
        boolean allServer = o.optBoolean("allServer");
        boolean otherTraces = o.optBoolean("otherTraces");
        if (!ServerCapture.store().start(id, owner, maxBytes, keepMs, traces, getPropertyString("glowrootUrl"), gauges,
                glowrootData, allServer, maxRecordingMs, otherTraces)) {
            error(response, HttpServletResponse.SC_CONFLICT, "This server capture id is already in use.");
            return;
        }
        ServerCapture.store().put(id, ServerCapture.PROFILE, DynamicDataSourceManager.getCurrentProfile());
        if (secret != null) {
            ServerCapture.store().put(id, ServerCaptureStore.SECRET_HASH, ArmTickets.hash(secret));
            ServerCapture.store().put(id, "armed", true);
        }
        ServerCapture.find(id);
        ServerInfo.save(ServerCapture.store().dir(id), request, (int) Math.min(positive("recentChangesDays", 7), 365),
                o.optBoolean("systemSecrets") ? Secrets.none() : new Secrets(getPropertyString("secretNames")), !o.optBoolean("license"));
        JSONObject started = new JSONObject().put("id", id).put("maxMb", maxBytes / (1024 * 1024))
                .put("traces", traces).put("gauges", gauges).put("glowrootData", glowrootData).put("allServer", allServer)
                .put("otherTraces", otherTraces).put("slowThresholdMs", slowMs());
        withLiveness(started, maxRecordingMs, maxSizeMb());
        JSONObject body = new JSONObject().put(RecordingStore.CAPTURE, started).put(RecordingStore.LEGACY_CAPTURE, started);
        if (secret != null) {
            body.put("captureSecret", secret);
        }
        json(response, body);
    }

    static final String ARM_SETTING = "allowArmedRecordings";
    static final String ARM_TTL_SETTING = "armTicketMinutes";

    static java.util.function.Supplier<ArmTickets> tickets = () -> new ArmTickets(ProfileDir.current().resolve(ArmTickets.DIR));

    private void armCapture(HttpServletRequest request, HttpServletResponse response, Access access) throws IOException, JSONException {
        if (!flag(ARM_SETTING, false)) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "Recordings that start before login are off on this server.");
            return;
        }
        if (!access.canUpload()) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "You are not allowed to upload recordings to this server.");
            return;
        }
        String user = access.user.getUsername();
        long now = System.currentTimeMillis();
        long ttlMs = ArmTickets.ttlMs(getPropertyString(ARM_TTL_SETTING));
        if (!access.admin) {
            if (!withinStartRate(response, user)) {
                return;
            }
            if (tickets.get().count(user, now) >= ArmTickets.MAX_PER_USER) {
                error(response, 429, "You already have " + ArmTickets.MAX_PER_USER + " prepared recordings waiting. "
                        + "Use one, or cancel it in the popup, or wait until it expires.");
                return;
            }
        }
        String ticket = tickets.get().arm(user, captureOptions(request, access), now, ttlMs);
        json(response, new JSONObject().put("ticket", ticket).put("expiresAt", now + ttlMs).put("ttlMs", ttlMs));
    }

    private void handleArmed(HttpServletRequest request, HttpServletResponse response, String action, String id, String ticket,
            String secret) throws IOException, JSONException {
        if (ticket != null) {
            if (!flag(ARM_SETTING, false)) {
                error(response, HttpServletResponse.SC_FORBIDDEN, "Recordings that start before login are off on this server.");
                return;
            }
            if (!ServerCaptureStore.isValidId(id)) {
                error(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid server capture id.");
                return;
            }
            JSONObject t = tickets.get().use(ticket, System.currentTimeMillis());
            JSONObject o = t != null ? t.optJSONObject("options") : null;
            String owner = t != null ? t.optString("owner") : "";
            if (o == null || owner.isEmpty()) {
                error(response, HttpServletResponse.SC_UNAUTHORIZED, "The prepared recording expired or was already used. Prepare it again.");
                return;
            }
            if (!o.optBoolean("admin") && overOpenLimit(response, owner, id)) {
                return;
            }
            openCapture(request, response, id, owner, o, ArmTickets.newToken());
            return;
        }
        String owner = ServerCaptureStore.isValidId(id) ? ServerCapture.store().ownerBySecret(id, secret) : null;
        if (owner == null) {
            error(response, HttpServletResponse.SC_NOT_FOUND, "Server capture not found.");
            return;
        }
        if ("recording-ping".equals(action)) {
            JSONObject state = ServerCapture.store().ping(id, owner, longParam(request, "lastSavedAt"), longParam(request, "videoBytes"));
            if (state == null) {
                error(response, HttpServletResponse.SC_NOT_FOUND, "Server capture not found.");
            } else {
                json(response, state);
            }
            return;
        }
        if (!ServerCapture.store().stop(id, owner, longParam(request, "stoppedAt"), LivenessKnobs.lostMs())) {
            error(response, HttpServletResponse.SC_NOT_FOUND, "Server capture not found.");
            return;
        }
        ServerCapture.close(id);
        json(response, new JSONObject().put("stopped", id));
    }

    static JSONObject withLiveness(JSONObject started) throws JSONException {
        return started.put("pingMs", LivenessKnobs.pingMs()).put("lostMs", LivenessKnobs.lostMs());
    }

    static JSONObject withLiveness(JSONObject started, long maxRecordingMs) throws JSONException {
        return withLiveness(started).put("maxRecordingMs", maxRecordingMs);
    }

    static JSONObject withLiveness(JSONObject started, long maxRecordingMs, long maxSizeMb) throws JSONException {
        return withLiveness(started, maxRecordingMs).put("maxSizeMb", maxSizeMb);
    }

    static long maxRecordingMs(String setting) {
        long min = DEFAULT_MAX_RECORDING_MIN;
        try {
            min = Long.parseLong(setting == null ? "" : setting.trim());
        } catch (NumberFormatException e) {
        }
        min = Math.max(MIN_RECORDING_MIN, Math.min(MAX_RECORDING_MIN, min));
        return Math.min(min * 60_000L, LivenessKnobs.minLimitMs());
    }

    private void pingServerCapture(HttpServletRequest request, HttpServletResponse response, Access access, String id)
            throws IOException, JSONException {
        JSONObject state = ServerCaptureStore.isValidId(id)
                ? ServerCapture.store().ping(id, access.user.getUsername(), longParam(request, "lastSavedAt"), longParam(request, "videoBytes"))
                : null;
        if (state == null) {
            error(response, HttpServletResponse.SC_NOT_FOUND, "Server capture not found.");
            return;
        }
        json(response, state);
    }

    private static long longParam(HttpServletRequest request, String name) {
        try {
            return Math.max(-1, Long.parseLong(request.getParameter(name)));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

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

    private void stopServerCapture(HttpServletRequest request, HttpServletResponse response, Access access, String id)
            throws IOException, JSONException {
        if (!ServerCaptureStore.isValidId(id)
                || !ServerCapture.store().stop(id, access.user.getUsername(), longParam(request, "stoppedAt"), LivenessKnobs.lostMs())) {
            error(response, HttpServletResponse.SC_NOT_FOUND, "Server capture not found.");
            return;
        }
        ServerCapture.close(id);
        JSONObject result = new JSONObject().put("stopped", id);
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) {
            result.put("appExport", exportAtStop(request, access, id));
        }
        json(response, result);
    }

    private void summarizeRecentApps(Path info, Path session, Path systemLog, String label) {
        try {
            List<String> ids = new java.util.ArrayList<>();
            for (AppExport.Seen s : AppExport.find(AppExport.hits(session, systemLog))) {
                ids.add(s.appId);
            }
            ServerInfo.summarizeApps(info, ids, ServerInfo.jogetTables());
        } catch (IOException | RuntimeException | LinkageError e) {
            LogUtil.warn(getClassName(), "Could not narrow the recent app changes of " + label + ": " + e);
        }
    }

    private static final long STOP_SYNC_MS = 20 * 1000L;
    private static final long STOP_EXPORT_WAIT_MS = STOP_SYNC_MS;

    private JSONObject exportAtStop(HttpServletRequest request, Access access, String id) throws IOException, JSONException {
        ServerCaptureStore logs = ServerCapture.store();
        JSONObject pending = captureMeta(id);
        if (pending == null) {
            return new JSONObject().put("state", "off");
        }
        if (pending.has("appExport") || pending.has("recording")) {
            return pending.optJSONObject("appExport") != null ? pending.getJSONObject("appExport") : new JSONObject().put("state", "off");
        }
        boolean off = !access.allows("allowAppExport", false) || "false".equals(request.getParameter("apps"));
        Path dir = logs.dir(id);
        Path session = dir.resolve("stop-session.json");
        Path systemLog = dir.resolve("stop-system-log.json");
        JSONObject state = new JSONObject().put("state", "failed");
        boolean queued = false;
        logs.put(id, "appExport", ExportJobs.exporting());
        try {
            try (InputStream in = request.getInputStream()) {
                SessionFile.copy(in, session, maxSizeMb() * 1024 * 1024, "The recording's requests");
                RecordingStore.acquireParsing();
                try {
                    SessionFile.check(session);
                } finally {
                    RecordingStore.releaseParsing();
                }
            } catch (RecordingStore.InvalidUpload e) {
                throw new IOException(e.getMessage());
            }
            logs.writeLines(id, systemLog);
            summarizeRecentApps(dir.resolve(ServerInfo.FILE), session, systemLog, id);
            if (off) {
                state = new JSONObject().put("state", "off");
                return state;
            }
            SampleData.Mode sample = sampleMode(request, access);
            java.util.concurrent.Callable<JSONArray> job = exportJob(dir, session, systemLog, sample, id, access);
            String mode = sample == null ? "none" : sample.name().toLowerCase();
            queued = true;
            ExportJobs.submit(() -> {
                JSONObject end = new JSONObject();
                try {
                    end.put("state", "done").put("saved", job.call()).put("sampleData", mode);
                } catch (Exception | LinkageError e) {
                    LogUtil.warn(getClassName(), "Could not export the apps at the stop of server capture " + id + ": " + e);
                    try {
                        end = new JSONObject().put("state", "failed");
                    } catch (JSONException ignored) {
                    }
                } finally {
                    deleteQuietly(session);
                    deleteQuietly(systemLog);
                }
                endStopExport(id, end);
            }, () -> {
                deleteQuietly(session);
                deleteQuietly(systemLog);
                try {
                    endStopExport(id, new JSONObject().put("state", "failed"));
                } catch (JSONException ignored) {
                }
            });
            return waitForStopExport(id, STOP_SYNC_MS);
        } catch (IOException | RuntimeException e) {
            LogUtil.warn(getClassName(), "Could not export the apps at the stop of server capture " + id + ": " + e);
        } finally {
            if (!queued) {
                Files.deleteIfExists(session);
                Files.deleteIfExists(systemLog);
                logs.put(id, "appExport", state);
            }
        }
        return state;
    }

    static void endStopExport(String id, JSONObject end) {
        try {
            ServerCapture.store().put(id, "appExport", end);
        } catch (IOException e) {
            LogUtil.warn(BlackBoxPlugin.class.getName(), "Could not save the app export state of server capture " + id + ": " + e);
        }
        deliverStopExport(id);
    }

    private static void deleteQuietly(Path f) {
        try {
            Files.deleteIfExists(f);
        } catch (IOException e) {
        }
    }

    private static JSONObject waitForStopExport(String id, long ms) throws JSONException {
        long until = System.currentTimeMillis() + ms;
        while (true) {
            JSONObject pending = captureMeta(id);
            JSONObject state = pending != null ? pending.optJSONObject("appExport") : null;
            if (state == null) {
                return new JSONObject().put("state", "failed");
            }
            if (!"exporting".equals(state.optString("state")) || System.currentTimeMillis() > until) {
                return state;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return state;
            }
        }
    }

    private static final Object DELIVERING = new Object();
    static Runnable afterLostCheck = () -> {
    };

    static JSONObject captureMeta(String id) {
        ServerCaptureStore logs = ServerCapture.store();
        JSONObject pending = logs.readMeta(id);
        if (pending != null && ExportJobs.lost(pending.optJSONObject("appExport"))) {
            afterLostCheck.run();
            try {
                if (logs.markExportLost(id)) {
                    LogUtil.info(BlackBoxPlugin.class.getName(), "The app export of server capture " + id + " was lost (restart, or its job died): failed");
                }
            } catch (IOException e) {
                LogUtil.warn(BlackBoxPlugin.class.getName(), "Could not save the lost app export of server capture " + id + ": " + e);
            }
            deliverStopExport(id);
            pending = logs.readMeta(id);
        }
        return pending;
    }

    static JSONObject settleAppExport(RecordingStore store, String id, JSONObject meta) {
        JSONObject state = meta.optJSONObject("appExport");
        if (state == null) {
            return meta;
        }
        String capture = state.optString("capture");
        try {
            if (ExportJobs.wasLost(state) && !capture.isEmpty()) {
                JSONObject pending = ServerCapture.store().readMeta(capture);
                JSONObject end = pending != null ? pending.optJSONObject("appExport") : null;
                String ended = end != null ? end.optString("state") : "";
                if (!"done".equals(ended) && !("failed".equals(ended) && !ExportJobs.wasLost(end))) {
                    return meta;
                }
                deliverStopExport(capture);
            } else if (!"exporting".equals(state.optString("state"))) {
                return meta;
            } else if (!capture.isEmpty()) {
                if (captureMeta(capture) != null) {
                    deliverStopExport(capture);
                } else if (!ServerCaptureStore.isValidId(capture) || !Files.isDirectory(ServerCapture.store().dir(capture))) {
                    store.modifyMeta(id, m -> {
                        JSONObject waiting = m.optJSONObject("appExport");
                        if (waiting != null && "exporting".equals(waiting.optString("state")) && capture.equals(waiting.optString("capture"))) {
                            m.put("appExport", new JSONObject().put("state", "failed"));
                        }
                    });
                } else {
                    return meta;
                }
            } else if (ExportJobs.lost(state)) {
                afterLostCheck.run();
                store.modifyMeta(id, m -> {
                    if (ExportJobs.lost(m.optJSONObject("appExport"))) {
                        m.put("appExport", ExportJobs.lostState());
                        LogUtil.info(BlackBoxPlugin.class.getName(), "The app export of recording " + id + " was lost (restart, or its job died): failed");
                    }
                });
            } else {
                return meta;
            }
            JSONObject latest = store.readMeta(id);
            return latest != null ? latest : meta;
        } catch (IOException | JSONException | RecordingStore.InvalidUpload e) {
            LogUtil.warn(BlackBoxPlugin.class.getName(), "Could not settle the app export of recording " + id + ": " + e);
            return meta;
        }
    }

    static void deliverStopExport(String captureId) {
        synchronized (DELIVERING) {
            try {
                ServerCaptureStore logs = ServerCapture.store();
                JSONObject pending = logs.readMeta(captureId);
                String rec = pending != null ? pending.optString("recording") : "";
                JSONObject state = pending != null ? pending.optJSONObject("appExport") : null;
                if (rec.isEmpty() || state == null || "exporting".equals(state.optString("state"))) {
                    return;
                }
                RecordingStore store = store();
                JSONObject meta = store.readMeta(rec);
                JSONObject waiting = meta != null ? meta.optJSONObject("appExport") : null;
                boolean open = waiting != null && ("exporting".equals(waiting.optString("state")) || ExportJobs.wasLost(waiting));
                if (!open || !captureId.equals(waiting.optString("capture"))) {
                    return;
                }
                JSONObject end = new JSONObject().put("state", state.optString("state"));
                if (ExportJobs.wasLost(state)) {
                    end.put("reason", "lost").put("capture", captureId);
                }
                JSONObject changes = new JSONObject().put("appExport", end);
                if ("done".equals(state.optString("state"))) {
                    TraceStore.moveFolder(logs.dir(captureId), store.dir(rec), AppExport.DIR, name -> true);
                    JSONArray saved = state.optJSONArray("saved");
                    if (saved != null && saved.length() > 0) {
                        changes.put("apps", saved);
                    }
                }
                store.updateMeta(rec, changes);
            } catch (IOException | RuntimeException e) {
                LogUtil.warn(BlackBoxPlugin.class.getName(), "Could not add the late app export of server capture " + captureId + ": " + e);
            }
        }
    }

    private void finishUploadExport(String recId, java.util.concurrent.Callable<JSONArray> job) {
        JSONArray saved = null;
        try {
            saved = job.call();
        } catch (Exception | LinkageError e) {
            LogUtil.warn(getClassName(), "Could not export the apps of " + recId + ": " + e);
        }
        try {
            JSONObject changes = new JSONObject().put("appExport", new JSONObject().put("state", saved != null ? "done" : "failed"));
            if (saved != null && saved.length() > 0) {
                changes.put("apps", saved);
            }
            store().updateMeta(recId, changes);
        } catch (IOException | JSONException e) {
            LogUtil.warn(getClassName(), "Could not list the exported apps of " + recId + ": " + e);
        }
    }

    private JSONObject appExportAtStop(String captureId, String owner) {
        long until = System.currentTimeMillis() + STOP_EXPORT_WAIT_MS;
        while (true) {
            JSONObject pending = captureMeta(captureId);
            JSONObject state = pending != null && owner.equals(pending.optString("owner")) ? pending.optJSONObject("appExport") : null;
            if (state == null || !"exporting".equals(state.optString("state"))) {
                return state;
            }
            if (System.currentTimeMillis() > until) {
                LogUtil.info(getClassName(), "The app export of server capture " + captureId + " is still running: added when it ends");
                return state;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    private JSONObject quickSettings(Access access, User user) throws JSONException {
        boolean apps = access.allows("allowAppExport", false) && access.mayExportApps();
        boolean sample = apps && access.allows("allowCaptureAppsData", false);
        return new JSONObject()
                .put("serverCapture", true)
                .put("apps", apps)
                .put("sample", sample)
                .put("mask", sample && access.allows("allowMaskData", false))
                .put("allServer", access.canCaptureServerData())
                .put("gauges", access.mayCapture("allowCaptureGauges"))
                .put("traces", access.mayCapture("allowCaptureTraces"))
                .put("otherTraces", access.mayCapture("allowCaptureTraces") && access.mayCapture(OTHER_TRACES)
                        && glowrootShared.getAsBoolean())
                .put("glowrootData", access.mayCapture("allowCaptureGlowrootData") && glowrootShared.getAsBoolean())
                .put("credentials", access.allows("allowUnmaskedCredentials", false))
                .put("systemSecrets", access.allows("allowUnmaskedSystemInfo", false))
                .put("license", access.allows("allowUnmaskedLicense", false) && ServerInfo.hasLicense())
                .put("arm", flag(ARM_SETTING, false) && access.canUpload());
    }

    private void traces(HttpServletResponse response, RecordingStore store, Access access, String id) throws IOException, JSONException {
        if (viewable(response, store, access, id) == null) {
            return;
        }
        if (!access.canViewServerData(store.readMeta(id))) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "You are not allowed to view this recording's server data.");
            return;
        }
        JSONArray list = TraceStore.merge(store.dir(id), System.currentTimeMillis());
        json(response, new JSONObject().put("traces", list).put("pending", TraceStore.pending(list)));
    }

    private void gauges(HttpServletResponse response, RecordingStore store, Access access, String id) throws IOException {
        if (viewable(response, store, access, id) == null) {
            return;
        }
        if (!access.canViewServerData(store.readMeta(id))) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "You are not allowed to view this recording's server data.");
            return;
        }
        JSONObject merged = GaugeStore.merge(store.dir(id));
        json(response, merged != null ? merged : new JSONObject());
    }

    private void glowrootData(HttpServletRequest request, HttpServletResponse response, RecordingStore store, Access access, String id)
            throws IOException, JSONException {
        if (viewable(response, store, access, id) == null) {
            return;
        }
        if (!access.canViewServerData(store.readMeta(id))) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "You are not allowed to view this recording's server data.");
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

    private void retryTraces(HttpServletRequest request, HttpServletResponse response, RecordingStore store, Access access, String id)
            throws IOException, JSONException {
        if (viewable(response, store, access, id) == null) {
            return;
        }
        if (!access.canViewServerData(store.readMeta(id))) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "You are not allowed to view this recording's server data.");
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

    private void trace(HttpServletRequest request, HttpServletResponse response, RecordingStore store, Access access, String id)
            throws IOException {
        if (viewable(response, store, access, id) == null) {
            return;
        }
        if (!access.canViewServerData(store.readMeta(id))) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "You are not allowed to view this recording's server data.");
            return;
        }
        String name = request.getParameter("file");
        response.setHeader("Content-Security-Policy", "sandbox allow-scripts allow-popups");
        response.setHeader("Referrer-Policy", "no-referrer");
        sendFile(request, response, store.traceFile(id, name), "text/html;charset=UTF-8", name);
    }

    private JSONObject attachServerCapture(RecordingStore store, String recId, String captureId, String owner, boolean withApps)
            throws JSONException {
        ServerCapture.close(captureId);
        try {
            JSONObject summary = ServerCapture.store().attach(captureId, owner, store.path(recId, RecordingStore.SYSTEM_LOG), withApps);
            if (summary != null) {
                return summary;
            }
        } catch (IOException e) {
            LogUtil.error(getClassName(), e, "Could not attach the server capture of recording " + recId);
        }
        return new JSONObject().put("status", "unavailable");
    }

    private JSONObject viewable(HttpServletResponse response, RecordingStore store, Access access, String id) throws IOException {
        JSONObject meta = store.readMeta(id);
        if (meta == null || !access.canView(meta)) {
            error(response, HttpServletResponse.SC_NOT_FOUND, "Recording not found.");
            return null;
        }
        return meta;
    }

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
            return false;
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

    static void error(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        try {
            json(response, new JSONObject().put("error", new JSONObject().put("code", String.valueOf(status)).put("message", message)));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    class Access {
        final HttpServletRequest request;
        final User user;
        final boolean admin;

        Access(HttpServletRequest request, User user, boolean admin) {
            this.request = request;
            this.user = user;
            this.admin = admin;
        }

        boolean mayCapture(String setting) {
            return allows(setting, false);
        }

        boolean allows(String setting, boolean dflt) {
            return admin || flag(setting, dflt);
        }

        boolean canUpload() {
            return admin || allowed("canUpload", true);
        }

        boolean canView(JSONObject meta) {
            return admin || isOwner(meta);
        }

        boolean canCaptureServerData() {
            return admin || allowed("canCaptureServerData", false);
        }

        boolean canViewServerData(JSONObject meta) {
            return canCaptureServerData() || (meta != null && isOwner(meta));
        }

        boolean canDelete(JSONObject meta) {
            return admin || isOwner(meta);
        }

        boolean canExportApp(AppDefinition def) {
            return isAppAdmin(def) || (allowed("canExportOtherApps", false) && canAccessApp(def));
        }

        boolean isAppAdmin(AppDefinition def) {
            return admin || AppAdmins.admits(AppAdmins.read(appSrc(), SecurityUtil.normalizedFileName(def.getAppId())),
                    user.getUsername(), groupIds());
        }

        boolean mayExportApps() {
            return admin || allowed("canExportOtherApps", false) || AppAdmins.anyAdmits(appSrc(), user.getUsername(), groupIds());
        }

        private boolean canAccessApp(AppDefinition def) {
            if (def.getUserviewDefinitionList() == null) {
                return false;
            }
            UserviewService userviews = (UserviewService) AppUtil.getApplicationContext().getBean("userviewService");
            for (UserviewDefinition uv : def.getUserviewDefinitionList()) {
                try {
                    UserviewSetting setting = userviews.getUserviewSetting(def, uv.getJson());
                    if (setting != null && setting.isIsAuthorize()) {
                        return true;
                    }
                } catch (RuntimeException e) {
                    LogUtil.warn(getClassName(), "Could not check userview " + uv.getId() + " of app " + def.getAppId() + ": " + e);
                }
            }
            return false;
        }

        private java.util.Set<String> groupIds;

        private java.util.Set<String> groupIds() {
            if (groupIds == null) {
                groupIds = new java.util.HashSet<>();
                DirectoryManager dm = (DirectoryManager) AppUtil.getApplicationContext().getBean("directoryManager");
                java.util.Collection<Group> groups = dm.getGroupByUsername(user.getUsername());
                for (Group g : groups != null ? groups : java.util.Collections.<Group>emptyList()) {
                    groupIds.add(g.getId());
                }
            }
            return groupIds;
        }

        private Path appSrc() {
            return Paths.get(SetupManager.getBaseDirectory(), "app_src");
        }

        private boolean isOwner(JSONObject meta) {
            return user.getUsername().equals(meta.optString("owner"));
        }

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
                return false;
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
