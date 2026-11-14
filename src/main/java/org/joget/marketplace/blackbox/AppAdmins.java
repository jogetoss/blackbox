package org.joget.marketplace.blackbox;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Properties;

final class AppAdmins {

    static final String FILE = "app.properties";
    static final String USERS = "ROLE_ADMIN";
    static final String GROUPS = "ROLE_ADMIN_GROUP";

    private AppAdmins() {
    }

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
            }
        }
        return props;
    }

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
