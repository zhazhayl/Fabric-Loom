package net.fabricmc.loom.providers.openfine;

import org.objectweb.asm.Opcodes;

public enum AccessChange {
	NONE, PRIVATE, PACKAGE, PROTECTED, PUBLIC;

	public static AccessChange forAccess(int original, int access) {
		if ((original & ACCESSES) != (access & ACCESSES)) {
			if ((original & Opcodes.ACC_PUBLIC) == Opcodes.ACC_PUBLIC) {
				return PUBLIC;
			}
			if ((original & Opcodes.ACC_PROTECTED) == Opcodes.ACC_PROTECTED) {
				return PROTECTED;
			}
			if ((original & Opcodes.ACC_PRIVATE) == Opcodes.ACC_PRIVATE) {
				return PRIVATE;
			}
			return PACKAGE;
		}

		return NONE;
	}

	private static final int ACCESSES = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE;
}