/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers.openfine;

public class ChangeSet {
	public final AccessChange accessChange;
	public final FinalityChange finalityChange;

	public ChangeSet(AccessChange accessChange, FinalityChange finalityChange) {
		this.accessChange = accessChange;
		this.finalityChange = finalityChange;
	}
}