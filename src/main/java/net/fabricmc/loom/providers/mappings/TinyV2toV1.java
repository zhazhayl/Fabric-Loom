/*
 * Copyright 2020 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers.mappings;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import net.fabricmc.mappings.visitor.ClassVisitor;
import net.fabricmc.mappings.visitor.FieldVisitor;
import net.fabricmc.mappings.visitor.LocalVisitor;
import net.fabricmc.mappings.visitor.MappingsVisitor;
import net.fabricmc.mappings.visitor.MethodVisitor;
import net.fabricmc.mappings.visitor.ParameterVisitor;
import net.fabricmc.mappings.visitor.TinyV2Visitor;

public class TinyV2toV1 {
	public static void convert(Path input, Path output) {
		convert(input, output, null);
	}

	public static void convert(Path input, Path output, Path params) {
		try (Reader in = new InputStreamReader(Files.newInputStream(input), StandardCharsets.UTF_8);
				BufferedWriter out = Files.newBufferedWriter(output);
				BufferedWriter paramOut = params != null ? Files.newBufferedWriter(params) : null) {
			TinyV2Visitor.read(in, new MappingsVisitor() {
				private final boolean writeParams = paramOut != null;
				private List<String> namespaces;
				private Runnable finaliser;

				@Override
				public void visitVersion(int major, int minor) {
					assert major == 2;
				}

				@Override
				public void visitProperty(String name) {
					// Don't need to catch escaped-names as the visitor will do it
				}

				@Override
				public void visitProperty(String name, String value) {
				}

				@Override
				public void visitNamespaces(String... namespaces) {
					if (writeParams) this.namespaces = Arrays.asList(namespaces);

					try {
						out.write("v1");
						for (String namespace : namespaces) {
							out.write('\t');
							out.write(namespace);
						}
						out.newLine();
					} catch (IOException e) {
						throw new UncheckedIOException("Error writing tiny header", e);
					}
				}

				@Override
				public ClassVisitor visitClass(long offset, String[] names) {
					try {
						out.write("CLASS");
						for (String name : names) {
							out.write('\t');
							out.write(name);
						}
						out.newLine();
					} catch (IOException e) {
						throw new UncheckedIOException("Error writing tiny class", e);
					}

					if (finaliser != null) finaliser.run(); //Ensure last parameters have definitely been written
					return new ClassVisitor() {
						class ParamHolder implements MethodVisitor {
							private final int named = namespaces.indexOf("named"), official = namespaces.indexOf("official");
							private final String className = names[named];
							private final String method, desc;
							private String[] args;

							public ParamHolder(String[] methodNames, String desc) {
								this.method = methodNames[official];
								this.desc = desc;
								finaliser = this::write;
							}

							@Override
							public ParameterVisitor visitParameter(long offset, String[] names, int index) {
								if (args.length <= index) {
									args = Arrays.copyOf(args, index + 1);
								}

								args[index] = names[named];
								return null;
							}

							@Override
							public LocalVisitor visitLocalVariable(long offset, String[] names, int localVariableIndex, int localVariableStartOffset, int localVariableTableIndex) {
								return null;
							}

							@Override
							public void visitComment(String line) {
							}

							public void write() {
								try {
									paramOut.write(className);
									paramOut.write('/');
									paramOut.write(method);
									paramOut.write(desc);
									paramOut.newLine();

									for (int i = args.length - 1; i > 0; i--) {
										if (args[i] != null) {
											paramOut.write('\t');
											paramOut.write(args[i]);
											paramOut.newLine();
										}
									}
								} catch (IOException e) {
									throw new UncheckedIOException("Error writing parameters in " + className + '#' + method + desc, e);
								}
							}
						}
						private ParamHolder currentMethod;

						@Override
						public MethodVisitor visitMethod(long offset, String[] names, String descriptor) {
							try {
								out.write("METHOD\t");
								out.write(descriptor);
								for (String name : names) {
									out.write('\t');
									out.write(name);
								}
								out.newLine();
							} catch (IOException e) {
								throw new UncheckedIOException("Error writing tiny method", e);
							}

							if (writeParams) {
								writeParams();
								return currentMethod = new ParamHolder(names, descriptor);
							} else {
								return null;
							}
						}

						@Override
						public FieldVisitor visitField(long offset, String[] names, String descriptor) {
							try {
								out.write("FIELD\t");
								out.write(descriptor);
								for (String name : names) {
									out.write('\t');
									out.write(name);
								}
								out.newLine();
							} catch (IOException e) {
								throw new UncheckedIOException("Error writing tiny field", e);
							}

							if (writeParams) writeParams();
							return null;
						}

						private void writeParams() {
							assert writeParams;
							if (currentMethod != null) currentMethod.write();
							finaliser = null;
						}

						@Override
						public void visitComment(String line) {
						}
					};
				}

				@Override
				public void finish() {
					if (finaliser != null) finaliser.run();
				}
			});
		} catch (IOException e) {
			throw new UncheckedIOException("Error preparing to convert " + input + " to " + output, e);
		}
	}
}