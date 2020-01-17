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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.stream.Collectors;

public class TinyWriter implements AutoCloseable {
	private final String[] namespaces;
	private final Writer writer;

	public TinyWriter(Path file, String... namespaces) throws IOException {
		Collection<String> uniqueNamespaces = new HashSet<>();
		Collections.addAll(uniqueNamespaces, namespaces);
		if (uniqueNamespaces.size() != namespaces.length) {
			Collection<String> namespacePool = Arrays.asList(namespaces);
			throw new IllegalArgumentException(uniqueNamespaces.stream().filter(namespace -> Collections.frequency(namespacePool, namespace) > 1).collect(Collectors.joining(", ", "Duplicate namespaces: ", "")));
		}

		writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		writer.write("v1");
		for (String namespace : this.namespaces = namespaces) {
			writer.write('\t');
			writer.write(namespace);
		}
		writer.write('\n');
	}

	private void ensureComplete(String... names) {
		if (names.length < namespaces.length) {
			throw new IllegalArgumentException("Missing names for namespaces " + Arrays.asList(namespaces).subList(names.length, namespaces.length));
		}
	}

	public void acceptClass(String... names) {
		ensureComplete(names);
		try {
			writer.write("CLASS");
			for (String name : names) {
				writer.write('\t');
				if (name != null) writer.write(name);
			}
			writer.write('\n');
		} catch (IOException e) {
			throw new UncheckedIOException("Error writing tiny class", e);
		}
	}

	public void acceptMethod(String notchClass, String desc, String... names) {
		ensureComplete(names);
		try {
			writer.write("METHOD\t");
			writer.write(notchClass);
			writer.write('\t');
			writer.write(desc);
			for (String name : names) {
				writer.write('\t');
				if (name != null) writer.write(name);
			}
			writer.write('\n');
		} catch (IOException e) {
			throw new UncheckedIOException("Error writing tiny method", e);
		}
	}

	public void acceptField(String notchClass, String desc, String... names) {
		ensureComplete(names);
		try {
			writer.write("FIELD\t");
			writer.write(notchClass);
			writer.write('\t');
			writer.write(desc);
			for (String name : names) {
				writer.write('\t');
				if (name != null) writer.write(name);
			}
			writer.write('\n');
		} catch (IOException e) {
			throw new UncheckedIOException("Error writing tiny field", e);
		}
	}

	public void flush() throws IOException {
		if (writer != null) writer.flush();
	}

	@Override
	public void close() throws IOException {
		if (writer != null) writer.close();
	}
}