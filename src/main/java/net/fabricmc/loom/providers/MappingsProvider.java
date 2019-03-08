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
import net.fabricmc.loom.providers.mappings.EnigmaReader;
import net.fabricmc.loom.providers.mappings.MappingBlob;
import net.fabricmc.loom.providers.mappings.MappingBlob.Mapping;
import net.fabricmc.loom.providers.mappings.MappingBlob.Mapping.Field;
import net.fabricmc.loom.providers.mappings.MappingBlob.Mapping.Method;
import net.fabricmc.loom.providers.mappings.TinyReader;
import net.fabricmc.loom.providers.mappings.TinyWriter;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DependencyProvider;
import net.fabricmc.stitch.commands.CommandProposeFieldNames;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.TinyUtils;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.gradle.api.Project;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
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
	private File intermediaryNames;
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
				case "zip": {//Directly downloaded the enigma file (:enigma@zip)
					if (parameterNames.exists()) parameterNames.delete();

					project.getLogger().lifecycle(":writing " + intermediaryNames.getName());
					MappingBlob tiny = new MappingBlob();
					if (!intermediaryNames.exists()) {//Grab intermediary mappings (which aren't in the enigma file)
						FileUtils.copyURLToFile(new URL("https://github.com/FabricMC/intermediary/raw/master/mappings/" + minecraftVersion + ".tiny"), intermediaryNames);
					}
					TinyReader.readTiny(intermediaryNames.toPath(), tiny);

					project.getLogger().lifecycle(":loading " + mappingsJar.getName());
					MappingBlob enigma = new MappingBlob();
					EnigmaReader.readEnigma(mappingsJar.toPath(), enigma);

					project.getLogger().lifecycle(":writing " + MAPPINGS_TINY_BASE.getName());
					try (TinyWriter writer = new TinyWriter(MAPPINGS_TINY_BASE.toPath())) {
						for (Mapping interMap : tiny) {
							String notch = interMap.from;
							Mapping namedMap = enigma.get(notch);

							String inter = interMap.to();
							writer.acceptClass(notch, namedMap.toOr(inter), inter);

							for (Method method : interMap.methods()) {
								Method namedMethod = namedMap.method(method);
								inter = method.name();
								writer.acceptMethod(notch, method.fromName, method.fromDesc, namedMethod.nameOr(inter), inter);
							}

							for (Field field : interMap.fields()) {
								Field namedField = namedMap.field(field);
								inter = field.name();
								writer.acceptField(notch, field.fromName, field.fromDesc, namedField.nameOr(inter), inter);
							}
						}
					}

					project.getLogger().lifecycle(":writing " + parameterNames.getName());
					try (BufferedWriter writer = new BufferedWriter(new FileWriter(parameterNames, false))) {
						for (Mapping mapping : enigma) {
							Mapping enigmaMap = enigma.get(mapping.from);
							String mappedName = mapping.toOr(enigmaMap.to());

							for (Method method : mapping.methods()) {
								if (method.hasArgs()) {
									writer.write(mappedName + '/' + method.nameOr(enigmaMap.method(method).name()));
									writer.newLine();
									for (String arg : method.namedArgs()) {
										writer.write("\t" + arg);
										writer.newLine();
									}
								}
							}
						}
					}
					break;
				}
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
				Map<String, String[]> lines = new HashMap<>();

				try (BufferedReader reader = new BufferedReader(new FileReader(parameterNames))) {
					for (String line = reader.readLine(), current = null; line != null; line = reader.readLine()) {
						if (current == null || line.charAt(0) != '\t') {
							current = line;
						} else {
							int split = line.indexOf(':'); //\tno: name
							int number = Integer.parseInt(line.substring(1, split));
							String name = line.substring(split + 2);

							String[] lineSet = lines.get(current);
							if (lineSet == null) {
								//The args are written backwards so the biggest index is first
								lines.put(current, lineSet = new String[number + 1]);
							}
							lineSet[number] = name;
						}
					}
				}

				mcRemappingFactory = (fromM, toM) -> new IMappingProvider() {
					private final IMappingProvider normal = TinyUtils.createTinyMappingProvider(MAPPINGS_TINY.toPath(), fromM, toM);

					@Override
					public void load(Map<String, String> classMap, Map<String, String> fieldMap, Map<String, String> methodMap, Map<String, String[]> localMap) {
						load(classMap, fieldMap, methodMap);
						localMap.putAll(lines);
					}

					@Override
					public void load(Map<String, String> classMap, Map<String, String> fieldMap, Map<String, String> methodMap) {
						normal.load(classMap, fieldMap, methodMap);
					}
				};
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
		intermediaryNames = new File(MAPPINGS_DIR, mappingsName + "-intermediary-" + minecraftVersion + "-" + mappingsVersion + ".tiny");
		parameterNames = new File(MAPPINGS_DIR, mappingsName + "-params-" + minecraftVersion + '-' + mappingsVersion);
		MAPPINGS_MIXIN_EXPORT = new File(extension.getProjectCache(), "mixin-map-" + minecraftVersion + "-" + mappingsVersion + ".tiny");
	}

	public void clearFiles() {
		MAPPINGS_TINY.delete();
        MAPPINGS_TINY_BASE.delete();
        intermediaryNames.delete();
        parameterNames.delete();
	}

	@Override
	public String getTargetConfig() {
		return Constants.MAPPINGS;
	}
}
