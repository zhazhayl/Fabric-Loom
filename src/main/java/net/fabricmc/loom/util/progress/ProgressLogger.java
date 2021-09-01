/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
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

package net.fabricmc.loom.util.progress;

import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.logging.Logger;
import org.gradle.internal.logging.progress.ProgressLoggerFactory;
import org.gradle.internal.service.ServiceRegistry;

/**
 * Wrapper to ProgressLogger internal API.
 */
public abstract class ProgressLogger {
	protected final Logger logger;

	ProgressLogger(Logger logger) {
		this.logger = logger;
	}

	/**
	 * Get a Progress logger from the Gradle internal API.
	 *
	 * @param project The project
	 * @param category The logger category
	 * @return In any case a progress logger
	 */
	public static ProgressLogger getProgressFactory(Project project, String category) {
		try {
			ServiceRegistry registry = ((ProjectInternal) project).getServices();
			ProgressLoggerFactory factory = registry.get(ProgressLoggerFactory.class);
			return new ProgressLoggerImpl(project.getLogger(), factory.newOperation(category), factory);
		} catch (OutOfMemoryError e) {
			throw e;
		} catch (Throwable t) {
			project.getLogger().error("Unable to get progress logger. Task progress will not be displayed.", t);
			return new ProgressLoggerShim(project.getLogger()).setDescription(category);
		}
	}

	/**
	 * Returns the description of the operation.
	 *
	 * @return the description, must not be empty.
	 */
	public abstract String getDescription();

	/**
	 * Sets the description of the operation. This should be a full, stand-alone description of the operation.
	 *
	 * <p>This must be called before {@link #started()}
	 *
	 * @param description The description.
	 */
	public abstract ProgressLogger setDescription(String description);

	/**
	 * Convenience method that sets descriptions and logs started() event.
	 *
	 * @return this logger instance
	 */
	public abstract ProgressLogger start(String description, String shortDescription);

	/**
	 * Logs the start of the operation, with no initial status.
	 */
	public abstract void started();

	/**
	 * Logs the start of the operation, with the given status.
	 *
	 * @param status The initial status message. Can be null or empty.
	 */
	public abstract void started(String status);

	/**
	 * Logs some progress, indicated by a new status.
	 *
	 * @param status The new status message. Can be null or empty.
	 */
	public abstract void progress(String status);

	/**
	 * Logs the completion of the operation, with no final status.
	 */
	public abstract void completed();

	/**
	 * Logs the completion of the operation, with a final status. This is generally logged along with the description.
	 *
	 * @param status The final status message. Can be null or empty.
	 * @param failed Whether the operation failed.
	 */
	public abstract void completed(String status, boolean failed);

	public abstract ProgressLogger newChild(Class<?> category);
}
