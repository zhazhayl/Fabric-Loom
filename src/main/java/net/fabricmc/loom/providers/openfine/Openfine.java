package net.fabricmc.loom.providers.openfine;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.apache.commons.io.FilenameUtils;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.logging.Logger;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;

import net.fabricmc.mappings.EntryTriple;
import net.fabricmc.mappings.FieldEntry;
import net.fabricmc.mappings.MappingsProvider;
import net.fabricmc.stitch.util.StitchUtil;
import net.fabricmc.stitch.util.StitchUtil.FileSystemDelegate;

public class Openfine {
	public static File process(Logger logger, String mcVersion, File minecraft, File optifineJar) throws IOException {
		OptiFineVersion optifine = new OptiFineVersion(optifineJar);
		logger.info("Loaded OptiFine " + optifine.version);

		if (!optifine.supports(mcVersion)) {
			throw new InvalidUserDataException("Incompatible OptiFine version, requires " + optifine.minecraftVersion + " rather than " + mcVersion);
		}

		if (optifine.isInstaller) {
			File installer = optifineJar;
			optifineJar = new File(minecraft.getParentFile(), "optifine" + File.pathSeparator + FilenameUtils.removeExtension(optifineJar.getName()) + "-extract.jar");
			if (!optifineJar.exists()) extract(logger, minecraft, installer, optifineJar);
		}

		File merged = optifineJar = new File(minecraft.getParentFile(), "optifine" + File.pathSeparator + FilenameUtils.removeExtension(minecraft.getName()) + "-optifined.jar");
		if (!merged.exists()) merge(logger, minecraft, optifineJar, merged);

		return merged;
	}

	private static void extract(Logger logger, File minecraft, File installer, File to) throws IOException {
		logger.info("Extracting OptiFine into " + to);
		try {
			URLClassLoader classLoader = new URLClassLoader(new URL[] {installer.toURI().toURL()}, Openfine.class.getClassLoader());

			Class<?> clazz = classLoader.loadClass("optifine.Patcher");
			Method method = clazz.getDeclaredMethod("process", File.class, File.class, File.class);
			method.invoke(null, minecraft, installer, to);

			classLoader.close();
		} catch (MalformedURLException e) {
			throw new RuntimeException("Unable to use OptiFine jar at " + installer.getAbsolutePath(), e);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException("Error running OptiFine installer", e);
		}
	}

	private static void merge(Logger logger, File minecraft, File optifine, File to) throws IOException {
		logger.info("Merging OptiFine into " + to);

		Set<String> mcEntries, optifineEntries, intersection;
		try (JarFile mcJar = new JarFile(minecraft); JarFile optifineJar = new JarFile(optifine)) {
			//Comparison on ZipEntries is poorly defined so we'll use the entry names for equality
			mcEntries = ImmutableSet.copyOf(Iterators.transform(Iterators.forEnumeration(mcJar.entries()), JarEntry::getName));
			optifineEntries = ImmutableSet.copyOf(Iterators.transform(Iterators.forEnumeration(optifineJar.entries()), JarEntry::getName));

			if (mcEntries.size() > optifineEntries.size()) {
				intersection = Sets.intersection(optifineEntries, mcEntries);
			} else {
				intersection = Sets.intersection(mcEntries, optifineEntries);
			}
		}

		try (FileSystemDelegate mcFS = StitchUtil.getJarFileSystem(minecraft, false);
				FileSystemDelegate ofFS = StitchUtil.getJarFileSystem(optifine, false);
				FileSystemDelegate outputFS = StitchUtil.getJarFileSystem(to, true)) {
			for (String entry : mcEntries) {
				copy(mcFS.get(), outputFS.get(), entry);
			}

			for (String entry : optifineEntries) {
				copy(ofFS.get(), outputFS.get(), entry);
			}

			for (String entry : intersection) {
				if (entry.endsWith(".class")) {
					Path pathRawIn = mcFS.get().getPath(entry);
					Path pathPatchedIn = ofFS.get().getPath(entry);

					Path pathOut = outputFS.get().getPath(entry);
			        if (pathOut.getParent() != null) {
			            Files.createDirectories(pathOut.getParent());
			        }

			        byte[] data = ClassReconstructor.reconstruct(Files.readAllBytes(pathRawIn), Files.readAllBytes(pathPatchedIn));

			        //BasicFileAttributes touchTime = Files.readAttributes(pathIn, BasicFileAttributes.class);
			        Files.write(pathOut, data, StandardOpenOption.CREATE_NEW);
			        //Files.getFileAttributeView(pathIn, BasicFileAttributeView.class).setTimes(touchTime.lastModifiedTime(), touchTime.lastAccessTime(), touchTime.creationTime());
				} else if (entry.startsWith("META-INF/")) {
					copy(mcFS.get(), outputFS.get(), entry);
				} else {
					copy(ofFS.get(), outputFS.get(), entry);
				}
			}
		}
	}

	private static void copy(FileSystem fsIn, FileSystem fsOut, String entry) throws IOException {
		Path pathIn = fsIn.getPath(entry);

		Path pathOut = fsOut.getPath(entry);
        if (pathOut.getParent() != null) {
            Files.createDirectories(pathOut.getParent());
        }

        BasicFileAttributes touchTime = Files.readAttributes(pathIn, BasicFileAttributes.class);
        Files.copy(pathIn, pathOut);
        Files.getFileAttributeView(pathIn, BasicFileAttributeView.class).setTimes(touchTime.lastModifiedTime(), touchTime.lastAccessTime(), touchTime.creationTime());
	}

	public static void applyBonusMappings(File to) throws IOException {
		List<FieldEntry> extra = new ArrayList<>();

		try (InputStream in = new FileInputStream(to)) {
			for (FieldEntry field : MappingsProvider.readTinyMappings(in, false).getFieldEntries()) {
				String interName = field.get("intermediary").getName();

				//Option#CLOUDS
				if ("field_1937".equals(interName)) {
					extra.add(namespace -> {
						EntryTriple real = field.get(namespace);
						return new EntryTriple(real.getOwner(), "official".equals(namespace) ? "CLOUDS" : "CLOUDS_OF", real.getDesc());
					});
				}

				//WorldRenderer#renderDistance
				if ("field_4062".equals(interName)) {
					extra.add(namespace -> {
						EntryTriple real = field.get(namespace);
						return new EntryTriple(real.getOwner(), "official".equals(namespace) ? "renderDistance" : "renderDistance_OF", real.getDesc());
					});
				}

				if (interName.endsWith("_OF")) return; //Already applied the bonus mappings to this file
			}
		}

		try (BufferedWriter writer = new BufferedWriter(new FileWriter(to, true))) {
			for (FieldEntry field : extra) {
				EntryTriple obf = field.get("official");
				writer.write(String.format("FIELD\t%s\t%s\t%s\t%s\t%s\n", obf.getOwner(), obf.getDesc(), obf.getName(), field.get("named").getName(), field.get("intermediary").getName()));
			}
		}
	}
}