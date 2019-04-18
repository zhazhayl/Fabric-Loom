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
package net.fabricmc.loom.task;

import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.bundling.Jar;

import groovy.lang.Closure;
import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.util.AccessTransformerHelper;
import net.fabricmc.loom.util.ModRemapper;

public class RemappingJar extends Jar {
	@Input
	public boolean includeAT = true;

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
			ModRemapper.remap(task, getArchivePath(), !includeAT);
		});
	}
}