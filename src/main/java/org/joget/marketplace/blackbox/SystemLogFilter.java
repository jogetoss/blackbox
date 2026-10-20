package org.joget.marketplace.blackbox;

import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.joget.apps.app.model.PluginWebFilterAbstract;

/**
 * Marks requests that carry X-BlackBox-Id (added by the extension to the recorded tab's requests) so their log
 * lines are captured ({@link SystemLogCapture}), records each one's method, URL, status and time, and hands slow
 * and failed ones to {@link TraceCollector} for their Glowroot trace.
 *
 * Runs on every URL before Spring Security, so log lines from the login itself are captured too. Requests without
 * the header cost one header lookup.
 */
public class SystemLogFilter extends PluginWebFilterAbstract {

    @Override
    public String getName() {
        return "BlackBox System Log";
    }

    @Override
    public String getVersion() {
        return BlackBoxPlugin.VERSION;
    }

    @Override
    public String getDescription() {
        return "Captures the system logs made during a BlackBox recording.";
    }

    public String getLabel() {
        return getName();
    }

    @Override
    public String getClassName() {
        return getClass().getName();
    }

    public String getPropertyOptions() {
        return "";
    }

    @Override
    public String[] getUrlPatterns() {
        return new String[]{"/**"};
    }

    @Override
    public boolean isPositionAfterSecurityFilter() {
        return false;
    }

    @Override
    public int getOrder() {
        return -1000; // wrap the other plugin filters, so their log lines are captured too
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        String id = request.getHeader(SystemLogCapture.HEADER);
        SystemLogCapture.Capture capture = id == null ? null : SystemLogCapture.find(id);
        if (capture == null) {
            chain.doFilter(req, res);
            return;
        }
        HttpServletResponse response = (HttpServletResponse) res;
        long start = System.currentTimeMillis();
        // The viewer compares this with the browser's clock to line up the system log with the video
        response.setHeader(SystemLogCapture.TIME_HEADER, String.valueOf(start));
        String url = request.getRequestURI() + (request.getQueryString() != null ? "?" + request.getQueryString() : "");
        GlowrootTag.tag(id); // a Glowroot trace of it, if Glowroot keeps one, carries the recording id
        Object prev = SystemLogCapture.begin(capture, request.getMethod() + " " + url);
        TraceCollector.Running run = TraceCollector.begin(capture, request.getMethod(), url, start);
        Throwable failure = null;
        try {
            chain.doFilter(req, res);
        } catch (IOException | ServletException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            SystemLogCapture.end(prev);
            long ms = System.currentTimeMillis() - start;
            capture.request(start, request.getMethod(), url, response.getStatus(), ms, failure);
            TraceCollector.end(run, response.getStatus(), ms, failure);
        }
    }
}
