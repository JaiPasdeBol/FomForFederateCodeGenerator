package rprfom;

/**
 * HLAInteger16LE.java
 *
 * HLAinteger16LE (16 bits signe, little-endian).
 * Wrapper dedie necessaire cote registre HLAEncoder/HLADecoder (Class<?>),
 * cf. la note d'architecture dans TypeMapper/HLAEncoder du generateur.
 */
public final class HLAInteger16LE {
    public short value;

    public HLAInteger16LE() {
    }

    public HLAInteger16LE(short value) {
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HLAInteger16LE other && other.value == value;
    }

    @Override
    public int hashCode() {
        return Short.hashCode(value);
    }

    @Override
    public String toString() {
        return "HLAInteger16LE(" + value + ")";
    }
}
