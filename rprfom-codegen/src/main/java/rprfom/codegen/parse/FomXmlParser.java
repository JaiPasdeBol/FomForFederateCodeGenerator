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

import rprfom.codegen.model.DataType;
import rprfom.codegen.model.FomModel;
import rprfom.codegen.model.InteractionClassNode;
import rprfom.codegen.model.ObjectClassNode;

/**
 * Parseur du format OMT XML SISO-STD-004.1 (celui de la RPR-FOM et de
 * ses extensions). Ignore le namespace (les fichiers RPR-FOM utilisent
 * un namespace par defaut, on matche par nom local pour rester robuste
 * a des variantes/versions de schema legerement differentes).
 *
 * Peut etre appele plusieurs fois sur le meme FomModel pour fusionner
 * plusieurs fichiers (RPR-Base, RPR-Physical, un fichier
 * d'enrichissement personnalise, ...).
 */
public final class FomXmlParser {

    public void parseInto(FomModel model, File xmlFile) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // Pas de resolution DTD/schema externe (les fichiers FOM
        // referencent souvent un schema XSD via une URL non
        // accessible hors-ligne) : on desactive la validation externe.
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(xmlFile);
        doc.getDocumentElement().normalize();

        Element root = doc.getDocumentElement();
        Element dataTypesEl = child(root, "dataTypes");
        if (dataTypesEl != null) {
            parseDataTypes(model, dataTypesEl);
        }
        Element objectsEl = child(root, "objects");
        if (objectsEl != null) {
            // <objects> contient directement un (ou plusieurs) <objectClass>
            // racine, normalement "HLAobjectRoot".
            for (Element objectClassEl : children(objectsEl, "objectClass")) {
                mergeObjectClass(model.root, objectClassEl);
            }
        }
        Element interactionsEl = child(root, "interactions");
        if (interactionsEl != null) {
            for (Element interactionClassEl : children(interactionsEl, "interactionClass")) {
                mergeInteractionClass(model.interactionRoot, interactionClassEl);
            }
        }
    }

    // ====================================================================
    // dataTypes
    // ====================================================================

    private void parseDataTypes(FomModel model, Element dataTypesEl) {
        Element simpleEl = child(dataTypesEl, "simpleDataTypes");
        if (simpleEl != null) {
            for (Element e : children(simpleEl, "simpleData")) {
                String name = text(child(e, "name"));
                String repr = text(child(e, "representation"));
                model.addDataType(new DataType.Simple(name, repr));
            }
        }

        Element enumEl = child(dataTypesEl, "enumeratedDataTypes");
        if (enumEl != null) {
            for (Element e : children(enumEl, "enumeratedData")) {
                String name = text(child(e, "name"));
                String repr = text(child(e, "representation"));
                DataType.Enumerated enumerated = new DataType.Enumerated(name, repr);
                for (Element enumerator : children(e, "enumerator")) {
                    String enName = text(child(enumerator, "name"));
                    String enValue = text(child(enumerator, "value"));
                    enumerated.enumerators.add(new DataType.Enumerated.Enumerator(enName, parseLong(enValue)));
                }
                model.addDataType(enumerated);
            }
        }

        Element fixedEl = child(dataTypesEl, "fixedRecordDataTypes");
        if (fixedEl != null) {
            for (Element e : children(fixedEl, "fixedRecordData")) {
                String name = text(child(e, "name"));
                DataType.FixedRecord rec = new DataType.FixedRecord(name);
                for (Element f : children(e, "field")) {
                    String fName = text(child(f, "name"));
                    String fType = text(child(f, "dataType"));
                    rec.fields.add(new DataType.FixedRecord.Field(fName, fType));
                }
                model.addDataType(rec);
            }
        }

        Element arrayEl = child(dataTypesEl, "arrayDataTypes");
        if (arrayEl != null) {
            for (Element e : children(arrayEl, "arrayData")) {
                String name = text(child(e, "name"));
                String elementType = text(child(e, "dataType"));
                String cardinality = text(child(e, "cardinality"));
                String encodingText = text(child(e, "encoding"));
                int fixed = -1;
                if (cardinality != null && !cardinality.equalsIgnoreCase("Dynamic")) {
                    try {
                        fixed = Integer.parseInt(cardinality.trim());
                    } catch (NumberFormatException ignored) {
                        fixed = -1; // cardinalite en intervalle (ex. "[1..2147483647]") -> traitee comme Dynamic
                    }
                }
                DataType.ArrayEncoding encoding = parseArrayEncoding(encodingText, fixed);
                model.addDataType(new DataType.Array(name, elementType, fixed, encoding));
            }
        }

        Element variantEl = child(dataTypesEl, "variantRecordDataTypes");
        if (variantEl != null) {
            for (Element e : children(variantEl, "variantRecordData")) {
                String name = text(child(e, "name"));
                String discName = text(child(e, "discriminant"));
                String discType = text(child(e, "dataType"));
                DataType.VariantRecord variant = new DataType.VariantRecord(name, discName, discType);
                for (Element alt : children(e, "alternative")) {
                    String enumerator = text(child(alt, "enumerator"));
                    String fieldName = text(child(alt, "name"));
                    String fieldType = text(child(alt, "dataType"));
                    variant.alternatives.add(new DataType.VariantRecord.Alternative(enumerator, fieldName, fieldType));
                }
                model.addDataType(variant);
            }
        }
    }

    /**
     * Traduit le texte de <encoding> en ArrayEncoding. Absent -> deduit
     * de la cardinalite (retro-compatible avec les fichiers FOM de test
     * ecrits avant l'ajout de cette gestion, qui n'ont jamais precise
     * <encoding> : Dynamic devient HLAvariableArray, une cardinalite
     * fixe devient HLAfixedArray -- exactement le comportement d'avant).
     */
    private static DataType.ArrayEncoding parseArrayEncoding(String encodingText, int fixedCardinality) {
        if (encodingText == null || encodingText.isBlank()) {
            return fixedCardinality < 0 ? DataType.ArrayEncoding.HLA_VARIABLE_ARRAY : DataType.ArrayEncoding.HLA_FIXED_ARRAY;
        }
        return switch (encodingText.trim()) {
            case "HLAvariableArray" -> DataType.ArrayEncoding.HLA_VARIABLE_ARRAY;
            case "RPRlengthlessArray" -> DataType.ArrayEncoding.RPR_LENGTHLESS_ARRAY;
            case "HLAfixedArray" -> DataType.ArrayEncoding.HLA_FIXED_ARRAY;
            case "RPRpaddingTo32Array" -> DataType.ArrayEncoding.RPR_PADDING_32;
            case "RPRpaddingTo64Array" -> DataType.ArrayEncoding.RPR_PADDING_64;
            case "RPRnullTerminatedArray" -> DataType.ArrayEncoding.RPR_NULL_TERMINATED_ARRAY;
            default -> fixedCardinality < 0 ? DataType.ArrayEncoding.HLA_VARIABLE_ARRAY : DataType.ArrayEncoding.HLA_FIXED_ARRAY;
        };
    }

    // ====================================================================
    // objectClass (hierarchie imbriquee)
    // ====================================================================

    private void mergeObjectClass(ObjectClassNode parent, Element objectClassEl) {
        String name = text(child(objectClassEl, "name"));
        ObjectClassNode node = parent.name.equals(name) ? parent : parent.childOrCreate(name);
        // Cas HLAobjectRoot : le noeud racine existe deja avec ce nom,
        // on fusionne directement dedans plutot que de creer un enfant
        // "HLAobjectRoot" sous "HLAobjectRoot".

        for (Element attrEl : children(objectClassEl, "attribute")) {
            String attrName = text(child(attrEl, "name"));
            String attrType = text(child(attrEl, "dataType"));
            // Si l'attribut est deja connu a ce niveau (redeclare par un
            // autre fichier), on le remplace plutot que de le dupliquer.
            node.ownAttributes.removeIf(a -> a.name.equals(attrName));
            node.ownAttributes.add(new ObjectClassNode.AttributeDef(attrName, attrType));
        }

        for (Element childEl : children(objectClassEl, "objectClass")) {
            mergeObjectClass(node, childEl);
        }
    }

    // ====================================================================
    // interactionClass (hierarchie imbriquee, miroir de objectClass mais
    // avec des <parameter> plutot que des <attribute> -- pas d'ownership).
    // ====================================================================

    private void mergeInteractionClass(InteractionClassNode parent, Element interactionClassEl) {
        String name = text(child(interactionClassEl, "name"));
        InteractionClassNode node = parent.name.equals(name) ? parent : parent.childOrCreate(name);

        for (Element paramEl : children(interactionClassEl, "parameter")) {
            String paramName = text(child(paramEl, "name"));
            String paramType = text(child(paramEl, "dataType"));
            node.ownParameters.removeIf(p -> p.name.equals(paramName));
            node.ownParameters.add(new InteractionClassNode.ParameterDef(paramName, paramType));
        }

        for (Element childEl : children(interactionClassEl, "interactionClass")) {
            mergeInteractionClass(node, childEl);
        }
    }

    // ====================================================================
    // Utilitaires DOM (recherche par nom local, independante du namespace)
    // ====================================================================

    private static Element child(Element parent, String localName) {
        if (parent == null) return null;
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && localName.equals(n.getLocalName())) {
                return (Element) n;
            }
        }
        return null;
    }

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

    private static long parseLong(String s) {
        if (s == null) return 0L;
        s = s.trim();
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return Long.parseLong(s.substring(2), 16);
            }
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
