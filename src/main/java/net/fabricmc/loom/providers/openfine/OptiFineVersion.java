package net.fabricmc.loom.providers.openfine;

import java.io.File;
import java.io.IOException;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.gradle.api.InvalidUserDataException;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import org.zeroturnaround.zip.ZipBreakException;
import org.zeroturnaround.zip.ZipUtil;

public class OptiFineVersion {
	public final String version, minecraftVersion;
	public final boolean isInstaller;

	public OptiFineVersion(File file) throws IOException {
		ClassNode classNode = new ClassNode();
		try (JarFile jarFile = new JarFile(file)) {
			JarEntry entry = jarFile.getJarEntry("Config.class");
			if (entry == null) {
				throw new InvalidUserDataException("Invalid OptiFine jar, could not find Config");
			}

			ClassReader classReader = new ClassReader(jarFile.getInputStream(entry));
			classReader.accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
		}

		String version = null, minecraftVersion = null;
		for (FieldNode fieldNode : classNode.fields) {
			if (fieldNode.name.equals("VERSION")) {
				version = (String) fieldNode.value;
			}
			if (fieldNode.name.equals("MC_VERSION")) {
				minecraftVersion = (String) fieldNode.value;
			}
		}

		if (version == null || version.isEmpty() || minecraftVersion == null || minecraftVersion.isEmpty()) {
			throw new IllegalStateException("Unable to find OptiFine version information for " + file + " (only found '" + version + "' for '" + minecraftVersion + "')");
		}

		this.version = version;
		this.minecraftVersion = minecraftVersion;

		boolean[] wrap = new boolean[] {false};
		ZipUtil.iterate(file, (in, entry) -> {
			if (entry.getName().startsWith("patch/")) {
				wrap[0] = true;
				throw new ZipBreakException();
			}
		});
		isInstaller = wrap[0];
	}

	public boolean supports(String minecraftVersion) {
		return this.minecraftVersion.equals(minecraftVersion);
	}
}