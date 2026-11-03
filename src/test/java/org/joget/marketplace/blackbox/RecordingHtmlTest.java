package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.junit.Test;

public class RecordingHtmlTest {

    private static final String VIEW = "<html><head><link rel=\"stylesheet\" href=\"assets/viewer.css\"></head><body>"
            + "<script src=\"assets/common.js\"></script>\n<script src=\"assets/viewer.js\"></script>\n"
            + "<script src=\"assets/view.js\"></script></body></html>";

    private static String block(String html, String id) {
        Matcher m = Pattern.compile("<script type=\"[^\"]+\" id=\"" + id + "\">(.*?)</script>", Pattern.DOTALL).matcher(html);
        return m.find() ? m.group(1) : null;
    }

    @Test
    public void inlinesViewerAndEmbedsTheRecording() throws Exception {
        Path dir = Files.createTempDirectory("bb-export");
        // A body with "</script>" must not end the data block
        String sessionJson = "{\"console\":[{\"t\":1,\"text\":\"</script><b>x</b> ünïcode\"}],\"network\":[]}";
        Path session = Files.write(dir.resolve("session.json"), sessionJson.getBytes(StandardCharsets.UTF_8));
        byte[] video = new byte[100_001]; // not a multiple of 3: padding at the end
        for (int i = 0; i < video.length; i++) {
            video[i] = (byte) i;
        }
        Path webm = Files.write(dir.resolve("recording.webm"), video);

        RecordingHtml export = new RecordingHtml(VIEW, "body{}", "var viewer=1;", "var display=1;", "var boot=1;");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        export.write(out, "{\"title\":\"a<b\"}", session, null, webm);
        String html = new String(out.toByteArray(), StandardCharsets.UTF_8);

        assertTrue(html.contains("<style>\nbody{}</style>"));
        assertTrue(html.contains("<script>\nvar viewer=1;</script>"));
        assertTrue("Theme and Text size in <head>, before the page draws",
                html.indexOf("<script>\nvar display=1;</script>") >= 0 && html.indexOf("var display=1;") < html.indexOf("</head>"));
        assertFalse(html.contains("assets/"));
        assertTrue("start-up code after the data", html.indexOf("var boot=1;") > html.indexOf("id=\"bb-video\""));

        assertEquals("a<b", new JSONObject(block(html, "bb-meta")).getString("title"));
        JSONObject parsed = new JSONObject(block(html, "bb-session"));
        assertEquals("</script><b>x</b> ünïcode", parsed.getJSONArray("console").getJSONObject(0).getString("text"));
        assertNull("no system log given", block(html, "bb-systemlog"));
        assertArrayEquals(video, Base64.getDecoder().decode(block(html, "bb-video")));
    }

    @Test
    public void embedsFullTracePagesGzipped() throws Exception {
        Path dir = Files.createTempDirectory("bb-export");
        Path session = Files.write(dir.resolve("session.json"), "{}".getBytes(StandardCharsets.UTF_8));
        String page = "<!doctype html><title>Trace export</title><script>var glowroot='</script>';</script>";
        String name = "node1-0123456789abcdef0123456789abcdef.html";
        assertTrue("test name must be a valid trace file name", TraceStore.isTraceFile(name));
        java.util.Map<String, Path> files = new java.util.LinkedHashMap<>();
        files.put(name, Files.write(dir.resolve(name), page.getBytes(StandardCharsets.UTF_8)));
        files.put("../evil.html", Files.write(dir.resolve("evil.html"), "x".getBytes(StandardCharsets.UTF_8))); // bad name: skipped
        files.put("node1-ffffffffffffffffffffffffffffffff.html", dir.resolve("missing.html")); // no file: skipped

        RecordingHtml export = new RecordingHtml(VIEW, "body{}", "var viewer=1;", "var display=1;", "var boot=1;");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        export.write(out, "{}", session, null, null, "[]", null, null, null, files);
        String html = new String(out.toByteArray(), StandardCharsets.UTF_8);

        Matcher m = Pattern.compile("<script type=\"application/gzip\" data-file=\"([^\"]+)\" id=\"bb-trace-(\\d+)\">(.*?)</script>",
                Pattern.DOTALL).matcher(html);
        assertTrue(m.find());
        assertEquals(name, m.group(1));
        assertEquals("0", m.group(2));
        byte[] gz = Base64.getDecoder().decode(m.group(3));
        try (java.io.InputStream in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(gz))) {
            ByteArrayOutputStream back = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) != -1; ) {
                back.write(buf, 0, n);
            }
            assertEquals(page, new String(back.toByteArray(), StandardCharsets.UTF_8));
        }
        assertFalse("only the valid, existing page", m.find());
        assertFalse(html.contains("evil"));
        assertTrue("start-up code after the trace pages", html.indexOf("var boot=1;") > html.indexOf("id=\"bb-trace-0\""));
    }

    @Test(expected = java.io.IOException.class)
    public void refusesCodeThatWouldCloseItsTag() throws Exception {
        new RecordingHtml(VIEW, "body{}", "x='</script>'", "", "");
    }
}
