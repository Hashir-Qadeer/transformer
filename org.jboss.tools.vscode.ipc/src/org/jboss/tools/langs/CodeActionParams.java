/*******************************************************************************
 * Copyright (c) 2016 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/

package org.jboss.tools.langs;

import javax.annotation.Generated;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

@Generated("org.jsonschema2pojo")
public class CodeActionParams {

    @SerializedName("textDocument")
    @Expose
    private TextDocumentIdentifier textDocument;
    @SerializedName("range")
    @Expose
    private Range range;
    @SerializedName("context")
    @Expose
    private CodeActionContext context;

    /**
     * 
     * @return
     *     The textDocument
     */
    public TextDocumentIdentifier getTextDocument() {
        return textDocument;
    }

    /**
     * 
     * @param textDocument
     *     The textDocument
     */
    public void setTextDocument(TextDocumentIdentifier textDocument) {
        this.textDocument = textDocument;
    }

    public CodeActionParams withTextDocument(TextDocumentIdentifier textDocument) {
        this.textDocument = textDocument;
        return this;
    }

    /**
     * 
     * @return
     *     The range
     */
    public Range getRange() {
        return range;
    }

    /**
     * 
     * @param range
     *     The range
     */
    public void setRange(Range range) {
        this.range = range;
    }

    public CodeActionParams withRange(Range range) {
        this.range = range;
        return this;
    }

    /**
     * 
     * @return
     *     The context
     */
    public CodeActionContext getContext() {
        return context;
    }

    /**
     * 
     * @param context
     *     The context
     */
    public void setContext(CodeActionContext context) {
        this.context = context;
    }

    public CodeActionParams withContext(CodeActionContext context) {
        this.context = context;
        return this;
    }

}
