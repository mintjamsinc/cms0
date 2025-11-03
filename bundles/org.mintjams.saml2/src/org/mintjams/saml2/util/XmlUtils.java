/*
 * Copyright (c) 2024 MintJams Inc.
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

package org.mintjams.saml2.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.mintjams.saml2.exception.Saml2Exception;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Utility class for XML operations in SAML 2.0 processing.
 */
public class XmlUtils {

	private static final String SAML2_PROTOCOL_NS = "urn:oasis:names:tc:SAML:2.0:protocol";
	private static final String SAML2_ASSERTION_NS = "urn:oasis:names:tc:SAML:2.0:assertion";

	/**
	 * Creates a new DocumentBuilder with security features enabled.
	 *
	 * @return a secure DocumentBuilder
	 * @throws Saml2Exception if creation fails
	 */
	public static DocumentBuilder createSecureDocumentBuilder() throws Saml2Exception {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			// Security features to prevent XXE attacks
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setExpandEntityReferences(false);
			return factory.newDocumentBuilder();
		} catch (Exception ex) {
			throw new Saml2Exception("Failed to create DocumentBuilder", ex);
		}
	}

	/**
	 * Parses a Base64 encoded SAML response.
	 *
	 * @param base64Response the Base64 encoded SAML response
	 * @return the parsed XML document
	 * @throws Saml2Exception if parsing fails
	 */
	public static Document parseBase64Response(String base64Response) throws Saml2Exception {
		try {
			byte[] decoded = Base64.getDecoder().decode(base64Response);
			DocumentBuilder builder = createSecureDocumentBuilder();
			return builder.parse(new ByteArrayInputStream(decoded));
		} catch (Exception ex) {
			throw new Saml2Exception("Failed to parse Base64 SAML response", ex);
		}
	}

	/**
	 * Parses a Base64 encoded and deflated SAML request.
	 *
	 * @param base64Request the Base64 encoded and deflated SAML request
	 * @return the parsed XML document
	 * @throws Saml2Exception if parsing fails
	 */
	public static Document parseBase64DeflatedRequest(String base64Request) throws Saml2Exception {
		try {
			byte[] decoded = Base64.getDecoder().decode(base64Request);
			ByteArrayInputStream bais = new ByteArrayInputStream(decoded);
			InflaterInputStream iis = new InflaterInputStream(bais, new Inflater(true));
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			byte[] buffer = new byte[1024];
			int length;
			while ((length = iis.read(buffer)) > 0) {
				baos.write(buffer, 0, length);
			}
			DocumentBuilder builder = createSecureDocumentBuilder();
			return builder.parse(new ByteArrayInputStream(baos.toByteArray()));
		} catch (Exception ex) {
			throw new Saml2Exception("Failed to parse Base64 deflated SAML request", ex);
		}
	}

	/**
	 * Converts an XML document to a string.
	 *
	 * @param document the XML document
	 * @return the XML string
	 * @throws Saml2Exception if conversion fails
	 */
	public static String documentToString(Document document) throws Saml2Exception {
		try {
			TransformerFactory tf = TransformerFactory.newInstance();
			Transformer transformer = tf.newTransformer();
			transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
			transformer.setOutputProperty(OutputKeys.METHOD, "xml");
			transformer.setOutputProperty(OutputKeys.INDENT, "no");
			transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");

			StringWriter writer = new StringWriter();
			transformer.transform(new DOMSource(document), new StreamResult(writer));
			return writer.toString();
		} catch (Exception ex) {
			throw new Saml2Exception("Failed to convert document to string", ex);
		}
	}

	/**
	 * Gets the first element with the specified tag name and namespace.
	 *
	 * @param parent the parent element
	 * @param namespace the namespace URI
	 * @param localName the local name
	 * @return the first matching element, or null if not found
	 */
	public static Element getFirstElement(Element parent, String namespace, String localName) {
		NodeList nodes = parent.getElementsByTagNameNS(namespace, localName);
		if (nodes.getLength() > 0) {
			return (Element) nodes.item(0);
		}
		return null;
	}

	/**
	 * Gets the text content of the first element with the specified tag name and namespace.
	 *
	 * @param parent the parent element
	 * @param namespace the namespace URI
	 * @param localName the local name
	 * @return the text content, or null if not found
	 */
	public static String getFirstElementText(Element parent, String namespace, String localName) {
		Element element = getFirstElement(parent, namespace, localName);
		return element != null ? element.getTextContent() : null;
	}

	/**
	 * Gets all elements with the specified tag name and namespace.
	 *
	 * @param parent the parent element
	 * @param namespace the namespace URI
	 * @param localName the local name
	 * @return NodeList of matching elements
	 */
	public static NodeList getElements(Element parent, String namespace, String localName) {
		return parent.getElementsByTagNameNS(namespace, localName);
	}

	/**
	 * Gets an attribute value from an element.
	 *
	 * @param element the element
	 * @param attributeName the attribute name
	 * @return the attribute value, or null if not found
	 */
	public static String getAttribute(Element element, String attributeName) {
		if (element.hasAttribute(attributeName)) {
			return element.getAttribute(attributeName);
		}
		return null;
	}

	/**
	 * Gets the SAML 2.0 Protocol namespace URI.
	 *
	 * @return the protocol namespace URI
	 */
	public static String getProtocolNamespace() {
		return SAML2_PROTOCOL_NS;
	}

	/**
	 * Gets the SAML 2.0 Assertion namespace URI.
	 *
	 * @return the assertion namespace URI
	 */
	public static String getAssertionNamespace() {
		return SAML2_ASSERTION_NS;
	}

}
