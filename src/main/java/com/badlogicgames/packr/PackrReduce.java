/*******************************************************************************
 * Copyright 2014 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogicgames.packr;

import com.eclipsesource.json.*;
import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.commons.FileUtils;

import java.io.*;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Functions to reduce package size for both classpath JARs, and the bundled JRE.
 */
class PackrReduce {

	static void minimizeJre(File output, PackrConfig config) throws IOException {
		if (config.minimizeJre == null) {
			return;
		}

		System.out.println("Minimizing JRE ...");

		JsonObject minimizeJson = readMinimizeProfile(config);
		if (minimizeJson != null) {
			if (config.verbose) {
				System.out.println("  # Removing files and directories in profile '" + config.minimizeJre + "' ...");
			}

			JsonArray reduceArray = minimizeJson.get("reduce").asArray();
			for (JsonValue reduce : reduceArray) {
				String path = reduce.asObject().get("archive").asString();
				File file = new File(output, path);

				if (!file.exists()) {
					if (config.verbose) {
						System.out.println("  # No file or directory '" + file.getPath() + "' found, skipping");
					}
					continue;
				}

				boolean needsUnpack = !file.isDirectory();

				File fileNoExt = needsUnpack
						? new File(output, path.contains(".") ? path.substring(0, path.lastIndexOf('.')) : path)
						: file;

				if (needsUnpack) {
					if (config.verbose) {
						System.out.println("  # Unpacking '" + file.getPath() + "' ...");
					}
					ZipUtil.unpack(file, fileNoExt);
				}

				JsonArray removeArray = reduce.asObject().get("paths").asArray();
				for (JsonValue remove : removeArray) {
					File removeFile = new File(fileNoExt, remove.asString());
					if (removeFile.exists()) {
						if (removeFile.isDirectory()) {
							FileUtils.deleteDirectory(removeFile);
						} else {
							PackrFileUtils.delete(removeFile);
						}
					} else {
						if (config.verbose) {
							System.out.println("  # No file or directory '" + removeFile.getPath() + "' found");
						}
					}
				}

				if (needsUnpack) {
					if (config.verbose) {
						System.out.println("  # Repacking '" + file.getPath() + "' ...");
					}

					long beforeLen = file.length();
					PackrFileUtils.delete(file);

					ZipUtil.pack(fileNoExt, file);
					FileUtils.deleteDirectory(fileNoExt);

					long afterLen = file.length();

					if (config.verbose) {
						System.out.println("  # " + beforeLen / 1024 + " kb -> " + afterLen / 1024 + " kb");
					}
				}
			}

			JsonArray removeArray = minimizeJson.get("remove").asArray();
			for (JsonValue remove : removeArray) {
				String platform = remove.asObject().get("platform").asString();

				if (!matchPlatformString(platform, config)) {
					continue;
				}

				JsonArray removeFilesArray = remove.asObject().get("paths").asArray();
				for (JsonValue removeFile : removeFilesArray) {
					removeFileWildcard(output, removeFile.asString(), config);
				}
			}
		}
	}

	private static boolean matchPlatformString(String platform, PackrConfig config) {
		return "*".equals(platform) || config.platform.desc.contains(platform);
	}

	private static void removeFileWildcard(File output, String removeFileWildcard, PackrConfig config) throws IOException {
		if (removeFileWildcard.contains("*")) {
			String removePath = removeFileWildcard.substring(0, removeFileWildcard.indexOf('*') - 1);
			String removeSuffix = removeFileWildcard.substring(removeFileWildcard.indexOf('*') + 1);

			File[] files = new File(output, removePath).listFiles();
			if (files != null) {
				for (File file : files) {
					if (removeSuffix.isEmpty() || file.getName().endsWith(removeSuffix)) {
						removeFile(file, config);
					}
				}
			} else {
				if (config.verbose) {
					System.out.println("  # No matching files found in '" + removeFileWildcard + "'");
				}
			}
		} else {
			removeFile(new File(output, removeFileWildcard), config);
		}
	}

	private static void removeFile(File file, PackrConfig config) throws IOException {
		if (!file.exists()) {
			if (config.verbose) {
				System.out.println("  # No file or directory '" + file.getPath() + "' found");
			}
			return;
		}

		if (config.verbose) {
			System.out.println("  # Removing '" + file.getPath() + "'");
		}

		if (file.isDirectory()) {
			FileUtils.deleteDirectory(file);
		} else {
			PackrFileUtils.delete(file);
		}
	}

	private static JsonObject readMinimizeProfile(PackrConfig config) throws IOException {

		JsonObject json = null;

		if (new File(config.minimizeJre).exists()) {
			json = JsonObject.readFrom(FileUtils.readFileToString(new File(config.minimizeJre)));
		} else {
			InputStream in = Packr.class.getResourceAsStream("/minimize/" + config.minimizeJre);
			if (in != null) {
				json = JsonObject.readFrom(new InputStreamReader(in));
			}
		}

		if (json == null && config.verbose) {
			System.out.println("  # No minimize profile '" + config.minimizeJre + "' found");
		}

		return json;
	}

	static void removePlatformLibs(PackrOutput output,
								   PackrConfig config,
								   Predicate<File> removePlatformLibsFileFilter) throws IOException {
		if (config.removePlatformLibs == null || config.removePlatformLibs.isEmpty()) {
			return;
		}

		boolean extractLibs = config.platformLibsOutDir != null;
		File libsOutputDir = null;
		if (extractLibs) {
			libsOutputDir = new File(output.executableFolder, config.platformLibsOutDir.getPath());
			PackrFileUtils.mkdirs(libsOutputDir);
		}

		System.out.println("Removing foreign platform libs ...");

		Set<String> extensions = new HashSet<>();
		String libExtension;

		switch (config.platform) {
			case Windows32:
			case Windows64:
				extensions.add(".dylib");
				extensions.add(".dylib.git");
				extensions.add(".dylib.sha1");
				extensions.add(".so");
				extensions.add(".so.git");
				extensions.add(".so.sha1");
				libExtension = ".dll";
				break;
			case Linux64:
			case LinuxAarch64:
				extensions.add(".dll");
				extensions.add(".dll.git");
				extensions.add(".dll.sha1");
				extensions.add(".dylib");
				extensions.add(".dylib.git");
				extensions.add(".dylib.sha1");
				libExtension = ".so";
				break;
			case MacOS64:
			case MacOSAarch64:
				extensions.add(".dll");
				extensions.add(".dll.git");
				extensions.add(".dll.sha1");
				extensions.add(".so");
				extensions.add(".so.git");
				extensions.add(".so.sha1");
				libExtension = ".dylib";
				break;
			default:
				throw new IllegalStateException();
		}

		// let's remove any shared libs not used on the platform, e.g. libGDX/LWJGL natives
		for (String classpath : config.removePlatformLibs) {
			File jar = new File(output.resourcesFolder, new File(classpath).getName());
			File jarDir = new File(output.resourcesFolder, jar.getName() + ".tmp");

			if (config.verbose) {
				if (jar.isDirectory()) {
					System.out.println("  # JAR '" + jar.getName() + "' is a directory");
				} else {
					System.out.println("  # Unpacking '" + jar.getName() + "' ...");
				}
			}

			if (!jar.isDirectory()) {
				ZipUtil.unpack(jar, jarDir);
			} else {
				jarDir = jar; // run in-place for directories
			}

			File[] files = jarDir.listFiles();
			if (files != null) {
				for (File file : files) {
					boolean removed = false;
					if (removePlatformLibsFileFilter.test(file)) {
						if (config.verbose) {
							System.out.println("  # Removing '" + file.getPath() + "' (filtered)");
						}
						PackrFileUtils.delete(file);
						removed = true;
					}
					if (!removed) {
						for (String extension : extensions) {
							if (file.getName().endsWith(extension)) {
								if (config.verbose) {
									System.out.println("  # Removing '" + file.getPath() + "'");
								}
								PackrFileUtils.delete(file);
								removed = true;
								break;
							}
						}
					}
					if (!removed && extractLibs) {
						if (file.getName().endsWith(libExtension)) {
							if (config.verbose) {
								System.out.println("  # Extracting '" + file.getPath() + "'");
							}
							File target = new File(libsOutputDir, file.getName());
							PackrFileUtils.copyFile(file, target);
							PackrFileUtils.delete(file);
						}
					}
				}
			}

			if (!jar.isDirectory()) {
				if (config.verbose) {
					System.out.println("  # Repacking '" + jar.getName() + "' ...");
				}

				long beforeLen = jar.length();
				PackrFileUtils.delete(jar);

				ZipUtil.pack(jarDir, jar);
				FileUtils.deleteDirectory(jarDir);

				long afterLen = jar.length();
				if (config.verbose) {
					System.out.println("  # " + beforeLen / 1024 + " kb -> " + afterLen / 1024 + " kb");
				}
			}
		}
	}

}
