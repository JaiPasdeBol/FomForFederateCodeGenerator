#pragma once
//
// IHLAAttribute.h
//
// Interface non templatée exposant les opérations d'un HLAAttribute<T>
// qui ne dépendent pas de T. Permet de manipuler des attributs de types
// différents (EntityTypeStruct, MarkingStruct, bool, ...) de façon
// générique, notamment pour les stocker dans un même conteneur (ex. une
// map indexée par handle RTI).
//
// La valeur elle-même (get()/set()/onReflect()) reste accessible
// uniquement via le HLAAttribute<T> concret, puisque son type dépend de
// T et ne peut donc pas figurer dans une interface non templatée.
//

#include <chrono>
#include <cstdint>
#include <vector>

#include "HLAOwnership.hpp"

namespace rprfom {

class IHLAAttributeBase {
public:
    virtual ~IHLAAttributeBase() = default;

    // ------------------------------------------------------------------
    // Ownership (independant de T -- cf. HLAOwnership.hpp)
    // ------------------------------------------------------------------
    virtual OwnershipState ownershipState() const = 0;
    virtual bool isOwned() const = 0;

    virtual void requestAcquisition() = 0;
    virtual void confirmAcquisition() = 0;
    virtual void cancelAcquisition() = 0;

    virtual void requestDivestiture() = 0;
    virtual void confirmDivestiture() = 0;
    virtual void cancelDivestiture() = 0;

    // Positionne directement l'etat d'ownership SANS passer par la
    // machine a etats (pas de verification de transition). Reserve a
    // l'initialisation (ex. seeder l'ownership depuis le tag
    // "InitialOwner" du FOM) ou aux tests -- ne pas utiliser en
    // fonctionnement normal, prefer les transitions ci-dessus.
    virtual void forceOwnershipState(OwnershipState state) = 0;

    // ------------------------------------------------------------------
    // Cote ecrivain
    // ------------------------------------------------------------------
    virtual bool needsPublication() const = 0;
    virtual void markPublished() = 0;

    // true si l'attribut porte une valeur : set() deja appele, ou valeur
    // recue du RTI (onReflect/reflectRaw), ou valeur initiale fournie au
    // constructeur. Equivalent C++ du "get() != null" cote Java : sert a
    // n'envoyer (updateAll, provideAttributeValueUpdate) que des valeurs
    // reellement renseignees.
    virtual bool hasValue() const = 0;

    // Sérialise la valeur courante en octets prêts à être envoyés au RTI
    // (AttributeHandleValueMap). C'est la clé pour publier de façon
    // générique via la map<long, IHLAAttributeBase*> : cette map ne
    // connaît pas T, mais chaque HLAAttribute<T> sait encoder sa propre
    // valeur, donc l'appelant n'a jamais besoin de connaître T.
    virtual std::vector<uint8_t> encode() const = 0;

    // ------------------------------------------------------------------
    // Côté souscripteur
    // ------------------------------------------------------------------
    virtual bool hasBeenReflected() const = 0;
    virtual std::chrono::system_clock::time_point lastUpdateTime() const = 0;

    // Symétrique de encode() : décode des octets reçus du RTI
    // (reflectAttributeValues) et met à jour la valeur + l'horodatage.
    // Comme pour encode(), l'appelant (ex. une boucle générique sur
    // attributesByHandle()) n'a jamais besoin de connaître T : c'est
    // HLAAttribute<T> qui sait décoder sa propre valeur via
    // HLADecoder<T> (cf. HLADecoder.hpp).
    virtual void reflectRaw(const std::vector<uint8_t>& bytes,
                            std::chrono::system_clock::time_point timestamp =
                                std::chrono::system_clock::now()) = 0;
};

} // namespace rprfom
