package org.joget.marketplace.blackbox;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.model.FormDefinition;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.apps.form.service.FormUtil;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Sample form data for an exported app: the records the recording touched (their ids in the recorded URLs and form
 * posts), the rows of the app's other tables that point at them, and, for tables with none of those, the newest rows.
 * Added to the .jwa the way Joget's own export adds data (data/&lt;table&gt;.json, app_formuploads/&lt;table&gt;/&lt;id&gt;/), so
 * importing the .jwa imports them too.
 * <ul>
 * <li>Masked (the default): values through {@link Masker}; user names become user1, user2...; uploaded files become
 * small placeholder files with masked names (the real ones are not copied).</li>
 * <li>Real: values and files as they are, except columns {@link Masker#isSensitive} names.</li>
 * </ul>
 * Record ids get new UUIDs everywhere they appear (unless kept), so importing never overwrites rows of the same id;
 * the recording's app/apps.json lists old -&gt; new ("idMap") so support can match them to the URLs and logs.
 */
final class SampleData {

    static final int MAX_IDS = 200;
    static final int MAX_CHILDREN = 50;
    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    static final long MAX_FILES_BYTES = 50L * 1024 * 1024;

    /** Record ids are UUIDs, or Joget process / activity ids (123_app_process, 45_123_app_process_activity). */
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern WORKFLOW_ID = Pattern.compile("(?<![A-Za-z0-9_])\\d{1,12}_[A-Za-z0-9][A-Za-z0-9_-]*_[A-Za-z0-9_-]+");
    private static final Pattern ID_PARAM = Pattern.compile("(?:^|[?&;\"])(?:id|_id|recordId|fk|parentId|processId|activityId)(?:\"\\s*:\\s*\"|=)([A-Za-z0-9_.-]{1,100})");
    private static final Set<String> SYSTEM = new HashSet<>(java.util.Arrays.asList(FormUtil.PROPERTY_ID,
            FormUtil.PROPERTY_DATE_CREATED, FormUtil.PROPERTY_DATE_MODIFIED, "createdBy", "modifiedBy", "createdByName", "modifiedByName"));

    enum Mode {
        MASKED, REAL;
    }

    /** What the data comes from: Joget's FormDataDao and upload folder (see {@link #joget}), or a test double. */
    interface Source {
        FormRow load(String table, String id);

        /** Rows whose column holds one of the values. */
        FormRowSet findIn(String table, String column, Collection<String> values, int max);

        FormRowSet newest(String table, int max);

        Collection<String> columns(String table);

        /** The uploaded files of a row, or an empty list. */
        List<File> files(String table, String id);

        /** For an activity id, its process id; else null. */
        String processOf(String activityId);
    }

    private SampleData() {
    }

    /** Candidate record ids in the recorded URLs and form posts. */
    static Set<String> candidateIds(Iterable<String> texts) {
        Set<String> ids = new LinkedHashSet<>();
        for (String t : texts) {
            if (t == null) {
                continue;
            }
            for (Pattern p : new Pattern[]{UUID, WORKFLOW_ID}) {
                Matcher m = p.matcher(t);
                while (m.find() && ids.size() < MAX_IDS) {
                    ids.add(m.group());
                }
            }
            Matcher m = ID_PARAM.matcher(t);
            while (m.find() && ids.size() < MAX_IDS) {
                ids.add(m.group(1));
            }
        }
        return ids;
    }

    /** Kept of the request bodies (the extension sends up to 64 KB of each): enough for the ids, bounded in memory. */
    static final int MAX_POST_CHARS = 64 * 1024;
    static final long MAX_POST_TOTAL = 16L * 1024 * 1024;

    /** The request bodies the browser sent (form posts carry the record id). */
    static List<String> postData(Path session) {
        List<String> list = new ArrayList<>();
        if (session == null || !Files.isRegularFile(session)) {
            return list;
        }
        try {
            long[] kept = {0};
            SessionFile.each(session, "network", java.util.Collections.singleton("postData"), e -> { // one entry at a time, only postData
                String post = e.optString("postData");
                if (!post.isEmpty() && kept[0] < MAX_POST_TOTAL) {
                    post = post.length() > MAX_POST_CHARS ? post.substring(0, MAX_POST_CHARS) : post; // ids are near the start
                    kept[0] += post.length();
                    list.add(post);
                }
            });
        } catch (IOException | RuntimeException e) {
            // unreadable: none
        }
        return list;
    }

    /** One table's sample: its rows (masked, ids replaced), files to add, and which columns were masked. */
    static final class Table {
        final String name;
        final FormRowSet rows = new FormRowSet();
        final Map<String, byte[]> files = new LinkedHashMap<>(); // app_formuploads/... -> content (placeholders)
        final Map<String, Path> fileSources = new LinkedHashMap<>(); // app_formuploads/... -> real file, streamed into the .jwa
        long sourceBytes; // their total size, for the per-table limit
        final Set<String> masked = new TreeSet<>();
        String picked; // "touched", "linked", "newest"

        Table(String name) {
            this.name = name;
        }
    }

    /** The app's form tables, each with the option values of its forms' select boxes, radios and check boxes. */
    static Map<String, Set<String>> tables(AppDefinition def) {
        Map<String, Set<String>> tables = new LinkedHashMap<>();
        Collection<FormDefinition> forms = def.getFormDefinitionList();
        for (FormDefinition f : forms != null ? forms : Collections.<FormDefinition>emptyList()) {
            if (f.getTableName() == null || f.getTableName().isEmpty()) {
                continue;
            }
            Set<String> options = tables.computeIfAbsent(f.getTableName(), k -> new HashSet<>());
            try {
                collectOptions(new JSONObject(f.getJson()), options);
            } catch (RuntimeException e) { // JSONException included
                // a form we can't read: no options from it
            }
        }
        return tables;
    }

    private static void collectOptions(Object json, Set<String> options) {
        if (json instanceof JSONObject) {
            JSONObject o = (JSONObject) json;
            JSONArray list = o.optJSONArray("options");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject opt = list.optJSONObject(i);
                if (opt != null && opt.has("value")) {
                    options.add(opt.optString("value"));
                }
            }
            for (java.util.Iterator<?> it = o.keys(); it.hasNext();) {
                collectOptions(o.opt(String.valueOf(it.next())), options);
            }
        } else if (json instanceof JSONArray) {
            JSONArray a = (JSONArray) json;
            for (int i = 0; i < a.length(); i++) {
                collectOptions(a.opt(i), options);
            }
        }
    }

    /**
     * Picks and converts the sample rows of these tables. ids is shared by all apps of the recording (one id map).
     */
    static List<Table> build(Map<String, Set<String>> tables, Set<String> candidates, Source src, Mode mode, Masker masker,
            Masker.IdMap ids, int newest) {
        // Activity ids point at their process, whose id is the record id of workflow forms
        Set<String> wanted = new LinkedHashSet<>(candidates);
        for (String c : candidates) {
            String process = WORKFLOW_ID.matcher(c).matches() ? src.processOf(c) : null;
            if (process != null) {
                wanted.add(process);
            }
        }
        Map<String, Table> picked = new LinkedHashMap<>();
        Set<String> found = new LinkedHashSet<>();
        for (String table : tables.keySet()) {
            Table t = new Table(table);
            for (String id : wanted) {
                FormRow row = safeLoad(src, table, id);
                if (row != null) {
                    t.rows.add(row);
                    found.add(id);
                }
            }
            t.picked = t.rows.isEmpty() ? null : "touched";
            picked.put(table, t);
        }
        // Rows pointing at the touched ones or at a recorded id (grids, subforms, child forms, logs by process id)
        found.addAll(wanted);
        if (!found.isEmpty()) {
            for (Table t : picked.values()) {
                Set<String> have = idsOf(t.rows);
                for (String column : src.columns(t.name)) {
                    if (SYSTEM.contains(column)) {
                        continue;
                    }
                    for (FormRow row : src.findIn(t.name, column, found, MAX_CHILDREN)) {
                        if (have.add(row.getId())) {
                            t.rows.add(row);
                            t.picked = t.picked != null ? t.picked : "linked";
                        }
                    }
                }
            }
        }
        // One step further: the rows those rows point at (a log row's parent request)
        Set<String> refs = new LinkedHashSet<>();
        for (Table t : picked.values()) {
            for (FormRow row : t.rows) {
                for (Object k : row.keySet()) {
                    Object v = row.get(k);
                    if (!SYSTEM.contains(String.valueOf(k)) && v instanceof String && isIdLike((String) v) && !found.contains(v)
                            && refs.size() < MAX_IDS) {
                        refs.add((String) v);
                    }
                }
            }
        }
        for (Table t : picked.values()) {
            Set<String> have = idsOf(t.rows);
            for (String id : refs) {
                FormRow row = have.contains(id) ? null : safeLoad(src, t.name, id);
                if (row != null) {
                    t.rows.add(row);
                    have.add(id);
                    t.picked = t.picked != null ? t.picked : "linked";
                }
            }
        }
        for (Table t : picked.values()) {
            if (t.rows.isEmpty() && newest > 0) {
                t.rows.addAll(src.newest(t.name, newest));
                t.picked = t.rows.isEmpty() ? null : "newest";
            }
            for (FormRow row : t.rows) {
                ids.add(row.getId());
            }
        }
        if (mode == Mode.MASKED) {
            for (Table t : picked.values()) {
                for (FormRow row : t.rows) {
                    masker.knowUser(row.getCreatedBy());
                    masker.knowUser(row.getModifiedBy());
                }
            }
        }
        List<Table> result = new ArrayList<>();
        for (Table t : picked.values()) {
            if (t.rows.isEmpty()) {
                continue;
            }
            FormRowSet converted = new FormRowSet();
            for (FormRow row : t.rows) {
                converted.add(convert(t, row, tables.get(t.name), src, mode, masker, ids));
            }
            t.rows.clear();
            t.rows.addAll(converted);
            result.add(t);
        }
        return result;
    }

    /** A value that could be a record id: one token of 5 to 100 id characters with a digit. */
    static boolean isIdLike(String v) {
        return v.length() >= 5 && v.length() <= 100 && v.matches("[A-Za-z0-9_.-]+") && v.matches(".*\\d.*");
    }

    private static FormRow safeLoad(Source src, String table, String id) {
        try {
            return src.load(table, id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Set<String> idsOf(FormRowSet rows) {
        Set<String> s = new HashSet<>();
        for (FormRow r : rows) {
            s.add(r.getId());
        }
        return s;
    }

    private static FormRow convert(Table t, FormRow row, Set<String> options, Source src, Mode mode, Masker masker, Masker.IdMap ids) {
        FormRow out = new FormRow();
        String oldId = row.getId();
        String newId = ids.get(oldId);
        Map<String, String> renamedFiles = new java.util.HashMap<>();
        // Uploaded files: placeholders with the masked name (masked), or the files (real), under the new id
        long total = 0;
        for (Map.Entry<String, byte[]> e : t.files.entrySet()) {
            total += e.getValue().length;
        }
        total += t.sourceBytes;
        for (File f : src.files(t.name, oldId)) {
            String name = f.getName();
            String target;
            byte[] content;
            if (mode == Mode.MASKED) {
                target = masker.fileName(name);
                content = ("BlackBox placeholder for an uploaded file (" + f.length() + " bytes, original not copied).\n")
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                target = name;
                if (f.length() > MAX_FILE_BYTES || total + f.length() > MAX_FILES_BYTES) {
                    content = ("BlackBox placeholder: the file (" + f.length() + " bytes) is larger than the sample limit.\n")
                            .getBytes(StandardCharsets.UTF_8);
                } else if (f.canRead()) {
                    total += f.length();
                    t.sourceBytes += f.length();
                    renamedFiles.put(name, target);
                    t.fileSources.put("app_formuploads/" + t.name + "/" + newId + "/" + target, f.toPath()); // copied at addTo
                    continue;
                } else {
                    continue;
                }
            }
            total += content.length;
            renamedFiles.put(name, target);
            t.files.put("app_formuploads/" + t.name + "/" + newId + "/" + target, content);
        }
        for (Object k : row.keySet()) {
            String key = String.valueOf(k);
            Object v = row.get(k);
            if (v == null) {
                continue;
            }
            if (FormUtil.PROPERTY_ID.equals(key)) {
                out.put(key, newId);
            } else if (v instanceof java.util.Date) {
                out.put(key, v);
            } else if (key.equals("createdBy") || key.equals("modifiedBy")) {
                out.put(key, mode == Mode.MASKED ? masker.user(String.valueOf(v)) : v);
            } else {
                String s = String.valueOf(v);
                String converted;
                if (isFileList(s, renamedFiles)) {
                    StringBuilder names = new StringBuilder(); // a file field: the names of the files written above
                    for (String n : s.split(";")) {
                        names.append(names.length() > 0 ? ";" : "").append(renamedFiles.get(n));
                    }
                    converted = names.toString();
                } else if (mode == Mode.REAL && !masker.isSensitive(key)) {
                    converted = ids.replaceIn(s, x -> x);
                } else {
                    converted = masker.value(key, s, options, ids);
                }
                if (!converted.equals(s) && !converted.equals(ids.replaceIn(s, x -> x))) {
                    t.masked.add(key); // changed by more than the new record ids
                }
                out.put(key, converted);
            }
        }
        return out;
    }

    private static boolean isFileList(String value, Map<String, String> files) {
        if (files.isEmpty()) {
            return false;
        }
        for (String n : value.split(";")) {
            if (!files.containsKey(n)) {
                return false;
            }
        }
        return true;
    }

    /** The text of the .jwa's app definition and process (for {@link Masker#setDefinition}). */
    static String definitionText(Path jwa) throws IOException {
        StringBuilder text = new StringBuilder();
        try (ZipFile z = new ZipFile(jwa.toFile())) {
            for (String name : new String[]{"appDefinition.xml", "package.xpdl"}) {
                ZipEntry e = z.getEntry(name);
                if (e != null) {
                    try (InputStream in = z.getInputStream(e)) {
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            out.write(buf, 0, n);
                        }
                        text.append(new String(out.toByteArray(), StandardCharsets.UTF_8)).append('\n');
                    }
                }
            }
        }
        return text.toString();
    }

    /** Copies the .jwa with the sample tables (data/ and app_formuploads/) added; replaces the file. */
    static void addTo(Path jwa, List<Table> tables) throws IOException {
        if (tables.isEmpty()) {
            return;
        }
        Path tmp = jwa.resolveSibling(jwa.getFileName() + ".sample");
        try (ZipFile in = new ZipFile(jwa.toFile()); OutputStream os = Files.newOutputStream(tmp);
                ZipOutputStream out = new ZipOutputStream(os)) {
            Set<String> written = new HashSet<>();
            for (Table t : tables) {
                put(out, written, "data/" + t.name + ".json", FormUtil.formRowSetToJson(t.rows, true).getBytes(StandardCharsets.UTF_8));
                for (Map.Entry<String, byte[]> f : t.files.entrySet()) {
                    put(out, written, f.getKey(), f.getValue());
                }
                for (Map.Entry<String, Path> f : t.fileSources.entrySet()) {
                    putFile(out, written, f.getKey(), f.getValue());
                }
            }
            Enumeration<? extends ZipEntry> all = in.entries();
            while (all.hasMoreElements()) {
                ZipEntry e = all.nextElement();
                if (written.contains(e.getName())) {
                    continue; // ours replace Joget's (there are none: the export had no data)
                }
                out.putNextEntry(new ZipEntry(e.getName()));
                try (InputStream is = in.getInputStream(e)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                }
                out.closeEntry();
            }
        }
        Files.move(tmp, jwa, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** A real uploaded file into the .jwa, copied as a stream (it may be several MB). */
    private static void putFile(ZipOutputStream out, Set<String> written, String name, Path file) throws IOException {
        if (!written.add(name)) {
            return;
        }
        out.putNextEntry(new ZipEntry(name));
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
        out.closeEntry();
    }

    private static void put(ZipOutputStream out, Set<String> written, String name, byte[] content) throws IOException {
        if (!written.add(name)) {
            return;
        }
        out.putNextEntry(new ZipEntry(name));
        out.write(content);
        out.closeEntry();
    }

    /** The string values of the converted sample rows (process ids among them are kept as they are). */
    static Set<String> valuesIn(List<Table> tables) {
        Set<String> values = new LinkedHashSet<>();
        for (Table t : tables) {
            for (FormRow row : t.rows) {
                for (Object v : row.values()) {
                    if (v instanceof String && WORKFLOW_ID.matcher((String) v).matches()) {
                        values.add((String) v);
                    }
                }
            }
        }
        return values;
    }

    /** apps.json's summary of one app's sample. */
    static JSONObject summary(List<Table> tables, Mode mode) throws JSONException {
        JSONArray list = new JSONArray();
        for (Table t : tables) {
            list.put(new JSONObject().put("table", t.name).put("rows", t.rows.size()).put("picked", t.picked)
                    .put("files", t.files.size() + t.fileSources.size()).put("masked", new JSONArray(t.masked)));
        }
        return new JSONObject().put("mode", mode.name().toLowerCase(java.util.Locale.ROOT)).put("tables", list);
    }

    /** Adds the id map to the recording's apps.json: "idMap": {"kept": false, "ids": {old: new}}. */
    static void writeIdMap(Path recordingDir, Masker.IdMap ids, boolean kept) throws IOException {
        Path list = AppExport.list(recordingDir);
        if (ids.all().isEmpty() || list == null) {
            return;
        }
        try {
            JSONObject body = new JSONObject(new String(Files.readAllBytes(list), StandardCharsets.UTF_8));
            body.put("idMap", new JSONObject().put("kept", kept).put("ids", new JSONObject(ids.all())));
            Files.write(list, body.toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** Joget's form data and upload folder. Table names are the forms' (without app_fd_). */
    static Source joget() {
        org.joget.apps.form.dao.FormDataDao dao = (org.joget.apps.form.dao.FormDataDao) org.joget.apps.app.service.AppUtil
                .getApplicationContext().getBean("formDataDao");
        org.joget.workflow.model.service.WorkflowManager wm = (org.joget.workflow.model.service.WorkflowManager) org.joget.apps.app.service.AppUtil
                .getApplicationContext().getBean("workflowManager");
        return new Source() {
            @Override
            public FormRow load(String table, String id) {
                return dao.load(table, table, id);
            }

            @Override
            public FormRowSet findIn(String table, String column, Collection<String> values, int max) {
                if (!column.matches("[A-Za-z0-9_]+") || values.isEmpty()) {
                    return new FormRowSet();
                }
                StringBuilder in = new StringBuilder();
                for (int i = 0; i < values.size(); i++) {
                    in.append(i > 0 ? ", ?" : "?");
                }
                try {
                    FormRowSet rows = dao.find(table, table, "WHERE e.customProperties." + column + " IN (" + in + ")",
                            values.toArray(), null, null, 0, max);
                    return rows != null ? rows : new FormRowSet();
                } catch (RuntimeException e) {
                    return new FormRowSet();
                }
            }

            @Override
            public FormRowSet newest(String table, int max) {
                try {
                    FormRowSet rows = dao.find(table, table, null, null, FormUtil.PROPERTY_DATE_MODIFIED, true, 0, max);
                    return rows != null ? rows : new FormRowSet();
                } catch (RuntimeException e) {
                    return new FormRowSet();
                }
            }

            @Override
            public Collection<String> columns(String table) {
                try {
                    Collection<String> c = dao.getFormDefinitionColumnNames(table);
                    return c != null ? c : Collections.<String>emptyList();
                } catch (RuntimeException e) {
                    return Collections.emptyList();
                }
            }

            @Override
            public List<File> files(String table, String id) {
                List<File> list = new ArrayList<>();
                File[] files;
                try {
                    files = new File(org.joget.apps.form.service.FileUtil.getUploadPath(table, id)).listFiles();
                } catch (RuntimeException e) {
                    return list;
                }
                for (File f : files != null ? files : new File[0]) {
                    if (f.isFile() && f.canRead()) {
                        list.add(f);
                    }
                }
                return list;
            }

            @Override
            public String processOf(String activityId) {
                try {
                    org.joget.workflow.model.WorkflowActivity a = wm.getActivityById(activityId);
                    return a != null ? a.getProcessId() : null;
                } catch (RuntimeException e) {
                    return null;
                }
            }
        };
    }
}
