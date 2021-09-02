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
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.google.common.collect.Iterables;

import net.fabricmc.stitch.util.StitchUtil;

class DependencyGraph {
	private static class DependencyNode {
		public final Class<? extends DependencyProvider> type;
		private DependencyProvider provider;
		private final Set<DependencyNode> dependencies = StitchUtil.newIdentityHashSet();
		private final Set<DependencyNode> remainingDependencies = StitchUtil.newIdentityHashSet();
		private final Set<DependencyNode> dependents = StitchUtil.newIdentityHashSet();
		private boolean isFixed;

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
			if (isFixed) throw new IllegalStateException("Node is fixed");

			this.provider = provider;
		}

		public DependencyProvider getProvider() {
			if (isEmpty()) throw new IllegalStateException("Cannot get provider of empty node");

			return provider;
		}

		boolean addDependency(DependencyNode node) {
			if (node.type == type) throw new IllegalArgumentException("Provider cannot depend on itself");
			if (isFixed) throw new IllegalStateException("Node is fixed");

			return dependencies.add(node);
		}

		Set<DependencyNode> getDependencies() {
			return Collections.unmodifiableSet(dependencies);
		}

		boolean addDependent(DependencyNode node) {
			if (node.type == type) throw new IllegalArgumentException("Provider cannot depend on itself");
			if (isFixed) throw new IllegalStateException("Node is fixed");

			return dependents.add(node);
		}

		Set<DependencyNode> getDependents() {
			return Collections.unmodifiableSet(dependents);
		}

		void setFixed() {
			isFixed = true;
			remainingDependencies.addAll(dependencies);
		}

		Set<DependencyNode> flagComplete() {
			return dependents.stream().filter(dependent -> dependent.dependencyComplete(this)).collect(Collectors.toSet());
		}

		private boolean dependencyComplete(DependencyNode node) {
			remainingDependencies.remove(node);
			return remainingDependencies.isEmpty();
		}

		@Override
		public String toString() {
			return isEmpty() ? '(' + type.getName() + ')' : '[' + type.getName() + ']';
		}
	}
	private final Queue<DependencyNode> currentActive = new ArrayDeque<>();

	public DependencyGraph(List<DependencyProvider> dependencies) {
		Map<Class<? extends DependencyProvider>, DependencyNode> dependenciesGraph = new IdentityHashMap<>();
		List<DependencyNode> roots = new ArrayList<>();

		for (DependencyProvider dependency : dependencies) {
			Class<? extends DependencyProvider> type = dependency.getClass();

			DependencyNode existing = dependenciesGraph.get(type);
			if (existing != null && !existing.isEmpty()) {
				throw new IllegalArgumentException("Duplicate dependency types of " + type);
			}

			Set<Class<? extends DependencyProvider>> before = dependency.getDependencies();
			Set<Class<? extends DependencyProvider>> after = dependency.getDependents();

			if (before.isEmpty() && after.isEmpty()) {
				if (existing != null) {
					existing.fill(dependency);
				} else {
					dependenciesGraph.put(type, existing = DependencyNode.of(dependency));
					roots.add(existing);
				}
			} else {
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
						roots.add(existingBefore); //Without any other information otherwise, the dependency is a root
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
			node.setFixed();

			Set<DependencyNode> checkedDependents = StitchUtil.newIdentityHashSet();
			Queue<DependencyNode> depenentList = new ArrayDeque<>(node.getDependents());

			DependencyNode dependent;
			while ((dependent = depenentList.poll()) != null) {
				Set<DependencyNode> depenents = dependent.getDependents();

				if (depenents.contains(node)) {
					throw new IllegalStateException("Circular dependencies on " + node.getClass());
				}

				for (DependencyNode child : depenents) {
					if (!checkedDependents.contains(child)) {
						checkedDependents.add(child);
						depenentList.add(child);
					}
				}
			}
		}

		if (roots.isEmpty()) {
			throw new IllegalStateException("All dependencies have dependencies!");
		}
		currentActive.addAll(roots);
	}

	/** Whether there are any more {@link DependencyProvider} currently capable of being processed */
	public boolean hasAvailable() {
		return !currentActive.isEmpty();
	}

	/** {@link Iterable} form of the graph designed to loop over all the {@link DependencyProvider}s in it */
	public Iterable<DependencyProvider> asIterable() {
		return Iterables.transform(asIterableNodes(), DependencyNode::getProvider);
	}

	private Iterable<DependencyNode> asIterableNodes() {
		return () -> new Iterator<DependencyNode>() {
			private DependencyNode last;

			@Override
			public boolean hasNext() {
				if (last != null) {
					currentActive.addAll(last.flagComplete());

					if (currentActive.isEmpty() && !last.getDependents().isEmpty()) {
						throw new IllegalStateException("All remaining dependencies have dependencies!");
					}

					last = null;
				}

				return hasAvailable();
			}

			@Override
			public DependencyNode next() {
				if (!hasNext()) {
					throw new NoSuchElementException("No more providers");
				}

				assert currentActive.stream().map(DependencyNode::getDependencies).allMatch(Set::isEmpty);
				return last = currentActive.poll();
			}
		};
	}

	/**
	 * The graph formed as chained {@link CompletableFuture}s which have automatically started
	 *
	 * @param task The task each node in the graph should have applied asynchronously
	 *
	 * @return Each node as a future, chained together according to their respective dependencies
	 */
	public Collection<CompletableFuture<Void>> asFutures(Consumer<DependencyProvider> task) {
		CountDownLatch starter = new CountDownLatch(1);
		CompletableFuture<Void> start = CompletableFuture.runAsync(() -> {
			while (true) {
				try {
					starter.await();
					break; //Time to start
				} catch (InterruptedException e) {
					//We've still (probably) not started
				}
			}
		});

		Map<Class<? extends DependencyProvider>, CompletableFuture<Void>> tasks = new IdentityHashMap<>();

		for (DependencyNode dependency : asIterableNodes()) {
			CompletableFuture<?> parent;
			switch (dependency.getDependencies().size()) {
			case 0:
				parent = start;
				break;

			case 1:
				parent = fetchTask(tasks, Iterables.getOnlyElement(dependency.getDependencies()).type);
				break;

			default:
				parent = CompletableFuture.allOf(dependency.getDependencies().stream().map(dep -> fetchTask(tasks, dep.type)).toArray(CompletableFuture[]::new));
				break;
			}

			tasks.put(dependency.type, parent.thenRunAsync(() -> task.accept(dependency.getProvider())));
		}

		starter.countDown(); //Ready to go now
		return tasks.values();
	}

	private static <K> CompletableFuture<?> fetchTask(Map<K, CompletableFuture<Void>> map, K key) {
		return Objects.requireNonNull(map.get(key), "Unable to find task to depend on for " + key);
	}
}