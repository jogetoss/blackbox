package org.joget.marketplace.blackbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks secrets in the System Info snapshot: JVM arguments, system properties and the datasource file.
 *
 * <p>The secret names are the admin's {@code secretNames} setting; empty means {@link #DEFAULT_NAMES}, the names of
 * joget-upgrade-advisor's {@code SecretMasker}. The value shapes come from SecretMasker too and are always applied.
 */
final class Secrets {

    static final String MASK = "****";

    /**
     * SecretMasker.SECRET_NAME as setting words: its key words, salt and cert anywhere in the name, and pass/key at
     * either end. Also the default value of the setting in blackboxPlugin.json; keep the two the same.
     */
    static final String DEFAULT_NAMES = String.join("\n", "password", "passwd", "passphrase", "pwd", "secret", "token",
            "api*key", "access*key", "private*key", "client*secret", "auth*key", "credential", "signing*key",
            "encryption*key", "salt", "cert", "pass*", "*pass", "_pass_", "key*", "*key");

    /** Values that are secrets whatever their name (SecretMasker.TOKEN_SHAPES). */
    private static final Pattern[] TOKEN_SHAPES = {
        Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
        Pattern.compile("%%%%[^%\\s]{4,}%%%%"), // a Joget-encrypted plugin password
        Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"), // JWT
        Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"), // AWS access key id
        Pattern.compile("\\b(?:sk|pk|rk)-(?:[a-z]+-)?[A-Za-z0-9_\\-]{16,}"), // OpenAI / Stripe style
        Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"), // GitHub
        Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}"), // Slack
        Pattern.compile("\\bAIza[0-9A-Za-z_\\-]{30,}"), // Google API key
    };
    /** {@code Bearer <token>} / {@code Basic <token>}: only the token (group 2) is masked. */
    private static final Pattern AUTH_SCHEME = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+([A-Za-z0-9\\-._~+/]{8,}=*)");
    /** {@code scheme://user:password@host}: only the password (group 2) is masked. */
    private static final Pattern URL_USERINFO = Pattern.compile(
            "(?<![a-zA-Z0-9+.-])([a-zA-Z][a-zA-Z0-9+.-]*://[^\\s:/@#\"']+:)([^\\s@/#\"']{3,})(@)");
    /**
     * name=value or name: value inside a longer value (JDBC URL options, -Dx="a=b", a command line). The name is taken
     * in one possessive run (linear, see SecretMasker.KEY_VALUE); the value is masked when the name is secret.
     */
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?<![A-Za-z0-9_.\\-])([A-Za-z0-9_.\\-]++)(\\s*[=:]\\s*)(\"[^\"\\n]*\"|'[^'\\n]*'|[^\\s\"'&;,#<>(){}\\[\\]]++)");

    static final int MAX_NAMES = 100;

    private final List<Glob> names = new ArrayList<>();

    /**
     * secretNames: one word per line (commas also separate); case doesn't matter. A word matches anywhere in the name;
     * {@code *} matches anything, so {@code pass*} matches names that start with pass and {@code *key} names that end
     * with key. Null or empty: {@link #DEFAULT_NAMES}.
     */
    Secrets(String secretNames) {
        String list = secretNames == null || secretNames.trim().isEmpty() ? DEFAULT_NAMES : secretNames;
        for (String w : list.split("[,\\r\\n]")) {
            String word = w.trim().toLowerCase(Locale.ROOT);
            if (word.isEmpty() || word.replace("*", "").isEmpty() || word.length() > 100 || names.size() >= MAX_NAMES) {
                continue; // "*" alone would mask everything
            }
            names.add(new Glob(word));
        }
    }

    boolean isSecretName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (Glob g : names) {
            if (g.matches(lower)) {
                return true;
            }
        }
        return false;
    }

    /**
     * One setting word, matched without regex so that no word can make matching slow (a regex of repeated {@code .*}
     * backtracks exponentially): the parts between stars are found in order with indexOf, the leftmost place each time,
     * which finds a match whenever one exists. Linear in the name's length for each part.
     */
    static final class Glob {
        private final String[] parts; // the non-empty parts between stars, in order
        private final boolean startsWith; // pass*: the first part at the start
        private final boolean endsWith; // *key: the last part at the end

        Glob(String word) {
            boolean starAtStart = word.startsWith("*");
            boolean starAtEnd = word.endsWith("*");
            startsWith = starAtEnd && !starAtStart;
            endsWith = starAtStart && !starAtEnd;
            List<String> p = new ArrayList<>();
            for (String part : word.split("\\*")) {
                if (!part.isEmpty()) {
                    p.add(part);
                }
            }
            parts = p.toArray(new String[0]);
        }

        boolean matches(String s) {
            int from = 0;
            int first = 0;
            int last = parts.length;
            int end = s.length();
            if (startsWith) {
                if (!s.startsWith(parts[0])) {
                    return false;
                }
                from = parts[0].length();
                first = 1;
            }
            if (endsWith) {
                String tail = parts[parts.length - 1];
                if (!s.endsWith(tail) || s.length() - tail.length() < from) {
                    return false;
                }
                end = s.length() - tail.length();
                last = parts.length - 1;
            }
            for (int i = first; i < last; i++) {
                int at = s.indexOf(parts[i], from);
                if (at < 0 || at + parts[i].length() > end) {
                    return false;
                }
                from = at + parts[i].length();
            }
            return true;
        }
    }

    /** A named value (system property, datasource key): all of it when the name is secret, else the secrets inside. */
    String mask(String name, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return isSecretName(name) ? MASK : maskValue(value);
    }

    /** One JVM argument: {@code -Dname=value} by its name, anything else by the secrets inside it. */
    String maskArgument(String arg) {
        if (arg == null) {
            return null;
        }
        int eq = arg.indexOf('=');
        if (arg.startsWith("-D") && eq > 2) {
            return arg.substring(0, eq + 1) + mask(arg.substring(2, eq), arg.substring(eq + 1));
        }
        return maskValue(arg);
    }

    /** Secrets inside free text: token shapes, Bearer/Basic, user:password@ in URLs, secret-named name=value pairs. */
    String maskValue(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        for (Pattern p : TOKEN_SHAPES) {
            s = p.matcher(s).replaceAll(MASK);
        }
        s = replaceGroup(s, AUTH_SCHEME, 2);
        s = replaceGroup(s, URL_USERINFO, 2);
        Matcher m = KEY_VALUE.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String v = m.group(3);
            boolean quoted = v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'');
            String masked = !isSecretName(m.group(1)) || v.equals(MASK) ? v : quoted ? v.charAt(0) + MASK + v.charAt(0) : MASK;
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + m.group(2) + masked));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceGroup(String s, Pattern p, int group) {
        Matcher m = p.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String whole = m.group();
            int from = m.start(group) - m.start();
            int to = m.end(group) - m.start();
            m.appendReplacement(sb, Matcher.quoteReplacement(whole.substring(0, from) + MASK + whole.substring(to)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
