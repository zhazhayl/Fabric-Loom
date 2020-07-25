/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers.openfine;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.gradle.api.InvalidUserDataException;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

public class OptiFineVersion {
	public final String version, minecraftVersion;
	public final boolean isInstaller;

	public OptiFineVersion(File file) throws IOException {
		ClassNode classNode = new ClassNode();
		boolean isInstaller = false;
		try (JarFile jarFile = new JarFile(file)) {
			JarEntry entry = jarFile.getJarEntry("net/optifine/Config.class");
			if (entry == null) entry = jarFile.getJarEntry("Config.class"); //Could be an old Optifine version

			if (entry == null) {
				throw new InvalidUserDataException("Invalid OptiFine jar, could not find Config");
			}

			ClassReader classReader = new ClassReader(jarFile.getInputStream(entry));
			classReader.accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);

			for (Enumeration<JarEntry> it = jarFile.entries(); it.hasMoreElements();) {
				if (it.nextElement().getName().startsWith("patch/")) {
					isInstaller = true;
					break;
				}
			}
		}

		String version = null, minecraftVersion = null;
		for (FieldNode fieldNode : classNode.fields) {
			if ("VERSION".equals(fieldNode.name)) {
				version = (String) fieldNode.value;
			} else if ("MC_VERSION".equals(fieldNode.name)) {
				minecraftVersion = (String) fieldNode.value;
			}
		}

		if (version == null || version.isEmpty() || minecraftVersion == null || minecraftVersion.isEmpty()) {
			throw new IllegalStateException("Unable to find OptiFine version information for " + file + " (only found '" + version + "' for '" + minecraftVersion + "')");
		}

		this.version = version;
		this.minecraftVersion = minecraftVersion;
		this.isInstaller = isInstaller;
	}

	public boolean supports(String minecraftVersion) {
		return this.minecraftVersion.equals(minecraftVersion);
	}
}