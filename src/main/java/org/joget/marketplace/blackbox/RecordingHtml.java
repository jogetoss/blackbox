package org.joget.marketplace.blackbox;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Writes one recording as a single .html file that replays it in any browser, with no server or extension:
 * the replay page (view.html) with viewer.css, viewer.js and display.js (Theme and Text size) inlined, the recording in data blocks
 * (meta, session.json, system-log.json, apps.json, the full trace pages gzipped as base64, the video as base64) and
 * export.js to open it.
 *
 * Data blocks are &lt;script type="application/json"&gt; elements. JSON text never needs a raw '&lt;' (in strings it can
 * be written <), so every '&lt;' is escaped and no "&lt;/script" can end a block early.
 */
public class RecordingHtml {

    static final String RESOURCES = "static/blackbox/";
    private static final String CSS_LINK = "<link rel=\"stylesheet\" href=\"assets/viewer.css\">";
    private static final String HEAD_END = "</head>";
    private static final String COMMON_JS = "<script src=\"assets/common.js\"></script>";
    private static final String VIEWER_JS = "<script src=\"assets/viewer.js\"></script>";
    private static final String VIEW_JS = "<script src=\"assets/view.js\"></script>";
    private static final byte[] LT = "\\u003c".getBytes(StandardCharsets.US_ASCII);

    private final String head; // the page up to where the data goes
    private final String tail; // export.js and the rest of the page

    /** Builds the page from the plugin's own resources. */
    public static RecordingHtml fromResources() throws IOException {
        ClassLoader cl = RecordingHtml.class.getClassLoader();
        return new RecordingHtml(read(cl, "view.html"), read(cl, "viewer.css"), read(cl, "viewer.js"), read(cl, "display.js"),
                read(cl, "export.js"));
    }

    RecordingHtml(String viewHtml, String css, String viewerJs, String displayJs, String exportJs) throws IOException {
        for (String code : new String[]{css, viewerJs, displayJs, exportJs}) {
            if (code.contains("</style") || code.contains("</script")) {
                throw new IOException("A viewer file contains a closing tag and cannot be inlined");
            }
        }
        String page = replaceOnce(viewHtml, CSS_LINK, "<style>\n" + css + "</style>");
        // in <head>: the saved Theme and Text size apply before the page draws
        page = replaceOnce(page, HEAD_END, "<script>\n" + displayJs + "</script>\n" + HEAD_END);
        page = replaceOnce(page, COMMON_JS, "");
        page = replaceOnce(page, VIEWER_JS, "<script>\n" + viewerJs + "</script>");
        int at = page.indexOf(VIEW_JS);
        if (at < 0) {
            throw new IOException("view.html no longer contains " + VIEW_JS);
        }
        head = page.substring(0, at);
        tail = "<script>\n" + exportJs + "</script>" + page.substring(at + VIEW_JS.length());
    }

    /**
     * meta: the recording's meta.json (title, owner, notes...). systemLog and video may be null.
     */
    public void write(OutputStream out, String meta, Path session, Path systemLog, Path video) throws IOException {
        write(out, meta, session, systemLog, video, null, null, null);
    }

    /** traces: the recording's Glowroot trace summaries (TraceStore.merge), or null. */
    public void write(OutputStream out, String meta, Path session, Path systemLog, Path video, String traces, String gauges,
            Path serverInfo) throws IOException {
        write(out, meta, session, systemLog, video, traces, gauges, serverInfo, null);
    }

    /** apps: the recording's app/apps.json (the App tab), or null. The .jwa files are not included: they are in the ZIP. */
    public void write(OutputStream out, String meta, Path session, Path systemLog, Path video, String traces, String gauges,
            Path serverInfo, Path apps) throws IOException {
        write(out, meta, session, systemLog, video, traces, gauges, serverInfo, apps, null);
    }

    /**
     * traceFiles: the full trace pages (Glowroot's export page of each saved trace) by file name, or null. Each goes in
     * its own block, gzipped (a page is ~450 KB, mostly Glowroot's script, ~105 KB gzipped) as base64; export.js unzips
     * them so Open full trace works in the file. Names must pass TraceStore.isTraceFile (they go in an attribute).
     */
    public void write(OutputStream out, String meta, Path session, Path systemLog, Path video, String traces, String gauges,
            Path serverInfo, Path apps, Map<String, Path> traceFiles) throws IOException {
        out.write(head.getBytes(StandardCharsets.UTF_8));
        block(out, "bb-meta", "application/json");
        escaped(new java.io.ByteArrayInputStream(meta.getBytes(StandardCharsets.UTF_8)), out);
        end(out);
        block(out, "bb-session", "application/json");
        try (InputStream in = Files.newInputStream(session)) {
            escaped(in, out);
        }
        end(out);
        if (systemLog != null) {
            block(out, "bb-systemlog", "application/json");
            try (InputStream in = Files.newInputStream(systemLog)) {
                escaped(in, out);
            }
            end(out);
        }
        if (traces != null) {
            block(out, "bb-traces", "application/json");
            escaped(new java.io.ByteArrayInputStream(traces.getBytes(StandardCharsets.UTF_8)), out);
            end(out);
        }
        if (gauges != null) {
            block(out, "bb-gauges", "application/json");
            escaped(new java.io.ByteArrayInputStream(gauges.getBytes(StandardCharsets.UTF_8)), out);
            end(out);
        }
        if (serverInfo != null) {
            block(out, "bb-serverinfo", "application/json");
            try (InputStream in = Files.newInputStream(serverInfo)) {
                escaped(in, out);
            }
            end(out);
        }
        if (apps != null) {
            block(out, "bb-apps", "application/json");
            try (InputStream in = Files.newInputStream(apps)) {
                escaped(in, out);
            }
            end(out);
        }
        if (traceFiles != null) {
            int n = 0;
            for (Map.Entry<String, Path> e : traceFiles.entrySet()) {
                if (!TraceStore.isTraceFile(e.getKey()) || e.getValue() == null || !Files.isRegularFile(e.getValue())) {
                    continue;
                }
                out.write(("\n<script type=\"application/gzip\" data-file=\"" + e.getKey() + "\" id=\"bb-trace-" + n++ + "\">")
                        .getBytes(StandardCharsets.UTF_8));
                try (InputStream in = Files.newInputStream(e.getValue())) {
                    base64(in, out, true);
                }
                end(out);
            }
        }
        if (video != null) {
            block(out, "bb-video", "text/plain");
            try (InputStream in = Files.newInputStream(video)) {
                base64(in, out, false);
            }
            end(out);
        }
        out.write(tail.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Copies in to out as base64, gzipped first if asked. Base64 has no '<'. Leaves out open. */
    private static void base64(InputStream in, OutputStream out, boolean gzip) throws IOException {
        // The encoder (and gzip) must be closed to write their last bytes, but not the response stream
        OutputStream keepOpen = new FilterOutputStream(out) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                flush();
            }
        };
        try (OutputStream b64 = Base64.getEncoder().wrap(keepOpen);
                OutputStream dst = gzip ? new GZIPOutputStream(b64, 64 * 1024) : b64) {
            copy(in, dst);
        }
    }

    private static void block(OutputStream out, String id, String type) throws IOException {
        out.write(("\n<script type=\"" + type + "\" id=\"" + id + "\">").getBytes(StandardCharsets.UTF_8));
    }

    private static void end(OutputStream out) throws IOException {
        out.write("</script>".getBytes(StandardCharsets.UTF_8));
    }

    /** Copies JSON, writing '<' as <. '<' is one byte in UTF-8 and never part of a multi-byte character. */
    static void escaped(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            int from = 0;
            for (int i = 0; i < n; i++) {
                if (buf[i] == '<') {
                    out.write(buf, from, i - from);
                    out.write(LT);
                    from = i + 1;
                }
            }
            out.write(buf, from, n - from);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
    }

    private static String replaceOnce(String s, String find, String with) throws IOException {
        int at = s.indexOf(find);
        if (at < 0) {
            throw new IOException("view.html no longer contains " + find);
        }
        return s.substring(0, at) + with + s.substring(at + find.length());
    }

    private static String read(ClassLoader cl, String name) throws IOException {
        try (InputStream in = cl.getResourceAsStream(RESOURCES + name)) {
            if (in == null) {
                throw new IOException("Missing resource " + RESOURCES + name);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
