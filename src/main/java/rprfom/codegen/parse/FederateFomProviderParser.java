package rprfom.codegen.parse;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import rprfom.codegen.model.FederateSelection;

/**
 * Parseur du fichier "FederateFOMProvider" (conforme a
 * federatesFOMProvider.xsd) genere par l'outil FOMProvider : la liste des
 * objectClass (avec leurs attributs utilises) et interactionClass auxquels
 * un federe particulier s'abonne/publie.
 *
 * Comme {@link FomXmlParser}, ignore le namespace (matche par nom local)
 * pour rester robuste a une variante mineure du schema.
 *
 * Ce parseur ne resout PAS la hierarchie d'heritage de la FOM -- il se
 * contente de lire le fichier tel quel. La resolution (retrouver la classe
 * parente qui declare reellement un attribut herite, inclure integralement
 * les classes ancetres, etc.) est faite par
 * {@code rprfom.codegen.filter.FederateScopeFilter} a partir du FomModel
 * complet deja parse/fusionne.
 */
public final class FederateFomProviderParser {

    public FederateSelection parse(File xmlFile) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(xmlFile);
        doc.getDocumentElement().normalize();

        Element root = doc.getDocumentElement();
        FederateSelection selection = new FederateSelection();
        if (root.hasAttribute("FederateName")) {
            selection.federateName = root.getAttribute("FederateName");
        }

        for (Element objectEl : children(root, "FOMObject")) {
            String name = objectEl.getAttribute("name");
            if (name.isBlank()) continue;
            selection.ensureObject(name);
            for (Element attrEl : children(objectEl, "attribute")) {
                selection.addObjectAttribute(name, text(attrEl));
            }
        }

        for (Element interactionEl : children(root, "FOMInteraction")) {
            String name = interactionEl.getAttribute("name");
            if (!name.isBlank()) {
                selection.interactionNames.add(name);
            }
        }

        return selection;
    }

    // ====================================================================
    // Utilitaires DOM (recherche par nom local, independante du namespace)
    // ====================================================================

    private static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        if (parent == null) return result;
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && localName.equals(n.getLocalName())) {
                result.add((Element) n);
            }
        }
        return result;
    }

    private static String text(Element el) {
        return el == null ? null : el.getTextContent().trim();
    }
}
