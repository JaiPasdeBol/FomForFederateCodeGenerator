#pragma once
//
// HLAOwnership.hpp
//
// Etat d'ownership (propriete) d'un attribut HLA, independant du type T
// de l'attribut -- exactement comme needsPublication()/hasBeenReflected().
// Modelise une version simplifiee (4 etats) du protocole standard de
// negociation d'ownership HLA (IEEE 1516) :
//
//   UNOWNED --requestAcquisition()--> ACQUIRING --confirmAcquisition()--> OWNED
//   UNOWNED <--cancelAcquisition()--  ACQUIRING
//   OWNED   --requestDivestiture()--> DIVESTING --confirmDivestiture()--> UNOWNED
//   OWNED   <--cancelDivestiture()--  DIVESTING
//
// - requestAcquisition()/requestDivestiture() : a appeler cote federate
//   juste avant (ou en meme temps que) le vrai appel RTI
//   (attributeOwnershipAcquisition, negotiatedAttributeOwnershipDivestiture).
// - confirmAcquisition()/confirmDivestiture() : a appeler depuis les
//   callbacks RTI reels une fois la confirmation recue
//   (attributeOwnershipAcquisitionNotification,
//    attributeOwnershipDivestitureNotification).
// - cancelAcquisition()/cancelDivestiture() : memes noms que les
//   services RTI correspondants (cancelAttributeOwnershipAcquisition,
//   cancelNegotiatedAttributeOwnershipDivestiture).
//

#include <string>

namespace rprfom {

enum class OwnershipState {
    Unowned,
    Owned,
    Divesting,
    Acquiring,
};

inline std::string toString(OwnershipState state) {
    switch (state) {
        case OwnershipState::Unowned:   return "Unowned";
        case OwnershipState::Owned:     return "Owned";
        case OwnershipState::Divesting: return "Divesting";
        case OwnershipState::Acquiring: return "Acquiring";
    }
    return "?";
}

} // namespace rprfom
