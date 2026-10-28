package rprfom.api;

/**
 * IHLAParameterBase.java -- fait partie de rprfom-runtime-api (jar partage).
 *
 * Miroir de IHLAAttributeBase.java, sans ownership ni suivi temporel --
 * une interaction HLA est un message envoye une fois
 * (sendInteraction/receiveInteraction), pas un etat persistant possede
 * par un federe.
 */
public interface IHLAParameterBase {

    /** true si set() (ou decodeFrom()) a deja ete appele sur ce parametre. */
    boolean hasValue();

    /** Serialise la valeur courante en octets prets a etre envoyes au RTI. */
    byte[] encode();

    /** Decode des octets recus du RTI (receiveInteraction) et met a jour la valeur. */
    void decodeFrom(byte[] bytes);
}
