package net.fabricmc.loom.providers.openfine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import com.chocohead.optisine.AccessChange.Access;
import com.chocohead.optisine.FinalityChange.Finality;
import com.chocohead.optisine.InterfaceGain;
import com.chocohead.optisine.InterfaceGains;
import com.chocohead.optisine.OptiFineAdded;
import com.chocohead.optisine.OptiFineChanged;
import com.chocohead.optisine.OptiFineLost;
import com.chocohead.optisine.OptiFineRemoved;
import com.google.common.collect.Iterables;

public class Annotator {
	private final AccessChange accessChange;
	private final FinalityChange finalityChange;
	private final Set<String> gainedInterfaces, lostInterfaces;
	private final Set<String> gainedMethods = new HashSet<>(), gainedFields = new HashSet<>();
	private final List<String> lostMethods = new ArrayList<>(), lostFields = new ArrayList<>();
	private final Map<String, ChangeSet> changedMethods = new HashMap<>(), changedFields = new HashMap<>();

	public Annotator(AccessChange accessChange, FinalityChange finalityChange, Set<String> gainedInterfaces, Set<String> lostInterfaces) {
		this.accessChange = accessChange;
		this.finalityChange = finalityChange;
		this.gainedInterfaces = gainedInterfaces;
		this.lostInterfaces = lostInterfaces;
	}

	public void addMethod(String name) {
		gainedMethods.add(name);
	}

	public void addChangedMethod(String name, ChangeSet changes) {
		changedMethods.put(name, changes);
	}

	public void dropMethod(String name) {
		lostMethods.add(name);
	}

	public void addField(String name) {
		gainedFields.add(name);
	}

	public void addChangedField(String name, ChangeSet changes) {
		changedFields.put(name, changes);
	}

	public void dropField(String name) {
		lostFields.add(name);
	}

	public void apply(ClassNode node) {
		boolean useAccessChange = false, useFinalityChange = false;

		if (accessChange != AccessChange.NONE) {
			AnnotationVisitor av = node.visitAnnotation(Type.getDescriptor(com.chocohead.optisine.AccessChange.class), false);
			av.visitEnum("was", Type.getDescriptor(Access.class), accessChange.toAccess().name());
			av.visitEnd();
		}

		if (finalityChange != FinalityChange.NONE) {
			AnnotationVisitor av = node.visitAnnotation(Type.getDescriptor(com.chocohead.optisine.FinalityChange.class), false);
			av.visitEnum("change", Type.getDescriptor(Finality.class), finalityChange.toFinality().name());
			av.visitEnd();
		}

		if (!gainedInterfaces.isEmpty()) {
			if (gainedInterfaces.size() == 1) {
				AnnotationVisitor av = node.visitAnnotation(Type.getDescriptor(InterfaceGain.class), false);
				av.visit("value", Type.getObjectType(Iterables.getOnlyElement(gainedInterfaces)).getClassName());
				av.visitEnd();
			} else {
				AnnotationVisitor av = node.visitAnnotation(Type.getDescriptor(InterfaceGains.class), false);
				AnnotationVisitor value = av.visitArray("value");

				for (String gainedInterface : gainedInterfaces) {
					AnnotationVisitor iav = value.visitAnnotation(null, Type.getDescriptor(InterfaceGain.class));
					iav.visit("value", Type.getObjectType(gainedInterface).getClassName());
					iav.visitEnd();
				}

				value.visitEnd();
				av.visitEnd();
			}
		}

		if (!lostInterfaces.isEmpty() || !lostMethods.isEmpty() || !lostFields.isEmpty()) {
			if (lostInterfaces.size() + lostMethods.size() + lostFields.size() == 1) {
				String name, type;
				if (!lostInterfaces.isEmpty()) {
					name = Type.getObjectType(Iterables.getOnlyElement(lostInterfaces)).getClassName();
					type = OptiFineRemoved.Type.INTERFACE.name();
				} else if (lostMethods.isEmpty()) {
					name = Iterables.getOnlyElement(lostFields);
					type = OptiFineRemoved.Type.FIELD.name();
				} else {
					name = Iterables.getOnlyElement(lostMethods);
					type = OptiFineRemoved.Type.METHOD.name();
				}

				AnnotationVisitor av = node.visitAnnotation(Type.getDescriptor(OptiFineRemoved.class), false);
				av.visitEnum("type", Type.getDescriptor(OptiFineRemoved.Type.class), type);
				av.visit("name", name);
				av.visitEnd();
			} else {
				AnnotationVisitor av = node.visitAnnotation(Type.getDescriptor(OptiFineLost.class), false);
				AnnotationVisitor value = av.visitArray("value");

				for (String lostInterface : lostInterfaces) {
					AnnotationVisitor iav = value.visitAnnotation(null, Type.getDescriptor(OptiFineRemoved.class));
					iav.visitEnum("type", Type.getDescriptor(OptiFineRemoved.Type.class), OptiFineRemoved.Type.INTERFACE.name());
					iav.visit("name", Type.getObjectType(lostInterface).getClassName());
					iav.visitEnd();
				}

				for (String field : lostFields) {
					AnnotationVisitor iav = value.visitAnnotation(null, Type.getDescriptor(OptiFineRemoved.class));
					iav.visitEnum("type", Type.getDescriptor(OptiFineRemoved.Type.class), OptiFineRemoved.Type.FIELD.name());
					iav.visit("name", field);
					iav.visitEnd();
				}

				for (String method : lostMethods) {
					AnnotationVisitor iav = value.visitAnnotation(null, Type.getDescriptor(OptiFineRemoved.class));
					iav.visitEnum("type", Type.getDescriptor(OptiFineRemoved.Type.class), OptiFineRemoved.Type.METHOD.name());
					iav.visit("name", method);
					iav.visitEnd();
				}

				value.visitEnd();
				av.visitEnd();
			}

			addInnerAccess(node, OptiFineRemoved.Type.class);
		}

		if (!changedMethods.isEmpty()) {
			on: for (Entry<String, ChangeSet> entry : changedMethods.entrySet()) {
				String methodTarget = entry.getKey();

				for (MethodNode method : node.methods) {
					if (methodTarget.equals(method.name + method.desc)) {
						ChangeSet change = entry.getValue();

						if (change.accessChange != AccessChange.NONE) {
							AnnotationVisitor av = method.visitAnnotation(Type.getDescriptor(com.chocohead.optisine.AccessChange.class), false);
							av.visitEnum("was", Type.getDescriptor(Access.class), change.accessChange.toAccess().name());
							av.visitEnd();
						}

						if (change.finalityChange != FinalityChange.NONE) {
							AnnotationVisitor av = method.visitAnnotation(Type.getDescriptor(com.chocohead.optisine.FinalityChange.class), false);
							av.visitEnum("change", Type.getDescriptor(Finality.class), change.finalityChange.toFinality().name());
							av.visitEnd();
						}

						method.visitAnnotation(Type.getDescriptor(OptiFineChanged.class), false).visitEnd();
						continue on;
					}
				}

				throw new IllegalStateException("Cannot find " + methodTarget + " in " + node.name);
			}

		}

		if (!changedFields.isEmpty()) {
			on: for (Entry<String, ChangeSet> entry : changedFields.entrySet()) {
				String fieldTarget = entry.getKey();

				for (FieldNode field : node.fields) {
					if (fieldTarget.equals(field.name + ";;" + field.desc)) {
						ChangeSet change = entry.getValue();

						if (change.accessChange != AccessChange.NONE) {
							AnnotationVisitor av = field.visitAnnotation(Type.getDescriptor(com.chocohead.optisine.AccessChange.class), false);
							av.visitEnum("was", Type.getDescriptor(Access.class), change.accessChange.toAccess().name());
							av.visitEnd();
						}

						if (change.finalityChange != FinalityChange.NONE) {
							AnnotationVisitor av = field.visitAnnotation(Type.getDescriptor(com.chocohead.optisine.FinalityChange.class), false);
							av.visitEnum("change", Type.getDescriptor(Finality.class), change.finalityChange.toFinality().name());
							av.visitEnd();
						}

						continue on;
					}
				}

				throw new IllegalStateException("Cannot find " + fieldTarget + " in " + node.name);
			}
		}

		if (!gainedMethods.isEmpty()) {
			on: for (String gainedMethod : gainedMethods) {
				for (MethodNode method : node.methods) {
					if (gainedMethod.equals(method.name + method.desc)) {
						method.visitAnnotation(Type.getDescriptor(OptiFineAdded.class), false).visitEnd();
						continue on;
					}
				}

				throw new IllegalStateException("Cannot find " + gainedMethod + " in " + node.name);
			}
		}

		if (!gainedFields.isEmpty()) {
			on: for (String gainedField : gainedFields) {
				for (FieldNode field : node.fields) {
					if (gainedField.equals(field.name + ";;" + field.desc)) {
						field.visitAnnotation(Type.getDescriptor(OptiFineAdded.class), false).visitEnd();
						continue on;
					}
				}

				throw new IllegalStateException("Cannot find " + gainedField + " in " + node.name);
			}
		}

		node.visitAnnotation(Type.getDescriptor(OptiFineChanged.class), false).visitEnd();
		if (useAccessChange) addInnerAccess(node, Access.class);
		if (useFinalityChange) addInnerAccess(node, Finality.class);
	}

	private static void addInnerAccess(ClassNode node, Class<?> type) {
		node.visitInnerClass(Type.getInternalName(type), Type.getInternalName(type.getEnclosingClass()), type.getSimpleName(), Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_STATIC + Opcodes.ACC_ENUM);
	}
}