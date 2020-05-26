/*
 * Copyright 2020 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradleExtension.JarMergeOrder;
import net.fabricmc.tinyremapper.TinyRemapper.Builder;

public interface MinecraftVersionAdaptable {
	/** The name of the Minecraft version */
	String getName();

	/** The collection of libraries needed to run the jar */
	Set<File> getJavaLibraries(Project project);

	/** The merged jar for the version, will act differently depending on the {@link #getMergeStrategy() merge strategy} */
	Path getMergedJar();

	/** The way the {@link #getMergedJar() merged jar} was made */
	JarMergeOrder getMergeStrategy();

	/** The set of namespaces the native Minecraft jars will use (based on the {@link #getMergeStrategy() merge strategy}) */
	default Set<String> getNativeHeaders() {
		return getMergeStrategy().getNativeHeaders();
	}

	/** The set of namespaces an Intermediary mapping file should contain for the given {@link #getMergeStrategy() merge strategy} */
	default List<String> getNeededHeaders() {
		return getMergeStrategy().getNeededHeaders();
	}

	/** Whether {@link #giveIntermediaries(Path)} needs to be called before {@link #getMergedJar()} can be */
	boolean needsIntermediaries();

	/** Provide Intermediaries for making the {@link #getMergedJar() merged jar} ({@link #needsIntermediaries() if needed}) */
	void giveIntermediaries(Path mappings);

	/** Whether to use {@link Builder#ignoreConflicts(boolean)} when remapping the jar */
	default boolean bulldozeMappings(Project project, LoomGradleExtension extension) {
		return false;
	}
}