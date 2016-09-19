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

package org.jboss.tools.langs.base;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;


/**
 * A notification message. A processed notification message must not send a response back.They work like events.
 * 
 */
public class NotificationMessage <R> extends Message
{

    /**
     * The method to be invoked.
     * 
     */
    @SerializedName("method")
    @Expose
    private String method;
    /**
     * The method's params.
     * 
     */
    @SerializedName("params")
    @Expose
    private R params;

    /**
     * The method to be invoked.
     * 
     * @return
     *     The method
     */
    public String getMethod() {
        return method;
    }

    /**
     * The method to be invoked.
     * 
     * @param method
     *     The method
     */
    public void setMethod(String method) {
        this.method = method;
    }

    /**
     * The method's params.
     * 
     * @return
     *     The params
     */
    public R getParams() {
        return params;
    }

    /**
     * The method's params.
     * 
     * @param params
     *     The params
     */
    public void setParams(R params) {
        this.params = params;
    }

}
