/*******************************************************************************
 * Copyright (c) 2012, 2015 Cognos Incorporated, IBM Corporation and others
 * All rights reserved. This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License v1.0 which
 * accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.eclipse.equinox.log.test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import junit.framework.TestCase;
import org.eclipse.equinox.log.*;
import org.eclipse.osgi.tests.OSGiTestsActivator;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceReference;
import org.osgi.service.log.*;
import org.osgi.service.log.Logger;
import org.osgi.service.log.admin.LoggerAdmin;
import org.osgi.service.log.admin.LoggerContext;

public class ExtendedLogReaderServiceTest extends TestCase {

	private ExtendedLogService log;
	private ServiceReference logReference;
	private ExtendedLogReaderService reader;
	private ServiceReference readerReference;
	private ServiceReference<LoggerAdmin> loggerAdminReference;
	private LoggerAdmin loggerAdmin;
	LoggerContext rootLoggerContext;
	Map<String, LogLevel> rootLogLevels;
	boolean called;

	public ExtendedLogReaderServiceTest(String name) {
		super(name);
	}

	protected void setUp() throws Exception {
		logReference = OSGiTestsActivator.getContext().getServiceReference(ExtendedLogService.class.getName());
		readerReference = OSGiTestsActivator.getContext().getServiceReference(ExtendedLogReaderService.class.getName());
		loggerAdminReference = OSGiTestsActivator.getContext().getServiceReference(LoggerAdmin.class);

		log = (ExtendedLogService) OSGiTestsActivator.getContext().getService(logReference);
		reader = (ExtendedLogReaderService) OSGiTestsActivator.getContext().getService(readerReference);
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
		OSGiTestsActivator.getContext().ungetService(logReference);
		OSGiTestsActivator.getContext().ungetService(readerReference);
	}

	public void testaddFilteredListener() throws Exception {
		TestListener listener = new TestListener();
		reader.addLogListener(listener, new LogFilter() {
			public boolean isLoggable(Bundle b, String loggerName, int logLevel) {
				return true;
			}
		});
		synchronized (listener) {
			log.log(LogService.LOG_INFO, "info");
			listener.waitForLogEntry();
		}
		assertTrue(listener.getEntryX().getLevel() == LogService.LOG_INFO);
	}

	public void testaddNullFilterr() throws Exception {
		TestListener listener = new TestListener();

		try {
			reader.addLogListener(listener, null);
		} catch (IllegalArgumentException e) {
			return;
		}
		fail();
	}

	public void testaddFilteredListenerTwice() throws Exception {
		TestListener listener = new TestListener();
		reader.addLogListener(listener, new LogFilter() {
			public boolean isLoggable(Bundle b, String loggerName, int logLevel) {
				return false;
			}
		});

		if (log.isLoggable(LogService.LOG_INFO))
			fail();

		reader.addLogListener(listener, new LogFilter() {
			public boolean isLoggable(Bundle b, String loggerName, int logLevel) {
				return true;
			}
		});
		synchronized (listener) {
			log.log(LogService.LOG_INFO, "info");
			listener.waitForLogEntry();
		}
		assertTrue(listener.getEntryX().getLevel() == LogService.LOG_INFO);
	}

	public void testaddNullListener() throws Exception {
		try {
			reader.addLogListener(null);
		} catch (IllegalArgumentException t) {
			return;
		}
		fail();
	}

	public void testBadFilter() throws Exception {
		TestListener listener = new TestListener();
		reader.addLogListener(listener, new LogFilter() {
			public boolean isLoggable(Bundle b, String loggerName, int logLevel) {
				throw new RuntimeException("Expected error for testBadFilter.");
			}
		});

		if (log.isLoggable(LogService.LOG_INFO))
			fail();
	}

	public void testSynchronousLogListener() throws Exception {
		final Thread loggerThread = Thread.currentThread();
		called = false;
		LogListener listener = new SynchronousLogListener() {
			public void logged(LogEntry entry) {
				assertTrue(Thread.currentThread() == loggerThread);
				called = true;
			}
		};
		reader.addLogListener(listener);
		log.log(LogService.LOG_INFO, "info");
		assertTrue(called);
	}

	public void testExtendedLogEntry() throws Exception {
		TestListener listener = new TestListener();
		reader.addLogListener(listener);
		long timeBeforeLog = System.currentTimeMillis();
		String threadName = Thread.currentThread().getName();
		long threadId = getCurrentThreadId();
		synchronized (listener) {
			log.getLogger("test").log(logReference, LogService.LOG_INFO, "info", new Throwable("test"));
			listener.waitForLogEntry();
		}
		ExtendedLogEntry entry = listener.getEntryX();
		long sequenceNumberBefore = entry.getSequenceNumber();

		synchronized (listener) {
			log.getLogger("test").log(logReference, LogService.LOG_INFO, "info", new Throwable("test"));
			listener.waitForLogEntry();
		}
		entry = listener.getEntryX();
		assertTrue(entry.getBundle() == OSGiTestsActivator.getContext().getBundle());
		assertTrue(entry.getMessage().equals("info"));
		assertTrue(entry.getException().getMessage().equals("test"));
		assertTrue(entry.getServiceReference() == logReference);
		assertTrue(entry.getTime() >= timeBeforeLog);
		assertTrue(entry.getLevel() == LogService.LOG_INFO);

		assertTrue(entry.getLoggerName().equals("test"));
		assertTrue(entry.getThreadName().equals(threadName));
		if (threadId >= 0)
			assertTrue(entry.getThreadId() == threadId);
		assertTrue(entry.getContext() == logReference);
		assertTrue(entry.getSequenceNumber() > sequenceNumberBefore);
	}

	private long getCurrentThreadId() {
		Thread current = Thread.currentThread();
		try {
			Method getId = Thread.class.getMethod("getId");
			Long id = (Long) getId.invoke(current);
			return id.longValue();
		} catch (Throwable t) {
			return -1;
		}
	}
}
