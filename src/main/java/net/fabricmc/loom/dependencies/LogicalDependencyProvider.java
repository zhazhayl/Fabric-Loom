/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.dependencies;

import java.util.Set;
import java.util.function.Consumer;

import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradleExtension;

/**
 * A {@link DependencyProvider} which waits for and runs after other {@link DependencyProvider}s
 *
 * @author Chocohead
 */
public abstract class LogicalDependencyProvider extends DependencyProvider {
	@Override
	public abstract Set<Class<? extends DependencyProvider>> getDependencies();

	/** Perform whatever action this needs once the dependencies have run and are all satisfied */
	public abstract void provide(Project project, LoomGradleExtension extension, Consumer<Runnable> postPopulationScheduler) throws Exception;
}