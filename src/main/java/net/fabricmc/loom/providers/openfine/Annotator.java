package net.fabricmc.loom.providers.openfine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;

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
		if (accessChange != AccessChange.NONE) {

		}

		if (finalityChange != FinalityChange.NONE) {

		}

		if (!gainedInterfaces.isEmpty()) {

		}

		if (!lostInterfaces.isEmpty() || !lostMethods.isEmpty() || !lostFields.isEmpty()) {

		}

		if (!changedMethods.isEmpty()) {

		}

		if (!changedFields.isEmpty()) {

		}

		if (!gainedMethods.isEmpty()) {

		}

		if (!gainedFields.isEmpty()) {

		}
	}
}