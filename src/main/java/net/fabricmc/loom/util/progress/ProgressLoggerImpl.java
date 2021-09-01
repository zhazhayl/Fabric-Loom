package net.fabricmc.loom.util.progress;

import org.gradle.api.logging.Logger;
import org.gradle.internal.logging.progress.ProgressLogger;
import org.gradle.internal.logging.progress.ProgressLoggerFactory;

class ProgressLoggerImpl extends net.fabricmc.loom.util.progress.ProgressLogger {
	private final ProgressLogger progress;
	private final ProgressLoggerFactory logFactory;

	ProgressLoggerImpl(Logger logger, ProgressLogger progress, ProgressLoggerFactory logFactory) {
		super(logger);

		this.progress = progress;
		this.logFactory = logFactory;
	}

	@Override
	public String getDescription() {
		try {
			return progress.getDescription();
		} catch (IncompatibleClassChangeError e) {
			logger.warn("Error calling ProgressLogger#getDescription", e);
			return ""; //Got to return something
		}
	}

	@Override
	public net.fabricmc.loom.util.progress.ProgressLogger setDescription(String description) {
		try {
			progress.setDescription(description);
		} catch (IncompatibleClassChangeError e) {
			logger.warn("Error calling ProgressLogger#setDescription", e);
		}

		return this;
	}

	@Override
	public net.fabricmc.loom.util.progress.ProgressLogger start(String description, String shortDescription) {
		try {
			progress.start(description, shortDescription);
		} catch (IncompatibleClassChangeError e) {
			logger.warn("Error calling ProgressLogger#start", e);
		}

		return this;
	}

	@Override
	public void started() {
		try {
			progress.started();
		} catch (IncompatibleClassChangeError e) {
			logger.warn("Error calling ProgressLogger#started", e);
		}
	}

	@Override
	public void started(String status) {
		try {
			progress.started(status);
		} catch (IncompatibleClassChangeError e) {
			logger.warn("Error calling ProgressLogger#started", e);
		}
	}

	@Override
	public void progress(String status) {
		try {
			progress.progress(status);
		} catch (IncompatibleClassChangeError e) {
			logger.warn("Error calling ProgressLogger#progress", e);
		}
	}

	@Override
	public void completed() {
		try {
			progress.completed();
		} catch (IncompatibleClassChangeError e) {
			logger.warn("Error calling ProgressLogger#completed", e);
		}
	}

	@Override
	public void completed(String status, boolean failed) {
		try {
			progress.completed(status, failed);
		} catch (IncompatibleClassChangeError e) {
			logger.warn("Error calling ProgressLogger#completed", e);
		}
	}

	@Override
	public net.fabricmc.loom.util.progress.ProgressLogger newChild(Class<?> category) {
		try {
			return new ProgressLoggerImpl(logger, logFactory.newOperation(category, progress), logFactory);
		} catch (IncompatibleClassChangeError e) {
			logger.warn("Error calling ProgressLoggerFactory#newOperation", e);
			return new ProgressLoggerShim(logger);
		}
	}
}