#pragma once
//
// HLAParameter.hpp
//
// Classe template generique representant un PARAMETRE d'interaction
// HLA/RPR-FOM. Miroir de HLAAttribute.hpp, avec deux differences
// assumees (une interaction n'est pas un etat persistant possede) :
//   - pas d'ownership (pas de OwnershipState, pas de garde sur set())
//   - pas de suivi temporel par parametre (pas de lastUpdateTime ;
//     l'horodatage d'une interaction se gere au niveau de l'occurrence
//     entiere, pas parametre par parametre)
//
// L'encodage/decodage (HLAEncoder<T>/HLADecoder<T>) et le mecanisme de
// type erasure (IHLAParameterBase) sont strictement les memes que pour
// HLAAttribute<T>.
//

#include "HLADecoder.hpp"
#include "HLAEncoder.hpp"
#include "IHLAParameter.h"

namespace rprfom {

template <typename T>
class HLAParameter : public IHLAParameterBase {
public:
    using ValueType = T;

    HLAParameter() = default;
    explicit HLAParameter(const T& initialValue) : value_(initialValue), hasValue_(true) {}

    // Un parametre appartient a SON interaction (adresse rangee dans
    // parametersByName_) : ni copie ni deplacement.
    HLAParameter(const HLAParameter&) = delete;
    HLAParameter& operator=(const HLAParameter&) = delete;

    // Modifie la valeur locale (pas de garde d'ownership : un parametre
    // d'interaction n'a pas de proprietaire).
    void set(const T& newValue) {
        value_ = newValue;
        hasValue_ = true;
    }

    bool hasValue() const override {
        return hasValue_;
    }

    const T& get() const { return value_; }
    T&       get()       { return value_; }

    operator const T&() const { return value_; }

    std::vector<uint8_t> encode() const override {
        return HLAEncoder<T>::encode(value_);
    }

    void decodeFrom(const std::vector<uint8_t>& bytes) override {
        value_ = HLADecoder<T>::decode(bytes);
        hasValue_ = true;
    }

private:
    T value_{};
    bool hasValue_{false};
};

} // namespace rprfom
