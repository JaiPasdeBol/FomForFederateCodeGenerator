# rprfom-codegen

Génère des bibliothèques **C++** et **Java** (`HLAAttribute<T>`, ownership,
map générique par handle, `HLAEncoder`/`HLADecoder` avec encodage HLA
conforme IEEE 1516.2) à partir d'un ou plusieurs fichiers **FOM XML**
(format OMT SISO-STD-004.1, celui de la RPR-FOM et de ses extensions),
y compris des fichiers d'enrichissement personnalisés qui ajoutent de
nouvelles classes sous des classes RPR-FOM existantes.

C'est un miroir automatisé de tout ce qu'on a construit à la main pour
`Aircraft` : mêmes noms, même architecture, appliqués maintenant à
**tout** objectClass présent dans le(s) FOM fourni(s).

## Build et exécution

Le projet a deux modules Maven (voir [« API d'exécution partagée »](#api-dexécution-partagée-rprfom-runtime-api)) :
`rprfom-runtime-api` (les interfaces communes au code Java généré) et `rprfom-codegen`
(le générateur). Les deux se construisent et s'installent depuis la racine :

```
mvn clean install
java -jar rprfom-codegen/target/rprfom-codegen.jar \
    --fom RPR-Base.xml \
    --fom RPR-Physical.xml \
    --fom MonEnrichissement.xml \
    --out-cpp ./out/cpp \
    --out-java ./out/java \
    --java-package com.example.rprfom
```

- `--fom` : répétable, un par fichier FOM XML à fusionner (ordre
  indifférent pour les types de données ; pour les `objectClass`, les
  fichiers sont fusionnés par correspondance de nom à chaque niveau de
  la hiérarchie imbriquée, donc un fichier d'enrichissement peut
  accrocher de nouvelles classes sous `Aircraft`, `GroundVehicle`, etc.
  définis dans un autre fichier).
- `--out-cpp` : dossier de sortie C++ (crée `include/rprfom/*.hpp`).
- `--out-java` : dossier de sortie Java, projet Maven complet
  (`pom.xml` + `src/main/java/<package>/*.java`). Voir plus bas
  [« Organisation du code Java généré »](#organisation-du-code-java-généré)
  pour le détail des deux sous-dossiers/packages produits.
- `--java-package` : package Java des classes générées (défaut `rprfom`).
- `--skip-xsd-validation` : désactive la validation XSD (cf. section
  dédiée ci-dessous) — déconseillé, mais utile si un fichier FOM
  personnalisé s'écarte légitimement du schéma d'une façon que le
  générateur tolère quand même.
- `--federate-fom` : optionnel, cf. section dédiée
  [« Génération ciblée pour un fédéré »](#génération-ciblée-pour-un-fédéré---federate-fom)
  ci-dessous — ne génère que les classes nécessaires à un fédéré
  particulier plutôt que la totalité du(des) FOM fourni(s).
- `--out-cpp-unreal` : optionnel, cf. section dédiée
  [« Sortie Unreal Engine »](#sortie-unreal-engine---out-cpp-unreal--couche-de-présentation-blueprint)
  ci-dessous — génère une couche de présentation Blueprint (`.h`/`.cpp`) pour le
  plugin Unreal PluginUnrealHLA, à partir du même FOM/filtrage fédéré.

## Organisation du code généré (C++ et Java)

Les deux générateurs séparent, chacun dans deux sous-dossiers distincts,
« les classes générées à partir du FOM » de « la mécanique d'encodage
centralisée » — même principe des deux côtés, adapté aux contraintes de
chaque langage.

### Côté Java

Le projet Java produit dans `--out-java` sépare, dans deux sous-dossiers
(et donc deux packages Java) distincts sous `src/main/java/` :

- **`<package>/`** — uniquement ce qui reflète directement le contenu
  du(des) fichier(s) FOM fourni(s) : `HLATypes.java` (les types de
  données déclarés dans le FOM — enums, structs, tableaux, variant
  records) et une classe par `objectClass`/`interactionClass`
  (`Radio.java`, `EmitterSystem.java`, `AggregateEntity.java`, etc.).
  C'est le dossier à regarder pour comprendre le modèle de données d'un
  FOM donné.
- **`<package>/runtime/`** — toute la mécanique d'encodage/décodage HLA
  centralisée, commune à n'importe quel FOM et jamais spécifique à l'un
  d'eux : `HLAEncoding.java`/`HLADecoding.java` (fonctions bas niveau,
  y compris le padding sur les octet boundaries IEEE 1516.2),
  `HLAAlignment.java` (table des octet boundaries par type),
  `HLAEncoder.java`/`HLADecoder.java` (registre par type qui orchestre
  l'encodage), `HLAAttribute.java`/`HLAParameter.java`, ainsi que les représentations de
  base (`HLAOctet.java`, `HLAUnsignedInteger16/32/64(LE).java`,
  `HLAInteger16/32/64LE.java`, `HLAFloat32/64LE.java`,
  `HLAOctetPair.java`, `HLAASCIIString.java`). Certains de ces fichiers
  sont copiés tels quels depuis les ressources du générateur, d'autres
  (`HLAAlignment`/`HLAEncoder`/`HLADecoder`) sont générés à partir du
  FOM (ils doivent connaître ses types), mais aucun des deux groupes ne
  contient de classe métier.

Les **interfaces** communes (`IHLAAttributeBase`, `IHLAParameterBase`,
`OwnershipState`, `HLAObjectClass`, `HLAInteractionClass`) ne sont pas copiées :
elles viennent du jar partagé `rprfom-runtime-api` (package fixe `rprfom.api`),
dont dépend le projet Java généré.

Les classes du package racine importent le package `runtime` (`import
<package>.runtime.*;`) pour rester utilisables normalement — `Radio`
expose toujours `new HLAAttribute<>(...)`, `HLAOctet`, etc. sans
préfixe. Cette séparation ne change rien au comportement à l'exécution
(c'est un simple découpage de fichiers/packages).

### Côté C++

Le projet C++ produit dans `--out-cpp` applique le même découpage, à
l'intérieur d'un seul et même dossier `include/rprfom/` :

- **`include/rprfom/`** — `HLATypes.hpp` et un `.hpp` par
  `objectClass`/`interactionClass` (`Radio.hpp`, `EmitterSystem.hpp`,
  `AggregateEntity.hpp`, etc.), c'est-à-dire tout ce qui reflète
  directement le FOM.
- **`include/rprfom/runtime/`** — `HLAAttribute.hpp`/`IHLAAttribute.h`,
  `HLAParameter.hpp`/`IHLAParameter.h`, `HLAOwnership.hpp`,
  `HLAEncoding.hpp`/`HLADecoding.hpp` (fichiers statiques copiés depuis
  les ressources du générateur), ainsi que `HLAAlignment.hpp`,
  `HLAEncoder.hpp` et `HLADecoder.hpp` (générés à partir du FOM mais
  sans classe métier).

Contrairement au Java, le C++ n'impose aucune correspondance entre
namespace et répertoire : les deux dossiers restent dans le même
namespace `rprfom` (`HLAAttribute`, `HLAOctet`, etc. s'utilisent sans
préfixe, exactement comme avant). Seuls les chemins des `#include`
changent en conséquence — par exemple `Radio.hpp` inclut désormais
`"runtime/HLAAttribute.hpp"` plutôt que `"HLAAttribute.hpp"`, et
`HLAAlignment.hpp` inclut `"../HLATypes.hpp"` plutôt que
`"HLATypes.hpp"`. Un projet qui compile déjà avec `-I <dossier>/include`
n'a rien d'autre à changer.

## API d'exécution partagée (`rprfom-runtime-api`)

Le runtime Java est copié dans le package de **chaque** projet généré
(`fr.hlabstract.radar.fom.runtime`, `fr.hlabstract.rafale.fom.runtime`…) : deux
`HLAAttribute` générés pour deux fédérés sont donc deux classes différentes. Une
couche qui doit manipuler n'importe quelle classe générée sans la connaître à la
compilation, comme HLAbstract, a besoin d'un contrat **commun**. C'est le rôle du
module `rprfom-runtime-api` (`com.rprfom:rprfom-runtime-api`, package fixe
`rprfom.api`, jamais réécrit, aucune dépendance) :

| Interface | Implémentée par | Rôle |
|---|---|---|
| `HLAObjectClass` | la classe d'objet racine générée (ex. `BaseEntity`), donc toutes ses sous-classes | `hlaObjectClassName()` (nom FOM complet, redéfini par chaque classe) et `attributesByName()` (attributs de l'instance par nom FOM, héritage compris, en lecture seule) |
| `HLAInteractionClass` | la classe d'interaction racine générée | `hlaInteractionClassName()`, `parametersByName()` |
| `IHLAAttributeBase` | `HLAAttribute<T>` | encodage, décodage, publication, ownership, `hasValue()` |
| `IHLAParameterBase` | `HLAParameter<T>` | `hasValue()`, `encode()`, `decodeFrom()` |
| `OwnershipState` | — | état d'ownership d'un attribut (`UNOWNED`, `OWNED`, `DIVESTING`, `ACQUIRING`) |

Seule exigence que l'interface ne peut pas exprimer : chaque classe générée garde
un constructeur public sans argument.

Conséquences :
- le `pom.xml` du projet Java généré dépend de `rprfom-runtime-api` ; un projet qui
  compile directement les sources générées (comme les fédérés HLAbstract) doit
  l'avoir sur son classpath (HLAbstract le transmet) ;
- le générateur et son API partagent la même version : régénérer le code après
  toute mise à jour du générateur ;
- le code qui importait `<package>.runtime.OwnershipState` (ou `IHLAAttributeBase`,
  `IHLAParameterBase`) doit importer `rprfom.api.OwnershipState` ;
- le C++ et la sortie Unreal ne sont pas concernés.

## Génération ciblée pour un fédéré (`--federate-fom`)

Par défaut (sans `--federate-fom`), l'outil génère une classe pour
**chaque** `objectClass`/`interactionClass` du (des) FOM fourni(s) — c'est
le comportement historique, inchangé.

`--federate-fom <fichier.xml>` restreint la génération aux seules classes
nécessaires à un fédéré particulier, à partir d'un fichier décrivant son
périmètre (`FOMObject`/`FOMInteraction`, format produit par l'outil
FOMProvider, conforme à `federatesFOMProvider.xsd`) :

```
java -jar rprfom-codegen/target/rprfom-codegen.jar \
    --fom RPR-Base.xml --fom RPR-Physical.xml --fom MonEnrichissement.xml \
    --out-cpp ./out/cpp --out-java ./out/java --java-package com.example.rprfom \
    --federate-fom RafaleCockpit.xml
```

```xml
<FederateFOMProvider FederateName="RafaleCockpit">
  <FOMObject name="RafaleAvionique">
    <attribute>Callsign</attribute>
    <attribute>Timestamp</attribute>
    <attribute>EntityIdentifier</attribute>  <!-- hérité de BaseEntity -->
    <attribute>HatchState</attribute>        <!-- hérité de Platform -->
  </FOMObject>
  <FOMObject name="PhysicalEntity">
    <attribute>DamageState</attribute>
  </FOMObject>
  <FOMInteraction name="MunitionDetonation"/>
</FederateFOMProvider>
```

Deux règles, respectées ensemble (voir `FederateScopeFilter`) :

1. **Les classes ancêtres non listées sont générées comme des coquilles
   structurelles, jamais avec tous leurs attributs.** Si le fédéré
   s'abonne à un objet enfant (ici `RafaleAvionique`, au bout de
   `BaseEntity → PhysicalEntity → Platform → Aircraft → Rafale →
   RafaleAvionique`), toute la chaîne de ses ancêtres est générée — c'est
   nécessaire pour que la hiérarchie d'héritage C++/Java reste valide —
   mais **uniquement** avec les attributs que le fichier fédéré liste
   réellement (et qui se résolvent vers cette classe précise, cf. règle
   2). Un ancêtre vers lequel rien ne se résout est généré comme une
   classe vide (aucun attribut propre) : c'est le cas d'`Aircraft` et
   `Rafale` dans l'exemple ci-dessus, dont aucun attribut n'est demandé
   par le fédéré. Même principe pour les `interactionClass`
   (`MunitionDetonation` tire `WeaponFire`, avec tous ses paramètres —
   les interactions n'ont pas de filtrage par paramètre, cf. plus bas). À
   l'inverse, une classe qui n'est ni demandée ni ancêtre d'une classe
   demandée n'est pas générée du tout.
2. **Un attribut hérité est placé sur la classe qui le déclare
   réellement dans la FOM.** Le fichier fédéré liste `EntityIdentifier`
   et `HatchState` sous `RafaleAvionique`, mais ces attributs sont en
   réalité déclarés respectivement sur `BaseEntity` et `Platform` dans la
   FOM (héritage) : le générateur les résout vers leur classe d'origine
   plutôt que de dupliquer un champ sous `RafaleAvionique` — c'est donc
   `BaseEntity` qui se retrouve avec `EntityIdentifier` comme unique
   attribut généré (`EntityType`/`Spatial`, non demandés, disparaissent),
   et `Platform` avec `HatchState` comme unique attribut
   (`AfterburnerOn` disparaît). `PhysicalEntity`, listé pour lui-même, ne
   garde que `DamageState` (ses autres attributs FOM — `ForceIdentifier`,
   `Marking`, `ArticulatedParametersArray` — disparaissent aussi).

Les `interactionClass` n'ont pas de notion d'attribut dans
`federatesFOMProvider.xsd` (seulement un nom) : une interaction demandée
(ou tirée comme ancêtre) est donc toujours générée avec tous ses
paramètres — il n'y a pas d'équivalent de la règle 1 à leur niveau.

Un objet, un attribut ou une interaction du fichier fédéré introuvable
dans la FOM fusionnée produit un avertissement non bloquant (ligne
`[avertissement]`) plutôt qu'un arrêt — la génération continue avec le
reste du périmètre.

Un exemple complet est fourni dans `test-fom/FederateExample.xml` (à
utiliser avec les fichiers de `test-fom/` déjà cités dans la section
Tests) ; `test-fom/FederateBad.xml` illustre les avertissements sur
objet/attribut/interaction inconnus.

**`--federate-fom` est validé contre `federatesFOMProvider.xsd`**
(bundlé dans `src/main/resources/schema/`, comme les schémas
`IEEE1516-DIF-2025.xsd`/`IEEE1516-DIF-2010.xsd` pour les fichiers
`--fom`) avant génération, avec le même mécanisme que
la validation FOM ci-dessous (erreurs bloquantes avec ligne/colonne,
désactivable via `--skip-xsd-validation`). Ce schéma, tel que fourni à
l'origine par le projet FOMProvider, référençait ses propres types
complexes sans le préfixe `tns:` (`<xsd:element name="FOMObject"
type="FederateFOMObject" .../>` au lieu de `type="tns:FederateFOMObject"`),
ce qui le faisait rejeter dès le chargement par un validateur XSD W3C
strict (`javax.xml.validation`), avant même d'examiner un document
d'instance — corrigé (les deux références de type portent maintenant le
préfixe `tns:`), vérifié en pratique (chargement du schéma, validation
d'un fichier conforme, et rejet avec message ligne/colonne d'un fichier
qui ne respecte pas l'ordre `FOMObject*` puis `FOMInteraction*`).

## Validation XSD des fichiers FOM en entrée

Chaque fichier passé via `--fom` est validé contre le schéma officiel
"Data Interchange Format" de la norme IEEE 1516.2, bundlé dans
`src/main/resources/schema/`, avant toute génération. Ce schéma est
choisi plutôt que le plus strict `IEEE1516-OMT-*.xsd` car il est
explicitement conçu pour accepter des fichiers individuellement
incomplets — exactement le cas d'un module RPR-FOM pris isolément ou
d'un fichier d'enrichissement qui ne redéfinit qu'une partie du modèle.
Voir la discussion complète dans l'historique du projet pour le détail
des différences entre les deux schémas (contraintes d'intégrité
référentielle `xs:key`/`xs:keyref`, éléments rendus obligatoires).

**Deux révisions du schéma DIF sont bundlées et reconnues
automatiquement** : `IEEE1516-DIF-2025.xsd` (norme IEEE 1516.2-2025,
namespace `http://standards.ieee.org/IEEE1516-2025`) et
`IEEE1516-DIF-2010.xsd` (norme IEEE 1516.2-2010, namespace
`http://standards.ieee.org/IEEE1516-2010`, conservé pour compatibilité
ascendante). Avant de valider un fichier, le générateur lit uniquement
le namespace déclaré par son élément racine `<objectModel>` (lecture
StAX minimale, sans validation) et sélectionne le schéma correspondant
— aucune option n'est nécessaire. C'est ce qui permet d'ingérer tel
quel un FOM officiel non retouché comme
`RPR_FOM_v2.0_1516-2010.xml` (qui déclare le namespace 2010 et ne sera
vraisemblablement jamais republié) au sein d'un même jeu de fichiers
`--fom` que des fichiers plus récents déclarant le namespace 2025 : le
message `Validation XSD : N fichier(s) OK (X via IEEE1516-DIF-2025.xsd,
Y via IEEE1516-DIF-2010.xsd)` indique alors combien de fichiers ont été
reconnus sous chaque révision. Un fichier dont l'élément racine ne
déclare aucun namespace, ou un namespace différent de ces deux-là,
produit une erreur bloquante explicite (`namespace de l'element racine
absent/inconnu`) plutôt que le message XSD générique et peu clair
`Cannot find the declaration of element 'objectModel'`.

Différences structurelles entre les deux révisions du schéma DIF (à
part le namespace) : `IEEE1516-DIF-2025.xsd` ajoute, dans
`<dataTypes>`, un élément optionnel `<referenceDataTypes>` (types de
données référençant des instances d'objet), inséré entre
`<simpleDataTypes>` et `<enumeratedDataTypes>`, exprime l'énumération
des encodages de tableau (`HLAfixedArray`/`HLAvariableArray`) via
`xs:pattern` plutôt que `xs:enumeration`, et retire le switch
`<conveyProducingFederate>` (présent dans `<switches>` en 2010, disparu
en 2025) — trois différences sans impact sur ce générateur :
`FomXmlParser` ne lit ni `<dataTypes>`/`<referenceDataTypes>` ni
`<switches>`, et les valeurs littérales d'encodage acceptées sont
identiques dans les deux cas.

En cas d'erreur bloquante, le message indique la ligne, la colonne et
la cause exacte, et la génération s'arrête avant de produire quoi que
ce soit. Certaines non-conformités précises, connues et sans
conséquence, sont dégradées en avertissement non bloquant ou tolérées
d'office plutôt que de forcer `--skip-xsd-validation` pour tout le
fichier :

- le champ `<reference><identification>`, typé `xs:anyURI` par le
  schéma (dans les deux révisions), contient en pratique du texte libre
  bibliographique (constaté dans le fichier RPR-FOM 2.0 officiel
  lui-même) — ce champ n'est de toute façon jamais lu par
  `FomXmlParser`, qui ne s'intéresse qu'à `<dataTypes>`, `<objects>` et
  `<interactions>` ;
- le RPR-FOM 2.0 officiel porte aussi, sur plusieurs `<objectClass>` /
  `<attribute>` / `<glyph>`, des attributs XML additionnels sans
  namespace non déclarés par le schéma (`notes="..."` sur
  objectClass/attribute, `type=`/`height=`/`width=` sur glyph — vraisemblablement
  des métadonnées internes de l'outil qui a produit ce fichier). Plutôt
  que de cataloguer un à un chaque nom d'attribut parasite, les deux
  schémas bundlés acceptent désormais n'importe quel attribut
  supplémentaire non déclaré, sur n'importe quel élément (joker
  `xs:anyAttribute` du groupe `commonAttributes`, élargi de
  `namespace="##other"` à `namespace="##any" processContents="skip"`)
  — ces attributs ne sont de toute façon jamais lus par `FomXmlParser`,
  qui ignore entièrement les attributs XML ;
- `RPR-Base_v3.0.xml` (RPR-FOM v3.0/2025 officiel, cf. section dédiée
  plus bas) porte, dans sa section `<time>`, les noms d'éléments hérités
  `<timeStamp>`/`<lookahead>` plutôt que `<logicalTime>`/
  `<logicalTimeInterval>` attendus par les deux révisions du schéma DIF
  — cette section n'est de toute façon jamais lue par `FomXmlParser`
  (elle ne produit aucune classe), donc ce rejet précis est dégradé en
  avertissement non bloquant plutôt que de forcer
  `--skip-xsd-validation` pour tout le fichier.

**Point important découvert en pratique** : l'ordre des éléments compte
dans ce schéma (`<objects>`/`<interactions>` doivent précéder
`<dataTypes>` ; à l'intérieur de `<dataTypes>`, l'ordre est
`basicDataRepresentations` → `simpleDataTypes` → `referenceDataTypes`
(uniquement dans la révision 2025, optionnel) → `enumeratedDataTypes`
→ `arrayDataTypes` → `fixedRecordDataTypes` → `variantRecordDataTypes`),
et le namespace de l'`<objectModel>` doit correspondre exactement à
l'une des deux révisions reconnues (cf. ci-dessus). `FomXmlParser`
lui-même est tolérant à l'ordre des éléments et ignore le namespace
(il résout les types par nom), mais un fichier qui viole l'ordre du
schéma sera désormais rejeté par cette étape de validation avant même
d'atteindre le parseur.

## Ce qui est généré vs copié tel quel

| Fichier | C++ | Java | Origine |
|---|---|---|---|
| `HLAAttribute` | `.hpp` | `.java` | statique (copié depuis les ressources) |
| `IHLAAttribute(Base)` | `.h` | `.java` | C++ : statique ; Java : jar `rprfom-runtime-api` |
| `HLAEncoding` / `HLADecoding` | `.hpp` | `.java` | statique |
| `HLAOwnership` / `OwnershipState` | `.hpp` | `.java` | C++ : statique ; Java : jar `rprfom-runtime-api` |
| `HLAOctet`, `HLAUnsignedInteger16/32` | — | `.java` | statique (Java uniquement, cf. plus bas) |
| `HLATypes` | `.hpp` | `.java` | **généré** depuis le FOM |
| `HLAAlignment` | `.hpp` | `.java` | **généré** |
| `HLAEncoder` / `HLADecoder` | `.hpp` | `.java` | **généré** |
| Une classe par `objectClass` | `.hpp` | `.java` | **généré** |

## Pourquoi le Java a 3 classes de plus que le C++

Java efface les génériques à l'exécution : `HLAEncoder`/`HLADecoder`
dispatchent donc par `Class<?>` (un registre), pas par spécialisation de
template comme en C++. Deux représentations HLA différentes ne doivent
jamais partager la même classe Java dans ce registre. Java n'ayant pas
d'entiers non signés, les représentations `HLAoctet`,
`HLAunsignedInteger16BE` et `HLAunsignedInteger32BE` ont donc besoin
d'une petite classe dédiée (`HLAOctet`, `HLAUnsignedInteger16`,
`HLAUnsignedInteger32`) plutôt que de réutiliser `Byte`/`Short`/`Integer`
qui sont déjà pris par d'autres représentations signées. Côté C++, ce
problème n'existe pas (`int16_t` et `uint16_t` sont deux types distincts
nativement).

## Interopérabilité avec d'autres RTI (Pitch, etc.) et d'autres fédérés

L'encodage/décodage se fait entièrement côté application (dans le code
généré), pas dans le RTI : un RTI (Pitch, MÄK, CERTI, Portico, ...) ne
fait que transporter des octets opaques
(`AttributeHandleValueMap`/`ParameterHandleValueMap`). Deux conditions
doivent donc être réunies pour interopérer avec des fédérés tiers :

1. **Les handles** : `bindAttributeHandle`/`bindParameterHandle`
   attendent de VRAIS handles obtenus via
   `RTIambassador::getAttributeHandle(...)`/`getParameterHandle(...)`
   après avoir rejoint la fédération. Ce code de câblage RTI (join,
   publish/subscribe, boucle d'appels) n'est **pas généré** par cet
   outil, il reste à écrire. Tant que tous les fédérés chargent le
   même FOM, HLA garantit que le nom d'un attribut résout vers le même
   handle pour tout le monde -- ce n'est donc qu'une question de code
   à écrire, pas un problème de fond.
2. **Le format des octets** : ils doivent respecter les règles
   d'encodage IEEE 1516.2 pour qu'un décodeur tiers (Pitch, ou un autre
   générateur conforme) les interprète correctement.

Un audit du point 2 a révélé et corrigé un vrai défaut de conformité en
cours de route : un champ composé (struct/enum/variant imbriqué) n'était
pas aligné sur son propre *octet boundary* avant d'être écrit quand il
n'était pas le premier champ de sa structure parente -- l'omission
restait invisible dans nos propres tests (encodeur et décodeur
généraient la même erreur, donc cohérents entre eux) mais aurait
désynchronisé un décodeur tiers strictement conforme. Corrigé et vérifié
avec un test dédié (`PaddingTest.xml`) prouvant l'insertion effective du
padding, à l'octet près, identique en C++ et en Java.

**Honnêteté sur les limites de cette vérification** : je n'ai pas fait
un audit ligne à ligne du texte intégral d'IEEE 1516.2 (je n'y ai pas
accès ici) -- l'essentiel des règles d'encodage a été vérifié par
recoupement (documentation CERTI, PyHLA, implémentations tierces) et
par un défaut réel trouvé et corrigé, mais je ne peux pas garantir à
100% une conformité exhaustive sur tous les cas de figure possibles
(HLAoctetPairBE/LE notamment reste une simplification assumée, cf.
ci-dessous). **Avant une mise en production dans une fédération
multi-fournisseurs, un test d'interopérabilité réel contre Pitch (ou
le RTI cible) reste recommandé.**

## Validé contre le fichier RPR-FOM 2.0 officiel (SISO-STD-001-2015)

Au-delà des fichiers FOM de test écrits à la main pour ce projet, le
générateur a été testé contre le fichier RPR-FOM 2.0 complet et réel
(`RPR_FOM_v2_0_1516-2010.xml`, ~25 700 lignes, 282 types de données,
63 `objectClass`, 78 `interactionClass`) : **génération, compilation
et round-trip C++/Java réussis de bout en bout**, avec des octets
strictement identiques entre les deux langages sur les cas testés.
Ce test grandeur nature a révélé plusieurs écarts réels entre ce que
prévoyait le générateur et ce que contient une FOM officielle publiée,
tous corrigés :

- **Représentations `RPR`-préfixées** (`RPRunsignedInteger8/16/32/64BE`)
  : la RPR-FOM définit ses propres représentations de base personnalisées
  pour les entiers non signés, absents du cœur IEEE 1516.2 à l'époque de
  sa rédaction (HLA4/IEEE 1516-2025 ne les a introduits que récemment).
  Wire-identiques à leurs équivalents `HLA`-préfixés, réutilisent le
  même encodage.
- **Plusieurs encodages de tableau distincts**, pas seulement
  `HLAvariableArray` :
  - `RPRlengthlessArray` (35 occurrences dans le fichier réel) : pas de
    compteur, les éléments occupent tout le buffer restant.
  - `RPRnullTerminatedArray` (via `RTIobjectId`, l'identifiant d'objet
    RTI utilisé dans une bonne partie des attributs/paramètres) :
    séquence d'éléments terminée par un octet `0x00`, comme une chaîne C.
  - `RPRpaddingTo32Array`/`RPRpaddingTo64Array` : pas des tableaux à
    proprement parler, mais des marqueurs de bourrage purs (aucune
    valeur, juste un effet d'alignement) amenant la longueur de
    l'enregistrement courant à un multiple de 32/64 bits.
- **Refonte des tableaux dynamiques en types nommés** : un même type
  d'élément (`Float32`, `Octet`, `VariableDatumStruct`) est utilisé
  dans le fichier réel avec *plusieurs* encodages différents. L'ancienne
  approche (tableau anonyme `std::vector<T>`/`List<T>`, une seule
  spécialisation par type d'élément) aurait produit une collision de
  spécialisation de template/registre. Chaque `arrayData` nommé du FOM
  génère désormais son propre type composé (portant le nom du FOM), ce
  qui élimine le problème par construction.
- **Collisions de noms après sanitisation** : deux énumérateurs FOM
  distincts (ex. `Entity_Type-Kind` avec un tiret et `Entity_Type_Kind`
  avec un underscore, tous deux réels dans le même `enumeratedData` de
  763 entrées `DatumIdentifierEnum32`) peuvent converger vers le même
  identifiant C++/Java après nettoyage des caractères invalides.
  Détecté et résolu par suffixe numérique automatique, valeurs
  préservées.
- **Collision entre un alias interne du générateur et un vrai type de
  la norme** : `RPRboolean` est un `enumeratedData` réel de la RPR-FOM
  (`HLAoctet`, False=0/True=1, "8-bit compatibility"), distinct de
  `HLAboolean`. Le générateur utilisait ce même nom en interne comme
  alias C++ pour son propre encodage de `HLAboolean` — renommé pour
  laisser la priorité au vrai type de la norme.
- **Mots réservés et caractères invalides comme noms de champs** (un
  champ FOM nommé `Class`, un autre `NumberOfBytes-A-Value` avec un
  tiret littéral) : `NameUtils` échappe désormais les mots réservés
  C++/Java et nettoie les caractères invalides dans les noms de champs,
  pas seulement les noms de types.
- **`std::variant` avec alternatives de type dupliqué** : un vrai
  `variantRecordData` du FOM (`SpatialVariantStruct`) a plusieurs
  alternatives (discriminants différents) qui partagent exactement le
  même type de charge utile. `std::variant` interdit la construction/
  affectation par type quand celui-ci apparaît plusieurs fois dans la
  liste des alternatives — le décodeur C++ construit désormais
  l'alternative par **index** (`emplace<I>`) plutôt que par type;
  l'encodeur utilisait déjà `std::get<index>`, donc déjà correct.
  Le design Java (indexé par nom de champ, jamais par type) n'a jamais
  eu ce problème.

## Validé contre les fichiers RPR-FOM v3.0 officiels (SISO-STD-001.1-2025)

Même démarche que pour le RPR-FOM 2.0 ci-dessus, appliquée cette fois aux
**15 fichiers normatifs** publiés par le SISO pour la RPR-FOM v3.0/2025
(`SISO-STD-001.1-2025 Annex A Files Normative/*.xml` — `RPR-Base`,
`RPR-Physical`, `RPR-Communication`, `RPR-DER`, `RPR-Enumerations`,
`RPR-Foundation`, `RPR-IO`, `RPR-Logistics`, `RPR-Minefield`, `RPR-SE`,
`RPR-SIMAN`, `RPR-Switches`, `RPR-UA`, `RPR-Warfare`, `RPR-Aggregate`) :
**génération, compilation (C++, Java et couche Unreal) et round-trip
encodage/décodage réussis de bout en bout**, sur les 706 types de
données et 94 `objectClass`/99 `interactionClass` fusionnés des 15
fichiers. Ce passage grandeur nature sur la révision 2025 du standard a
révélé des constructions réelles du FOM que le générateur, validé
jusque-là sur le RPR-FOM 2.0 et des FOM de test plus simples, ne savait
pas encore traiter :

- **`HLAinteger64Time`/`HLAfloat64Time` non reconnus** : ces deux
  représentations d'horodatage sont définies par le MIM (Management
  Object Model) du socle HLA 1516.2 lui-même, pas redéclarées dans les
  fichiers FOM (utilisées 3 fois dans `RPR-Base_v3.0.xml`) — le
  générateur ne connaissait que les représentations explicitement
  listées *dans* le FOM. Ajoutées en dur à la liste des représentations
  de base reconnues (encodage identique à `HLAinteger64BE`/
  `HLAfloat64BE`), y compris côté table de correspondance Unreal
  (`HLAinteger64Time` → `int64`, `HLAfloat64Time` → `double`), qui est
  séparée de celle utilisée par les générateurs C++/Java et ne les
  connaissait donc pas non plus.
- **Alternative de `variantRecordData` à discriminant multiple** : le
  schéma OMT autorise qu'une alternative soit associée à une *liste*
  d'énumérateurs séparés par des virgules plutôt qu'à un seul — cas réel
  dans `RPR-Communication_v3.0.xml`
  (`<enumerator>HAVE_QUICK_I, HAVE_QUICK_II, SATURN</enumerator>` sur
  l'alternative `BasicHAVEQUICKModulation`), c'est-à-dire que plusieurs
  valeurs du discriminant doivent toutes mener à la même alternative.
  Le générateur ne gérait qu'un seul énumérateur par alternative ;
  il génère désormais, côté Java comme C++, toutes les valeurs listées
  pour une même alternative (en C++ : plusieurs `case` qui tombent dans
  le même bloc).
- **Tableau à cardinalité FIXE (`HLAfixedArray`) utilisé ailleurs qu'en
  champ simple** : le générateur ne savait créer, côté Java, une classe
  wrapper que pour les tableaux *dynamiques* ; pour un tableau fixe, il
  échouait dès qu'il fallait l'utiliser imbriqué dans un autre tableau
  (`OpaqueDatum64` dans `OpaqueDatum64Array1Plus`,
  `RPR-Base_v3.0.xml`), comme alternative de variant, ou comme attribut
  direct (`ASCIIcharArray8` sur `ModeSTransponder.AircraftIdentification`,
  `RPR-DER_v3.0.xml`). Le générateur crée désormais une classe wrapper
  pour tous les tableaux (fixes ou dynamiques), avec un codec dédié qui
  lit/écrit exactement N éléments, sans compteur sur le fil — côté C++,
  un tableau fixe reste représenté de façon purement structurelle
  (`std::array<T, N>`, jamais de wrapper nommé), ce qui a nécessité
  d'ajouter les spécialisations génériques `HLAEncoderDetail`/
  `HLADecoderDetail` correspondantes (absentes jusque-là, le seul cas
  générique préexistant étant `std::vector<T>`) : un oubli resté invisible
  à la compilation (les templates C++ ne sont vérifiés qu'à l'usage) et
  qui n'est apparu qu'à l'écriture d'un vrai test d'encodage **puis**
  décodage — une simple compilation ne suffit donc pas à valider cette
  sortie, leçon désormais appliquée systématiquement.
- **`variantRecordData` sans aucune alternative** :
  `ModeSDownlinkAircraftParameterVariantStruct`
  (`RPR-DER_v3.0.xml`) est déclaré avec zéro `<alternative>` — un cas
  limite réel du standard, marqué d'un encodage spécifique
  (`RPRextendedVariantRecord`). Un `std::variant<>` C++ vide étant
  invalide, le générateur insère désormais `std::monostate` comme type
  de remplissage dans ce cas ; une tentative d'encoder une telle
  structure lève une erreur explicite à l'exécution (il n'y a de toute
  façon rien à encoder).
- **Section `<time>` en noms d'éléments hérités** : cf. l'avertissement
  non bloquant dédié dans la section de validation XSD ci-dessus
  (`<timeStamp>`/`<lookahead>` dans `RPR-Base_v3.0.xml`).

## Types « à responsabilité du développeur » (`--user-type`)

Un développeur peut reprendre à sa charge l'encodage/décodage d'un `fixedRecordData` de la FOM
(exemple : `WorldLocationStruct` avec latitude/longitude/altitude WGS84 au lieu de x, y, z).
Le type n'est alors **plus généré** dans `HLATypes`/`HLAEncoder`/`HLADecoder` : on copie son fichier à la place.
Périmètre v1 : `fixedRecordData` uniquement.

### Options

| Option | Rôle |
|---|---|
| `--user-type Nom` | Déclare le type (répétable). |
| `--user-type-java Nom=fichier.java` | Fichier Java explicite. |
| `--user-type-cpp Nom=Nom.hpp[,NomCodec.hpp]` | Fichiers C++ explicites. |
| `--user-types-java dossier` / `--user-types-cpp dossier` | Convention : `Nom.java`, `Nom.hpp` + `NomCodec.hpp`. |
| `--user-types-config fichier.xml` | Fichier XML associant à chaque type son dossier Java et/ou C++ (déclare aussi les types) ; chemins relatifs au fichier XML. Voir `examples/user-types/user-types.xml`. Les options explicites `--user-type-java/-cpp` restent prioritaires. |
| `--fallback-dir dossier` | Où écrire les classes de secours (défaut : `<out-java>/fallback`, `<out-cpp>/fallback/usertypes`). |
| `--strict-user-types` | Fichier absent ou sans empreinte = erreur (au lieu d'un avertissement). |
| `--allow-stale-user-types` | Empreinte périmée tolérée (avertissement). |
| `--skip-conformance-test` | Ne génère pas le test de conformité. |

Priorité : fichier explicite > dossier de convention > **type intégré au générateur** (cf. section dédiée juste après) > **classe de secours**.

### Types intégrés au générateur (sans aucune option d'appel)

Au-delà des types déclarés par CE projet via les options ci-dessus, le générateur
lui-même peut embarquer, dans ses propres ressources, des types « à responsabilité
du développeur » appliqués **par défaut, sans rien à déclarer en ligne de commande**.
L'idée : la plupart des projets réécrivent finalement les mêmes types de la même
façon (ex. `WorldLocationStruct` en latitude/longitude/altitude) — plutôt que
chaque projet redéclare son propre `--user-types-java`/`--user-types-config` pour
ces types récurrents, le générateur peut les fournir lui-même une fois pour toutes.

Convention de ressources, sous `src/main/resources/usertypes-builtin/` :

```
usertypes-builtin/
  registry.txt            -- un nom de type (fixedRecordData de la FOM) par ligne ;
                              lignes vides et lignes commençant par '#' ignorées.
  java/<Nom>.java          -- optionnel : recouvrement Java intégré pour ce type.
  cpp/<Nom>.hpp
  cpp/<Nom>Codec.hpp       -- optionnels, ensemble (comme pour --user-type-cpp).
```

Pour chaque nom listé dans `registry.txt` et effectivement défini dans la FOM
traitée, le fichier correspondant est utilisé automatiquement — **sauf si le
projet fournit lui-même un fichier de même nom** via `--user-type-java/-cpp`,
`--user-types-java/-cpp` ou `--user-types-config` : ces mécanismes restent
inchangés et toujours prioritaires, type par type et langage par langage (un
projet peut par exemple ne reprendre la main que sur le C++ d'un type et garder
le Java intégré). `registry.txt` est livré vide (juste des commentaires
d'explication) : le mécanisme est en place mais n'impose aucun type par défaut
tant que personne n'y en ajoute un.

Un type intégré bénéficie d'une validation **souple**, différente de celle des
types déclarés explicitement : absent de la FOM traitée, filtré par
`--federate-fom`, non `fixedRecordData`, en collision de nom avec une classe
générée, dont le contrat (méthodes/struct attendues) n'est pas respecté, ou dont
l'empreinte FOM ne correspond pas à la définition courante du type est
simplement **ignoré pour ce langage** (repli silencieux sur la génération
standard, éventuellement accompagné d'un avertissement si l'anomalie révèle un
vrai défaut du fichier intégré) — jamais une erreur bloquante. `--strict-user-types`
ne s'applique d'ailleurs jamais à un type purement intégré (un projet ne doit
jamais être bloqué par un type qu'il n'a lui-même jamais demandé). C'est ce qui
permet d'enrichir ce registre sans risque de casser un projet qui ne définit pas
(ou définit différemment) le type ajouté.

Dans le rapport de fin de génération, un type intégré apparaît avec la source
`INTEGRE` (à côté de `CUSTOM` pour un fichier du projet et `SECOURS` pour la
génération standard).

### Classe de secours
Pour chaque type déclaré, une classe identique au code standard est toujours régénérée dans le dossier `fallback`.
Si aucun fichier développeur n'est fourni, elle est copiée à la bonne destination ; elle sert aussi de gabarit de départ.

### Contrat Java
Classe publique de premier niveau, constructeur sans argument, et :
`public static void encodeInto(ByteArrayOutputStream, X)`, `public static X decodeAt(byte[], HLADecoding.Offset)`.
Le fichier porte le commentaire `@rprfom-fom-fingerprint: <16 hex>` (recopié depuis la classe de secours).
Le `package` et les imports sont réécrits pour suivre `--java-package`.

### Contrat C++
Deux fichiers copiés dans `include/rprfom/usertypes/` : `X.hpp` (struct dans `namespace rprfom`) et `XCodec.hpp`
(définitions inline de `HLAEncoderDetail<X>::encodeInto` et `HLADecoderDetail<X>::decodeAt`). Fournir un seul des deux est une erreur.

### Garde-fous
- Toute la validation est faite **avant** d'écrire quoi que ce soit (erreur = code de sortie 1, rien de généré).
- Nom inconnu (suggestion « Vouliez-vous dire »), type non `fixedRecordData`, type exclu par `--federate-fom` (ignoré), collision de nom, doublon.
- Contrôle de contrat (signatures, constructeur, struct/namespace/codec), fichier posé dans un dossier de convention mais non déclaré (faute de frappe).
- **Empreinte FOM** : SHA-256 (16 hex) de la définition canonique du type. Périmée = erreur ; absente = avertissement (erreur en `--strict-user-types`).
- **Test de conformité** généré (Java : `src/test/java/<pkg>/conformance/UserTypesConformanceCheck.java`, exécuté en phase `test` de Maven ; C++ : `<out-cpp>/test/UserTypesConformance.cpp`) :
  compare octet par octet votre code au code standard sur 300 tampons pseudo-aléatoires (octets produits, offset consommé, alignement).
- Manifeste `.rprfom-user-types.manifest` : les fichiers copiés sont supprimés à la génération suivante (pas de `X.java` orphelin qui masquerait le type généré).
  Un fichier existant non géré et différent est sauvegardé en `.bak`.

### Conception recommandée
Conservez les champs de la FOM comme **état** (x, y, z) et exposez les valeurs dérivées par des accesseurs (`latitudeDeg()`…).
Un type qui stockerait lat/lon/alt et reconvertirait à l'encodage perd des bits : il échoue au test exact. Voir `examples/user-types/`.

### Limites
Les tableaux dynamiques sont testés vides et les variants sur leur première alternative ; un type non simulable n'est testé que sur l'instance par défaut (le rapport le précise).

## Nettoyage automatique de la génération précédente

Chaque dossier de sortie (`--out-java`, `--out-cpp`) contient un fichier `.rprfom-generated.manifest` listant les fichiers écrits par `rprfom-codegen`.
À l'exécution suivante, ces fichiers sont supprimés avant de régénérer (puis les dossiers devenus vides) : changer de fichier `--federate-fom`
ne laisse donc plus d'anciennes classes d'objets ou d'interactions qui casseraient la compilation. Les fichiers qui n'ont pas été écrits par l'outil
ne sont jamais touchés. Un dossier généré par une version antérieure (sans manifeste) n'est pas nettoyé la première fois : videz-le une fois à la main.

## Sortie Unreal Engine (`--out-cpp-unreal`) : couche de présentation Blueprint

Un troisième dossier de sortie, optionnel, génère une couche de présentation `.h`/`.cpp`
exposant les `objectClass`/`interactionClass` du FOM (attributs/paramètres, pas les
fonctions d'encodage/décodage) en Blueprint, pour le projet plugin Unreal
**PluginUnrealHLA** (visé : Unreal 5.6). Cette sortie ne crée **pas** le plugin lui-même
(pas de `.uplugin`, pas de `Build.cs`, pas de squelette de module, pas de macro
d'export `_API`) : les fichiers générés sont à copier dans le plugin existant/à créer.

```
java -jar rprfom-codegen/target/rprfom-codegen.jar \
    --fom RPR-Base.xml \
    --out-cpp ./out/cpp \
    --out-java ./out/java \
    --out-cpp-unreal ./out/unreal
```

### Architecture

Couche de présentation séparée, dépendance vers le code natif (`resultatCpp`) par
simple `#include` (pas de lien dans l'autre sens : le code natif ne connaît pas
Unreal). Une fonction de conversion `ToUnreal_X`/`ToNative_X` est générée pour chaque
classe/type FOM (nommées explicitement par type FOM plutôt que surchargées par
paramètre, pour éviter toute ambiguïté — ex. `HLAopaqueData`/`HLAhandle` sont tous
deux des tableaux d'octets au niveau natif).

- **Fichier agrégé `RPRFOMUnrealTypes.h`/`.cpp`** : un `USTRUCT`/`UENUM` par
  `fixedRecordData`/`variantRecordData`/`enumeratedData`/tableau nommé du FOM, plus
  leurs fonctions de conversion. À placer, avec `HLATypes.hpp` (`resultatCpp`), dans
  les chemins d'inclusion du module Unreal (les `#include` générés ne portent pas de
  préfixe de dossier).
- **Une `UCLASS` par `objectClass`/`interactionClass`** (vrais `UObject`, pas des
  `USTRUCT`) : un fichier `.h`/`.cpp` par classe, nommé d'après le nom FOM d'origine
  (sans le préfixe `U` imposé par Unreal Header Tool — ex. classe `UAircraft` dans le
  fichier `Aircraft.h`, comme le ferait un module Unreal classique). Chaque
  attribut/paramètre expose `GetX()`/`SetX()` (no-op silencieux si l'attribut n'est
  pas possédé) et `IsXOwned()` (objet) ou `HasX()` (interaction) — pas d'ownership
  pour une interaction. Le wrapper `HLAAttribute<T>`/`HLAParameter<T>` lui-même n'est
  **pas** exposé (template, incompatible Blueprint) ; la publication/souscription RTI
  reste gérée ailleurs, en C++ pur, par le futur `HLAbstract core` du plugin — cette
  sortie ne crée que les classes, pas la synchronisation runtime.
- **`Category` des `UPROPERTY`/`UFUNCTION`** : basée sur la hiérarchie FOM, racine
  `HLAobjectRoot`/`HLAinteractionRoot` omise (ex. classe `Platform` sous
  `BaseEntity.PhysicalEntity` → `Category="BaseEntity|PhysicalEntity|Platform"`).

### Granularité et filtrage

Comme pour les deux autres sorties, `--federate-fom` filtre aussi `--out-cpp-unreal`
(pas de filtrage obligatoire propre à cette sortie : en son absence, le FOM complet
est exposé). Un type « à responsabilité du développeur » (`--user-type`) reçoit son
miroir Unreal comme n'importe quel autre type : cette sortie dérive uniquement du
schéma FOM, jamais du fichier Java/C++ fourni par le développeur.

### Types FOM de base : pertes acceptées pour la compatibilité Blueprint

- **`variantRecordData`** : le discriminant **et toutes les alternatives** sont
  présents simultanément dans le `USTRUCT` (Blueprint n'a pas d'équivalent de
  `std::variant`) — seule l'alternative correspondant au discriminant est valide à un
  instant donné, comme pour le `std::variant` natif.
- **`FString`** : perte acceptée de la distinction ASCII/Unicode des `HLAunicodeString`/`HLAASCIIstring`.
- **Entiers 64 bits non signés** (`HLAunsignedInteger64`) : élargis en `int64`, perte
  de portée acceptée sur les très grandes valeurs (pas de support Blueprint natif
  d'un entier 64 bits non signé). Même traitement (élargissement, sans perte ici)
  pour les horodatages du socle HLA `HLAinteger64Time` (→ `int64`) et
  `HLAfloat64Time` (→ `double`).
- **`HLAoctetPairStruct`** (représentations `HLAoctetPairBE`/`HLAoctetPairLE`) :
  exclu entièrement de cette sortie.

### Vérification effectuée (et ses limites)

Aucun moteur Unreal/Unreal Header Tool (UHT) n'est disponible dans l'environnement de
génération : le code a été vérifié avec un harnais de smoke-test `g++ -fsyntax-only`
(macros `UCLASS`/`USTRUCT`/`UPROPERTY`/etc. neutralisées, `FString`/`TArray<T>`/`UObject`
minimaux reconstitués à la main) sur l'intégralité des fichiers générés (FOM complet et
filtré par fédéré), ce qui valide la **syntaxe et la logique C++** mais **pas** le
comportement réel de la réflexion Unreal (UHT), ni les idiomes `FString`/UTF-16 exacts
(`UTF16CHAR*`, `FTCHARToUTF16`), qui suivent l'API Unreal connue mais n'ont pas été
testés contre le moteur réel. Une première compilation réelle dans le plugin
`PluginUnrealHLA` reste donc à faire.

### Constat annexe (préexistant, non corrigé)

En vérifiant cette sortie, une collision de noms préexistante a été repérée dans les
**trois** générateurs (Java, C++, Unreal) : sur les 141 `objectClass`/`interactionClass`
du FOM RPR 2.0 complet, deux classes partagent le même nom de feuille FOM dans des
branches différentes de la hiérarchie, et le fichier généré pour la seconde écrase
silencieusement celui de la première (140 fichiers de classe distincts au lieu de 141).
Ce comportement existait déjà côté `resultatCpp`/`resultatJava` avant cette fonctionnalité
et n'a pas été modifié ici (hors périmètre de la demande) ; à corriger séparément si besoin,
par exemple en qualifiant le nom de fichier par le chemin FOM complet plutôt que la feuille
seule.

## Limitations connues (honnêtes)

- **`HLAoctetPairBE`/`HLAoctetPairLE`** : traités de façon identique
  (2 octets bruts dans l'ordre donné, sans permutation), faute de
  semantique numérique propre à ce type dans la norme.
- **Alignement des tableaux (`HLAOctetBoundary`)** : calculé comme le
  plus grand octet boundary parmi les champs du type élément. Correct
  pour les cas usuels, reste une heuristique simplifiée pour des types
  imbriqués très hétérogènes.
- **`RPRnullTerminatedArray`** : implémenté uniquement pour des éléments
  d'1 octet (le cas réel rencontré, `RTIobjectId`/`HLAASCIIchar`) — le
  générateur échoue explicitement à la génération si jamais rencontré
  avec un élément plus large, plutôt que de produire un encodage
  incorrect.
- **`variantRecordData` sans aucune alternative** (cas réel rencontré
  dans le RPR-FOM v3.0/2025, cf. section dédiée plus haut) : génère un
  type qui compile (`std::monostate` côté C++) mais ne peut jamais être
  encodé avec une valeur utile — c'est un cas limite du standard sans
  contenu à transporter, pas une donnée manquante du générateur.
- Pas de constructeurs de convenance générés pour les structs Java
  (contrairement à nos `ArticulatedParameterStruct`/etc. écrits à la
  main) : on construit par affectation de champs.

## Couverture des types HLA (IEEE 1516.2 + extensions non signées HLA4/IEEE 1516-2025)

Toutes les représentations de base standard sont supportées : entiers
signés/non signés 16/32/64 bits en big-endian ET little-endian, flottants
32/64 bits BE/LE, `HLAoctet`, `HLAboolean`, `HLAbyte`,
`HLAunsignedInteger8`, `HLAASCIIchar`, `HLAunicodeChar`,
`HLAoctetPairBE/LE`, `HLAASCIIstring`, `HLAunicodeString`,
`HLAopaqueData`, `HLAhandle` — 26 représentations au total, plus les 4
représentations `RPR`-préfixées personnalisées de la RPR-FOM
(`RPRunsignedInteger8/16/32/64BE`, wire-identiques à leurs équivalents
`HLA`-préfixés), et les 2 représentations d'horodatage du socle HLA
(`HLAinteger64Time`, `HLAfloat64Time`, définies par le MIM et non
redéclarées dans les fichiers FOM — reconnues en dur, wire-identiques à
`HLAinteger64BE`/`HLAfloat64BE`).

Les tableaux dynamiques supportent trois encodages distincts, détectés
via l'élément `<encoding>` du FOM (`HLAvariableArray` par défaut si
absent, rétro-compatible) : `HLAvariableArray` (compteur + éléments),
`RPRlengthlessArray` (pas de compteur, consomme tout le buffer restant)
et `RPRnullTerminatedArray` (terminateur `0x00`, éléments d'1 octet
uniquement). Chaque `arrayData` nommé du FOM génère son propre type
composé plutôt qu'un alias anonyme, pour rester correct même quand le
même type d'élément est utilisé avec plusieurs encodages différents
dans le même FOM (cf. section de validation ci-dessus).

Les tableaux à cardinalité **fixe** (`HLAfixedArray`) sont supportés
partout où un tableau peut apparaître dans le FOM — champ simple d'une
structure, mais aussi imbriqué dans un autre tableau, alternative de
`variantRecordData`, ou attribut/paramètre direct — avec le même
mécanisme de type nommé que les tableaux dynamiques côté Java (classe
wrapper + codec qui lit/écrit exactement N éléments, sans compteur sur
le fil) ; côté C++, un tableau fixe reste représenté de façon purement
structurelle (`std::array<T, N>`, jamais de wrapper nommé), via des
spécialisations génériques de l'encodeur/décodeur plutôt qu'une par
taille.

**Point d'architecture important** : une collision de type ne peut se
produire qu'au niveau d'un ATTRIBUT (dispatch par `Class<?>` en Java,
spécialisation de template en C++), jamais au niveau d'un CHAMP de
structure (résolu à la génération, pas à l'exécution). `TypeMapper`
distingue donc explicitement un type de CHAMP (réutilisable librement,
ex. `int16_t`/`short` pour BE et LE) d'un type d'ATTRIBUT (qui doit être
unique par représentation, avec un wrapper dédié — `Integer16LE`,
`HLAUnsignedInteger64`, etc. — quand le type "naturel" est déjà pris).
Ce choix vaut aussi bien en C++ (où `using Integer16LE = int16_t;`
collisionnerait avec `Integer16` dans `HLAEncoderDetail<T>`) qu'en Java.

`HLAopaqueData`/`HLAhandle` sont traités en interne comme de vrais
tableaux dynamiques d'`HLAoctet` synthétiques, injectés dans le modèle
au démarrage : aucun code de génération dédié n'a été nécessaire, tout
le mécanisme de tableau déjà écrit s'applique automatiquement.

## `variantRecordData` : encodage conforme (seule l'alternative active)

Un `variantRecordData` n'encode que l'alternative sélectionnée par le
discriminant, précédée de ce discriminant — pas un aplatissement de
toutes les alternatives.

- **C++** : `std::variant<Alt1, Alt2, ...>` (l'index du variant sert
  directement de discriminant à l'encodage ; le décodeur lit le
  discriminant et reconstruit l'alternative correspondante via un
  `switch`).
- **Java** : classe "tagged union" écrite à la main (`alternativeIndex`
  + `Object value`, fabriques `ofXxx(...)` et accesseurs `asXxx()`) —
  choix délibéré pour rester compatible Java 17 sans dépendre des
  *sealed classes* + *pattern matching for switch*, en préversion avant
  Java 21.
- Le mapping discriminant ↔ alternative se fait soit par valeur
  numérique littérale (`<enumerator>0</enumerator>`), soit par nom
  d'énumérateur si le discriminant est un `enumeratedData` (résolu via
  `TypeMapper.discriminantValuesOf`) — **une même alternative peut être
  associée à plusieurs valeurs de discriminant à la fois** (liste
  d'énumérateurs séparés par des virgules, autorisée par le schéma OMT ;
  cas réel dans la RPR-FOM v3.0/2025, cf. section de validation
  dédiée plus haut) : toutes les valeurs listées décodent alors vers la
  même alternative.
- Un `variantRecordData` peut légitimement n'avoir **aucune**
  alternative (cas limite réel du standard, cf. section de validation
  RPR-FOM v3.0/2025 plus haut) : le type est généré et compile
  (`std::monostate` côté C++), mais toute tentative de l'encoder lève
  une erreur explicite, faute de contenu à transporter.
- Un discriminant inconnu à la décodification lève une exception
  explicite (`std::logic_error` / `IllegalStateException`) plutôt que
  de produire une valeur invalide.

## `interactionClass` : miroir de `objectClass`, sans ownership

Les interactions HLA (`<interactions><interactionClass>...`, racine
`HLAinteractionRoot`) sont générées exactement comme les `objectClass`
(hiérarchie imbriquée fusionnée par nom, map générique par handle RTI,
`HLAEncoder`/`HLADecoder`), avec une différence assumée : **pas
d'ownership**. Une interaction HLA est un message envoyé une fois
(`sendInteraction`/`receiveInteraction`), pas un état persistant qu'un
fédéré possède — il n'y a donc ni `OwnershipState`, ni garde sur
`set()`, ni suivi temporel par paramètre (`lastUpdateTime`), qui n'ont
pas de sens pour un message éphémère.

- **`HLAParameter<T>`** (C++/Java) remplace `HLAAttribute<T>` : mêmes
  mécanismes d'encodage/décodage et de type erasure (jeton `Class<T>`
  en Java), sans ownership. `hasValue()` remplace `needsPublication()`
  — indique si `set()`/`decodeFrom()` a déjà été appelé, pour ne
  construire le `ParameterHandleValueMap` envoyé au RTI qu'avec les
  paramètres effectivement renseignés (un paramètre non renseigné
  n'est simplement pas inclus, comme le montre le test généré : 3
  paramètres sur 6 envoyés).
- `IHLAParameterBase` remplace `IHLAAttributeBase` (interface non
  générique, même rôle).
- `bindParameterHandle`/`parameter`/`parametersByHandle`/
  `registerParameter` remplacent leurs équivalents `*Attribute*`.
- `HLA_INTERACTION_CLASS_NAME` remplace `HLA_OBJECT_CLASS_NAME`.
- Les interactions peuvent elles-mêmes former une hiérarchie imbriquée
  (ex. `WeaponFire` → `MunitionDetonation`), avec le même mécanisme
  parent/enfant et la même map partagée que pour les `objectClass`.

## Tests

Un jeu de fichiers FOM de démonstration sous `test-fom/` (`RPR-Base.xml`,
`RPR-Physical.xml`, `MonEnrichissement.xml`, `Extensions.xml`,
`Interactions.xml`, `PaddingTest.xml`) reconstruit
`BaseEntity -> PhysicalEntity -> Platform -> Aircraft`, ajoute une
classe personnalisée `Rafale`, exerce systématiquement les types
étendus (BE/LE, chaînes, données opaques), le variant réel, les
interactions et le padding des champs composés non-initiaux. Généré et
testé (compilation + round-trip encode/decode) avec succès en C++
(`g++ -std=c++17`) et en Java (`javac`/`java`), avec des octets encodés
**identiques** entre les deux langages sur tous les cas.

Au-delà de ces fichiers écrits à la main, le générateur a également été
validé contre le fichier RPR-FOM 2.0 officiel complet et réel (cf. la
section dédiée plus haut) : 282 types de données, 63 `objectClass`,
78 `interactionClass`, compilation et round-trip réussis dans les deux
langages, octets identiques entre C++ et Java.

Il a de même été validé contre les **15 fichiers normatifs RPR-FOM
v3.0/2025** officiels (`FOM/SISO-STD-001.1-2025 Annex A Files
Normative/*.xml`, cf. la section dédiée plus haut) : 706 types de
données, 94 `objectClass`, 99 `interactionClass`, génération et
compilation réussies en C++, Java et sortie Unreal, avec des tests
d'encodage **puis** décodage dédiés (pas une simple compilation) sur
chacun des cas limites réels trouvés dans cette révision du standard
(tableau fixe imbriqué, tableau de cardinalité 0, variant à
discriminant multiple, variant sans alternative, tableau fixe en
attribut direct).

`test-fom/FederateExample.xml` et `test-fom/FederateBad.xml` testent
`--federate-fom` (cf. section dédiée plus haut) sur ce même jeu de FOM,
validation XSD (`federatesFOMProvider.xsd`) comprise : filtrage
d'attributs sur une classe explicitement demandée à la fois feuille
(`RafaleAvionique`) et intermédiaire (`PhysicalEntity`), résolution
d'attributs hérités vers leur classe d'origine réelle (`EntityIdentifier`
-> `BaseEntity`, `HatchState` -> `Platform`, chacune ne gardant alors que
cet unique attribut), génération de classes ancêtres vides quand aucun
attribut ne s'y résout (`Aircraft`, `Rafale`), et avertissements sur
objet/attribut/interaction inconnus -- vérifié en pratique avec
compilation C++ (`g++ -std=c++17`) et Java (`javac`) du résultat filtré.
