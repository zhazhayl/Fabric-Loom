/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers.openfine;

import org.objectweb.asm.Opcodes;

import com.chocohead.optisine.AccessChange.Access;

public enum AccessChange {
	NONE(null), PRIVATE(Access.PRIVATE), PACKAGE(Access.PACKAGE), PROTECTED(Access.PROTECTED), PUBLIC(Access.PUBLIC);

	private AccessChange(Access access) {
		this.access = access;
	}

	private final Access access;
	public Access toAccess() {
		if (access == null) throw new UnsupportedOperationException("Cannot convert AccessChange#" + name() + " to Access!");
		return access;
	}

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