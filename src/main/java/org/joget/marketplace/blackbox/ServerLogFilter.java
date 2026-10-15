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
 * lines are captured ({@link ServerLogCapture}), and records each one's method, URL, status and time.
 *
 * Runs on every URL before Spring Security, so log lines from the login itself are captured too. Requests without
 * the header cost one header lookup.
 */
public class ServerLogFilter extends PluginWebFilterAbstract {

    @Override
    public String getName() {
        return "BlackBox Server Log";
    }

    @Override
    public String getVersion() {
        return BlackBoxPlugin.VERSION;
    }

    @Override
    public String getDescription() {
        return "Captures the server logs made during a BlackBox recording.";
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
        String id = request.getHeader(ServerLogCapture.HEADER);
        ServerLogCapture.Capture capture = id == null ? null : ServerLogCapture.find(id);
        if (capture == null) {
            chain.doFilter(req, res);
            return;
        }
        HttpServletResponse response = (HttpServletResponse) res;
        long start = System.currentTimeMillis();
        // The viewer compares this with the browser's clock to line up the server log with the video
        response.setHeader(ServerLogCapture.TIME_HEADER, String.valueOf(start));
        String url = request.getRequestURI() + (request.getQueryString() != null ? "?" + request.getQueryString() : "");
        Object prev = ServerLogCapture.begin(capture, request.getMethod() + " " + url);
        Throwable failure = null;
        try {
            chain.doFilter(req, res);
        } catch (IOException | ServletException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            ServerLogCapture.end(prev);
            capture.request(start, request.getMethod(), url, response.getStatus(), System.currentTimeMillis() - start, failure);
        }
    }
}
