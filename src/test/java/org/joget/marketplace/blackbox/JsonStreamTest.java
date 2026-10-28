package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class JsonStreamTest {

    private static Object parse(String json) throws IOException {
        try (JsonStream js = new JsonStream(new StringReader(json))) {
            Object v = js.nextValue(Long.MAX_VALUE / 4);
            js.end();
            return v;
        }
    }

    private static void malformed(String json) {
        try {
            parse(json);
            fail("accepted: " + json);
        } catch (JsonStream.Malformed e) {
            // expected
        } catch (IOException e) {
            fail("wrong error for " + json + ": " + e);
        }
    }

    @Test
    public void buildsValuesLikeOrgJson() throws Exception {
        String json = "{\"a\":[1,-2.5,3e2,true,false,null,\"x\\\"y\\\\z\\/\\n\\u00e9\"],\"b\":{\"c\":{}},\"d\":[],\"e\":12345678901234567890}";
        JSONObject o = (JSONObject) parse(json);
        JSONArray a = o.getJSONArray("a");
        assertEquals(1L, a.getLong(0));
        assertEquals(-2.5, a.getDouble(1), 0);
        assertEquals(300, a.getDouble(2), 0);
        assertTrue(a.getBoolean(3));
        assertTrue(a.isNull(5));
        assertEquals("x\"y\\z/\n\u00e9", a.getString(6));
        assertEquals(0, o.getJSONObject("b").getJSONObject("c").length());
        assertEquals(0, o.getJSONArray("d").length());
        assertEquals(new JSONObject(json).getJSONArray("a").getString(6), a.getString(6));
        assertEquals("12345678901234567890", o.get("e").toString());
    }

    @Test
    public void rejectsWhatIsNotJson() {
        for (String bad : new String[]{"", "{", "{\"a\"}", "{\"a\":1,}", "[1,]", "[1 2]", "{'a':1}", "{\"a\":tru}", "\"ab",
                "\"a\u0001\"", "\"\\x\"", "01", "1.", "-", "[1]x", "{}{}", "\"\\u12g4\""}) {
            malformed(bad);
        }
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < JsonStream.MAX_DEPTH + 5; i++) {
            deep.append('[');
        }
        malformed(deep.toString());
    }

    @Test
    public void capsAValueAndSkipsWithoutBuilding() throws Exception {
        try (JsonStream js = new JsonStream(new StringReader("[\"" + new String(new char[5000]).replace('\0', 'a') + "\",2]"))) {
            js.beginArray();
            assertTrue(js.hasNext());
            try {
                js.nextValue(1000);
                fail("too large");
            } catch (JsonStream.TooLarge e) {
                // expected
            }
        }
        try (JsonStream js = new JsonStream(new StringReader("{\"skip\":{\"x\":[1,2,{\"y\":\"z\"}]},\"keep\":7}"))) {
            js.beginObject();
            assertTrue(js.hasNext());
            assertEquals("skip", js.nextName());
            js.skipValue(100);
            assertTrue(js.hasNext());
            assertEquals("keep", js.nextName());
            assertEquals(7L, js.nextValue(10));
            assertFalse(js.hasNext());
            js.endObject();
            js.end();
        }
    }

    @Test
    public void sessionFileIsCheckedAndWalkedAsAStream() throws Exception {
        Path f = Files.createTempFile("session", ".json");
        StringBuilder sb = new StringBuilder("{\"tool\":\"bb\",\"recording\":{\"tab\":{\"title\":\"T\"}},\"console\":[{\"level\":\"error\",\"t\":5}],\"network\":[");
        for (int i = 0; i < 2000; i++) {
            sb.append(i > 0 ? "," : "").append("{\"t\":").append(i).append(",\"url\":\"/jw/web/userview/app").append(i % 3)
                    .append("/v\",\"responseBody\":\"").append(new String(new char[500]).replace('\0', 'b')).append("\"}");
        }
        sb.append("],\"summary\":{\"requests\":2000}}");
        Files.write(f, sb.toString().getBytes(StandardCharsets.UTF_8));

        SessionFile.Head head = SessionFile.check(f);
        assertEquals("bb", head.tool);
        assertEquals("T", head.recording.getJSONObject("tab").getString("title"));
        assertEquals(2000, head.summary.getInt("requests"));
        List<Long> ts = new ArrayList<>();
        SessionFile.each(f, "network", e -> ts.add(e.getLong("t")));
        assertEquals(2000, ts.size());
        assertEquals(Long.valueOf(1999), ts.get(1999));
        assertEquals(2000, AppExport.urls(f, null).size());

        Files.write(f, "{\"console\":[],\"network\":{}}".getBytes(StandardCharsets.UTF_8));
        expectInvalid(f);
        Files.write(f, "{\"console\":[]}".getBytes(StandardCharsets.UTF_8));
        expectInvalid(f);
        Files.write(f, "{\"console\":[],\"network\":[{\"a\":1}]".getBytes(StandardCharsets.UTF_8)); // cut short
        expectInvalid(f);
        Files.write(f, ("{\"console\":[],\"network\":[{\"body\":\"" + new String(new char[(int) SessionFile.MAX_ENTRY_CHARS + 10]).replace('\0', 'x')
                + "\"}]}").getBytes(StandardCharsets.UTF_8));
        expectInvalid(f);
        Files.delete(f);
    }

    @Test
    public void systemLogSummaryCountsAsAStream() throws Exception {
        Path f = Files.createTempFile("system-log", ".json");
        Files.write(f, ("{\"startedAt\":1,\"truncated\":true,\"entries\":[{\"kind\":\"request\"},{\"level\":\"ERROR\"},"
                + "{\"level\":\"INFO\"},{\"level\":\"ERROR\",\"other\":true}]}").getBytes(StandardCharsets.UTF_8));
        JSONObject s = SessionFile.systemLogSummary(f);
        assertEquals(2, s.getInt("entries"));
        assertEquals(1, s.getInt("requests"));
        assertEquals(1, s.getInt("otherEntries"));
        assertEquals(1, s.getInt("errors"));
        assertTrue(s.getBoolean("truncated"));
        Files.write(f, "{\"startedAt\":1}".getBytes(StandardCharsets.UTF_8));
        try {
            SessionFile.systemLogSummary(f);
            fail("no entries");
        } catch (RecordingStore.InvalidUpload e) {
            // expected
        }
        Files.delete(f);
    }

    private static void expectInvalid(Path f) throws Exception {
        try {
            SessionFile.check(f);
            fail("accepted " + new String(Files.readAllBytes(f), StandardCharsets.UTF_8).substring(0, 30));
        } catch (RecordingStore.InvalidUpload e) {
            // expected
        }
    }
}
