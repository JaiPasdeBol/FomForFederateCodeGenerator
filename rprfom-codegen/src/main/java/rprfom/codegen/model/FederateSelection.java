package rprfom.codegen.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resultat du parsing d'un fichier "FederateFOMProvider" (conforme a
 * federatesFOMProvider.xsd) : la liste des objectClass et interactionClass
 * auxquels un federe particulier s'abonne/publie, et pour chaque objet, la
 * liste des attributs qu'il utilise reellement.
 *
 * Ce n'est PAS un sous-ensemble pret a generer : {@link #objectAttributes}
 * ne contient que ce que le fichier federe declare explicitement pour
 * chaque objet, sans egard a la hierarchie d'heritage de la FOM (un
 * attribut liste ici pour un objet peut en realite etre declare sur une
 * classe parente dans la FOM reelle). C'est le role de
 * {@code rprfom.codegen.filter.FederateScopeFilter} de resoudre cette
 * hierarchie et de reduire un FomModel en consequence.
 */
public final class FederateSelection {

    /** Attribut "FederateName" du fichier, informatif seulement (peut etre absent). */
    public String federateName;

    /**
     * Nom d'objectClass (tel qu'ecrit dans le fichier federe) -> noms
     * d'attributs demandes pour cet objet (ordre d'apparition preserve,
     * sans doublon). Une entree peut avoir une liste vide (l'objet est
     * demande mais aucun attribut precis n'est liste).
     */
    public final Map<String, List<String>> objectAttributes = new LinkedHashMap<>();

    /** Noms d'interactionClass demandes (ordre d'apparition preserve, sans doublon). */
    public final Set<String> interactionNames = new LinkedHashSet<>();

    /** Ajoute (ou complete si deja present) une entree FOMObject. */
    public void addObjectAttribute(String objectName, String attributeName) {
        List<String> attrs = objectAttributes.computeIfAbsent(objectName, k -> new ArrayList<>());
        if (attributeName != null && !attributeName.isBlank() && !attrs.contains(attributeName)) {
            attrs.add(attributeName);
        }
    }

    /** Enregistre un objet demande sans (encore) lui associer d'attribut. */
    public void ensureObject(String objectName) {
        objectAttributes.computeIfAbsent(objectName, k -> new ArrayList<>());
    }

    public boolean isEmpty() {
        return objectAttributes.isEmpty() && interactionNames.isEmpty();
    }
}
