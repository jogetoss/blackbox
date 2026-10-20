package org.joget.marketplace.blackbox;

import java.util.ArrayList;
import java.util.Collection;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.joget.commons.util.LogUtil;

public class Activator implements BundleActivator {
    protected Collection<ServiceRegistration> registrationList;

    public void start(BundleContext context) {
        registrationList = new ArrayList<>();
        registrationList.add(context.registerService(BlackBoxPlugin.class.getName(), new BlackBoxPlugin(), null));
        registrationList.add(context.registerService(BlackBoxPageFilter.class.getName(), new BlackBoxPageFilter(), null));
        registrationList.add(context.registerService(BlackBoxApiFilter.class.getName(), new BlackBoxApiFilter(), null));
        registrationList.add(context.registerService(SystemLogFilter.class.getName(), new SystemLogFilter(), null));
        try {
            SystemLogCapture.start();
        } catch (Throwable e) {
            // e.g. a Joget without the log4j 1.2 bridge: recordings still work, without system logs
            LogUtil.error(getClass().getName(), e, "BlackBox could not start system log capture");
        }
    }

    public void stop(BundleContext context) {
        try {
            SystemLogCapture.stop();
        } catch (Throwable e) {
            LogUtil.error(getClass().getName(), e, "BlackBox could not stop system log capture");
        }
        for (ServiceRegistration registration : registrationList) {
            registration.unregister();
        }
    }
}
