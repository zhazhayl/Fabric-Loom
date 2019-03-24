package net.fabricmc.loom.task;

import org.gradle.api.tasks.bundling.Jar;

import groovy.lang.Closure;
import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.util.AccessTransformerHelper;
import net.fabricmc.loom.util.ModRemapper;

public class RemappingJar extends Jar {
	public RemappingJar() {
		setGroup("fabric");

		configure(new Closure<RemapJar>(this, this) {
			private static final long serialVersionUID = -5294178679681444341L;

			@Override
			public RemapJar call() {
				AccessTransformerHelper.copyInAT(getProject().getExtensions().getByType(LoomGradleExtension.class), RemappingJar.this);
				return null;
			}
		});
		doLast(task -> {
			ModRemapper.remap(task, getArchivePath());
		});
	}
}