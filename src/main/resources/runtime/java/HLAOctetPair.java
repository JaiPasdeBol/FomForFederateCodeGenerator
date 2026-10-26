package rprfom;

import java.util.Arrays;

/**
 * HLAOctetPair.java
 *
 * HLAoctetPairBE / HLAoctetPairLE : 2 octets bruts sans interpretation
 * arithmetique. SIMPLIFICATION : BE et LE partagent ce meme wrapper et
 * le meme encodage (pas de permutation), cf. la note dans HLAEncoding.java.
 */
public final class HLAOctetPair {
    public byte[] value = new byte[2];

    public HLAOctetPair() {
    }

    public HLAOctetPair(byte[] value) {
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HLAOctetPair other && Arrays.equals(other.value, value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "HLAOctetPair(" + Arrays.toString(value) + ")";
    }
}
