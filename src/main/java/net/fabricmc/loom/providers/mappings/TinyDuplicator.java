/*
 * Copyright 2019, 2020 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers.mappings;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class TinyDuplicator {
	public static void duplicateV1Column(Path from, Path to, String copyColumn, String newColumn) throws IOException {
		try (BufferedReader reader = TinyReader.getMappingReader(from); BufferedWriter writer = Files.newBufferedWriter(to)) {
			readTiny(reader, writer, copyColumn, newColumn);
		}
	}

	private static void readTiny(BufferedReader reader, BufferedWriter writer, String copyColumn, String newColumn) throws IOException {
		List<String> headers = TinyReader.readHeaders(reader);

		int from = headers.indexOf(copyColumn) + 1;
		if (from <= 0) throw new IllegalArgumentException("Unable to find column named " + copyColumn);
		if (headers.contains(newColumn)) throw new IllegalArgumentException("Column with name " + newColumn + " already exists");

		writer.write("v1\t");
		for (String header : headers) {
			writer.write(header);
			writer.write('\t');
		}
		writer.write(newColumn);
		writer.newLine();

		for (String line = reader.readLine(); line != null; line = reader.readLine()) {
			if (line.isEmpty() || line.startsWith("#")) continue;

			String[] parts = line.split("\t");
			if (parts.length < 2) throw new IOException("Invalid tiny line (missing columns): " + line);

			writer.write(line);
			writer.write('\t');

			switch (parts[0]) {
			case "CLASS":
				assert parts.length >= 2: "Invalid tiny line (missing columns): " + line;

				writer.write(parts[from]);
				break;

			case "METHOD":
			case "FIELD":
				if (parts.length < 4) throw new IOException("Invalid tiny line (missing columns): " + line);
				if (parts[1].isEmpty()) throw new IOException("Invalid tiny line (empty src class): " + line);
				if (parts[2].isEmpty()) throw new IOException("Invalid tiny line (empty src method desc): " + line);

				writer.write(parts[2 + from]);
				break;

			default:
				throw new IOException("Unexpected tiny line (unknown type): " + line);
			}

			writer.newLine();
		}
	}
}