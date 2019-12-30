/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import org.apache.commons.io.FilenameUtils;

import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.dependencies.PhysicalDependencyProvider;
import net.fabricmc.loom.providers.StackedMappingsProvider.MappingFile.MappingType;
import net.fabricmc.loom.util.Constants;

public class StackedMappingsProvider extends PhysicalDependencyProvider {
	static final class MappingFile {
		enum MappingType {
			TinyV1, TinyV2, TinyGz, Enigma;
		}

		public final String name, version, minecraftVersion;
		public final File origin;
		public final MappingType type;

		public MappingFile(File origin, String name, String version, String minecraftVersion, MappingType type) {
			this.origin = origin;
			this.name = name;
			this.version = version;
			this.minecraftVersion = minecraftVersion;
			this.type = type;
		}
	}
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
		File mappingsFile = dependency.resolveFile().orElseThrow(() -> new RuntimeException("Could not find dependency " + dependency));
		String mappingsName = dependency.getFullName();

		String version = dependency.getResolvedVersion();
		String minecraftVersion, mappingsVersion;
		if (version.contains("+build.")) {
			minecraftVersion = version.substring(0, version.lastIndexOf('+'));
			mappingsVersion = version.substring(version.lastIndexOf('.') + 1);
		} else {
			char splitter = version.contains("-") ? '-' : '.';
			minecraftVersion = version.substring(0, version.lastIndexOf(splitter));
			mappingsVersion = version.substring(version.lastIndexOf(splitter) + 1);
		}

		MappingType type;
		switch (FilenameUtils.getExtension(mappingsFile.getName())) {
		case "zip": {//Directly downloaded the enigma file (:enigma@zip)
			type = MappingType.Enigma;
			break;
		}
		case "gz": //Directly downloaded the tiny file (:tiny@gz)
			type = MappingType.TinyGz;
			break;

		case "jar": //Downloaded a jar containing the tiny jar
			try (FileSystem fileSystem = FileSystems.newFileSystem(mappingsFile.toPath(), null)) {
				if (looksLikeV2(fileSystem.getPath("mappings/mappings.tiny"))) {
					type = MappingType.TinyV2;
				} else {
					type = MappingType.TinyV1;
				}
			}
			break;

		default: //Not sure what we've ended up with, but it's not what we want/expect
			throw new IllegalStateException("Unexpected mappings base type: " + FilenameUtils.getExtension(mappingsFile.getName()) + "(from " + mappingsFile.getName() + ')');
		}

		realProvider.stackMappings(new MappingFile(mappingsFile, mappingsName, mappingsVersion, minecraftVersion, type));
	}

	private static boolean looksLikeV2(Path mappings) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(mappings)) {
			String header = reader.readLine();

			if (header == null) {
				throw new EOFException("Empty mappings supplied in " + mappings);
			} else if (header.startsWith("v1\t")) {
				return false;
			} else if (header.startsWith("tiny\t2\t")) {
				return true;
			} else {
				throw new IOException("Unable to guess mapping version from " + header + " in " + mappings);
			}
		}
	}
}