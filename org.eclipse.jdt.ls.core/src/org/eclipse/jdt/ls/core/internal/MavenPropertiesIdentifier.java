package org.eclipse.jdt.ls.core.internal;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.m2e.core.embedder.ArtifactKey;


public class MavenPropertiesIdentifier {

	public ArtifactKey identify(IPath path, IProgressMonitor monitor) {
		if (path == null) {
			return null;
		}
		return identify(path.toFile());
	}

	public ArtifactKey identify(File file) {
		if (file == null || !file.isFile() || !file.canRead()) {
			return null;
		}
		try (ZipFile jar = new ZipFile(file)) {
			return getArtifactFromPomProperties(jar);
		} catch (IOException e) {
			JavaLanguageServerPlugin.logError("Failed to identify " + file + " : " + e);
		}
		return null;
	}

	private ArtifactKey getArtifactFromPomProperties(ZipFile jar) throws IOException {
		ZipEntry mavenEntry = jar.getEntry("META-INF/maven");//$NON-NLS-1$
		if (mavenEntry == null) {
			return null;
		}
		String jarName = jar.getName();
		String entryName = mavenEntry.getName();
		Enumeration<? extends ZipEntry> zipEntries = jar.entries();
		ArtifactKey artifact = null;

		while (zipEntries.hasMoreElements()) {
			ZipEntry zipEntry = zipEntries.nextElement();
			if (zipEntry.getName().endsWith("pom.properties") && zipEntry.getName().startsWith(entryName)) {
				Properties props = new Properties();
				props.load(jar.getInputStream(zipEntry));
				String groupId = props.getProperty("groupId");
				String artifactId = props.getProperty("artifactId");
				String version = props.getProperty("version");
				String classifier = props.getProperty("classifier");
				if (groupId != null && artifactId != null && version != null) {
					ArtifactKey currentArtifact = new ArtifactKey(groupId, artifactId, version, classifier);
					if (artifact == null) {
						artifact = currentArtifact;
					} else {
						//Shaded artifacts can contain multiple pom.properties. Our best bet is now to find the one matching the file name
						// eg. org.fusesource.jansi:jansi:1.6
						if (jarName.contains(artifactId + "-" + version)) {
							artifact = currentArtifact;
						}
					}
				}
			}
		}

		return artifact;
	}
}