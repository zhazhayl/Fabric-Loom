/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.tasks.TaskDependency;

import net.fabricmc.loom.dependencies.ComputedDependency;
import net.fabricmc.loom.providers.mappings.IMappingAcceptor;
import net.fabricmc.loom.providers.mappings.TinyReader;
import net.fabricmc.loom.providers.mappings.TinyWriter;
import net.fabricmc.loom.util.DownloadUtil;
import net.fabricmc.mappings.EntryTriple;

public class YarnGithubResolver {
	private static final String DEFAULT_REPO = "FabricMC/yarn";
	private static final String DOWNLOAD_URL = "https://api.github.com/repos/%s/zipball/%s";
	private static final Action<DownloadSpec> NOOP = spec -> {
	};
	final Function<Path, FileCollection> fileFactory;
	private final Path globalCache, projectCache;
	final Logger logger;

	static void createDirectory(Path path) {
		try {
			if (Files.notExists(path)) Files.createDirectories(path);
			assert Files.exists(path) && Files.isDirectory(path);
		} catch (IOException e) {
			throw new UncheckedIOException("Unable to create directory", e);
		}
	}

	public YarnGithubResolver(Project project) {
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		globalCache = extension.getUserCache().toPath();
		createDirectory(globalCache);
		projectCache = extension.getRootProjectPersistentCache().toPath();
		createDirectory(projectCache);

		logger = project.getLogger();
		fileFactory = project::files;
	}

	public static class DownloadSpec {
		final String originalName;
		private String group, name, version, reason;
		boolean forceFresh;

		protected DownloadSpec(String name) {
			int group = name.indexOf('/');
			this.group = name.substring(0, group++);
			this.name = name.substring(group, name.indexOf('/', group));

			originalName = name;
		}

		public String getGroup() {
			return group;
		}

		public void setGroup(String group) {
			this.group = group;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getVersion() {
			//Might be able to infer the default relative to the original commit, might also be more effort than it's worth
			return version == null ? "1.0" : version;
		}

		public void setVersion(String version) {
			this.version = version;
		}

		public String getReason() {
			return reason;
		}

		public void because(String reason) {
			this.reason = reason;
		}

		public void setForceFresh(boolean force) {
			forceFresh = force;
		}
	}

	public Dependency yarnBranch(String branch) {
		return yarnBranch(branch, NOOP);
	}

	public Dependency yarnBranch(String branch, Action<DownloadSpec> action) {
		return fromBranch(DEFAULT_REPO, branch, action);
	}

	public Dependency fromBranch(String repo, String branch) {
		return fromBranch(repo, branch, NOOP);
	}

	public Dependency fromBranch(String repo, String branch, Action<DownloadSpec> action) {
		DownloadSpec spec = new DownloadSpec(repo + '/' + branch);
		action.execute(spec);
		return createFrom(spec, String.format(DOWNLOAD_URL, repo, branch));
	}

	public Dependency yarnCommit(String commit) {
		return yarnCommit(commit, NOOP);
	}

	public Dependency yarnCommit(String commit, Action<DownloadSpec> action) {
		return fromCommit(DEFAULT_REPO, commit, action);
	}

	public Dependency fromCommit(String repo, String commit) {
		return fromCommit(repo, commit, NOOP);
	}

	public Dependency fromCommit(String repo, String commit, Action<DownloadSpec> action) {
		DownloadSpec spec = new DownloadSpec(repo + '/' + commit);
		action.execute(spec);
		return createFrom(spec, String.format(DOWNLOAD_URL, repo, commit));
	}

	public class GithubDependency extends ComputedDependency {
		protected final DownloadSpec spec;
		protected final String origin;
		protected final Path destination;

		public GithubDependency(DownloadSpec spec, String origin, Path destination) {
			super(spec.getGroup(), spec.getName(), spec.getVersion());
			this.spec = spec;
			this.origin = origin;
			this.destination = destination;
			because(spec.getReason());
		}

		@Override
		public boolean contentEquals(Dependency dependency) {
			if (dependency == this) return true;
			if (!(dependency instanceof GithubDependency)) return false;
			return Objects.equals(((GithubDependency) dependency).origin, origin);
		}

		@Override
		public Dependency copy() {
			return new GithubDependency(spec, origin, destination);
		}

		@Override
		public TaskDependency getBuildDependencies() {
			logger.info("Computing Github dependency's task dependency for " + spec.originalName + " from " + origin + " to " + destination);
			return super.getBuildDependencies();
		}

		@Override
		public FileCollection getFiles() {
			logger.info("Detecting Github dependency's file(s) for " + spec.originalName + " from " + origin + " to " + destination);
			return super.getFiles();
		}

		@Override
		protected FileCollection makeFiles() {
			return fileFactory.apply(destination);
		}

		@Override
		public Set<File> resolve() {
			logger.info("Resolving Github dependency for " + spec.originalName + " from " + origin + " to " + destination);
			if (Files.notExists(destination.getParent())) throw new IllegalStateException("Dependency on " + origin + " lacks a destination");

			try {
				DownloadUtil.downloadIfChanged(new URL(origin), destination.toFile(), logger, true);
				return Collections.singleton(destination.toFile());
			} catch (MalformedURLException e) {
				throw new IllegalArgumentException("Invalid origin URL: " + origin, e);
			} catch (IOException e) {
				throw new RuntimeException("Unable to download " + spec.originalName + " from " + origin, e);
			}
		}
	}

	private Dependency createFrom(DownloadSpec spec, String origin) {
		Path destination = globalCache.resolve("yarn-resolutions").resolve(spec.originalName + ".zip");
		createDirectory(destination.getParent());

		if (spec.forceFresh) {
			try {
				Files.deleteIfExists(destination);
			} catch (IOException e) {
				throw new UncheckedIOException("Unable to delete " + spec.originalName + " at " + destination, e);
			}
		}

		logger.debug("Creating new Github dependency for " + spec.originalName + " from " + origin + " to " + destination);
		return new GithubDependency(spec, origin, destination);
	}

	public Dependency extraMappings(Action<ExtraMappings> action) {
		ExtraMappings mappings = new ExtraMappings(projectCache.resolve("extra-mappings"), fileFactory);
		action.execute(mappings);
		return mappings;
	}

	public static class ExtraMappings extends ComputedDependency {
		private static class NotaGoto extends RuntimeException {
			private static final long serialVersionUID = -1268411969286315592L;
			static final NotaGoto INSTANCE = new NotaGoto();

			private NotaGoto() {//Nothing to do with exception based flow control
				setStackTrace(new StackTraceElement[0]);
			}

			@Override
			public synchronized Throwable fillInStackTrace() {
				setStackTrace(new StackTraceElement[0]);
				return this;
			}
		}
		private static volatile int instance = 1;
		private final Path cache;
		private final Function<Path, FileCollection> fileFactory;
		private String from = "intermediary", to = "named";
		private Map<String, String> classes = new HashMap<>();
		private Map<EntryTriple, String> methods = new HashMap<>();
		private Map<EntryTriple, String> fields = new HashMap<>();

		public ExtraMappings(Path cache, Function<Path, FileCollection> fileFactory) {
			this("instance:" + instance, cache, fileFactory);
		}

		private ExtraMappings(String instance, Path cache, Function<Path, FileCollection> fileFactory) {
			super("net.fabricmc.synthetic.extramappings", instance, "1");

			this.cache = cache;
			this.fileFactory = fileFactory;
		}

		public void setFrom(String from) {
			this.from = from;
		}

		public String getFrom() {
			return from;
		}

		public void setTo(String to) {
			this.to = to;
		}

		public String getTo() {
			return to;
		}

		public void setClass(String from, String to) {
			classes.put(from, to);
		}

		public Map<String, String> getClasses() {
			return classes;
		}

		public void setClasses(Map<String, String> mappings) {
			if (mappings == null) throw new NullPointerException("Null class mappings");
			classes.clear();
			classes.putAll(mappings);
		}

		public void setMethod(EntryTriple from, String to) {
			methods.put(from, to);
		}

		public Map<EntryTriple, String> getMethods() {
			return methods;
		}

		public void setMethods(Map<EntryTriple, String> mappings) {
			if (mappings == null) throw new NullPointerException("Null method mappings");
			methods.clear();
			methods.putAll(mappings);
		}

		public void setField(EntryTriple from, String to) {
			fields.put(from, to);
		}

		public Map<EntryTriple, String> getFields() {
			return fields;
		}

		public void setFields(Map<EntryTriple, String> mappings) {
			if (mappings == null) throw new NullPointerException("Null field mappings");
			fields.clear();
			fields.putAll(mappings);
		}

		@Override
		public Set<File> resolve() {
			Path output = cache.resolve("mappings_" + getName().substring(9) + ".gz");

			out: if (Files.exists(output)) {
				try {
					TinyReader.readTiny(output, from, to, new IMappingAcceptor() {
						private final Map<String, String> classes = new HashMap<>(ExtraMappings.this.classes);
						private final Map<EntryTriple, String> methods = new HashMap<>(ExtraMappings.this.methods);
						private final Map<EntryTriple, String> fields = new HashMap<>(ExtraMappings.this.fields);

						@Override
						public void acceptClass(String srcName, String dstName) {
							if (!classes.remove(srcName, dstName)) {
								throw NotaGoto.INSTANCE;
							}
						}

						@Override
						public void acceptMethod(String srcClsName, String srcName, String srcDesc, String dstClsName, String dstName, String dstDesc) {
							if (!methods.remove(new EntryTriple(srcClsName, srcName, srcDesc), dstName)) {
								throw NotaGoto.INSTANCE;
							}
						}

						@Override
						public void acceptMethodArg(String srcClsName, String srcMethodName, String srcMethodDesc, int lvIndex, String dstArgName) {
						}

						@Override
						public void acceptField(String srcClsName, String srcName, String srcDesc, String dstClsName, String dstName, String dstDesc) {
							if (!fields.remove(new EntryTriple(srcClsName, srcName, srcDesc), dstName)) {
								throw NotaGoto.INSTANCE;
							}
						}
					});
				} catch (IOException e) {
					throw new RuntimeException("Error reading extra mappings from " + output, e);
				} catch (NotaGoto e) {
					break out;
				}

				return Collections.singleton(output.toFile());
			}

			createDirectory(output.getParent());
			try (TinyWriter writer = new TinyWriter(output, true, "intermediary", "named")) {
				for (Entry<String, String> entry : classes.entrySet()) {
					writer.acceptClass(entry.getKey(), entry.getValue());
				}

				for (Entry<EntryTriple, String> entry : methods.entrySet()) {
					EntryTriple method = entry.getKey();
					writer.acceptMethod(method.getOwner(), method.getDesc(), method.getName(), entry.getValue());
				}

				for (Entry<EntryTriple, String> entry : fields.entrySet()) {
					EntryTriple field = entry.getKey();
					writer.acceptField(field.getOwner(), field.getDesc(), field.getName(), entry.getValue());
				}

				return Collections.singleton(output.toFile());
			} catch (IOException e) {
				throw new RuntimeException("Error writing extra mappings to " + output, e);
			}
		}

		@Override
		protected FileCollection makeFiles() {
			return fileFactory.apply(null);
		}

		@Override
		public boolean contentEquals(Dependency dependency) {
			if (dependency == this) return true;
			if (!(dependency instanceof ExtraMappings)) return false;
			return Objects.equals(dependency.getName(), getName());
		}

		@Override
		public Dependency copy() {
			return new ExtraMappings(getName(), cache, fileFactory);
		}
	}
}