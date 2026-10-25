package org.joget.marketplace.blackbox;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.joget.workflow.model.WorkflowActivity;
import org.joget.workflow.model.WorkflowProcess;
import org.joget.workflow.model.WorkflowVariable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The process instances a recording touched, for reading only (Joget has no import for running processes): state,
 * requester, dates, workflow variables and each activity with its state, assignees and dates. Found from the
 * process and activity ids in the recorded URLs and form posts and in the sample rows (workflow forms use the process
 * id as record id). Masked like the sample data: user names become the same pseudonyms, variable values are masked
 * (real values: only variables named like a sensitive field).
 */
final class ProcessData {

    static final int MAX_PROCESSES = 20;
    static final int MAX_ACTIVITIES = 100;

    private static final Pattern WORKFLOW_ID = Pattern.compile("\\d{1,12}_[A-Za-z0-9][A-Za-z0-9_-]*_[A-Za-z0-9_-]+");

    /** Joget's WorkflowManager, or a test double. */
    interface Source {
        WorkflowProcess process(String processId);

        Collection<WorkflowActivity> activities(String processId);

        /** An activity with its assignees (getAssignmentUsers, getNameOfAcceptedUser), or null. */
        WorkflowActivity activityInfo(String activityId);

        Collection<WorkflowVariable> variables(String processId);

        /** For an activity id, its process id; else null. */
        String processOf(String activityId);
    }

    private ProcessData() {
    }

    /** Process ids among these ids: process ids as they are, activity ids as their process. */
    static Set<String> processIds(Collection<String> ids, Source src) {
        Set<String> out = new LinkedHashSet<>();
        for (String id : ids) {
            if (id == null || !WORKFLOW_ID.matcher(id).matches()) {
                continue;
            }
            String process = src.processOf(id);
            out.add(process != null ? process : id);
        }
        return out;
    }

    /** The processes of this app (process definition ids start with appId#), as apps.json's "processes". */
    static JSONArray collect(String appId, Set<String> processIds, Source src, SampleData.Mode mode, Masker masker,
            Masker.IdMap ids) throws JSONException {
        JSONArray list = new JSONArray();
        for (String id : processIds) {
            if (list.length() >= MAX_PROCESSES) {
                break;
            }
            WorkflowProcess p;
            try {
                p = src.process(id);
            } catch (RuntimeException e) {
                p = null;
            }
            if (p == null || p.getId() == null || !p.getId().startsWith(appId + "#")) {
                continue; // not a process, or another app's
            }
            JSONObject o = new JSONObject().put("id", id).put("processDefId", p.getId()).put("name", p.getName())
                    .put("version", p.getVersion()).put("state", p.getState());
            if (ids.contains(id)) {
                o.put("recordId", ids.get(id)); // its workflow form row in the sample data
            }
            putUser(o, "requester", p.getRequesterId(), mode, masker);
            putDate(o, "created", p.getCreatedTime());
            putDate(o, "started", p.getStartedTime());
            putDate(o, "finished", p.getFinishTime());
            JSONObject vars = new JSONObject();
            for (WorkflowVariable v : safe(() -> src.variables(id))) {
                if (v != null && v.getName() != null) {
                    String val = v.getVal() != null ? String.valueOf(v.getVal()) : "";
                    vars.put(v.getName(), mode == SampleData.Mode.REAL && !masker.isSensitive(v.getName()) ? val
                            : masker.value(v.getName(), val, Collections.<String>emptySet(), ids));
                }
            }
            o.put("variables", vars);
            JSONArray acts = new JSONArray();
            for (WorkflowActivity a : safe(() -> src.activities(id))) {
                if (acts.length() >= MAX_ACTIVITIES) {
                    o.put("activitiesCut", true);
                    break;
                }
                JSONObject ao = new JSONObject().put("id", a.getId()).put("defId", a.getActivityDefId()).put("name", a.getName())
                        .put("state", a.getState());
                putDate(ao, "created", a.getCreatedTime());
                putDate(ao, "started", a.getStartedTime());
                putDate(ao, "finished", a.getFinishTime());
                WorkflowActivity info;
                try {
                    info = src.activityInfo(a.getId());
                } catch (RuntimeException e) {
                    info = null;
                }
                if (info != null) {
                    JSONArray users = new JSONArray();
                    for (String u : info.getAssignmentUsers() != null ? info.getAssignmentUsers() : new String[0]) {
                        users.put(user(u, mode, masker));
                    }
                    if (users.length() > 0) {
                        ao.put("assignees", users);
                    }
                    putUser(ao, "acceptedBy", info.getNameOfAcceptedUser(), mode, masker);
                }
                acts.put(ao);
            }
            o.put("activities", acts);
            list.put(o);
        }
        return list;
    }

    private interface Call<T> {
        Collection<T> get();
    }

    private static <T> Collection<T> safe(Call<T> call) {
        try {
            Collection<T> c = call.get();
            return c != null ? c : Collections.<T>emptyList();
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    private static String user(String username, SampleData.Mode mode, Masker masker) {
        return mode == SampleData.Mode.REAL ? username : masker.user(username);
    }

    private static void putUser(JSONObject o, String key, String username, SampleData.Mode mode, Masker masker) throws JSONException {
        if (username != null && !username.isEmpty()) {
            o.put(key, user(username, mode, masker));
        }
    }

    private static void putDate(JSONObject o, String key, Date d) throws JSONException {
        if (d != null) {
            o.put(key, d.getTime());
        }
    }

    /** Joget's WorkflowManager. */
    static Source joget() {
        org.joget.workflow.model.service.WorkflowManager wm = (org.joget.workflow.model.service.WorkflowManager)
                org.joget.apps.app.service.AppUtil.getApplicationContext().getBean("workflowManager");
        return new Source() {
            @Override
            public WorkflowProcess process(String processId) {
                return wm.getRunningProcessById(processId);
            }

            @Override
            public Collection<WorkflowActivity> activities(String processId) {
                return wm.getActivityList(processId, 0, MAX_ACTIVITIES + 1, "dateCreated", false);
            }

            @Override
            public WorkflowActivity activityInfo(String activityId) {
                return wm.getRunningActivityInfo(activityId);
            }

            @Override
            public Collection<WorkflowVariable> variables(String processId) {
                return wm.getProcessVariableList(processId);
            }

            @Override
            public String processOf(String activityId) {
                WorkflowActivity a = wm.getActivityById(activityId);
                return a != null ? a.getProcessId() : null;
            }
        };
    }
}
