package net.fabricmc.loom.util.progress;

import org.gradle.api.logging.Logger;

class ProgressLoggerShim extends ProgressLogger {
	private String description = "";

	ProgressLoggerShim(Logger logger) {
		super(logger);
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public ProgressLogger setDescription(String description) {
		this.description = description;
		return this;
	}

	@Override
	public ProgressLogger start(String description, String shortDescription) {
		this.description = description;
		return this;
	}

	@Override
	public void started() {
	}

	@Override
	public void started(String status) {
		logger.info("{}-{}", description, status);
	}

	@Override
	public void progress(String status) {
		logger.info("{}-{}", description, status);
	}

	@Override
	public void completed() {
	}

	@Override
	public void completed(String status, boolean failed) {
		logger.info("{}-{}", description, status);
	}

	@Override
	public ProgressLogger newChild(Class<?> category) {
		return new ProgressLoggerShim(logger).setDescription(category.getName());
	}
}