package rprfom;

/**
 * HLAFloat64LE.java
 *
 * HLAfloat64LE (flottant double precision, little-endian).
 * Wrapper dedie necessaire cote registre HLAEncoder/HLADecoder (Class<?>),
 * cf. la note d'architecture dans TypeMapper/HLAEncoder du generateur.
 */
public final class HLAFloat64LE {
    public double value;

    public HLAFloat64LE() {
    }

    public HLAFloat64LE(double value) {
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HLAFloat64LE other && other.value == value;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(value);
    }

    @Override
    public String toString() {
        return "HLAFloat64LE(" + value + ")";
    }
}
