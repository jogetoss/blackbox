package org.joget.marketplace.blackbox;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class Masker {

    static final String DEFAULT_SENSITIVE = String.join("\n", "password", "ic", "nric", "passport", "email", "phone", "mobile",
            "tel", "address", "salary", "birth", "dob", "bank", "account", "card", "iban", "ssn");

    private static final Pattern TECH_ID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
            + "|\\d{1,12}_[A-Za-z0-9][A-Za-z0-9_-]*_[A-Za-z0-9_-]+");
    private static final Pattern SMALL_NUMBER = Pattern.compile("-?\\d{1,5}([.,]\\d+)?");
    private static final Pattern DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})(.*)", Pattern.DOTALL);
    private static final Set<String> FLAGS = new HashSet<>(Arrays.asList("true", "false", "yes", "no", "y", "n", "on", "off"));
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";

    private final Mac mac;
    private final Set<String> sensitive;
    private final Map<String, String> users = new HashMap<>();
    private Set<String> definitionTokens = new HashSet<>();
    private String definitionText = "";

    Masker(String sensitiveWords) {
        this(sensitiveWords, randomKey());
    }

    Masker(String sensitiveWords, byte[] key) {
        try {
            mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        sensitive = new HashSet<>();
        for (String w : (sensitiveWords != null ? sensitiveWords : DEFAULT_SENSITIVE).split("[,\\r\\n]")) {
            if (!w.trim().isEmpty()) {
                sensitive.add(w.trim().toLowerCase(Locale.ROOT));
            }
        }
    }

    private static byte[] randomKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }

    void setDefinition(String text) {
        definitionText = text != null ? text : "";
        definitionTokens = new HashSet<>();
        Matcher m = Pattern.compile("[\\p{L}\\p{N}_-]{2,60}").matcher(definitionText);
        while (m.find()) {
            definitionTokens.add(m.group());
        }
    }

    private boolean inDefinition(String value) {
        if (definitionTokens.contains(value)) {
            return true;
        }
        if (value.length() < 8 || definitionText.isEmpty()) {
            return false;
        }
        return definitionText.contains(value) || definitionText.contains(xml(value));
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    void knowUser(String username) {
        user(username);
    }

    boolean isSensitive(String column) {
        for (String w : words(column)) {
            if (sensitive.contains(w)) {
                return true;
            }
        }
        return false;
    }

    static List<String> words(String name) {
        List<String> words = new ArrayList<>();
        for (String part : name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").split("[^A-Za-z]+")) {
            if (!part.isEmpty()) {
                words.add(part.toLowerCase(Locale.ROOT));
            }
        }
        return words;
    }

    String value(String column, String value, Collection<String> options, IdMap ids) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        boolean always = isSensitive(column);
        if (!always && ids.contains(value)) {
            return ids.get(value);
        }
        if (!always && users.containsKey(value)) {
            return users.get(value);
        }
        String trimmed = value.trim();
        if ((trimmed.startsWith("{") || trimmed.startsWith("[")) && trimmed.length() > 1) {
            try {
                Object json = trimmed.startsWith("{") ? new JSONObject(trimmed) : new JSONArray(trimmed);
                return json(column, json, options, ids, always).toString();
            } catch (JSONException e) {
            }
        }
        if (!always && (options.contains(value) || FLAGS.contains(trimmed.toLowerCase(Locale.ROOT))
                || SMALL_NUMBER.matcher(trimmed).matches() || TECH_ID.matcher(trimmed).matches() || inDefinition(value))) {
            return value;
        }
        if (!always && containsOption(value, options)) {
            return value;
        }
        return ids.replaceIn(value, this::shapeAroundHashVariables);
    }

    private static final Pattern HASH_VARIABLE = Pattern.compile("#[A-Za-z][A-Za-z0-9_.\\-]*(\\[[^#]*\\])?#");

    String shapeAroundHashVariables(String value) {
        Matcher m = HASH_VARIABLE.matcher(value);
        StringBuilder out = new StringBuilder();
        int from = 0;
        while (m.find()) {
            out.append(m.start() > from ? shape(value.substring(from, m.start())) : "").append(m.group());
            from = m.end();
        }
        return from == 0 ? shape(value) : out.append(from < value.length() ? shape(value.substring(from)) : "").toString();
    }

    private static boolean containsOption(String value, Collection<String> options) {
        if (options.isEmpty() || !value.contains(";")) {
            return false;
        }
        for (String part : value.split(";")) {
            if (!options.contains(part)) {
                return false;
            }
        }
        return true;
    }

    private Object json(String column, Object json, Collection<String> options, IdMap ids, boolean always) throws JSONException {
        if (json instanceof JSONObject) {
            JSONObject o = (JSONObject) json;
            JSONObject out = new JSONObject();
            for (java.util.Iterator<?> it = o.keys(); it.hasNext();) {
                String k = String.valueOf(it.next());
                out.put(k, json(always ? column : k, o.get(k), options, ids, always || isSensitive(k)));
            }
            return out;
        }
        if (json instanceof JSONArray) {
            JSONArray a = (JSONArray) json;
            JSONArray out = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                out.put(json(column, a.get(i), options, ids, always));
            }
            return out;
        }
        if (json instanceof String) {
            return value(always ? "password" : column, (String) json, options, ids);
        }
        return json;
    }

    String shape(String value) {
        Matcher d = DATE.matcher(value);
        if (d.matches()) {
            byte[] h = hash(value);
            int month = 1 + (h[0] & 0xff) % 12;
            int day = 1 + (h[1] & 0xff) % 28;
            return d.group(1) + String.format(Locale.ROOT, "-%02d-%02d", month, day) + d.group(4);
        }
        byte[] h = hash(value);
        StringBuilder out = new StringBuilder(value.length());
        int k = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (k >= h.length) {
                h = hash(value + "#" + i);
                k = 0;
            }
            int r = h[k++] & 0xff;
            if (c >= 'A' && c <= 'Z') {
                out.append(UPPER.charAt(r % 26));
            } else if (c >= 'a' && c <= 'z') {
                out.append(LOWER.charAt(r % 26));
            } else if (c >= '0' && c <= '9') {
                out.append((char) ('0' + r % 10));
            } else if (Character.isLetter(c)) {
                out.append(Character.isUpperCase(c) ? UPPER.charAt(r % 26) : LOWER.charAt(r % 26));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    String user(String username) {
        if (username == null || username.isEmpty()) {
            return username;
        }
        return users.computeIfAbsent(username, u -> "user" + (users.size() + 1));
    }

    String fileName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? shape(name.substring(0, dot)) + name.substring(dot) : shape(name);
    }

    private synchronized byte[] hash(String value) {
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    static final class IdMap {
        private final Map<String, String> map = new java.util.LinkedHashMap<>();
        private final boolean keep;
        private Pattern pattern;

        IdMap(boolean keep) {
            this.keep = keep;
        }

        void add(String oldId) {
            if (oldId != null && !oldId.isEmpty() && !map.containsKey(oldId)) {
                map.put(oldId, keep ? oldId : java.util.UUID.randomUUID().toString());
                pattern = null;
            }
        }

        boolean contains(String id) {
            return map.containsKey(id);
        }

        String get(String id) {
            return map.get(id);
        }

        Map<String, String> all() {
            return java.util.Collections.unmodifiableMap(map);
        }

        String replaceIn(String value, java.util.function.UnaryOperator<String> rest) {
            if (map.isEmpty()) {
                return rest.apply(value);
            }
            if (pattern == null) {
                List<String> ids = new ArrayList<>(map.keySet());
                ids.sort((a, b) -> b.length() - a.length());
                StringBuilder p = new StringBuilder();
                for (String id : ids) {
                    p.append(p.length() > 0 ? "|" : "").append(Pattern.quote(id));
                }
                pattern = Pattern.compile(p.toString());
            }
            Matcher m = pattern.matcher(value);
            StringBuilder out = new StringBuilder();
            int from = 0;
            while (m.find()) {
                if (m.start() > from) {
                    out.append(rest.apply(value.substring(from, m.start())));
                }
                out.append(map.get(m.group()));
                from = m.end();
            }
            if (from < value.length()) {
                out.append(from == 0 ? rest.apply(value) : rest.apply(value.substring(from)));
            }
            return out.toString();
        }
    }
}
