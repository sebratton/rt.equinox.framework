/*******************************************************************************
 * Copyright (c) 2012 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.container;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.osgi.framework.*;
import org.osgi.framework.hooks.bundle.CollisionHook;
import org.osgi.framework.hooks.resolver.ResolverHookFactory;
import org.osgi.framework.namespace.HostNamespace;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.service.resolver.ResolutionException;
import org.osgi.service.resolver.Resolver;

/**
 * A container for installing, updating, uninstalling and resolve modules.
 *
 */
public class ModuleContainer {

	/**
	 * Used by install operations to establish a write lock on an install location
	 */
	private final LockSet<String> locationLocks = new LockSet<String>(false);

	/**
	 * Used by install and update operations to establish a write lock for a name
	 */
	private final LockSet<String> nameLocks = new LockSet<String>(false);

	/**
	 * Monitors read and write access to the moduleDataBase
	 */
	private final UpgradeableReadWriteLock monitor = new UpgradeableReadWriteLock();

	/**
	 * An implementation of FrameworkWiring for this container
	 */
	private final FrameworkWiring frameworkWiring = new ModuleFrameworkWiring();

	/**
	 * The module database for this container.  All access to this database MUST
	 * be guarded by the monitor lock
	 */
	/* @GuardedBy("monitor") */
	ModuleDataBase moduleDataBase;

	/**
	 * Hook used to determine if a bundle being installed or updated will cause a collision
	 */
	private final CollisionHook bundleCollisionHook;

	/**
	 * The module resolver which implements the ResolverContext and handles calling the 
	 * resolver service.
	 */
	private final ModuleResolver moduleResolver;

	public ModuleContainer(CollisionHook bundleCollisionHook, ResolverHookFactory resolverHookFactory, Resolver resolver) {
		this.bundleCollisionHook = bundleCollisionHook;
		this.moduleResolver = new ModuleResolver(resolverHookFactory, resolver);
	}

	public void setModuleDataBase(ModuleDataBase moduleDataBase) {
		monitor.lockWrite();
		try {
			if (this.moduleDataBase != null)
				throw new IllegalStateException("Module Database is already set."); //$NON-NLS-1$
			this.moduleDataBase = moduleDataBase;
			this.moduleDataBase.setContainer(this);
		} finally {
			monitor.unlockWrite();
		}
	}

	/**
	 * Returns the list of currently installed modules sorted by module id.
	 * @return the list of currently installed modules sorted by module id.
	 */
	public List<Module> getModules() {
		List<Module> result;
		monitor.lockRead(false);
		try {
			result = new ArrayList<Module>(moduleDataBase.getModules());
		} finally {
			monitor.unlockRead(false);
		}
		Collections.sort(result, new Comparator<Module>() {
			@Override
			public int compare(Module m1, Module m2) {
				return m1.getRevisions().getId().compareTo(m2.getRevisions().getId());
			}
		});
		return result;
	}

	/**
	 * Returns the module installed with the specified id, or null if no 
	 * such module is installed.
	 * @param id the id of the module
	 * @return the module with the specified id, or null of no such module is installed.
	 */
	public Module getModule(long id) {
		monitor.lockRead(false);
		try {
			return moduleDataBase.getModule(id);
		} finally {
			monitor.unlockRead(false);
		}
	}

	/**
	 * Returns the module installed with the specified location, or null if no 
	 * such module is installed.
	 * @param location the location of the module
	 * @return the module with the specified location, or null of no such module is installed.
	 */
	public Module getModule(String location) {
		monitor.lockRead(false);
		try {
			return moduleDataBase.getModule(location);
		} finally {
			monitor.unlockRead(false);
		}
	}

	public Module install(BundleContext origin, String location, ModuleRevisionBuilder builder) throws BundleException {
		String name = builder.getSymbolicName();
		boolean locationLocked = false;
		boolean nameLocked = false;
		try {
			Module existingLocation = null;
			Collection<Bundle> collisionCandidates = Collections.emptyList();
			monitor.lockRead(false);
			try {
				existingLocation = moduleDataBase.getModule(location);
				if (existingLocation == null) {
					// Attempt to lock the location and name
					try {
						locationLocked = locationLocks.tryLock(location, 5, TimeUnit.SECONDS);
						nameLocked = name != null && nameLocks.tryLock(name, 5, TimeUnit.SECONDS);
						if (!locationLocked || !nameLocked) {
							throw new BundleException("Failed to obtain id locks for installation.", BundleException.STATECHANGE_ERROR);
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new BundleException("Failed to obtain id locks for installation.", BundleException.STATECHANGE_ERROR, e);
					}
					// Collect existing bundles with the same name and version as the bundle we want to install
					// This is to perform the collision check below
					Collection<ModuleRevision> existingRevisionNames = moduleDataBase.getRevisions(name, builder.getVersion());
					if (!existingRevisionNames.isEmpty()) {
						collisionCandidates = new ArrayList<Bundle>(1);
						for (ModuleRevision equinoxRevision : existingRevisionNames) {
							Bundle b = equinoxRevision.getBundle();
							// need to prevent duplicates here; this is in case a revisions object contains multiple revision objects.
							if (b != null && !collisionCandidates.contains(b))
								collisionCandidates.add(b);
						}
					}
				}
			} finally {
				monitor.unlockRead(false);
			}
			// Check that the existing location is visible from the origin bundle
			if (existingLocation != null) {
				if (origin != null) {
					if (origin.getBundle(existingLocation.getRevisions().getId()) == null) {
						Bundle b = existingLocation.getBundle();
						throw new BundleException("Bundle \"" + b.getSymbolicName() + "\" version \"" + b.getVersion() + "\" is already installed at location: " + location, BundleException.REJECTED_BY_HOOK);
					}
				}
				return existingLocation;
			}
			// Check that the bundle does not collide with other bundles with the same name and version
			// This is from the perspective of the origin bundle
			if (origin != null && !collisionCandidates.isEmpty()) {
				bundleCollisionHook.filterCollisions(CollisionHook.INSTALLING, origin.getBundle(), collisionCandidates);
				if (!collisionCandidates.isEmpty()) {
					throw new BundleException("A bundle is already installed with name \"" + name + "\" and version \"" + builder.getVersion(), BundleException.DUPLICATE_BUNDLE_ERROR);
				}
			}
			Module result;
			monitor.lockWrite();
			try {
				result = moduleDataBase.install(location, builder);
				// downgrade to read lock to fire installed events
				monitor.lockRead(false);
			} finally {
				monitor.unlockWrite();
			}
			try {
				// TODO fire installed event
			} finally {
				monitor.unlockRead(false);
			}
			return result;
		} finally {
			if (locationLocked)
				locationLocks.unlock(location);
			if (nameLocked)
				nameLocks.unlock(name);
		}
	}

	public void update(Module module, ModuleRevisionBuilder builder) throws BundleException {
		String name = builder.getSymbolicName();
		boolean nameLocked = false;
		try {
			Collection<Bundle> collisionCandidates = Collections.emptyList();
			monitor.lockRead(false);
			try {
				// Attempt to lock the name
				try {
					nameLocked = name != null && nameLocks.tryLock(name, 5, TimeUnit.SECONDS);
					if (!nameLocked) {
						throw new BundleException("Failed to obtain id locks for installation.", BundleException.STATECHANGE_ERROR);
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new BundleException("Failed to obtain id locks for installation.", BundleException.STATECHANGE_ERROR, e);
				}
				// Collect existing bundles with the same name and version as the bundle we want to install
				// This is to perform the collision check below
				Collection<ModuleRevision> existingRevisionNames = moduleDataBase.getRevisions(name, builder.getVersion());
				if (!existingRevisionNames.isEmpty()) {
					collisionCandidates = new ArrayList<Bundle>(1);
					for (ModuleRevision equinoxRevision : existingRevisionNames) {
						Bundle b = equinoxRevision.getBundle();
						// need to prevent duplicates here; this is in case a revisions object contains multiple revision objects.
						if (b != null && !collisionCandidates.contains(b))
							collisionCandidates.add(b);
					}
				}

			} finally {
				monitor.unlockRead(false);
			}

			// Check that the bundle does not collide with other bundles with the same name and version
			// This is from the perspective of the origin bundle being updated
			Bundle origin = module.getBundle();
			if (origin != null && !collisionCandidates.isEmpty()) {
				bundleCollisionHook.filterCollisions(CollisionHook.UPDATING, origin, collisionCandidates);
				if (!collisionCandidates.isEmpty()) {
					throw new BundleException("A bundle is already installed with name \"" + name + "\" and version \"" + builder.getVersion(), BundleException.DUPLICATE_BUNDLE_ERROR);
				}
			}
			monitor.lockWrite();
			try {
				moduleDataBase.update(module, builder);
			} finally {
				monitor.unlockWrite();
			}
		} finally {
			if (nameLocked)
				nameLocks.unlock(name);
		}
	}

	public void uninstall(Module module) {
		monitor.lockWrite();
		try {
			moduleDataBase.uninstall(module);
		} finally {
			monitor.unlockWrite();
		}
		// TODO fire uninstalled event
		// no need to hold the read lock because the module has been complete removed
		// from the database at this point. (except for removal pending wirings).
	}

	ModuleWiring getWiring(ModuleRevision revision) {
		monitor.lockRead(false);
		try {
			return moduleDataBase.getWiring(revision);
		} finally {
			monitor.unlockRead(false);
		}
	}

	public FrameworkWiring getFrameworkWiring() {
		return frameworkWiring;
	}

	public void resolve(Collection<Module> triggers) throws ResolutionException {
		resolve(triggers, true);
	}

	private void resolve(Collection<Module> triggers, boolean reserveUpgrade) throws ResolutionException {
		monitor.lockRead(reserveUpgrade);
		try {
			Map<ModuleRevision, ModuleWiring> wiringCopy = moduleDataBase.getWiringsCopy();
			Collection<ModuleRevision> triggerRevisions = new ArrayList<ModuleRevision>(triggers.size());
			for (Module module : triggers) {
				ModuleRevision current = module.getCurrentRevision();
				if (current != null)
					triggerRevisions.add(current);
			}
			Map<ModuleRevision, ModuleWiring> deltaWiring = moduleResolver.resolveDelta(triggerRevisions, wiringCopy, moduleDataBase);
			if (deltaWiring.isEmpty())
				return; // nothing to do
			Collection<Module> newlyResolved = new ArrayList<Module>();
			// now attempt to apply the delta
			monitor.lockWrite();
			try {
				for (Map.Entry<ModuleRevision, ModuleWiring> deltaEntry : deltaWiring.entrySet()) {
					ModuleWiring current = wiringCopy.get(deltaEntry.getKey());
					if (current != null) {
						// only need to update the provided and required wires for currently resolved
						current.setProvidedWires(deltaEntry.getValue().getProvidedModuleWires(null));
						current.setRequiredWires(deltaEntry.getValue().getRequiredModuleWires(null));
						deltaEntry.setValue(current); // set the real wiring into the delta
					} else {
						newlyResolved.add(deltaEntry.getValue().getRevision().getRevisions().getModule());
					}
				}
				moduleDataBase.mergeWiring(deltaWiring);
			} finally {
				monitor.unlockWrite();
			}
			// TODO send out resolved events
		} finally {
			monitor.unlockRead(reserveUpgrade);
		}
	}

	private Collection<Module> unresolve(Collection<Module> initial) {
		if (monitor.getReadHoldCount() == 0) // sanity check
			throw new IllegalStateException("Must hold read lock and upgrade request"); //$NON-NLS-1$

		Map<ModuleRevision, ModuleWiring> wiringCopy = moduleDataBase.getWiringsCopy();
		Collection<Module> refreshTriggers = getRefreshClosure(initial, wiringCopy);
		Collection<ModuleRevision> toRemoveRevisions = new ArrayList<ModuleRevision>();
		Collection<ModuleWiring> toRemoveWirings = new ArrayList<ModuleWiring>();
		Map<ModuleWiring, Collection<ModuleWire>> toRemoveWireLists = new HashMap<ModuleWiring, Collection<ModuleWire>>();
		for (Module module : refreshTriggers) {
			boolean first = true;
			for (ModuleRevision revision : module.getRevisions().getModuleRevisions()) {
				ModuleWiring removedWiring = wiringCopy.remove(revision);
				if (removedWiring != null) {
					toRemoveWirings.add(removedWiring);
					List<ModuleWire> removedWires = removedWiring.getRequiredModuleWires(null);
					for (ModuleWire wire : removedWires) {
						Collection<ModuleWire> providerWires = toRemoveWireLists.get(wire.getProviderWiring());
						if (providerWires == null) {
							providerWires = new ArrayList<ModuleWire>();
							toRemoveWireLists.put(wire.getProviderWiring(), providerWires);
						}
						providerWires.add(wire);
					}
				}
				if (!first || revision.getRevisions().isUninstalled()) {
					toRemoveRevisions.add(revision);
				}
				first = false;
			}
			// TODO grab module state change locks and stop modules
			// TODO remove any non-active modules from the refreshTriggers
		}

		monitor.lockWrite();
		try {
			// remove any wires from unresolved wirings that got removed
			for (Map.Entry<ModuleWiring, Collection<ModuleWire>> entry : toRemoveWireLists.entrySet()) {
				List<ModuleWire> provided = entry.getKey().getProvidedModuleWires(null);
				provided.removeAll(entry.getValue());
				entry.getKey().setProvidedWires(provided);
			}
			// remove any revisions that got removed as part of the refresh
			for (ModuleRevision removed : toRemoveRevisions) {
				removed.getRevisions().removeRevision(removed);
				moduleDataBase.removeCapabilities(removed);
			}
			// invalidate any removed wiring objects
			for (ModuleWiring moduleWiring : toRemoveWirings) {
				moduleWiring.invalidate();
			}
			moduleDataBase.setWiring(wiringCopy);
		} finally {
			monitor.unlockWrite();
		}
		// TODO set unresolved status of modules
		// TODO fire unresolved events
		return refreshTriggers;
	}

	public void refresh(Collection<Module> initial) throws ResolutionException {
		Collection<Module> refreshTriggers;
		monitor.lockRead(true);
		try {
			refreshTriggers = unresolve(initial);
			resolve(refreshTriggers, false);
		} finally {
			monitor.unlockRead(true);
		}
		// TODO start the trigger modules

	}

	static Collection<Module> getRefreshClosure(Collection<Module> initial, Map<ModuleRevision, ModuleWiring> wiringCopy) {
		Set<Module> refreshClosure = new HashSet<Module>();
		for (Module module : initial)
			addDependents(module, wiringCopy, refreshClosure);
		return refreshClosure;
	}

	private static void addDependents(Module module, Map<ModuleRevision, ModuleWiring> wiringCopy, Set<Module> refreshClosure) {
		if (refreshClosure.contains(module))
			return;
		refreshClosure.add(module);
		List<ModuleRevision> revisions = module.getRevisions().getModuleRevisions();
		for (ModuleRevision revision : revisions) {
			ModuleWiring wiring = wiringCopy.get(revision);
			if (wiring == null)
				continue;
			List<ModuleWire> provided = wiring.getProvidedModuleWires(null);
			// add all requirers of the provided wires
			for (ModuleWire providedWire : provided) {
				addDependents(providedWire.getRequirer().getRevisions().getModule(), wiringCopy, refreshClosure);
			}
			// add all hosts of a fragment
			if (revision.getTypes() == BundleRevision.TYPE_FRAGMENT) {
				List<ModuleWire> hosts = wiring.getRequiredModuleWires(HostNamespace.HOST_NAMESPACE);
				for (ModuleWire hostWire : hosts) {
					addDependents(hostWire.getProvider().getRevisions().getModule(), wiringCopy, refreshClosure);
				}
			}
		}
	}

	static Collection<ModuleRevision> getDependencyClosure(ModuleRevision initial, Map<ModuleRevision, ModuleWiring> wiringCopy) {
		Set<ModuleRevision> dependencyClosure = new HashSet<ModuleRevision>();
		addDependents(initial, wiringCopy, dependencyClosure);
		return dependencyClosure;
	}

	private static void addDependents(ModuleRevision revision, Map<ModuleRevision, ModuleWiring> wiringCopy, Set<ModuleRevision> dependencyClosure) {
		if (dependencyClosure.contains(revision))
			return;
		dependencyClosure.add(revision);
		ModuleWiring wiring = wiringCopy.get(revision);
		if (wiring == null)
			return;
		List<ModuleWire> provided = wiring.getProvidedModuleWires(null);
		// add all requirers of the provided wires
		for (ModuleWire providedWire : provided) {
			addDependents(providedWire.getRequirer(), wiringCopy, dependencyClosure);
		}
		// add all hosts of a fragment
		if (revision.getTypes() == BundleRevision.TYPE_FRAGMENT) {
			List<ModuleWire> hosts = wiring.getRequiredModuleWires(HostNamespace.HOST_NAMESPACE);
			for (ModuleWire hostWire : hosts) {
				addDependents(hostWire.getProvider(), wiringCopy, dependencyClosure);
			}
		}
	}

	public class ModuleFrameworkWiring implements FrameworkWiring {

		@Override
		public Bundle getBundle() {
			monitor.lockRead(false);
			try {
				Module systemModule = moduleDataBase.getModule(Constants.SYSTEM_BUNDLE_LOCATION);
				return systemModule == null ? null : systemModule.getBundle();
			} finally {
				monitor.unlockRead(false);
			}
		}

		@Override
		public void refreshBundles(Collection<Bundle> bundles, FrameworkListener... listeners) {
			Collection<Module> modules = getModules(bundles);
			// TODO must happen in background
			try {
				refresh(modules);
			} catch (ResolutionException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			// TODO Must fire refresh event to listeners
		}

		@Override
		public boolean resolveBundles(Collection<Bundle> bundles) {
			Collection<Module> modules = getModules(bundles);
			try {
				resolve(modules);
			} catch (ResolutionException e) {
				return false;
			}
			for (Module module : modules) {
				if (getWiring(module.getCurrentRevision()) == null)
					return false;
			}
			return true;
		}

		@Override
		public Collection<Bundle> getRemovalPendingBundles() {
			monitor.lockRead(false);
			try {
				Collection<Bundle> removalPendingBundles = new HashSet<Bundle>();
				Collection<ModuleRevision> removalPending = moduleDataBase.getRemovalPending();
				for (ModuleRevision moduleRevision : removalPending) {
					removalPendingBundles.add(moduleRevision.getBundle());
				}
				return removalPendingBundles;
			} finally {
				monitor.unlockRead(false);
			}
		}

		@Override
		public Collection<Bundle> getDependencyClosure(Collection<Bundle> bundles) {
			Collection<Module> modules = getModules(bundles);
			monitor.lockRead(false);
			try {
				Collection<Module> closure = ModuleContainer.getRefreshClosure(modules, moduleDataBase.getWiringsCopy());
				Collection<Bundle> result = new ArrayList<Bundle>(closure.size());
				for (Module module : closure) {
					result.add(module.getBundle());
				}
				return result;
			} finally {
				monitor.unlockRead(false);
			}
		}

		private Collection<Module> getModules(final Collection<Bundle> bundles) {
			return AccessController.doPrivileged(new PrivilegedAction<Collection<Module>>() {
				@Override
				public Collection<Module> run() {
					Collection<Module> result = new ArrayList<Module>(bundles.size());
					for (Bundle bundle : bundles) {
						Module module = bundle.adapt(Module.class);
						if (module == null)
							throw new IllegalStateException("Could not adapt a bundle to a module."); //$NON-NLS-1$
						result.add(module);
					}
					return result;
				}
			});
		}
	}
}
