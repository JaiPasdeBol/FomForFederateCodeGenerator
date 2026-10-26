package rprfom.codegen.filter;

import rprfom.codegen.model.DataType;
import rprfom.codegen.model.FederateSelection;
import rprfom.codegen.model.FomModel;
import rprfom.codegen.model.InteractionClassNode;
import rprfom.codegen.model.ObjectClassNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reduit un {@link FomModel} deja parse/fusionne au sous-ensemble
 * necessaire a un federe particulier, decrit par un {@link FederateSelection}
 * (issu du fichier FederateFOMProvider), en respectant les regles
 * d'heritage de la FOM :
 *
 * <ul>
 *   <li>Si le federe s'abonne a un objet (ou une interaction) enfant, la
 *       CHAINE COMPLETE de ses classes ancetres est generee -- ce sont des
 *       classes structurelles de la FOM (l'heritage C++/Java l'exige), pas
 *       un choix du federe -- mais AUCUN attribut supplementaire n'est
 *       ajoute de son propre chef sur ces classes ancetres : seuls les
 *       attributs effectivement listes dans le fichier federe (et resolus
 *       vers leur classe declarante reelle, cf. point suivant) y
 *       apparaissent. Une classe ancetre non listee et vers laquelle aucun
 *       attribut ne se resout est donc generee comme une coquille vide
 *       (aucun attribut propre), uniquement pour que la hierarchie
 *       d'heritage reste valide.</li>
 *   <li>Un attribut liste dans le fichier federe est place sur la classe
 *       qui le declare REELLEMENT dans la FOM (sa classe parente, si c'est
 *       un attribut herite), pas necessairement celle sous laquelle le
 *       fichier federe l'a liste -- jamais duplique sous la classe qui l'a
 *       demande.</li>
 *   <li>Les interactionClass n'ont pas de notion d'attribut dans
 *       federatesFOMProvider.xsd (seulement un nom) : une interaction
 *       demandee est donc toujours generee integralement (tous ses
 *       parametres), comme ses ancetres.</li>
 *   <li>Une fois les objectClass/interactionClass reduits, {@code model.dataTypes}
 *       est lui-meme reduit aux seuls types de donnees encore atteignables
 *       depuis un attribut ou un parametre effectivement conserve (fermeture
 *       transitive : un struct dont un champ est conserve garde ce champ,
 *       un tableau conserve garde son type d'element, etc.) -- cf.
 *       {@link #pruneUnusedDataTypes}. HLATypes.hpp/HLATypes.java (et les
 *       fichiers d'encodage qui en dependent, generes a partir du meme
 *       {@code mapper.topologicalOrder()}) ne contiennent donc plus, pour
 *       une generation federee, que les types reellement utilises par les
 *       classes generees -- jamais l'integralite des types de la FOM
 *       source.</li>
 * </ul>
 *
 * Modifie le FomModel EN PLACE (elague ObjectClassNode.children /
 * InteractionClassNode.children, filtre ObjectClassNode.ownAttributes,
 * reduit model.dataTypes) -- le modele passe en entree n'est donc plus
 * utilisable pour une generation "complete" apres cet appel.
 */
public final class FederateScopeFilter {

    /**
     * Avertissements non bloquants (objet/interaction/attribut introuvable
     * dans la FOM) rencontres pendant le filtrage, et bilan du filtrage des
     * types de donnees ({@code dataTypesKept}/{@code dataTypesRemoved}, cf.
     * {@link #pruneUnusedDataTypes}).
     */
    public record Result(List<String> warnings, int dataTypesKept, int dataTypesRemoved) {
    }

    public Result apply(FomModel model, FederateSelection selection) {
        List<String> warnings = new ArrayList<>();
        filterObjects(model, selection, warnings);
        filterInteractions(model, selection, warnings);
        int before = model.dataTypes.size();
        pruneUnusedDataTypes(model);
        int after = model.dataTypes.size();
        return new Result(warnings, after, before - after);
    }

    // ====================================================================
    // Types de donnees : ne garder que ceux atteignables depuis un
    // attribut/parametre effectivement conserve.
    // ====================================================================

    /**
     * Reduit {@code model.dataTypes} a la fermeture transitive des types
     * effectivement utilises par les objectClass/interactionClass restants
     * (apres {@link #filterObjects}/{@link #filterInteractions}), en
     * suivant les memes relations de dependance que
     * {@link DataType#dependsOn()} (champ de struct, element de tableau,
     * discriminant/alternatives d'un variant record).
     *
     * Cas particulier : {@link DataType.Enumerated#dependsOn()} ne liste
     * pas sa representation sous-jacente (elle designe le plus souvent
     * directement une representation de base HLA, jamais une entree du
     * modele -- cf. TypeMapper.resolveEnumUnderlying), mais peut
     * exceptionnellement pointer vers un {@code simpleData}. On l'ajoute
     * donc explicitement comme dependance ici, pour ne jamais retirer un
     * alias encore necessaire a la resolution de la representation d'un
     * enum conserve.
     */
    private void pruneUnusedDataTypes(FomModel model) {
        Set<String> used = new LinkedHashSet<>();
        collectUsedDataTypes(model.root, used);
        collectUsedDataTypes(model.interactionRoot, used);

        Set<String> reachable = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(used);
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (!reachable.add(name)) continue;
            DataType dt = model.dataType(name);
            if (dt == null) continue; // representation de base HLA directe (HLAoctet, ...), pas une entree du modele.
            for (String dep : dependenciesForPruning(dt)) {
                if (!reachable.contains(dep)) queue.add(dep);
            }
        }

        model.dataTypes.keySet().removeIf(name -> !reachable.contains(name));
    }

    private List<String> dependenciesForPruning(DataType dt) {
        if (dt instanceof DataType.Enumerated e) {
            return List.of(e.representation);
        }
        return dt.dependsOn();
    }

    private void collectUsedDataTypes(ObjectClassNode node, Set<String> used) {
        for (ObjectClassNode.AttributeDef a : node.ownAttributes) used.add(a.dataTypeName);
        for (ObjectClassNode child : node.children.values()) collectUsedDataTypes(child, used);
    }

    private void collectUsedDataTypes(InteractionClassNode node, Set<String> used) {
        for (InteractionClassNode.ParameterDef p : node.ownParameters) used.add(p.dataTypeName);
        for (InteractionClassNode child : node.children.values()) collectUsedDataTypes(child, used);
    }

    // ====================================================================
    // objectClass
    // ====================================================================

    private void filterObjects(FomModel model, FederateSelection selection, List<String> warnings) {
        if (selection.objectAttributes.isEmpty()) {
            // Aucun objet demande par ce federe : il ne publie/souscrit a aucun objectClass.
            model.root.children.clear();
            return;
        }

        Map<String, ObjectClassNode> byName = new LinkedHashMap<>();
        Map<String, ObjectClassNode> byFullPath = new LinkedHashMap<>();
        Map<ObjectClassNode, ObjectClassNode> parentOf = new IdentityHashMap<>();
        indexObjects(model.root, null, model.root.name, byName, byFullPath, parentOf);

        Set<ObjectClassNode> kept = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<ObjectClassNode, Set<String>> selectedAttrsByOwner = new IdentityHashMap<>();

        for (Map.Entry<String, List<String>> entry : selection.objectAttributes.entrySet()) {
            String objectName = entry.getKey();
            // Le fichier federe (produit par l'outil FOMProvider) peut nommer
            // un objet soit par son nom court ("Aircraft"), soit par son
            // chemin complet depuis la racine ("HLAobjectRoot.BaseEntity...
            // Aircraft", tel qu'affiche dans HLA_OBJECT_CLASS_NAME) -- les
            // deux formats sont acceptes de maniere transparente.
            ObjectClassNode node = byName.get(objectName);
            if (node == null) node = byFullPath.get(objectName);
            if (node == null) {
                warnings.add("Objet inconnu dans la FOM, ignore : '" + objectName + "'");
                continue;
            }
            keepWithAncestors(node, parentOf, kept);

            for (String attrName : entry.getValue()) {
                ObjectClassNode owner = findAttributeOwner(node, parentOf, attrName);
                if (owner == null) {
                    warnings.add("Attribut '" + attrName + "' introuvable dans la hierarchie de l'objet '"
                            + objectName + "' (ni sur cet objet, ni sur ses ancetres jusqu'a HLAobjectRoot), ignore.");
                    continue;
                }
                selectedAttrsByOwner.computeIfAbsent(owner, k -> new LinkedHashSet<>()).add(attrName);
                keepWithAncestors(owner, parentOf, kept);
            }
        }

        pruneObjectChildren(model.root, kept);

        // Chaque classe conservee (explicitement demandee dans le fichier
        // federe, OU simple ancetre structurel d'un objet demande) ne garde
        // QUE les attributs effectivement listes par le federe et resolus
        // vers elle -- jamais la totalite de ses attributs propres. Une
        // classe ancetre vers laquelle rien ne se resout devient une
        // coquille vide (aucun attribut), presente uniquement pour que la
        // hierarchie d'heritage reste valide.
        for (ObjectClassNode node : kept) {
            Set<String> keepAttrs = selectedAttrsByOwner.getOrDefault(node, Set.of());
            node.ownAttributes.removeIf(a -> !keepAttrs.contains(a.name));
        }
    }

    private void indexObjects(ObjectClassNode node, ObjectClassNode parent, String fullPath,
                               Map<String, ObjectClassNode> byName, Map<String, ObjectClassNode> byFullPath,
                               Map<ObjectClassNode, ObjectClassNode> parentOf) {
        // Premiere occurrence gagne : les noms d'objectClass sont normalement
        // uniques dans une FOM fusionnee (une classe ne peut avoir qu'un
        // seul parent).
        byName.putIfAbsent(node.name, node);
        byFullPath.putIfAbsent(fullPath, node);
        if (parent != null) parentOf.put(node, parent);
        for (ObjectClassNode child : node.children.values()) {
            indexObjects(child, node, fullPath + "." + child.name, byName, byFullPath, parentOf);
        }
    }

    private void keepWithAncestors(ObjectClassNode node, Map<ObjectClassNode, ObjectClassNode> parentOf, Set<ObjectClassNode> kept) {
        ObjectClassNode current = node;
        while (current != null && kept.add(current)) {
            current = parentOf.get(current);
        }
    }

    /** Cherche, en partant de {@code start} puis en remontant vers la racine, le noeud qui declare reellement l'attribut nomme {@code attrName}. */
    private ObjectClassNode findAttributeOwner(ObjectClassNode start, Map<ObjectClassNode, ObjectClassNode> parentOf, String attrName) {
        for (ObjectClassNode current = start; current != null; current = parentOf.get(current)) {
            for (ObjectClassNode.AttributeDef a : current.ownAttributes) {
                if (a.name.equals(attrName)) return current;
            }
        }
        return null;
    }

    private void pruneObjectChildren(ObjectClassNode node, Set<ObjectClassNode> kept) {
        node.children.entrySet().removeIf(e -> !kept.contains(e.getValue()));
        for (ObjectClassNode child : node.children.values()) {
            pruneObjectChildren(child, kept);
        }
    }

    // ====================================================================
    // interactionClass (miroir, sans filtrage de parametres -- cf. javadoc de la classe)
    // ====================================================================

    private void filterInteractions(FomModel model, FederateSelection selection, List<String> warnings) {
        if (selection.interactionNames.isEmpty()) {
            model.interactionRoot.children.clear();
            return;
        }

        Map<String, InteractionClassNode> byName = new LinkedHashMap<>();
        Map<String, InteractionClassNode> byFullPath = new LinkedHashMap<>();
        Map<InteractionClassNode, InteractionClassNode> parentOf = new IdentityHashMap<>();
        indexInteractions(model.interactionRoot, null, model.interactionRoot.name, byName, byFullPath, parentOf);

        Set<InteractionClassNode> kept = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String name : selection.interactionNames) {
            // Meme tolerance nom court / chemin complet que pour les objets
            // (cf. filterObjects).
            InteractionClassNode node = byName.get(name);
            if (node == null) node = byFullPath.get(name);
            if (node == null) {
                warnings.add("Interaction inconnue dans la FOM, ignoree : '" + name + "'");
                continue;
            }
            keepWithAncestors(node, parentOf, kept);
        }

        pruneInteractionChildren(model.interactionRoot, kept);
    }

    private void indexInteractions(InteractionClassNode node, InteractionClassNode parent, String fullPath,
                                    Map<String, InteractionClassNode> byName, Map<String, InteractionClassNode> byFullPath,
                                    Map<InteractionClassNode, InteractionClassNode> parentOf) {
        byName.putIfAbsent(node.name, node);
        byFullPath.putIfAbsent(fullPath, node);
        if (parent != null) parentOf.put(node, parent);
        for (InteractionClassNode child : node.children.values()) {
            indexInteractions(child, node, fullPath + "." + child.name, byName, byFullPath, parentOf);
        }
    }

    private void keepWithAncestors(InteractionClassNode node, Map<InteractionClassNode, InteractionClassNode> parentOf, Set<InteractionClassNode> kept) {
        InteractionClassNode current = node;
        while (current != null && kept.add(current)) {
            current = parentOf.get(current);
        }
    }

    private void pruneInteractionChildren(InteractionClassNode node, Set<InteractionClassNode> kept) {
        node.children.entrySet().removeIf(e -> !kept.contains(e.getValue()));
        for (InteractionClassNode child : node.children.values()) {
            pruneInteractionChildren(child, kept);
        }
    }
}
