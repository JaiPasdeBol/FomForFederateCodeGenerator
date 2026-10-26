#pragma once
//
// WorldLocationStructCodec.hpp -- encodage/decodage HLA de WorldLocationStruct (exemple).
// Ne code QUE les champs de la FOM : x, y, z en HLAfloat64BE.
//
// @rprfom-fom-fingerprint: 80bf448eeef3f1f4
//

#include "../runtime/HLAEncoder.hpp"
#include "../runtime/HLADecoder.hpp"

namespace rprfom {

inline void HLAEncoderDetail<WorldLocationStruct>::encodeInto(std::vector<uint8_t>& b, const WorldLocationStruct& v) {
    hla_encoding::appendFloat64BE(b, v.x);
    hla_encoding::appendFloat64BE(b, v.y);
    hla_encoding::appendFloat64BE(b, v.z);
}

inline WorldLocationStruct HLADecoderDetail<WorldLocationStruct>::decodeAt(const std::vector<uint8_t>& bytes, size_t& offset) {
    WorldLocationStruct v{};
    v.x = hla_decoding::readFloat64BE(bytes, offset);
    v.y = hla_decoding::readFloat64BE(bytes, offset);
    v.z = hla_decoding::readFloat64BE(bytes, offset);
    return v;
}

} // namespace rprfom
