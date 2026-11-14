package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

public class SecretsTest {

    private final Secrets builtIn = new Secrets(null);

    @Test
    public void builtInNamesAreSecret() {
        for (String n : new String[]{"javax.net.ssl.keyStorePassword", "db.passwd", "smtp.pwd", "aws.secretAccessKey",
                "vault.token", "sendgrid.apiKey", "openai.api_key", "my.access-key", "jwt.private_key", "oauth.client_secret",
                "google.application.credentials", "app.signingKey", "app.encryption_key", "user_pass_word", "pass.file",
                "smtp.pass", "platform.systemKey", "key.alias", "hash.salt", "client.cert"}) {
            assertTrue(n, builtIn.isSecretName(n));
        }
    }

    @Test
    public void ordinaryNamesAreNot() {
        for (String n : new String[]{"java.version", "javax.net.ssl.keyStore", "javax.net.ssl.keyStoreType", "user.home",
                "wflow.home", "glowroot.agent.id", "maxActive", "file.encoding", "sun.java.command"}) {
            assertFalse(n, builtIn.isSecretName(n));
        }
    }

    @Test
    public void theSettingReplacesTheDefaultNames() {
        Secrets s = new Secrets(" Customer.ID , jdbc*user, ,*");
        assertTrue(s.isSecretName("app.customer.id"));
        assertTrue(s.isSecretName("JDBC.readonly.USER"));
        assertFalse("only the listed names", s.isSecretName("db.password"));
        assertFalse("a lone * is ignored, not \"mask everything\"", s.isSecretName("java.version"));
        assertFalse("regex characters are literal", new Secrets("a.b").isSecretName("axb"));
        assertTrue("empty = the default names", new Secrets("  ").isSecretName("db.password"));
        Secrets lines = new Secrets("alpha\r\nbeta\n\ngamma, delta");
        for (String n : new String[]{"x.alpha", "beta.y", "gamma", "delta"}) {
            assertTrue("one per line, commas too: " + n, lines.isSecretName(n));
        }
    }

    @Test
    public void starAtOneEndAnchorsTheOtherEnd() {
        Secrets s = new Secrets("pass*, *key, *mid*");
        assertTrue(s.isSecretName("pass.file"));
        assertFalse("pass* = starts with", s.isSecretName("db.pass.file"));
        assertTrue(s.isSecretName("platform.systemKey"));
        assertFalse("*key = ends with", s.isSecretName("javax.net.ssl.keyStore"));
        assertTrue("*mid* = anywhere", s.isSecretName("a.mid.b"));
        Secrets order = new Secrets("jdbc*user, db*pass*, *a*b");
        assertTrue(order.isSecretName("jdbc.ro.user"));
        assertFalse("parts in order", order.isSecretName("user.jdbc"));
        assertTrue(order.isSecretName("db.x.pass.y"));
        assertFalse("db*pass* = starts with db", order.isSecretName("x.db.pass"));
        assertTrue(order.isSecretName("xab"));
        assertFalse("*a*b: b at the end, a before it", order.isSecretName("ba"));
        assertFalse("the end part can't overlap the start part", new Secrets("ab*").isSecretName("a"));
    }

    @Test
    public void manyStarsStayFast() {
        StringBuilder w = new StringBuilder();
        for (int i = 0; i < 98; i++) {
            w.append('*');
        }
        Secrets s = new Secrets(w + "zq, a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*b");
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 100_000; i++) {
            name.append('a');
        }
        long t = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            assertFalse(s.isSecretName("java.vm.specification.vendor"));
            assertFalse(s.isSecretName(name.toString()));
        }
        assertTrue("took " + (System.nanoTime() - t) / 1_000_000 + " ms", System.nanoTime() - t < 2_000_000_000L);
    }

    @Test
    public void defaultNamesMatchTheSettingDefault() throws Exception {
        String json = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/resources/properties/blackboxPlugin.json")), "UTF-8");
        org.json.JSONArray props = new org.json.JSONArray(json).getJSONObject(0).getJSONArray("properties");
        for (int i = 0; i < props.length(); i++) {
            if ("secretNames".equals(props.getJSONObject(i).optString("name"))) {
                assertEquals(Secrets.DEFAULT_NAMES, props.getJSONObject(i).getString("value"));
                return;
            }
        }
        fail("no secretNames setting");
    }

    @Test
    public void jvmArgumentsAreMaskedByName() {
        assertEquals("-Djavax.net.ssl.keyStorePassword=****", builtIn.maskArgument("-Djavax.net.ssl.keyStorePassword=changeit"));
        assertEquals("-Dapp.apiKey=****", builtIn.maskArgument("-Dapp.apiKey=abc123"));
        assertEquals("-Djavax.net.ssl.keyStore=/opt/ks.jks", builtIn.maskArgument("-Djavax.net.ssl.keyStore=/opt/ks.jks"));
        assertEquals("-Xmx4g", builtIn.maskArgument("-Xmx4g"));
        assertEquals("-Dcustomer.id=****", new Secrets("customer.id").maskArgument("-Dcustomer.id=42"));
    }

    @Test
    public void secretsInsideValuesAreMasked() {
        assertEquals("jdbc:oracle:thin:scott/tiger@db", "jdbc:oracle:thin:scott/tiger@db",
                builtIn.maskValue("jdbc:oracle:thin:scott/tiger@db"));
        assertEquals("https://user:****@host/x", builtIn.maskValue("https://user:hunter22@host/x"));
        assertEquals("Authorization: Bearer ****", builtIn.maskValue("Authorization: Bearer abcdefgh12345678"));
        assertEquals("user=root;pwd=****;timeout=5", builtIn.maskValue("user=root;pwd=s3cret;timeout=5"));
        assertEquals("--token=\"****\" --name=\"x\"", builtIn.maskValue("--token=\"abc def\" --name=\"x\""));
        assertEquals("key ****", builtIn.maskValue("key AKIAABCDEFGHIJKLMNOP"));
        assertEquals("a ****", builtIn.maskValue("a eyJhbGciOiJIUzI1.eyJzdWIiOiIxMjM0.SflKxwRJSMeKKF2Q"));
        assertEquals("password=****", builtIn.maskValue("password=****"));
    }

    @Test
    public void systemPropertiesAreMaskedByNameAndValue() throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("java.version", "11");
        m.put("smtp.password", "x");
        m.put("proxy.url", "http://bob:secret99@proxy:3128");
        m.put("customer.id", "42");
        JSONObject o = ServerInfo.masked(m, new Secrets(Secrets.DEFAULT_NAMES + ", customer.id"));
        assertEquals("11", o.getString("java.version"));
        assertEquals("****", o.getString("smtp.password"));
        assertEquals("http://bob:****@proxy:3128", o.getString("proxy.url"));
        assertEquals("****", o.getString("customer.id"));
    }

    @Test
    public void longInputsStayFast() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200_000; i++) {
            sb.append(i % 2 == 0 ? "-" : "password");
        }
        long t = System.nanoTime();
        builtIn.maskValue(sb.toString());
        assertTrue("took " + (System.nanoTime() - t) / 1_000_000 + " ms", System.nanoTime() - t < 2_000_000_000L);
    }

    @Test
    public void noneMasksNothing() {
        Secrets none = Secrets.none();
        assertFalse(none.masks());
        assertTrue(builtIn.masks());
        assertFalse(none.isSecretName("platform.systemKey"));
        assertEquals("abc123", none.mask("platform.systemKey", "abc123"));
        assertEquals("-Dsmtp.pass=s3cret", none.maskArgument("-Dsmtp.pass=s3cret"));
        assertEquals("jdbc:mysql://root:s3cret@db/jwdb", none.maskValue("jdbc:mysql://root:s3cret@db/jwdb"));
        assertEquals("****", builtIn.mask("platform.systemKey", "abc123"));
    }

    @Test
    public void exceptionsAreNeverSecret() {
        assertTrue("the license's system key", builtIn.isSecretName("platform.systemKey"));
        assertFalse("the system key generator argument", builtIn.isSecretName("wflow.systemkey"));
        assertEquals("-Dwflow.systemkey=Domain", builtIn.maskArgument("-Dwflow.systemkey=Domain"));
        Secrets s = new Secrets("*key\n! app.*key ");
        assertFalse(s.isSecretName("app.publicKey"));
        assertTrue(s.isSecretName("other.publicKey"));
        assertTrue("a lone ! is ignored", new Secrets("*key\n!\n!*").isSecretName("a.key"));
        assertTrue(builtIn.isSecretName("wflow.systemkey.password"));
        assertTrue(new Secrets("password\n!app").isSecretName("app.password"));
        assertFalse(new Secrets("password\n!app.*").isSecretName("app.password"));
        assertTrue(new Secrets("password\n!app.*").isSecretName("my.app.password"));
    }

    @Test
    public void licenseMasksOnlySystemKeySignatureAndMac() throws Exception {
        Map<String, String> license = new LinkedHashMap<>();
        license.put("platform.systemKey", "EE-DOMAIN-localhost");
        license.put("platform.net.address", "FA  33  D4  59  E7  B2");
        license.put("platform.license.holder", "Free");
        license.put("platform.license.signature", "EE-DOMAIN-example.com");
        license.put("platform.license.expiry", "");
        JSONObject o = ServerInfo.license(license, true);
        assertEquals("****", o.getString("platform.systemKey"));
        assertEquals("****", o.getString("platform.net.address"));
        assertEquals("the signature is the issued system key", "****", o.getString("platform.license.signature"));
        assertEquals("Free", o.getString("platform.license.holder"));
        assertEquals("", o.getString("platform.license.expiry"));
        assertEquals("", ServerInfo.license(java.util.Collections.singletonMap("platform.license.signature", ""), true)
                .getString("platform.license.signature"));
        JSONObject u = ServerInfo.license(license, false);
        assertEquals("EE-DOMAIN-localhost", u.getString("platform.systemKey"));
        assertEquals("FA  33  D4  59  E7  B2", u.getString("platform.net.address"));
        assertEquals("EE-DOMAIN-example.com", u.getString("platform.license.signature"));
    }

    @Test
    public void theOldDefaultListMeansTheNewDefault() {
        assertTrue(Secrets.DEFAULT_NAMES.endsWith("\n!wflow.systemkey"));
        assertFalse(Secrets.OLD_DEFAULT_NAMES.contains("!"));
        assertFalse("saved old default", new Secrets(Secrets.OLD_DEFAULT_NAMES).isSecretName("wflow.systemkey"));
        assertFalse("CRLF and case", new Secrets(Secrets.OLD_DEFAULT_NAMES.replace("\n", "\r\n").toUpperCase()).isSecretName("wflow.systemkey"));
        assertTrue(new Secrets(Secrets.OLD_DEFAULT_NAMES).isSecretName("platform.systemKey"));
        assertTrue("an edited list is kept as it is", new Secrets(Secrets.OLD_DEFAULT_NAMES + "\ncustomer.id").isSecretName("wflow.systemkey"));
    }

    @Test
    public void passwordsOnlyKeepsTheDatabasePasswordOut() {
        Secrets p = Secrets.passwords();
        assertEquals("jdbc:mysql://root:****@db/jwdb", p.maskValue("jdbc:mysql://root:s3cret@db/jwdb"));
        assertEquals("****", p.mask("workflowPwd", "s3cret"));
        assertEquals("user=root;pwd=****", p.maskValue("user=root;pwd=s3cret"));
        assertEquals("not a password", "abc", p.mask("platform.systemKey", "abc"));
    }
}
