package org.joget.marketplace.blackbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Secrets {

    static final String MASK = "****";

    static final String DEFAULT_NAMES = String.join("\n", "password", "passwd", "passphrase", "pwd", "secret", "token",
            "api*key", "access*key", "private*key", "client*secret", "auth*key", "credential", "signing*key",
            "encryption*key", "salt", "cert", "pass*", "*pass", "_pass_", "key*", "*key",
            "!wflow.systemkey");

    static final String OLD_DEFAULT_NAMES = DEFAULT_NAMES.substring(0, DEFAULT_NAMES.lastIndexOf('\n'));

    private static final Pattern[] TOKEN_SHAPES = {
        Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
        Pattern.compile("%%%%[^%\\s]{4,}%%%%"),
        Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
        Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
        Pattern.compile("\\b(?:sk|pk|rk)-(?:[a-z]+-)?[A-Za-z0-9_\\-]{16,}"),
        Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"),
        Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}"),
        Pattern.compile("\\bAIza[0-9A-Za-z_\\-]{30,}"),
    };
    private static final Pattern AUTH_SCHEME = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+([A-Za-z0-9\\-._~+/]{8,}=*)");
    private static final Pattern URL_USERINFO = Pattern.compile(
            "(?<![a-zA-Z0-9+.-])([a-zA-Z][a-zA-Z0-9+.-]*://[^\\s:/@#\"']+:)([^\\s@/#\"']{3,})(@)");
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?<![A-Za-z0-9_.\\-])([A-Za-z0-9_.\\-]++)(\\s*[=:]\\s*)(\"[^\"\\n]*\"|'[^'\\n]*'|[^\\s\"'&;,#<>(){}\\[\\]]++)");

    static final int MAX_NAMES = 100;

    private final List<Glob> names = new ArrayList<>();
    private final List<Glob> notSecret = new ArrayList<>();
    private final boolean on;

    static Secrets passwords() {
        return new Secrets("password\npasswd\npassphrase\npwd\npass*\n*pass\n_pass_", true);
    }

    static Secrets none() {
        return new Secrets(null, false);
    }

    Secrets(String secretNames) {
        this(secretNames, true);
    }

    private Secrets(String secretNames, boolean on) {
        this.on = on;
        String list = secretNames == null || secretNames.trim().isEmpty() || words(secretNames).equals(words(OLD_DEFAULT_NAMES))
                ? DEFAULT_NAMES : secretNames;
        for (String w : list.split("[,\\r\\n]")) {
            String word = w.trim().toLowerCase(Locale.ROOT);
            boolean exception = word.startsWith("!");
            if (exception) {
                word = word.substring(1).trim();
            }
            if (word.isEmpty() || word.replace("*", "").isEmpty() || word.length() > 100
                    || names.size() + notSecret.size() >= MAX_NAMES) {
                continue;
            }
            (exception ? notSecret : names).add(new Glob(word, exception));
        }
    }

    private static List<String> words(String list) {
        List<String> out = new ArrayList<>();
        for (String w : list.split("[,\\r\\n]")) {
            String word = w.trim().toLowerCase(Locale.ROOT);
            if (!word.isEmpty()) {
                out.add(word);
            }
        }
        return out;
    }

    boolean masks() {
        return on;
    }

    boolean isSecretName(String name) {
        if (!on || name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (Glob g : notSecret) {
            if (g.matches(lower)) {
                return false;
            }
        }
        for (Glob g : names) {
            if (g.matches(lower)) {
                return true;
            }
        }
        return false;
    }

    static final class Glob {
        private final String[] parts;
        private final boolean startsWith;
        private final boolean endsWith;
        private final String exact;

        Glob(String word) {
            this(word, false);
        }

        Glob(String word, boolean whole) {
            boolean starAtStart = word.startsWith("*");
            boolean starAtEnd = word.endsWith("*");
            startsWith = whole ? !starAtStart : starAtEnd && !starAtStart;
            endsWith = whole ? !starAtEnd : starAtStart && !starAtEnd;
            exact = whole && !word.contains("*") ? word : null;
            List<String> p = new ArrayList<>();
            for (String part : word.split("\\*")) {
                if (!part.isEmpty()) {
                    p.add(part);
                }
            }
            parts = p.toArray(new String[0]);
        }

        boolean matches(String s) {
            if (exact != null) {
                return exact.equals(s);
            }
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

    String mask(String name, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return isSecretName(name) ? MASK : maskValue(value);
    }

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

    String maskValue(String s) {
        if (!on || s == null || s.isEmpty()) {
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
