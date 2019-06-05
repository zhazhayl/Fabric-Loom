package net.fabricmc.loom.providers.openfine;

import org.objectweb.asm.Opcodes;

public enum FinalityChange {
	NONE, GAINED, LOST;

	public static FinalityChange forAccess(int original, int access) {
		if ((original & Opcodes.ACC_FINAL) != (access & Opcodes.ACC_FINAL)) {
			return (original & Opcodes.ACC_FINAL) == Opcodes.ACC_FINAL ? LOST : GAINED;
		}

		return NONE;
	}
}