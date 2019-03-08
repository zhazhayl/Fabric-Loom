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

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DependencyProvider;
import net.fabricmc.stitch.commands.CommandProposeFieldNames;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.TinyUtils;

import org.apache.commons.io.FilenameUtils;
import org.gradle.api.Project;

import java.io.File;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiFunction;
import java.util.function.Consumer;

//TODO fix local mappings
//TODO possibly use maven for mappings, can fix above at the same time
public class MappingsProvider extends DependencyProvider {
	public MinecraftMappedProvider mappedProvider;

	public String mappingsName;
	public String minecraftVersion;
	public String mappingsVersion;

	private File MAPPINGS_DIR;
	private File MAPPINGS_TINY_BASE;
	public File MAPPINGS_TINY;
	private File parameterNames;
	public BiFunction<String, String, IMappingProvider> mcRemappingFactory;

	public File MAPPINGS_MIXIN_EXPORT;

	@Override
	public void provide(DependencyInfo dependency, Project project, LoomGradleExtension extension, Consumer<Runnable> postPopulationScheduler) throws Exception {
		MinecraftProvider minecraftProvider = getDependencyManager().getProvider(MinecraftProvider.class);

		project.getLogger().lifecycle(":setting up mappings (" + dependency.getDependency().getName() + " " + dependency.getResolvedVersion() + ")");

		String version = dependency.getResolvedVersion();
		File mappingsJar = dependency.resolveFile().orElseThrow(() -> new RuntimeException("Could not find dependency " + dependency));

		this.mappingsName = dependency.getDependency().getName();
		char verSep = version.contains("-") ? '-' : '.';

		this.minecraftVersion = version.substring(0, version.lastIndexOf(verSep));
		this.mappingsVersion = version.substring(version.lastIndexOf(verSep) + 1);

		initFiles(project);

		if (!MAPPINGS_DIR.exists()) {
			MAPPINGS_DIR.mkdir();
		}

		if (!MAPPINGS_TINY_BASE.exists() || !MAPPINGS_TINY.exists()) {
			if (!MAPPINGS_TINY_BASE.exists()) {
				switch (FilenameUtils.getExtension(mappingsJar.getName())) {
				case "zip": //Directly downloaded the enigma file (:enigma@zip)
					if (parameterNames.exists()) parameterNames.delete();
					//Convert enigma to tiny + extra out parameter names
					break;

				case "gz": //Directly downloaded the tiny file (:tiny@gz)
					project.getLogger().lifecycle(":extracting " + mappingsJar.getName());
					try (FileSystem fileSystem = FileSystems.newFileSystem(mappingsJar.toPath(), null)) {
						Path fileToExtract = fileSystem.getPath(dependency.getDependency().getName() + '-' + dependency.getResolvedVersion() + "-tiny");
						Files.copy(fileToExtract, MAPPINGS_TINY_BASE.toPath());
					}
					break;

				case "jar": //Downloaded a jar containing the tiny jar
					project.getLogger().lifecycle(":extracting " + mappingsJar.getName());
					try (FileSystem fileSystem = FileSystems.newFileSystem(mappingsJar.toPath(), null)) {
						Path fileToExtract = fileSystem.getPath("mappings/mappings.tiny");
						Files.copy(fileToExtract, MAPPINGS_TINY_BASE.toPath());
					}
					break;

				default: //Not sure what we've ended up with, but it's not what we want/expect
					throw new IllegalStateException("Unexpected mappings base type: " + FilenameUtils.getExtension(mappingsJar.getName()) + "(from " + mappingsJar.getName() + ')');
				}
			}

			if (MAPPINGS_TINY.exists()) {
				MAPPINGS_TINY.delete();
			}

			project.getLogger().lifecycle(":populating field names");
			new CommandProposeFieldNames().run(new String[] {
					minecraftProvider.MINECRAFT_MERGED_JAR.getAbsolutePath(),
					MAPPINGS_TINY_BASE.getAbsolutePath(),
					MAPPINGS_TINY.getAbsolutePath()
			});

			if (parameterNames.exists()) {
				//Merge the tiny mappings with parameter names
			} else {
				mcRemappingFactory = (fromM, toM) -> TinyUtils.createTinyMappingProvider(MAPPINGS_TINY.toPath(), fromM, toM);
			}
		}

		mappedProvider = new MinecraftMappedProvider();
		mappedProvider.initFiles(project, minecraftProvider, this);
		mappedProvider.provide(dependency, project, extension, postPopulationScheduler);
	}

	public void initFiles(Project project) {
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		MAPPINGS_DIR = new File(extension.getUserCache(), "mappings");

		MAPPINGS_TINY_BASE = new File(MAPPINGS_DIR, mappingsName + "-tiny-" + minecraftVersion + "-" + mappingsVersion + "-base");
		MAPPINGS_TINY = new File(MAPPINGS_DIR, mappingsName + "-tiny-" + minecraftVersion + "-" + mappingsVersion);
		parameterNames = new File(MAPPINGS_DIR, mappingsName + "-params-" + minecraftVersion + '-' + mappingsVersion);
		MAPPINGS_MIXIN_EXPORT = new File(extension.getProjectCache(), "mixin-map-" + minecraftVersion + "-" + mappingsVersion + ".tiny");
	}

	public void clearFiles() {
		MAPPINGS_TINY.delete();
        MAPPINGS_TINY_BASE.delete();
        parameterNames.delete();
	}

	@Override
	public String getTargetConfig() {
		return Constants.MAPPINGS;
	}
}
