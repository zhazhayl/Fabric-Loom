package net.fabricmc.loom.providers.openfine;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

public class ClassReconstructor {
	public static byte[] reconstruct(byte[] original, byte[] modified) {
		ClassNode originalClass = read(original);
		ClassNode patchedClass = read(modified);

		assert Objects.equals(originalClass.name, patchedClass.name);
		assert Objects.equals(originalClass.superName, patchedClass.superName);
		assert Objects.equals(originalClass.signature, patchedClass.signature);

		AccessChange accessChange = AccessChange.forAccess(originalClass.access, patchedClass.access);
		FinalityChange finalityChange = FinalityChange.forAccess(originalClass.access, patchedClass.access);

		Set<String> gainedInterfaces, lostInterfaces;
		if (!Objects.equals(originalClass.interfaces, patchedClass.interfaces)) {
			gainedInterfaces = Sets.difference(ImmutableSet.copyOf(patchedClass.interfaces), ImmutableSet.copyOf(originalClass.interfaces));
			lostInterfaces = Sets.difference(ImmutableSet.copyOf(originalClass.interfaces), ImmutableSet.copyOf(patchedClass.interfaces));
		} else {
			gainedInterfaces = lostInterfaces = Collections.emptySet();
		}

		assert Objects.equals(originalClass.outerClass, patchedClass.outerClass);
		assert Objects.equals(originalClass.outerMethod, patchedClass.outerMethod);
		assert Objects.equals(originalClass.outerMethodDesc, patchedClass.outerMethodDesc);

		assert Objects.equals(originalClass.nestHostClass, patchedClass.nestHostClass);
		assert Objects.equals(originalClass.nestMembers, patchedClass.nestMembers);

		new MethodChanges(originalClass.name, originalClass.methods, patchedClass.methods);
		new FieldChanges(originalClass.name, originalClass.fields, patchedClass.fields);

		return null;
	}

	private static ClassNode read(byte[] data) {
		ClassNode node = new ClassNode();
		new ClassReader(data).accept(node, ClassReader.EXPAND_FRAMES);
		return node;
	}

	private static byte[] write(ClassNode node) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		return writer.toByteArray();
	}
}