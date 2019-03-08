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

			public String arg(int index) {
				return args.length > index ? args[index] : null;
			}

			public Iterable<String> namedArgs() {
				return () -> new Iterator<String>() {
					int head = args.length - 1;

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

		public Method method(Method other) {
			return method(other.fromName, other.fromDesc);
		}

		Method method(String srcName, String srcDesc) {
			return methods.computeIfAbsent(srcName + srcDesc, k -> new Method(srcName, srcDesc));
		}

		public Iterable<Field> fields() {
			return fields.values();
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