package org.joget.marketplace.blackbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks secrets in the System Info snapshot: JVM arguments, system properties and the datasource file.
 *
 * <p>The built-in names and value shapes come from joget-upgrade-advisor's {@code SecretMasker} and are always applied;
 * the admin can only add names ({@code secretNames} setting), not remove built-in ones.
 */
final class Secrets {

    static final String MASK = "****";

    /** Words that make a name secret, anywhere in it (SecretMasker.KEY_WORDS). */
    static final String KEY_WORDS = "password|passwd|passphrase|pwd|secret|token|api[_-]?key|apikey|access[_-]?key|"
            + "private[_-]?key|client[_-]?secret|auth[_-]?key|credential|signing[_-]?key|encryption[_-]?key";

    /** SecretMasker.SECRET_NAME: the key words, plus pass/key at either end of the name, salt and cert. */
    private static final Pattern BUILT_IN_NAME = Pattern.compile("(?i)(" + KEY_WORDS + "|pass$|^pass|_pass_|key$|^key|salt|cert)");

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

    static final int MAX_EXTRA = 100;

    private final List<Pattern> extra = new ArrayList<>();

    /** extraNames: the admin's comma-separated words; {@code *} matches anything; case doesn't matter. */
    Secrets(String extraNames) {
        if (extraNames == null) {
            return;
        }
        for (String w : extraNames.split(",")) {
            String word = w.trim();
            if (word.isEmpty() || word.replace("*", "").isEmpty() || word.length() > 100 || extra.size() >= MAX_EXTRA) {
                continue; // "*" alone would mask everything
            }
            StringBuilder re = new StringBuilder();
            for (String part : word.split("\\*", -1)) {
                if (re.length() > 0 || word.startsWith("*")) {
                    re.append(".*");
                }
                re.append(Pattern.quote(part.toLowerCase(Locale.ROOT)));
            }
            extra.add(Pattern.compile(re.toString()));
        }
    }

    boolean isSecretName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (BUILT_IN_NAME.matcher(name).find()) {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (Pattern p : extra) {
            if (p.matcher(lower).find()) {
                return true;
            }
        }
        return false;
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
