package net.fabricmc.loom.providers.openfine;

public class ChangeSet {
	public final AccessChange accessChange;
	public final FinalityChange finalityChange;

	public ChangeSet(AccessChange accessChange, FinalityChange finalityChange) {
		this.accessChange = accessChange;
		this.finalityChange = finalityChange;
	}
}