package rprfom;

import java.util.List;

// Interface PARTAGEE (jar rprfom-runtime-api), commune a tous les federes.
import rprfom.api.IHLAParameterBase;

/**
 * HLAParameter.java
 *
 * Miroir de HLAAttribute.java pour un PARAMETRE d'interaction, sans
 * ownership ni suivi temporel (cf. IHLAParameterBase.java). Meme
 * mecanisme de type erasure (jeton Class&lt;T&gt;, ou Class&lt;E&gt; d'element
 * via ofList() pour un parametre de type tableau) que HLAAttribute.
 */
public final class HLAParameter<T> implements IHLAParameterBase {

    private T value;
    private boolean hasValue = false;

    private final Class<T> type;
    private final Class<?> elementType;

    public HLAParameter(Class<T> type) {
        this.type = type;
        this.elementType = null;
    }

    public HLAParameter(Class<T> type, T initialValue) {
        this(type);
        set(initialValue);
    }

    private HLAParameter(ListMarker marker, Class<?> elementType) {
        this.type = null;
        this.elementType = elementType;
    }

    private static final class ListMarker {
    }

    /** Fabrique pour un parametre de type tableau (HLAvariableArray). */
    public static <E> HLAParameter<List<E>> ofList(Class<E> elementType) {
        return new HLAParameter<>(new ListMarker(), elementType);
    }

    /** Modifie la valeur locale (pas de garde d'ownership). */
    public void set(T newValue) {
        this.value = newValue;
        this.hasValue = true;
    }

    public T get() {
        return value;
    }

    @Override
    public boolean hasValue() {
        return hasValue;
    }

    @Override
    @SuppressWarnings("unchecked")
    public byte[] encode() {
        if (elementType != null) {
            return HLAEncoder.encodeList((List<Object>) value, (Class<Object>) elementType);
        }
        return HLAEncoder.encode(type, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void decodeFrom(byte[] bytes) {
        if (elementType != null) {
            value = (T) HLADecoder.decodeList(bytes, elementType);
        } else {
            value = HLADecoder.decode(type, bytes);
        }
        hasValue = true;
    }
}
