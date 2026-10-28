package rprfom.api;

import java.util.Map;

/**
 * HLAInteractionClass.java -- fait partie de rprfom-runtime-api (jar partage).
 *
 * Pendant de {@link HLAObjectClass} pour les classes d'interaction generees par
 * rprfom-codegen. Une interaction est un message envoye une fois : ni ownership,
 * ni suivi temporel. Chaque classe generee a un constructeur public sans argument.
 */
public interface HLAInteractionClass {

    /** Nom FOM complet (ex. "HLAinteractionRoot.WeaponFire"), egal a HLA_INTERACTION_CLASS_NAME. */
    String hlaInteractionClassName();

    /** Parametres de CETTE instance par nom FOM, heritage compris (vue en lecture seule). */
    Map<String, IHLAParameterBase> parametersByName();
}
