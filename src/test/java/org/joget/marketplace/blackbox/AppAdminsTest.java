package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import org.junit.Test;

public class AppAdminsTest {

    private static Properties props(String users, String groups) {
        Properties p = new Properties();
        if (users != null) {
            p.setProperty(AppAdmins.USERS, users);
        }
        if (groups != null) {
            p.setProperty(AppAdmins.GROUPS, groups);
        }
        return p;
    }

    @Test
    public void usersAndGroupsAsJogetSplitsThem() {
        Properties p = props(" ann ; bob,cat ", "g1;g2");
        assertTrue(AppAdmins.admits(p, "bob", Collections.emptySet()));
        assertTrue(AppAdmins.admits(p, "cat", null));
        assertFalse("whole names only", AppAdmins.admits(p, "an", Collections.emptySet()));
        assertTrue("by group", AppAdmins.admits(p, "dan", new HashSet<>(Arrays.asList("g2"))));
        assertFalse(AppAdmins.admits(p, "dan", new HashSet<>(Arrays.asList("g3"))));
        assertFalse("no admins set", AppAdmins.admits(props(null, null), "ann", Collections.emptySet()));
        assertFalse(AppAdmins.admits(props("", " "), "", Collections.emptySet()));
    }

    @Test
    public void readsAppPropertiesWithoutCreatingFolders() throws Exception {
        Path src = Files.createTempDirectory("app_src");
        Files.createDirectories(src.resolve("crm"));
        Files.write(src.resolve("crm").resolve(AppAdmins.FILE), "ROLE_ADMIN=ann\nROLE_ADMIN_GROUP=ops\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("ann", AppAdmins.read(src, "crm").getProperty(AppAdmins.USERS));
        assertTrue(AppAdmins.read(src, "hr").isEmpty());
        assertFalse("no folder made for a missing app", Files.exists(src.resolve("hr")));
        assertTrue("no paths outside app_src", AppAdmins.read(src, "../crm").isEmpty());
        assertTrue(AppAdmins.read(src, "..").isEmpty());
        assertTrue(AppAdmins.anyAdmits(src, "ann", Collections.emptySet()));
        assertTrue(AppAdmins.anyAdmits(src, "zed", new HashSet<>(Arrays.asList("ops"))));
        assertFalse(AppAdmins.anyAdmits(src, "zed", Collections.emptySet()));
        assertFalse(AppAdmins.anyAdmits(src.resolve("missing"), "ann", Collections.emptySet()));
    }
}
