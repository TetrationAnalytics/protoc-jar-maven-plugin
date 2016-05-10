/*
 * Copyright 2014 protoc-jar developers
 * 
 * Incorporates code derived from https://github.com/igor-petruk/protobuf-maven-plugin
 * Copyright 2012, by Yet another Protobuf Maven Plugin Developers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.os72.protocjar.maven;

import com.github.os72.protocjar.Protoc;
import org.apache.commons.io.FilenameUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.FileUtils;
import org.sonatype.plexus.build.incremental.BuildContext;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedList;

/**
 * Compiles .proto files using protoc-jar embedded protoc compiler (external protoc executable also supported)
 * 
 * @goal run
 * @phase generate-sources
 * @requiresDependencyResolution
 */
public class ProtocJarMojo extends AbstractMojo
{
	private static final String DEFAULT_INPUT_DIR = "/src/main/protobuf/".replace('/', File.separatorChar);

	/**
	 * The Maven project.
	 * 
	 * @parameter property="project"
	 * @required
	 * @readonly
	 */
	private MavenProject project;

	/** @component */
	private BuildContext buildContext;

	/**
	 * Input directories that have *.proto files (or the configured extension).
	 * If none specified then <b>src/main/protobuf</b> is used.
	 * 
	 * @parameter property="inputDirectories"
	 */
	private File[] inputDirectories;

	/**
	 * This parameter lets you specify additional include paths to protoc.
	 * 
	 * @parameter property="includeDirectories"
	 */
	private File[] includeDirectories;

	/**
	 * If this parameter is set to "true" output folder is cleaned prior to the
	 * build. This will not let old and new classes coexist after package or
	 * class rename in your IDE cache or after non-clean rebuild. Set this to
	 * "false" if you are doing multiple plugin invocations per build and it is
	 * important to preserve output folder contents
	 * <p>
	 * Ignored when {@code <outputTargets>} is given
	 * 
	 * @parameter property="cleanOutputFolder" default-value="false"
	 */
	private boolean cleanOutputFolder;

	/**
	 * Specifies whether to add outputDirectory to sources that are going to be compiled.
	 * Options: "main", "test", "none" (default: "main")
	 * <p>
	 * Ignored when {@code <outputTargets>} is given
	 * 
	 * @parameter property="addSources" default-value="main"
	 */
	private String addSources;

	/**
	 * Output directory for the generated java files. Defaults to
	 * "${project.build.directory}/generated-sources/protobuf" or
	 * "${project.build.directory}/generated-test-sources/protobuf"
	 * depending on the addSources parameter
	 * <p>
	 * Ignored when {@code <outputTargets>} is given
	 * 
	 * @parameter property="outputDirectory"
	 */
	private File outputDirectory;

	/**
	 * Specifies output type.
	 * Options: "java",  "cpp", "python", "descriptor" (default: "java")
	 * <p>
	 * Ignored when {@code <outputTargets>} is given
	 * 
	 * @parameter property="type" default-value="java"
	 */
	String type;

	/**
	 * This parameter lets you specify multiple protoc output targets.
	 * OutputTarget parameters: "type", "addSources", "cleanOutputFolder", "outputDirectory".
	 * Type options: "java", "cpp", "python", "descriptor" (default: "java")
	 * 
	 * <pre>
	 * {@code
	 * <outputTargets>
	 * 	<outputTarget>
	 * 		<type>java</type>
	 * 		<addSources>none</addSources>
	 * 		<cleanOutputFolder>false</cleanOutputFolder>
	 * 		<outputDirectory>src/main/java</outputDirectory>
	 * 	</outputTarget>
	 * 	<outputTarget>
	 * 		<type>python</type>
	 * 		<addSources>none</addSources>
	 * 		<cleanOutputFolder>false</cleanOutputFolder>
	 * 		<outputDirectory>src/main/python</outputDirectory>
	 * 	</outputTarget>
	 * <outputTargets>
	 * }
	 * </pre>
	 * 
	 * @parameter property="outputTargets"
	 */
	private OutputTarget[] outputTargets;

	/**
	 * Default extension for protobuf files
	 * 
	 * @parameter property="extension" default-value=".proto"
	 */
	private String extension;

	/**
	 * This parameter allows to use a protoc binary instead of the protoc-jar bundle
	 * 
	 * @parameter property="protocCommand"
	 */
	private String protocCommand;

	public void execute() throws MojoExecutionException {
		if (project.getPackaging() != null && "pom".equals(project.getPackaging().toLowerCase())) {
			getLog().info("Skipping 'pom' packaged project");
			return;
		}
		
		if (outputTargets == null || outputTargets.length == 0) {
			OutputTarget target = new OutputTarget();
			target.type = type;
			target.addSources = addSources;
			target.cleanOutputFolder = cleanOutputFolder;
			target.outputDirectory = outputDirectory;
			outputTargets = new OutputTarget[] {target};
		}

		for (OutputTarget target : outputTargets) {
			target.addSources = target.addSources.toLowerCase().trim();
			if ("true".equals(target.addSources)) target.addSources = "main";
			
			if (target.outputDirectory == null) {
				String subdir = "generated-" + ("test".equals(target.addSources) ? "test-" : "") + "sources";
				target.outputDirectory = new File(project.getBuild().getDirectory() + File.separator + subdir + File.separator);
			}

			// Copy files to a temporary directory.
			target.outputDirectoryFinal = target.outputDirectory;
			target.outputDirectory = new File(target.outputDirectory.getParent(), target.outputDirectory.getName() + "-tmp");
		}
		
		performProtoCompilation();
	}

	private void copyAndUpdateOutputTarget(OutputTarget outputTarget) throws MojoExecutionException {
		File src = outputTarget.outputDirectory;
		File dst = outputTarget.outputDirectoryFinal;

		dst.mkdirs();

		// List files in dst and copy them to target.
		Collection<File> files = org.apache.commons.io.FileUtils.listFiles(src, null, true);

		for (File generatedFile : files) {
			String path = generatedFile.getAbsolutePath();
			path = path.replaceAll("^" + src.getAbsolutePath(), dst.getAbsolutePath());
			File targetFile = new File(path);

			try {
				if (org.apache.commons.io.FileUtils.contentEquals(generatedFile, targetFile)) {
					getLog().info("Skipping " + targetFile.getName());
					continue;
				}
			} catch (IOException ignored) {
				// Copy the file on any error, for e.g., if the dstFile does not exist.
			}

			try {
				// Remove the target file if it already exists.
				targetFile.delete();
				org.apache.commons.io.FileUtils.copyFile(generatedFile, targetFile);
				getLog().info("Updating " + targetFile.getName());
			} catch (IOException e) {
				throw new MojoExecutionException("Error copying file from " + targetFile + " to " + generatedFile, e);
			}
		}

		// Update the output directory and restore to its true, final, path.
		outputTarget.outputDirectory = outputTarget.outputDirectoryFinal;
	}

	private void performProtoCompilation() throws MojoExecutionException {	
		if (includeDirectories != null && includeDirectories.length > 0) {
			getLog().info("Include directories:");
			for (File include : includeDirectories) getLog().info("    " + include);
		}
		
		getLog().info("Input directories:");
		for (File input : inputDirectories) getLog().info("    " + input);
		
		if (includeDirectories == null || inputDirectories.length == 0) {
			File inputDir = new File(project.getBasedir().getAbsolutePath() + DEFAULT_INPUT_DIR);
			getLog().info("    " + inputDir + " (using default)");
			inputDirectories = new File[] { inputDir };
		}
		
		getLog().info("Output targets:");
		for (OutputTarget target : outputTargets) getLog().info("    " + target);
		for (OutputTarget target : outputTargets) preprocessTarget(target);
		for (OutputTarget target : outputTargets) processTarget(target);
	}

	private void preprocessTarget(OutputTarget target) throws MojoExecutionException {
		File f = target.outputDirectory;
		if (!f.exists()) {
			getLog().info(f + " does not exist. Creating...");
			f.mkdirs();
		}
		
		if (target.cleanOutputFolder) {
			try {
				getLog().info("Cleaning " + f);
				FileUtils.cleanDirectory(f);
			}
			catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private void processTarget(OutputTarget target) throws MojoExecutionException {		
		FileFilter fileFilter = new FileFilter(extension);
		for (File input : inputDirectories) {
			if (input == null) continue;
			
			if (input.exists() && input.isDirectory()) {
				File[] files = input.listFiles(fileFilter);
				for (File file : files) {
					if (target.cleanOutputFolder || buildContext.hasDelta(file.getPath())) processFile(file, target.type, target.outputDirectory);
					else getLog().info("Not changed " + file);
				}
			}
			else {
				if (input.exists()) getLog().warn(input + " is not a directory");
				else getLog().warn(input + " does not exist");
			}
		}
		
		boolean mainAddSources = "main".endsWith(target.addSources);
		boolean testAddSources = "test".endsWith(target.addSources);

		copyAndUpdateOutputTarget(target);
		
		if (mainAddSources) {
			getLog().info("Adding generated classes in " + target.outputDirectory.getAbsolutePath() + " to classpath");
			project.addCompileSourceRoot(target.outputDirectory.getAbsolutePath());
		}
		if (testAddSources) {
			getLog().info("Adding generated classes in " + target.outputDirectory.getAbsolutePath() + " to test classpath");
			project.addTestCompileSourceRoot(target.outputDirectory.getAbsolutePath());
		}
		if (mainAddSources || testAddSources) {
			buildContext.refresh(target.outputDirectory);
		}
	}

	private void processFile(File file, String type, File outputDir) throws MojoExecutionException {
		getLog().info("    Processing ("+ type + "): " + file.getName());
		Collection<String> cmd = buildCommand(file, type, outputDir);
		try {
			int ret = 0;
			if (protocCommand == null) ret = Protoc.runProtoc(cmd.toArray(new String[0]));
			else ret = Protoc.runProtoc(protocCommand, cmd.toArray(new String[0]));
			if (ret != 0) throw new MojoExecutionException("protoc-jar failed for " + file + ". Exit code " + ret);
		}
		catch (InterruptedException e) {
			throw new MojoExecutionException("Interrupted", e);
		}
		catch (IOException e) {
			throw new MojoExecutionException("Unable to execute protoc-jar for " + file, e);
		}
	}

	private Collection<String> buildCommand(File file, String type, File outputDir) throws MojoExecutionException {
		Collection<String> cmd = new LinkedList<String>();
		populateIncludes(cmd);
		cmd.add("-I" + file.getParentFile().getAbsolutePath());
		if ("descriptor".equals(type)) {
			File outFile = new File(outputDir, file.getName());
			cmd.add("--descriptor_set_out=" + FilenameUtils.removeExtension(outFile.toString()) + ".desc");
			cmd.add("--include_imports");
		}
		else if ("python".equals(type)) {
			cmd.add("--python_out=" + outputDir);
		}
		else if ("cpp".equals(type)) {
			cmd.add("--cpp_out=" + outputDir);
		}
		else {
			cmd.add("--java_out=" + outputDir);
		}
		cmd.add(file.toString());
		return cmd;
	}

	private void populateIncludes(Collection<String> args) throws MojoExecutionException {
		for (File include : includeDirectories) {
			if (!include.exists()) throw new MojoExecutionException("Include path '" + include.getPath() + "' does not exist");
			if (!include.isDirectory()) throw new MojoExecutionException("Include path '" + include.getPath() + "' is not a directory");
			args.add("-I" + include.getPath());
		}
	}

	class FileFilter implements FilenameFilter
	{
		String extension;

		public FileFilter(String extension) {
			this.extension = extension;
		}

		public boolean accept(File dir, String name) {
			return name.endsWith(extension);
		}
	}
}
