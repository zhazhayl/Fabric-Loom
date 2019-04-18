/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2019 Chocohead
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package net.fabricmc.loom.providers.mappings;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class TinyReader {
	public static void readTiny(Path file, IMappingAcceptor mappingAcceptor) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(file)) {
			readTiny(reader, mappingAcceptor);
		}
	}

	private static void readTiny(BufferedReader reader, IMappingAcceptor mappingAcceptor) throws IOException {
		boolean firstLine = true;
		String line;

		while ((line = reader.readLine()) != null) {
			if (firstLine) {
				firstLine = false;
				if (!line.startsWith("v1\t")) throw new IOException("invalid/unsupported tiny file (incorrect header)");
				continue;
			}

			if (line.isEmpty() || line.startsWith("#")) continue;

			String[] parts = line.split("\t");
			if (parts.length < 3) throw new IOException("invalid tiny line (missing columns): "+line);

			switch (parts[0]) {
			case "CLASS":
				if (parts.length != 3) throw new IOException("invalid tiny line (extra columns): "+line);
				if (parts[1].isEmpty()) throw new IOException("invalid tiny line (empty src class): "+line);
				if (parts[2].isEmpty()) throw new IOException("invalid tiny line (empty dst class): "+line);

				mappingAcceptor.acceptClass(parts[1], parts[2]);
				break;
			case "CLS-CMT":
				/*if (parts.length != 3) throw new IOException("invalid tiny line (extra columns): "+line);
				if (parts[1].isEmpty()) throw new IOException("invalid tiny line (empty src class): "+line);
				if (parts[2].isEmpty()) throw new IOException("invalid tiny line (empty class comment): "+line);

				mappingAcceptor.acceptClassComment(parts[1], unescape(parts[2]));*/
				break;
			case "METHOD":
				if (parts.length != 5) throw new IOException("invalid tiny line (missing/extra columns): "+line);
				if (parts[1].isEmpty()) throw new IOException("invalid tiny line (empty src class): "+line);
				if (parts[2].isEmpty()) throw new IOException("invalid tiny line (empty src method desc): "+line);
				if (parts[3].isEmpty()) throw new IOException("invalid tiny line (empty src method name): "+line);
				if (parts[4].isEmpty()) throw new IOException("invalid tiny line (empty dst method name): "+line);

				mappingAcceptor.acceptMethod(parts[1], parts[3], parts[2], null, parts[4], null);
				break;
			case "MTH-CMT":
				/*if (parts.length != 5) throw new IOException("invalid tiny line (missing/extra columns): "+line);
				if (parts[1].isEmpty()) throw new IOException("invalid tiny line (empty src class): "+line);
				if (parts[2].isEmpty()) throw new IOException("invalid tiny line (empty src method desc): "+line);
				if (parts[3].isEmpty()) throw new IOException("invalid tiny line (empty src method name): "+line);
				if (parts[4].isEmpty()) throw new IOException("invalid tiny line (empty method comment): "+line);

				mappingAcceptor.acceptMethodComment(parts[1], parts[3], parts[2], unescape(parts[4]));*/
				break;
			case "MTH-ARG":
			case "MTH-VAR":
				if (parts.length != 6) throw new IOException("invalid tiny line (missing/extra columns): "+line);
				if (parts[1].isEmpty()) throw new IOException("invalid tiny line (empty src class): "+line);
				if (parts[2].isEmpty()) throw new IOException("invalid tiny line (empty src method desc): "+line);
				if (parts[3].isEmpty()) throw new IOException("invalid tiny line (empty src method name): "+line);
				if (parts[4].isEmpty()) throw new IOException("invalid tiny line (empty method arg/var index): "+line);
				if (parts[5].isEmpty()) throw new IOException("invalid tiny line (empty dst method arg/var name): "+line);

				if (parts[0].equals("MTH-ARG")) {
					mappingAcceptor.acceptMethodArg(parts[1], parts[3], parts[2], Integer.parseInt(parts[4]), -1, parts[5]);
				} else {
					mappingAcceptor.acceptMethodVar(parts[1], parts[3], parts[2], Integer.parseInt(parts[4]), -1, parts[5]);
				}

				break;
			case "FIELD":
				if (parts.length != 5) throw new IOException("invalid tiny line (missing/extra columns): "+line);
				if (parts[1].isEmpty()) throw new IOException("invalid tiny line (empty src class): "+line);
				if (parts[2].isEmpty()) throw new IOException("invalid tiny line (empty src field desc): "+line);
				if (parts[3].isEmpty()) throw new IOException("invalid tiny line (empty src field name): "+line);
				if (parts[4].isEmpty()) throw new IOException("invalid tiny line (empty dst field name): "+line);

				mappingAcceptor.acceptField(parts[1], parts[3], parts[2], null, parts[4], null);
				break;
			case "FLD-CMT":
				/*if (parts.length != 5) throw new IOException("invalid tiny line (missing/extra columns): "+line);
				if (parts[1].isEmpty()) throw new IOException("invalid tiny line (empty src class): "+line);
				if (parts[2].isEmpty()) throw new IOException("invalid tiny line (empty src field desc): "+line);
				if (parts[3].isEmpty()) throw new IOException("invalid tiny line (empty src field name): "+line);
				if (parts[4].isEmpty()) throw new IOException("invalid tiny line (empty field comment): "+line);

				mappingAcceptor.acceptFieldComment(parts[1], parts[3], parts[2], unescape(parts[4]));*/
				break;
			default:
				throw new IOException("invalid tiny line (unknown type): "+line);
			}
		}

		if (firstLine) throw new IOException("invalid tiny mapping file");
	}

}