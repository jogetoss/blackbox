package org.joget.marketplace.blackbox;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Properties;

/**
 * An app's own admins, as Joget 8.2 reads them for App Composer (EnhancedWorkflowUserManager.checkCustomAppAdmin):
 * the ROLE_ADMIN (usernames) and ROLE_ADMIN_GROUP (group ids) keys of app_src/{appId}/app.properties, split on ";" or
 * ",". Read directly: AppDevUtil.getAppDevProperties creates the app's folder when it is missing.
 */
final class AppAdmins {

    static final String FILE = "app.properties";
    static final String USERS = "ROLE_ADMIN";
    static final String GROUPS = "ROLE_ADMIN_GROUP";

    private AppAdmins() {
    }

    /** True when the properties name the user, or one of their groups, as an admin of the app. */
    static boolean admits(Properties props, String username, Collection<String> groupIds) {
        if (props == null || username == null) {
            return false;
        }
        for (String u : split(props.getProperty(USERS))) {
            if (u.equals(username)) {
                return true;
            }
        }
        for (String g : split(props.getProperty(GROUPS))) {
            if (groupIds != null && groupIds.contains(g)) {
                return true;
            }
        }
        return false;
    }

    /** app_src/{folder}/app.properties, or empty properties when there is none. folder: Joget's normalized app id. */
    static Properties read(Path appSrc, String folder) {
        Properties props = new Properties();
        if (appSrc == null || folder == null || folder.isEmpty() || folder.contains("/") || folder.contains("\\") || folder.startsWith(".")) {
            return props;
        }
        Path f = appSrc.resolve(folder).resolve(FILE);
        if (Files.isRegularFile(f)) {
            try (InputStream in = Files.newInputStream(f)) {
                props.load(in);
            } catch (IOException e) {
                // unreadable: no app admins, as in Joget
            }
        }
        return props;
    }

    /** True when the user is an admin of at least one app (what the popup offers before the apps are known). */
    static boolean anyAdmits(Path appSrc, String username, Collection<String> groupIds) {
        if (appSrc == null || !Files.isDirectory(appSrc)) {
            return false;
        }
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(appSrc)) {
            for (Path dir : dirs) {
                if (Files.isDirectory(dir) && admits(read(appSrc, dir.getFileName().toString()), username, groupIds)) {
                    return true;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    private static String[] split(String value) {
        if (value == null || value.trim().isEmpty()) {
            return new String[0];
        }
        String[] parts = value.split("[;,]");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }
}
