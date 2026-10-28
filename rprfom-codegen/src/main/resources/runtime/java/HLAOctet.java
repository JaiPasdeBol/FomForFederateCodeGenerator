package rprfom;

/**
 * HLAOctet.java
 *
 * Wrapper dedie pour la representation de base "HLAoctet" (entier non
 * signe 0-255) utilisee directement comme type d'ATTRIBUT (pas comme
 * simple champ de struct, ou un int Java suffit).
 *
 * Necessaire car Java n'a pas d'octet non signe natif, et le registre
 * HLAEncoder/HLADecoder est indexe par Class<?> (effacement de type) :
 * reutiliser Byte ou Integer pour HLAoctet entrerait en collision avec
 * d'autres representations HLA deja mappees sur ces classes. Cf. la
 * note d'architecture dans TypeMapper (genere ce commentaire) / HLAEncoder.
 */
public final class HLAOctet {
    public int value; // 0..255

    public HLAOctet() {
    }

    public HLAOctet(int value) {
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HLAOctet other && other.value == value;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(value);
    }

    @Override
    public String toString() {
        return "HLAOctet(" + value + ")";
    }
}
