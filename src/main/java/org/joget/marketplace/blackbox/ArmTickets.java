package org.joget.marketplace.blackbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

final class ArmTickets {

    static final String DIR = "tickets";
    static final long DEFAULT_TTL_MIN = 15;
    static final long MIN_TTL_MIN = 1;
    static final long MAX_TTL_MIN = 60;
    static final long TTL_MS = DEFAULT_TTL_MIN * 60_000L;
    static final long MAX_TTL_MS = MAX_TTL_MIN * 60_000L;
    static final int MAX_PER_USER = 3;
    static final String TICKET_SCHEME = "BlackBox-Ticket";
    static final String SECRET_HEADER = "X-BlackBox-Capture-Secret";
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern FILE = Pattern.compile("^[0-9a-f]{64}\\.json$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path root;

    ArmTickets(Path root) {
        this.root = root;
    }

    static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static boolean validToken(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    static String hash(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte x : d) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean matches(String token, String storedHash) {
        if (!validToken(token) || storedHash == null || storedHash.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(hash(token).getBytes(StandardCharsets.US_ASCII), storedHash.getBytes(StandardCharsets.US_ASCII));
    }

    static String ticketFrom(String authorization) {
        if (authorization == null) {
            return null;
        }
        String a = authorization.trim();
        if (a.length() <= TICKET_SCHEME.length() + 1 || !a.regionMatches(true, 0, TICKET_SCHEME + " ", 0, TICKET_SCHEME.length() + 1)) {
            return null;
        }
        String t = a.substring(TICKET_SCHEME.length() + 1).trim();
        return validToken(t) ? t : null;
    }

    static long ttlMs(String setting) {
        long min = DEFAULT_TTL_MIN;
        try {
            min = Long.parseLong(setting == null ? "" : setting.trim());
        } catch (NumberFormatException e) {
        }
        return Math.max(MIN_TTL_MIN, Math.min(MAX_TTL_MIN, min)) * 60_000L;
    }

    String arm(String owner, JSONObject options, long now) throws IOException {
        return arm(owner, options, now, TTL_MS);
    }

    String arm(String owner, JSONObject options, long now, long ttlMs) throws IOException {
        Files.createDirectories(root);
        String token = newToken();
        JSONObject t;
        try {
            t = new JSONObject().put("owner", owner).put("createdAt", now).put("expiresAt", now + ttlMs).put("options", options);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        Path tmp = root.resolve(hash(token) + ".json." + Long.toHexString(System.nanoTime()) + ".part");
        try {
            Files.write(tmp, t.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, root.resolve(hash(token) + ".json"));
        } finally {
            Files.deleteIfExists(tmp);
        }
        return token;
    }

    JSONObject use(String token, long now) throws IOException {
        if (!validToken(token)) {
            return null;
        }
        Path file = root.resolve(hash(token) + ".json");
        JSONObject t;
        try {
            t = new JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (NoSuchFileException e) {
            return null;
        } catch (JSONException e) {
            Files.deleteIfExists(file);
            return null;
        }
        try {
            Files.delete(file);
        } catch (NoSuchFileException e) {
            return null;
        }
        return t.optLong("expiresAt") >= now ? t : null;
    }

    int count(String owner, long now) throws IOException {
        int n = 0;
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root)) {
            for (Path f : files) {
                if (!FILE.matcher(f.getFileName().toString()).matches()) {
                    continue;
                }
                JSONObject t = read(f);
                if (t != null && t.optLong("expiresAt") >= now && owner.equals(t.optString("owner"))) {
                    n++;
                }
            }
        }
        return n;
    }

    int sweep(long now) throws IOException {
        int n = 0;
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root)) {
            for (Path f : files) {
                String name = f.getFileName().toString();
                JSONObject t = FILE.matcher(name).matches() ? read(f) : null;
                boolean stalePart = name.endsWith(".part") && Files.getLastModifiedTime(f).toMillis() < now - MAX_TTL_MS;
                if ((t == null && FILE.matcher(name).matches()) || (t != null && t.optLong("expiresAt") < now) || stalePart) {
                    if (Files.deleteIfExists(f)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    private static JSONObject read(Path f) {
        try {
            return new JSONObject(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return null;
        }
    }
}
