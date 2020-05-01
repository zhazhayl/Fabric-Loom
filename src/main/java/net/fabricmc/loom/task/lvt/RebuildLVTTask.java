/*
 * Copyright 2020 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.task.lvt;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map.Entry;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import org.gradle.api.Project;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.TaskAction;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.transform.ByteArrayZipEntryTransformer;
import org.zeroturnaround.zip.transform.ZipEntryTransformerEntry;

import com.google.common.collect.Iterators;
import com.google.common.collect.Streams;

import net.fabricmc.loom.task.AbstractLoomTask;
import net.fabricmc.loom.util.progress.ProgressLogger;

public class RebuildLVTTask extends AbstractLoomTask {
	private Object input;

	@TaskAction
	public void doTask() throws Throwable {
		Project project = getProject();

		project.getLogger().info(":Rebuilding local variable table");

		ProgressLogger progressLogger = ProgressLogger.getProgressFactory(project, RebuildLVTTask.class.getName());
		progressLogger.start("Rebuilding local variable table", "LVT Rebuild");

		ZipUtil.transformEntries(getInput(), getTransformers(progressLogger));

		progressLogger.completed();
	}

	private ZipEntryTransformerEntry[] getTransformers(ProgressLogger logger) throws ZipException, IOException {
		try (ZipFile jar = new ZipFile(getInput())) {
			return Streams.stream(Iterators.forEnumeration(jar.entries())).map(ZipEntry::getName).filter(name -> name.endsWith(".class")).distinct().map(className -> {
				return new ZipEntryTransformerEntry(className, new ByteArrayZipEntryTransformer() {
					@Override
					protected byte[] transform(ZipEntry zipEntry, byte[] input) throws IOException {
						logger.progress("Remapping " + className.substring(0, className.length() - 6));

						ClassNode node = new ClassNode();
						new ClassReader(input).accept(node, ClassReader.EXPAND_FRAMES);

						for (Entry<MethodNode, List<LocalVariableNode>> entry : LocalTableRebuilder.generateLocalVariableTable(node).entrySet()) {
							entry.getKey().localVariables = entry.getValue();
						}

						ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
						node.accept(writer);
						return writer.toByteArray();
					}

					@Override
					protected boolean preserveTimestamps() {
						return true; //Why not?
					}
				});
			}).toArray(ZipEntryTransformerEntry[]::new);
		}
	}

	@InputFile
	public File getInput() {
		return getProject().file(input);
	}

	public void setInput(Object input) {
		this.input = input;
	}
}