package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.json.JSONArray;
import org.junit.Test;

public class MaskerSettingTest {

    private static final byte[] KEY = "k".getBytes(StandardCharsets.UTF_8);

    @Test
    public void oneWordPerLineAndCommasBothWork() {
        Masker m = new Masker("salary\r\nhome\n\nbank, iban", KEY);
        for (String c : new String[]{"salary", "homeAddress", "bank_name", "iban"}) {
            assertTrue(c, m.isSensitive(c));
        }
        assertFalse(m.isSensitive("email"));
    }

    @Test
    public void defaultMatchesTheSettingDefault() throws Exception {
        JSONArray props = new JSONArray(new String(Files.readAllBytes(
                Paths.get("src/main/resources/properties/blackboxPlugin.json")), StandardCharsets.UTF_8))
                .getJSONObject(0).getJSONArray("properties");
        for (int i = 0; i < props.length(); i++) {
            if ("sensitiveColumns".equals(props.getJSONObject(i).optString("name"))) {
                assertEquals(Masker.DEFAULT_SENSITIVE, props.getJSONObject(i).getString("value"));
                return;
            }
        }
        fail("no sensitiveColumns setting");
    }
}
