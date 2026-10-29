package org.joget.marketplace.blackbox;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class ExportJobsTest {

    @Test
    public void exportingIsLostOnlyWhenQueuedHereBeforeTheQueueStarted() throws Exception {
        ExportJobs.start();
        try {
            JSONObject now = ExportJobs.exporting();
            assertFalse("queued since the start: still running", ExportJobs.lost(now));
            JSONObject before = new JSONObject(now.toString()).put("at", 1);
            assertTrue("queued before a restart", ExportJobs.lost(before));
            assertFalse("another node's job", ExportJobs.lost(new JSONObject(before.toString()).put("node", "other-node")));
            assertTrue("older version: no node", ExportJobs.lost(new JSONObject().put("state", "exporting").put("at", 1)));
            assertFalse("ended", ExportJobs.lost(new JSONObject(before.toString()).put("state", "done")));
            assertFalse(ExportJobs.lost(null));
        } finally {
            ExportJobs.stop();
        }
    }
}
