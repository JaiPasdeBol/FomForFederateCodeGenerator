package rprfom;

/**
 * HLAUnsignedInteger32LE.java
 *
 * HLAunsignedInteger32LE (32 bits non signe, little-endian).
 * Wrapper dedie necessaire cote registre HLAEncoder/HLADecoder (Class<?>),
 * cf. la note d'architecture dans TypeMapper/HLAEncoder du generateur.
 */
public final class HLAUnsignedInteger32LE {
    public long value;

    public HLAUnsignedInteger32LE() {
    }

    public HLAUnsignedInteger32LE(long value) {
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HLAUnsignedInteger32LE other && other.value == value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    @Override
    public String toString() {
        return "HLAUnsignedInteger32LE(" + value + ")";
    }
}
