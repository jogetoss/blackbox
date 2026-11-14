package org.joget.marketplace.blackbox;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.Deque;
import org.json.JSONArray;
import org.json.JSONObject;

final class JsonStream implements Closeable {

    static class Malformed extends IOException {
        Malformed(String message) {
            super(message);
        }
    }

    static class TooLarge extends IOException {
        TooLarge(String message) {
            super(message);
        }
    }

    static final int MAX_DEPTH = 256;
    static final int MAX_NAME = 4096;

    private final Reader in;
    private final char[] buf = new char[64 * 1024];
    private int pos;
    private int len;
    private int ahead = -2;
    private long read;
    private long limit = Long.MAX_VALUE;
    private final Deque<boolean[]> open = new ArrayDeque<>();

    JsonStream(Reader in) {
        this.in = in;
    }

    void beginObject() throws IOException {
        expect('{');
        open.push(new boolean[]{true});
    }

    void endObject() throws IOException {
        expect('}');
        open.pop();
    }

    void beginArray() throws IOException {
        expect('[');
        open.push(new boolean[]{true});
    }

    void endArray() throws IOException {
        expect(']');
        open.pop();
    }

    boolean hasNext() throws IOException {
        int c = peekNonSpace();
        if (c == '}' || c == ']') {
            return false;
        }
        boolean[] first = open.peek();
        if (first == null) {
            throw new Malformed("hasNext outside a container");
        }
        if (first[0]) {
            first[0] = false;
        } else {
            expect(',');
        }
        return true;
    }

    String nextName() throws IOException {
        if (peekNonSpace() != '"') {
            throw new Malformed("Expected a name at character " + read);
        }
        long saved = limit;
        limit = Math.min(limit, read + MAX_NAME + 1);
        String name;
        try {
            name = string(true);
        } catch (TooLarge e) {
            throw new Malformed("A name is longer than " + MAX_NAME + " characters");
        } finally {
            limit = saved;
        }
        expect(':');
        return name;
    }

    int peekKind() throws IOException {
        return peekNonSpace();
    }

    void end() throws IOException {
        if (peekNonSpace() != -1) {
            throw new Malformed("Unexpected content after the JSON value at character " + read);
        }
    }

    void skipValue(long maxChars) throws IOException {
        long saved = limit;
        limit = Math.min(limit, read + maxChars + 1);
        try {
            value(false, 0);
        } finally {
            limit = saved;
        }
    }

    void skipValue() throws IOException {
        value(false, 0);
    }

    Object nextValue(long maxChars) throws IOException {
        long saved = limit;
        limit = Math.min(limit, read + maxChars + 1);
        try {
            return value(true, 0);
        } finally {
            limit = saved;
        }
    }

    private Object value(boolean build, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new Malformed("Nested deeper than " + MAX_DEPTH);
        }
        int c = peekNonSpace();
        switch (c) {
            case '{': {
                next();
                JSONObject o = build ? new JSONObject() : null;
                boolean first = true;
                while (true) {
                    int d = peekNonSpace();
                    if (d == '}') {
                        next();
                        return o;
                    }
                    if (!first) {
                        expect(',');
                        peekNonSpace();
                    }
                    first = false;
                    if (peekNonSpace() != '"') {
                        throw new Malformed("Expected a name at character " + read);
                    }
                    String name = string(build);
                    expect(':');
                    Object v = value(build, depth + 1);
                    if (build) {
                        try {
                            o.put(name, v);
                        } catch (org.json.JSONException e) {
                            throw new Malformed(e.getMessage());
                        }
                    }
                }
            }
            case '[': {
                next();
                JSONArray a = build ? new JSONArray() : null;
                boolean first = true;
                while (true) {
                    int d = peekNonSpace();
                    if (d == ']') {
                        next();
                        return a;
                    }
                    if (!first) {
                        expect(',');
                    }
                    first = false;
                    Object v = value(build, depth + 1);
                    if (build) {
                        a.put(v);
                    }
                }
            }
            case '"':
                return string(build);
            case 't':
                word("true");
                return Boolean.TRUE;
            case 'f':
                word("false");
                return Boolean.FALSE;
            case 'n':
                word("null");
                return JSONObject.NULL;
            case -1:
                throw new Malformed("Unexpected end of the document");
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number(build);
                }
                throw new Malformed("Unexpected character '" + (char) c + "' at character " + read);
        }
    }

    private String string(boolean build) throws IOException {
        expect('"');
        StringBuilder sb = build ? new StringBuilder() : null;
        while (true) {
            int c = next();
            if (c == -1) {
                throw new Malformed("Unterminated string");
            }
            if (c == '"') {
                return build ? sb.toString() : null;
            }
            if (c < 0x20) {
                throw new Malformed("Control character in a string at character " + read);
            }
            if (c == '\\') {
                int e = next();
                char out;
                switch (e) {
                    case '"': out = '"'; break;
                    case '\\': out = '\\'; break;
                    case '/': out = '/'; break;
                    case 'b': out = '\b'; break;
                    case 'f': out = '\f'; break;
                    case 'n': out = '\n'; break;
                    case 'r': out = '\r'; break;
                    case 't': out = '\t'; break;
                    case 'u': {
                        int v = 0;
                        for (int i = 0; i < 4; i++) {
                            int h = Character.digit(next(), 16);
                            if (h < 0) {
                                throw new Malformed("Bad \\u escape at character " + read);
                            }
                            v = v * 16 + h;
                        }
                        out = (char) v;
                        break;
                    }
                    default:
                        throw new Malformed("Bad escape at character " + read);
                }
                if (build) {
                    sb.append(out);
                }
            } else if (build) {
                sb.append((char) c);
            }
        }
    }

    private Object number(boolean build) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = peek()) != -1 && (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9'))) {
            sb.append((char) next());
            if (sb.length() > 64) {
                throw new Malformed("Number too long at character " + read);
            }
        }
        String s = sb.toString();
        if (!s.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
            throw new Malformed("Bad number '" + s + "' at character " + read);
        }
        if (!build) {
            return null;
        }
        if (s.matches("-?[0-9]{1,18}")) {
            return Long.valueOf(s);
        }
        return new java.math.BigDecimal(s);
    }

    private void word(String w) throws IOException {
        for (int i = 0; i < w.length(); i++) {
            if (next() != w.charAt(i)) {
                throw new Malformed("Expected " + w + " at character " + read);
            }
        }
    }

    private void expect(char want) throws IOException {
        int c = peekNonSpace();
        if (c != want) {
            throw new Malformed("Expected '" + want + "' at character " + read + (c == -1 ? " (end of the document)" : ""));
        }
        next();
    }

    private int peekNonSpace() throws IOException {
        int c;
        while ((c = peek()) == ' ' || c == '\n' || c == '\r' || c == '\t') {
            next();
        }
        return c;
    }

    private int peek() throws IOException {
        if (ahead == -2) {
            if (pos == len) {
                len = in.read(buf, 0, buf.length);
                pos = 0;
            }
            ahead = len <= 0 ? -1 : buf[pos++];
            if (len <= 0) {
                len = 0;
            }
            if (ahead != -1 && ++read > limit) {
                throw new TooLarge("A value is larger than allowed");
            }
        }
        return ahead;
    }

    private int next() throws IOException {
        int c = peek();
        ahead = -2;
        return c;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
