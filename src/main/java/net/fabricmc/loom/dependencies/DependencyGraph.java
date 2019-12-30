/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.dependencies;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import net.fabricmc.stitch.util.StitchUtil;

class DependencyGraph {
	static class DependencyNode {
		public final Class<? extends DependencyProvider> type;
		private DependencyProvider provider;
		private final Set<DependencyNode> dependencies = StitchUtil.newIdentityHashSet();
		private final Set<DependencyNode> dependents = StitchUtil.newIdentityHashSet();

		static DependencyNode emptyOf(Class<? extends DependencyProvider> type) {
			return new DependencyNode(type, null);
		}

		static DependencyNode of(DependencyProvider provider) {
			return new DependencyNode(provider.getClass(), provider);
		}

		private <T extends DependencyProvider> DependencyNode(Class<? extends T> type, T provider) {
			this.type = type;
			this.provider = provider;
		}

		public boolean isEmpty() {
			return provider == null;
		}

		void fill(DependencyProvider provider) {
			if (!isEmpty()) throw new IllegalStateException("Duplicate providers given for " + type);

			this.provider = provider;
		}

		public DependencyProvider getProvider() {
			if (isEmpty()) throw new IllegalStateException("Cannot get provider of empty node");

			return provider;
		}

		boolean addDependency(DependencyNode node) {
			if (node.getClass().equals(type)) throw new IllegalArgumentException("Provider cannot depend on itself");

			return dependencies.add(node);
		}

		Set<DependencyNode> getDependencies() {
			return Collections.unmodifiableSet(dependencies);
		}

		boolean addDependent(DependencyNode node) {
			if (node.getClass().equals(type)) throw new IllegalArgumentException("Provider cannot depend on itself");

			return dependents.add(node);
		}

		Set<DependencyNode> getDependents() {
			return Collections.unmodifiableSet(dependents);
		}

		Set<DependencyNode> flagComplete() {
			return dependents.stream().filter(dependent -> dependent.dependencyComplete(this)).collect(Collectors.toSet());
		}

		private boolean dependencyComplete(DependencyNode node) {
			dependencies.remove(node);
			return dependencies.isEmpty();
		}
	}

	private final Queue<DependencyNode> currentActive = new ArrayDeque<>();

	public DependencyGraph(List<DependencyProvider> dependencies) {
		Set<Class<? extends DependencyProvider>> seenDependencies = StitchUtil.newIdentityHashSet();
		Map<Class<? extends DependencyProvider>, DependencyNode> dependenciesGraph = new IdentityHashMap<>();
		List<DependencyNode> roots = new ArrayList<>();

		for (DependencyProvider dependency : dependencies) {
			Class<? extends DependencyProvider> type = dependency.getClass();
			if (!seenDependencies.add(type)) {
				throw new IllegalArgumentException("Duplicate dependency types of " + type);
			}

			Set<Class<? extends DependencyProvider>> before = dependency.getDependencies();
			Set<Class<? extends DependencyProvider>> after = dependency.getDependents();

			if (before.isEmpty() && after.isEmpty()) {
				DependencyNode existing = dependenciesGraph.get(type);

				if (existing != null) {
					existing.fill(dependency);
				} else {
					dependenciesGraph.put(type, existing = DependencyNode.of(dependency));
					roots.add(existing);
				}
			} else {
				DependencyNode existing = dependenciesGraph.get(type);

				if (existing != null) {
					//If we have dependencies of our own we aren't a root
					if (!before.isEmpty()) roots.remove(existing);

					existing.fill(dependency);
				} else {
					dependenciesGraph.put(type, existing = DependencyNode.of(dependency));

					//If we don't have dependencies of our own we are a root
					if (before.isEmpty()) roots.add(existing);
				}

				assert existing != null;
				for (Class<? extends DependencyProvider> beforeType : before) {
					DependencyNode existingBefore = dependenciesGraph.get(beforeType);

					if (existingBefore == null) {
						dependenciesGraph.put(beforeType, existingBefore = DependencyNode.emptyOf(beforeType));
					}

					existingBefore.addDependent(existing);
					existing.addDependency(existingBefore);
				}

				for (Class<? extends DependencyProvider> afterType : after) {
					DependencyNode existingAfter = dependenciesGraph.get(afterType);

					if (existingAfter == null) {
						dependenciesGraph.put(afterType, existingAfter = DependencyNode.emptyOf(afterType));
					} else {
						roots.remove(existingAfter);
					}

					existing.addDependent(existingAfter);
					existingAfter.addDependency(existing);
				}
			}
		}

		for (DependencyNode node : dependenciesGraph.values()) {
			if (node.isEmpty()) {
				throw new IllegalStateException("Missing dependency type: " + node.type);
			}

			Set<DependencyNode> allDependents = StitchUtil.newIdentityHashSet();
			allDependents.addAll(node.getDependents());

			Queue<DependencyNode> depenentList = new ArrayDeque<>(allDependents);
			DependencyNode dependent;
			while ((dependent = depenentList.poll()) != null) {
				Set<DependencyNode> depenents = dependent.getDependents();
				depenents.removeIf(depenentList::contains);

				if (depenents.contains(node)) {
					throw new IllegalStateException("Circular dependencies on " + node.getClass());
				}

				allDependents.addAll(depenents);
				depenentList.addAll(depenents);
			}
		}

		if (roots.isEmpty()) {
			throw new IllegalStateException("All dependencies have dependencies!");
		}
		currentActive.addAll(roots);
	}

	public DependencyProvider nextAvailable() {
		if (!hasAvailable()) {
			throw new NoSuchElementException("No more providers");
		}

		assert currentActive.stream().allMatch(node -> node.getDependencies().isEmpty());
		return currentActive.poll().getProvider();
	}

	public void markComplete(DependencyProvider provider) {
		for (Iterator<DependencyNode> it = currentActive.iterator(); it.hasNext();) {
			DependencyNode node = it.next();

			if (node.getProvider() == provider) {
				it.remove();
				currentActive.addAll(node.flagComplete());

				if (currentActive.isEmpty() && !node.getDependents().isEmpty()) {
					throw new IllegalStateException("All remaining dependencies have dependencies!");
				}
				break;
			}
		}
	}

	public boolean hasAvailable() {
		return !currentActive.isEmpty();
	}

	public Iterable<DependencyProvider> asIterable() {
		return () -> new Iterator<DependencyProvider>() {
			@Override
			public boolean hasNext() {
				return hasAvailable();
			}

			@Override
			public DependencyProvider next() {
				return nextAvailable();
			}
		};
	}
}