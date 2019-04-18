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

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.fabricmc.loom.providers.mappings.MappingBlob.Mapping;

public class MappingBlob implements IMappingAcceptor, Iterable<Mapping> {
	public static class Mapping {
		public static class Method extends Field {
			private String[] args = new String[0];

			public Method(String fromName, String fromDesc) {
				super(fromName, fromDesc);

				if (fromName.charAt(0) == '<') {
					//If we're an <init> (or theoretically a <clinit>) we'll never get a name so should do it now
					setMapping(fromName, null);
				}
			}

			void addArg(String name, int index) {
				if (args.length <= index) {
					String[] longerArgs = new String[index + 1];
					System.arraycopy(args, 0, longerArgs, 0, args.length);
					args = longerArgs;
				}
				args[index] = name;
			}

			public boolean hasArgs() {
				return args.length > 0;
			}

			String[] args() {
				return args;
			}

			public String arg(int index) {
				return args.length > index ? args[index] : null;
			}

			public Iterable<String> namedArgs() {
				return () -> new Iterator<String>() {
					private int head = args.length - 1;

					@Override
					public boolean hasNext() {
						return head >= 0;
					}

					@Override
					public String next() {
						return head + ": " + args[head--];
					}
				};
			}
		}
		public static class Field {
			public final String fromName, fromDesc;
			private String toName, toDesc;

			public Field(String fromName, String fromDesc) {
				this.fromName = fromName;
				this.fromDesc = fromDesc;
			}

			void setMapping(String name, String desc) {
				this.toName = name;
				this.toDesc = desc;
			}

			public String name() {
				return toName;
			}

			public String nameOr(String alternative) {
				return toName != null ? toName : alternative;
			}

			public String desc() {
				return toDesc;
			}
		}

		public final String from;
		String to;
		final Map<String, Method> methods = new HashMap<>();
		final Map<String, Field> fields = new HashMap<>();

		public Mapping(String from) {
			this.from = from;
		}

		public String to() {
			return to;
		}

		public String toOr(String alternative) {
			return to != null ? to : alternative;
		}

		public Iterable<Method> methods() {
			return methods.values();
		}

		public boolean hasMethod(Method other) {
			return methods.containsKey(other.fromName + other.fromDesc);
		}

		public Method method(Method other) {
			return method(other.fromName, other.fromDesc);
		}

		Method method(String srcName, String srcDesc) {
			return methods.computeIfAbsent(srcName + srcDesc, k -> new Method(srcName, srcDesc));
		}

		public Iterable<Field> fields() {
			return fields.values();
		}

		public boolean hasField(Field other) {
			return fields.containsKey(other.fromName + ";;" + other.fromDesc);
		}

		public Field field(Field other) {
			return field(other.fromName, other.fromDesc);
		}

		Field field(String srcName, String srcDesc) {
			return fields.computeIfAbsent(srcName + ";;" + srcDesc, k -> new Field(srcName, srcDesc));
		}
	}

	private final Map<String, Mapping> mappings = new HashMap<>();

	public Mapping get(String srcName) {
		return mappings.computeIfAbsent(srcName, Mapping::new);
	}

	public String tryMapName(String srcName) {
		Mapping mapping = mappings.get(srcName);
		return mapping != null ? mapping.to : null;
	}

	@Override
	public void acceptClass(String srcName, String dstName) {
		get(srcName).to = dstName;
	}

	@Override
	public void acceptMethod(String srcClsName, String srcName, String srcDesc, String dstClsName, String dstName, String dstDesc) {
		get(srcClsName).method(srcName, srcDesc).setMapping(dstName, dstDesc);
	}

	@Override
	public void acceptMethodArg(String srcClsName, String srcMethodName, String srcMethodDesc, int argIndex, int lvIndex, String dstArgName) {
		get(srcClsName).method(srcMethodName, srcMethodDesc).addArg(dstArgName, lvIndex);
	}

	@Override
	public void acceptMethodVar(String srcClsName, String srcMethodName, String srcMethodDesc, int varIndex, int lvIndex, String dstVarName) {
		get(srcClsName).method(srcMethodName, srcMethodDesc).addArg(dstVarName, lvIndex);
	}

	@Override
	public void acceptField(String srcClsName, String srcName, String srcDesc, String dstClsName, String dstName, String dstDesc) {
		get(srcClsName).field(srcName, srcDesc).setMapping(dstName, dstDesc);
	}

	@Override
	public Iterator<Mapping> iterator() {
		return mappings.values().iterator();
	}
}