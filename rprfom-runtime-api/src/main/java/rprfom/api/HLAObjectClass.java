package rprfom.api;

import java.util.Map;

/**
 * HLAObjectClass.java -- fait partie de rprfom-runtime-api (jar partage).
 *
 * Contrat commun a toutes les classes d'objet Java generees par rprfom-codegen
 * (une classe par objectClass du FOM). La classe racine generee (ex. BaseEntity)
 * l'implemente, ses sous-classes en heritent ; chaque classe redefinit
 * {@link #hlaObjectClassName()}.
 *
 * C'est ce contrat qu'utilise une couche RTI comme HLAbstract pour manipuler
 * n'importe quelle classe generee sans la connaitre a la compilation (chaque
 * federe genere ses classes dans son propre package) et sans reflexion.
 *
 * Exigence supplementaire, non exprimable par une interface : chaque classe
 * generee a un constructeur public sans argument.
 */
public interface HLAObjectClass {

    /**
     * Nom FOM complet de la classe (ex.
     * "HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft"), egal a la
     * constante HLA_OBJECT_CLASS_NAME de la classe generee.
     */
    String hlaObjectClassName();

    /**
     * Attributs de CETTE instance par nom FOM (ex. "Spatial"), heritage compris.
     * Vue en lecture seule : les objets {@link IHLAAttributeBase} sont les champs
     * publics de l'instance (ex. {@code aircraft.spatial}), pas des copies.
     */
    Map<String, IHLAAttributeBase> attributesByName();
}
