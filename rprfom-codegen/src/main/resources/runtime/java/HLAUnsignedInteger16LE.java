package rprfom;

/**
 * HLAUnsignedInteger16LE.java
 *
 * HLAunsignedInteger16LE (16 bits non signe, 0..65535, little-endian).
 * Wrapper dedie necessaire cote registre HLAEncoder/HLADecoder (Class<?>),
 * cf. la note d'architecture dans TypeMapper/HLAEncoder du generateur.
 */
public final class HLAUnsignedInteger16LE {
    public int value;

    public HLAUnsignedInteger16LE() {
    }

    public HLAUnsignedInteger16LE(int value) {
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HLAUnsignedInteger16LE other && other.value == value;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(value);
    }

    @Override
    public String toString() {
        return "HLAUnsignedInteger16LE(" + value + ")";
    }
}
