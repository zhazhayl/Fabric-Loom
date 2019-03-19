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

package net.fabricmc.loom.util;


import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.providers.MinecraftJarProvider;
import net.fabricmc.loom.providers.MinecraftMappedProvider;
import net.fabricmc.mappings.ClassEntry;
import net.fabricmc.mappings.EntryTriple;
import net.fabricmc.mappings.Mappings;
import net.fabricmc.mappings.MethodEntry;
import net.fabricmc.stitch.util.Pair;
import net.fabricmc.loom.providers.MappingsProvider;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;

import org.apache.commons.io.IOUtils;
import com.google.common.io.Files;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

public class MapJarsTiny {

	public void mapJars(MinecraftJarProvider jarProvider, MinecraftMappedProvider mapProvider, Project project) throws IOException {
		String fromM = "official";

		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		MappingsProvider mappingsProvider = extension.getMappingsProvider();

		Path[] classpath = mapProvider.getMapperPaths().stream()
				.map(File::toPath)
				.toArray(Path[]::new);

		Path input = jarProvider.getMergedJar().toPath();
		Path outputMapped = mapProvider.getMappedJar().toPath();
		Path outputIntermediary = mapProvider.getIntermediaryJar().toPath();

		for (String toM : Arrays.asList("named", "intermediary")) {
			Path output = "named".equals(toM) ? outputMapped : outputIntermediary;

			project.getLogger().lifecycle(":remapping minecraft (TinyRemapper, " + fromM + " -> " + toM + ")");

			TinyRemapper remapper = TinyRemapper.newRemapper()
					.withMappings(mappingsProvider.mcRemappingFactory.apply(fromM, toM))
					.renameInvalidLocals(true)
					.rebuildSourceFilenames(true)
					.build();

			try (OutputConsumerPath outputConsumer = new OutputConsumerPath(output)) {
				outputConsumer.addNonClassFiles(input);
				remapper.read(input);
				remapper.read(classpath);
				remapper.apply(input, outputConsumer);
			} catch (Exception e) {
				throw new RuntimeException("Failed to remap JAR", e);
			} finally {
				remapper.finish();
			}
		}
	}

	public static void transform(Project project, Set<Pair<String, String>> ats, MinecraftMappedProvider jarProvider, MappingsProvider mappingProvider) throws IOException {
		project.getLogger().info("Reading in mappings...");

		Mappings mappings;
		try (InputStream in = new FileInputStream(mappingProvider.MAPPINGS_TINY)) {
			mappings = net.fabricmc.mappings.MappingsProvider.readTinyMappings(in, false);
		}

		project.getLogger().info("Read in " + mappings.getClassEntries() + " classes");
		project.getLogger().info("Working out what we have to do");

		final String wildcard = "<*>"; //Special marker for the class itself rather than a method
		Map<String, Set<String>> transforms = new HashMap<>();
		Map<String, Set<String>> interTransforms = new HashMap<>();

		Map<Boolean, Set<Pair<String, String>>> bits = ats.stream().collect(Collectors.partitioningBy(pair -> pair.getRight() != null, Collectors.toSet()));
		Set<String> rawClasses = bits.get(Boolean.FALSE).stream().map(Pair::getLeft).collect(Collectors.toSet());

		for (ClassEntry entry : mappings.getClassEntries()) {
			String named = entry.get("named");
			if (rawClasses.contains(named)) {
				rawClasses.remove(named);

				String inter = entry.get("intermediary");
				if (inter == null) throw new IllegalStateException("Missing intermediary name for " + named);
				transforms.computeIfAbsent(named, k -> new HashSet<>()).add(wildcard);
				interTransforms.computeIfAbsent(inter, k -> new HashSet<>()).add(wildcard);
			}
		}

		Map<String, Set<String>> methods = bits.get(Boolean.TRUE).stream().collect(Collectors.groupingBy(Pair::getLeft, Collectors.mapping(Pair::getRight, Collectors.toSet())));
		for (MethodEntry entry : mappings.getMethodEntries()) {
			EntryTriple named = entry.get("named");
			Set<String> targets = methods.get(named.getOwner());

			if (targets != null && targets.contains(named.getName() + named.getDesc())) {
				EntryTriple inter = entry.get("intermediary");
				if (inter == null) throw new IllegalStateException("Missing intermediary name for " + named);
				transforms.computeIfAbsent(named.getOwner(), k -> new HashSet<>()).add(named.getName() + named.getDesc());
				interTransforms.computeIfAbsent(inter.getOwner(), k -> new HashSet<>()).add(inter.getName() + inter.getDesc());

				targets.remove(named.getName() + named.getDesc());
				if (targets.isEmpty()) methods.remove(named.getOwner());
			}
		}

		if (!rawClasses.isEmpty() || !methods.isEmpty()) {
			project.getLogger().error("Unable to find mappings for the following entries in access transformer:");
			rawClasses.forEach(name -> project.getLogger().error('\t' + name));
			methods.forEach((key, value) -> {
				project.getLogger().error('\t' + key + ':');
				value.forEach(name -> project.getLogger().error("\t\t" + name));
			});
			throw new InvalidUserDataException("Invalid lines found within access transformer");
		}
		project.getLogger().info("Found " + transforms.size() + " classes that need tinkering with");
		project.getLogger().lifecycle(":transforming minecraft");

		//Move these out of the way as we need to make new jars in their place
		File intermediaryJar = preATmove(jarProvider.MINECRAFT_INTERMEDIARY_JAR);
		File namedJar = preATmove(jarProvider.MINECRAFT_MAPPED_JAR);

		project.getLogger().info("Transforming intermediary jar");
		doTheDeed(intermediaryJar, jarProvider.MINECRAFT_INTERMEDIARY_JAR, interTransforms, wildcard);
		project.getLogger().info("Transforming named jar");
		doTheDeed(namedJar, jarProvider.MINECRAFT_MAPPED_JAR, transforms, wildcard);

		project.getLogger().info("Transformation complete"); //Probably, successful is another matter
		intermediaryJar.delete();
		namedJar.delete(); //Done with these now
	}

	private static File preATmove(File jar) throws IOException {
		File moved = new File(jar.getParentFile(), "preAT-" + jar.getName());
		Files.move(jar, moved);
		return moved;
	}

	private static void doTheDeed(File from, File to, Map<String, Set<String>> transforms, String wildcard) throws IOException {
		try (JarFile jar = new JarFile(from); JarOutputStream out = new JarOutputStream(new FileOutputStream(to))) {
			for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements();) {
				JarEntry entry = entries.nextElement();

				InputStream in = jar.getInputStream(entry);
				String name = entry.getName();
				byte[] data = IOUtils.toByteArray(in);

				if (data != null && name.endsWith(".class")) {
					String className = name.substring(0, name.length() - 6);
					data = transform(data, transforms.get(className), wildcard);
				}

				JarEntry newEntry = new JarEntry(name);
				out.putNextEntry(newEntry);
				out.write(data);
				out.closeEntry();
			}
		}
	}


	private static byte[] transform(byte[] data, Set<String> transforms, String wildcard) {
		if (transforms == null || transforms.isEmpty()) return data;

		ClassNode clazz = new ClassNode();
        ClassReader reader = new ClassReader(data);
        reader.accept(clazz, 0);

        if (transforms.remove(wildcard)) {
        	clazz.access = flipBits(clazz.access);
        	//Remember to do the inner class attribute too (if there is one)
			for (InnerClassNode innerClass : clazz.innerClasses) {
				if (innerClass.name.equals(clazz.name)) {
					innerClass.access = flipBits(innerClass.access);
					break;
				}
			}
		}

        if (!transforms.isEmpty()) {
        	for (MethodNode method : clazz.methods) {
        		if (transforms.remove(method.name + method.desc)) {
        			method.access = flipBits(method.access);
        			//Technically speaking we should probably do INVOKESPECIAL -> INVOKEVIRTUAL for private -> public transforms
        			//But equally that's effort, so let's see how far we can get before it becomes an issue (from being lazy)
        			if (transforms.isEmpty()) break;
        		}
        	}
        }

        if (!transforms.isEmpty()) {//There's still more we never found, not so good that
        	throw new IllegalStateException("Ran through class " + clazz.name + " but couldn't find " + transforms);
        }

        ClassWriter writer = new ClassWriter(0);
        clazz.accept(writer);
        return writer.toByteArray();
	}

	private static final int ACCESSES = ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE);
	private static int flipBits(int access) {
		access &= ACCESSES;
		access |= Opcodes.ACC_PUBLIC;
		access &= ~Opcodes.ACC_FINAL;
		return access;
	}
}
