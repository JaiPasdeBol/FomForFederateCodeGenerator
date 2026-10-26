package rprfom;

/**
 * HLAUnsignedInteger16.java
 *
 * Wrapper dedie pour "HLAunsignedInteger16BE" (0..65535) utilise comme
 * type d'ATTRIBUT. Un int Java stocke la valeur complete sans perte ;
 * ce wrapper existe uniquement pour donner a cette representation une
 * Class distincte de HLAinteger32BE (qui utilise Integer nativement)
 * dans le registre HLAEncoder/HLADecoder.
 */
public final class HLAUnsignedInteger16 {
    public int value; // 0..65535

    public HLAUnsignedInteger16() {
    }

    public HLAUnsignedInteger16(int value) {
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HLAUnsignedInteger16 other && other.value == value;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(value);
    }

    @Override
    public String toString() {
        return "HLAUnsignedInteger16(" + value + ")";
    }
}
