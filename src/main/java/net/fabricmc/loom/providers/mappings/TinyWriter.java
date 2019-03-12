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