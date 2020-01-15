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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;

import net.fabricmc.tinyremapper.TinyUtils;

public class TinyReader {
	static BufferedReader getMappingReader(Path file) throws IOException {
		InputStream in = Files.newInputStream(file);

		if (file.getFileName().toString().endsWith(".gz")) {
			in = new GZIPInputStream(in);
		}

		return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
	}

	public static List<String> readHeaders(Path file) throws IOException {
		try (BufferedReader reader = getMappingReader(file)) {
			return readHeaders(reader);
		}
	}

	static List<String> readHeaders(BufferedReader reader) throws IOException {
		String header = reader.readLine();

		if (header == null) {
			return Collections.emptyList(); //No headers in an empty file
		} else if (header.startsWith("v1\t")) {
			return Arrays.asList(header.substring(3).split("\t"));
		} else if (header.startsWith("tiny\t2\t")) {
			String[] bits;
			return Arrays.asList(bits = header.split("\t")).subList(3, bits.length);
		} else {
			throw new IOException("Unlikely tiny file given " + header);
		}
	}

	public static void readTiny(Path file, String from, String to, IMappingAcceptor mappingAcceptor) throws IOException {
		try (BufferedReader reader = getMappingReader(file)) {
			TinyUtils.read(reader, from, to, (classFrom, name) -> {
				mappingAcceptor.acceptClass(classFrom, name);
			}, (fieldFrom, name) -> {
				mappingAcceptor.acceptField(fieldFrom.owner, fieldFrom.name, fieldFrom.desc, null, name, null);
			}, (methodFrom, name) -> {
				mappingAcceptor.acceptMethod(methodFrom.owner, methodFrom.name, methodFrom.desc, null, name, null);
			}, (methodFrom, args) -> {
				for (int i = args.length - 1; i > 0; i--) {
					if (args[i] != null) {
						mappingAcceptor.acceptMethodArg(methodFrom.owner, methodFrom.name, methodFrom.desc, i, args[i]);
					}
				}
			});
		}
	}
}