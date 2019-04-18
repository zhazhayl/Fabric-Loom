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

public class TinyWriter implements AutoCloseable {
	private final Writer writer;

	public TinyWriter(Path file) throws IOException {
		writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		writer.write("v1\t");
		writer.write("official");
		writer.write('\t');
		writer.write("named");
		writer.write('\t');
		writer.write("intermediary");
		writer.write('\n');
	}

	public void acceptClass(String notchName, String namedName, String interName) {
		try {
			writer.write("CLASS\t");
			writer.write(notchName);
			writer.write('\t');
			writer.write(namedName);
			writer.write('\t');
			writer.write(interName);
			writer.write('\n');
		} catch (IOException e) {
			throw new UncheckedIOException("Error writing tiny class", e);
		}
	}

	public void acceptMethod(String notchClass, String notchName, String desc, String namedName, String interName) {
		try {
			writer.write("METHOD\t");
			writer.write(notchClass);
			writer.write('\t');
			writer.write(desc);
			writer.write('\t');
			writer.write(notchName);
			writer.write('\t');
			writer.write(namedName);
			writer.write('\t');
			writer.write(interName);
			writer.write('\n');
		} catch (IOException e) {
			throw new UncheckedIOException("Error writing tiny method", e);
		}
	}

	public void acceptField(String notchClass, String notchName, String desc, String namedName, String interName) {
		try {
			writer.write("FIELD\t");
			writer.write(notchClass);
			writer.write('\t');
			writer.write(desc);
			writer.write('\t');
			writer.write(notchName);
			writer.write('\t');
			writer.write(namedName);
			writer.write('\t');
			writer.write(interName);
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