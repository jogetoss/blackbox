package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import javax.servlet.http.HttpServletRequest;
import org.junit.Test;

/** "Allow uploads over HTTP" off: which requests count as safe, and which settings default to on. */
public class HttpsOnlyTest {

    private static HttpServletRequest req(boolean secure, String host, String forwardedProto) {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpsOnlyTest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (p, m, a) -> {
                    switch (m.getName()) {
                        case "isSecure": return secure;
                        case "getServerName": return host;
                        case "getHeader": return "X-Forwarded-Proto".equals(a[0]) ? forwardedProto : null;
                        default: return null;
                    }
                });
    }

    @Test
    public void httpsOrLocalhostIsSafe() {
        assertTrue(BlackBoxPlugin.secureOrLocal(req(true, "joget.example.com", null)));
        assertTrue("behind a TLS proxy", BlackBoxPlugin.secureOrLocal(req(false, "joget.example.com", "https")));
        assertTrue(BlackBoxPlugin.secureOrLocal(req(false, "localhost", null)));
        assertTrue(BlackBoxPlugin.secureOrLocal(req(false, "127.0.0.1", null)));
        assertFalse(BlackBoxPlugin.secureOrLocal(req(false, "joget.example.com", null)));
        assertFalse(BlackBoxPlugin.secureOrLocal(req(false, "joget.example.com", "http")));
        assertFalse("a name that only starts with localhost", BlackBoxPlugin.secureOrLocal(req(false, "localhost.evil.com", null)));
    }

    @Test
    public void uploadActionsAreTheOnesThatSendData() {
        for (String a : new String[]{"create", "video", "import", "recording-start", "recording-stop", "recording-ping"}) {
            assertTrue(a, BlackBoxPlugin.UPLOAD_ACTIONS.contains(a));
        }
        assertFalse(BlackBoxPlugin.UPLOAD_ACTIONS.contains("delete"));
    }

    @Test
    public void unsavedCheckboxesTakeTheirDefault() {
        assertTrue("allowHttpUpload / allowUnsandboxedTraces: on until unticked", BlackBoxPlugin.flagValue(null, true));
        assertFalse("unticked and saved", BlackBoxPlugin.flagValue("", true));
        assertFalse("allow* for non-admins: off until ticked", BlackBoxPlugin.flagValue(null, false));
        assertTrue(BlackBoxPlugin.flagValue("true", false));
    }

    @Test
    public void onlyAllowedExtensionsSkipTheCsrfToken() {
        String official = "chrome-extension://" + BlackBoxPlugin.OFFICIAL_EXTENSION_ID;
        assertTrue("never saved: the official one", BlackBoxPlugin.fromAllowedExtension(official, null));
        assertFalse(BlackBoxPlugin.fromAllowedExtension("chrome-extension://abcdefghijklmnopabcdefghijklmnop", null));
        assertTrue("empty: any extension", BlackBoxPlugin.fromAllowedExtension("chrome-extension://abcdefghijklmnopabcdefghijklmnop", " "));
        assertTrue(BlackBoxPlugin.fromAllowedExtension("chrome-extension://bbb", "aaa, bbb"));
        assertFalse("a prefix is not enough", BlackBoxPlugin.fromAllowedExtension("chrome-extension://bb", "aaa, bbb"));
        assertFalse("a web page", BlackBoxPlugin.fromAllowedExtension("https://evil.example", ""));
        assertFalse(BlackBoxPlugin.fromAllowedExtension(null, ""));
    }
}
