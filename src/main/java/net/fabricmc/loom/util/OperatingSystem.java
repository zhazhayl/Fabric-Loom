/*
 * Copyright 2019, 2021 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.util;

import java.util.Locale;

import com.google.gson.annotations.SerializedName;

public enum OperatingSystem {
	@SerializedName(value = "windows", alternate = "win")
	WINDOWS("win"),
	@SerializedName(value = "osx", alternate = "mac")
	OSX("mac"),
	@SerializedName(value = "linux", alternate = "unix")
	LINUX("linux", "unix");
	public static final transient OperatingSystem ACTIVE = get();

	private final String[] names;

	private OperatingSystem(String... names) {
		this.names = names;
	}

	private static OperatingSystem get() {
		String osName = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);

		for (OperatingSystem os : values()) {
			for (String name : os.names) {
				if (osName.contains(name)) {
					return os;
				}
			}
		}

		throw new IllegalStateException("Unable to find OS for current system: " + osName);
	}

	public static boolean is64Bit() {
		return System.getProperty("sun.arch.data.model").contains("64");
	}
}
