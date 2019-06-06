package net.fabricmc.loom.providers.openfine;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

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

		annotate(patchedClass, accessChange, finalityChange, gainedInterfaces, lostInterfaces);

		MethodChanges methodChanges = new MethodChanges(originalClass.name, originalClass.methods, patchedClass.methods);
		if (methodChanges.couldNeedLambdasFixing()) {
			Map<String, String> lambdaFixes = new HashMap<>();
			methodChanges.tryFixLambdas(lambdaFixes);

			if (!lambdaFixes.isEmpty()) fixLambdas(lambdaFixes, methodChanges.modifiedMethods());
		}
		methodChanges.annotate();

		FieldChanges fieldChanges = new FieldChanges(originalClass.name, originalClass.fields, patchedClass.fields);
		fieldChanges.annotate();

		return write(patchedClass);
	}

	private static ClassNode read(byte[] data) {
		ClassNode node = new ClassNode();
		new ClassReader(data).accept(node, ClassReader.EXPAND_FRAMES);
		return node;
	}

	private static void annotate(ClassNode node, AccessChange accessChange, FinalityChange finalityChange, Set<String> gainedInterfaces, Set<String> lostInterfaces) {
		if (accessChange != AccessChange.NONE) {

		}

		if (finalityChange != FinalityChange.NONE) {

		}

		if (!gainedInterfaces.isEmpty()) {

		}

		if (!lostInterfaces.isEmpty()) {

		}
	}

	private static void fixLambdas(Map<String, String> fixes, Stream<MethodNode> methods) {
		methods.forEach(method -> MethodComparison.findLambdas(method.instructions, 0, idin -> {
			Handle handle = (Handle) idin.bsmArgs[1];
			String remap = fixes.get(handle.getOwner() + '#' + handle.getName() + handle.getDesc());

			if (remap != null) {
				int split = remap.indexOf('#');
				String owner = remap.substring(0, split);
				remap = remap.substring(split + 1);

				split = remap.indexOf('(');
				String name = remap.substring(0, split);
				String desc = remap.substring(split);

				idin.bsmArgs[1] = new Handle(handle.getTag(), owner, name, desc, handle.isInterface());
			}
		}));
	}

	private static byte[] write(ClassNode node) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		return writer.toByteArray();
	}
}