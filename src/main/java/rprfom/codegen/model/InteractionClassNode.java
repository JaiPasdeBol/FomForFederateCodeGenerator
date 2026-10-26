package rprfom.codegen.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Noeud de la hierarchie des interactionClass (racine "HLAinteractionRoot").
 * Structure identique a ObjectClassNode (imbrication XML, fusion multi-fichiers
 * par nom a chaque niveau), mais avec des PARAMETRES plutot que des
 * ATTRIBUTS -- une interaction HLA est un message envoye une fois
 * (sendInteraction/receiveInteraction), pas un etat persistant possede
 * par un federe : pas de notion d'ownership ici, contrairement a
 * ObjectClassNode.
 */
public final class InteractionClassNode {
    public final String name;
    public final List<ParameterDef> ownParameters = new ArrayList<>();
    public final Map<String, InteractionClassNode> children = new LinkedHashMap<>();

    public InteractionClassNode(String name) {
        this.name = name;
    }

    public InteractionClassNode childOrCreate(String childName) {
        return children.computeIfAbsent(childName, InteractionClassNode::new);
    }

    public static final class ParameterDef {
        public final String name;
        public final String dataTypeName;

        public ParameterDef(String name, String dataTypeName) {
            this.name = name;
            this.dataTypeName = dataTypeName;
        }
    }
}
