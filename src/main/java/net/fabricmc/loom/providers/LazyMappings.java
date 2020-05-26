/*
 * Copyright 2020 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers;

import java.io.IOException;
import java.nio.file.Path;

import net.fabricmc.loom.providers.LazyMappings.ActiveMappings;

interface LazyMappings {
	interface ActiveMappings extends AutoCloseable {
		Path getMappings();

		@Override
		default void close() throws IOException {
		}
	}

	ActiveMappings open() throws IOException;
}

final class DirectMappings implements ActiveMappings {
	private final Path mappings;

	public DirectMappings(Path mappings) {
		this.mappings = mappings;
	}

	@Override
	public Path getMappings() {
		return mappings;
	}

	@Override
	public void close() {
	}
}