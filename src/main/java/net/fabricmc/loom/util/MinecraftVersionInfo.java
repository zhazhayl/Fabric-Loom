/*
 * Copyright 2019, 2021 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.util;

import java.net.URL;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.common.collect.ObjectArrays;
import com.google.gson.annotations.SerializedName;

import net.fabricmc.loom.util.MinecraftVersionInfo.Library.Rule.Action;

public class MinecraftVersionInfo {
	public static class Library {
		public static class Rule {
			public enum Action {
				@SerializedName("allow")
				ALLOW,
				@SerializedName("disallow")
				DISALLOW;
			}

			public static class OS {
				public OperatingSystem name;
				public String version;
				public String arch;

				public boolean doesMatch(OperatingSystem os) {
					if (name != null && name != os) {
						return false;
					}

					if (version != null && !Pattern.matches(version, System.getProperty("os.version"))) {
						return false;
					}

					if (arch != null && !Pattern.matches(arch, System.getProperty("os.arch"))) {
						return false;
					}

					return true;
				}
			}

			public Action action = Action.ALLOW;
			public OS os;

			public boolean doesRuleApply(OperatingSystem with) {
				return os == null || os.doesMatch(with);
			}
		}

		public static class Extraction {
			public List<String> exclude = Collections.emptyList();

			public boolean isSpecial() {
				return !exclude.isEmpty();
			}
		}

		public static class Downloads {
			public Download artifact;
			public Map<String, Download> classifiers = Collections.emptyMap();

			public boolean isSpecial() {
				return !classifiers.isEmpty();
			}

			public Download getDownload(String classifier) {
				return classifier == null ? artifact : classifiers.get(classifier);
			}
		}

		public String name;
		public Rule[] rules;
		public Map<OperatingSystem, String> natives;
		public Extraction extract;
		public Downloads downloads;

		public boolean shouldUse() {
			return shouldUse(OperatingSystem.ACTIVE);
		}

		public boolean shouldUse(OperatingSystem os) {
			if (rules == null || rules.length == 0) return true;

			for (int i = rules.length - 1; i >= 0; i--) {
				if (rules[i].doesRuleApply(os)) {
					return rules[i].action == Action.ALLOW;
				}
			}

			return false;
		}

		public String getArtifactName() {
			return getArtifactName(OperatingSystem.ACTIVE);
		}

		public String getArtifactName(OperatingSystem os) {
			if (!isNative()) return name;

			if (os == null || !hasNativeFor(os)) {
				throw new IllegalArgumentException(name + " has no native for " + os);
			}

			return name + ':' + natives.get(os);
		}

		public boolean isNative() {
			return natives != null;
		}

		public boolean hasNativeFor(OperatingSystem os) {
			return isNative() && natives.containsKey(os);
		}

		public Download getDownload() {
			return downloads.getDownload(null);
		}

		public Download getDownload(OperatingSystem os) {
			if (os == null || !hasNativeFor(os) || !downloads.isSpecial()) {
				throw new IllegalArgumentException(name + " has no native for " + os);
			}

			return downloads.getDownload(natives.get(os));
		}
	}

	public static class Download {
		public URL url;
		public URL[] altUrls = new URL[0];
		@SerializedName("sha1")
		public String hash;
		public int size;

		public URL[] getURLs() {
			return ObjectArrays.concat(url, altUrls);
		}
	}

	public static class AssetIndex {
		public String id;
		public URL url;
		@SerializedName("sha1")
		public String hash;

		public String getFabricId(String version) {
			return id.equals(version) ? version : version + '-' + id;
		}
	}

	public String id;
	public Date releaseTime;
	public List<Library> libraries;
	public Map<String, Download> downloads;
	public AssetIndex assetIndex;
}
