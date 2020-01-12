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
			Queue<String> contextNamedStack = Collections.asLifoQueue(new ArrayDeque<>());
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
							contextNamedStack.remove();
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
					String obfName = parts[1];
					if (indent >= 1) {//Inner classes have certain inconsistencies...
						if (obfName.indexOf('/') > 0) {//Some inner classes carry the named outer class, others the obf'd outer class
							int split = obfName.lastIndexOf('$');
							assert split > 2; //Should be at least a/b$c
							String context = contextStack.peek();
							if (context == null || context.charAt(0) != 'C') throw new IOException("Invalid enigma line (named inner class without outer class name): " + line);
							obfName = context.substring(1) + '$' + obfName.substring(split + 1);
						} else if (obfName.indexOf('$') < 1) {//Some inner classes don't carry any outer name at all
							assert obfName.indexOf('$') == -1 && obfName.indexOf('/') == -1;
							String context = contextStack.peek();
							if (context == null || context.charAt(0) != 'C') throw new IOException("Invalid enigma line (named inner class without outer class name): " + line);
							obfName = context.substring(1) + '$' + obfName;
						}
					}
					contextStack.add('C' + obfName);
					indent++;
					if (parts.length == 3) {
						String className;
						if (indent > 1) {//If we're an indent in, we're an inner class so want the outer classes's name
							String context = contextNamedStack.peek();
							if (context == null || context.charAt(0) != 'C') throw new IOException("Invalid enigma line (named inner class without outer class name): " + line);
							//Named inner classes shouldn't ever carry the outer class's package + name
							assert !parts[2].startsWith(context.substring(1)): "Pre-prefixed enigma class name: " + parts[2];
							className = context.substring(1) + '$' + parts[2];
						} else {
							className = parts[2];
						}
						contextNamedStack.add('C' + className);
						mappingAcceptor.acceptClass(obfName, className);
					} else {
						contextNamedStack.add('C' + obfName); //No name, but we still need something to avoid underflowing
					}
					break;
				case "METHOD": {
					if (parts.length < 3 || parts.length > 4) throw new IOException("invalid enigma line (missing/extra columns): "+line);
					if (!parts[parts.length - 1].startsWith("(")) throw new IOException("invalid enigma line (invalid method desc): "+line);
					String context = contextStack.peek();
					if (context == null || context.charAt(0) != 'C') throw new IOException("invalid enigma line (method without class): "+line);
					contextStack.add("M"+parts[1]+parts[parts.length - 1]);
					indent++;
					if (parts.length == 4) {
						mappingAcceptor.acceptMethod(context.substring(1), parts[1], parts[3], contextNamedStack.peek().substring(1), parts[2], null);
						contextNamedStack.add('M' + parts[2]);
					} else {
						contextNamedStack.add('M' + parts[1]); //No name, but we still need something to avoid underflowing
					}
					break;
				}
				case "ARG":
				case "VAR": {
					if (parts.length < 2 || parts.length > 3) throw new IOException("invalid enigma line (missing/extra columns): "+line);
					String methodContext = contextStack.poll();
					if (methodContext == null || methodContext.charAt(0) != 'M') throw new IOException("invalid enigma line (arg without method): "+line);
					String classContext = contextStack.peek();
					if (classContext == null || classContext.charAt(0) != 'C') throw new IllegalStateException();
					contextStack.add(methodContext);

					int index = Integer.parseInt(parts[1]);
					boolean isArg = parts[0].equals("ARG");

					if (parts.length == 3) {
						int methodDescStart = methodContext.indexOf('(');
						assert methodDescStart != -1;

						String srcClsName = classContext.substring(1);
						String srcMethodName = methodContext.substring(1, methodDescStart);
						String srcMethodDesc = methodContext.substring(methodDescStart);
						String name = parts[2];

						if (isArg) {
							mappingAcceptor.acceptMethodArg(srcClsName, srcMethodName, srcMethodDesc, index, name);
						} else {
							throw new UnsupportedOperationException("Method var " + index + " in " + srcClsName + '#' + methodContext.substring(1));
						}

						contextNamedStack.add((isArg ? 'A' : 'V') + name);
					} else {
						contextNamedStack.add(isArg ? "A" : "V");
					}

					indent++;
					contextStack.add((isArg ? "A" : "V") + index);
					break;
				}
				case "FIELD":
					if (parts.length < 3 || parts.length > 4) throw new IOException("invalid enigma line (missing/extra columns): "+line);
					String context = contextStack.peek();
					if (context == null || context.charAt(0) != 'C') throw new IOException("invalid enigma line (field without class): "+line);
					assert parts[1].indexOf('#') < 0;
					assert parts[parts.length - 1].indexOf('#') < 0;
					contextStack.add('F' + parts[1] + '#' + parts[parts.length - 1]);
					indent++;
					if (parts.length == 4) {
						mappingAcceptor.acceptField(context.substring(1), parts[1], parts[3], contextNamedStack.peek().substring(1), parts[2], null);
						contextNamedStack.add('F' + parts[2]);
					} else {
						contextNamedStack.add('F' + parts[1]); //No name, but we still need something to avoid underflowing
					}
					break;
				case "COMMENT":
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