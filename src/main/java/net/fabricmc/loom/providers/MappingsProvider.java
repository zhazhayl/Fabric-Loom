/*
 * Copyright 2019, 2020 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.common.net.UrlEscapers;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import org.gradle.api.Project;
import org.gradle.api.logging.Logger;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.dependencies.DependencyProvider;
import net.fabricmc.loom.dependencies.LogicalDependencyProvider;
import net.fabricmc.loom.providers.StackedMappingsProvider.MappingFile;
import net.fabricmc.loom.providers.StackedMappingsProvider.MappingFile.MappingType;
import net.fabricmc.loom.providers.mappings.EnigmaReader;
import net.fabricmc.loom.providers.mappings.MappingBlob;
import net.fabricmc.loom.providers.mappings.MappingBlob.InvertionTarget;
import net.fabricmc.loom.providers.mappings.MappingBlob.Mapping;
import net.fabricmc.loom.providers.mappings.MappingSplat;
import net.fabricmc.loom.providers.mappings.MappingSplat.CombinedMapping;
import net.fabricmc.loom.providers.mappings.MappingSplat.CombinedMapping.ArgOnlyMethod;
import net.fabricmc.loom.providers.mappings.MappingSplat.CombinedMapping.CombinedField;
import net.fabricmc.loom.providers.mappings.MappingSplat.CombinedMapping.CombinedMethod;
import net.fabricmc.loom.providers.mappings.TinyDuplicator;
import net.fabricmc.loom.providers.mappings.TinyReader;
import net.fabricmc.loom.providers.mappings.TinyWriter;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.TinyRemapperMappingsHelper;
import net.fabricmc.mappings.Mappings;
import net.fabricmc.stitch.commands.CommandProposeFieldNames;
import net.fabricmc.tinyremapper.IMappingProvider;

public class MappingsProvider extends LogicalDependencyProvider {
	public interface MappingFactory {//IOException throwing BiFunction<String, String, IMappingProvider>
		IMappingProvider create(String fromMapping, String toMapping) throws IOException;
	}
	public MappingFactory mcRemappingFactory;

	private static final String INTERMEDIARY = "net.fabricmc.intermediary";
	private final List<MappingFile> mappingFiles = new ArrayList<>();

	public String mappingsName;
	public String minecraftVersion;
	public String mappingsVersion;

	public File MAPPINGS_DIR;
	public File MAPPINGS_MIXIN_EXPORT;

	private Path stackHistory;
	private boolean knownStack;
	private File intermediaryNames;
	// The mappings that gradle gives us
	private File MAPPINGS_TINY_BASE;
	// The mappings we use in practice
	public File MAPPINGS_TINY;
	private File parameterNames;

	public Mappings getMappings() throws IOException {
		return MappingsCache.INSTANCE.get(MAPPINGS_TINY.toPath());
	}

	@Override
	public Set<Class<? extends DependencyProvider>> getDependencies() {
		return ImmutableSet.of(StackedMappingsProvider.class, MinecraftProvider.class);
	}

	void stackMappings(MappingFile mappings) {
		mappingFiles.add(mappings);
	}

	@Override
	public void provide(Project project, LoomGradleExtension extension, Consumer<Runnable> postPopulationScheduler) throws Exception {
		MinecraftProvider minecraftProvider = getProvider(MinecraftProvider.class);

		initFiles(extension, project.getLogger(), minecraftProvider);

		if (!MAPPINGS_TINY_BASE.exists() || !MAPPINGS_TINY.exists()) {
			if (!MAPPINGS_DIR.exists()) {
				MAPPINGS_DIR.mkdir();
			}

			free: if (!MAPPINGS_TINY_BASE.exists()) {
				//Need to see if any of the mapping files have Intermediaries for the Minecraft version they're going to be running on
				Optional<MappingFile> interProvider = mappingFiles.stream().filter(file -> file.minecraftVersion.equals(minecraftVersion)).sorted((fileA, fileB) -> {
					if (fileA.type == fileB.type) return 0;

					switch (fileA.type) {//Sort by ease of ability to extract headers
					case TinyGz:
						return -1;

					case TinyV1:
						return fileB.type == MappingType.TinyGz ? 1 : -1;

					case TinyV2:
						return fileB.type == MappingType.Enigma ? -1 : 1;

					case Enigma:
						return 1;

					default:
						throw new IllegalArgumentException("Unexpected mapping types to compare: " + fileA.type + " and " + fileB.type);
					}
				}).filter(file -> {
					try {
						InputStream in;
						switch (file.type) {
						case Enigma: //Never will (unless it goes Notch <=> Intermediary which is pointless to be in Enigma's format)
							return false;

						case TinyV1:
						case TinyV2:
							try (FileSystem fileSystem = FileSystems.newFileSystem(file.origin.toPath(), null)) {
								in = Files.newInputStream(fileSystem.getPath("mappings/mappings.tiny"));
							}
							break;

						case TinyGz:
							in = new GZIPInputStream(Files.newInputStream(file.origin.toPath()));
							break;

						default:
							throw new IllegalArgumentException("Unexpected mapping types to read: " + file.type);
						}

						try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
							String header = reader.readLine();
							assert header != null;

							List<String> headers;
							if (file.type != MappingType.TinyV2) {
								assert header.startsWith("v1\t");
								headers = Arrays.asList(header.substring(3).split(" "));
							} else {
								assert header.startsWith("tiny\t2\t");
								String[] bits;
								headers = Arrays.asList(bits = header.split(" ")).subList(3, bits.length);
							}

							assert headers.indexOf("named") >= 0;
							return headers.indexOf("official") >= 0 && headers.indexOf("intermediary") >= 0;
						}
					} catch (IOException e) {
						throw new UncheckedIOException("Error reading mapping file from " + file.origin, e);
					}
				}).findFirst();

				MappingBlob intermediaries;
				if (interProvider.isPresent()) {
					if (mappingFiles.size() == 1) {
						MappingFile mappings = Iterables.getOnlyElement(mappingFiles);
						assert mappings == interProvider.get();

						switch (mappings.type) {
						case TinyV1:
							project.getLogger().lifecycle(":extracting " + mappings.origin.getName());
							try (FileSystem fileSystem = FileSystems.newFileSystem(mappings.origin.toPath(), null)) {
								Files.copy(fileSystem.getPath("mappings/mappings.tiny"), MAPPINGS_TINY_BASE.toPath());
							}
							break free;

						case TinyGz:
							project.getLogger().lifecycle(":extracting " + mappings.origin.getName());
							FileUtils.copyInputStreamToFile(new GZIPInputStream(new FileInputStream(mappings.origin)), MAPPINGS_TINY_BASE);
							break free;

						case TinyV2:
							//TODO: Implement V2 -> V1 converter
							break free;

						case Enigma:
						default: //Shouldn't end up here if this is the only mapping file supplied
							throw new IllegalStateException("Unexpected mappings type " + mappings.type + " from " + mappings.origin);
						}
					}

					intermediaries = null; //FIXME
				} else {
					project.getLogger().lifecycle(":loading " + intermediaryNames.getName());

					if (!intermediaryNames.exists()) {//Grab intermediary mappings from Github
						FileUtils.copyURLToFile(new URL("https://github.com/FabricMC/intermediary/raw/master/mappings/" + UrlEscapers.urlPathSegmentEscaper().escape(minecraftVersion) + ".tiny"), intermediaryNames);
					}

					if (mappingFiles.isEmpty()) {
						TinyDuplicator.duplicateV1Column(intermediaryNames.toPath(), MAPPINGS_TINY_BASE.toPath(), "intermediary", "named");
						break free;
					}

					TinyReader.readTiny(intermediaryNames.toPath(), intermediaries = new MappingBlob());
				}

				File mappingsFile = null;
				switch (FilenameUtils.getExtension(mappingsFile.getName())) {
				case "zip": {//Directly downloaded the enigma file (:enigma@zip)
					if (parameterNames.exists()) parameterNames.delete();

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

						enigma = enigma.rename(intermediaries.invert(InvertionTarget.MEMBERS));
					}

					project.getLogger().lifecycle(":combining mappings");
					MappingSplat combined = new MappingSplat(enigma, intermediaries);

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


					break;

				case "jar": //Downloaded a jar containing the tiny jar

					break;

				default: //Not sure what we've ended up with, but it's not what we want/expect
					throw new IllegalStateException("Unexpected mappings base type: " + FilenameUtils.getExtension(mappingsFile.getName()) + "(from " + mappingsFile.getName() + ')');
				}

				if (MAPPINGS_TINY.exists()) {
					MAPPINGS_TINY.delete();
				}

				//If we've successfully joined all the mappings together, save the stack
				if (!knownStack && mappingFiles.size() > 1) writeStackHistory(mappingsVersion);
			}

			assert MAPPINGS_TINY_BASE.exists();
			assert !MAPPINGS_TINY.exists();

			project.getLogger().lifecycle(":populating field names");
			new CommandProposeFieldNames().run(new String[] {
					minecraftProvider.getMergedJar().getAbsolutePath(),
					MAPPINGS_TINY_BASE.getAbsolutePath(),
					MAPPINGS_TINY.getAbsolutePath()
			});
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
				private final IMappingProvider normal = TinyRemapperMappingsHelper.create(getMappings(), fromM, toM, false);

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
			mcRemappingFactory = (fromM, toM) -> TinyRemapperMappingsHelper.create(getMappings(), fromM, toM, false);
		}

		File mappingJar;
		if (mappingFiles.size() == 1 && Iterables.getOnlyElement(mappingFiles).type == MappingType.TinyV1) {
			mappingJar = Iterables.getOnlyElement(mappingFiles).origin;
			if (MAPPINGS_TINY.lastModified() < mappingJar.lastModified()) MAPPINGS_TINY.setLastModified(mappingJar.lastModified() - 1);
		} else {
			mappingJar = new File(MAPPINGS_DIR, FilenameUtils.removeExtension(MAPPINGS_TINY.getName()) + ".jar");

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
	}

	private String readStackHistory() {
		if (Files.notExists(stackHistory)) {
			return "1";
		} else {
			List<String> expected = mappingFiles.stream().map(mappings -> mappings.name + '-' + mappings.version + ' ' + mappings.minecraftVersion).collect(Collectors.toList());
			assert !expected.isEmpty();

			try (BufferedReader reader = Files.newBufferedReader(stackHistory)) {
				String currentVersion = reader.readLine();
				String newestVersion = currentVersion;
				int currentPosition = 0;
				for (String line = reader.readLine(); line != null; line = reader.readLine()) {
					assert !line.isEmpty();

					if (currentVersion == null || line.charAt(0) != '\t') {
						if (currentPosition == expected.size()) {
							assert currentVersion != null;
							knownStack = true;
							return currentVersion;
						}

						currentVersion = line;
						currentPosition = 0;
					} else {
						assert line.charAt(0) == '\t';

						if (currentPosition >= expected.size() || expected.get(currentPosition) != line.substring(1)) {
							currentVersion = null;
						} else {
							currentPosition++;
						}
					}
				}

				return Integer.toString(Integer.parseUnsignedInt(newestVersion) + 1);
			} catch (IOException e) {
				throw new UncheckedIOException("Error reading stack history file at " + stackHistory, e);
			}
		}
	}

	private void writeStackHistory(String version) {
		assert !knownStack; //No need to go through all this if it's already in the history

		StringBuilder addition = new StringBuilder(version).append('\n');

		for (MappingFile mapping : mappingFiles) {
			addition.append('\t').append(mapping.name).append('-').append(mapping.version).append(' ').append(mapping.minecraftVersion).append('\n');
		}

		byte data[] = addition.toString().getBytes(StandardCharsets.UTF_8);
		ByteBuffer active;
		if (data.length >= 4096) {
			active = ByteBuffer.wrap(data);
		} else {
			active = ByteBuffer.allocate(4096);
			active.put(data);
			active.flip();
		}
		assert active.limit() == data.length;

		ByteBuffer passive = ByteBuffer.allocate(active.capacity());

		try (FileChannel channel = FileChannel.open(stackHistory, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
			assert channel.position() == 0;

			if (passive.capacity() != active.capacity()) {//It's important the amount of the file read is the same as the amount written
				throw new IllegalArgumentException("Inconsistent capacities between buffers: " + passive.capacity() + " and " + active.capacity());
			}

			long offset = passive.capacity() - active.limit(); //Account for reading/writing not necessarily being at the same start/end positions
			assert offset >= 0;
			do {
				long position = channel.position();
				if (position > 0 && offset > 0) {
					channel.position(position + offset);
				}

				passive.clear(); //Ensure the buffer's limit == capacity, as well as position == 0

				int read;
				do {
					read = channel.read(passive);
				} while (read != -1 && passive.hasRemaining());
				//System.out.println("Read in \"" + new String(passive.array(), 0, passive.position()) + '"');
				passive.flip();

				channel.position(position);

				//System.out.println("Writing \"" + new String(active.array(), active.position(), active.remaining()) + '"');
				while (active.hasRemaining()) {
					channel.write(active);
				}

				ByteBuffer swap = passive;
				passive = active;
				active = swap;
			} while (active.limit() >= active.capacity());

			while (active.hasRemaining()) {
				//System.out.println("Writing final \"" + new String(active.array(), active.position(), active.remaining()) + '"');
				channel.write(active);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Error writing stack history file to " + stackHistory, e);
		}
	}

	private void initFiles(LoomGradleExtension extension, Logger logger, MinecraftProvider minecraftProvider) {
		MAPPINGS_DIR = new File(extension.getUserCache(), "mappings/" + minecraftProvider.minecraftVersion);

		switch (mappingFiles.size()) {
		case 0:
			logger.lifecycle(":setting up mappings (" + INTERMEDIARY + ' ' + minecraftProvider.minecraftVersion + ')');
			mappingsName = INTERMEDIARY;
			mappingsVersion = minecraftVersion = minecraftProvider.minecraftVersion;
			break;

		case 1: {
			MappingFile mappings = Iterables.getOnlyElement(mappingFiles);
			logger.lifecycle(":setting up mappings (" + mappings.name + ' ' + mappings.version + '@' + mappings.minecraftVersion + ')');
			mappingsName = mappings.name;
			mappingsVersion = mappings.version;
			minecraftVersion = mappings.minecraftVersion;
			break;
		}

		default: {
			logger.lifecycle(":setting up mappings (" + mappingFiles.size() + " files in stack)");
			stackHistory = new File(MAPPINGS_DIR, "stack.history").toPath();

			mappingsName = "stack";
			mappingsVersion = readStackHistory();
			//The stack could be made up of multiple Minecraft versions, so we'll just use the version the stack will run on
			minecraftVersion = minecraftProvider.minecraftVersion;
			break;
		}
		}

		intermediaryNames = new File(MAPPINGS_DIR, INTERMEDIARY + "-intermediary.tiny");
		MAPPINGS_TINY_BASE = new File(MAPPINGS_DIR, mappingsName + "-tiny-" + minecraftVersion + '-' + mappingsVersion + "-base.tiny");
		MAPPINGS_TINY = new File(MAPPINGS_DIR, mappingsName + "-tiny-" + minecraftVersion + '-' + mappingsVersion + ".tiny");
		parameterNames = new File(MAPPINGS_DIR, mappingsName + "-params-" + minecraftVersion + '-' + mappingsVersion);

		MAPPINGS_MIXIN_EXPORT = new File(extension.getProjectBuildCache(), "mixin-map-" + minecraftVersion + '-' + mappingsVersion + ".tiny");
	}

	public void clearFiles() {
		MAPPINGS_TINY.delete();
		MAPPINGS_TINY_BASE.delete();
		intermediaryNames.delete();
		parameterNames.delete();
	}
}
