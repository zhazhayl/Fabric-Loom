/*
 * Copyright 2020 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers;

import static net.fabricmc.loom.providers.MappingsProvider.INTERMEDIARY;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.zip.ZipError;

import org.apache.commons.io.FileUtils;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.logging.Logger;

import com.google.common.base.Suppliers;
import com.google.common.util.concurrent.Callables;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradleExtension.JarMergeOrder;
import net.fabricmc.loom.util.DownloadUtil;
import net.fabricmc.loom.util.MinecraftVersionInfo;
import net.fabricmc.loom.util.MinecraftVersionInfo.AssetIndex;
import net.fabricmc.loom.util.MinecraftVersionInfo.Library;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;

public class SnappyRemapper {
	public static final class MinecraftVersion {
		private final MinecraftVersionInfo versionInfo;
		private final File clientJar;
		private final File serverJar;

		private final JarMergeOrder mergeOrder;
		private final ForkJoinTask<File> jarMerger;
		Supplier<Path> mappings;

		MinecraftVersion(MinecraftVersionInfo versionInfo, File clientJar, File serverJar, JarMergeOrder mergeOrder, Function<MinecraftVersion, Callable<File>> jarMerger) {
			this.versionInfo = versionInfo;
			this.clientJar = clientJar;
			this.serverJar = serverJar;
			this.mergeOrder = mergeOrder;
			this.jarMerger = ForkJoinPool.commonPool().submit(jarMerger.apply(this));
		}

		public String getName() {
			return versionInfo.id;
		}

		public Collection<Library> getLibraries() {
			return Collections.unmodifiableList(versionInfo.libraries);
		}

		public Set<File> getJavaLibraries(Project project) {
			return project.getConfigurations().detachedConfiguration(versionInfo.libraries.stream().filter(library -> library.allowed() && !library.isNative())
					.map(library -> project.getDependencies().module(library.getArtifactName())).toArray(Dependency[]::new)).getFiles();
		}

		public AssetIndex getAssetIndex() {
			return versionInfo.assetIndex;
		}

		public File getClientJar() {
			return clientJar;
		}

		public File getServerJar() {
			return serverJar;
		}

		public File getMergedJar() {
			return jarMerger.join();
		}

		public JarMergeOrder getMergeStrategy() {
			return mergeOrder;
		}

		public boolean needsIntermediaries() {
			return mergeOrder == JarMergeOrder.LAST && mappings == null;
		}

		public void giveIntermediaries(Supplier<Path> mappingMaker) {
			if (!jarMerger.isDone()) {
				synchronized (this) {
					if (mappings == null) {
						mappings = mappingMaker;
						notify();
					}
				}
			}
		}
	}

	public static MinecraftVersion makeMergedJar(Project project, LoomGradleExtension extension, String version, Optional<String> customManifest, JarMergeOrder mergeOrder) throws IOException {
		boolean offline = project.getGradle().getStartParameter().isOffline();

		MinecraftVersionInfo versionInfo;
		try (FileReader reader = new FileReader(MinecraftProvider.downloadMcJson(project.getLogger(), extension, version, offline, customManifest))) {
			versionInfo = MinecraftProvider.GSON.fromJson(reader, MinecraftVersionInfo.class);
		}
		SpecialCases.enhanceVersion(extension, versionInfo);

		if (mergeOrder == JarMergeOrder.INDIFFERENT) {
			Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Europe/Stockholm"));
			calendar.set(2012, Calendar.JULY, 22); //Day before 12w30a
			mergeOrder = calendar.getTime().before(versionInfo.releaseTime) ? JarMergeOrder.FIRST : JarMergeOrder.LAST;
		}

		boolean needClient = extension.getJarMergeOrder() != JarMergeOrder.SERVER_ONLY;
		boolean needServer = extension.getJarMergeOrder() != JarMergeOrder.CLIENT_ONLY;

		File clientJar = new File(extension.getUserCache(), JarNameFactory.CLIENT.getJarName(version));
		File serverJar = new File(extension.getUserCache(), JarNameFactory.SERVER.getJarName(version));
		File mergedJar = new File(extension.getUserCache(), mergeOrder.getJarName(version));

		if (offline) {
			if ((!needClient || clientJar.exists()) && (!needServer || serverJar.exists())) {
				project.getLogger().debug("Found client and server " + version + " jars, presuming up-to-date");
			} else if (mergedJar.exists()) {
				//Strictly we don't need the split jars if the merged one exists, let's try go on
				project.getLogger().warn("Missing game jar(s) but merged " + version + " jar present, things might end badly");
			} else {
				throw new GradleException("Missing " + version + " jar(s); Client: " + clientJar.exists() + ", Server: " + serverJar.exists());
			}
		} else {
			if (needClient) MinecraftProvider.downloadJar(project.getLogger(), version, versionInfo, clientJar, "client");
			if (needServer) MinecraftProvider.downloadJar(project.getLogger(), version, versionInfo, serverJar, "server");
		}

		Function<MinecraftVersion, Callable<File>> mergeTask;
		switch (mergeOrder) {
		case FIRST:
			mergeTask = lock -> () -> {
				if (!mergedJar.exists()) {
					try {
						MinecraftProvider.mergeJars(project.getLogger(), clientJar, serverJar, mergedJar);
					} catch (ZipError e) {
						DownloadUtil.delete(clientJar);
						DownloadUtil.delete(serverJar);

						project.getLogger().error("Could not merge JARs! Deleting source JARs - please re-run the command and move on.", e);
						throw new RuntimeException("Error merging " + clientJar + " and " + serverJar, e);
					}
				}

				return mergedJar;
			};
			break;

		case LAST:
			mergeTask = lock -> () -> {//TODO: Account for Openfine (or any other jar interfering tasks) changing the merged jar name
				if (!mergedJar.exists()) {
					synchronized (lock) {
						while (lock.mappings == null) {
							lock.wait();
						}
					}

					Path interClient = extension.getUserCache().toPath().resolve(JarNameFactory.CLIENT_INTERMEDIARY.getJarName(version));
					if (Files.notExists(interClient)) {
						//Can't use the library provider yet as the configuration might need more things adding to it
						Set<File> libraries = lock.getJavaLibraries(project);
						remapJar(project.getLogger(), libraries, clientJar.toPath(), "client", lock.mappings.get(), interClient);
					}

					Path interServer = interClient.resolveSibling(JarNameFactory.SERVER_INTERMEDIARY.getJarName(version));
					if (Files.notExists(interServer)) {
						Set<File> libraries = Collections.emptySet(); //The server contains all its own dependencies
						remapJar(project.getLogger(), libraries, serverJar.toPath(), "server", lock.mappings.get(), interServer);
					}

					MinecraftProvider.mergeJars(project.getLogger(), interClient.toFile(), interServer.toFile(), mergedJar);
				}

				return mergedJar;
			};
			break;

		case CLIENT_ONLY:
			mergeTask = lock -> Callables.returning(clientJar);
			break;

		case SERVER_ONLY:
			mergeTask = lock -> Callables.returning(serverJar);
			break;

		case INDIFFERENT:
		default:
			throw new IllegalStateException("Unexpected jar merge order " + mergeOrder);
		}

		return new MinecraftVersion(versionInfo, clientJar, serverJar, mergeOrder, mergeTask);
	}

	public static Path makeInterJar(Project project, LoomGradleExtension extension, String version, Optional<Path> intermediaryMappings) throws IOException {
		MinecraftVersion versionInfo = makeMergedJar(project, extension, version, Optional.empty(), JarMergeOrder.INDIFFERENT);

		if (versionInfo.getMergeStrategy() == JarMergeOrder.LAST) {
			versionInfo.giveIntermediaries(Suppliers.memoize(() -> intermediaryMappings.orElseGet(() -> getIntermediaries(extension, version))));
			return versionInfo.getMergedJar().toPath();
		} else {
			return remapJar(project, extension, versionInfo, versionInfo.getMergedJar().toPath(), intermediaryMappings);
		}
	}

	public static Path remapCurrentJar(Project project, LoomGradleExtension extension, MinecraftProvider provider, Optional<Path> intermediaryMappings) {
		if (provider.getMergeStrategy() == JarMergeOrder.LAST) {
			provider.giveIntermediaries(Suppliers.memoize(() -> intermediaryMappings.orElseGet(() -> getIntermediaries(extension, provider.minecraftVersion))));
			return provider.getMergedJar().toPath();
		} else {
			//return remapJar(project, extension, provider.versionInfo, provider.getMergedJar().toPath(), intermediaryMappings);
			return null;
		}
	}

	public static Path remapJar(Project project, LoomGradleExtension extension, MinecraftVersion version, Path mergedJar, Optional<Path> intermediaryMappings) {
		Path remappedJar = extension.getUserCache().toPath().resolve(JarNameFactory.MERGED_INTERMEDIARY.getJarName(version.getName()));

		if (Files.notExists(remappedJar)) {
			remapJar(project, version, mergedJar, intermediaryMappings.orElseGet(() -> getIntermediaries(extension, version.getName())), remappedJar);
		}

		return remappedJar;
	}

	static Path getIntermediaries(LoomGradleExtension extension, String version) {
		File intermediaryNames = new File(extension.getUserCache(), "mappings/" + version + '/' + INTERMEDIARY + "-intermediary.tiny");

		if (!intermediaryNames.exists()) {
			try {
				FileUtils.copyURLToFile(new URL(SpecialCases.intermediaries(version)), intermediaryNames);
			} catch (IOException e) {
				throw new UncheckedIOException("Error downloading Intermediary mappings for " + version, e);
			}
		}

		return intermediaryNames.toPath();
	}

	private static void remapJar(Project project, MinecraftVersion version, Path mergedJar, Path intermediaryMappings, Path remappedJar) {
		remapJar(project.getLogger(), version.getJavaLibraries(project), mergedJar, "official", intermediaryMappings, remappedJar);
	}

	static void remapJar(Logger logger, Collection<File> libraries, Path originJar, String originMappings, Path intermediaryMappings, Path remappedJar) {
		logger.lifecycle("Remapping minecraft (TinyRemapper, " + originMappings + " -> intermediary)");

		TinyRemapper remapper = TinyRemapper.newRemapper()
				.withMappings(TinyUtils.createTinyMappingProvider(intermediaryMappings, originMappings, "intermediary"))
				.build();

		try (OutputConsumerPath outputConsumer = new OutputConsumerPath(remappedJar)) {
			remapper.readClassPath(libraries.stream().map(File::toPath).distinct().toArray(Path[]::new));
			remapper.readInputs(originJar);
			remapper.apply(outputConsumer);
			outputConsumer.addNonClassFiles(originJar, NonClassCopyMode.FIX_META_INF, remapper);
		} catch (IOException e) {
			throw new RuntimeException("Failed to remap JAR " + originJar + " with mappings from " + intermediaryMappings, e);
		} finally {
			remapper.finish();
		}
	}
}