package rprfom.api;

import java.time.Instant;

/**
 * IHLAAttributeBase.java -- fait partie de rprfom-runtime-api (jar partage).
 *
 * Interface non generique, exposant les operations d'un HLAAttribute&lt;T&gt;
 * qui ne dependent pas de T. Permet de manipuler des attributs de types
 * differents (EntityTypeStruct, MarkingStruct, Boolean, ...) de facon
 * generique, notamment pour les stocker dans une meme Map (indexee par
 * handle RTI).
 *
 * Miroir exact de IHLAAttribute.h (C++). En Java, l'effacement de type
 * (type erasure) empeche de toute facon HLAAttribute&lt;T&gt; de connaitre T
 * a l'execution sans aide -- cette interface reste donc necessaire pour
 * les memes raisons qu'en C++ (stockage heterogene dans une Map/liste).
 */
public interface IHLAAttributeBase {

    // ------------------------------------------------------------------
    // Ownership (independant de T -- cf. OwnershipState.java)
    // ------------------------------------------------------------------
    OwnershipState ownershipState();

    boolean isOwned();

    void requestAcquisition();

    void confirmAcquisition();

    void cancelAcquisition();

    void requestDivestiture();

    void confirmDivestiture();

    void cancelDivestiture();

    /**
     * Positionne directement l'etat d'ownership SANS passer par la
     * machine a etats (pas de verification de transition). Reserve a
     * l'initialisation (ex. seeder l'ownership depuis le tag
     * "InitialOwner" du FOM) ou aux tests -- ne pas utiliser en
     * fonctionnement normal, preferer les transitions ci-dessus.
     */
    void forceOwnershipState(OwnershipState state);

    // ------------------------------------------------------------------
    // Cote ecrivain
    // ------------------------------------------------------------------
    boolean needsPublication();

    void markPublished();

    /**
     * Serialise la valeur courante en octets prets a etre envoyes au RTI
     * (AttributeHandleValueMap). C'est la cle pour publier de facon
     * generique via une Map&lt;Long, IHLAAttributeBase&gt; : cette map ne
     * connait pas T, mais chaque HLAAttribute&lt;T&gt; sait encoder sa propre
     * valeur, donc l'appelant n'a jamais besoin de connaitre T.
     */
    byte[] encode();

    // ------------------------------------------------------------------
    // Valeur (independant de T)
    // ------------------------------------------------------------------

    /** true si l'attribut porte une valeur (set(), valeur initiale ou reflect deja recu). */
    boolean hasValue();

    // ------------------------------------------------------------------
    // Cote souscripteur
    // ------------------------------------------------------------------
    boolean hasBeenReflected();

    Instant lastUpdateTime();

    /**
     * Symetrique de encode() : decode des octets recus du RTI
     * (reflectAttributeValues) et met a jour la valeur + l'horodatage.
     * Comme pour encode(), l'appelant n'a jamais besoin de connaitre T :
     * c'est HLAAttribute&lt;T&gt; qui sait decoder sa propre valeur via
     * HLADecoder&lt;T&gt;.
     */
    void reflectRaw(byte[] bytes, Instant timestamp);

    /** Equivalent du parametre par defaut C++ (timestamp = Clock::now()). */
    default void reflectRaw(byte[] bytes) {
        reflectRaw(bytes, Instant.now());
    }
}
