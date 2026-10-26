package rprfom;

/**
 * HLAASCIIString.java
 *
 * HLAASCIIstring (chaine ASCII) -- dedie car HLAunicodeString utilise deja java.lang.String nativement.
 * Wrapper dedie necessaire cote registre HLAEncoder/HLADecoder (Class<?>),
 * cf. la note d'architecture dans TypeMapper/HLAEncoder du generateur.
 */
public final class HLAASCIIString {
    public String value;

    public HLAASCIIString() {
    }

    public HLAASCIIString(String value) {
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HLAASCIIString other && java.util.Objects.equals(other.value, value);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return "HLAASCIIString(" + value + ")";
    }
}
