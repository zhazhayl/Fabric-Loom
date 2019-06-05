package net.fabricmc.loom.providers.openfine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;

public class MethodChanges {
	private final String className;
	private final List<MethodComparison> modifiedMethods = new ArrayList<>();
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
					modifiedMethods.add(new MethodComparison(originalMethod, patchedMethod));
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
		modifiedMethods.sort(Comparator.comparingInt(method -> original.indexOf(method.node)));
		lostMethods.sort(Comparator.comparingInt(original::indexOf));
		gainedMethods.sort(Comparator.comparingInt(patched::indexOf));
	}

	public boolean couldNeedLambdasFixing() {
		return modifiedMethods.stream().anyMatch(method -> !method.equal && method.hasLambdas()) && !lostMethods.isEmpty() && !gainedMethods.isEmpty();
	}

	public void tryFixLambdas(Map<String, String> fixes) {//How do you fix lambdas? With dozens of other lambdas of course
		List<MethodNode> gainedLambdas = gainedMethods.stream().filter(method -> (method.access & Opcodes.ACC_STATIC) != 0 && method.name.startsWith("lambda$")).collect(Collectors.toList());
		if (gainedLambdas.isEmpty()) return; //Nothing looks like a lambda

		if (gainedLambdas.size() == lostMethods.size()) {
			int[] lambdaDemand = modifiedMethods.stream().mapToLong(comparison -> comparison.getLambads().stream().map(lambda -> lambda.substring(0, lambda.indexOf('#'))).filter(className::equals).count()).filter(count -> count > 0).mapToInt(Math::toIntExact).toArray();
			Pattern regex = Pattern.compile("lambda\\$(\\w+)\\$(\\d+)");
			int[] lambdaSupply = gainedLambdas.stream().map(method -> regex.matcher(method.name)).filter(Matcher::matches).sorted(Comparator.comparingInt(matcher -> Integer.parseInt(matcher.group(2)))).collect(Collectors.groupingBy(matcher -> matcher.group(1), LinkedHashMap::new, Collectors.counting())).values().stream().mapToInt(Long::intValue).toArray();

			if (Arrays.equals(lambdaDemand, lambdaSupply)) {//The gained lambdas match completely with the lost methods, map directly
				Streams.forEachPair(lostMethods.stream(), gainedLambdas.stream(), (lost, gained) -> addFix(fixes, gained, lost));
				gainedMethods.removeAll(gainedLambdas);
				lostMethods.clear();
				return; //Nothing more to do
			}
		}

		Map<String, MethodNode> descToLambda = gainedLambdas.stream().collect(Collectors.groupingBy(lambda -> lambda.desc)).entrySet().stream().filter(entry -> entry.getValue().size() == 1).collect(Collectors.toMap(Entry::getKey, entry -> Iterables.getOnlyElement(entry.getValue())));
		if (!descToLambda.isEmpty()) {
			Map<String, MethodNode> lambdaToHandle = modifiedMethods.stream().flatMap(comparison -> comparison.getLambads().stream().filter(lambda -> className.equals(lambda.substring(0, lambda.indexOf('#'))))).collect(Collectors.toMap(Function.identity(), lambda -> descToLambda.get(lambda.substring(lambda.indexOf('(')))));
			boolean complete = !lambdaToHandle.values().removeIf(Objects::isNull);

			if (!lambdaToHandle.isEmpty()) {
				Map<String, MethodNode> nameToLostMethod = lostMethods.stream().collect(Collectors.toMap(method -> method.name + method.desc, Function.identity()));

				Map<MethodNode, MethodNode> lostToGained = lambdaToHandle.entrySet().stream().collect(Collectors.toMap(entry -> nameToLostMethod.get(entry.getKey().substring(entry.getKey().indexOf('#') + 1)), Entry::getValue));
				assert !lostToGained.containsKey(null); //Should find all these

				lostToGained.forEach((lost, gained) -> addFix(fixes, gained, lost));
				lostMethods.removeAll(lostToGained.keySet());
				gainedMethods.removeAll(lostToGained.values());
			} else {
				assert !complete; //Unfortunate
			}

			if (complete) return; //Caught all the lambdas
		}

		//If we can't directly match up the lost and gained lambda like methods, nor match by description more creative solutions will be needed
		throw new IllegalStateException("Unable to resolve " + gainedLambdas.size() + " lambdas in " + className);
	}

	private void addFix(Map<String, String> fixes, MethodNode from, MethodNode to) {
		fixes.put(className + '#' + from.name + from.desc, className + '#' + to.name + to.desc);
	}
}