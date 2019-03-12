package net.fabricmc.loom.providers.mappings;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.loom.providers.mappings.MappingBlob.Mapping;
import net.fabricmc.loom.providers.mappings.MappingBlob.Mapping.Field;
import net.fabricmc.loom.providers.mappings.MappingBlob.Mapping.Method;
import net.fabricmc.loom.providers.mappings.MappingSplat.CombinedMapping;
import net.fabricmc.loom.providers.mappings.MappingSplat.CombinedMapping.CombinedField;
import net.fabricmc.loom.providers.mappings.MappingSplat.CombinedMapping.CombinedMethod;

public class MappingSplat implements Iterable<CombinedMapping> {
	public static class CombinedMapping {
		public static class CombinedField {
			public final String from, fallback, to;
			public final String fromDesc, fallbackDesc, toDesc;

			public CombinedField(String from, String fromDesc, String fallback, String fallbackDesc, String to, String toDesc) {
				this.from = from;
				this.fromDesc = fromDesc;
				this.fallback = fallback;
				this.fallbackDesc = fallbackDesc;
				this.to = to;
				this.toDesc = toDesc;
			}
		}
		public static class CombinedMethod extends CombinedField {
			private final String[] args;

			public CombinedMethod(String from, String fromDesc, String fallback, String fallbackDesc, String to, String toDesc, String[] args) {
				super(from, fromDesc, fallback, fallbackDesc, to, toDesc);

				this.args = args != null ? args : new String[0];
			}

			public boolean hasArgs() {
				return args.length > 0;
			}

			public String arg(int index) {
				return args.length > index ? args[index] : null;
			}

			public Iterable<String> namedArgs() {
				if (!hasArgs()) {
					return () -> Collections.emptyIterator();
				} else {
					return () -> new Iterator<String>() {
						private int head = args.length - 1;

						@Override
						public boolean hasNext() {
							return head >= 0;
						}

						@Override
						public String next() {
							String next = head + ": " + args[head--];
							while (hasNext() && args[head] == null) head--;
							return next;
						}
					};
				}
			}
		}

		public final String from, fallback, to;
		final Map<String, CombinedMethod> methods = new HashMap<>();
		final Map<String, CombinedField> fields = new HashMap<>();

		public CombinedMapping(String from, String fallback, String to) {
			this.from = from;
			this.fallback = fallback;
			this.to = to;
		}

		public Iterable<CombinedMethod> methods() {
			return methods.values();
		}

		public Iterable<CombinedField> fields() {
			return fields.values();
		}
	}

	private final Map<String, CombinedMapping> mappings = new HashMap<>();

	public MappingSplat(MappingBlob mappings, MappingBlob fallback) {
		UnaryOperator<String> fallbackRemapper = className -> {
			String triedMapping = fallback.tryMapName(className);
			return triedMapping != null ? triedMapping : className;
		};
		UnaryOperator<String> remapper = className -> {
			String triedMapping = mappings.tryMapName(className);
			return triedMapping != null ? triedMapping : fallbackRemapper.apply(className);
		};

		//Fallback should cover all of mapping's class names, with the potential for more it doesn't have
		for (Mapping mapping : fallback) {
			String notch = mapping.from;
			Mapping other = mappings.get(notch);

			String inter = either(mapping.to, notch);
			String name = either(other.to, inter);
			assert !inter.equals(notch) || name.equals(notch);

			CombinedMapping combined = new CombinedMapping(notch, inter, name);
			this.mappings.put(notch, combined);

			for (Method method : mapping.methods()) {
				Method otherMethod = other.method(method);
				notch = method.fromName;

				if (notch.charAt(0) == '<') {
					name = inter = notch;
				} else {
					inter = method.nameOr(notch);
					name = otherMethod.nameOr(inter);
				}
				String interDesc = makeDesc(method, fallbackRemapper);
				String nameDesc = makeDesc(otherMethod, remapper);
				String[] args = either(otherMethod.args(), method.args());

				CombinedMethod combinedMethod = new CombinedMethod(notch, method.fromDesc, inter, interDesc, name, nameDesc, args);
				combined.methods.put(notch + method.fromDesc, combinedMethod);
			}

			for (Field field : mapping.fields()) {
				Field otherField = other.field(field);
				notch = field.fromName;

				inter = field.nameOr(notch);
				String interDesc = makeDesc(field, fallbackRemapper);
				name = otherField.nameOr(inter);
				String nameDesc = makeDesc(otherField, remapper);

				CombinedField combinedField = new CombinedField(notch, field.fromDesc, inter, interDesc, name, nameDesc);
				combined.fields.put(notch + ";;" + field.fromDesc, combinedField);
			}
		}

		for (Mapping mapping : mappings) {
			String notch = mapping.from;

			Mapping other = fallback.get(notch);
			CombinedMapping combined = this.mappings.get(notch);
			if (other == null || combined == null) {
				System.err.println("Missing " + (other == null ? combined == null ? "both" : "from mappings" : "from combined"));
				throw new IllegalStateException("Extra mappings missing from fallback! Unable to find " + notch + " (" + mapping.to + ')');
			}

			for (Method method : mapping.methods()) {
				if (other.hasMethod(method)) continue;
				notch = method.fromName;

				if (notch.charAt(0) == '<') {
					//Args for constructors (and static blocks) won't appear in fallback from intermediary mappings not assigning constructor names
					String interDesc = remapDesc(method.fromDesc, fallbackRemapper);
					String nameDesc = makeDesc(method, remapper);

					CombinedMethod combinedMethod = new CombinedMethod(notch, method.fromDesc, notch, interDesc, notch, nameDesc, method.args());
					combined.methods.put(notch + method.fromDesc, combinedMethod);
				} else {
					throw new IllegalStateException("Extra mappings missing from fallback! Unable to find " + mapping.from + '#' + method.fromName + method.fromDesc + " (" + mapping.to + '#' + method.name() + ')');
				}
			}

			for (Field field : mapping.fields()) {
				if (other.hasField(field)) continue;

				throw new IllegalStateException("Extra mapping missing from fallback! Unable to find " + mapping.from + '#' + field.fromName + " (" + field.fromDesc + ')');
			}
		}
	}

	@Override
	public Iterator<CombinedMapping> iterator() {
		return mappings.values().iterator();
	}

	private static <T> T either(T prefered, T other) {
		return prefered != null ? prefered : other;
	}

	private static String makeDesc(Field method, UnaryOperator<String> remapper) {
		if (method.desc() != null) {
			return method.desc();
		} else {
			return remapDesc(method.fromDesc, remapper);
		}
	}

	private static final Pattern CLASS_FINDER = Pattern.compile("L([^;]+);");
	private static String remapDesc(String desc, UnaryOperator<String> classRemapper) {
		StringBuffer buf = new StringBuffer();

        Matcher matcher = CLASS_FINDER.matcher(desc);
        while (matcher.find()) {
            matcher.appendReplacement(buf, Matcher.quoteReplacement('L' + classRemapper.apply(matcher.group(1)) + ';'));
        }
        matcher.appendTail(buf);

        return buf.toString();
	}
}