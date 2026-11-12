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
    public void extraNamesAddToTheBuiltInOnes() {
        Secrets s = new Secrets(" Customer.ID , jdbc*user, ,*");
        assertTrue(s.isSecretName("app.customer.id"));
        assertTrue(s.isSecretName("JDBC.readonly.USER"));
        assertTrue("built-in words still apply", s.isSecretName("db.password"));
        assertFalse("a lone * is ignored, not \"mask everything\"", s.isSecretName("java.version"));
        assertFalse("regex characters are literal", new Secrets("a.b").isSecretName("axb"));
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
                builtIn.maskValue("jdbc:oracle:thin:scott/tiger@db")); // no scheme://, left as is: covered by name masking
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
        JSONObject o = ServerInfo.masked(m, new Secrets("customer.id"));
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
}
