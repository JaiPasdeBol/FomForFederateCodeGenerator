package rprfom.codegen.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Noeud de la hierarchie des objectClass. Dans le XML OMT SISO, cette
 * hierarchie est representee par des <objectClass> IMBRIQUES (chaque
 * sous-classe est un enfant XML de sa classe parente), pas par un
 * attribut "parent" a plat -- exactement comme notre hierarchie
 * BaseEntity -> PhysicalEntity -> Platform -> Aircraft ecrite a la main.
 *
 * Plusieurs fichiers FOM peuvent chacun redeclarer une partie de cet
 * arbre (ex. RPR-Physical_v2.0.xml redeclare le chemin
 * HLAobjectRoot.BaseEntity pour y accrocher PhysicalEntity) : la fusion
 * se fait par correspondance de NOM a chaque niveau, en creant les
 * noeuds manquants au besoin. C'est ce qui permet a un fichier
 * d'enrichissement personnalise d'ajouter de nouvelles sous-classes
 * sous des classes RPR-FOM existantes.
 */
public final class ObjectClassNode {
    public final String name;
    public final List<AttributeDef> ownAttributes = new ArrayList<>();
    public final Map<String, ObjectClassNode> children = new LinkedHashMap<>();

    public ObjectClassNode(String name) {
        this.name = name;
    }

    /** Cree l'enfant s'il n'existe pas encore, le retourne dans tous les cas. */
    public ObjectClassNode childOrCreate(String childName) {
        return children.computeIfAbsent(childName, ObjectClassNode::new);
    }

    public static final class AttributeDef {
        public final String name;
        public final String dataTypeName;

        public AttributeDef(String name, String dataTypeName) {
            this.name = name;
            this.dataTypeName = dataTypeName;
        }
    }
}
