package net.fabricmc.loom.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;

import org.objectweb.asm.commons.Remapper;

import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.transform.ByteArrayZipEntryTransformer;
import org.zeroturnaround.zip.transform.StreamZipEntryTransformer;
import org.zeroturnaround.zip.transform.ZipEntryTransformerEntry;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.task.RemapJar;
import net.fabricmc.stitch.util.Pair;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

public class AccessTransformerHelper {
	private interface ClassProcessor {//Consumer<String> which throws an IOException
		void accept(String name) throws IOException;
	}
	private interface MethodProcessor {//BiConsumer<String, String> which throws an IOException
		void accept(String className, String method) throws IOException;
	}
	private static final String MAGIC_AT_NAME = "silky.at";

	public static boolean obfATs(LoomGradleExtension extension, RemapJar task, TinyRemapper tiny, OutputConsumerPath consumer) throws IOException {
		if (extension.hasAT()) {
			File at = new File(task.getTemporaryDir(), MAGIC_AT_NAME);

			try (BufferedWriter writer = new BufferedWriter(new FileWriter(at))) {
				writer.write("#Remapped from " + extension.getAT().getName());
				writer.newLine();

				readATs(new FileReader(extension.getAT()), writer, tiny.getRemapper());
			}

			consumer.addNonClassFile(at.toPath(), at.getName()); //Add at to the root of the obf'd jar
			return true;
        } else {
            return false;
        }
    }

	public static boolean deobfATs(TinyRemapper tiny, File jar) {
		return ZipUtil.transformEntry(jar, new ZipEntryTransformerEntry(MAGIC_AT_NAME, new StreamZipEntryTransformer() {
			@Override
			protected void transform(ZipEntry zipEntry, InputStream in, OutputStream out) throws IOException {
				try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
					readATs(new InputStreamReader(in, StandardCharsets.UTF_8), writer, tiny.getRemapper());
				}
			}
		}));
	}

	private static void readATs(Reader from, BufferedWriter to, Remapper remapper) throws IOException {
		readATs(from, name -> {
			to.write(remapper.map(name));
			to.newLine();
		}, (className, method) -> {
			to.write(remapper.map(className));
			to.write(' ');
			//Abuse the fact AsmRemapper merges the name and desc together to save us having to re-split them up
			to.write(remapper.mapMethodName(className, method, ""));
			to.newLine();
		});
	}

	public static Set<Pair<String, String>> loadATs(File from) throws IOException {
		Set<Pair<String, String>> targets = new HashSet<>();

		readATs(new FileReader(from), name -> {
			targets.add(Pair.of(name, null));
		}, (className, method) -> {
			targets.add(Pair.of(className, method));
		});

    	return targets;
	}

    private static void readATs(Reader from, ClassProcessor rawClassEater, MethodProcessor methodEater) throws IOException {
    	try (BufferedReader reader = new BufferedReader(from)) {
			for (String line = reader.readLine(); line != null; line = reader.readLine()) {
				line = line.trim(); //Clip off whitespace
				if (line.isEmpty() || line.startsWith("#")) continue;

				int split = line.indexOf(' ');
				if (split > 0) {
					methodEater.accept(line.substring(0, split++), line.substring(split));
				} else {
					rawClassEater.accept(line);
				}
			}
		}
    }

    public static class ZipAT extends ByteArrayZipEntryTransformer {
    	private boolean hasTransformed = false;

    	public ZipAT() {
			// TODO Auto-generated constructor stub
		}

		@Override
		protected byte[] transform(ZipEntry zipEntry, byte[] input) throws IOException {
			hasTransformed = true;
			return null;
		}

		public boolean didTransform() {
			return hasTransformed;
		}
    }

    public static ZipEntryTransformerEntry[] makeZipATs(Map<String, Set<String>> transforms, String wildcard) {
    	return null;
    }
}