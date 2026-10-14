package org.joget.marketplace.blackbox;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.joget.apps.app.model.PluginWebFilterAbstract;

/**
 * Serves the BlackBox pages at <context>/blackbox/...
 *
 * They are static shells; all data comes from /api/blackbox, which checks the user.
 */
public class BlackBoxPageFilter extends PluginWebFilterAbstract {

    private static final String PREFIX = "/blackbox";
    private static final Map<String, String> FILES = new HashMap<>();

    static {
        // URL path (after /blackbox) -> resource, content type. Nothing else under /blackbox is served.
        FILES.put("", "list.html");
        FILES.put("/", "list.html");
        FILES.put("/view", "view.html");
        for (String f : new String[]{"blackbox.css", "common.js", "list.js", "view.js", "viewer.js", "viewer.css"}) {
            FILES.put("/assets/" + f, f);
        }
    }

    @Override
    public String getName() {
        return "BlackBox Pages";
    }

    @Override
    public String getVersion() {
        return BlackBoxPlugin.VERSION;
    }

    @Override
    public String getDescription() {
        return "Serves the BlackBox recording pages at `wflow/blackbox/`.";
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
        return new String[]{PREFIX, PREFIX + "/**"};
    }

    @Override
    public boolean isPositionAfterSecurityFilter() {
        return false; // static files only, no user needed
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.equals(PREFIX) && !path.startsWith(PREFIX + "/")) {
            chain.doFilter(req, res);
            return;
        }
        String name = FILES.get(path.substring(PREFIX.length()));
        if (name == null || !"GET".equalsIgnoreCase(request.getMethod()) && !"HEAD".equalsIgnoreCase(request.getMethod())) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (path.equals(PREFIX)) {
            // relative asset links need the trailing slash
            response.sendRedirect(request.getContextPath() + PREFIX + "/");
            return;
        }
        try (InputStream in = BlackBoxPageFilter.class.getClassLoader().getResourceAsStream("static/blackbox/" + name)) {
            if (in == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            response.setContentType(contentType(name));
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("X-Content-Type-Options", "nosniff");
            if (name.endsWith(".html")) {
                response.setHeader("X-Frame-Options", "SAMEORIGIN");
            }
            OutputStream out = response.getOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    private static String contentType(String name) {
        if (name.endsWith(".html")) {
            return "text/html;charset=UTF-8";
        } else if (name.endsWith(".css")) {
            return "text/css;charset=UTF-8";
        }
        return "application/javascript;charset=UTF-8";
    }
}
