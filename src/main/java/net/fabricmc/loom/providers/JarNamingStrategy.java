/*
 * Copyright 2020 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers;

public final class JarNamingStrategy {
	private final String version, mappings, extra;

	private JarNamingStrategy(String version, String mappings, String extra) {
		this.version = version;
		this.mappings = mappings;
		this.extra = extra;
	}

	public static JarNamingStrategy forVersion(String version) {
		if (version == null) throw new IllegalArgumentException("Null version");
		return new JarNamingStrategy(version, null, null);
	}

	public JarNamingStrategy withMappings(String mappings) {
		if (mappings == null) throw new IllegalArgumentException("Null mappings");
		return new JarNamingStrategy(version, mappings, extra);
	}

	public JarNamingStrategy withExtra(String extra) {
		if (extra == null) throw new IllegalArgumentException("Null extra");
		return new JarNamingStrategy(version, mappings, extra);
	}

	public String getVersion() {
		return version;
	}

	public String getMappings() {
		if (mappings == null) throw new UnsupportedOperationException("Strategy does not support mappings");
		return mappings;
	}

	public String getExtra() {
		return extra != null ? extra : "";
	}

	@Override
	public String toString() {
		return "NamingStrategy[version = " + version + (mappings != null ? ", mappings = " + mappings : "") + (extra != null ? ", extra = " + extra : "") + ']';
	}
}