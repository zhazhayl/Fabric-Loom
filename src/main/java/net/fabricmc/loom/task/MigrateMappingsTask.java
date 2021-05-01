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

package net.fabricmc.loom.task;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.io.MappingsReader;
import org.cadixdev.mercury.Mercury;
import org.cadixdev.mercury.mixin.MixinRemapper;
import org.cadixdev.mercury.remapper.MercuryRemapper;

import org.gradle.api.GradleException;
import org.gradle.api.IllegalDependencyNotation;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.providers.MappingsProvider;
import net.fabricmc.loom.providers.MinecraftMappedProvider;
import net.fabricmc.loom.util.SourceRemapper;
import net.fabricmc.mappings.ClassEntry;
import net.fabricmc.mappings.EntryTriple;
import net.fabricmc.mappings.FieldEntry;
import net.fabricmc.mappings.Mappings;
import net.fabricmc.mappings.MethodEntry;

public class MigrateMappingsTask extends AbstractLoomTask {
	private Path inputDir;
	private Path outputDir;
	private String mappings;
	private boolean doMixins;

	public MigrateMappingsTask() {
		inputDir = getProject().file("src/main/java").toPath();
		outputDir = getProject().file("remappedSrc").toPath();
	}

	@Option(option = "input", description = "Java source file directory")
	public void setInputDir(String inputDir) {
		this.inputDir = getProject().file(inputDir).toPath();
	}

	@Option(option = "output", description = "Remapped source output directory")
	public void setOutputDir(String outputDir) {
		this.outputDir = getProject().file(outputDir).toPath();
	}

	@Option(option = "mappings", description = "Target mappings")
	public void setMappings(String mappings) {
		this.mappings = mappings;
	}

	@Option(option = "mixins", description = "Also remap Mixins")
	public void setDoMixins(boolean doMixins) {
		this.doMixins = doMixins;
	}

	@TaskAction
	public void doTask() throws Throwable {
		Project project = getProject();
		LoomGradleExtension extension = getExtension();

		project.getLogger().lifecycle(":loading mappings");

		if (!Files.exists(inputDir) || !Files.isDirectory(inputDir)) {
			throw new IllegalArgumentException("Could not find input directory: " + inputDir.toAbsolutePath());
		}

		Files.createDirectories(outputDir);

		File mappings = loadMappings();
		MappingsProvider mappingsProvider = extension.getMappingsProvider();

		try {
			Mappings currentMappings = mappingsProvider.getMappings();
			Mappings targetMappings = getMappings(mappings);
			migrateMappings(project, extension.getMinecraftMappedProvider(), inputDir, outputDir, currentMappings, targetMappings, doMixins);
			project.getLogger().lifecycle(":remapped project written to " + outputDir.toAbsolutePath());
		} catch (IOException e) {
			throw new IllegalArgumentException("Error while loading mappings", e);
		}
	}

	private File loadMappings() {
		Project project = getProject();

		if (mappings == null || mappings.isEmpty()) {
			throw new IllegalArgumentException("No mappings were specified. Use --mappings=\"\" to specify target mappings");
		}

		Set<File> files;

		try {
			files = project.getConfigurations().detachedConfiguration(project.getDependencies().create(mappings)).resolve();
		} catch (IllegalDependencyNotation ignored) {
			project.getLogger().info("Could not locate mappings, presuming V2 Yarn");

			try {
				files = project.getConfigurations().detachedConfiguration(project.getDependencies().module(ImmutableMap.of("group", "net.fabricmc", "name", "yarn", "version", mappings, "classifier", "v2"))).resolve();
			} catch (GradleException ignored2) {
				project.getLogger().info("Could not locate mappings, presuming V1 Yarn");
				files = project.getConfigurations().detachedConfiguration(project.getDependencies().module(ImmutableMap.of("group", "net.fabricmc", "name", "yarn", "version", mappings))).resolve();
			}
		}

		if (files.isEmpty()) {
			throw new IllegalArgumentException("Mappings could not be found");
		}

		return Iterables.getOnlyElement(files);
	}

	private static Mappings getMappings(File mappings) throws IOException {
		try (FileSystem fs = FileSystems.newFileSystem(mappings.toPath(), null); InputStream in = Files.newInputStream(fs.getPath("mappings/mappings.tiny"))) {
			return net.fabricmc.mappings.MappingsProvider.readTinyMappings(in);
		}
	}

	private static void migrateMappings(Project project, MinecraftMappedProvider minecraftMappedProvider,
										Path inputDir, Path outputDir, Mappings currentMappings, Mappings targetMappings, boolean doMixins
	) throws IOException {
		project.getLogger().lifecycle(":joining mappings");
		@SuppressWarnings("resource") //Hush, it doesn't need closing
		MappingSet mappingSet = new MappingsJoiner(currentMappings, targetMappings, "intermediary", "named").read();

		project.getLogger().lifecycle(":remapping");
		Mercury mercury = SourceRemapper.createMercuryWithClassPath(project, false);

		mercury.getClassPath().add(minecraftMappedProvider.MINECRAFT_MAPPED_JAR.toPath());
		mercury.getClassPath().add(minecraftMappedProvider.MINECRAFT_INTERMEDIARY_JAR.toPath());

		if (doMixins) mercury.getProcessors().add(MixinRemapper.create(mappingSet));
		mercury.getProcessors().add(MercuryRemapper.create(mappingSet));

		try {
			mercury.rewrite(inputDir, outputDir);
		} catch (Exception e) {
			project.getLogger().warn("Could not remap fully!", e);
		}

		project.getLogger().lifecycle(":cleaning file descriptors");
		System.gc();
	}

	private static class MappingsJoiner extends MappingsReader {
		private final Mappings sourceMappings, targetMappings;
		private final String fromNamespace, toNamespace;

		/**
		 * Say A is the source mappings and B is the target mappings.
		 * It does not map from intermediary to named but rather maps from named-A to named-B, by matching intermediary names.
		 * It goes through all of the intermediary names of A, and for every such intermediary name, call it I,
		 * matches the named mapping of I in A, with the named mapping of I in B.
		 * As you might imagine, this requires intermediary mappings to be stable across all versions.
		 * Since we only use intermediary names (and not descriptors) to match, and intermediary names are unique,
		 * this will migrate methods that have had their signature changed too.
		 */
		private MappingsJoiner(Mappings sourceMappings, Mappings targetMappings, String fromNamespace, String toNamespace) {
			this.sourceMappings = sourceMappings;
			this.targetMappings = targetMappings;
			this.fromNamespace = fromNamespace;
			this.toNamespace = toNamespace;
		}

		@Override
		public MappingSet read(MappingSet mappings) {
			Map<String, ClassEntry> targetClasses = targetMappings.getClassEntries().stream().collect(Collectors.toMap(mapping -> mapping.get(fromNamespace), Function.identity()));
			Map<EntryTriple, FieldEntry> targetFields = targetMappings.getFieldEntries().stream().collect(Collectors.toMap(mapping -> mapping.get(fromNamespace), Function.identity()));
			Map<EntryTriple, MethodEntry> targetMethods = targetMappings.getMethodEntries().stream().collect(Collectors.toMap(mapping -> mapping.get(fromNamespace), Function.identity()));

			for (ClassEntry entry : sourceMappings.getClassEntries()) {
				String from = entry.get(toNamespace);
				String to = targetClasses.getOrDefault(entry.get(fromNamespace), entry).get(toNamespace);

				mappings.getOrCreateClassMapping(from).setDeobfuscatedName(to);
			}

			for (FieldEntry entry : sourceMappings.getFieldEntries()) {
				EntryTriple fromEntry = entry.get(toNamespace);
				EntryTriple toEntry = targetFields.getOrDefault(entry.get(fromNamespace), entry).get(toNamespace);

				mappings.getOrCreateClassMapping(fromEntry.getOwner()).getOrCreateFieldMapping(fromEntry.getName(), fromEntry.getDesc()).setDeobfuscatedName(toEntry.getName());
			}

			for (MethodEntry entry : sourceMappings.getMethodEntries()) {
				EntryTriple fromEntry = entry.get(toNamespace);
				EntryTriple toEntry = targetMethods.getOrDefault(entry.get(fromNamespace), entry).get(toNamespace);

				mappings.getOrCreateClassMapping(fromEntry.getOwner()).getOrCreateMethodMapping(fromEntry.getName(), fromEntry.getDesc()).setDeobfuscatedName(toEntry.getName());
			}

			return mappings;
		}

		@Override
		public void close() {
		}
	}
}
