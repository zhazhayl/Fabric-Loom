package net.fabricmc.loom.providers.openfine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodNode;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
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
		sortModifiedMethods(patched);
		lostMethods.sort(Comparator.comparingInt(original::indexOf));
		gainedMethods.sort(Comparator.comparingInt(patched::indexOf));
	}

	void sortModifiedMethods(List<MethodNode> patched) {
		modifiedMethods.sort(Comparator.comparingInt(method -> !"<clinit>".equals(method.node.name) ? patched.indexOf(method.node) : "com/mojang/blaze3d/platform/GLX".equals(className) ? patched.size() : -1));
	}

	public boolean couldNeedLambdasFixing() {
		return modifiedMethods.stream().anyMatch(method -> !method.equal && method.hasLambdas()) && !lostMethods.isEmpty() && !gainedMethods.isEmpty();
	}

	public void tryFixLambdas(Map<String, String> fixes) {//How do you fix lambdas? With dozens of other lambdas of course
		List<MethodNode> gainedLambdas = gainedMethods.stream().filter(method -> (method.access & Opcodes.ACC_SYNTHETIC) != 0 && method.name.startsWith("lambda$")).collect(Collectors.toList());
		if (gainedLambdas.isEmpty()) return; //Nothing looks like a lambda

		Set<String> possibleLambdas = gainedLambdas.stream().map(method -> className + '#' + method.name + method.desc).collect(Collectors.toSet()); //The collection of lambdas we're looking to fix, any others are irrelevant from the point of view that they're probably fine

		if (gainedLambdas.size() == lostMethods.size()) {
			int[] lambdaDemand = modifiedMethods.stream().mapToLong(comparison -> comparison.getLambads().stream().filter(possibleLambdas::contains).count()).filter(count -> count > 0).mapToInt(Math::toIntExact).toArray();
			Pattern regex = Pattern.compile("lambda\\$(\\w+)\\$(\\d+)");
			int[] lambdaSupply = gainedLambdas.stream().map(method -> regex.matcher(method.name)).filter(Matcher::matches).sorted(Comparator.comparingInt(matcher -> Integer.parseInt(matcher.group(2)))).collect(Collectors.groupingBy(matcher -> matcher.group(1), LinkedHashMap::new, Collectors.counting())).values().stream().mapToInt(Long::intValue).toArray();

			if (Arrays.equals(lambdaDemand, lambdaSupply)) {//The gained lambdas match completely with the lost methods, map directly
				Streams.forEachPair(lostMethods.stream(), gainedLambdas.stream(), (lost, gained) -> addFix(fixes, gained, lost));
				gainedMethods.removeAll(gainedLambdas);
				lostMethods.clear();
				return; //Nothing more to do
			}
		}

		Map<String, MethodNode> newDescToLambda = gainedLambdas.stream().collect(Collectors.groupingBy(lambda -> lambda.desc)).entrySet().stream().filter(entry -> entry.getValue().size() == 1).collect(Collectors.toMap(Entry::getKey, entry -> Iterables.getOnlyElement(entry.getValue())));
		Map<String, MethodNode> oldDescToMethod = lostMethods.stream().collect(Collectors.groupingBy(lambda -> lambda.desc)).entrySet().stream().filter(entry -> entry.getValue().size() == 1).collect(Collectors.toMap(Entry::getKey, entry -> Iterables.getOnlyElement(entry.getValue())));

		Set<String> commonDescs = Sets.intersection(newDescToLambda.keySet(), oldDescToMethod.keySet()); //Unique descriptions that are found in both the lost methods and gained lambdas
		if (!commonDescs.isEmpty()) {
			boolean complete = modifiedMethods.stream().flatMap(comparison -> comparison.getLambads().stream().filter(possibleLambdas::contains)).allMatch(lambda -> commonDescs.contains(lambda.substring(lambda.indexOf('('))));

			Map<MethodNode, MethodNode> lostToGained = newDescToLambda.entrySet().stream().filter(entry -> oldDescToMethod.containsKey(entry.getKey())).collect(Collectors.toMap(entry -> oldDescToMethod.get(entry.getKey()), Entry::getValue));
			if (lostToGained.containsKey(null)) {//Should find all these
				throw new IllegalStateException("Unable to find lostMethod from " + newDescToLambda.keySet() + " => " + oldDescToMethod.keySet());
			}

			lostToGained.forEach((lost, gained) -> addFix(fixes, gained, lost));
			lostMethods.removeAll(lostToGained.keySet());
			gainedMethods.removeAll(lostToGained.values());

			if (complete) return; //Caught all the lambdas
			gainedLambdas.retainAll(gainedMethods);
		}

		switch (className) {//Special case classes which can't be easily automatically resolved or have misses that cannot be avoided
			case "cvi": {//ItemRenderer
				expect("lost methods", lostMethods, "a(Lcxm;CI)V", "a(Lcxm;II)V", "a(I[ZLcxl;III)V", "a(Ljm;)V", "b(Ljm;)V");
				expect("gained lambdas", gainedLambdas, "lambda$onCharEvent$6(CILcxm;)V", "lambda$onCharEvent$5(IILcxm;)V", "lambda$onKeyEvent$4(I[ZIIILcxl;)V", "lambda$onKeyEvent$3(Ljm;)V", "lambda$null$2(Ljm;)V");

				expect("new matched lambdas", fixes.keySet(), "cvi#lambda$getClipboardString$7(IJ)V", "cvi#lambda$copyHoveredObject$1(Lqs;Lcrx;Lib;)V", "cvi#lambda$copyHoveredObject$0(Lbvk;Lev;Lib;)V");
				expect("old matched lambdas", fixes.values(), "cvi#a(IJ)V", "cvi#b(Lqs;Lcrx;Lib;)V", "cvi#b(Lbvk;Lev;Lib;)V");

				List<MethodNode> reversedLostMethods = Lists.reverse(lostMethods); //Not strictly necessary, just makes grabbing the final elements a little nicer looking
				List<MethodNode> reversedGainedLambdas = Lists.reverse(gainedLambdas);

				applyFix(fixes, reversedGainedLambdas.remove(0), reversedLostMethods.remove(0)); //Match lambda$null$2(Ljm;)V => b(Ljm;)V
				applyFix(fixes, reversedGainedLambdas.remove(0), reversedLostMethods.remove(0)); //Match lambda$onKeyEvent$3(Ljm;)V => a(Ljm;)V
				//The remaining 3 lambdas are technically still in the class, but have completely different signatures due to the way they've changed to account for Forge event support

				return; //Manually checked and corrected everything
			}

			case "cwd": {//InGameHud
				expect("lost methods", lostMethods, "a(FIILduj;)V");
				expect("gained lambdas", gainedLambdas, "lambda$renderPotionEffects$1(FIILduj;)V", "lambda$renderPotionEffects$0(FIILduj;)V");

				expect("new matched lambdas", fixes.keySet(), "cwd#lambda$renderScoreboard$2(Lcsw;)Z");
				expect("old matched lambdas", fixes.values(), "cwd#a(Lcsw;)Z");

				//The single lost lambda is effectively duplicated by the two gained ones, owing to Forge event support using a duplicated (but different) code path
				applyFix(fixes, gainedLambdas.remove(0), lostMethods.remove(0)); //Match lambda$renderPotionEffects$1(FIILduj;)V => a(FIILduj;)V as it is the vanilla code path version

				return;
			}

			case "czv": {//SkinOptionsScreen
				expect("lost methods", lostMethods, "a(Lcwq;)V", "b(Lcwq;)V");
				expect("gained lambdas", gainedLambdas, "lambda$init$3(Lcwq;)V", "lambda$init$2(Lcwq;)V", "lambda$init$1(Lcwq;)V");

				expect("new matched lambdas", fixes.keySet(), "czv#lambda$init$0(Lavz;Lcwq;)V");
				expect("old matched lambdas", fixes.values(), "czv#a(Lavz;Lcwq;)V");

				applyFix(fixes, gainedLambdas.remove(0), lostMethods.get(0)); //Match lambda$init$3(Lcwq;)V => a(Lcwq;)V
				applyFix(fixes, gainedLambdas.remove(1), lostMethods.get(0)); //Match lambda$init$1(Lcwq;)V => b(Lcwq;)V

				return; //The init$2 lambda is an OptiFine added button callback
			}

			case "dix": {//ParticleManager
				expect("lost methods", lostMethods, "a(Lagh;Ljava/util/Map;Ldui$a;)V");
				expect("gained lambdas", gainedLambdas, "lambda$reload$5(Lagh;Ljava/util/Map;Ljava/lang/Object;)V");

				expect("new matched lambdas", fixes.keySet(), "dix#lambda$reload$1(Lxe;Ljava/util/Map;Ljava/util/concurrent/Executor;Lqs;)Ljava/util/concurrent/CompletableFuture;", "dix#lambda$reload$3(Lagh;Ljava/util/Map;Lxe;Ljava/lang/Void;)Ldui$a;", "dix#lambda$addBlockDestroyEffects$8(Lev;Lbvk;DDDDDD)V",
						"dix#lambda$tick$7(Ldiz;)Ljava/util/Queue;", "dix#lambda$null$0(Lxe;Lqs;Ljava/util/Map;)V", "dix#lambda$null$4(Lduj;Lqs;Ljava/util/List;)V", "dix#lambda$reload$2(I)[Ljava/util/concurrent/CompletableFuture;", "dix#lambda$tick$6(Ldiz;Ljava/util/Queue;)V");
				expect("old matched lambdas", fixes.values(), "dix#a(Lxe;Ljava/util/Map;Ljava/util/concurrent/Executor;Lqs;)Ljava/util/concurrent/CompletableFuture;", "dix#a(Lagh;Ljava/util/Map;Lxe;Ljava/lang/Void;)Ldui$a;", "dix#a(Lev;Lbvk;DDDDDD)V", "dix#a(Ldiz;)Ljava/util/Queue;",
						"dix#b(Lxe;Lqs;Ljava/util/Map;)V", "dix#a(Lduj;Lqs;Ljava/util/List;)V", "dix#a(I)[Ljava/util/concurrent/CompletableFuture;", "dix#a(Ldiz;Ljava/util/Queue;)V");

				return; //The reload$5 lambda does match the single lost method, but has a signature change (apparently for no reason)
			}

			case "dwa": {//ModelLoader
				expect("lost methods", lostMethods, "a(Lxd;)Lcom/mojang/datafixers/util/Pair;", "a(Ldlm;)V", "b(Ldlm;)V");
				expect("gained lambdas", gainedLambdas, "lambda$loadBlockstate$11(Lqs;Lxd;)Lcom/mojang/datafixers/util/Pair;", "lambda$static$1(Ldlm;)V", "lambda$static$0(Ldlm;)V");

				expect("new matched lambdas", fixes.keySet(), "dwa#lambda$null$13(Ldwg;Ljava/util/Map$Entry;)Z", "dwa#lambda$null$14(Ljava/util/Map;Ldlv;Ldwg;Ldln;Lbvk;)V", "dwa#lambda$loadBlockstate$10(Ljava/util/Map;Lqs;Lbvk;)V", "dwa#lambda$null$2(Lqs;Lbvk;)V",
						"dwa#lambda$loadBlockstate$15(Lcom/google/common/collect/ImmutableList;Lbvl;Ljava/util/Map;Ldwg;Ldln;Lqs;Lcom/mojang/datafixers/util/Pair;Ljava/lang/String;Ldlv;)V", "dwa#lambda$parseVariantKey$8(Lbmm;Ljava/util/Map;Lbvk;)Z", "dwa#lambda$func_217844_a$7(Lqs;)V",
						"dwa#lambda$new$4(Lbvk;)V", "dwa#lambda$new$5(Ljava/util/Set;Ldwg;)Ljava/util/stream/Stream;", "dwa#lambda$new$3(Lqs;Lbvl;)V", "dwa#lambda$new$6(Ljava/lang/String;)V", "dwa#lambda$loadBlockstate$12(Ljava/util/Map;Ldwg;Lbvk;)V", "dwa#lambda$loadBlockstate$9(Lqs;)Lbvl;");
				expect("old matched lambdas", fixes.values(), "dwa#a(Ldwg;Ljava/util/Map$Entry;)Z", "dwa#a(Ljava/util/Map;Ldlv;Ldwg;Ldln;Lbvk;)V", "dwa#a(Ljava/util/Map;Lqs;Lbvk;)V", "dwa#a(Lqs;Lbvk;)V",
						"dwa#a(Lcom/google/common/collect/ImmutableList;Lbvl;Ljava/util/Map;Ldwg;Ldln;Lqs;Lcom/mojang/datafixers/util/Pair;Ljava/lang/String;Ldlv;)V", "dwa#a(Lbmm;Ljava/util/Map;Lbvk;)Z", "dwa#e(Lqs;)V", "dwa#a(Lbvk;)V", "dwa#a(Ljava/util/Set;Ldwg;)Ljava/util/stream/Stream;",
						"dwa#a(Lqs;Lbvl;)V", "dwa#a(Ljava/lang/String;)V", "dwa#a(Ljava/util/Map;Ldwg;Lbvk;)V", "dwa#d(Lqs;)Lbvl;");

				applyFix(fixes, gainedLambdas.remove(1), lostMethods.get(1)); //Match lambda$static$1(Ldlm;)V => a(Ldlm;)V
				applyFix(fixes, gainedLambdas.remove(1), lostMethods.get(1)); //Match lambda$static$0(Ldlm;)V => b(Ldlm;)V
				//The loadBlockstate$11 lambda matches the remaining lost method, but gains a parameter for Forge blockstate support

				return;
			}
		}

		//If we can't directly match up the lost and gained lambda like methods, nor match by description more creative solutions will be needed
		throw new IllegalStateException("Unable to resolve " + gainedLambdas.size() + " lambda(s) in " + className + ": " + gainedLambdas.stream().map(method -> method.name + method.desc).collect(Collectors.joining(", ", "[", "]")) + " from " + lostMethods.stream().map(method -> method.name + method.desc).collect(Collectors.joining(", ", "[", "]")) + " having found " + fixes);
	}

	private static void expect(String type, List<MethodNode> methods, String... names) {
		if (!Arrays.equals(methods.stream().map(method -> method.name + method.desc).toArray(String[]::new), names)) {
			throw new IllegalStateException("Mismatch with " + type + ", expected " + Arrays.toString(names) + " but had " + methods.stream().map(method -> method.name + method.desc).collect(Collectors.joining(", ", "[", "]")));
		}
	}

	private static void expect(String type, Collection<String> methods, String... names) {
		if (methods.size() != names.length || !methods.containsAll(Arrays.asList(names))) {
			throw new IllegalStateException("Mismatch with " + type + ", expected " + Arrays.toString(names) + " but had " + methods);
		}
	}

	private void applyFix(Map<String, String> fixes, MethodNode from, MethodNode to) {
		if (addFix(fixes, from, to)) {
			gainedMethods.remove(from);
			lostMethods.remove(to);
		}
	}

	private boolean addFix(Map<String, String> fixes, MethodNode from, MethodNode to) {
		if (!from.desc.equals(to.desc)) {
			System.err.println("Description changed remapping lambda handle: " + className + '#' + from.name + from.desc + " => " + className + '#' + to.name + to.desc);
			return false; //Don't add the fix if it is wrong
		}

		fixes.put(className + '#' + from.name + from.desc, className + '#' + to.name + to.desc);

		from.name = to.name; //Apply the rename to the actual method node too
		modifiedMethods.add(new MethodComparison(to, from));
		return true;
	}

	public Stream<MethodNode> modifiedMethods() {
		return modifiedMethods.stream().map(method -> method.node);
	}

	public void refreshChanges(List<MethodNode> original) {
		Map<String, MethodNode> originalMethods = original.stream().collect(Collectors.toMap(method -> method.name + method.desc, Function.identity()));

		for (ListIterator<MethodComparison> it = modifiedMethods.listIterator(); it.hasNext();) {
			MethodComparison comparison = it.next();

			MethodNode originalMethod = originalMethods.get(comparison.node.name + comparison.node.desc);
			if (originalMethod == null) continue;

			it.set(new MethodComparison(originalMethod, comparison.node));
		}
	}

	public void annotate(Annotator annotator) {
		lostMethods.stream().map(method -> {
			Type methodType = Type.getType(method.desc);
			StringJoiner joiner = new StringJoiner(", ", "(", ")");
			Arrays.stream(methodType.getArgumentTypes()).map(Type::getClassName).forEach(joiner::add);
			return method.name + joiner.toString() + methodType.getReturnType();
		}).forEach(annotator::dropMethod);
		gainedMethods.stream().map(method -> method.name + method.desc).forEach(annotator::addMethod);
		modifiedMethods.stream().filter(MethodComparison::hasChanged).collect(Collectors.toMap(comparison -> comparison.node.name + comparison.node.desc, MethodComparison::toChangeSet)).forEach(annotator::addChangedMethod);
	}
}