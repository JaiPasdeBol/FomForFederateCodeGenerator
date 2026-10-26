package rprfom;

/**
 * HLAUnsignedInteger32.java
 *
 * Wrapper dedie pour "HLAunsignedInteger32BE" (0..4294967295) utilise
 * comme type d'ATTRIBUT. Un long Java stocke la valeur complete sans
 * perte ; ce wrapper existe uniquement pour donner a cette
 * representation une Class distincte de HLAinteger64BE (qui utilise
 * Long nativement) dans le registre HLAEncoder/HLADecoder.
 */
public final class HLAUnsignedInteger32 {
    public long value; // 0..4294967295

    public HLAUnsignedInteger32() {
    }

    public HLAUnsignedInteger32(long value) {
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HLAUnsignedInteger32 other && other.value == value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    @Override
    public String toString() {
        return "HLAUnsignedInteger32(" + value + ")";
    }
}
