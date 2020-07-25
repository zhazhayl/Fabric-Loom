/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers.openfine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

public class MethodComparison {
	public final MethodNode node;
	public final AccessChange access;
	public final FinalityChange finality;
	public final Set<String> gainedExceptions, lostExceptions;
	public final boolean equal;
	private final List<String> lambdaHandles = new ArrayList<>();

	public MethodComparison(MethodNode original, MethodNode patched) {
		assert Objects.equals(original.name, patched.name);
		assert Objects.equals(original.desc, patched.desc);
		node = patched;

		access = AccessChange.forAccess(original.access, patched.access);
		finality = FinalityChange.forAccess(original.access, patched.access);

		if (!Objects.equals(original.exceptions, patched.exceptions)) {
			//Sets.symmetricDifference(ImmutableSet.copyOf(original.exceptions), ImmutableSet.copyOf(patched.exceptions));
			gainedExceptions = Sets.difference(ImmutableSet.copyOf(patched.exceptions), ImmutableSet.copyOf(original.exceptions));
			lostExceptions = Sets.difference(ImmutableSet.copyOf(original.exceptions), ImmutableSet.copyOf(patched.exceptions));
		} else {
			gainedExceptions = lostExceptions = Collections.emptySet();
		}

		if (original.instructions.size() == patched.instructions.size()) {
			equal = compare(original.instructions, patched.instructions);
		} else {
			equal = false;
			findHandles(patched.instructions, 0, this::logLambda);
		}
	}

	private boolean compare(InsnList listA, InsnList listB) {
		assert listA.size() == listB.size();

		for (int i = 0; i < listA.size(); i++) {
			AbstractInsnNode insnA = listA.get(i);
			AbstractInsnNode insnB = listB.get(i);

			if (!compare(listA, listB, insnA, insnB)) {
				findHandles(listB, i + 1, this::logLambda);
				return false;
			}
		}

		return true;
	}

	private boolean compare(InsnList listA, InsnList listB, AbstractInsnNode insnA, AbstractInsnNode insnB) {
		if (insnA.getOpcode() != insnB.getOpcode()) return false;

		switch (insnA.getType()) {
		case AbstractInsnNode.INT_INSN: {
			IntInsnNode a = (IntInsnNode) insnA;
			IntInsnNode b = (IntInsnNode) insnB;

			return a.operand == b.operand;
		}

		case AbstractInsnNode.VAR_INSN: {
			VarInsnNode a = (VarInsnNode) insnA;
			VarInsnNode b = (VarInsnNode) insnB;

			return a.var == b.var;
		}

		case AbstractInsnNode.TYPE_INSN: {
			TypeInsnNode a = (TypeInsnNode) insnA;
			TypeInsnNode b = (TypeInsnNode) insnB;

			return Objects.equals(a.desc, b.desc);
		}

		case AbstractInsnNode.FIELD_INSN: {
			FieldInsnNode a = (FieldInsnNode) insnA;
			FieldInsnNode b = (FieldInsnNode) insnB;

			return Objects.equals(a.owner, b.owner) && Objects.equals(a.name, b.name) && Objects.equals(a.desc, b.desc);
		}

		case AbstractInsnNode.METHOD_INSN: {
			MethodInsnNode a = (MethodInsnNode) insnA;
			MethodInsnNode b = (MethodInsnNode) insnB;

			if (!Objects.equals(a.owner, b.owner) || !Objects.equals(a.name, b.name) || !Objects.equals(a.desc, b.desc)) {
				return false;
			}
			if (a.itf != b.itf) {
				//More debatable if the actual method is the same, we'll go with it being a change for now
				return false;
			}

			return true;
		}

		case AbstractInsnNode.INVOKE_DYNAMIC_INSN: {
			InvokeDynamicInsnNode a = (InvokeDynamicInsnNode) insnA;
			InvokeDynamicInsnNode b = (InvokeDynamicInsnNode) insnB;

			if (!a.bsm.equals(b.bsm)) return false;

			if (isJavaLambdaMetafactory(a.bsm)) {
				Handle implA = (Handle) a.bsmArgs[1];
				Handle implB = (Handle) b.bsmArgs[1];

				if (implA.getTag() != implB.getTag()) return false;

				switch (implA.getTag()) {
				case Opcodes.H_INVOKEVIRTUAL:
				case Opcodes.H_INVOKESTATIC:
				case Opcodes.H_INVOKESPECIAL:
				case Opcodes.H_NEWINVOKESPECIAL:
				case Opcodes.H_INVOKEINTERFACE:
					logLambda(implB);

					if (!Objects.equals(implA.getOwner(), implB.getOwner()) || !Objects.equals(implA.getName(), implB.getName()) || !Objects.equals(implA.getDesc(), implB.getDesc())) {
						return false;
					}
					if (implA.isInterface() != implB.isInterface()) {
						//More debatable if the actual method is the same, we'll go with it being a change for now
						return false;
					}

					return true;

				default:
					throw new IllegalStateException("Unexpected impl tag: " + implA.getTag());
				}
			} else {
				throw new IllegalStateException(String.format("Unknown invokedynamic bsm: %s#%s%s (tag=%d iif=%b)", a.bsm.getOwner(), a.bsm.getName(), a.bsm.getDesc(), a.bsm.getTag(), a.bsm.isInterface()));
			}
		}

		case AbstractInsnNode.JUMP_INSN: {
			JumpInsnNode a = (JumpInsnNode) insnA;
			JumpInsnNode b = (JumpInsnNode) insnB;

			// check if the 2 jumps have the same direction
			return Integer.signum(listA.indexOf(a.label) - listA.indexOf(a)) == Integer.signum(listB.indexOf(b.label) - listB.indexOf(b));
		}

		case AbstractInsnNode.LDC_INSN: {
			LdcInsnNode a = (LdcInsnNode) insnA;
			LdcInsnNode b = (LdcInsnNode) insnB;
			Class<?> typeClsA = a.cst.getClass();

			if (typeClsA != b.cst.getClass()) return false;

			if (typeClsA == Type.class) {
				Type typeA = (Type) a.cst;
				Type typeB = (Type) b.cst;

				if (typeA.getSort() != typeB.getSort()) return false;

				switch (typeA.getSort()) {
				case Type.ARRAY:
				case Type.OBJECT:
					return Objects.equals(typeA.getDescriptor(), typeB.getDescriptor());

				case Type.METHOD:
					throw new UnsupportedOperationException("Bad sort: " + typeA);
				}
			} else {
				return a.cst.equals(b.cst);
			}
		}

		case AbstractInsnNode.IINC_INSN: {
			IincInsnNode a = (IincInsnNode) insnA;
			IincInsnNode b = (IincInsnNode) insnB;

			return a.incr == b.incr && a.var == b.var;
		}

		case AbstractInsnNode.TABLESWITCH_INSN: {
			TableSwitchInsnNode a = (TableSwitchInsnNode) insnA;
			TableSwitchInsnNode b = (TableSwitchInsnNode) insnB;

			return a.min == b.min && a.max == b.max;
		}

		case AbstractInsnNode.LOOKUPSWITCH_INSN: {
			LookupSwitchInsnNode a = (LookupSwitchInsnNode) insnA;
			LookupSwitchInsnNode b = (LookupSwitchInsnNode) insnB;

			return a.keys.equals(b.keys);
		}

		case AbstractInsnNode.MULTIANEWARRAY_INSN: {
			MultiANewArrayInsnNode a = (MultiANewArrayInsnNode) insnA;
			MultiANewArrayInsnNode b = (MultiANewArrayInsnNode) insnB;

			return a.dims == b.dims && Objects.equals(a.desc, b.desc);
		}

		case AbstractInsnNode.INSN:
		case AbstractInsnNode.LABEL:
		case AbstractInsnNode.LINE:
		case AbstractInsnNode.FRAME: {
			return true;
		}

		default:
			throw new IllegalArgumentException("Unexpected instructions: " + insnA + ", " + insnB);
		}
	}

	private static boolean isJavaLambdaMetafactory(Handle bsm) {
		return bsm.getTag() == Opcodes.H_INVOKESTATIC
				&& "java/lang/invoke/LambdaMetafactory".equals(bsm.getOwner())
				&& ("metafactory".equals(bsm.getName())
						&& "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;".equals(bsm.getDesc())
						|| "altMetafactory".equals(bsm.getName())
						&& "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;".equals(bsm.getDesc())
				) && !bsm.isInterface();
	}

	public static List<String> lambdas(InsnList instructions) {
		List<String> lambdas = new ArrayList<>();
		findHandles(instructions, 0, handle -> lambdas.add(handle.getOwner() + '#' + handle.getName() + handle.getDesc()));
		return Collections.unmodifiableList(lambdas);
	}

	public static void findHandles(InsnList instructions, int from, Consumer<Handle> lambdaEater) {
		findLambdas(instructions, from, idin -> {
			Handle impl = (Handle) idin.bsmArgs[1];

			switch (impl.getTag()) {
			case Opcodes.H_INVOKEVIRTUAL:
			case Opcodes.H_INVOKESTATIC:
			case Opcodes.H_INVOKESPECIAL:
			case Opcodes.H_NEWINVOKESPECIAL:
			case Opcodes.H_INVOKEINTERFACE:
				lambdaEater.accept(impl);
				break;

			default:
				throw new IllegalStateException("Unexpected impl tag: " + impl.getTag());
			}
		});
	}

	public static void findLambdas(InsnList instructions, int from, Consumer<InvokeDynamicInsnNode> instructionEater) {
		for (; from < instructions.size(); from++) {
			AbstractInsnNode insn = instructions.get(from);

			if (insn.getType() == AbstractInsnNode.INVOKE_DYNAMIC_INSN) {
				InvokeDynamicInsnNode idin = (InvokeDynamicInsnNode) insn;

				if (isJavaLambdaMetafactory(idin.bsm)) {
					instructionEater.accept(idin);
				} else {
					throw new IllegalStateException(String.format("Unknown invokedynamic bsm: %s#%s%s (tag=%d iif=%b)", idin.bsm.getOwner(), idin.bsm.getName(), idin.bsm.getDesc(), idin.bsm.getTag(), idin.bsm.isInterface()));
				}
			}
		}
	}

	private void logLambda(Handle handle) {
		lambdaHandles.add(handle.getOwner() + '#' + handle.getName() + handle.getDesc());
	}

	public boolean hasLambdas() {
		return !lambdaHandles.isEmpty();
	}

	public List<String> getLambads() {
		return Collections.unmodifiableList(lambdaHandles);
	}

	public boolean hasChanged() {
		return access != AccessChange.NONE || finality != FinalityChange.NONE || !equal;
	}

	public ChangeSet toChangeSet() {
		return new ChangeSet(access, finality);
	}
}