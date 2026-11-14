package org.joget.marketplace.blackbox;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.joget.commons.util.LogUtil;
import org.json.JSONObject;

final class LivenessKnobs {

    static final String FILE = "liveness-test.json";
    static final long PING_MS = 30_000L;
    static final long LOST_MS = 3 * 60_000L;
    static final long CHECK_MS = 30_000L;
    static final long MIN_VALUE_MS = 1000L;
    private static final long WARN_EVERY_MS = 60_000L;

    static Supplier<Path> fileSupplier = () -> ProfileDir.current().resolve(FILE);

    private static volatile long lastWarn;

    private LivenessKnobs() {
    }

    static long pingMs() {
        return get("pingMs", PING_MS);
    }

    static long lostMs() {
        return get("lostMs", LOST_MS);
    }

    static long lostMs(Path file) {
        return get(file, "lostMs", LOST_MS);
    }

    static long checkMs() {
        return get("checkMs", CHECK_MS);
    }

    static long checkMs(Path file) {
        return get(file, "checkMs", CHECK_MS);
    }

    static long minLimitMs() {
        return get("minLimitMs", Long.MAX_VALUE);
    }

    static boolean inUse() {
        JSONObject o = read(defaultFile());
        if (o == null) {
            return false;
        }
        for (String k : new String[] {"pingMs", "lostMs", "checkMs", "minLimitMs"}) {
            if (valid(o, k)) {
                return true;
            }
        }
        return false;
    }

    private static long get(String key, long def) {
        return get(defaultFile(), key, def);
    }

    private static long get(Path file, String key, long def) {
        JSONObject o = read(file);
        if (o == null || !valid(o, key)) {
            return def;
        }
        warn();
        return o.getLong(key);
    }

    private static boolean valid(JSONObject o, String key) {
        Object v = o.opt(key);
        return v instanceof Number && ((Number) v).longValue() >= MIN_VALUE_MS;
    }

    private static Path defaultFile() {
        try {
            return fileSupplier.get();
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject read(Path f) {
        try {
            if (f == null || !Files.isRegularFile(f)) {
                return null;
            }
            return new JSONObject(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static void warn() {
        long now = System.currentTimeMillis();
        if (now - lastWarn < WARN_EVERY_MS) {
            return;
        }
        lastWarn = now;
        try {
            LogUtil.warn(LivenessKnobs.class.getName(), "Test timing override in use (" + FILE + " in the blackbox folder); delete it for normal timing");
        } catch (Throwable t) {
        }
    }
}
