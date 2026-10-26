package rprfom.codegen.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Modele FOM complet, resultat de la fusion d'un ou plusieurs fichiers
 * XML OMT. Contient la table des types de donnees (par nom) et la
 * racine de l'arbre des objectClass (toujours "HLAobjectRoot").
 */
public final class FomModel {
    public final Map<String, DataType> dataTypes = new LinkedHashMap<>();
    public final ObjectClassNode root = new ObjectClassNode("HLAobjectRoot");
    public final InteractionClassNode interactionRoot = new InteractionClassNode("HLAinteractionRoot");

    public void addDataType(DataType type) {
        // Un fichier peut re-declarer un type deja connu (rare, mais on
        // privilegie la derniere definition rencontree comme le ferait
        // un merge de FOM reel).
        dataTypes.put(type.name, type);
    }

    public DataType dataType(String name) {
        return dataTypes.get(name);
    }
}
