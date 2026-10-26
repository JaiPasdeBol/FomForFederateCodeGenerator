package rprfom;

/**
 * HLAUnsignedInteger64.java
 *
 * HLAunsignedInteger64BE (64 bits non signe ; long Java porte les bits bruts).
 * Wrapper dedie necessaire cote registre HLAEncoder/HLADecoder (Class<?>),
 * cf. la note d'architecture dans TypeMapper/HLAEncoder du generateur.
 */
public final class HLAUnsignedInteger64 {
    public long value;

    public HLAUnsignedInteger64() {
    }

    public HLAUnsignedInteger64(long value) {
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HLAUnsignedInteger64 other && other.value == value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    @Override
    public String toString() {
        return "HLAUnsignedInteger64(" + value + ")";
    }
}
