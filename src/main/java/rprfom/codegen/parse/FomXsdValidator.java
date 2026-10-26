package rprfom.codegen.parse;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Valide un fichier FOM XML contre le schema "Data Interchange Format" de la
 * norme IEEE 1516.2 qui correspond au namespace declare par le document lui
 * meme : IEEE1516-DIF-2025.xsd (namespace
 * http://standards.ieee.org/IEEE1516-2025) ou, pour compatibilite
 * ascendante, IEEE1516-DIF-2010.xsd (namespace
 * http://standards.ieee.org/IEEE1516-2010).
 *
 * Ce choix de tolerer les deux namespaces vient d'un cas reel : les fichiers
 * RPR-FOM officiels publies par SISO (ex. RPR_FOM_v2.0_1516-2010.xml) sont
 * ancres sur le namespace 2010 et ne seront probablement jamais republies --
 * les rejeter categoriquement des lors que le schema de reference passe a
 * 2025 casserait l'ingestion de tout FOM officiel non retouche. Le
 * generateur detecte donc le namespace de l'element racine du document (par
 * une lecture StAX minimale, avant toute validation) puis selectionne le
 * schema XSD correspondant. Un namespace absent ou different des deux
 * connus produit une erreur bloquante explicite plutot que le message XSD
 * generique et peu clair "Cannot find the declaration of element
 * 'objectModel'".
 *
 * DIF plutot que OMT : ce schema est choisi plutot que le plus strict
 * IEEE1516-OMT-2025.xsd / IEEE1516-OMT-2010.xsd (qui rendent obligatoires la
 * plupart des elements et ajoutent des contraintes d'integrite
 * referentielle) car il est explicitement concu, dixit son propre
 * commentaire d'en-tete, "for exchanging possibly incomplete OMT files
 * between tools" -- exactement le cas d'un module RPR-FOM pris isolement
 * (RPR-Base.xml seul ne definit ni <objects> racine complet, ni toutes les
 * <interactions>, etc.) ou d'un fichier d'enrichissement personnalise qui ne
 * redefinit qu'une partie du modele. Valider chaque fichier contre OMT.xsd
 * individuellement rejetterait a tort la plupart des fichiers FOM reels, qui
 * ne sont complets qu'une fois tous fusionnes.
 *
 * Assouplissement cible et documente : le champ <reference><identification>
 * de modelIdentification (metadonnee bibliographique -- la citation d'un
 * document de reference, ex. "SISO-REF-010") est type xs:anyURI dans les
 * deux revisions du schema, mais le fichier RPR-FOM 2.0 officiel lui-meme y
 * met du texte libre multi-lignes plutot qu'une URI (constate en pratique :
 * le ':' apres "Reference for" est interprete comme un schema d'URI
 * invalide). Le seul autre endroit du schema type en anyURI est l'attribut
 * href d'un glyphe (image inline dans une note), tout aussi hors du
 * perimetre de ce generateur. Ni l'un ni l'autre champ n'est jamais lu par
 * FomXmlParser (qui ne s'interesse qu'a <dataTypes>/<objects>/
 * <interactions>) -- ces erreurs precises sont donc degradees en simple
 * avertissement non bloquant plutot que de forcer --skip-xsd-validation
 * (qui desactiverait aussi toutes les verifications structurelles utiles)
 * pour un probleme de metadonnee bibliographique sans consequence.
 *
 * Differences structurelles entre les deux revisions du schema DIF (a part
 * le namespace) : la revision 2025 ajoute, dans <dataTypes>, un element
 * optionnel <referenceDataTypes> (types de donnees referencant des
 * instances d'objet) entre <simpleDataTypes> et <enumeratedDataTypes>,
 * exprime l'enumeration des encodages de tableau (HLAfixedArray /
 * HLAvariableArray) via xs:pattern plutot que xs:enumeration, et retire le
 * switch <conveyProducingFederate> (present dans <switches> en 2010,
 * disparu en 2025) -- changements sans consequence pour FomXmlParser, qui
 * ne lit ni <dataTypes>/<referenceDataTypes> ni <switches>, et accepte les
 * memes valeurs litterales d'encodage de tableau dans les deux cas.
 *
 * Second assouplissement, constate sur le RPR-FOM 2.0 officiel lui-meme
 * (RPR_FOM_v2.0_1516-2010.xml) : celui-ci porte, sur plusieurs
 * <objectClass>/<attribute>/<glyph>, des attributs XML additionnels sans
 * namespace (ex. notes="..." sur objectClass/attribute, ou
 * type=/height=/width= sur glyph) qui ne sont declares nulle part dans le
 * schema officiel -- vraisemblablement des metadonnees internes de l'outil
 * d'edition qui a produit ce fichier. Comme pour l'assouplissement anyURI
 * ci-dessus, ces attributs ne sont jamais lus par FomXmlParser (qui ignore
 * completement les attributs XML, ne s'interessant qu'aux elements enfants
 * connus). Plutot que de cataloguer un a un chaque nom d'attribut parasite
 * rencontre (fragile : rien ne garantit qu'un autre fichier n'en ajoute pas
 * d'autres), le joker <xs:anyAttribute> du groupe commonAttributes (utilise
 * par la quasi-totalite des types du schema) a ete elargi de
 * namespace="##other" (qui n'autorise que des attributs d'un AUTRE
 * namespace, donc pas ceux sans namespace comme ci-dessus) a namespace=
 * "##any" processContents="skip" (accepte tout attribut supplementaire,
 * de n'importe quel namespace y compris l'absence de namespace, sans le
 * valider) -- dans les deux schemas bundles (2025 et 2010).
 *
 * Troisieme assouplissement, constate sur le RPR-FOM v3.0/2025 officiel
 * (RPR-Base_v3.0.xml, module "SISO-STD-001.1-2025") : sa section racine
 * <time> y decrit les deux champs HLAinteger64Time du module via les noms
 * d'element HERITES <timeStamp>/<lookahead>, alors que le schema DIF (dans
 * les deux revisions, 2010 ET 2025 -- confirme aussi contre
 * IEEE1516-OMT-2025.xsd et IEEE1516-FDD-2025.xsd) exige <logicalTime>/
 * <logicalTimeInterval> pour le complexType timeType. Ni l'un ni l'autre
 * n'est jamais lu par FomXmlParser (qui ignore completement la section
 * <time>, hors de son perimetre -- seuls <dataTypes>/<objects>/
 * <interactions> l'interessent) : comme pour l'assouplissement anyURI,
 * cette non-conformite precise du fichier FOM lui-meme (pas du schema) est
 * donc degradee en avertissement non bloquant plutot que de forcer
 * --skip-xsd-validation pour tout le fichier.
 *
 * Reutilisable pour valider un autre document XML contre un unique schema
 * XSD embarque, sans detection de namespace (cf. constructeur a deux
 * arguments) : c'est le cas du fichier FederateFOMProvider, valide contre
 * federatesFOMProvider.xsd -- les assouplissements anyURI et <time> ci-dessus
 * sont alors desactives, car specifiques aux schemas IEEE1516-DIF-*.xsd et
 * sans objet ailleurs.
 */
public final class FomXsdValidator {

    /** Namespace cible du schema DIF le plus recent (norme IEEE 1516.2-2025). */
    private static final String NAMESPACE_2025 = "http://standards.ieee.org/IEEE1516-2025";

    /** Namespace cible du schema DIF de la norme IEEE 1516.2-2010, conserve pour compatibilite ascendante. */
    private static final String NAMESPACE_2010 = "http://standards.ieee.org/IEEE1516-2010";

    /**
     * Association namespace -> ressource XSD correspondante (sur le
     * classpath), dans l'ordre de preference d'affichage. Utilisee par le
     * constructeur par defaut pour choisir automatiquement le bon schema
     * selon ce que declare le document a valider.
     */
    private static final Map<String, String> SCHEMA_RESOURCE_BY_NAMESPACE = new LinkedHashMap<>();

    static {
        SCHEMA_RESOURCE_BY_NAMESPACE.put(NAMESPACE_2025, "schema/IEEE1516-DIF-2025.xsd");
        SCHEMA_RESOURCE_BY_NAMESPACE.put(NAMESPACE_2010, "schema/IEEE1516-DIF-2010.xsd");
    }

    /**
     * Fragment de message Xerces identifiant sans ambiguite une violation
     * de la facette xs:anyURI -- seuls <reference><identification> et
     * l'attribut href d'un glyphe sont types ainsi dans le schema, tous
     * deux des metadonnees jamais lues par ce generateur (cf. javadoc de
     * la classe).
     */
    private static final String ANYURI_MESSAGE_MARKER = "is not a valid value for 'anyURI'";

    /**
     * Second diagnostic Xerces compagnon, rapporte sur la meme ligne/
     * colonne pour la meme non-conformite : "identification" n'apparait
     * qu'une seule fois dans tout le schema (idReferenceType, cf.
     * ci-dessus), donc ce message ne peut concerner que ce meme champ.
     */
    private static final String IDENTIFICATION_MESSAGE_MARKER = "Element 'identification' must have no element";

    /**
     * Fragment de message Xerces identifiant la non-conformite <time>
     * connue du RPR-FOM v3.0/2025 officiel : <timeStamp>/<lookahead>
     * (noms d'element herites d'une revision anterieure de la norme) au
     * lieu de <logicalTime>/<logicalTimeInterval> (cf. javadoc de la
     * classe). "timeStamp" et "lookahead" ne designent aucun autre element
     * ni attribut dans les schemas IEEE1516-DIF-2010/2025.xsd bundles
     * (verifie -- "lookahead" n'y apparait que dans un commentaire
     * <xs:documentation>), donc ces deux fragments ne peuvent provenir que
     * de cette non-conformite precise, jamais d'une autre erreur de schema.
     */
    private static final String TIME_STAMP_MESSAGE_MARKER = "timeStamp}'";
    private static final String TIME_LOOKAHEAD_MESSAGE_MARKER = "lookahead}'";

    /** Mode multi-namespace (constructeur par defaut) : une Schema par namespace connu, choisie a la validation. */
    private final Map<String, Schema> schemasByNamespace;

    /** Mode schema unique (constructeur a deux arguments) : pas de detection de namespace. */
    private final Schema fixedSchema;

    private final boolean applyAnyUriWorkaround;

    public FomXsdValidator() {
        this.applyAnyUriWorkaround = true;
        this.fixedSchema = null;
        Map<String, Schema> schemas = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : SCHEMA_RESOURCE_BY_NAMESPACE.entrySet()) {
            schemas.put(entry.getKey(), loadSchema(entry.getValue()));
        }
        this.schemasByNamespace = schemas;
    }

    /**
     * @param schemaResourcePath    chemin de la ressource XSD (sur le classpath) a charger.
     * @param applyAnyUriWorkaround si true, degrade les deux diagnostics anyURI connus
     *                              (cf. javadoc de la classe) en avertissement plutot
     *                              qu'en erreur bloquante -- specifique aux schemas
     *                              IEEE1516-DIF-*.xsd, a laisser a false pour tout
     *                              autre schema.
     */
    public FomXsdValidator(String schemaResourcePath, boolean applyAnyUriWorkaround) {
        this.applyAnyUriWorkaround = applyAnyUriWorkaround;
        this.fixedSchema = loadSchema(schemaResourcePath);
        this.schemasByNamespace = null;
    }

    private static Schema loadSchema(String schemaResourcePath) {
        try {
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            try (InputStream in = FomXsdValidator.class.getClassLoader().getResourceAsStream(schemaResourcePath)) {
                if (in == null) {
                    throw new IllegalStateException("Ressource schema introuvable: " + schemaResourcePath);
                }
                return factory.newSchema(new StreamSource(in));
            }
        } catch (SAXException | IOException e) {
            throw new IllegalStateException("Impossible de charger le schema XSD " + schemaResourcePath, e);
        }
    }

    /**
     * Resultat d'une validation : erreurs bloquantes (empechent la
     * generation) et avertissements informatifs. {@code schemaUsed} decrit
     * le schema effectivement applique (utile en mode multi-namespace pour
     * indiquer a l'utilisateur lequel a ete detecte), ou {@code null} si la
     * detection de namespace a echoue avant meme de choisir un schema.
     */
    public record Result(List<String> blockingErrors, List<String> warnings, String schemaUsed) {
        public boolean isValid() {
            return blockingErrors.isEmpty();
        }
    }

    /**
     * Valide le fichier. Ne leve jamais d'exception pour une erreur de
     * contenu XML : les problemes sont collectes dans le resultat, pour
     * un rapport clair et complet plutot qu'un arret a la premiere erreur.
     * Les violations connues et sans consequence (cf. javadoc de la
     * classe) sont classees en avertissement plutot qu'en erreur bloquante.
     *
     * En mode multi-namespace (constructeur par defaut), le namespace de
     * l'element racine du document est d'abord detecte (lecture StAX
     * minimale, sans validation) pour choisir automatiquement le schema
     * IEEE1516-DIF-2025.xsd ou IEEE1516-DIF-2010.xsd correspondant. Un
     * namespace absent ou inconnu des deux produit une erreur bloquante
     * explicite plutot que le message XSD generique et peu clair que
     * produirait une validation forcee contre le mauvais schema.
     */
    public Result validate(File fomFile) {
        List<String> blocking = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Schema schemaToUse = fixedSchema;
        String schemaUsedDescription = null;
        if (schemasByNamespace != null) {
            String namespace;
            try {
                namespace = detectRootNamespace(fomFile);
            } catch (XMLStreamException | IOException e) {
                blocking.add("  [erreur] lecture du fichier impossible : " + e.getMessage());
                return new Result(blocking, warnings, null);
            }
            String resource = namespace == null ? null : SCHEMA_RESOURCE_BY_NAMESPACE.get(namespace);
            if (resource == null) {
                blocking.add("  [erreur] namespace de l'element racine "
                        + (namespace == null || namespace.isBlank() ? "absent" : "inconnu ('" + namespace + "')")
                        + " -- ce generateur sait valider les documents declarant "
                        + String.join(" ou ", SCHEMA_RESOURCE_BY_NAMESPACE.keySet()) + ".");
                return new Result(blocking, warnings, null);
            }
            schemaToUse = schemasByNamespace.get(namespace);
            schemaUsedDescription = resource.substring(resource.lastIndexOf('/') + 1);
        }

        final Schema schema = schemaToUse;
        try {
            Validator validator = schema.newValidator();
            validator.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException e) {
                    warnings.add(format("avertissement", e));
                }

                @Override
                public void error(SAXParseException e) {
                    classify(e);
                }

                @Override
                public void fatalError(SAXParseException e) {
                    classify(e);
                }

                private void classify(SAXParseException e) {
                    String msg = e.getMessage();
                    if (applyAnyUriWorkaround && msg != null
                            && (msg.contains(ANYURI_MESSAGE_MARKER) || msg.contains(IDENTIFICATION_MESSAGE_MARKER))) {
                        warnings.add(format("avertissement (metadonnee bibliographique anyURI, ignoree)", e));
                    } else if (applyAnyUriWorkaround && msg != null
                            && (msg.contains(TIME_STAMP_MESSAGE_MARKER) || msg.contains(TIME_LOOKAHEAD_MESSAGE_MARKER))) {
                        warnings.add(format("avertissement (section <time> en noms d'element herites "
                                + "timeStamp/lookahead, non lue par le generateur, ignoree)", e));
                    } else {
                        blocking.add(format("erreur", e));
                    }
                }

                private String format(String severity, SAXParseException e) {
                    return "  [" + severity + "] ligne " + e.getLineNumber() + ", colonne "
                            + e.getColumnNumber() + " : " + e.getMessage();
                }
            });
            validator.validate(new StreamSource(fomFile));
        } catch (SAXException e) {
            // Erreur XML non structurelle (mal formee, encodage, etc.) non
            // deja remontee via l'ErrorHandler (rare, mais possible avant
            // meme le debut de la validation de schema).
            blocking.add("  [erreur] " + e.getMessage());
        } catch (IOException e) {
            blocking.add("  [erreur] lecture du fichier impossible : " + e.getMessage());
        }
        return new Result(blocking, warnings, schemaUsedDescription);
    }

    /**
     * Lit uniquement le premier element du document (lecture StAX en
     * streaming, arretee des la premiere balise ouvrante trouvee) pour en
     * extraire le namespace declare -- sans faire de validation ni de
     * parsing complet. Retourne {@code null} si l'element racine n'est
     * qualifie par aucun namespace.
     */
    private static String detectRootNamespace(File fomFile) throws XMLStreamException, IOException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try (InputStream in = new FileInputStream(fomFile)) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                        return reader.getNamespaceURI();
                    }
                }
                return null;
            } finally {
                reader.close();
            }
        }
    }
}
