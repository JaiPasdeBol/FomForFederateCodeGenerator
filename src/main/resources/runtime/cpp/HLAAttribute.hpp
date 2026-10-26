#pragma once
//
// HLAAttribute.hpp
//
// Classe template générique représentant un attribut d'objet HLA/RPR-FOM.
//
// Un federate peut être, pour un attribut donné :
//   - souscripteur (reflète les valeurs reçues du RTI)      -> onReflect()
//   - propriétaire/écrivain (publie les valeurs vers le RTI) -> set()
//   - les deux à la fois, ou aucun des deux (dans ce cas l'attribut
//     garde simplement sa valeur par défaut)
//
// Ce même wrapper gère les deux rôles simultanément : il n'y a donc
// qu'une seule classe à générer par attribut, quel que soit le rôle
// que jouera le federate à l'exécution (le rôle réel est déterminé par
// les souscriptions/publications faites auprès du RTI, pas par le type
// C++ utilisé ici).
//
// HLAAttribute<T> hérite de IHLAAttributeBase (interface non
// templatée) afin de pouvoir être manipulé de façon générique, sans
// connaître T (ex. stockage dans une map<long, IHLAAttributeBase*>
// indexée par handle RTI, cf. BaseEntity). L'encodage HLA de la valeur
// (méthode encode()) délègue à HLAEncoder<T> (cf. HLAEncoder.hpp),
// spécialisable par type.
//

#include <chrono>
#include <stdexcept>

#include "HLADecoder.hpp"
#include "HLAEncoder.hpp"
#include "IHLAAttribute.h"

namespace rprfom {

template <typename T>
class HLAAttribute : public IHLAAttributeBase {
public:
    using ValueType = T;
    using Clock     = std::chrono::system_clock;
    using TimePoint = Clock::time_point;

    HLAAttribute() = default;
    explicit HLAAttribute(const T& initialValue) : value_(initialValue), hasValue_(true) {}

    // Un attribut appartient a SON objet (l'objet genere range son adresse
    // dans attributesByName_) : ni copie ni deplacement.
    HLAAttribute(const HLAAttribute&) = delete;
    HLAAttribute& operator=(const HLAAttribute&) = delete;

    // ------------------------------------------------------------------
    // Ownership (independant de T -- cf. HLAOwnership.hpp)
    // ------------------------------------------------------------------

    OwnershipState ownershipState() const override {
        return ownershipState_;
    }

    bool isOwned() const override {
        return ownershipState_ == OwnershipState::Owned;
    }

    void requestAcquisition() override {
        if (ownershipState_ != OwnershipState::Unowned) {
            throw std::logic_error(
                "requestAcquisition() invalide depuis l'etat " + toString(ownershipState_) +
                " (attendu Unowned)");
        }
        ownershipState_ = OwnershipState::Acquiring;
    }

    void confirmAcquisition() override {
        if (ownershipState_ != OwnershipState::Acquiring) {
            throw std::logic_error(
                "confirmAcquisition() invalide depuis l'etat " + toString(ownershipState_) +
                " (attendu Acquiring)");
        }
        ownershipState_ = OwnershipState::Owned;
    }

    void cancelAcquisition() override {
        if (ownershipState_ != OwnershipState::Acquiring) {
            throw std::logic_error(
                "cancelAcquisition() invalide depuis l'etat " + toString(ownershipState_) +
                " (attendu Acquiring)");
        }
        ownershipState_ = OwnershipState::Unowned;
    }

    void requestDivestiture() override {
        if (ownershipState_ != OwnershipState::Owned) {
            throw std::logic_error(
                "requestDivestiture() invalide depuis l'etat " + toString(ownershipState_) +
                " (attendu Owned)");
        }
        ownershipState_ = OwnershipState::Divesting;
    }

    void confirmDivestiture() override {
        if (ownershipState_ != OwnershipState::Divesting) {
            throw std::logic_error(
                "confirmDivestiture() invalide depuis l'etat " + toString(ownershipState_) +
                " (attendu Divesting)");
        }
        ownershipState_ = OwnershipState::Unowned;
    }

    void cancelDivestiture() override {
        if (ownershipState_ != OwnershipState::Divesting) {
            throw std::logic_error(
                "cancelDivestiture() invalide depuis l'etat " + toString(ownershipState_) +
                " (attendu Divesting)");
        }
        ownershipState_ = OwnershipState::Owned;
    }

    void forceOwnershipState(OwnershipState state) override {
        ownershipState_ = state;
    }

    // ------------------------------------------------------------------
    // Côté écrivain (le federate possède/publie cet attribut)
    // ------------------------------------------------------------------

    // Modifie la valeur locale et marque l'attribut comme "à publier".
    // GARDE PAR L'OWNERSHIP : leve une exception si l'attribut n'est pas
    // actuellement OWNED -- le RTI rejetterait de toute facon un
    // updateAttributeValues pour un attribut non possede, autant
    // echouer tot et localement.
    void set(const T& newValue) {
        if (ownershipState_ != OwnershipState::Owned) {
            throw std::logic_error(
                "set() impossible : attribut non possede (ownershipState=" +
                toString(ownershipState_) + ", attendu Owned)");
        }
        value_ = newValue;
        needsPublication_ = true;
        hasValue_ = true;
    }

    // true si l'attribut porte une valeur (set(), reflect recu, ou valeur
    // initiale) -- cf. IHLAAttributeBase::hasValue().
    bool hasValue() const override {
        return hasValue_;
    }

    // À appeler une fois la mise à jour effectivement envoyée au RTI
    // (updateAttributeValues) pour réarmer le flag.
    void markPublished() override {
        needsPublication_ = false;
    }

    // true si la valeur a changé depuis la dernière publication.
    bool needsPublication() const override {
        return needsPublication_;
    }

    // ------------------------------------------------------------------
    // Côté souscripteur (le federate reçoit des reflectAttributeValues)
    // ------------------------------------------------------------------

    // À appeler depuis le callback reflectAttributeValues du fédéré.
    void onReflect(const T& newValue, TimePoint timestamp = Clock::now()) {
        value_ = newValue;
        lastUpdateTime_ = timestamp;
        hasBeenReflectedAtLeastOnce_ = true;
        hasValue_ = true;
    }

    // Horodatage (temps federate/local) de la dernière valeur reçue par
    // réflexion. Retourné par valeur : c'est la signature imposée par
    // IHLAAttributeBase::lastUpdateTime().
    TimePoint lastUpdateTime() const override {
        return lastUpdateTime_;
    }

    // true si au moins une valeur a été reçue depuis le RTI.
    bool hasBeenReflected() const override {
        return hasBeenReflectedAtLeastOnce_;
    }

    // Décode des octets reçus du RTI et applique le résultat via
    // onReflect(). Symétrique de encode() : délègue à HLADecoder<T>
    // (cf. HLADecoder.hpp), le point d'extension pour le décodage HLA
    // spécialisable par type.
    void reflectRaw(const std::vector<uint8_t>& bytes, TimePoint timestamp = Clock::now()) override {
        onReflect(HLADecoder<T>::decode(bytes), timestamp);
    }

    // Sérialise la valeur courante en octets, pour publication générique
    // via la map<long, IHLAAttributeBase*> (cf. IHLAAttribute.h).
    //
    // Délègue à HLAEncoder<T> (cf. HLAEncoder.hpp), le point
    // d'extension spécialisable par type pour un encodage HLA (IEEE
    // 1516.2) conforme. Si aucune spécialisation HLAEncoder<T> n'existe
    // pour ce T, la compilation échoue explicitement (cf. le
    // static_assert de la primaire) plutôt que de produire un encodage
    // silencieusement incorrect.
    std::vector<uint8_t> encode() const override {
        return HLAEncoder<T>::encode(value_);
    }

    // ------------------------------------------------------------------
    // Accès commun à la valeur
    // ------------------------------------------------------------------

    const T& get() const { return value_; }
    T&       get()       { return value_; }

    operator const T&() const { return value_; }

private:
    T value_{};
    bool needsPublication_{false};
    bool hasValue_{false};

    TimePoint lastUpdateTime_{};
    bool hasBeenReflectedAtLeastOnce_{false};

    OwnershipState ownershipState_{OwnershipState::Unowned};
};

} // namespace rprfom
