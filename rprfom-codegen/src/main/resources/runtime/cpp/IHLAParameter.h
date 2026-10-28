#pragma once
//
// IHLAParameter.h
//
// Interface non templatee pour un PARAMETRE d'interaction HLA. Miroir
// de IHLAAttribute.h, mais sans les notions d'ownership ni de suivi
// temporel (lastUpdateTime) : une interaction HLA est un message
// envoye une fois (sendInteraction) ou recu une fois
// (receiveInteraction), pas un etat persistant possede par un federe.
// Chaque occurrence d'interaction a SA PROPRE instance de parametres,
// construite pour l'envoi ou decodee a la reception -- il n'y a donc
// pas de "dirty flag" a reinitialiser ni d'ownership a negocier.
//
// Permet, comme pour les attributs, de manipuler des parametres de
// types differents de facon generique (stockage dans une map indexee
// par handle RTI, cf. la classe d'interaction generee).
//

#include <cstdint>
#include <vector>

namespace rprfom {

class IHLAParameterBase {
public:
    virtual ~IHLAParameterBase() = default;

    // true si set() (ou decodeFrom()) a deja ete appele sur ce
    // parametre -- utile pour ne construire le ParameterHandleValueMap
    // envoye au RTI qu'avec les parametres effectivement renseignes.
    virtual bool hasValue() const = 0;

    // Serialise la valeur courante en octets prets a etre envoyes au
    // RTI (ParameterHandleValueMap). Meme principe que
    // IHLAAttributeBase::encode() : l'appelant n'a jamais besoin de
    // connaitre T.
    virtual std::vector<uint8_t> encode() const = 0;

    // Decode des octets recus du RTI (receiveInteraction) et met a jour
    // la valeur. Symetrique de encode().
    virtual void decodeFrom(const std::vector<uint8_t>& bytes) = 0;
};

} // namespace rprfom
