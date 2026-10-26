#pragma once
//
// HLADecoding.hpp
//
// Fonctions de décodage bas niveau, miroir exact de HLAEncoding.hpp :
// lecture d'entiers/flottants en big-endian, HLAboolean lu comme un
// HLAinteger32BE (0/1), et gestion du padding pour retrouver
// l'alignement de chaque champ à l'intérieur d'un HLAfixedRecord.
//
// Chaque fonction avance `offset` du nombre d'octets consommés, ce qui
// permet d'enchaîner les lectures dans le même ordre que l'écriture
// (cf. HLAEncoding.hpp) pour décoder un HLAfixedRecord champ par champ.
//

#include <array>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

namespace rprfom {
namespace hla_decoding {

// Avance `offset` jusqu'à ce qu'il soit un multiple de `alignment`
// (symétrique de hla_encoding::appendPadding).
inline void skipPadding(const std::vector<uint8_t>& /*buffer*/, size_t& offset, size_t alignment) {
    while (offset % alignment != 0) {
        ++offset;
    }
}

inline void checkAvailable(const std::vector<uint8_t>& buffer, size_t offset, size_t needed) {
    if (offset + needed > buffer.size()) {
        throw std::out_of_range(
            "HLA decode: buffer trop court (offset=" + std::to_string(offset) +
            ", besoin de " + std::to_string(needed) +
            " octets, taille du buffer=" + std::to_string(buffer.size()) + ")");
    }
}

inline uint8_t readOctet(const std::vector<uint8_t>& buffer, size_t& offset) {
    checkAvailable(buffer, offset, 1);
    return buffer[offset++];
}

inline int16_t readInteger16BE(const std::vector<uint8_t>& buffer, size_t& offset) {
    skipPadding(buffer, offset, 2);
    checkAvailable(buffer, offset, 2);
    const uint16_t u = (static_cast<uint16_t>(buffer[offset]) << 8) |
                        static_cast<uint16_t>(buffer[offset + 1]);
    offset += 2;
    return static_cast<int16_t>(u);
}

inline uint16_t readUnsignedInteger16BE(const std::vector<uint8_t>& buffer, size_t& offset) {
    skipPadding(buffer, offset, 2);
    checkAvailable(buffer, offset, 2);
    const uint16_t u = (static_cast<uint16_t>(buffer[offset]) << 8) |
                        static_cast<uint16_t>(buffer[offset + 1]);
    offset += 2;
    return u;
}

inline int32_t readInteger32BE(const std::vector<uint8_t>& buffer, size_t& offset) {
    skipPadding(buffer, offset, 4);
    checkAvailable(buffer, offset, 4);
    const uint32_t u = (static_cast<uint32_t>(buffer[offset]) << 24) |
                        (static_cast<uint32_t>(buffer[offset + 1]) << 16) |
                        (static_cast<uint32_t>(buffer[offset + 2]) << 8) |
                        static_cast<uint32_t>(buffer[offset + 3]);
    offset += 4;
    return static_cast<int32_t>(u);
}

inline uint32_t readUnsignedInteger32BE(const std::vector<uint8_t>& buffer, size_t& offset) {
    skipPadding(buffer, offset, 4);
    checkAvailable(buffer, offset, 4);
    const uint32_t u = (static_cast<uint32_t>(buffer[offset]) << 24) |
                        (static_cast<uint32_t>(buffer[offset + 1]) << 16) |
                        (static_cast<uint32_t>(buffer[offset + 2]) << 8) |
                        static_cast<uint32_t>(buffer[offset + 3]);
    offset += 4;
    return u;
}

inline int64_t readInteger64BE(const std::vector<uint8_t>& buffer, size_t& offset) {
    skipPadding(buffer, offset, 8);
    checkAvailable(buffer, offset, 8);
    uint64_t u = 0;
    for (int i = 0; i < 8; ++i) {
        u = (u << 8) | static_cast<uint64_t>(buffer[offset + i]);
    }
    offset += 8;
    return static_cast<int64_t>(u);
}

// HLAboolean == HLAinteger32BE, 0 = false, tout le reste = true.
inline bool readBoolean(const std::vector<uint8_t>& buffer, size_t& offset) {
    return readInteger32BE(buffer, offset) != 0;
}

inline float readFloat32BE(const std::vector<uint8_t>& buffer, size_t& offset) {
    const uint32_t bits = readUnsignedInteger32BE(buffer, offset);
    float v;
    std::memcpy(&v, &bits, sizeof(v));
    return v;
}

inline double readFloat64BE(const std::vector<uint8_t>& buffer, size_t& offset) {
    skipPadding(buffer, offset, 8);
    checkAvailable(buffer, offset, 8);
    uint64_t bits = 0;
    for (int i = 0; i < 8; ++i) {
        bits = (bits << 8) | static_cast<uint64_t>(buffer[offset + i]);
    }
    offset += 8;
    double v;
    std::memcpy(&v, &bits, sizeof(v));
    return v;
}

// ============================================================
// Variantes little-endian (LE) et entiers non signes 64 bits.
// ============================================================

inline int16_t readInteger16LE(const std::vector<uint8_t>& buffer, size_t& offset) {
    skipPadding(buffer, offset, 2);
    checkAvailable(buffer, offset, 2);
    const uint16_t u = static_cast<uint16_t>(buffer[offset]) |
                        (static_cast<uint16_t>(buffer[offset + 1]) << 8);
    offset += 2;
    return static_cast<int16_t>(u);
}

inline uint16_t readUnsignedInteger16LE(const std::vector<uint8_t>& buffer, size_t& offset) {
    skipPadding(buffer, offset, 2);
    checkAvailable(buffer, offset, 2);
    const uint16_t u = static_cast<uint16_t>(buffer[offset]) |
                        (static_cast<uint16_t>(buffer[offset + 1]) << 8);
    offset += 2;
    return u;
}

inline int32_t readInteger32LE(const std::vector<uint8_t>& buffer, size_t& offset) {
    skipPadding(buffer, offset, 4);
    checkAvailable(buffer, offset, 4);
    const uint32_t u = static_cast<uint32_t>(buffer[offset]) |
                        (static_cast<uint32_t>(buffer[offset + 1]) << 8) |
                        (static_cast<uint32_t>(buffer[offset + 2]) << 16) |
                        (static_cast<uint32_t>(buffer[offset + 3]) << 24);
    offset += 4;
    return static_cast<int32_t>(u);
}

inline uint32_t readUnsignedInteger32LE(const std::vector<uint8_t>& buffer, size_t& offset) {
    skipPadding(buffer, offset, 4);
    checkAvailable(buffer, offset, 4);
    const uint32_t u = static_cast<uint32_t>(buffer[offset]) |
                        (static_cast<uint32_t>(buffer[offset + 1]) << 8) |
                        (static_cast<uint32_t>(buffer[offset + 2]) << 16) |
                        (static_cast<uint32_t>(buffer[offset + 3]) << 24);
    offset += 4;
    return u;
}

inline int64_t readInteger64LE(const std::vector<uint8_t>& buffer, size_t& offset) {
    skipPadding(buffer, offset, 8);
    checkAvailable(buffer, offset, 8);
    uint64_t u = 0;
    for (int i = 7; i >= 0; --i) {
        u = (u << 8) | static_cast<uint64_t>(buffer[offset + i]);
    }
    offset += 8;
    return static_cast<int64_t>(u);
}

inline uint64_t readUnsignedInteger64BE(const std::vector<uint8_t>& buffer, size_t& offset) {
    skipPadding(buffer, offset, 8);
    checkAvailable(buffer, offset, 8);
    uint64_t u = 0;
    for (int i = 0; i < 8; ++i) {
        u = (u << 8) | static_cast<uint64_t>(buffer[offset + i]);
    }
    offset += 8;
    return u;
}

inline uint64_t readUnsignedInteger64LE(const std::vector<uint8_t>& buffer, size_t& offset) {
    skipPadding(buffer, offset, 8);
    checkAvailable(buffer, offset, 8);
    uint64_t u = 0;
    for (int i = 7; i >= 0; --i) {
        u = (u << 8) | static_cast<uint64_t>(buffer[offset + i]);
    }
    offset += 8;
    return u;
}

inline float readFloat32LE(const std::vector<uint8_t>& buffer, size_t& offset) {
    skipPadding(buffer, offset, 4);
    checkAvailable(buffer, offset, 4);
    const uint32_t bits = static_cast<uint32_t>(buffer[offset]) |
                           (static_cast<uint32_t>(buffer[offset + 1]) << 8) |
                           (static_cast<uint32_t>(buffer[offset + 2]) << 16) |
                           (static_cast<uint32_t>(buffer[offset + 3]) << 24);
    offset += 4;
    float v;
    std::memcpy(&v, &bits, sizeof(v));
    return v;
}

inline double readFloat64LE(const std::vector<uint8_t>& buffer, size_t& offset) {
    skipPadding(buffer, offset, 8);
    checkAvailable(buffer, offset, 8);
    uint64_t bits = 0;
    for (int i = 7; i >= 0; --i) {
        bits = (bits << 8) | static_cast<uint64_t>(buffer[offset + i]);
    }
    offset += 8;
    double v;
    std::memcpy(&v, &bits, sizeof(v));
    return v;
}

// Miroir de appendOctetPair (cf. HLAEncoding.hpp) : pas de permutation,
// simple lecture de 2 octets bruts.
inline std::array<uint8_t, 2> readOctetPair(const std::vector<uint8_t>& buffer, size_t& offset) {
    checkAvailable(buffer, offset, 2);
    std::array<uint8_t, 2> pair{buffer[offset], buffer[offset + 1]};
    offset += 2;
    return pair;
}

inline std::string readASCIIString(const std::vector<uint8_t>& buffer, size_t& offset) {
    const uint32_t count = readUnsignedInteger32BE(buffer, offset);
    checkAvailable(buffer, offset, count);
    std::string s(reinterpret_cast<const char*>(&buffer[offset]), count);
    offset += count;
    return s;
}

inline std::u16string readUnicodeString(const std::vector<uint8_t>& buffer, size_t& offset) {
    const uint32_t count = readUnsignedInteger32BE(buffer, offset);
    std::u16string s;
    s.reserve(count);
    for (uint32_t i = 0; i < count; ++i) {
        s.push_back(static_cast<char16_t>(readUnsignedInteger16BE(buffer, offset)));
    }
    return s;
}

} // namespace hla_decoding
} // namespace rprfom
