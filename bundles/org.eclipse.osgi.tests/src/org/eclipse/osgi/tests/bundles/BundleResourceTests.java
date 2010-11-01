/*******************************************************************************
 * Copyright (c) 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.tests.bundles;

import java.util.Enumeration;
import junit.framework.Test;
import junit.framework.TestSuite;
import org.eclipse.core.tests.harness.CoreTest;
import org.eclipse.osgi.tests.OSGiTestsActivator;
import org.osgi.framework.*;

public class BundleResourceTests extends CoreTest {
	private BundleInstaller installer;

	protected void setUp() throws Exception {
		try {
			installer = new BundleInstaller(OSGiTestsActivator.TEST_FILES_ROOT + "resourcetests/bundles", OSGiTestsActivator.getContext()); //$NON-NLS-1$
		} catch (InvalidSyntaxException e) {
			fail("Failed to create bundle installer", e); //$NON-NLS-1$
		}
	}

	protected void tearDown() throws Exception {
		installer.shutdown();
	}

	public static Test suite() {
		return new TestSuite(BundleResourceTests.class);
	}

	public void testBug328795() throws BundleException {
		Bundle bundle = installer.installBundle("test"); //$NON-NLS-1$
		checkEntries(bundle, "notFound\\", 0); // this results in invalid syntax exception which is logged because of trailing escape
		checkEntries(bundle, "notFound\\\\", 0); // test escaped escape "notFound\"
		checkEntries(bundle, "notFound(", 0); // test unescaped trailing (
		checkEntries(bundle, "notFound\\(", 0); // test escaped trailing (
		checkEntries(bundle, "notFound)", 0); // test unescaped trailing )
		checkEntries(bundle, "notFound\\)", 0); // test escaped trailing )
		checkEntries(bundle, "notFound*", 0); // test trailing unescaped *
		checkEntries(bundle, "notFound\\*", 0); // test trailing escaped *
		checkEntries(bundle, "paren(.txt", 1); // test unescaped ( -> should find one
		checkEntries(bundle, "paren\\(.txt", 1); // test escaped ( -> should find one
		checkEntries(bundle, "paren\\\\(.txt", 0); // test escaped escape before unescaped ( -> should find none; looks for paren\(.txt file
		checkEntries(bundle, "paren).txt", 1); // test unescaped ) -> should find one
		checkEntries(bundle, "paren\\).txt", 1); // test escaped ) -> should find one
		checkEntries(bundle, "paren\\\\).txt", 0); // test escaped escape before unescaped ) -> should find none; looks for paren\).txt file
		checkEntries(bundle, "paren(", 1); // test unescaped trailing ( -> should find one
		checkEntries(bundle, "paren\\(", 1); // test escaped trailing ( -> should find one
		checkEntries(bundle, "paren\\\\(", 0); // test escaped escape before ( -> should find none; looks for paren\(
		checkEntries(bundle, "paren)", 1); // test unescaped trailing ( -> should find one
		checkEntries(bundle, "paren\\)", 1); // test escaped trailing ( -> should find one
		checkEntries(bundle, "paren\\\\)", 0); // test escaped escape before ) -> should find none; looks for paren\)
		checkEntries(bundle, "paren*", 4); // test trailing wild cards
		checkEntries(bundle, "paren*.txt", 2); // test middle wild cards
		checkEntries(bundle, "paren\\*", 0); // test escaped wild card -> should find none; looks for paren*
		checkEntries(bundle, "paren\\\\*", 0); // test escaped escape before wild card -> should find none; looks for paren\*
		checkEntries(bundle, "p*r*n*", 4); // test multiple wild cards
		checkEntries(bundle, "p*r*n*.txt", 2); // test multiple wild cards
		checkEntries(bundle, "*)*", 2);
		checkEntries(bundle, "*(*", 2);
		checkEntries(bundle, "*\\)*", 2);
		checkEntries(bundle, "*\\(*", 2);
	}

	private void checkEntries(Bundle bundle, String filePattern, int expectedNumber) {
		Enumeration entries = bundle.findEntries("folder", filePattern, false);
		if (expectedNumber == 0) {
			assertNull("Expected nothing here.", entries);
			return;
		}
		int i = 0;
		while (entries.hasMoreElements()) {
			entries.nextElement();
			i++;
		}
		assertEquals("Unexpected number of entries", expectedNumber, i);
	}
}
