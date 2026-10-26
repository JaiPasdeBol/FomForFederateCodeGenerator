package rprfom;

/**
 * HLAFloat32LE.java
 *
 * HLAfloat32LE (flottant simple precision, little-endian).
 * Wrapper dedie necessaire cote registre HLAEncoder/HLADecoder (Class<?>),
 * cf. la note d'architecture dans TypeMapper/HLAEncoder du generateur.
 */
public final class HLAFloat32LE {
    public float value;

    public HLAFloat32LE() {
    }

    public HLAFloat32LE(float value) {
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HLAFloat32LE other && other.value == value;
    }

    @Override
    public int hashCode() {
        return Float.hashCode(value);
    }

    @Override
    public String toString() {
        return "HLAFloat32LE(" + value + ")";
    }
}
