package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class SampleDataTest {

    private static final byte[] KEY = "test key".getBytes(StandardCharsets.UTF_8);
    private static final String P1 = "6c1c0e5e-1111-4a6b-9a39-1c2a3b4c5d6e";
    private static final String C1 = "7d2d1f6f-2222-4b7c-8b4a-2d3b4c5d6e7f";

    private final Masker masker = new Masker(Masker.DEFAULT_SENSITIVE, KEY);
    private final Masker.IdMap none = new Masker.IdMap(false);

    @Test
    public void maskKeepsTheShape() {
        String m = masker.value("name", "Ali bin Abu-Bakar, 3rd!", Collections.emptySet(), none);
        assertEquals(23, m.length());
        assertTrue(m, m.matches("[A-Z][a-z]{2} [a-z]{3} [A-Z][a-z]{2}-[A-Z][a-z]{4}, \\d[a-z]{2}!"));
        assertNotEquals("Ali bin Abu-Bakar, 3rd!", m);
        assertEquals("the same value, the same fake", m, masker.value("other", "Ali bin Abu-Bakar, 3rd!", Collections.emptySet(), none));
        assertNotEquals("another key, another fake", m,
                new Masker(null, "other".getBytes(StandardCharsets.UTF_8)).value("name", "Ali bin Abu-Bakar, 3rd!", Collections.emptySet(), none));
        assertEquals("", masker.value("name", "", Collections.emptySet(), none));
        assertEquals("  ", masker.shape("  "));
    }

    @Test
    public void keepsOptionsFlagsAndSmallNumbers() {
        Set<String> options = new HashSet<>(Arrays.asList("approved", "rejected", "hr"));
        assertEquals("approved", masker.value("status", "approved", options, none));
        assertEquals("approved;hr", masker.value("depts", "approved;hr", options, none));
        assertEquals("true", masker.value("flag", "true", options, none));
        assertEquals("12345.67", masker.value("amount", "12345.67", options, none));
        assertNotEquals("phone-like numbers are masked", "0123456789", masker.value("x", "0123456789", options, none));
        assertNotEquals("sensitive columns always", "approved", masker.value("homeAddress", "approved", options, none));
        assertNotEquals("12", masker.value("ic_no", "12", options, none));
    }

    @Test
    public void hashVariablesInsideValuesAreKept() {
        String v = "DE#form.mt_year.y[{date.yyyy}]#-02-01-12204#form.org.prefix[{currentUser.username}]#";
        String m = masker.value("caseNo", v, Collections.emptySet(), none);
        assertTrue(m, m.matches("[A-Z]{2}#form\\.mt_year\\.y\\[\\{date\\.yyyy\\}\\]#-\\d{2}-\\d{2}-\\d{5}#form\\.org\\.prefix\\[\\{currentUser\\.username\\}\\]#"));
        assertNotEquals(v, m);
    }

    @Test
    public void datesKeepTheirYearAndStayValid() {
        String d = masker.value("dob", "1985-07-21 10:30:00", Collections.emptySet(), none);
        assertTrue(d, d.matches("1985-(0[1-9]|1[0-2])-(0[1-9]|1\\d|2[0-8]) 10:30:00"));
    }

    @Test
    public void jsonIsMaskedLeafByLeaf() throws Exception {
        String v = masker.value("items", "[{\"desc\":\"Laptop for Siti\",\"qty\":2,\"email\":\"a@b.com\",\"status\":\"ok\"}]",
                new HashSet<>(Collections.singletonList("ok")), none);
        JSONObject row = new JSONArray(v).getJSONObject(0);
        assertEquals(15, row.getString("desc").length());
        assertNotEquals("Laptop for Siti", row.getString("desc"));
        assertEquals(2, row.getInt("qty"));
        assertEquals("ok", row.getString("status"));
        assertTrue(row.getString("email").matches("[a-z]@[a-z]\\.[a-z]{3}"));
    }

    @Test
    public void sensitiveWordsMatchWholeWords() {
        assertTrue(masker.isSensitive("ic_no"));
        assertTrue(masker.isSensitive("homeAddress"));
        assertTrue(masker.isSensitive("email-2"));
        assertFalse("price has 'ic' inside, not as a word", masker.isSensitive("price"));
        assertFalse(masker.isSensitive("status"));
    }

    @Test
    public void idsAreReplacedEverywhereAndNeverMasked() {
        Masker.IdMap ids = new Masker.IdMap(false);
        ids.add(P1);
        ids.add(C1);
        String newP = ids.get(P1);
        assertNotEquals(P1, newP);
        assertEquals(newP, masker.value("parentId", P1, Collections.emptySet(), ids));
        assertEquals(newP + ";" + ids.get(C1), masker.value("links", P1 + ";" + C1, Collections.emptySet(), ids));
        String mixed = masker.value("note", "see " + P1 + " now", Collections.emptySet(), ids);
        assertTrue(mixed, mixed.matches("[a-z]{3} " + newP + " [a-z]{3}"));
        Masker.IdMap kept = new Masker.IdMap(true);
        kept.add(P1);
        assertEquals(P1, kept.get(P1));
    }

    @Test
    public void candidateIdsComeFromUrlsAndPosts() {
        Set<String> ids = SampleData.candidateIds(Arrays.asList(
                "/jw/web/userview/leave/v/_/list?id=" + P1,
                "/jw/web/client/app/leave/1/assignment/45_12_leave_process1_approve",
                "{\"id\":\"rec-77\",\"name\":\"x\"}",
                "/jw/web/json/workflow/currentUsername"));
        assertTrue(ids.contains(P1));
        assertTrue(ids.contains("45_12_leave_process1_approve"));
        assertTrue(ids.contains("rec-77"));
        assertEquals(3, ids.size());
    }

    /** Tables: leave (parent rows) and leave_item (grid rows pointing at the parent by "parentId"), plus an untouched one. */
    private static class Fake implements SampleData.Source {
        final Map<String, List<FormRow>> data = new LinkedHashMap<>();
        final Map<String, List<File>> files = new HashMap<>();

        void row(String table, String id, String... kv) {
            FormRow r = new FormRow();
            r.setId(id);
            for (int i = 0; i < kv.length; i += 2) {
                r.setProperty(kv[i], kv[i + 1]);
            }
            data.computeIfAbsent(table, k -> new ArrayList<>()).add(r);
        }

        @Override
        public FormRow load(String table, String id) {
            for (FormRow r : data.getOrDefault(table, Collections.emptyList())) {
                if (id.equals(r.getId())) {
                    return r;
                }
            }
            return null;
        }

        @Override
        public FormRowSet findIn(String table, String column, Collection<String> values, int max) {
            FormRowSet s = new FormRowSet();
            for (FormRow r : data.getOrDefault(table, Collections.emptyList())) {
                if (values.contains(r.getProperty(column))) {
                    s.add(r);
                }
            }
            return s;
        }

        @Override
        public FormRowSet newest(String table, int max) {
            FormRowSet s = new FormRowSet();
            for (FormRow r : data.getOrDefault(table, Collections.emptyList())) {
                if (s.size() < max) {
                    s.add(r);
                }
            }
            return s;
        }

        @Override
        public Collection<String> columns(String table) {
            Set<String> c = new HashSet<>();
            for (FormRow r : data.getOrDefault(table, Collections.emptyList())) {
                for (Object k : r.keySet()) {
                    c.add(String.valueOf(k));
                }
            }
            return c;
        }

        @Override
        public List<File> files(String table, String id) {
            return files.getOrDefault(table + "/" + id, Collections.emptyList());
        }

        @Override
        public String processOf(String activityId) {
            return null;
        }
    }

    private static Map<String, Set<String>> tables(String... names) {
        Map<String, Set<String>> t = new LinkedHashMap<>();
        for (String n : names) {
            t.put(n, new HashSet<>(Collections.singletonList("approved")));
        }
        return t;
    }

    @Test
    public void picksTouchedLinkedAndNewestRows() throws Exception {
        Fake src = new Fake();
        src.row("leave", P1, "name", "Siti Aminah", "status", "approved", "createdBy", "siti", "photo", "me.jpg");
        src.row("leave", "other", "name", "Not touched");
        src.row("leave_item", C1, "parentId", P1, "desc", "Flight");
        src.row("leave_item", "c2", "parentId", "other", "desc", "Hotel");
        src.row("setting", "s1", "key", "max days");
        Path upload = Files.createTempDirectory("up");
        File photo = upload.resolve("me.jpg").toFile();
        Files.write(photo.toPath(), new byte[1234]);
        src.files.put("leave/" + P1, Collections.singletonList(photo));

        Masker.IdMap ids = new Masker.IdMap(false);
        List<SampleData.Table> out = SampleData.build(tables("leave", "leave_item", "setting"), Collections.singleton(P1), src,
                SampleData.Mode.MASKED, masker, ids, 5);
        assertEquals(3, out.size());
        SampleData.Table leave = out.get(0);
        assertEquals("touched", leave.picked);
        assertEquals("only the touched row", 1, leave.rows.size());
        FormRow p = leave.rows.get(0);
        assertEquals(ids.get(P1), p.getId());
        assertNotEquals(P1, p.getId());
        assertEquals(11, p.getProperty("name").length());
        assertNotEquals("Siti Aminah", p.getProperty("name"));
        assertEquals("approved", p.getProperty("status"));
        assertEquals("user1", p.getProperty("createdBy"));
        assertTrue(leave.masked.contains("name"));
        assertFalse(leave.masked.contains("status"));
        // the file: a placeholder under the new id, the column names it
        assertEquals(1, leave.files.size());
        String path = leave.files.keySet().iterator().next();
        assertEquals("app_formuploads/leave/" + p.getId() + "/" + p.getProperty("photo"), path);
        assertTrue(p.getProperty("photo").endsWith(".jpg"));
        assertTrue(new String(leave.files.get(path), StandardCharsets.UTF_8).contains("1234 bytes"));

        SampleData.Table items = out.get(1);
        assertEquals("linked", items.picked);
        assertEquals(1, items.rows.size());
        assertEquals("the grid row points at the parent's new id", p.getId(), items.rows.get(0).getProperty("parentId"));
        assertFalse("new ids are not masking", items.masked.contains("parentId"));
        assertEquals(ids.get(C1), items.rows.get(0).getId());

        assertEquals("newest", out.get(2).picked);
        assertEquals(1, out.get(2).rows.size());
    }

    @Test
    public void realValuesKeepAllButSensitiveColumns() throws Exception {
        Fake src = new Fake();
        src.row("leave", P1, "name", "Siti Aminah", "email", "siti@x.com", "createdBy", "siti");
        Masker.IdMap ids = new Masker.IdMap(false);
        FormRow p = SampleData.build(tables("leave"), Collections.singleton(P1), src, SampleData.Mode.REAL, masker, ids, 0)
                .get(0).rows.get(0);
        assertEquals("Siti Aminah", p.getProperty("name"));
        assertEquals("siti", p.getProperty("createdBy"));
        assertNotEquals("siti@x.com", p.getProperty("email"));
        assertEquals(ids.get(P1), p.getId());
    }

    @Test
    public void addsTheDataToTheJwa() throws Exception {
        Path jwa = Files.createTempFile("app", ".jwa");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jwa))) {
            z.putNextEntry(new ZipEntry("appDefinition.xml"));
            z.write("<appDefinition/>".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        Fake src = new Fake();
        src.row("leave", P1, "name", "Siti");
        List<SampleData.Table> t = SampleData.build(tables("leave"), Collections.singleton(P1), src, SampleData.Mode.MASKED,
                masker, new Masker.IdMap(false), 0);
        SampleData.addTo(jwa, t);
        try (ZipFile z = new ZipFile(jwa.toFile())) {
            assertNotNull(z.getEntry("appDefinition.xml"));
            ZipEntry data = z.getEntry("data/leave.json");
            assertNotNull(data);
            JSONArray rows = new JSONArray(new String(readAll(z, data), StandardCharsets.UTF_8));
            assertEquals(1, rows.length());
            assertEquals(4, rows.getJSONObject(0).getString("name").length());
        }
        JSONObject summary = SampleData.summary(t, SampleData.Mode.MASKED);
        assertEquals("masked", summary.getString("mode"));
        assertEquals(1, summary.getJSONArray("tables").getJSONObject(0).getInt("rows"));
    }

    @Test
    public void rowsWithDatesAreWrittenAfterTheRequestEnded() throws Exception {
        Path jwa = Files.createTempFile("app", ".jwa");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jwa))) {
            z.putNextEntry(new ZipEntry("appDefinition.xml"));
            z.write("<appDefinition/>".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        SampleData.Table t = new SampleData.Table("leave");
        FormRow row = new FormRow();
        row.setId(P1);
        row.setProperty("name", "Siti");
        java.util.Date created = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2026-07-10 14:56:15");
        row.setDateCreated(created);
        row.setDateModified(created);
        t.rows.add(row);
        // The export thread: the locale comes from a request that has ended (Tomcat: "The request object has been recycled")
        org.springframework.context.i18n.LocaleContextHolder.setLocaleContext(() -> {
            throw new IllegalStateException("The request object has been recycled and is no longer associated with this facade");
        });
        try {
            SampleData.addTo(jwa, Collections.singletonList(t));
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
        String json;
        try (ZipFile z = new ZipFile(jwa.toFile())) {
            json = new String(readAll(z, z.getEntry("data/leave.json")), StandardCharsets.UTF_8);
        }
        JSONObject written = new JSONArray(json).getJSONObject(0);
        assertEquals(P1, written.getString("id"));
        assertEquals("2026-07-10 14:56:15", written.getString("dateCreated"));
        assertEquals("Siti", written.getString("name"));
        // Joget's import (FormUtil.jsonToFormRowSet) reads the dates with this pattern
        java.text.SimpleDateFormat importFormat = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        assertEquals(created, importFormat.parse(written.getString("dateCreated")));
        assertEquals(created, importFormat.parse(written.getString("dateModified")));
    }

    @Test
    public void realFilesAreStreamedIntoTheJwa() throws Exception {
        Path jwa = Files.createTempFile("app", ".jwa");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jwa))) {
            z.putNextEntry(new ZipEntry("appDefinition.xml"));
            z.write("<appDefinition/>".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        Fake src = new Fake();
        src.row("leave", P1, "name", "Siti", "photo", "me.jpg");
        Path upload = Files.createTempDirectory("up");
        File photo = upload.resolve("me.jpg").toFile();
        byte[] bytes = new byte[300 * 1024];
        new java.util.Random(7).nextBytes(bytes);
        Files.write(photo.toPath(), bytes);
        src.files.put("leave/" + P1, Collections.singletonList(photo));
        List<SampleData.Table> t = SampleData.build(tables("leave"), Collections.singleton(P1), src, SampleData.Mode.REAL,
                masker, new Masker.IdMap(false), 0);
        assertEquals("not held in memory", 0, t.get(0).files.size());
        assertEquals(1, t.get(0).fileSources.size());
        SampleData.addTo(jwa, t);
        String name = t.get(0).fileSources.keySet().iterator().next();
        try (ZipFile z = new ZipFile(jwa.toFile())) {
            assertArrayEquals(bytes, readAll(z, z.getEntry(name)));
        }
        assertEquals(1, SampleData.summary(t, SampleData.Mode.REAL).getJSONArray("tables").getJSONObject(0).getInt("files"));
    }

    @Test
    public void followsProcessIdsAndReferences() throws Exception {
        Fake src = new Fake() {
            @Override
            public String processOf(String activityId) {
                return activityId.equals("82_70_app_proc_approve") ? "70_app_proc" : null;
            }
        };
        src.row("req", "REQ-0001", "title", "Printer broken");
        src.row("req", "REQ-0002", "title", "Not this one");
        src.row("trail", "t1", "processId", "70_app_proc", "parentId", "REQ-0001", "status", "approved");
        src.row("trail", "t2", "processId", "99_app_proc", "parentId", "REQ-0002");
        Masker.IdMap ids = new Masker.IdMap(false);
        List<SampleData.Table> out = SampleData.build(tables("req", "trail"), Collections.singleton("82_70_app_proc_approve"), src,
                SampleData.Mode.MASKED, masker, ids, 0);
        assertEquals(2, out.size());
        SampleData.Table req = out.get(0);
        assertEquals("the trail's parent request, one step away", 1, req.rows.size());
        assertEquals(ids.get("REQ-0001"), req.rows.get(0).getId());
        SampleData.Table trail = out.get(1);
        assertEquals(1, trail.rows.size());
        assertEquals("70_app_proc", trail.rows.get(0).getProperty("processId"));
        assertEquals(ids.get("REQ-0001"), trail.rows.get(0).getProperty("parentId"));
    }

    @Test
    public void keepsWhatTheAppDefinitionSaysAndPseudonymsUsersEverywhere() throws Exception {
        Masker m = new Masker(null, KEY);
        m.setDefinition("<transition condition=\"status=='approved'\"/> &quot;#form.org.name[{currentUser.username}]#&quot; toolRequest");
        assertEquals("approved", m.value("status", "approved", Collections.emptySet(), none));
        assertEquals("toolRequest", m.value("activityDefID", "toolRequest", Collections.emptySet(), none));
        assertEquals("#form.org.name[{currentUser.username}]#", m.value("pos", "#form.org.name[{currentUser.username}]#", Collections.emptySet(), none));
        assertNotEquals("Siti", m.value("name", "Siti", Collections.emptySet(), none));

        Fake src = new Fake();
        src.row("req", P1, "approver", "clark", "createdBy", "clark");
        FormRow r = SampleData.build(tables("req"), Collections.singleton(P1), src, SampleData.Mode.MASKED, m, new Masker.IdMap(false), 0)
                .get(0).rows.get(0);
        assertEquals("user1", r.getProperty("createdBy"));
        assertEquals("the same pseudonym in other columns", "user1", r.getProperty("approver"));
    }

    private static byte[] readAll(ZipFile z, ZipEntry e) throws Exception {
        try (java.io.InputStream in = z.getInputStream(e)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) != -1) {
                out.write(b, 0, n);
            }
            return out.toByteArray();
        }
    }
}
