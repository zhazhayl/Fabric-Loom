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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.dependencies.DependencyProvider;
import net.fabricmc.loom.dependencies.LogicalDependencyProvider;
import net.fabricmc.loom.util.Constants;

public class LaunchProvider extends LogicalDependencyProvider {
	@Override
	public Set<Class<? extends DependencyProvider>> getDependencies() {
		return ImmutableSet.of(MinecraftProvider.class, MinecraftLibraryProvider.class);
	}

	@Override
	public void provide(Project project, LoomGradleExtension extension, Consumer<Runnable> postPopulationScheduler) throws IOException {
		File remapClasspath = new File(extension.getDevLauncherConfig().getParentFile(), "remapClasspath.txt");

		File log4jConfig = new File(extension.getDevLauncherConfig().getParentFile(), "log4j.xml");
		writeLog4jConfig("/log4j2.fabric.xml", log4jConfig);

		final LaunchConfig launchConfig = new LaunchConfig()
				.property("fabric.development", "true")
				.property("fabric.remapClasspathFile", remapClasspath.getAbsolutePath())
				.property("log4j.configurationFile", log4jConfig.getAbsolutePath())

				.argument("client", "--assetIndex")
				.argument("client", extension.getMinecraftProvider().getAssetIndex().getFabricId(extension.getMinecraftProvider().minecraftVersion))
				.argument("client", "--assetsDir")
				.argument("client", new File(extension.getUserCache(), "assets").getAbsolutePath());

		if (extension.extractNatives()) {
			launchConfig.property("client", "java.library.path", extension.getNativesDirectory().getAbsolutePath())
						.property("client", "org.lwjgl.librarypath", extension.getNativesDirectory().getAbsolutePath());
		}

		//Enable ansi by default for idea and vscode
		if (Arrays.stream(project.getRootDir().listFiles()).map(File::getName).anyMatch(file -> ".vscode".equals(file) || ".idea".equals(file) || file.endsWith(".iws"))) {
			launchConfig.property("fabric.log.disableAnsi", "false");
		}

		FileUtils.writeStringToFile(extension.getDevLauncherConfig(), launchConfig.asString(), StandardCharsets.UTF_8);

		addDependency("net.fabricmc:dev-launch-injector:" + Constants.DEV_LAUNCH_INJECTOR_VERSION, project, JavaPlugin.RUNTIME_ONLY_CONFIGURATION_NAME);
		addDependency("net.minecrell:terminalconsoleappender:" + Constants.TERMINAL_CONSOLE_APPENDER_VERSION, project, JavaPlugin.RUNTIME_ONLY_CONFIGURATION_NAME);

		postPopulationScheduler.accept(() -> {
			MinecraftMappedProvider mappedProvider = getProvider(MinecraftMappedProvider.class);
			String classpath = Streams.concat(Stream.of(mappedProvider.getIntermediaryJar()), mappedProvider.getMapperPaths().stream(),
									getProvider(MappedModsProvider.class).getClasspath().stream()).map(File::getAbsolutePath).collect(Collectors.joining(File.pathSeparator));

			try {
				if (remapClasspath.exists() && classpath.equals(FileUtils.readFileToString(remapClasspath, StandardCharsets.UTF_8))) return;

				FileUtils.writeStringToFile(remapClasspath, classpath, StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new UncheckedIOException("Failed to generate remap classpath", e);
			}
		});
	}

	private static void writeLog4jConfig(String xml, File log4jConfig) throws IOException {
		if (log4jConfig.exists()) {
			try (InputStream expected = LaunchProvider.class.getResourceAsStream(xml); InputStream actual = new FileInputStream(log4jConfig)) {
				if (IOUtils.contentEquals(expected, actual)) return;
			}
		}

		try (InputStream config = LaunchProvider.class.getResourceAsStream(xml)) {
			FileUtils.copyToFile(config, log4jConfig);
		}
	}

	public static class LaunchConfig {
		private final Map<String, List<String>> values = new HashMap<>();

		public LaunchConfig property(String key, String value) {
			return property("common", key, value);
		}

		public LaunchConfig property(String side, String key, String value) {
			values.computeIfAbsent(side + "Properties", s -> new ArrayList<>())
					.add(String.format("%s=%s", key, value));
			return this;
		}

		public LaunchConfig argument(String value) {
			return argument("common", value);
		}

		public LaunchConfig argument(String side, String value) {
			values.computeIfAbsent(side + "Args", s -> new ArrayList<>())
					.add(value);
			return this;
		}

		public String asString() {
			StringJoiner stringJoiner = new StringJoiner("\n");

			for (Map.Entry<String, List<String>> entry : values.entrySet()) {
				stringJoiner.add(entry.getKey());

				for (String s : entry.getValue()) {
					stringJoiner.add("\t" + s);
				}
			}

			return stringJoiner.toString();
		}
	}
}
