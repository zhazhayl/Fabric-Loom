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

package net.fabricmc.loom.util;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.util.ModProcessor;
import net.fabricmc.loom.util.SourceRemapper;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.function.Consumer;

public class ModCompileRemapper {
	public static void remapDependencies(Project project, String mappingsPrefix, LoomGradleExtension extension, Configuration modCompile, Configuration modCompileRemapped, Configuration regularCompile, Consumer<Runnable> postPopulationScheduler) {
		Logger logger = project.getLogger();
		DependencyHandler dependencies = project.getDependencies();

		for (ArtifactInfo artifact : ArtifactInfo.resolve(modCompile, dependencies)) {
			String group = artifact.group;
			String name = artifact.name;
			String version = artifact.version;
			String classifier = artifact.classifier;

			File input = artifact.getFile();

			if (!artifact.isFabricMod()) {
				logger.lifecycle(":providing " + artifact.notation());
				dependencies.add(regularCompile.getName(), artifact.asNonTransitiveDependency());
				continue;
			}

			String remappedLog = group + ':' + name + ':' + version + classifier + " (" + mappingsPrefix + ")";
			String remappedNotation = "net.fabricmc.mapped:" + mappingsPrefix + '.' + group + '.' + name + ':' + version + classifier;
			String remappedFilename = mappingsPrefix + '.' + group + '.' + name + '-' + version + classifier.replace(':', '-');
			logger.lifecycle(":providing " + remappedLog);

			File modStore = extension.getRemappedModCache();

			File output = new File(modStore, remappedFilename + ".jar");
			if (!output.exists() || input.lastModified() <= 0 || input.lastModified() > output.lastModified()) {
				//If the output doesn't exist, or appears to be outdated compared to the input we'll remap it
				try {
					ModProcessor.processMod(input, output, project, modCompileRemapped);
				} catch (IOException e) {
					throw new RuntimeException("Failed to remap mod", e);
				}

				if (!output.exists()){
					throw new RuntimeException("Failed to remap mod");
				}

				output.setLastModified(input.lastModified());
			} else {
				logger.info(output.getName() + " is up to date with " + input.getName());
			}

			dependencies.add(modCompileRemapped.getName(), dependencies.module(remappedNotation));

			Optional<File> sources = artifact.getSources();
			if (sources.isPresent()) {
				postPopulationScheduler.accept(() -> {
					logger.lifecycle(":providing " + remappedLog + " sources");
					File remappedSources = new File(modStore, remappedFilename + "-sources.jar");

					if (!remappedSources.exists() || sources.get().lastModified() <= 0 || sources.get().lastModified() > remappedSources.lastModified()) {
						try {
							SourceRemapper.remapSources(project, sources.get(), remappedSources, true);

							//Set the remapped sources creation date to match the sources if we're likely succeeded in making it
							remappedSources.setLastModified(sources.get().lastModified());
						} catch (Exception e) {
							e.printStackTrace();
						}
					} else {
						logger.info(remappedSources.getName() + " is up to date with " + sources.get().getName());
					}
				});
			}
		}
	}
}
