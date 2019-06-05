package net.fabricmc.loom.providers.openfine;

import java.util.Objects;

import org.objectweb.asm.tree.FieldNode;

public class FieldComparison {
	public final FieldNode node;
	public final AccessChange access;
	public final FinalityChange finality;

	public FieldComparison(FieldNode original, FieldNode patched) {
		assert Objects.equals(original.name, patched.name);
		assert Objects.equals(original.desc, patched.desc);
		node = patched;

		access = AccessChange.forAccess(original.access, patched.access);
		finality = FinalityChange.forAccess(original.access, patched.access);
	}
}
