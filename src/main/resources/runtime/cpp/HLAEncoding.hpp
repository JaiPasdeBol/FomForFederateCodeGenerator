#pragma once
//
// HLAEncoding.hpp
//
// Fonctions d'encodage bas niveau pour les représentations de base HLA
// telles que définies par IEEE 1516.2 (Basic Data Representations) :
// entiers/flottants en big-endian ("network byte order"), HLAboolean
// encodé comme un HLAinteger32BE valant 0 ou 1, et alignement de
// chaque champ sur son propre "octet boundary" à l'intérieur d'un
// HLAfixedRecord.
//
// Ce fichier n'est volontairement pas lié à un binding RTI particulier
// (Portico/Pitch/CERTI/...) : il ne fait que produire des octets. À
// terme, si tu utilises les encoders natifs de ton RTI (rti1516e
// HLAinteger32BE, HLAfixedRecord, ...), ce fichier peut être remplacé
// par de simples appels à cette API sans changer HLAEncoder.hpp.
//

#include <array>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace rprfom {
namespace hla_encoding {

// Complète le buffer avec des octets à 0 jusqu'à ce que sa taille soit
// un multiple de `alignment` (octet boundary du champ à venir).
inline void appendPadding(std::vector<uint8_t>& buffer, size_t alignment) {
    while (buffer.size() % alignment != 0) {
        buffer.push_back(0);
    }
}

inline void appendOctet(std::vector<uint8_t>& buffer, uint8_t v) {
    buffer.push_back(v);
}

inline void appendInteger16BE(std::vector<uint8_t>& buffer, int16_t v) {
    appendPadding(buffer, 2);
    const auto u = static_cast<uint16_t>(v);
    buffer.push_back(static_cast<uint8_t>((u >> 8) & 0xFF));
    buffer.push_back(static_cast<uint8_t>(u & 0xFF));
}

inline void appendUnsignedInteger16BE(std::vector<uint8_t>& buffer, uint16_t v) {
    appendPadding(buffer, 2);
    buffer.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
    buffer.push_back(static_cast<uint8_t>(v & 0xFF));
}

inline void appendInteger32BE(std::vector<uint8_t>& buffer, int32_t v) {
    appendPadding(buffer, 4);
    const auto u = static_cast<uint32_t>(v);
    buffer.push_back(static_cast<uint8_t>((u >> 24) & 0xFF));
    buffer.push_back(static_cast<uint8_t>((u >> 16) & 0xFF));
    buffer.push_back(static_cast<uint8_t>((u >> 8) & 0xFF));
    buffer.push_back(static_cast<uint8_t>(u & 0xFF));
}

inline void appendUnsignedInteger32BE(std::vector<uint8_t>& buffer, uint32_t v) {
    appendPadding(buffer, 4);
    buffer.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
    buffer.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
    buffer.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
    buffer.push_back(static_cast<uint8_t>(v & 0xFF));
}

inline void appendInteger64BE(std::vector<uint8_t>& buffer, int64_t v) {
    appendPadding(buffer, 8);
    uint64_t u = static_cast<uint64_t>(v);
    for (int shift = 56; shift >= 0; shift -= 8) {
        buffer.push_back(static_cast<uint8_t>((u >> shift) & 0xFF));
    }
}

// HLAboolean (IEEE 1516.2) est représenté comme un HLAinteger32BE dont
// la valeur vaut 0 (faux) ou 1 (vrai).
inline void appendBoolean(std::vector<uint8_t>& buffer, bool v) {
    appendInteger32BE(buffer, v ? 1 : 0);
}

inline void appendFloat32BE(std::vector<uint8_t>& buffer, float v) {
    appendPadding(buffer, 4);
    uint32_t bits;
    std::memcpy(&bits, &v, sizeof(bits)); // IEEE 754 bits -> ordre reseau
    buffer.push_back(static_cast<uint8_t>((bits >> 24) & 0xFF));
    buffer.push_back(static_cast<uint8_t>((bits >> 16) & 0xFF));
    buffer.push_back(static_cast<uint8_t>((bits >> 8) & 0xFF));
    buffer.push_back(static_cast<uint8_t>(bits & 0xFF));
}

inline void appendFloat64BE(std::vector<uint8_t>& buffer, double v) {
    appendPadding(buffer, 8);
    uint64_t bits;
    std::memcpy(&bits, &v, sizeof(bits));
    for (int shift = 56; shift >= 0; shift -= 8) {
        buffer.push_back(static_cast<uint8_t>((bits >> shift) & 0xFF));
    }
}

// ============================================================
// Variantes little-endian (LE) et entiers non signes 64 bits.
// ============================================================

inline void appendInteger16LE(std::vector<uint8_t>& buffer, int16_t v) {
    appendPadding(buffer, 2);
    const auto u = static_cast<uint16_t>(v);
    buffer.push_back(static_cast<uint8_t>(u & 0xFF));
    buffer.push_back(static_cast<uint8_t>((u >> 8) & 0xFF));
}

inline void appendUnsignedInteger16LE(std::vector<uint8_t>& buffer, uint16_t v) {
    appendPadding(buffer, 2);
    buffer.push_back(static_cast<uint8_t>(v & 0xFF));
    buffer.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
}

inline void appendInteger32LE(std::vector<uint8_t>& buffer, int32_t v) {
    appendPadding(buffer, 4);
    const auto u = static_cast<uint32_t>(v);
    buffer.push_back(static_cast<uint8_t>(u & 0xFF));
    buffer.push_back(static_cast<uint8_t>((u >> 8) & 0xFF));
    buffer.push_back(static_cast<uint8_t>((u >> 16) & 0xFF));
    buffer.push_back(static_cast<uint8_t>((u >> 24) & 0xFF));
}

inline void appendUnsignedInteger32LE(std::vector<uint8_t>& buffer, uint32_t v) {
    appendPadding(buffer, 4);
    buffer.push_back(static_cast<uint8_t>(v & 0xFF));
    buffer.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
    buffer.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
    buffer.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
}

inline void appendInteger64LE(std::vector<uint8_t>& buffer, int64_t v) {
    appendPadding(buffer, 8);
    const auto u = static_cast<uint64_t>(v);
    for (int shift = 0; shift <= 56; shift += 8) {
        buffer.push_back(static_cast<uint8_t>((u >> shift) & 0xFF));
    }
}

inline void appendUnsignedInteger64BE(std::vector<uint8_t>& buffer, uint64_t v) {
    appendPadding(buffer, 8);
    for (int shift = 56; shift >= 0; shift -= 8) {
        buffer.push_back(static_cast<uint8_t>((v >> shift) & 0xFF));
    }
}

inline void appendUnsignedInteger64LE(std::vector<uint8_t>& buffer, uint64_t v) {
    appendPadding(buffer, 8);
    for (int shift = 0; shift <= 56; shift += 8) {
        buffer.push_back(static_cast<uint8_t>((v >> shift) & 0xFF));
    }
}

inline void appendFloat32LE(std::vector<uint8_t>& buffer, float v) {
    appendPadding(buffer, 4);
    uint32_t bits;
    std::memcpy(&bits, &v, sizeof(bits));
    buffer.push_back(static_cast<uint8_t>(bits & 0xFF));
    buffer.push_back(static_cast<uint8_t>((bits >> 8) & 0xFF));
    buffer.push_back(static_cast<uint8_t>((bits >> 16) & 0xFF));
    buffer.push_back(static_cast<uint8_t>((bits >> 24) & 0xFF));
}

inline void appendFloat64LE(std::vector<uint8_t>& buffer, double v) {
    appendPadding(buffer, 8);
    uint64_t bits;
    std::memcpy(&bits, &v, sizeof(bits));
    for (int shift = 0; shift <= 56; shift += 8) {
        buffer.push_back(static_cast<uint8_t>((bits >> shift) & 0xFF));
    }
}

// HLAoctetPairBE/LE : 2 octets bruts, sans interpretation arithmetique.
// SIMPLIFICATION : BE et LE sont traites de facon identique ici (les 2
// octets sont ecrits dans l'ordre donne, sans permutation), car
// HLAoctetPair n'a pas de semantique numerique propre dans la norme --
// c'est juste une paire d'octets opaque. A affiner si un usage
// specifique de ton FOM exige une permutation.
inline void appendOctetPair(std::vector<uint8_t>& buffer, const std::array<uint8_t, 2>& pair) {
    buffer.push_back(pair[0]);
    buffer.push_back(pair[1]);
}

// HLAASCIIstring : HLAvariableArray de HLAASCIIchar -- compteur
// HLAinteger32BE (nombre de caracteres) puis les octets bruts de la
// chaine (1 octet par caractere ASCII).
inline void appendASCIIString(std::vector<uint8_t>& buffer, const std::string& s) {
    appendUnsignedInteger32BE(buffer, static_cast<uint32_t>(s.size()));
    for (char c : s) {
        buffer.push_back(static_cast<uint8_t>(c));
    }
}

// HLAunicodeString : HLAvariableArray de HLAunicodeChar -- compteur
// HLAinteger32BE (nombre d'unites UTF-16) puis chaque unite en
// HLAinteger16BE (2 octets).
inline void appendUnicodeString(std::vector<uint8_t>& buffer, const std::u16string& s) {
    appendUnsignedInteger32BE(buffer, static_cast<uint32_t>(s.size()));
    for (char16_t c : s) {
        appendUnsignedInteger16BE(buffer, static_cast<uint16_t>(c));
    }
}

} // namespace hla_encoding
} // namespace rprfom
