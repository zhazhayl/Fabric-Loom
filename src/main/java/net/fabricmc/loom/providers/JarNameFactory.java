/*
 * Copyright 2020 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers;

/** We're one strategy away from being proper enterprise Java code */
public enum JarNameFactory {
	CLIENT {
		@Override
		public String getDependencyName(JarNamingStrategy strategy) {
			return strategy.getVersion().concat("-client");
		}
	},
	CLIENT_INTERMEDIARY {
		@Override
		public String getDependencyName(JarNamingStrategy strategy) {
			return strategy.getVersion().concat("-client-intermediary");
		}
	},
	SERVER {
		@Override
		public String getDependencyName(JarNamingStrategy strategy) {
			return strategy.getVersion().concat("-server");
		}
	},
	SERVER_INTERMEDIARY {
		@Override
		public String getDependencyName(JarNamingStrategy strategy) {
			return strategy.getVersion().concat("-server-intermediary");
		}
	},
	MERGED {
		@Override
		public String getDependencyName(JarNamingStrategy strategy) {
			return strategy.getVersion().concat("-merged");
		}
	},
	MERGED_INTERMEDIARY {
		@Override
		public String getDependencyName(JarNamingStrategy strategy) {
			return strategy.getVersion() + "-intermediary" + strategy.getExtra(); //FIXME: Attach the mapping name to the end?
		}
	},
	NAMED {
		@Override
		public String getDependencyName(JarNamingStrategy strategy) {
			return strategy.getVersion() + "-mapped" + strategy.getExtra() + '-' + strategy.getMappings();
		}
	};

	public abstract String getDependencyName(JarNamingStrategy strategy);

	public String getJarName(JarNamingStrategy strategy) {
		return "minecraft-" + getDependencyName(strategy) + ".jar";
	}
}