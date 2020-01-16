package net.fabricmc.loom.dependencies;

import java.io.File;
import java.util.Collections;
import java.util.Set;

import org.gradle.api.artifacts.FileCollectionDependency;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.artifacts.dependencies.SelfResolvingDependencyInternal;
import org.gradle.api.tasks.TaskDependency;

//Gradle needs the internal interface to avoid class cast exceptions
public abstract class ComputedDependency implements SelfResolvingDependencyInternal, FileCollectionDependency {
	private final String group, name, version;
	private String reason;

	public ComputedDependency(String group, String name, String version) {
		this.group = group;
		this.name = name;
		this.version = version;
	}

	@Override
	public String getGroup() {
		return group;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String getVersion() {
		return version;
	}

	@Override
	public ComponentIdentifier getTargetComponentId() {
		return null;
	}

	@Override
	public void because(String reason) {
		this.reason = reason;
	}

	@Override
	public String getReason() {
		return reason;
	}

	@Override
	public TaskDependency getBuildDependencies() {
		return task -> Collections.emptySet();
	}

	@Override
	public FileCollection getFiles() {
		resolve(); //Ensure the destination actually exists, as Gradle doesn't really care either way
		return makeFiles();
	}

	protected abstract FileCollection makeFiles();

	@Override
	public Set<File> resolve(boolean transitive) {
		return resolve();
	}
}