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
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.zip.ZipError;

import com.google.common.io.Files;
import com.google.gson.Gson;

import org.gradle.api.GradleException;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;

import net.fabricmc.loom.AbstractPlugin;
import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.dependencies.LoomDependencyManager;
import net.fabricmc.loom.dependencies.PhysicalDependencyProvider;
import net.fabricmc.loom.providers.openfine.Openfine;
import net.fabricmc.loom.util.Checksum;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DownloadUtil;
import net.fabricmc.loom.util.ManifestVersion;
import net.fabricmc.loom.util.MinecraftVersionInfo;
import net.fabricmc.loom.util.StaticPathWatcher;
import net.fabricmc.stitch.merge.JarMerger;

public class MinecraftProvider extends PhysicalDependencyProvider {
	private final Gson gson = new Gson();
	public String minecraftVersion;
	public MinecraftVersionInfo versionInfo;

	private File MINECRAFT_JSON;
	private File MINECRAFT_CLIENT_JAR;
	private File MINECRAFT_SERVER_JAR;
	private File MINECRAFT_MERGED_JAR;

	@Override
	public void register(LoomDependencyManager dependencyManager) {
		super.register(dependencyManager);

		dependencyManager.addProvider(new MinecraftLibraryProvider());
	}

	@Override
	public void provide(DependencyInfo dependency, Project project, LoomGradleExtension extension, Consumer<Runnable> postPopulationScheduler) throws Exception {
		minecraftVersion = dependency.getDependency().getVersion();
		boolean offline = project.getGradle().getStartParameter().isOffline();

		initFiles(project);

		downloadMcJson(project, offline);

		try (FileReader reader = new FileReader(MINECRAFT_JSON)) {
			versionInfo = gson.fromJson(reader, MinecraftVersionInfo.class);
		}

		if (offline) {
			if (MINECRAFT_CLIENT_JAR.exists() && MINECRAFT_SERVER_JAR.exists()) {
				project.getLogger().debug("Found client and server jars, presuming up-to-date");
			} else if (MINECRAFT_MERGED_JAR.exists()) {
				//Strictly we don't need the split jars if the merged one exists, let's try go on
				project.getLogger().warn("Missing game jar but merged jar present, things might end badly");
			} else {
				throw new GradleException("Missing jar(s); Client: " + MINECRAFT_CLIENT_JAR.exists() + ", Server: " + MINECRAFT_SERVER_JAR.exists());
			}
		} else {
			downloadJars(project.getLogger());
		}

		if (extension.hasOptiFine()) {
			MINECRAFT_CLIENT_JAR = Openfine.process(project.getLogger(), minecraftVersion, MINECRAFT_CLIENT_JAR, MINECRAFT_SERVER_JAR, extension.getOptiFine());
			MINECRAFT_MERGED_JAR = new File(MINECRAFT_CLIENT_JAR.getParentFile(), MINECRAFT_CLIENT_JAR.getName().replace("client", "merged"));
			project.getDependencies().add(Constants.MINECRAFT_DEPENDENCIES, project.getDependencies().module("com.github.Chocohead:OptiSine:" + Openfine.VERSION));
			AbstractPlugin.addMavenRepo(project, "Jitpack", "https://jitpack.io/"); //Needed to fetch OptiSine from
		}

		if (!MINECRAFT_MERGED_JAR.exists()) {
			try {
				mergeJars(project.getLogger());
			} catch (ZipError e) {
				DownloadUtil.delete(MINECRAFT_CLIENT_JAR);
				DownloadUtil.delete(MINECRAFT_SERVER_JAR);

				project.getLogger().error("Could not merge JARs! Deleting source JARs - please re-run the command and move on.", e);
				throw new RuntimeException();
			}
		}
	}

	private void initFiles(Project project) {
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		MINECRAFT_JSON = new File(extension.getUserCache(), "minecraft-" + minecraftVersion + "-info.json");
		MINECRAFT_CLIENT_JAR = new File(extension.getUserCache(), "minecraft-" + minecraftVersion + "-client.jar");
		MINECRAFT_SERVER_JAR = new File(extension.getUserCache(), "minecraft-" + minecraftVersion + "-server.jar");
		MINECRAFT_MERGED_JAR = new File(extension.getUserCache(), "minecraft-" + minecraftVersion + "-merged.jar");
	}

	private void downloadMcJson(Project project, boolean offline) throws IOException {
		if (offline) {
			if (MINECRAFT_JSON.exists()) {
				//If there is the manifest already we'll presume that's good enough
				project.getLogger().debug("Found Minecraft {} manifest, presuming up-to-date", minecraftVersion);
			} else {
				//If we don't have the manifests then there's nothing more we can do
				throw new GradleException("Minecraft " + minecraftVersion + " manifest not found at " + MINECRAFT_JSON.getAbsolutePath());
			}
		} else {
			LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);

			Optional<String> versionURL;
			if (extension.customManifest != null) {
				project.getLogger().lifecycle("Using custom minecraft manifest");

				versionURL = Optional.of(extension.customManifest);
			} else {
				File manifests = new File(extension.getUserCache(), "version_manifest.json");

				project.getLogger().debug("Downloading version manifests");
				DownloadUtil.downloadIfChanged(new URL("https://launchermeta.mojang.com/mc/game/version_manifest.json"), manifests, project.getLogger());

				try (Reader versionManifest = Files.newReader(manifests, StandardCharsets.UTF_8)) {
					ManifestVersion mcManifest = gson.fromJson(versionManifest, ManifestVersion.class);
					versionURL = mcManifest.versions.stream().filter(versions -> versions.id.equalsIgnoreCase(minecraftVersion)).findFirst().map(version -> version.url);
				}

				out: if (!versionURL.isPresent()) {
					String url;
					switch (minecraftVersion) {
					case "1.14.3 - Combat Test":
					case "1.14_combat-212796": //Combat Test 1
						//Extracted from https://launcher.mojang.com/experiments/combat/610f5c9874ba8926d5ae1bcce647e5f0e6e7c889/1_14_combat-212796.zip
						url = "https://gist.github.com/Chocohead/b62b1e94d8d4b32ef3326df63cf407f2/raw/bdc1f968b3529ebca9b197a5b2612f57b6354624/1.14_combat-212796.json";

						//No Intermediaries as https://github.com/FabricMC/intermediary/pull/7 was never fully merged
						throw new InvalidUserDataException("No Intermediaries for first combat snapshot!");

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
					project.getLogger().debug("Downloading Minecraft {} manifest", minecraftVersion);
					DownloadUtil.downloadIfChanged(new URL(versionURL.get()), MINECRAFT_JSON, project.getLogger());
					StaticPathWatcher.INSTANCE.resetFile(MINECRAFT_JSON.toPath());
				}
			} else {
				throw new RuntimeException("Failed to find minecraft version: " + minecraftVersion);
			}
		}
	}

	private void downloadJars(Logger logger) throws IOException {
		if (!MINECRAFT_CLIENT_JAR.exists() || !Checksum.equals(MINECRAFT_CLIENT_JAR, versionInfo.downloads.get("client").sha1) && StaticPathWatcher.INSTANCE.hasFileChanged(MINECRAFT_CLIENT_JAR.toPath())) {
			logger.debug("Downloading Minecraft {} client jar", minecraftVersion);
			DownloadUtil.downloadIfChanged(new URL(versionInfo.downloads.get("client").url), MINECRAFT_CLIENT_JAR, logger);
			StaticPathWatcher.INSTANCE.resetFile(MINECRAFT_CLIENT_JAR.toPath());
		}

		if (!MINECRAFT_SERVER_JAR.exists() || !Checksum.equals(MINECRAFT_SERVER_JAR, versionInfo.downloads.get("server").sha1) && StaticPathWatcher.INSTANCE.hasFileChanged(MINECRAFT_SERVER_JAR.toPath())) {
			logger.debug("Downloading Minecraft {} server jar", minecraftVersion);
			DownloadUtil.downloadIfChanged(new URL(versionInfo.downloads.get("server").url), MINECRAFT_SERVER_JAR, logger);
			StaticPathWatcher.INSTANCE.resetFile(MINECRAFT_SERVER_JAR.toPath());
		}
	}

	private void mergeJars(Logger logger) throws IOException {
		logger.lifecycle(":merging jars");

		try (JarMerger jarMerger = new JarMerger(MINECRAFT_CLIENT_JAR, MINECRAFT_SERVER_JAR, MINECRAFT_MERGED_JAR)) {
			jarMerger.enableSyntheticParamsOffset();
			jarMerger.merge();
		}
	}

	public File getMergedJar() {
		return MINECRAFT_MERGED_JAR;
	}

	public MinecraftLibraryProvider getLibraryProvider() {
		return getProvider(MinecraftLibraryProvider.class);
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
