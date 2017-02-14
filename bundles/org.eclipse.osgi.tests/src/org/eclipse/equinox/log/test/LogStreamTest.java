/*******************************************************************************
 * Copyright (c) 2007, 2014 IBM Corporation and others All rights reserved. This
 * program and the accompanying materials are made available under the terms of
 * the Eclipse Public License v1.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors: IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.equinox.log.test;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.eclipse.osgi.tests.OSGiTestsActivator;
import org.eclipse.osgi.tests.bundles.AbstractBundleTests;
import org.osgi.framework.ServiceReference;
import org.osgi.service.log.*;
import org.osgi.service.log.admin.LoggerAdmin;
import org.osgi.service.log.admin.LoggerContext;
import org.osgi.service.log.stream.LogStreamProvider;
import org.osgi.util.promise.Promise;
import org.osgi.util.pushstream.PushStream;

public class LogStreamTest extends AbstractBundleTests {

	private LogService logService;
	private ServiceReference logServiceReference;
	private LogStreamProvider logStreamProvider; //LogReaderService reader
	private ServiceReference logStreamProviderReference;
	private ServiceReference<LoggerAdmin> loggerAdminReference;
	private LoggerAdmin loggerAdmin;
	LoggerContext rootLoggerContext;
	Map<String, LogLevel> rootLogLevels;

	public LogStreamTest(String name) {
		setName(name);
	}

	protected void setUp() throws Exception {
		super.setUp();
		logServiceReference = OSGiTestsActivator.getContext().getServiceReference(LogService.class.getName());
		logService = (LogService) OSGiTestsActivator.getContext().getService(logServiceReference);

		logStreamProviderReference = OSGiTestsActivator.getContext().getServiceReference(LogStreamProvider.class.getName());
		logStreamProvider = (LogStreamProvider) OSGiTestsActivator.getContext().getService(logStreamProviderReference);

		loggerAdminReference = OSGiTestsActivator.getContext().getServiceReference(LoggerAdmin.class);

		loggerAdmin = OSGiTestsActivator.getContext().getService(loggerAdminReference);

		rootLoggerContext = loggerAdmin.getLoggerContext(null);
		rootLogLevels = rootLoggerContext.getLogLevels();

		Map<String, LogLevel> copyLogLevels = new HashMap<String, LogLevel>(rootLogLevels);
		copyLogLevels.put(Logger.ROOT_LOGGER_NAME, LogLevel.TRACE);
		rootLoggerContext.setLogLevels(copyLogLevels);
	}

	protected void tearDown() throws Exception {
		rootLoggerContext.setLogLevels(rootLogLevels);
		OSGiTestsActivator.getContext().ungetService(loggerAdminReference);
		OSGiTestsActivator.getContext().ungetService(logServiceReference);
		OSGiTestsActivator.getContext().ungetService(logStreamProviderReference);
		super.tearDown();
	}

	public void testLogWithHistory() throws Exception {
		PushStream<LogEntry> ps = logStreamProvider.createStream();
		Set<String> messageSet = new HashSet<String>() {
			{
				add("test1");
				add("test2");
				add("test3");
			}
		};

		CountDownLatch latch = new CountDownLatch(messageSet.size());
		ps.forEach(m -> {
			messageSet.remove(m.getMessage());
			latch.countDown();
		});
		Promise<Long> count = ps.count();
		logService.log(logServiceReference, LogService.LOG_INFO, "test1");
		logService.log(logServiceReference, LogService.LOG_INFO, "test2");
		logService.log(logServiceReference, LogService.LOG_INFO, "test3");
		latch.await(1, TimeUnit.SECONDS);
		ps.close();
		System.out.println("Count = " + count);
		assertEquals("Some number of message, >0 not in stream", 0, messageSet.size());
		ps.close();
	}

	public void XtestLogWithHistory() throws Exception {
		logService.log(logServiceReference, LogService.LOG_INFO, "test"); //$NON-NLS-1$ //$NON-NLS-2$
		System.out.println("In LogStreamTest.");
		PushStream<LogEntry> stream = logStreamProvider.createStream(LogStreamProvider.Options.HISTORY).filter(l -> "test".equals(l.getMessage()));
		Promise<Long> p = stream.count();
		stream.close();
		long count = p.getValue();
		assertEquals("Incorrect count for matching log entry", 1L, count, 0);
	}

	public void XtestMultipleStreams() {
	}

	//	public void testLogHistory1() throws BundleException {
	//		File config = OSGiTestsActivator.getContext().getDataFile(getName());
	//		Map<String, Object> configuration = new HashMap<String, Object>();
	//		configuration.put(Constants.FRAMEWORK_STORAGE, config.getAbsolutePath());
	//		configuration.put(EquinoxConfiguration.PROP_LOG_HISTORY_MAX, "10");
	//		Equinox equinox = new Equinox(configuration);
	//		equinox.start();
	//
	//		try {
	//			LogService testLog = equinox.getBundleContext().getService(equinox.getBundleContext().getServiceReference(LogService.class));
	//			LogReaderService testReader = equinox.getBundleContext().getService(equinox.getBundleContext().getServiceReference(LogReaderService.class));
	//			assertEquals("Expecting no logs.", 0, countLogEntries(testReader.getLog(), 0));
	//			// log 9 things
	//			for (int i = 0; i < 9; i++) {
	//				testLog.log(LogService.LOG_WARNING, String.valueOf(i));
	//			}
	//			assertEquals("Wrong number of logs.", 9, countLogEntries(testReader.getLog(), 0));
	//
	//			// log 9 more things
	//			for (int i = 9; i < 18; i++) {
	//				testLog.log(LogService.LOG_WARNING, String.valueOf(i));
	//			}
	//
	//			// should only be the last 10 logs (8 - 17)
	//			assertEquals("Wrong number of logs.", 10, countLogEntries(testReader.getLog(), 8));
	//		} finally {
	//			try {
	//				equinox.stop();
	//			} catch (BundleException e) {
	//				// ignore
	//			}
	//		}
	//	}
	//
	//	public void testLogHistory2() throws BundleException {
	//		File config = OSGiTestsActivator.getContext().getDataFile(getName());
	//		Map<String, Object> configuration = new HashMap<String, Object>();
	//		configuration.put(Constants.FRAMEWORK_STORAGE, config.getAbsolutePath());
	//		Equinox equinox = new Equinox(configuration);
	//		equinox.start();
	//
	//		try {
	//			LogService testLog = equinox.getBundleContext().getService(equinox.getBundleContext().getServiceReference(LogService.class));
	//			LogReaderService testReader = equinox.getBundleContext().getService(equinox.getBundleContext().getServiceReference(LogReaderService.class));
	//			assertEquals("Expecting no logs.", 0, countLogEntries(testReader.getLog(), 0));
	//			// log 9 things
	//			for (int i = 0; i < 9; i++) {
	//				testLog.log(LogService.LOG_WARNING, String.valueOf(i));
	//			}
	//			assertEquals("Wrong number of logs.", 0, countLogEntries(testReader.getLog(), 0));
	//		} finally {
	//			try {
	//				equinox.stop();
	//			} catch (BundleException e) {
	//				// ignore
	//			}
	//		}
	//	}
	//
}
