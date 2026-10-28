package rprfom;

/**
 * HLADecoding.java
 *
 * Fonctions de decodage bas niveau, miroir exact de HLADecoding.hpp :
 * lecture d'entiers/flottants en big-endian, HLAboolean lu comme un
 * HLAinteger32BE (0/1), et gestion du padding pour retrouver
 * l'alignement de chaque champ a l'interieur d'un HLAfixedRecord.
 *
 * Java n'a pas de "size_t&amp; offset" (parametre de sortie primitif) :
 * Offset est une petite classe mutable a un seul champ qui joue
 * exactement ce role, passee par reference d'objet.
 */
public final class HLADecoding {

    private HLADecoding() {
    }

    /** Equivalent du "size_t& offset" C++ : un curseur mutable partage. */
    public static final class Offset {
        public int value;

        public Offset() {
            this(0);
        }

        public Offset(int initial) {
            this.value = initial;
        }
    }

    public static void skipPadding(byte[] buffer, Offset offset, int alignment) {
        while (offset.value % alignment != 0) {
            offset.value++;
        }
    }

    public static void checkAvailable(byte[] buffer, int offset, int needed) {
        if (offset + needed > buffer.length) {
            throw new IndexOutOfBoundsException(
                    "HLA decode: buffer trop court (offset=" + offset +
                            ", besoin de " + needed +
                            " octets, taille du buffer=" + buffer.length + ")");
        }
    }

    public static int readOctet(byte[] buffer, Offset offset) {
        checkAvailable(buffer, offset.value, 1);
        return buffer[offset.value++] & 0xFF;
    }

    public static short readInteger16BE(byte[] buffer, Offset offset) {
        skipPadding(buffer, offset, 2);
        checkAvailable(buffer, offset.value, 2);
        int u = ((buffer[offset.value] & 0xFF) << 8) | (buffer[offset.value + 1] & 0xFF);
        offset.value += 2;
        return (short) u;
    }

    public static int readUnsignedInteger16BE(byte[] buffer, Offset offset) {
        skipPadding(buffer, offset, 2);
        checkAvailable(buffer, offset.value, 2);
        int u = ((buffer[offset.value] & 0xFF) << 8) | (buffer[offset.value + 1] & 0xFF);
        offset.value += 2;
        return u;
    }

    public static int readInteger32BE(byte[] buffer, Offset offset) {
        skipPadding(buffer, offset, 4);
        checkAvailable(buffer, offset.value, 4);
        int u = ((buffer[offset.value] & 0xFF) << 24) |
                ((buffer[offset.value + 1] & 0xFF) << 16) |
                ((buffer[offset.value + 2] & 0xFF) << 8) |
                (buffer[offset.value + 3] & 0xFF);
        offset.value += 4;
        return u;
    }

    public static long readUnsignedInteger32BE(byte[] buffer, Offset offset) {
        return readInteger32BE(buffer, offset) & 0xFFFFFFFFL;
    }

    public static long readInteger64BE(byte[] buffer, Offset offset) {
        skipPadding(buffer, offset, 8);
        checkAvailable(buffer, offset.value, 8);
        long v = 0;
        for (int i = 0; i < 8; ++i) {
            v = (v << 8) | (buffer[offset.value + i] & 0xFFL);
        }
        offset.value += 8;
        return v;
    }

    /** HLAboolean == HLAinteger32BE, 0 = false, tout le reste = true. */
    public static boolean readBoolean(byte[] buffer, Offset offset) {
        return readInteger32BE(buffer, offset) != 0;
    }

    public static float readFloat32BE(byte[] buffer, Offset offset) {
        int bits = readInteger32BE(buffer, offset);
        return Float.intBitsToFloat(bits);
    }

    public static double readFloat64BE(byte[] buffer, Offset offset) {
        skipPadding(buffer, offset, 8);
        checkAvailable(buffer, offset.value, 8);
        long bits = 0;
        for (int i = 0; i < 8; ++i) {
            bits = (bits << 8) | (buffer[offset.value + i] & 0xFFL);
        }
        offset.value += 8;
        return Double.longBitsToDouble(bits);
    }

    // ============================================================
    // Variantes little-endian (LE) et entiers non signes 64 bits.
    // ============================================================

    public static short readInteger16LE(byte[] buffer, Offset offset) {
        skipPadding(buffer, offset, 2);
        checkAvailable(buffer, offset.value, 2);
        int u = (buffer[offset.value] & 0xFF) | ((buffer[offset.value + 1] & 0xFF) << 8);
        offset.value += 2;
        return (short) u;
    }

    public static int readUnsignedInteger16LE(byte[] buffer, Offset offset) {
        skipPadding(buffer, offset, 2);
        checkAvailable(buffer, offset.value, 2);
        int u = (buffer[offset.value] & 0xFF) | ((buffer[offset.value + 1] & 0xFF) << 8);
        offset.value += 2;
        return u;
    }

    public static int readInteger32LE(byte[] buffer, Offset offset) {
        skipPadding(buffer, offset, 4);
        checkAvailable(buffer, offset.value, 4);
        int u = (buffer[offset.value] & 0xFF) |
                ((buffer[offset.value + 1] & 0xFF) << 8) |
                ((buffer[offset.value + 2] & 0xFF) << 16) |
                ((buffer[offset.value + 3] & 0xFF) << 24);
        offset.value += 4;
        return u;
    }

    public static long readUnsignedInteger32LE(byte[] buffer, Offset offset) {
        return readInteger32LE(buffer, offset) & 0xFFFFFFFFL;
    }

    public static long readInteger64LE(byte[] buffer, Offset offset) {
        skipPadding(buffer, offset, 8);
        checkAvailable(buffer, offset.value, 8);
        long v = 0;
        for (int i = 7; i >= 0; --i) {
            v = (v << 8) | (buffer[offset.value + i] & 0xFFL);
        }
        offset.value += 8;
        return v;
    }

    /** Java n'a pas d'entier 64 bits non signe natif ; `long` porte les 64 bits bruts. */
    public static long readUnsignedInteger64BE(byte[] buffer, Offset offset) {
        return readInteger64BE(buffer, offset);
    }

    public static long readUnsignedInteger64LE(byte[] buffer, Offset offset) {
        return readInteger64LE(buffer, offset);
    }

    public static float readFloat32LE(byte[] buffer, Offset offset) {
        skipPadding(buffer, offset, 4);
        checkAvailable(buffer, offset.value, 4);
        int bits = (buffer[offset.value] & 0xFF) |
                ((buffer[offset.value + 1] & 0xFF) << 8) |
                ((buffer[offset.value + 2] & 0xFF) << 16) |
                ((buffer[offset.value + 3] & 0xFF) << 24);
        offset.value += 4;
        return Float.intBitsToFloat(bits);
    }

    public static double readFloat64LE(byte[] buffer, Offset offset) {
        skipPadding(buffer, offset, 8);
        checkAvailable(buffer, offset.value, 8);
        long bits = 0;
        for (int i = 7; i >= 0; --i) {
            bits = (bits << 8) | (buffer[offset.value + i] & 0xFFL);
        }
        offset.value += 8;
        return Double.longBitsToDouble(bits);
    }

    /** Miroir de appendOctetPair : pas de permutation, lecture brute de 2 octets. */
    public static byte[] readOctetPair(byte[] buffer, Offset offset) {
        checkAvailable(buffer, offset.value, 2);
        byte[] pair = {buffer[offset.value], buffer[offset.value + 1]};
        offset.value += 2;
        return pair;
    }

    public static String readASCIIString(byte[] buffer, Offset offset) {
        long count = readUnsignedInteger32BE(buffer, offset);
        checkAvailable(buffer, offset.value, (int) count);
        String s = new String(buffer, offset.value, (int) count, java.nio.charset.StandardCharsets.US_ASCII);
        offset.value += (int) count;
        return s;
    }

    public static String readUnicodeString(byte[] buffer, Offset offset) {
        long count = readUnsignedInteger32BE(buffer, offset);
        StringBuilder sb = new StringBuilder();
        for (long i = 0; i < count; i++) {
            sb.append((char) readUnsignedInteger16BE(buffer, offset));
        }
        return sb.toString();
    }
}
