package dev.ftcsim.importer.resources;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/** Reads the {@code res/values} files of Android resources. The files come from libraries, so no DTD is honoured. */
final class ValuesXml {
    /** The kinds of value the simulator's Android layer can answer by id. */
    static final Set<String> READABLE_TYPES = Set.of("string", "integer", "bool", "color");

    private ValuesXml() {}

    /** The elements directly below {@code <resources>}. */
    static List<Element> elements(InputStream in, String source) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Element root = factory.newDocumentBuilder().parse(in).getDocumentElement();
            List<Element> elements = new ArrayList<>();
            for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element element) {
                    elements.add(element);
                }
            }
            return elements;
        } catch (ParserConfigurationException | SAXException e) {
            throw new ResourceException(source + " is not a readable resource file: " + e.getMessage(), e);
        }
    }

    /** Resource names are written with dots in XML and with underscores in {@code R}. */
    static String symbolName(String xmlName) {
        return xmlName.replace('.', '_');
    }
}
