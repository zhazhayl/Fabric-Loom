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

package net.fabricmc.loom;

import net.fabricmc.loom.providers.MappingsProvider;
import net.fabricmc.loom.providers.MinecraftLibraryProvider;
import net.fabricmc.loom.task.*;
import net.fabricmc.loom.task.fernflower.FernFlowerTask;
import net.fabricmc.loom.util.LineNumberRemapper;
import net.fabricmc.loom.util.progress.ProgressLogger;
import net.fabricmc.stitch.util.StitchUtil;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.BiConsumer;

public class LoomGradlePlugin extends AbstractPlugin {
	private static File getMappedByproduct(Project project, String suffix) {
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		MappingsProvider mappingsProvider = extension.getMappingsProvider();
		File mappedJar = mappingsProvider.mappedProvider.getMappedJar();
		String path = mappedJar.getAbsolutePath();
		if (!path.toLowerCase(Locale.ROOT).endsWith(".jar")) {
			throw new RuntimeException("Invalid mapped JAR path: " + path);
		}

		return new File(path.substring(0, path.length() - 4) + suffix);
	}

	@Override
	public void apply(Project target) {
		super.apply(target);

		TaskContainer tasks = target.getTasks();

		tasks.register("cleanLoomBinaries", CleanLoomBinaries.class);
		tasks.register("cleanLoomMappings", CleanLoomMappings.class);

		tasks.register("remapJar", RemapJar.class);

		register(project, "genSources", FernFlowerTask.class, t -> {
			t.getOutputs().upToDateWhen((o) -> false);
		}, (project, task) -> {
			LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
			MinecraftLibraryProvider libraryProvider = extension.getMinecraftProvider().libraryProvider;
			MappingsProvider mappingsProvider = extension.getMappingsProvider();
			File mappedJar = mappingsProvider.mappedProvider.getMappedJar();
			File linemappedJarTmp = getMappedByproduct(project, "-linemapped.jar.tmp");
			File sourcesJar = getMappedByproduct(project, "-sources.jar");
			File linemapFile = getMappedByproduct(project, "-sources.lmap");

			task.setInput(mappedJar);
			task.setOutput(sourcesJar);
			task.setLineMapFile(linemapFile);
			task.setLibraries(libraryProvider.getLibraries());

			task.doLast(t -> {
				project.getLogger().lifecycle(":adjusting line numbers");
				LineNumberRemapper remapper = new LineNumberRemapper();
				remapper.readMappings(linemapFile);

				ProgressLogger progressLogger = ProgressLogger.getProgressFactory(project, FernFlowerTask.class.getName());
				progressLogger.start("Adjusting line numbers", "linemap");

				try (StitchUtil.FileSystemDelegate inFs = StitchUtil.getJarFileSystem(mappedJar, true);
				     StitchUtil.FileSystemDelegate outFs = StitchUtil.getJarFileSystem(linemappedJarTmp, true)) {
					remapper.process(progressLogger, inFs.get().getPath("/"), outFs.get().getPath("/"));
				} catch (IOException e) {
					throw new RuntimeException(e);
				}

				progressLogger.completed();

				Path mappedJarPath = mappedJar.toPath();
				Path linemappedJarTmpPath = linemappedJarTmp.toPath();

				if (Files.exists(linemappedJarTmpPath)) {
					try {
						Files.deleteIfExists(mappedJarPath);
						Files.move(linemappedJarTmpPath, mappedJarPath);
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				}
			});
		});

		tasks.register("downloadAssets", DownloadAssetsTask.class, t -> {
			t.setGroup("ide");
		});

		tasks.register("genIdeaWorkspace", GenIdeaProjectTask.class, t -> {
			t.dependsOn("idea", "downloadAssets");
			t.setGroup("ide");
		});

		tasks.register("genEclipseRuns", GenEclipseRunsTask.class, t -> {
			t.dependsOn("downloadAssets");
			t.setGroup("ide");
		});

		tasks.register("vscode", GenVsCodeProjectTask.class, t -> {
			t.dependsOn("downloadAssets");
			t.setGroup("ide");
		});

		tasks.register("remapSourcesJar", RemapSourcesJar.class);

		tasks.register("runClient", RunClientTask.class, t -> {
			t.dependsOn("buildNeeded", "downloadAssets");
			t.setGroup("minecraftMapped");
		});

		tasks.register("runServer", RunServerTask.class, t -> {
			t.dependsOn("buildNeeded");
			t.setGroup("minecraftMapped");
		});
	}

	/**
	 * Adds the given task to the given project, running the first configuration immediately, and the second on {@link Project#afterEvaluate(Action)}
	 *
	 * @param project The project to add the task to
	 * @param name The name of the task to be added
	 * @param taskClass The type of the task to be added
	 * @param configuration Any configuration to be done on the task immediately
	 * @param postConfiguration Any configuration to be done on the task once the project has been evaluated
	 *
	 * @return The created task provider from {@link TaskContainer#register(String, Class, Action)}
	 */
	private static <T extends Task> TaskProvider<T> register(Project project, String name, Class<T> taskClass, Action<? super T> configuration, BiConsumer<? super Project, ? super T> postConfiguration) {
		TaskProvider<T> task = project.getTasks().register(name, taskClass, configuration);
		project.afterEvaluate(p -> task.configure(t -> postConfiguration.accept(p, t)));
		return task;
	}
}
