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
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import com.google.gson.Gson;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradleExtension.JarMergeOrder;
import net.fabricmc.loom.dependencies.LoomDependencyManager;
import net.fabricmc.loom.dependencies.PhysicalDependencyProvider;
import net.fabricmc.loom.providers.SnappyRemapper.MinecraftVersion;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DownloadUtil;
import net.fabricmc.loom.util.ManifestVersion;
import net.fabricmc.loom.util.MinecraftVersionInfo;
import net.fabricmc.loom.util.MinecraftVersionInfo.AssetIndex;
import net.fabricmc.loom.util.MinecraftVersionInfo.Library;
import net.fabricmc.loom.util.StaticPathWatcher;
import net.fabricmc.stitch.merge.JarMerger;

public class MinecraftProvider extends PhysicalDependencyProvider {
	private static final Map<String, MinecraftVersion> VERSION_TO_VERSION = new ConcurrentHashMap<>();
	private static final byte DOWNLOAD_ATTEMPTS = 3;
	static final Gson GSON = new Gson();

	public String minecraftVersion;
	private MinecraftVersion version;

	@Override
	public void register(LoomDependencyManager dependencyManager) {
		super.register(dependencyManager);

		dependencyManager.addProvider(new MinecraftLibraryProvider());
	}

	@Override
	public void provide(DependencyInfo dependency, Project project, LoomGradleExtension extension, Consumer<Runnable> postPopulationScheduler) throws Exception {
		minecraftVersion = dependency.getDependency().getVersion();

		version = VERSION_TO_VERSION.computeIfAbsent(minecraftVersion, version -> {
			try {
				return SnappyRemapper.makeMergedJar(project, extension, version, Optional.ofNullable(extension.customManifest), extension.getJarMergeOrder());
			} catch (IOException e) {
				throw new UncheckedIOException("Error fetching Minecraft " + version + " jar", e);
			}
		});
	}

	public static File downloadMcJson(Logger logger, LoomGradleExtension extension, String minecraftVersion, boolean offline, Optional<String> customManifest) throws IOException {
		File MINECRAFT_JSON = new File(extension.getUserCache(), "minecraft-" + minecraftVersion + "-info.json");

		if (offline) {
			if (MINECRAFT_JSON.exists()) {
				//If there is the manifest already we'll presume that's good enough
				logger.debug("Found Minecraft {} manifest, presuming up-to-date", minecraftVersion);
			} else {
				//If we don't have the manifests then there's nothing more we can do
				throw new GradleException("Minecraft " + minecraftVersion + " manifest not found at " + MINECRAFT_JSON.getAbsolutePath());
			}
		} else {
			Optional<String> versionURL;
			if (customManifest.isPresent()) {
				logger.lifecycle("Using custom minecraft manifest");

				versionURL = customManifest;
			} else {
				File manifests = new File(extension.getUserCache(), "version_manifest.json");

				logger.debug("Downloading version manifests");
				DownloadUtil.downloadIfChanged(new URL("https://launchermeta.mojang.com/mc/game/version_manifest.json"), manifests, logger);

				try (Reader versionManifest = Files.newReader(manifests, StandardCharsets.UTF_8)) {
					ManifestVersion mcManifest = GSON.fromJson(versionManifest, ManifestVersion.class);
					versionURL = mcManifest.versions.stream().filter(versions -> versions.id.equalsIgnoreCase(minecraftVersion)).findFirst().map(version -> version.url);
				}

				out: if (!versionURL.isPresent()) {
					String url;
					switch (minecraftVersion) {
					case "1.14.3 - Combat Test":
						logger.warn("Using old name for first Combat Test, should use 1.14_combat-212796 instead");
					case "1.14_combat-212796": //Combat Test 1
						//Extracted from https://launcher.mojang.com/experiments/combat/610f5c9874ba8926d5ae1bcce647e5f0e6e7c889/1_14_combat-212796.zip
						url = "https://gist.github.com/Chocohead/b62b1e94d8d4b32ef3326df63cf407f2/raw/bdc1f968b3529ebca9b197a5b2612f57b6354624/1.14_combat-212796.json";

					case "1.14_combat-0": //Combat Test 2
						//Extracted from https://launcher.mojang.com/experiments/combat/d164bb6ecc5fca9ac02878c85f11befae61ac1ca/1_14_combat-0.zip
						url = "https://gist.github.com/Chocohead/b62b1e94d8d4b32ef3326df63cf407f2/raw/bdc1f968b3529ebca9b197a5b2612f57b6354624/1.14_combat-0.json";
						break;

					case "1.14_combat-3": //Combat Test 3
						//Extracted from https://launcher.mojang.com/experiments/combat/0f209c9c84b81c7d4c88b4632155b9ae550beb89/1_14_combat-3.zip
						url = "https://gist.github.com/Chocohead/b62b1e94d8d4b32ef3326df63cf407f2/raw/bdc1f968b3529ebca9b197a5b2612f57b6354624/1.14_combat-3.json";
						break;

					case "1.15_combat-1": //Combat Test 4
						//Extracted from https://launcher.mojang.com/experiments/combat/ac11ea96f3bb2fa2b9b76ab1d20cacb1b1f7ef60/1_15_combat-1.zip
						url = "https://gist.github.com/Chocohead/b62b1e94d8d4b32ef3326df63cf407f2/raw/bdc1f968b3529ebca9b197a5b2612f57b6354624/1.15_combat-1.json";
						break;

					case "1_15_combat-6": //Combat Test 5
						//Extracted from https://launcher.mojang.com/experiments/combat/52263d42a626b40c947e523128f7a195ec5af76a/1_15_combat-6.zip
						url = "https://gist.github.com/Chocohead/b62b1e94d8d4b32ef3326df63cf407f2/raw/bdc1f968b3529ebca9b197a5b2612f57b6354624/1.15_combat-6.json";
						break;

					default:
						break out;
					}

					versionURL = Optional.of(url);
				}
			}

			if (versionURL.isPresent()) {
				if (StaticPathWatcher.INSTANCE.hasFileChanged(MINECRAFT_JSON.toPath())) {
					logger.debug("Downloading Minecraft {} manifest", minecraftVersion);
					DownloadUtil.downloadIfChanged(new URL(versionURL.get()), MINECRAFT_JSON, logger);
					StaticPathWatcher.INSTANCE.resetFile(MINECRAFT_JSON.toPath());
				}
			} else {
				throw new RuntimeException("Failed to find minecraft version: " + minecraftVersion);
			}
		}

		return MINECRAFT_JSON;
	}

	public static void downloadJar(Logger logger, String minecraftVersion, MinecraftVersionInfo versionInfo, File to, String name) throws IOException {
		downloadJar(logger, minecraftVersion, new URL(versionInfo.downloads.get(name).url), to, name, versionInfo.downloads.get(name).sha1);
	}

	private static void downloadJar(Logger logger, String minecraftVersion, URL from, File to, String name, String hash) throws IOException {
		if (!to.exists() || !Checksum.equals(to, hash) && StaticPathWatcher.INSTANCE.hasFileChanged(to.toPath())) {
			logger.debug("Downloading Minecraft {} {} jar", minecraftVersion, name);

			int attempt = 1;
			do {
				DownloadUtil.delete(to); //Clear the existing (wrong) contents out of the way
				DownloadUtil.downloadIfChanged(from, to, logger);
			} while (attempt++ <= DOWNLOAD_ATTEMPTS && !Checksum.equals(to, hash));

			if (attempt > DOWNLOAD_ATTEMPTS) {//Apparently we just couldn't get a jar which had the right hash
				throw new IllegalStateException("Unable to successfully download an intact " + minecraftVersion + ' ' + name + " jar!");
			}

			StaticPathWatcher.INSTANCE.resetFile(to.toPath());
		}
	}

	void giveIntermediaries(Supplier<Path> mappings) {
		version.giveIntermediaries(mappings);
	}

	public static void mergeJars(Logger logger, File MINECRAFT_CLIENT_JAR, File MINECRAFT_SERVER_JAR, File MINECRAFT_MERGED_JAR) throws IOException {
		logger.lifecycle(":merging jars");

		try (JarMerger jarMerger = new JarMerger(MINECRAFT_CLIENT_JAR, MINECRAFT_SERVER_JAR, MINECRAFT_MERGED_JAR)) {
			jarMerger.enableSyntheticParamsOffset();
			jarMerger.merge();
		}
	}

	public JarMergeOrder getMergeStrategy() {
		return version.getMergeStrategy();
	}

	public Set<String> getNeededHeaders() {
		switch (version.getMergeStrategy()) {
		case FIRST:
			return ImmutableSet.of("official", "intermediary");

		case LAST:
			return ImmutableSet.of("client", "server", "intermediary");

		case CLIENT_ONLY:
			return ImmutableSet.of("client", "intermediary");

		case SERVER_ONLY:
			return ImmutableSet.of("server", "intermediary");

		case INDIFFERENT:
		default:
			throw new IllegalStateException("Unexpected jar merge order " + version.getMergeStrategy());
		}
	}

	public File getMergedJar() {
		return version.getMergedJar();
	}

	public Collection<Library> getLibraries() {
		return version.getLibraries();
	}

	public MinecraftLibraryProvider getLibraryProvider() {
		return getProvider(MinecraftLibraryProvider.class);
	}

	public AssetIndex getAssetIndex() {
		return version.getAssetIndex();
	}

	@Override
	public String getTargetConfig() {
		return Constants.MINECRAFT;
	}

	@Override
	public boolean isRequired() {
		return true;
	}

	@Override
	public boolean isUnique() {
		return true;
	}
}
