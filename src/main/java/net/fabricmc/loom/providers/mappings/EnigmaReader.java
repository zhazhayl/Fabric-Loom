package net.fabricmc.loom.providers.mappings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Queue;
import java.util.stream.Stream;

public class EnigmaReader {
	static final boolean LEGACY = true;

	public static void readEnigma(Path dir, IMappingAcceptor mappingAcceptor) throws IOException {
		try (Stream<Path> stream = Files.find(FileSystems.newFileSystem(dir, null).getPath("/"),
				Integer.MAX_VALUE,
				(path, attr) -> attr.isRegularFile() && path.getFileName().toString().endsWith(".mapping"),
				FileVisitOption.FOLLOW_LINKS)) {
			stream.forEach(file -> readEnigmaFile(file, mappingAcceptor));
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
	}

	private static void readEnigmaFile(Path file, IMappingAcceptor mappingAcceptor) {
		try (BufferedReader reader = Files.newBufferedReader(file)) {
			String line;
			Queue<String> contextStack = Collections.asLifoQueue(new ArrayDeque<>());
			int indent = 0;

			while ((line = reader.readLine()) != null) {
				if (line.isEmpty()) continue;

				int newIndent = 0;
				while (newIndent < line.length() && line.charAt(newIndent) == '\t') newIndent++;
				int indentChange = newIndent - indent;

				if (indentChange != 0) {
					if (indentChange < 0) {
						for (int i = 0; i < -indentChange; i++) {
							contextStack.remove();
						}

						indent = newIndent;
					} else {
						throw new IOException("invalid enigma line (invalid indentation change): "+line);
					}
				}

				line = line.substring(indent);
				String[] parts = line.split(" ");

				switch (parts[0]) {
				case "CLASS":
					if (parts.length < 2 || parts.length > 3) throw new IOException("invalid enigma line (missing/extra columns): "+line);
					String className;
					if (indent > 0) {//If we're an intent in we're an inner class so want the outer classes's name
						StringBuilder classNameBits = new StringBuilder(parts[1]);
						String context = contextStack.peek();
						if (context == null || context.charAt(0) != 'C') throw new IOException("Invalid enigma line (indented class without outer class): "+line);
						classNameBits.insert(0, '$');
						classNameBits.insert(0, context);
						className = classNameBits.toString();
					} else {
						className = parts[1];
					}
					contextStack.add("C"+className);
					indent++;
					if (parts.length == 3) mappingAcceptor.acceptClass(className, parts[2]);
					break;
				case "METHOD": {
					if (parts.length < 3 || parts.length > 4) throw new IOException("invalid enigma line (missing/extra columns): "+line);
					if (!parts[parts.length - 1].startsWith("(")) throw new IOException("invalid enigma line (invalid method desc): "+line);
					String context = contextStack.peek();
					if (context == null || context.charAt(0) != 'C') throw new IOException("invalid enigma line (method without class): "+line);
					contextStack.add("M"+parts[1]+parts[parts.length - 1]);
					indent++;
					if (parts.length == 4) mappingAcceptor.acceptMethod(context.substring(1), parts[1], parts[3], null, parts[2], null);
					break;
				}
				case "ARG":
				case "VAR": {
					if (parts.length != 3) throw new IOException("invalid enigma line (missing/extra columns): "+line);
					String methodContext = contextStack.poll();
					if (methodContext == null || methodContext.charAt(0) != 'M') throw new IOException("invalid enigma line (arg without method): "+line);
					String classContext = contextStack.peek();
					if (classContext == null || classContext.charAt(0) != 'C') throw new IllegalStateException();
					contextStack.add(methodContext);
					int methodDescStart = methodContext.indexOf('(');
					assert methodDescStart != -1;

					String srcClsName = classContext.substring(1);
					String srcMethodName = methodContext.substring(1, methodDescStart);
					String srcMethodDesc = methodContext.substring(methodDescStart);
					int index = Integer.parseInt(parts[1]);
					int lvIndex = -1;
					String name = parts[2];

					if (LEGACY) {
						lvIndex = index;
						index = -1;
					}

					if (parts[0].equals("ARG")) {
						mappingAcceptor.acceptMethodArg(srcClsName, srcMethodName, srcMethodDesc, index, lvIndex, name);
					} else {
						mappingAcceptor.acceptMethodVar(srcClsName, srcMethodName, srcMethodDesc, index, lvIndex, name);
					}

					break;
				}
				case "FIELD":
					if (parts.length != 4) throw new IOException("invalid enigma line (missing/extra columns): "+line);
					String context = contextStack.peek();
					if (context == null || context.charAt(0) != 'C') throw new IOException("invalid enigma line (field without class): "+line);
					mappingAcceptor.acceptField(context.substring(1), parts[1], parts[3], null, parts[2], null);
					break;
				default:
					throw new IOException("invalid enigma line (unknown type): "+line);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}