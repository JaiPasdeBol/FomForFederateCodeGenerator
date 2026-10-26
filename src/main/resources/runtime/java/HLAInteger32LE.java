package rprfom;

/**
 * HLAInteger32LE.java
 *
 * HLAinteger32LE (32 bits signe, little-endian).
 * Wrapper dedie necessaire cote registre HLAEncoder/HLADecoder (Class<?>),
 * cf. la note d'architecture dans TypeMapper/HLAEncoder du generateur.
 */
public final class HLAInteger32LE {
    public int value;

    public HLAInteger32LE() {
    }

    public HLAInteger32LE(int value) {
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HLAInteger32LE other && other.value == value;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(value);
    }

    @Override
    public String toString() {
        return "HLAInteger32LE(" + value + ")";
    }
}
