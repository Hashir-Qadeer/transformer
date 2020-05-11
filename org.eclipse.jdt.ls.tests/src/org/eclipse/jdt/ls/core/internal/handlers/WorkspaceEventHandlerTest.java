/*******************************************************************************
 * Copyright (c) 2020 Microsoft Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Microsoft Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.jdt.ls.core.internal.handlers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;

import com.google.common.io.Files;

import org.eclipse.core.resources.IFolder;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.manipulation.CoreASTProvider;
import org.eclipse.jdt.ls.core.internal.JDTUtils;
import org.eclipse.jdt.ls.core.internal.JavaClientConnection;
import org.eclipse.jdt.ls.core.internal.managers.AbstractProjectsManagerBasedTest;
import org.eclipse.jdt.ls.core.internal.preferences.ClientPreferences;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.TextDocumentItem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

public class WorkspaceEventHandlerTest extends AbstractProjectsManagerBasedTest {
	private CoreASTProvider sharedASTProvider;
	private DocumentLifeCycleHandler lifeCycleHandler;
	private JavaClientConnection javaClient;

	@Mock
	private ClientPreferences clientPreferences;

	@Before
	public void setup() throws Exception {
		sharedASTProvider = CoreASTProvider.getInstance();
		sharedASTProvider.disposeAST();
		javaClient = new JavaClientConnection(client);
		lifeCycleHandler = new DocumentLifeCycleHandler(javaClient, preferenceManager, projectsManager, false);
	}

	@After
	public void tearDown() throws Exception {
		javaClient.disconnect();
		for (ICompilationUnit cu : JavaCore.getWorkingCopies(null)) {
			cu.discardWorkingCopy();
		}
	}

	@Test
	public void testDiscardStaleWorkingCopies() throws Exception {
		IJavaProject javaProject = newEmptyProject();
		IFolder src = javaProject.getProject().getFolder("src");
		IPackageFragmentRoot srcRoot = javaProject.getPackageFragmentRoot(src);
		IPackageFragment mypack = srcRoot.createPackageFragment("mypack", true, null);
		// @formatter:off
		String contents = "package mypack;\n" +
						"public class Foo {" +
						"}\n";
		// @formatter:on
		ICompilationUnit unit = mypack.createCompilationUnit("Foo.java", contents, true, null);
		openDocument(unit, contents, 1);
		assertTrue(unit.isWorkingCopy());

		String oldUri = JDTUtils.getFileURI(srcRoot.getResource());
		String parentUri = JDTUtils.getFileURI(src);
		String newUri = oldUri.replace("mypack", "mynewpack");
		File oldPack = mypack.getResource().getLocation().toFile();
		File newPack = new File(oldPack.getParent(), "mynewpack");
		Files.move(oldPack, newPack);
		assertTrue(unit.isWorkingCopy());
		DidChangeWatchedFilesParams params = new DidChangeWatchedFilesParams(Arrays.asList(
			new FileEvent(newUri, FileChangeType.Created),
			new FileEvent(parentUri, FileChangeType.Changed),
			new FileEvent(oldUri, FileChangeType.Deleted)
		));
		new WorkspaceEventsHandler(projectsManager, javaClient, lifeCycleHandler).didChangeWatchedFiles(params);
		assertFalse(unit.isWorkingCopy());
	}

	private void openDocument(ICompilationUnit cu, String content, int version) {
		openDocument(JDTUtils.toURI(cu), content, version);
	}

	private void openDocument(String uri, String content, int version) {
		DidOpenTextDocumentParams openParms = new DidOpenTextDocumentParams();
		TextDocumentItem textDocument = new TextDocumentItem();
		textDocument.setLanguageId("java");
		textDocument.setText(content);
		textDocument.setUri(uri);
		textDocument.setVersion(version);
		openParms.setTextDocument(textDocument);
		lifeCycleHandler.didOpen(openParms);
	}
}
