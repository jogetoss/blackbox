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

/**
 * Writes one recording as a single .html file that replays it in any browser, with no server or extension:
 * the replay page (view.html) with viewer.css and viewer.js inlined, the recording in data blocks
 * (meta, session.json, server-log.json, the video as base64) and export.js to open it.
 *
 * Data blocks are &lt;script type="application/json"&gt; elements. JSON text never needs a raw '&lt;' (in strings it can
 * be written <), so every '&lt;' is escaped and no "&lt;/script" can end a block early.
 */
public class RecordingHtml {

    static final String RESOURCES = "static/blackbox/";
    private static final String CSS_LINK = "<link rel=\"stylesheet\" href=\"assets/viewer.css\">";
    private static final String COMMON_JS = "<script src=\"assets/common.js\"></script>";
    private static final String VIEWER_JS = "<script src=\"assets/viewer.js\"></script>";
    private static final String VIEW_JS = "<script src=\"assets/view.js\"></script>";
    private static final byte[] LT = "\\u003c".getBytes(StandardCharsets.US_ASCII);

    private final String head; // the page up to where the data goes
    private final String tail; // export.js and the rest of the page

    /** Builds the page from the plugin's own resources. */
    public static RecordingHtml fromResources() throws IOException {
        ClassLoader cl = RecordingHtml.class.getClassLoader();
        return new RecordingHtml(read(cl, "view.html"), read(cl, "viewer.css"), read(cl, "viewer.js"), read(cl, "export.js"));
    }

    RecordingHtml(String viewHtml, String css, String viewerJs, String exportJs) throws IOException {
        for (String code : new String[]{css, viewerJs, exportJs}) {
            if (code.contains("</style") || code.contains("</script")) {
                throw new IOException("A viewer file contains a closing tag and cannot be inlined");
            }
        }
        String page = replaceOnce(viewHtml, CSS_LINK, "<style>\n" + css + "</style>");
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
     * meta: the recording's meta.json (title, owner, notes...). serverLog and video may be null.
     */
    public void write(OutputStream out, String meta, Path session, Path serverLog, Path video) throws IOException {
        out.write(head.getBytes(StandardCharsets.UTF_8));
        block(out, "bb-meta", "application/json");
        escaped(new java.io.ByteArrayInputStream(meta.getBytes(StandardCharsets.UTF_8)), out);
        end(out);
        block(out, "bb-session", "application/json");
        try (InputStream in = Files.newInputStream(session)) {
            escaped(in, out);
        }
        end(out);
        if (serverLog != null) {
            block(out, "bb-serverlog", "application/json");
            try (InputStream in = Files.newInputStream(serverLog)) {
                escaped(in, out);
            }
            end(out);
        }
        if (video != null) {
            // Base64 has no '<'; the encoder must be closed to write its padding, but not the response stream
            block(out, "bb-video", "text/plain");
            try (OutputStream b64 = Base64.getEncoder().wrap(new FilterOutputStream(out) {
                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        out.write(b, off, len);
                    }

                    @Override
                    public void close() throws IOException {
                        flush();
                    }
                }); InputStream in = Files.newInputStream(video)) {
                copy(in, b64);
            }
            end(out);
        }
        out.write(tail.getBytes(StandardCharsets.UTF_8));
        out.flush();
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
