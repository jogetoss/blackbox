package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.joget.apps.app.model.AppDefinition;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class AppExportTest {

    @Test
    public void findsAppsInJogetUrls() {
        List<AppExport.Seen> apps = AppExport.find(Arrays.asList(
                "http://h/jw/web/userview/leave/v/_/home",
                "/jw/web/userview/leave/v/_/apply?_mode=add",
                "/jw/web/embed/userview/leave/v/_/list",
                "/jw/web/app/hr/3/form/embed?_submitButtonLabel=Submit",
                "/jw/web/console/app/crm/2/forms",
                "/jw/web/json/workflow/process/start/expense#4#approval",
                "/jw/web/json/plugin/x/service?appId=inv&appVersion=7",
                "/jw/web/userview/appcenter/v/_/home",
                "/jw/web/app/crm/resources/crm.png?timestamp=1",
                "/jw/web/app/isr/2/resources/icon.png",
                "/jw/web/userview/screenshot/appcenter/v?timestamp=1",
                "/jw/web/json/workflow/currentUsername",
                null));
        assertEquals(Arrays.asList("leave", "hr", "crm", "expense", "inv"), ids(apps));
        assertEquals("most used first", 3, apps.get(0).count);
        assertNull("userview URLs name no version", apps.get(0).version);
        assertEquals("3", apps.get(1).version);
        assertEquals("2", apps.get(2).version);
        assertEquals("process definition id", "4", apps.get(3).version);
        assertEquals("appVersion parameter", "7", apps.get(4).version);
    }

    @Test
    public void latestMeansThePublishedVersion() {
        List<AppExport.Seen> apps = AppExport.find(Arrays.asList("/jw/web/app/hr/latest/form/x", "/jw/web/client/app/hr/_/form/y"));
        assertEquals(1, apps.size());
        assertNull(apps.get(0).version);
    }

    @Test
    public void urlsComeFromTheSessionAndTheSystemLog() throws Exception {
        Path dir = Files.createTempDirectory("rec");
        Path session = dir.resolve("session.json");
        Files.write(session, "{\"network\":[{\"url\":\"http://h/jw/web/userview/a%20b/v\"},{\"url\":\"/jw/web/json/workflow/process/start/exp%234%23p\"}]}"
                .getBytes(StandardCharsets.UTF_8));
        Path log = dir.resolve("system-log.json");
        Files.write(log, "{\"entries\":[{\"kind\":\"request\",\"url\":\"/jw/web/app/hr/1/form/x\"},{\"kind\":\"log\"}]}".getBytes(StandardCharsets.UTF_8));
        List<String> urls = AppExport.urls(session, log);
        assertEquals(3, urls.size());
        assertEquals("decoded", "/jw/web/json/workflow/process/start/exp#4#p", urls.get(1));
        assertEquals(Arrays.asList("exp", "hr"), ids(AppExport.find(urls.subList(1, 3))));
        assertTrue(AppExport.urls(null, dir.resolve("missing.json")).isEmpty());
    }

    private static final String PAGE = "http://h/jw/web/userview/exp/v/_/claims";

    /** session.json with these network entries and console errors (JSON objects), and a system log with these entries. */
    private static List<AppExport.Hit> hits(String network, String console, String log) throws Exception {
        Path dir = Files.createTempDirectory("rec");
        Path session = dir.resolve("session.json");
        Files.write(session, ("{\"network\":[" + network + "],\"console\":[" + console + "]}").getBytes(StandardCharsets.UTF_8));
        Path systemLog = dir.resolve("system-log.json");
        Files.write(systemLog, ("{\"entries\":[" + log + "]}").getBytes(StandardCharsets.UTF_8));
        return AppExport.hits(session, systemLog);
    }

    private static String req(int t, String url, int status, String referer) {
        return "{\"t\":" + t + ",\"url\":\"" + url + "\",\"status\":" + status + ",\"type\":\"" + (referer == null ? "Document" : "XHR")
                + "\",\"requestHeaders\":{\"Referer\":\"" + (referer == null ? "" : referer) + "\"}}";
    }

    @Test
    public void appsWithProblemsComeFirst() throws Exception {
        List<AppExport.Seen> apps = AppExport.find(hits(
                req(0, "http://h/jw/web/userview/leave/v/_/home", 200, null)
                        + "," + req(10, "http://h/jw/web/json/data/list/leave/1/x/", 200, "http://h/jw/web/userview/leave/v/_/home")
                        + "," + req(20, "http://h/jw/web/json/data/list/leave/1/y/", 200, "http://h/jw/web/userview/leave/v/_/home")
                        + "," + req(30, PAGE, 200, null)
                        + "," + req(40, "http://h/jw/wro/missing.js", 404, PAGE),
                "", ""));
        assertEquals("exp had a failed request on its page", Arrays.asList("exp", "leave"), ids(apps));
        assertEquals(1, apps.get(0).problems);
        assertEquals(1, apps.get(0).count);
        assertEquals(3, apps.get(1).count);
    }

    @Test
    public void consoleErrorsAndServerErrorsCountForTheirApp() throws Exception {
        List<AppExport.Seen> apps = AppExport.find(hits(
                req(0, "http://h/jw/web/userview/leave/v/_/home", 200, null) + "," + req(10, "http://h/jw/web/userview/leave/v/_/a", 200, null)
                        + "," + req(20, PAGE, 200, null) + "," + req(25, "http://h/jw/web/userview/leave/v/_/b", 200, null),
                "{\"t\":22,\"level\":\"error\"},{\"t\":23,\"level\":\"log\"}",
                "{\"kind\":\"log\",\"level\":\"ERROR\",\"req\":\"POST /jw/web/userview/exp/v/_/claims\"},"
                        + "{\"kind\":\"log\",\"level\":\"ERROR\",\"other\":true,\"req\":\"GET /jw/web/userview/leave/v/_/a\"}"));
        assertEquals(Arrays.asList("exp", "leave"), ids(apps));
        assertEquals("a console error on its page, an error line of its request", 2, apps.get(0).problems);
        assertEquals("other threads' lines don't count", 0, apps.get(1).problems);
    }

    @Test
    public void theSystemLogNamesNoNewAppsWhenTheBrowserDid() throws Exception {
        // Service worker fetches of other tabs reach the system log too: App Center icons, other apps
        List<AppExport.Seen> apps = AppExport.find(hits(req(0, PAGE, 200, null), "",
                "{\"kind\":\"request\",\"url\":\"/jw/web/userview/crm/v/_/home\",\"status\":500},"
                        + "{\"kind\":\"request\",\"url\":\"/jw/web/userview/exp/v/_/claims\",\"status\":200}"));
        assertEquals(Arrays.asList("exp"), ids(apps));
        assertEquals(2, apps.get(0).count);
        // No app in the browser's requests: the system log names them
        assertEquals(Arrays.asList("crm"), ids(AppExport.find(hits(req(0, "http://h/jw/web/console/home", 200, null), "",
                "{\"kind\":\"request\",\"url\":\"/jw/web/userview/crm/v/_/home\",\"status\":200}"))));
    }

    @Test
    public void appCenterOnlyWithProblemsOrAlone() throws Exception {
        String appCenter = "http://h/jw/web/userview/appcenter/v/_/home";
        assertEquals("passed through", Arrays.asList("exp"),
                ids(AppExport.find(hits(req(0, appCenter, 200, null) + "," + req(5, PAGE, 200, null), "", ""))));
        assertEquals("the only app", Arrays.asList("appcenter"), ids(AppExport.find(hits(req(0, appCenter, 200, null), "", ""))));
        assertEquals("an icon failed on its page", Arrays.asList("appcenter", "exp"), ids(AppExport.find(hits(req(0, appCenter, 200, null)
                + "," + req(1, "http://h/jw/web/app/crm/resources/crm.png", 404, appCenter) + "," + req(5, PAGE, 200, null), "", ""))));
    }

    @Test
    public void savesTheExportsAndListsWhatHappened() throws Exception {
        Path rec = Files.createTempDirectory("rec");
        List<AppExport.Seen> apps = AppExport.find(Arrays.asList("/jw/web/app/hr/3/x", "/jw/web/app/hr/3/y", "/jw/web/userview/gone/v",
                "/jw/web/userview/bad/v", "/jw/web/userview/a1/v", "/jw/web/userview/a2/v", "/jw/web/userview/a3/v"));
        apps.get(1).problems = 2; // gone
        JSONArray summary = AppExport.save(rec, apps, (appId, version, out) -> {
            if (appId.equals("gone")) {
                return null;
            }
            if (appId.equals("bad")) {
                throw new IllegalStateException("broken");
            }
            out.write(("jwa of " + appId).getBytes(StandardCharsets.UTF_8));
            AppDefinition d = new AppDefinition();
            d.setAppId(appId);
            d.setName("App " + appId);
            d.setVersion(version != null ? Long.valueOf(version) : 1L);
            d.setPublished(version == null);
            return d;
        });
        assertEquals("hr, a1, a2 exported; a3 is the 6th app", 3, summary.length());
        assertEquals("App hr", summary.getJSONObject(0).getString("name"));
        assertEquals(3, summary.getJSONObject(0).getLong("version"));

        JSONObject list = new JSONObject(new String(Files.readAllBytes(AppExport.list(rec)), StandardCharsets.UTF_8));
        JSONArray all = list.getJSONArray("apps");
        assertEquals(6, all.length());
        assertEquals("saved", all.getJSONObject(0).getString("state"));
        assertEquals("hr-3.jwa", all.getJSONObject(0).getString("file"));
        assertEquals(2, all.getJSONObject(0).getInt("requests"));
        assertEquals("missing", state(all, "gone"));
        assertEquals(2, all.getJSONObject(1).getInt("problems"));
        assertFalse(all.getJSONObject(0).has("problems"));
        assertEquals("failed", state(all, "bad"));
        assertEquals("skipped", state(all, "a3"));
        assertEquals("jwa of hr", new String(Files.readAllBytes(AppExport.file(rec, "hr-3.jwa")), StandardCharsets.UTF_8));
        assertEquals(3, AppExport.files(rec).size());
        assertFalse("no leftovers", Files.exists(rec.resolve("app/gone.part")));
        assertNull("only exported file names", AppExport.file(rec, "../meta.json"));
        assertNull(AppExport.file(rec, "apps.json"));
    }

    @Test
    public void mostAppsIsASetting() throws Exception {
        List<AppExport.Seen> apps = AppExport.find(Arrays.asList("/jw/web/userview/a1/v", "/jw/web/userview/a2/v", "/jw/web/userview/a3/v"));
        AppExport.Exporter exporter = (appId, version, out) -> {
            out.write(1);
            AppDefinition d = new AppDefinition();
            d.setAppId(appId);
            d.setVersion(1L);
            return d;
        };
        assertEquals(1, AppExport.save(Files.createTempDirectory("rec"), apps, exporter, null, 1).length());
        assertEquals("0: no limit", 3, AppExport.save(Files.createTempDirectory("rec"), apps, exporter, null, 0).length());
    }

    @Test
    public void noAppsNoFolder() throws Exception {
        Path rec = Files.createTempDirectory("rec");
        assertEquals(0, AppExport.save(rec, AppExport.find(Arrays.asList("/jw/web/console/home")), (a, v, o) -> null).length());
        assertNull(AppExport.list(rec));
    }

    private static List<String> ids(List<AppExport.Seen> apps) {
        return Arrays.asList(apps.stream().map((s) -> s.appId).toArray(String[]::new));
    }

    private static String state(JSONArray all, String id) {
        for (int i = 0; i < all.length(); i++) {
            if (id.equals(all.getJSONObject(i).optString("id"))) {
                return all.getJSONObject(i).optString("state");
            }
        }
        return null;
    }
}
