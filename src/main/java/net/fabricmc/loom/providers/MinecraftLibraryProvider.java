/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.providers;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.dependencies.DependencyProvider;
import net.fabricmc.loom.dependencies.LogicalDependencyProvider;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.GradleSupport;
import net.fabricmc.loom.util.MinecraftVersionInfo.Library;

public class MinecraftLibraryProvider extends LogicalDependencyProvider {
	final List<Library> natives = new ArrayList<>();
	private Set<File> libs = Collections.emptySet();

	@Override
	public Set<Class<? extends DependencyProvider>> getDependencies() {
		return Collections.singleton(MinecraftProvider.class);
	}

	@Override
	public void provide(Project project, LoomGradleExtension extension, Consumer<Runnable> postPopulationScheduler) throws Exception {
		MinecraftProvider minecraftProvider = getProvider(MinecraftProvider.class);
		boolean lwjgl2 = false;

		for (Library library : minecraftProvider.getLibraries()) {
			if (library.shouldUse()) {
				if (!library.isNative()) {
					addDependency(library.getArtifactName(), project, Constants.MINECRAFT_LIBRARIES);
					lwjgl2 |= library.name.startsWith("org.lwjgl.lwjgl:lwjgl:2.");
				} else {
					natives.add(library);
				}
			}
		}

		if (!extractNatives(project) && !lwjgl2) {
			for (Library library : natives) {
				addDependency(library.getArtifactName(), project, Constants.MINECRAFT_LIBRARIES);
			}
			natives.clear();
		}

		libs = project.getConfigurations().getByName(Constants.MINECRAFT_LIBRARIES).getFiles();
	}

	private static boolean extractNatives(Project project) {
		int major = GradleSupport.majorGradleVersion(project);
		return major > 5 || major == 5 && GradleSupport.minorGradleVersion(project) >= 6 && GradleSupport.patchGradleVersion(project) >= 3;
	}

	public Set<File> getLibraries() {
		return Collections.unmodifiableSet(libs);
	}

	public boolean extractNatives() {
		return !natives.isEmpty();
	}
}
