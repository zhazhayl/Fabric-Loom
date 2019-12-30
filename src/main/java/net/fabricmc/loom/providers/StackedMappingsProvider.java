/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers;

import java.util.function.Consumer;

import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.dependencies.PhysicalDependencyProvider;
import net.fabricmc.loom.util.Constants;

public class StackedMappingsProvider extends PhysicalDependencyProvider {
	private final MappingsProvider realProvider = new MappingsProvider();

	public StackedMappingsProvider() {
		getDependencyManager().addProvider(realProvider);
	}

	@Override
	public String getTargetConfig() {
		return Constants.MAPPINGS_RAW;
	}

	@Override
	protected boolean isRequired() {
		return false; //Mappings can be inferred from the (required) Minecraft provider
	}

	@Override
	protected boolean isUnique() {
		return false; //Multiple mappings can be defined then stacked together
	}

	@Override
	public void provide(DependencyInfo dependency, Project project, LoomGradleExtension extension, Consumer<Runnable> postPopulationScheduler) throws Exception {
		// TODO Auto-generated method stub

	}
}