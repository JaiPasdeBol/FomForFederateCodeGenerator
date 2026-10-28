package rprfom;

import java.io.ByteArrayOutputStream;

/**
 * HLAEncoding.java
 *
 * Fonctions d'encodage bas niveau pour les representations de base HLA
 * (IEEE 1516.2 Basic Data Representations) : entiers/flottants en
 * big-endian, HLAboolean encode comme un HLAinteger32BE valant 0 ou 1,
 * et alignement de chaque champ sur son propre "octet boundary" a
 * l'interieur d'un HLAfixedRecord.
 *
 * Miroir exact de HLAEncoding.hpp. Le "std::vector&lt;uint8_t&gt;&amp;" C++
 * devient ici un ByteArrayOutputStream (mutable, append-only, exactement
 * le meme usage).
 */
public final class HLAEncoding {

    private HLAEncoding() {
    }

    /** Complete le buffer avec des octets a 0 jusqu'a un multiple de `alignment`. */
    public static void appendPadding(ByteArrayOutputStream buffer, int alignment) {
        while (buffer.size() % alignment != 0) {
            buffer.write(0);
        }
    }

    public static void appendOctet(ByteArrayOutputStream buffer, int v) {
        buffer.write(v & 0xFF);
    }

    public static void appendInteger16BE(ByteArrayOutputStream buffer, short v) {
        appendPadding(buffer, 2);
        buffer.write((v >> 8) & 0xFF);
        buffer.write(v & 0xFF);
    }

    public static void appendUnsignedInteger16BE(ByteArrayOutputStream buffer, int v) {
        appendPadding(buffer, 2);
        buffer.write((v >> 8) & 0xFF);
        buffer.write(v & 0xFF);
    }

    public static void appendInteger32BE(ByteArrayOutputStream buffer, int v) {
        appendPadding(buffer, 4);
        buffer.write((v >> 24) & 0xFF);
        buffer.write((v >> 16) & 0xFF);
        buffer.write((v >> 8) & 0xFF);
        buffer.write(v & 0xFF);
    }

    public static void appendUnsignedInteger32BE(ByteArrayOutputStream buffer, long v) {
        appendPadding(buffer, 4);
        buffer.write((int) ((v >> 24) & 0xFF));
        buffer.write((int) ((v >> 16) & 0xFF));
        buffer.write((int) ((v >> 8) & 0xFF));
        buffer.write((int) (v & 0xFF));
    }

    public static void appendInteger64BE(ByteArrayOutputStream buffer, long v) {
        appendPadding(buffer, 8);
        for (int shift = 56; shift >= 0; shift -= 8) {
            buffer.write((int) ((v >> shift) & 0xFF));
        }
    }

    /** HLAboolean (IEEE 1516.2) == HLAinteger32BE, 0 = faux, 1 = vrai. */
    public static void appendBoolean(ByteArrayOutputStream buffer, boolean v) {
        appendInteger32BE(buffer, v ? 1 : 0);
    }

    public static void appendFloat32BE(ByteArrayOutputStream buffer, float v) {
        appendPadding(buffer, 4);
        int bits = Float.floatToIntBits(v);
        buffer.write((bits >> 24) & 0xFF);
        buffer.write((bits >> 16) & 0xFF);
        buffer.write((bits >> 8) & 0xFF);
        buffer.write(bits & 0xFF);
    }

    public static void appendFloat64BE(ByteArrayOutputStream buffer, double v) {
        appendPadding(buffer, 8);
        long bits = Double.doubleToLongBits(v);
        for (int shift = 56; shift >= 0; shift -= 8) {
            buffer.write((int) ((bits >> shift) & 0xFF));
        }
    }

    // ============================================================
    // Variantes little-endian (LE) et entiers non signes 64 bits.
    // ============================================================

    public static void appendInteger16LE(ByteArrayOutputStream buffer, short v) {
        appendPadding(buffer, 2);
        buffer.write(v & 0xFF);
        buffer.write((v >> 8) & 0xFF);
    }

    public static void appendUnsignedInteger16LE(ByteArrayOutputStream buffer, int v) {
        appendPadding(buffer, 2);
        buffer.write(v & 0xFF);
        buffer.write((v >> 8) & 0xFF);
    }

    public static void appendInteger32LE(ByteArrayOutputStream buffer, int v) {
        appendPadding(buffer, 4);
        buffer.write(v & 0xFF);
        buffer.write((v >> 8) & 0xFF);
        buffer.write((v >> 16) & 0xFF);
        buffer.write((v >> 24) & 0xFF);
    }

    public static void appendUnsignedInteger32LE(ByteArrayOutputStream buffer, long v) {
        appendPadding(buffer, 4);
        buffer.write((int) (v & 0xFF));
        buffer.write((int) ((v >> 8) & 0xFF));
        buffer.write((int) ((v >> 16) & 0xFF));
        buffer.write((int) ((v >> 24) & 0xFF));
    }

    public static void appendInteger64LE(ByteArrayOutputStream buffer, long v) {
        appendPadding(buffer, 8);
        for (int shift = 0; shift <= 56; shift += 8) {
            buffer.write((int) ((v >> shift) & 0xFF));
        }
    }

    /** HLAunsignedInteger64BE/LE : Java n'a pas d'entier 64 bits non signe natif ;
     *  `long` porte les 64 bits bruts (a interpreter comme non signe par l'appelant). */
    public static void appendUnsignedInteger64BE(ByteArrayOutputStream buffer, long v) {
        appendInteger64BE(buffer, v);
    }

    public static void appendUnsignedInteger64LE(ByteArrayOutputStream buffer, long v) {
        appendInteger64LE(buffer, v);
    }

    public static void appendFloat32LE(ByteArrayOutputStream buffer, float v) {
        appendPadding(buffer, 4);
        int bits = Float.floatToIntBits(v);
        buffer.write(bits & 0xFF);
        buffer.write((bits >> 8) & 0xFF);
        buffer.write((bits >> 16) & 0xFF);
        buffer.write((bits >> 24) & 0xFF);
    }

    public static void appendFloat64LE(ByteArrayOutputStream buffer, double v) {
        appendPadding(buffer, 8);
        long bits = Double.doubleToLongBits(v);
        for (int shift = 0; shift <= 56; shift += 8) {
            buffer.write((int) ((bits >> shift) & 0xFF));
        }
    }

    /**
     * HLAoctetPairBE/LE : 2 octets bruts, sans interpretation arithmetique.
     * SIMPLIFICATION : BE et LE traites de facon identique (cf. HLAEncoding.hpp).
     */
    public static void appendOctetPair(ByteArrayOutputStream buffer, byte[] pair) {
        buffer.write(pair[0]);
        buffer.write(pair[1]);
    }

    /** HLAASCIIstring : compteur HLAinteger32BE + octets ASCII bruts. */
    public static void appendASCIIString(ByteArrayOutputStream buffer, String s) {
        byte[] raw = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        appendUnsignedInteger32BE(buffer, raw.length);
        buffer.writeBytes(raw);
    }

    /** HLAunicodeString : compteur HLAinteger32BE (nb d'unites UTF-16) + unites en HLAinteger16BE. */
    public static void appendUnicodeString(ByteArrayOutputStream buffer, String s) {
        appendUnsignedInteger32BE(buffer, s.length());
        for (int i = 0; i < s.length(); i++) {
            appendUnsignedInteger16BE(buffer, s.charAt(i));
        }
    }
}
