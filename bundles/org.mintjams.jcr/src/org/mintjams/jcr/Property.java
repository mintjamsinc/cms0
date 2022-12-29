/*
 * Copyright (c) 2022 MintJams Inc.
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

package org.mintjams.jcr;

public interface Property extends javax.jcr.Property {

	String JCR_PRIMARY_TYPE_NAME = "jcr:primaryType";
	String JCR_MIXIN_TYPES_NAME = "jcr:mixinTypes";
	String JCR_ENCODING_NAME = "jcr:encoding";
	String JCR_MIMETYPE_NAME = "jcr:mimeType";
	String JCR_DATA_NAME = "jcr:data";
	String JCR_CREATED_BY_NAME = "jcr:createdBy";
	String JCR_LAST_MODIFIED_BY_NAME = "jcr:lastModifiedBy";
	String JCR_FROZEN_PRIMARY_TYPE_NAME = "jcr:frozenPrimaryType";
	String JCR_FROZEN_MIXIN_TYPES_NAME = "jcr:mixinTypes";

}
