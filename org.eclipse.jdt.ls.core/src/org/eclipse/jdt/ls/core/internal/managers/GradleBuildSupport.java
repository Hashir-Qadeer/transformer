/*******************************************************************************
 * Copyright (c) 2016-2022 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.managers;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.buildship.core.BuildConfiguration;
import org.eclipse.buildship.core.GradleBuild;
import org.eclipse.buildship.core.GradleCore;
import org.eclipse.buildship.core.internal.CorePlugin;
import org.eclipse.buildship.core.internal.launch.GradleClasspathProvider;
import org.eclipse.buildship.core.internal.preferences.PersistentModel;
import org.eclipse.buildship.core.internal.util.file.FileUtils;
import org.eclipse.buildship.core.internal.workspace.FetchStrategy;
import org.eclipse.buildship.core.internal.workspace.InternalGradleBuild;
import org.eclipse.buildship.core.internal.workspace.WorkbenchShutdownEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.jdt.apt.core.util.AptConfig;
import org.eclipse.jdt.apt.core.util.IFactoryPath;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.ProjectUtils;
import org.eclipse.jdt.ls.core.internal.ResourceUtils;
import org.eclipse.jdt.ls.core.internal.managers.ProjectsManager.CHANGE_TYPE;
import org.eclipse.jdt.ls.core.internal.preferences.IPreferencesChangeListener;
import org.eclipse.jdt.ls.core.internal.preferences.PreferenceManager;
import org.gradle.tooling.CancellationTokenSource;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.model.eclipse.EclipseProject;

/**
 * @author Fred Bricon
 *
 */
public class GradleBuildSupport implements IBuildSupport {

	public static final Pattern GRADLE_FILE_EXT = Pattern.compile("^.*\\.gradle(\\.kts)?$");
	public static final String GRADLE_PROPERTIES = "gradle.properties";
	public static final List<String> WATCH_FILE_PATTERNS = Arrays.asList("**/*.gradle", "**/*.gradle.kts", "**/gradle.properties");
	public static final String UNSUPPORTED_ON_GRADLE = "Unsupported operation. Please use build.gradle file to manage the source directories of gradle project.";

	private static IPreferencesChangeListener listener = new GradlePreferenceChangeListener();

	/**
	 * The relative path where store the sources generated by annotation processors
	 */
	private static final String GENERATED_SOURCES_PATH = "bin/generated-sources/annotations";
	/**
	 * The relative path where store the test sources generated by annotation processors
	 */
	private static final String GENERATED_TEST_SOURCES_PATH = "bin/generated-test-sources/annotations";

	@Override
	public boolean applies(IProject project) {
		return ProjectUtils.isGradleProject(project);
	}

	@Override
	public void update(IProject project, boolean force, IProgressMonitor monitor) throws CoreException {
		if (!applies(project)) {
			return;
		}
		JavaLanguageServerPlugin.logInfo("Starting Gradle update for " + project.getName());
		Optional<GradleBuild> build = GradleCore.getWorkspace().getBuild(project);
		if (build.isPresent()) {
			GradleBuild gradleBuild = build.get();
			boolean isRoot = isRoot(project, gradleBuild, monitor);
			if (force && isRoot) {
				String projectPath = project.getLocation().toFile().getAbsolutePath();
				BuildConfiguration buildConfiguration = GradleProjectImporter.getBuildConfiguration(Paths.get(projectPath));
				gradleBuild = GradleCore.getWorkspace().createBuild(buildConfiguration);
			}
			File buildFile = project.getFile(GradleProjectImporter.BUILD_GRADLE_DESCRIPTOR).getLocation().toFile();
			File settingsFile = project.getFile(GradleProjectImporter.SETTINGS_GRADLE_DESCRIPTOR).getLocation().toFile();
			File buildKtsFile = project.getFile(GradleProjectImporter.BUILD_GRADLE_KTS_DESCRIPTOR).getLocation().toFile();
			File settingsKtsFile = project.getFile(GradleProjectImporter.SETTINGS_GRADLE_KTS_DESCRIPTOR).getLocation().toFile();
			boolean shouldUpdate = (buildFile.exists() && JavaLanguageServerPlugin.getDigestStore().updateDigest(buildFile.toPath()))
					|| (settingsFile.exists() && JavaLanguageServerPlugin.getDigestStore().updateDigest(settingsFile.toPath())) 
					|| (buildKtsFile.exists() && JavaLanguageServerPlugin.getDigestStore().updateDigest(buildKtsFile.toPath()))
					|| (settingsKtsFile.exists() && JavaLanguageServerPlugin.getDigestStore().updateDigest(settingsKtsFile.toPath()));
			if (isRoot || shouldUpdate) {
				gradleBuild.synchronize(monitor);
				syncAnnotationProcessingConfiguration(gradleBuild, monitor);
			}
		}
	}

	public static void syncAnnotationProcessingConfiguration(IProject project, IProgressMonitor monitor) {
		Optional<GradleBuild> build = GradleCore.getWorkspace().getBuild(project);
		if (build.isPresent()) {
			syncAnnotationProcessingConfiguration(build.get(), monitor);
		}
	}

	/**
	 * Synchronize the annotation processing configurations to JDT APT.
	 * @param gradleBuild The GradleBuild instance.
	 * @param monitor progress monitor.
	 */
	@SuppressWarnings("unchecked")
	public static void syncAnnotationProcessingConfiguration(GradleBuild gradleBuild, IProgressMonitor monitor) {
		PreferenceManager preferencesManager = JavaLanguageServerPlugin.getPreferencesManager();
		if (preferencesManager == null) {
			return;
		}
		if (!preferencesManager.getPreferences().isGradleAnnotationProcessingEnabled()) {
			return;
		}

		File initScript = GradleUtils.getGradleInitScript("/gradle/apt/init.gradle");
		if (initScript == null) {
			return;
		}

		Map<File, Map<String, Object>> model = null;
		try {
			model = gradleBuild.withConnection(connection -> {
				return connection.model(Map.class).withArguments("--init-script", initScript.getAbsolutePath()).get();
			}, monitor);
		} catch (Exception e) {
			JavaLanguageServerPlugin.logException(e.getMessage(), e);
		}

		if (model == null) {
			return;
		}

		// Even reloading a sub project will get the annotation processors for all
		// the projects, due to the Gradle custom model api's limitation.
		for (IProject project : ProjectUtils.getGradleProjects()) {
			IJavaProject javaProject = JavaCore.create(project);
			if (javaProject == null) {
				continue;
			}

			Map<String, Object> apConfigurations = model.get(project.getLocation().toFile());
			if (apConfigurations == null) {
				continue;
			}
			
			if (apConfigurations.isEmpty()) {
				disableApt(javaProject);
				continue;
			}

			Set<File> processors = getProcessors(apConfigurations);
			if (processors.isEmpty()) {
				continue;
			}

			AptConfig.setGenSrcDir(javaProject, GENERATED_SOURCES_PATH);
			AptConfig.setGenTestSrcDir(javaProject, GENERATED_TEST_SOURCES_PATH);

			if (!AptConfig.isEnabled(javaProject)) {
				// setEnabled will ensure the output folder existing on disk, that why
				// we set enabled status after the output folder is set to APT, which can
				// avoid generating default output folder.
				AptConfig.setEnabled(javaProject, true);
			}

			IFactoryPath factoryPath = AptConfig.getDefaultFactoryPath(javaProject);
			for(File processor : processors){
				factoryPath.addExternalJar(processor);
			}

			try {
				AptConfig.setFactoryPath(javaProject, factoryPath);
			} catch (CoreException e) {
				JavaLanguageServerPlugin.log(e);
			}

			List<String> compilerArgs = getCompilerArgs(apConfigurations);
			Map<String, String> newOptions = GradleUtils.parseProcessorOptions(compilerArgs);
			Map<String, String> currentOptions = AptConfig.getRawProcessorOptions(javaProject);
			if(!currentOptions.equals(newOptions)) {
				AptConfig.setProcessorOptions(newOptions, javaProject);
			}
		}
	}

	private boolean isRoot(IProject project, GradleBuild gradleBuild, IProgressMonitor monitor) {
		if (gradleBuild instanceof InternalGradleBuild) {
			CancellationTokenSource tokenSource = GradleConnector.newCancellationTokenSource();
			Map<String, EclipseProject> eclipseProjects = ((InternalGradleBuild) gradleBuild).getModelProvider().fetchModels(EclipseProject.class, FetchStrategy.LOAD_IF_NOT_CACHED, tokenSource, monitor);
			File projectDirectory = project.getLocation().toFile();
			for (EclipseProject eclipseProject : eclipseProjects.values()) {
				File eclipseProjectDirectory = eclipseProject.getProjectDirectory();
				if (eclipseProjectDirectory.equals(projectDirectory)) {
					return eclipseProject.getParent() == null;
				}
			}
		}
		return false;
	}

	@Override
	public boolean isBuildFile(IResource resource) {
		if (resource != null && resource.getType() == IResource.FILE && isBuildLikeFileName(resource.getName())
			&& ProjectUtils.isGradleProject(resource.getProject())) {
			try {
				if (!ProjectUtils.isJavaProject(resource.getProject())) {
					return true;
				}
				IJavaProject javaProject = JavaCore.create(resource.getProject());
				IPath outputLocation = javaProject.getOutputLocation();
				return outputLocation == null || !outputLocation.isPrefixOf(resource.getFullPath());
			} catch (JavaModelException e) {
				JavaLanguageServerPlugin.logException(e.getMessage(), e);
			}
		}
		return false;
	}

	@Override
	public boolean isBuildLikeFileName(String fileName) {
		return GRADLE_FILE_EXT.matcher(fileName).matches() || fileName.equals(GRADLE_PROPERTIES);
	}

	/**
	 * delete stale gradle project preferences
	 *
	 * @param monitor
	 */
	public static void cleanGradleModels(IProgressMonitor monitor) {
		File projectPreferences = CorePlugin.getInstance().getStateLocation().append("project-preferences").toFile();
		if (projectPreferences.isDirectory()) {
			File[] projectFiles = projectPreferences.listFiles();
			for (File projectFile : projectFiles) {
				String projectName = projectFile.getName();
				if (!ResourcesPlugin.getWorkspace().getRoot().getProject(projectName).exists()) {
					FileUtils.deleteRecursively(projectFile);
				}
			}
		}
	}

	@Override
	public boolean fileChanged(IResource resource, CHANGE_TYPE changeType, IProgressMonitor monitor) throws CoreException {
		if (resource == null || !applies(resource.getProject())) {
			return false;
		}
		return IBuildSupport.super.fileChanged(resource, changeType, monitor) || isBuildFile(resource);
	}

	@Override
	public boolean useDefaultVM(IProject project, IVMInstall defaultVM) {
		return GradleProjectImporter.useDefaultVM();
	}

	/**
	 * save gradle project preferences
	 *
	 */
	public static void saveModels() {
		CorePlugin.listenerRegistry().dispatch(new WorkbenchShutdownEvent());
	}

	@Override
	public ILaunchConfiguration getLaunchConfiguration(IJavaProject javaProject, String scope) throws CoreException {
		return new JavaApplicationLaunchConfiguration(javaProject.getProject(), scope, GradleClasspathProvider.ID);
	}

	@Override
	public List<String> getWatchPatterns() {
		return WATCH_FILE_PATTERNS;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.ls.core.internal.managers.IBuildSupport.registerPreferencesChangeListener(PreferenceManager)
	 */
	@Override
	public void registerPreferencesChangeListener(PreferenceManager preferenceManager) throws CoreException {
		preferenceManager.addPreferencesChangeListener(listener);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.ls.core.internal.managers.IBuildSupport.unregisterPreferencesChangeListener(PreferenceManager)
	 */
	@Override
	public void unregisterPreferencesChangeListener(PreferenceManager preferenceManager) throws CoreException {
		preferenceManager.removePreferencesChangeListener(listener);
	}

	@Override
	public String buildToolName() {
		return "Gradle";
	}

	@Override
	public String unsupportedOperationMessage() {
		return UNSUPPORTED_ON_GRADLE;
	}

	@Override
	public boolean hasSpecificDeleteProjectLogic() {
		return true;
	}

	@Override
	public void deleteInvalidProjects(Collection<IPath> rootPaths, ArrayList<IProject> deleteProjectCandates, IProgressMonitor monitor) {
		List<IProject> validGradleProjects = new ArrayList<>();
		List<IProject> suspiciousGradleProjects = new ArrayList<>();

		for (IProject project : deleteProjectCandates) {
			if (applies(project)) {
				if (ResourceUtils.isContainedIn(project.getLocation(), rootPaths)) {
					validGradleProjects.add(project);
				} else {
					suspiciousGradleProjects.add(project);
				}
			}

		}

		List<IProject> unrelatedProjects = findUnrelatedGradleProjects(suspiciousGradleProjects, validGradleProjects);
		unrelatedProjects.forEach((project) -> {
			try {
				project.delete(false, true, monitor);
			} catch (CoreException e1) {
				JavaLanguageServerPlugin.logException(e1.getMessage(), e1);
			}
		});
	}

	/**
	 * Find those gradle projects not referenced by any gradle project in the
	 * current workspace.
	 */
	private List<IProject> findUnrelatedGradleProjects(List<IProject> suspiciousProjects, List<IProject> validProjects) {
		suspiciousProjects.sort((IProject p1, IProject p2) -> p1.getLocation().toOSString().length() - p2.getLocation().toOSString().length());

		List<IProject> unrelatedCandidates = new ArrayList<>();
		Collection<IPath> validSubPaths = new ArrayList<>();
		for (IProject suspiciousProject : suspiciousProjects) {
			if (validSubPaths.contains(suspiciousProject.getFullPath().makeRelative())) {
				continue;
			}

			// Check whether the suspicious gradle project is the parent project of the opening project.
			boolean isParentProject = false;
			Collection<IPath> subpaths = null;
			PersistentModel model = CorePlugin.modelPersistence().loadModel(suspiciousProject);
			if (model.isPresent()) {
				subpaths = model.getSubprojectPaths();
				if (!subpaths.isEmpty()) {
					for (IProject validProject : validProjects) {
						if (subpaths.contains(validProject.getFullPath().makeRelative())) {
							isParentProject = true;
							break;
						}
					}
				}
			}

			if (isParentProject) {
				validSubPaths.addAll(subpaths);
			} else {
				unrelatedCandidates.add(suspiciousProject);
			}
		}

		List<IProject> result = new ArrayList<>();
		// Exclude those projects which are the subprojects of the verified parent project.
		for (IProject candidate : unrelatedCandidates) {
			if (!validSubPaths.contains(candidate.getFullPath().makeRelative())) {
				result.add(candidate);
			}
		}

		return result;
	}

	@SuppressWarnings("unchecked")
	private static List<String> getCompilerArgs(Map<String, Object> apConfigurations) {
		Object object = apConfigurations.get("compilerArgs");
		if (!(object instanceof List)) {
			return Collections.emptyList();
		}
		return (List<String>) object;
	}

	@SuppressWarnings("unchecked")
	private static Set<File> getProcessors(Map<String, Object> apConfigurations) {
		Object object = apConfigurations.get("processors");
		if (!(object instanceof Set)) {
			return Collections.emptySet();
		}
		return (Set<File>) object;
	}

	private static void disableApt(IJavaProject javaProject) {
		if (AptConfig.isEnabled(javaProject)) {
			AptConfig.setEnabled(javaProject, false);
		}
	}
}
