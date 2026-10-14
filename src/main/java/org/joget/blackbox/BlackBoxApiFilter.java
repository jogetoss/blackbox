package org.joget.blackbox;

import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.joget.apps.app.model.PluginWebFilterAbstract;
import org.joget.apps.app.service.AppUtil;
import org.joget.plugin.base.PluginManager;

/**
 * Serves the BlackBox API at <context>/api/blackbox.
 *
 * /api/* runs Spring Security (so the logged-in user is known) but not JsonResponseFilter, which rejects
 * requests whose Referer is not Joget - the extension's requests have none. The request never reaches
 * Joget's apiServlet: this filter answers it and does not continue the chain.
 */
public class BlackBoxApiFilter extends PluginWebFilterAbstract {

    static final String PATH = "/api/blackbox";

    @Override
    public String getName() {
        return "BlackBox API";
    }

    @Override
    public String getVersion() {
        return BlackBoxPlugin.VERSION;
    }

    @Override
    public String getDescription() {
        return "BlackBox recording API at /api/blackbox.";
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
        return new String[]{PATH, PATH + "/**"};
    }

    @Override
    public boolean isPositionAfterSecurityFilter() {
        return true;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.equals(PATH)) {
            chain.doFilter(req, res);
            return;
        }
        // Loaded through the plugin manager so the saved settings are applied
        PluginManager pm = (PluginManager) AppUtil.getApplicationContext().getBean("pluginManager");
        BlackBoxPlugin plugin = (BlackBoxPlugin) pm.getPlugin(BlackBoxPlugin.class.getName());
        if (plugin == null) {
            BlackBoxPlugin.error(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "BlackBox plugin is not available.");
            return;
        }
        plugin.handle(request, response);
    }
}
