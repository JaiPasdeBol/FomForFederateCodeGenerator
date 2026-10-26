package rprfom;

/**
 * OwnershipState.java
 *
 * Miroir exact de HLAOwnership.hpp. Etat d'ownership (propriete) d'un
 * attribut HLA, independant du type T de l'attribut. Modelise une
 * version simplifiee (4 etats) du protocole standard de negociation
 * d'ownership HLA (IEEE 1516) :
 *
 *   UNOWNED --requestAcquisition()--> ACQUIRING --confirmAcquisition()--> OWNED
 *   UNOWNED <--cancelAcquisition()--  ACQUIRING
 *   OWNED   --requestDivestiture()--> DIVESTING --confirmDivestiture()--> UNOWNED
 *   OWNED   <--cancelDivestiture()--  DIVESTING
 */
public enum OwnershipState {
    UNOWNED,
    OWNED,
    DIVESTING,
    ACQUIRING
}
