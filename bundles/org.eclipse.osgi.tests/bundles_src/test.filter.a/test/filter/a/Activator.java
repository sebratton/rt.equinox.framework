/*******************************************************************************
 * Copyright (c) 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package test.filter.a;

import java.util.Hashtable;
import org.osgi.framework.*;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

public class Activator implements BundleActivator {

	public void start(BundleContext context) throws Exception {
		final boolean[] serviceChanged = {false};
		ServiceListener listener = new ServiceListener() {
			public void serviceChanged(ServiceEvent event) {
				serviceChanged[0] = true;
			}
		};
		context.addServiceListener(listener, "(&(objectClass=java.lang.String)(test=*))");
		final boolean[] modifiedService = {false};
		ServiceTracker tracker = new ServiceTracker(context, FrameworkUtil.createFilter("(&(objectClass=java.lang.String)(test=*))"), new ServiceTrackerCustomizer() {

			public Object addingService(ServiceReference reference) {
				return reference;
			}

			public void modifiedService(ServiceReference reference, Object service) {
				modifiedService[0] = true;
			}

			public void removedService(ServiceReference reference, Object service) {
				// TODO Auto-generated method stub

			}

		});
		tracker.open();
		Hashtable props = new Hashtable();
		props.put("test", "value1");
		ServiceRegistration registration = context.registerService(String.class.getName(), "test", props);
		props.put("test", "value2");
		registration.setProperties(props);
		if (!serviceChanged[0])
			throw new Exception("did not call service listener");
		if (!modifiedService[0])
			throw new Exception("did not call tracker customer");
	}

	public void stop(BundleContext context) throws Exception {
		//nothing; framework will clean up our listeners
	}

}
