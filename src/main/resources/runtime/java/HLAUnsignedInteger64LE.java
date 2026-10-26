package rprfom;

/**
 * HLAUnsignedInteger64LE.java
 *
 * HLAunsignedInteger64LE (64 bits non signe, little-endian ; long Java porte les bits bruts).
 * Wrapper dedie necessaire cote registre HLAEncoder/HLADecoder (Class<?>),
 * cf. la note d'architecture dans TypeMapper/HLAEncoder du generateur.
 */
public final class HLAUnsignedInteger64LE {
    public long value;

    public HLAUnsignedInteger64LE() {
    }

    public HLAUnsignedInteger64LE(long value) {
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HLAUnsignedInteger64LE other && other.value == value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    @Override
    public String toString() {
        return "HLAUnsignedInteger64LE(" + value + ")";
    }
}
