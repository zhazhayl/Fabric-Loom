/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers.openfine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.common.util.concurrent.Runnables;

public class MethodChanges {
	private final String className;
	private final List<MethodComparison> commonMethods = new ArrayList<>();
	private final List<MethodNode> lostMethods = new ArrayList<>();
	private final List<MethodNode> gainedMethods = new ArrayList<>();

	public MethodChanges(String className, List<MethodNode> original, List<MethodNode> patched) {
		Map<String, MethodNode> originalMethods = original.stream().collect(Collectors.toMap(method -> method.name + method.desc, Function.identity()));
		Map<String, MethodNode> patchedMethods = patched.stream().collect(Collectors.toMap(method -> method.name + method.desc, Function.identity()));

		for (String methodName : Sets.union(originalMethods.keySet(), patchedMethods.keySet())) {
			MethodNode originalMethod = originalMethods.get(methodName);
			MethodNode patchedMethod = patchedMethods.get(methodName);

			if (originalMethod != null) {
				if (patchedMethod != null) {//Both have the method
					commonMethods.add(new MethodComparison(originalMethod, patchedMethod));
				} else {//Just the original has the method
					lostMethods.add(originalMethod);
				}
			} else if (patchedMethod != null) {//Just the modified has the method
				gainedMethods.add(patchedMethod);
			} else {//Neither have the method?!
				throw new IllegalStateException("Unable to find " + methodName + " in either " + className + " versions");
			}
		}

		this.className = className;
		sortModifiedMethods(patched);
		lostMethods.sort(Comparator.comparingInt(original::indexOf));
		gainedMethods.sort(Comparator.comparingInt(patched::indexOf));
	}

	void sortModifiedMethods(List<MethodNode> patched) {
		commonMethods.sort(Comparator.comparingInt(method -> !"<clinit>".equals(method.node.name) ? patched.indexOf(method.node) : "com/mojang/blaze3d/platform/GLX".equals(className) ? patched.size() : -1));
	}

	public boolean couldNeedLambdasFixing() {
		return commonMethods.stream().anyMatch(method -> !method.equal && method.hasLambdas()) && !lostMethods.isEmpty() && !gainedMethods.isEmpty();
	}

	public boolean tryFixLambdas(Map<String, String> fixes) {
		List<MethodNode> gainedLambdas = gainedMethods.stream().filter(method -> (method.access & Opcodes.ACC_SYNTHETIC) != 0 && method.name.startsWith("lambda$")).collect(Collectors.toList());
		if (gainedLambdas.isEmpty()) return true; //Nothing looks like a lambda

		Map<String, MethodNode> possibleLambdas = gainedLambdas.stream().collect(Collectors.toMap(method -> method.name.concat(method.desc), Function.identity())); //The collection of lambdas we're looking to fix, any others are irrelevant from the point of view that they're probably fine
		Map<String, MethodNode> nameToLosses = lostMethods.stream().collect(Collectors.toMap(method -> method.name.concat(method.desc), Function.identity()));

		for (int i = 0; i < commonMethods.size(); i++) {//Indexed for loop as each added fix will add to commonMethods
			MethodComparison method = commonMethods.get(i);

			if (method.effectivelyEqual) resolveCloseMethod(method, fixes, nameToLosses, possibleLambdas);
		}

		for (int i = 0; i < commonMethods.size(); i++) {
			MethodComparison method = commonMethods.get(i);
			if (method.effectivelyEqual) continue; //Already handled this method

			List<Lambda> originalLambdas = method.getOriginalLambads();
			List<Lambda> patchedLambdas = method.getPatchedLambads();

			out: if (originalLambdas.size() == patchedLambdas.size()) {
				for (Iterator<Lambda> itOriginal = originalLambdas.iterator(), itPatched = patchedLambdas.iterator(); itOriginal.hasNext() && itPatched.hasNext();) {
					Lambda original = itOriginal.next();
					Lambda patched = itPatched.next();

					//Check if the lambdas are acting as the same method implementation
					if (!Objects.equals(original.method, patched.method)) break out;
				}

				pairUp(originalLambdas, patchedLambdas, fixes, nameToLosses, possibleLambdas, () -> {
					for (int j = commonMethods.size() - 1; j < commonMethods.size(); j++) {
						MethodComparison innerMethod = commonMethods.get(j);

						if (innerMethod.effectivelyEqual) resolveCloseMethod(innerMethod, fixes, nameToLosses, possibleLambdas);
					}
				});

				continue; //Matched all the lambdas up for method
			}

			Collector<Lambda, ?, Map<String, Map<String, List<Lambda>>>> lambdaCategorisation = Collectors.groupingBy(lambda -> lambda.desc, Collectors.groupingBy(lambda -> lambda.method));
			Map<String, Map<String, List<Lambda>>> descToOriginalLambda = originalLambdas.stream().collect(lambdaCategorisation);
			Map<String, Map<String, List<Lambda>>> descToPatchedLambda = patchedLambdas.stream().collect(lambdaCategorisation);

			Set<String> commonDescs = Sets.intersection(descToOriginalLambda.keySet(), descToPatchedLambda.keySet()); //Unique descriptions that are found in both the lost methods and gained lambdas
			if (!commonDescs.isEmpty()) {
				int fixedLambdas = 0;

				for (String desc : commonDescs) {
					Map<String, List<Lambda>> typeToOriginalLambda = descToOriginalLambda.get(desc);
					Map<String, List<Lambda>> typeToPatchedLambda = descToPatchedLambda.get(desc);

					for (String type : Sets.intersection(typeToOriginalLambda.keySet(), typeToPatchedLambda.keySet())) {
						List<Lambda> matchedOriginalLambdas = typeToOriginalLambda.get(type);
						List<Lambda> matchedPatchedLambdas = typeToPatchedLambda.get(type);

						if (matchedOriginalLambdas.size() == matchedPatchedLambdas.size()) {//Presume if the size is more than one they're in the same order
							fixedLambdas += matchedOriginalLambdas.size();

							pairUp(matchedOriginalLambdas, matchedPatchedLambdas, fixes, nameToLosses, possibleLambdas, () -> {
								for (int j = commonMethods.size() - 1; j < commonMethods.size(); j++) {
									MethodComparison innerMethod = commonMethods.get(j);

									if (innerMethod.effectivelyEqual) resolveCloseMethod(innerMethod, fixes, nameToLosses, possibleLambdas);
								}
							});
						}
					}
				}

				if (fixedLambdas == originalLambdas.size()) return true; //Caught all the lambdas
			}
		}

		return possibleLambdas.isEmpty(); //All the lambda-like methods which could be matched up if possibleLambdas is empty
	}

	private void resolveCloseMethod(MethodComparison method, Map<String, String> fixes, Map<String, MethodNode> nameToLosses, Map<String, MethodNode> possibleLambdas) {
		assert method.effectivelyEqual;

		if (!method.equal) {
			if (method.getOriginalLambads().size() != method.getPatchedLambads().size()) {
				throw new IllegalStateException("Bytecode in " + className + '#' + method.node.name + method.node.desc + " appeared unchanged but lambda count changed?");
			}

			pairUp(method.getOriginalLambads(), method.getPatchedLambads(), fixes, nameToLosses, possibleLambdas, Runnables.doNothing());
		} else {
			assert method.getOriginalLambads().stream().filter(lambda -> className.equals(lambda.owner)).map(Lambda::getName).noneMatch(lostMethod -> lostMethods().anyMatch(lostMethod::equals));
			assert method.getPatchedLambads().stream().filter(lambda -> className.equals(lambda.owner)).map(Lambda::getName).noneMatch(gainedMethod -> gainedLambdas().anyMatch(gainedMethod::equals));
		}
	}

	private void pairUp(List<Lambda> originalLambdas, List<Lambda> patchedLambdas, Map<String, String> fixes, Map<String, MethodNode> nameToLosses, Map<String, MethodNode> possibleLambdas, Runnable onPair) {
		Streams.forEachPair(originalLambdas.stream(), patchedLambdas.stream(), (lost, gained) -> {
			if (!className.equals(lost.owner)) return;
			assert className.equals(gained.owner);

			MethodNode lostMethod = nameToLosses.remove(lost.getName());
			MethodNode gainedMethod = possibleLambdas.remove(gained.getName());

			if (addFix(fixes, gainedMethod, lostMethod)) {
				lostMethods.remove(lostMethod);
				gainedMethods.remove(gainedMethod);
				onPair.run();
			}
		});
	}

	private boolean addFix(Map<String, String> fixes, MethodNode from, MethodNode to) {
		if (!from.desc.equals(to.desc)) {
			System.err.println("Description changed remapping lambda handle: " + className + '#' + from.name + from.desc + " => " + className + '#' + to.name + to.desc);
			return false; //Don't add the fix if it is wrong
		}

		fixes.put(className + '#' + from.name + from.desc, className + '#' + to.name + to.desc);

		from.name = to.name; //Apply the rename to the actual method node too
		commonMethods.add(new MethodComparison(to, from));
		return true;
	}

	Stream<String> lostMethods() {
		return lostMethods.stream().map(method -> method.name + method.desc);
	}

	Stream<String> gainedLambdas() {
		return gainedMethods.stream().filter(method -> (method.access & Opcodes.ACC_SYNTHETIC) != 0 && method.name.startsWith("lambda$")).map(method -> method.name + method.desc);
	}

	public void refreshChanges(List<MethodNode> original) {
		Map<String, MethodNode> originalMethods = original.stream().collect(Collectors.toMap(method -> method.name + method.desc, Function.identity()));

		for (ListIterator<MethodComparison> it = commonMethods.listIterator(); it.hasNext();) {
			MethodComparison comparison = it.next();

			MethodNode originalMethod = originalMethods.get(comparison.node.name + comparison.node.desc);
			if (originalMethod == null) continue;

			it.set(new MethodComparison(originalMethod, comparison.node));
		}
	}

	public void annotate(Annotator annotator) {
		lostMethods.stream().map(method -> method.name.concat(method.desc)).forEach(annotator::dropMethod);
		gainedMethods.stream().map(method -> method.name + method.desc).forEach(annotator::addMethod);
		commonMethods.stream().filter(MethodComparison::hasChanged).collect(Collectors.toMap(comparison -> comparison.node.name + comparison.node.desc, MethodComparison::toChangeSet)).forEach(annotator::addChangedMethod);
	}
}