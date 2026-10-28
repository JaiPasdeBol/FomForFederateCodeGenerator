/**
 * rprfom-runtime-api : contrat Java PARTAGE entre le code genere par
 * rprfom-codegen et les couches qui l'utilisent (HLAbstract...).
 *
 * <p>Le code genere copie son runtime (HLAAttribute, HLAEncoder...) dans le package
 * de chaque federe ; seuls les types de CE package sont communs a tous : une
 * classe generee pour le Radar et une autre pour le Rafale implementent les memes
 * interfaces. Aucune dependance externe.
 */
package rprfom.api;
