package org.joget.blackbox;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
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

    static final String VERSION = "8.2-PREVIEW";
    private static final long DEFAULT_MAX_MB = 500;
    private static final Pattern RANGE = Pattern.compile("^bytes=(\\d*)-(\\d*)$");

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
        return "Stores and replays recordings (video, console and network log)";
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
                        .put("maxSizeMb", maxSizeMb()));
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
                    json(response, meta);
                }
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
        LogUtil.info(getClassName(), "Recording " + meta.getString("id") + " uploaded by " + access.user.getUsername());
        // url: where the extension's "Open uploaded" link points
        meta.put("url", request.getContextPath() + "/blackbox/view?id=" + meta.getString("id"));
        json(response, meta);
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
        try {
            long mb = Long.parseLong(getPropertyString("maxSizeMb").trim());
            return mb > 0 ? mb : DEFAULT_MAX_MB;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_MB;
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
