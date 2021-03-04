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

package net.fabricmc.loom.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.transform.StringZipEntryTransformer;
import org.zeroturnaround.zip.transform.ZipEntryTransformerEntry;

public final class MixinRefmapHelper {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private MixinRefmapHelper() { }

	public static boolean addRefmapName(String filename, String mixinVersion, File output) {
		Set<String> mixinFilenames = findMixins(output);

		if (mixinFilenames.size() > 0) {
			return ZipUtil.transformEntries(output, mixinFilenames.stream().map((f) -> new ZipEntryTransformerEntry(f, new StringZipEntryTransformer("UTF-8") {
				@Override
				protected String transform(ZipEntry zipEntry, String input) throws IOException {
					try {
						JsonObject json = GSON.fromJson(input, JsonObject.class);

						if (!json.has("refmap")) {
							json.addProperty("refmap", filename);
						}

						if (!json.has("minVersion") && mixinVersion != null) {
							json.addProperty("minVersion", mixinVersion);
						}

						return GSON.toJson(json);
					} catch (JsonSyntaxException e) {
						System.err.println("Suspected Mixin config " + zipEntry + " is not a JSON object");
						e.printStackTrace();
						return input;
					}
				}
			})).toArray(ZipEntryTransformerEntry[]::new));
		} else {
			return false;
		}
	}

	private static Set<String> findMixins(File output) {
		// first, identify all of the mixin files
		Set<String> mixinFilename = new HashSet<>();
		// TODO: this is a lovely hack
		ZipUtil.iterate(output, (stream, entry) -> {
			if (!entry.isDirectory() && entry.getName().endsWith(".json") && !entry.getName().contains("/") && !entry.getName().contains("\\")) {
				// JSON file in root directory
				try (JsonReader in = new JsonReader(new InputStreamReader(stream))) {
					out: if (in.peek() == JsonToken.BEGIN_OBJECT) {
						boolean hasPackage = false;
						boolean probablyMixin = false;
						boolean hasRefmap = false;
						boolean hasMinVersion = false;

						in.beginObject();
						while (in.hasNext()) {
							switch (in.nextName()) {
							case "package":
								if (in.peek() == JsonToken.STRING) {
									hasPackage = true;
								} else {
									break out; //Not Mixin
								}
								break;

							case "mixins":
							case "client":
							case "server":
								if (in.peek() == JsonToken.BEGIN_ARRAY) {
									probablyMixin = true;
								} else {
									break out; //Not Mixin
								}
								break;

							case "refmap":
								if (in.peek() == JsonToken.STRING) {
									hasRefmap = true;
								} else {
									break out; //Not Mixin
								}
								break;

							case "minVersion":
								if (in.peek() == JsonToken.STRING || in.peek() == JsonToken.NUMBER) {
									hasMinVersion = true;
								} else {
									break out; //Not Mixin
								}
								break;
							}

							in.skipValue(); //Don't actually mind what the value is
						}

						if (hasPackage && probablyMixin && (!hasRefmap || !hasMinVersion)) {
							mixinFilename.add(entry.getName());
						}
					}
                } catch (IOException | IllegalStateException e) {
                	System.err.println("Error reading " + entry);
                    e.printStackTrace();
                }
            }
        });
        return mixinFilename;
    }
}
