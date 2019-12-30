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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import com.google.common.net.UrlEscapers;
import org.apache.commons.io.FileUtils;
import org.apache.tools.ant.util.StringUtils;
import org.gradle.api.Project;
import org.zeroturnaround.zip.FileSource;
import org.zeroturnaround.zip.ZipEntrySource;
import org.zeroturnaround.zip.ZipUtil;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.dependencies.PhysicalDependencyProvider;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DownloadUtil;
import net.fabricmc.mappings.Mappings;
import net.fabricmc.stitch.Command;
import net.fabricmc.stitch.commands.CommandProposeFieldNames;
import net.fabricmc.tinyremapper.IMappingProvider;

public class MappingsProvider extends PhysicalDependencyProvider {
	public interface MappingFactory {//IOException throwing BiPredicate<String, String, IMappingProvider>
		IMappingProvider create(String fromMapping, String toMapping) throws IOException;
	}
	public MinecraftMappedProvider mappedProvider;
	public MappingFactory mcRemappingFactory;

	public String mappingsName;
	public String minecraftVersion;
	public String mappingsVersion;

	public File MAPPINGS_DIR;
	// The mappings that gradle gives us
	private File MAPPINGS_TINY_BASE;
	// The mappings we use in practice
	public File MAPPINGS_TINY;
	private File intermediaryNames;
	private File parameterNames;

	public File MAPPINGS_MIXIN_EXPORT;

	public Mappings getMappings() throws IOException {
		return MappingsCache.INSTANCE.get(MAPPINGS_TINY.toPath());
	}

	@Override
	public void provide(DependencyInfo dependency, Project project, LoomGradleExtension extension, Consumer<Runnable> postPopulationScheduler) throws Exception {
		MinecraftProvider minecraftProvider = getDependencyManager().getProvider(MinecraftProvider.class);

		project.getLogger().lifecycle(":setting up mappings (" + dependency.getFullName() + " " + dependency.getResolvedVersion() + ")");

		String version = dependency.getResolvedVersion();
		File mappingsJar = dependency.resolveFile().orElseThrow(() -> new RuntimeException("Could not find yarn mappings: " + dependency));

		this.mappingsName = StringUtils.removeSuffix(dependency.getDependency().getGroup() + "." + dependency.getDependency().getName(), "-unmerged");

		boolean isV2 = doesJarContainV2Mappings(mappingsJar.toPath());

		this.minecraftVersion = minecraftProvider.minecraftVersion;
		this.mappingsVersion = version + (isV2 ? "-v2" : "");

		initFiles(project);

		Files.createDirectories(mappingsDir);
		Files.createDirectories(mappingsStepsDir);

		String[] depStringSplit = dependency.getDepString().split(":");
		String jarClassifier = "final";

		if (depStringSplit.length >= 4) {
			jarClassifier = jarClassifier + depStringSplit[3];
		}

		if (!MAPPINGS_TINY_BASE.exists() || !MAPPINGS_TINY.exists()) {
			if (!MAPPINGS_TINY_BASE.exists()) {
				switch (FilenameUtils.getExtension(mappingsFile.getName())) {
				case "zip": {//Directly downloaded the enigma file (:enigma@zip)
					if (parameterNames.exists()) parameterNames.delete();

					project.getLogger().lifecycle(":loading " + intermediaryNames.getName());
					MappingBlob tiny = new MappingBlob();
					if (!intermediaryNames.exists()) {//Grab intermediary mappings (which aren't in the enigma file)
						FileUtils.copyURLToFile(new URL("https://github.com/FabricMC/intermediary/raw/master/mappings/" + UrlEscapers.urlPathSegmentEscaper().escape(minecraftVersion + ".tiny")), intermediaryNames);
					}
					TinyReader.readTiny(intermediaryNames.toPath(), tiny);

					project.getLogger().lifecycle(":loading " + mappingsFile.getName());
					MappingBlob enigma = new MappingBlob();
					EnigmaReader.readEnigma(mappingsFile.toPath(), enigma);

					if (Streams.stream(enigma.iterator()).parallel().anyMatch(mapping -> mapping.from.startsWith("net/minecraft/class_"))) {
						assert Streams.stream(enigma.iterator()).parallel().filter(mapping -> mapping.to() != null).allMatch(mapping -> mapping.from.startsWith("net/minecraft/class_") || mapping.from.matches("com\\/mojang\\/.+\\$class_\\d+")):
							Streams.stream(enigma.iterator()).filter(mapping -> mapping.to() != null && !mapping.from.startsWith("net/minecraft/class_") && !mapping.from.matches("com\\/mojang\\/.+\\$class_\\d+")).map(mapping -> mapping.from).collect(Collectors.joining(", ", "Found unexpected initial mapping classes: [", "]"));
						assert Streams.stream(enigma.iterator()).map(Mapping::methods).flatMap(Streams::stream).parallel().filter(method -> method.name() != null).allMatch(method -> method.fromName.startsWith("method_") || method.fromName.equals(method.name())):
							Streams.stream(enigma.iterator()).map(Mapping::methods).flatMap(Streams::stream).parallel().filter(method -> method.name() != null && !method.fromName.startsWith("method_")).map(method -> method.fromName + method.fromDesc).collect(Collectors.joining(", ", "Found unexpected method mappings: ", "]"));
						assert Streams.stream(enigma.iterator()).map(Mapping::fields).flatMap(Streams::stream).parallel().filter(field -> field.name() != null).allMatch(field -> field.fromName.startsWith("field_")):
							Streams.stream(enigma.iterator()).map(Mapping::fields).flatMap(Streams::stream).parallel().filter(field -> field.name() != null && !field.fromName.startsWith("field_")).map(field -> field.fromName).collect(Collectors.joining(", ", "Found unexpected field mappings: ", "]"));

						enigma = enigma.rename(tiny.invert(InvertionTarget.MEMBERS));
					}

					project.getLogger().lifecycle(":combining mappings");
					MappingSplat combined = new MappingSplat(enigma, tiny);

					project.getLogger().lifecycle(":writing " + MAPPINGS_TINY_BASE.getName());
					try (TinyWriter writer = new TinyWriter(MAPPINGS_TINY_BASE.toPath())) {
						for (CombinedMapping mapping : combined) {
							String notch = mapping.from;
							writer.acceptClass(notch, mapping.to, mapping.fallback);

							for (CombinedMethod method : mapping.methods()) {
								writer.acceptMethod(notch, method.from, method.fromDesc, method.to, method.fallback);
							}

							for (CombinedField field : mapping.fields()) {
								writer.acceptField(notch, field.from, field.fromDesc, field.to, field.fallback);
							}
						}
					}

					project.getLogger().lifecycle(":writing " + parameterNames.getName());
					try (BufferedWriter writer = new BufferedWriter(new FileWriter(parameterNames, false))) {
						for (CombinedMapping mapping : combined) {
							for (ArgOnlyMethod method : mapping.allArgs()) {
								writer.write(mapping.to + '/' + method.from + method.fromDesc);
								writer.newLine();
								for (String arg : method.namedArgs()) {
									assert !arg.endsWith(": null"); //Skip nulls
									writer.write("\t" + arg);
									writer.newLine();
								}
							}
						}
					}
					break;
				}
				case "gz": //Directly downloaded the tiny file (:tiny@gz)
					project.getLogger().lifecycle(":extracting " + mappingsFile.getName());
					FileUtils.copyInputStreamToFile(new GZIPInputStream(new FileInputStream(mappingsFile)), MAPPINGS_TINY_BASE);
					break;

				case "jar": //Downloaded a jar containing the tiny jar
					project.getLogger().lifecycle(":extracting " + mappingsFile.getName());
					try (FileSystem fileSystem = FileSystems.newFileSystem(mappingsFile.toPath(), null)) {
						Path fileToExtract = fileSystem.getPath("mappings/mappings.tiny");
						Files.copy(fileToExtract, MAPPINGS_TINY_BASE.toPath());
					}
					break;

				default: //Not sure what we've ended up with, but it's not what we want/expect
					throw new IllegalStateException("Unexpected mappings base type: " + FilenameUtils.getExtension(mappingsFile.getName()) + "(from " + mappingsFile.getName() + ')');
				}
			}

		if (!tinyMappings.exists()) {
			storeMappings(project, minecraftProvider, mappingsJar.toPath());
		}

		if (!tinyMappingsJar.exists()) {
			ZipUtil.pack(new ZipEntrySource[] {new FileSource("mappings/mappings.tiny", tinyMappings)}, tinyMappingsJar);
		}

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
				private final IMappingProvider normal = TinyRemapperMappingsHelper.create(getMappings(), fromM, toM);

				@Override
				public void load(Map<String, String> classMap, Map<String, String> fieldMap, Map<String, String> methodMap, Map<String, String[]> localMap) {
					load(classMap, fieldMap, methodMap);
					if ("official".equals(fromM)) {
						localMap.putAll(lines);
					} else {
						//If we're not going from notch names to something else the line map is useless
						project.getLogger().warn("Missing param map from " + fromM + " to " + toM);
					}
				}

				@Override
				public void load(Map<String, String> classMap, Map<String, String> fieldMap, Map<String, String> methodMap) {
					normal.load(classMap, fieldMap, methodMap);
				}
			};
		} else {
			mcRemappingFactory = (fromM, toM) -> TinyRemapperMappingsHelper.create(getMappings(), fromM, toM);
		}

		File mappingJar;
		if ("jar".equals(FilenameUtils.getExtension(mappingsFile.getName()))) {
			mappingJar = mappingsFile;
			if (MAPPINGS_TINY.lastModified() < mappingJar.lastModified()) mappingJar.setLastModified(mappingJar.lastModified());
		} else {
			mappingJar = new File(MAPPINGS_DIR, mappingsName + "-tiny-" + minecraftVersion + '-' + this.mappingsVersion + ".jar");

			if (!mappingJar.exists() || mappingJar.lastModified() < MAPPINGS_TINY.lastModified()) {
				try (FileSystem fs = FileSystems.newFileSystem(new URI("jar:" + mappingJar.toURI()), Collections.singletonMap("create", "true"))) {
					Path destination = fs.getPath("mappings/mappings.tiny");

					Files.createDirectories(destination.getParent());
					Files.copy(MAPPINGS_TINY.toPath(), destination, StandardCopyOption.REPLACE_EXISTING);
				} catch (URISyntaxException e) {
					throw new IllegalStateException("Cannot convert jar path to URI?", e);
				} catch (IOException e) {
					throw new UncheckedIOException("Error creating mappings jar", e);
				}
			}
		}

		assert mappingJar.exists() && mappingJar.lastModified() >= MAPPINGS_TINY.lastModified();
		addDependency(mappingJar, project, Constants.MAPPINGS);

		mappedProvider = new MinecraftMappedProvider();
		mappedProvider.provide(project, extension, minecraftProvider, this, postPopulationScheduler);
	}

	private void storeMappings(Project project, MinecraftProvider minecraftProvider, Path yarnJar) throws IOException {
		project.getLogger().lifecycle(":extracting " + yarnJar.getFileName());

		try (FileSystem fileSystem = FileSystems.newFileSystem(yarnJar, null)) {
			extractMappings(fileSystem, baseTinyMappings);
		}

		if (baseMappingsAreV2()) {
			// These are unmerged v2 mappings

			// Download and extract intermediary
			String encodedMinecraftVersion = UrlEscapers.urlFragmentEscaper().escape(minecraftVersion);
			String intermediaryArtifactUrl = "https://maven.fabricmc.net/net/fabricmc/intermediary/" + encodedMinecraftVersion + "/intermediary-" + encodedMinecraftVersion + "-v2.jar";
			Path intermediaryJar = mappingsStepsDir.resolve("v2-intermediary-" + minecraftVersion + ".jar");
			DownloadUtil.downloadIfChanged(new URL(intermediaryArtifactUrl), intermediaryJar.toFile(), project.getLogger());

			mergeAndSaveMappings(project, intermediaryJar, yarnJar);
		} else {
			// These are merged v1 mappings
			if (tinyMappings.exists()) {
				tinyMappings.delete();
			}

			project.getLogger().lifecycle(":populating field names");
			suggestFieldNames(minecraftProvider, baseTinyMappings, tinyMappings.toPath());
		}
	}

	private boolean baseMappingsAreV2() throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(baseTinyMappings)) {
			TinyV2Factory.readMetadata(reader);
			return true;
		} catch (IllegalArgumentException e) {
			// TODO: just check the mappings version when Parser supports V1 in readMetadata()
			return false;
		}
	}

	private boolean doesJarContainV2Mappings(Path path) throws IOException {
		try (FileSystem fs = FileSystems.newFileSystem(path, null)) {
			try (BufferedReader reader = Files.newBufferedReader(fs.getPath("mappings", "mappings.tiny"))) {
				TinyV2Factory.readMetadata(reader);
				return true;
			} catch (IllegalArgumentException e) {
				return false;
			}
		}
	}

	public static void extractMappings(FileSystem jar, Path extractTo) throws IOException {
		Files.copy(jar.getPath("mappings/mappings.tiny"), extractTo, StandardCopyOption.REPLACE_EXISTING);
	}

	private void mergeAndSaveMappings(Project project, Path unmergedIntermediaryJar, Path unmergedYarnJar) throws IOException {
		Path unmergedIntermediary = Paths.get(mappingsStepsDir.toString(), "unmerged-intermediary.tiny");
		project.getLogger().info(":extracting " + unmergedIntermediaryJar.getFileName());

		try (FileSystem unmergedIntermediaryFs = FileSystems.newFileSystem(unmergedIntermediaryJar, null)) {
			extractMappings(unmergedIntermediaryFs, unmergedIntermediary);
		}

		Path unmergedYarn = Paths.get(mappingsStepsDir.toString(), "unmerged-yarn.tiny");
		project.getLogger().info(":extracting " + unmergedYarnJar.getFileName());

		try (FileSystem unmergedYarnJarFs = FileSystems.newFileSystem(unmergedYarnJar, null)) {
			extractMappings(unmergedYarnJarFs, unmergedYarn);
		}

		Path invertedIntermediary = Paths.get(mappingsStepsDir.toString(), "inverted-intermediary.tiny");
		reorderMappings(unmergedIntermediary, invertedIntermediary, "intermediary", "official");
		Path unorderedMergedMappings = Paths.get(mappingsStepsDir.toString(), "unordered-merged.tiny");
		project.getLogger().info(":merging");
		mergeMappings(invertedIntermediary, unmergedYarn, unorderedMergedMappings);
		reorderMappings(unorderedMergedMappings, tinyMappings.toPath(), "official", "intermediary", "named");
	}

	private void reorderMappings(Path oldMappings, Path newMappings, String... newOrder) {
		Command command = new CommandReorderTinyV2();
		String[] args = new String[2 + newOrder.length];
		args[0] = oldMappings.toAbsolutePath().toString();
		args[1] = newMappings.toAbsolutePath().toString();
		System.arraycopy(newOrder, 0, args, 2, newOrder.length);
		runCommand(command, args);
	}

	private void mergeMappings(Path intermediaryMappings, Path yarnMappings, Path newMergedMappings) {
		try {
			Command command = new CommandMergeTinyV2();
			runCommand(command, intermediaryMappings.toAbsolutePath().toString(),
							yarnMappings.toAbsolutePath().toString(),
							newMergedMappings.toAbsolutePath().toString(),
							"intermediary", "official");
		} catch (Exception e) {
			throw new RuntimeException("Could not merge mappings from " + intermediaryMappings.toString()
							+ " with mappings from " + yarnMappings, e);
		}
	}

	private void suggestFieldNames(MinecraftProvider minecraftProvider, Path oldMappings, Path newMappings) {
		Command command = new CommandProposeFieldNames();
		runCommand(command, minecraftProvider.MINECRAFT_MERGED_JAR.getAbsolutePath(),
						oldMappings.toAbsolutePath().toString(),
						newMappings.toAbsolutePath().toString());
	}

	private void runCommand(Command command, String... args) {
		try {
			command.run(args);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void initFiles(Project project) {
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		mappingsDir = extension.getUserCache().toPath().resolve("mappings");
		mappingsStepsDir = mappingsDir.resolve("steps");

		MAPPINGS_TINY_BASE = new File(MAPPINGS_DIR, mappingsName + "-tiny-" + minecraftVersion + "-" + mappingsVersion + "-base");
		MAPPINGS_TINY = new File(MAPPINGS_DIR, mappingsName + "-tiny-" + minecraftVersion + "-" + mappingsVersion);
		intermediaryNames = new File(MAPPINGS_DIR, mappingsName + "-intermediary-" + minecraftVersion + ".tiny");
		parameterNames = new File(MAPPINGS_DIR, mappingsName + "-params-" + minecraftVersion + '-' + mappingsVersion);
		MAPPINGS_MIXIN_EXPORT = new File(extension.getProjectBuildCache(), "mixin-map-" + minecraftVersion + "-" + mappingsVersion + ".tiny");
	}

	public void clearFiles() {
		MAPPINGS_TINY.delete();
		MAPPINGS_TINY_BASE.delete();
		intermediaryNames.delete();
		parameterNames.delete();
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
		return false;
	}
}
