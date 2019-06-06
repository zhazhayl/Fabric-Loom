package net.fabricmc.loom.providers.openfine;

import org.objectweb.asm.Opcodes;

import com.chocohead.optisine.FinalityChange.Finality;

public enum FinalityChange {
	NONE(null), GAINED(Finality.GAIN), LOST(Finality.LOST);

	private FinalityChange(Finality finality) {
		this.finality = finality;
	}

	private final Finality finality;
	public Finality toFinality() {
		if (finality == null) throw new UnsupportedOperationException("Cannot convert FinalityChange#" + name() + " to Finality!");
		return finality;
	}

	public static FinalityChange forAccess(int original, int access) {
		if ((original & Opcodes.ACC_FINAL) != (access & Opcodes.ACC_FINAL)) {
			return (original & Opcodes.ACC_FINAL) == Opcodes.ACC_FINAL ? LOST : GAINED;
		}

		return NONE;
	}
}