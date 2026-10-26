package rprfom;

/**
 * HLAInteger64LE.java
 *
 * HLAinteger64LE (64 bits signe, little-endian).
 * Wrapper dedie necessaire cote registre HLAEncoder/HLADecoder (Class<?>),
 * cf. la note d'architecture dans TypeMapper/HLAEncoder du generateur.
 */
public final class HLAInteger64LE {
    public long value;

    public HLAInteger64LE() {
    }

    public HLAInteger64LE(long value) {
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HLAInteger64LE other && other.value == value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    @Override
    public String toString() {
        return "HLAInteger64LE(" + value + ")";
    }
}
