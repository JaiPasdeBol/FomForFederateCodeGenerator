package rprfom;

import java.time.Instant;
import java.util.List;

// Interfaces et etat d'ownership PARTAGES (jar rprfom-runtime-api) : communs a
// tous les federes, quel que soit le package de generation.
import rprfom.api.IHLAAttributeBase;
import rprfom.api.OwnershipState;

/**
 * HLAAttribute.java
 *
 * Classe generique representant un attribut d'objet HLA/RPR-FOM.
 * Miroir exact de HLAAttribute.hpp : memes noms de methodes
 * (set/markPublished/needsPublication/onReflect/lastUpdateTime/
 * hasBeenReflected/get/encode/reflectRaw).
 *
 * DIFFERENCE INCONTOURNABLE avec le C++ : l'effacement de type Java
 * (type erasure) signifie que HLAAttribute&lt;T&gt; ne connait pas T a
 * l'execution. Pour que encode()/reflectRaw() puissent quand meme
 * dispatcher vers le bon HLAEncoder/HLADecoder (cf. HLAEncoder.java,
 * HLADecoder.java), l'attribut doit recevoir un jeton Class&lt;T&gt; a la
 * construction -- c'est l'equivalent Java du parametre de template T
 * que le compilateur C++ deduit tout seul.
 *
 * Pour les attributs de type tableau (List&lt;E&gt;), utiliser la fabrique
 * statique ofList(Class&lt;E&gt;) plutot que le constructeur normal, car
 * List&lt;ArticulatedParameterStruct&gt; et List&lt;PropulsionSystemDataStruct&gt;
 * partagent la meme Class a l'execution (List.class) : c'est le type
 * d'ELEMENT qu'il faut retenir pour pouvoir encoder/decoder, exactement
 * comme le T de HLAEncoder&lt;std::vector&lt;T&gt;&gt; en C++.
 */
public final class HLAAttribute<T> implements IHLAAttributeBase {

    private T value;
    private boolean needsPublication = false;

    private Instant lastUpdateTime = Instant.EPOCH;
    private boolean hasBeenReflectedAtLeastOnce = false;

    // Un seul des deux est non-null selon le mode de construction.
    private final Class<T> type;         // attribut scalaire/struct
    private final Class<?> elementType;  // attribut de type List<E>

    private OwnershipState ownershipState = OwnershipState.UNOWNED;

    public HLAAttribute(Class<T> type) {
        this.type = type;
        this.elementType = null;
    }

    public HLAAttribute(Class<T> type, T initialValue) {
        this(type);
        this.value = initialValue;
    }

    private HLAAttribute(ListMarker marker, Class<?> elementType) {
        this.type = null;
        this.elementType = elementType;
    }

    /** Type marqueur prive, uniquement pour distinguer ce constructeur de l'autre a l'effacement. */
    private static final class ListMarker {
    }

    /**
     * Fabrique pour un attribut de type tableau (HLAvariableArray),
     * equivalent de HLAAttribute&lt;std::vector&lt;E&gt;&gt; en C++.
     */
    public static <E> HLAAttribute<List<E>> ofList(Class<E> elementType) {
        return new HLAAttribute<>(new ListMarker(), elementType);
    }

    // ------------------------------------------------------------------
    // Ownership (independant de T -- cf. OwnershipState.java)
    // ------------------------------------------------------------------

    @Override
    public OwnershipState ownershipState() {
        return ownershipState;
    }

    @Override
    public boolean isOwned() {
        return ownershipState == OwnershipState.OWNED;
    }

    @Override
    public void requestAcquisition() {
        if (ownershipState != OwnershipState.UNOWNED) {
            throw new IllegalStateException(
                    "requestAcquisition() invalide depuis l'etat " + ownershipState + " (attendu UNOWNED)");
        }
        ownershipState = OwnershipState.ACQUIRING;
    }

    @Override
    public void confirmAcquisition() {
        if (ownershipState != OwnershipState.ACQUIRING) {
            throw new IllegalStateException(
                    "confirmAcquisition() invalide depuis l'etat " + ownershipState + " (attendu ACQUIRING)");
        }
        ownershipState = OwnershipState.OWNED;
    }

    @Override
    public void cancelAcquisition() {
        if (ownershipState != OwnershipState.ACQUIRING) {
            throw new IllegalStateException(
                    "cancelAcquisition() invalide depuis l'etat " + ownershipState + " (attendu ACQUIRING)");
        }
        ownershipState = OwnershipState.UNOWNED;
    }

    @Override
    public void requestDivestiture() {
        if (ownershipState != OwnershipState.OWNED) {
            throw new IllegalStateException(
                    "requestDivestiture() invalide depuis l'etat " + ownershipState + " (attendu OWNED)");
        }
        ownershipState = OwnershipState.DIVESTING;
    }

    @Override
    public void confirmDivestiture() {
        if (ownershipState != OwnershipState.DIVESTING) {
            throw new IllegalStateException(
                    "confirmDivestiture() invalide depuis l'etat " + ownershipState + " (attendu DIVESTING)");
        }
        ownershipState = OwnershipState.UNOWNED;
    }

    @Override
    public void cancelDivestiture() {
        if (ownershipState != OwnershipState.DIVESTING) {
            throw new IllegalStateException(
                    "cancelDivestiture() invalide depuis l'etat " + ownershipState + " (attendu DIVESTING)");
        }
        ownershipState = OwnershipState.OWNED;
    }

    @Override
    public void forceOwnershipState(OwnershipState state) {
        this.ownershipState = state;
    }

    // ------------------------------------------------------------------
    // Cote ecrivain (le federate possede/publie cet attribut)
    // ------------------------------------------------------------------

    /**
     * Modifie la valeur locale et marque l'attribut comme "a publier".
     * GARDE PAR L'OWNERSHIP : leve une exception si l'attribut n'est pas
     * actuellement OWNED.
     */
    public void set(T newValue) {
        if (ownershipState != OwnershipState.OWNED) {
            throw new IllegalStateException(
                    "set() impossible : attribut non possede (ownershipState=" + ownershipState + ", attendu OWNED)");
        }
        this.value = newValue;
        this.needsPublication = true;
    }

    @Override
    public void markPublished() {
        needsPublication = false;
    }

    @Override
    public boolean needsPublication() {
        return needsPublication;
    }

    // ------------------------------------------------------------------
    // Cote souscripteur (le federate recoit des reflectAttributeValues)
    // ------------------------------------------------------------------

    public void onReflect(T newValue, Instant timestamp) {
        this.value = newValue;
        this.lastUpdateTime = timestamp;
        this.hasBeenReflectedAtLeastOnce = true;
    }

    public void onReflect(T newValue) {
        onReflect(newValue, Instant.now());
    }

    @Override
    public Instant lastUpdateTime() {
        return lastUpdateTime;
    }

    @Override
    public boolean hasBeenReflected() {
        return hasBeenReflectedAtLeastOnce;
    }

    /**
     * Decode des octets recus du RTI et applique le resultat via
     * onReflect(). Delegue a HLADecoder (cf. HLADecoder.java).
     */
    @Override
    @SuppressWarnings("unchecked")
    public void reflectRaw(byte[] bytes, Instant timestamp) {
        T decoded;
        if (elementType != null) {
            decoded = (T) HLADecoder.decodeList(bytes, elementType);
        } else {
            decoded = HLADecoder.decode(type, bytes);
        }
        onReflect(decoded, timestamp);
    }

    // ------------------------------------------------------------------
    // Acces commun a la valeur
    // ------------------------------------------------------------------

    public T get() {
        return value;
    }

    @Override
    public boolean hasValue() {
        return value != null;
    }

    /**
     * Serialise la valeur courante en octets. Delegue a HLAEncoder
     * (cf. HLAEncoder.java).
     */
    @Override
    @SuppressWarnings("unchecked")
    public byte[] encode() {
        if (elementType != null) {
            return HLAEncoder.encodeList((List<Object>) value, (Class<Object>) elementType);
        }
        return HLAEncoder.encode(type, value);
    }
}
