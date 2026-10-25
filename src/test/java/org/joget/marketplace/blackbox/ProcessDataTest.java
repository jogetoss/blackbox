package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Set;
import org.joget.workflow.model.WorkflowActivity;
import org.joget.workflow.model.WorkflowProcess;
import org.joget.workflow.model.WorkflowVariable;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ProcessDataTest {

    private static final String PID = "70_leave_approval";

    private final ProcessData.Source src = new ProcessData.Source() {
        @Override
        public WorkflowProcess process(String id) {
            WorkflowProcess p = new WorkflowProcess();
            if (id.equals(PID) || id.equals("71_other_flow")) {
                p.setId(id.equals(PID) ? "leave#3#approval" : "other#1#flow");
                p.setInstanceId(id);
                p.setName("Approval");
                p.setState("open.running");
                p.setRequesterId("siti");
                p.setCreatedTime(new Date(1000));
            }
            return p; // Joget returns an empty one for unknown ids
        }

        @Override
        public Collection<WorkflowActivity> activities(String id) {
            WorkflowActivity a = new WorkflowActivity();
            a.setId("82_" + PID + "_approve");
            a.setActivityDefId("approve");
            a.setName("Approve");
            a.setState("open.not_running.not_started");
            return Collections.singletonList(a);
        }

        @Override
        public WorkflowActivity activityInfo(String id) {
            WorkflowActivity a = new WorkflowActivity();
            a.setAssignmentUsers(new String[]{"clark", "siti"});
            return a;
        }

        @Override
        public Collection<WorkflowVariable> variables(String id) {
            WorkflowVariable status = new WorkflowVariable();
            status.setName("status");
            status.setVal("pending");
            WorkflowVariable reason = new WorkflowVariable();
            reason.setName("reason");
            reason.setVal("Family trip to Penang");
            return Arrays.asList(status, reason);
        }

        @Override
        public String processOf(String id) {
            return id.equals("82_" + PID + "_approve") ? PID : null;
        }
    };

    @Test
    public void findsProcessesFromProcessAndActivityIds() {
        Set<String> ids = ProcessData.processIds(Arrays.asList("82_" + PID + "_approve", PID, "REQ-1", null), src);
        assertEquals(Collections.singleton(PID), ids);
    }

    @Test
    public void collectsThisAppsProcessesMasked() throws Exception {
        Masker m = new Masker(null, "k".getBytes(StandardCharsets.UTF_8));
        m.setDefinition("pending");
        Masker.IdMap ids = new Masker.IdMap(false);
        ids.add(PID);
        JSONArray list = ProcessData.collect("leave", new java.util.LinkedHashSet<>(Arrays.asList(PID, "71_other_flow", "9_nothing_here")),
                src, SampleData.Mode.MASKED, m, ids);
        assertEquals("only the leave app's process", 1, list.length());
        JSONObject p = list.getJSONObject(0);
        assertEquals(PID, p.getString("id"));
        assertEquals(ids.get(PID), p.getString("recordId"));
        assertEquals("leave#3#approval", p.getString("processDefId"));
        assertEquals("user1", p.getString("requester"));
        assertEquals("pending", p.getJSONObject("variables").getString("status"));
        String reason = p.getJSONObject("variables").getString("reason");
        assertEquals(21, reason.length());
        assertNotEquals("Family trip to Penang", reason);
        JSONObject a = p.getJSONArray("activities").getJSONObject(0);
        assertEquals("approve", a.getString("defId"));
        assertEquals("user2", a.getJSONArray("assignees").getString(0));
        assertEquals("the same pseudonym as the requester", "user1", a.getJSONArray("assignees").getString(1));
    }

    @Test
    public void realValuesKeepNames() throws Exception {
        Masker m = new Masker(null, "k".getBytes(StandardCharsets.UTF_8));
        JSONObject p = ProcessData.collect("leave", Collections.singleton(PID), src, SampleData.Mode.REAL, m, new Masker.IdMap(false))
                .getJSONObject(0);
        assertEquals("siti", p.getString("requester"));
        assertEquals("Family trip to Penang", p.getJSONObject("variables").getString("reason"));
    }
}
