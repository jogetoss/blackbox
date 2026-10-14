package org.joget.blackbox;

import java.util.ArrayList;
import java.util.Collection;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

public class Activator implements BundleActivator {
    protected Collection<ServiceRegistration> registrationList;

    public void start(BundleContext context) {
        registrationList = new ArrayList<>();
        registrationList.add(context.registerService(BlackBoxPlugin.class.getName(), new BlackBoxPlugin(), null));
        registrationList.add(context.registerService(BlackBoxPageFilter.class.getName(), new BlackBoxPageFilter(), null));
        registrationList.add(context.registerService(BlackBoxApiFilter.class.getName(), new BlackBoxApiFilter(), null));
    }

    public void stop(BundleContext context) {
        for (ServiceRegistration registration : registrationList) {
            registration.unregister();
        }
    }
}
