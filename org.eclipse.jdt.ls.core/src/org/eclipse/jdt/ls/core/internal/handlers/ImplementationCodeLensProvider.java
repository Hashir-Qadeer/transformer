/*******************************************************************************
 * Copyright (c) 2017 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.handlers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.ls.core.internal.JDTUtils;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.preferences.PreferenceManager;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Location;

/**
 * @author xuzho
 *
 */
public class ImplementationCodeLensProvider implements CodeLensProvider {
	private static final String JAVA_SHOW_IMPLEMENTATIONS_COMMAND = "java.show.implementations";
	private static final String IMPLEMENTATION_TYPE = "implementations";

	private final PreferenceManager preferenceManager;

	public ImplementationCodeLensProvider(PreferenceManager preferenceManager) {
		this.preferenceManager = preferenceManager;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.ls.core.internal.handlers.CodeLensProvider#resolveCodeLens(org.eclipse.lsp4j.CodeLens, org.eclipse.core.runtime.IProgressMonitor)
	 */
	@Override
	public CodeLens resolveCodeLens(CodeLens lens, IProgressMonitor monitor) {
		if (lens == null) {
			return null;
		}
		//Note that codelens resolution is honored if the request was emitted
		//before disabling codelenses in the preferences, else invalid codeLenses
		//(i.e. having no commands) would be returned.
		List<Object> data = (List<Object>) lens.getData();
		String type = (String) data.get(2);
		if (!type.equals(getType())) {
			return lens;
		}
		Map<String, Object> position = (Map<String, Object>) data.get(1);
		String uri = (String) data.get(0);
		String label = "implementation";
		String command = JAVA_SHOW_IMPLEMENTATIONS_COMMAND;
		List<Location> locations = null;
		try {
			ICompilationUnit unit = JDTUtils.resolveCompilationUnit(uri);
			if (unit != null) {
				IJavaElement element = JDTUtils.findElementAtSelection(unit, ((Double) position.get("line")).intValue(), ((Double) position.get("character")).intValue(), this.preferenceManager, monitor);
				if (element instanceof IType) {
					try {
						locations = findImplementations((IType) element, monitor);
					} catch (CoreException e) {
						JavaLanguageServerPlugin.logException(e.getMessage(), e);
					}
				}
			}
		} catch (CoreException e) {
			JavaLanguageServerPlugin.logException("Problem resolving code lens", e);
		}
		if (locations == null) {
			locations = Collections.emptyList();
		}
		int size = locations.size();
		Command c = new Command(size + " " + label + ((size == 1) ? "" : "s"), command, Arrays.asList(uri, position, locations));
		lens.setCommand(c);
		return lens;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.ls.core.internal.handlers.CodeLensProvider#getType()
	 */
	@Override
	public String getType() {
		return IMPLEMENTATION_TYPE;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jdt.ls.core.internal.handlers.CodeLensProvider#collectCodeLenses(org.eclipse.jdt.core.ICompilationUnit, org.eclipse.jdt.core.IJavaElement[], org.eclipse.core.runtime.IProgressMonitor)
	 */
	@Override
	public ArrayList<CodeLens> collectCodeLenses(ICompilationUnit unit, IJavaElement[] elements, IProgressMonitor monitor) throws JavaModelException {
		ArrayList<CodeLens> lenses = new ArrayList<>();
		for (IJavaElement element : elements) {
			if (monitor.isCanceled()) {
				return lenses;
			}
			if (element.getElementType() == IJavaElement.TYPE) {
				lenses.addAll(collectCodeLenses(unit, ((IType) element).getChildren(), monitor));
			} else if (element.getElementType() != IJavaElement.METHOD || JDTUtils.isHiddenGeneratedElement(element)) {
				continue;
			}

			if (preferenceManager.getPreferences().isImplementationsCodeLensEnabled() && element instanceof IType) {
				IType type = (IType) element;
				if (type.isInterface() || Flags.isAbstract(type.getFlags())) {
					CodeLens lens = getCodeLens(IMPLEMENTATION_TYPE, element, unit);
					lenses.add(lens);
				}
			}
		}
		return lenses;
	}

	private List<Location> findImplementations(IType type, IProgressMonitor monitor) throws JavaModelException {
		IType[] results = type.newTypeHierarchy(monitor).getAllSubtypes(type);
		final List<Location> result = new ArrayList<>();
		for (IType t : results) {
			ICompilationUnit compilationUnit = (ICompilationUnit) t.getAncestor(IJavaElement.COMPILATION_UNIT);
			if (compilationUnit == null) {
				continue;
			}
			Location location = JDTUtils.toLocation(t);
			result.add(location);
		}
		return result;
	}
}
