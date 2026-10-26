# Correctif generateur RPR-FOM v3.0 (2025) — fichiers corriges / nouveaux

Ce dossier contient les fichiers source Java du generateur (`FomForFederateCodeGenerator`)
qui ont ete corriges pour que la generation complete (C++, Java, Unreal) reussisse a
partir des 15 fichiers normatifs `SISO-STD-001.1-2025 Annex A Files Normative` (RPR-FOM
v3.0 / 2025, publies par le SISO).

## Comment appliquer le correctif

1. Dans votre copie du projet `FomForFederateCodeGenerator`, remplacez les fichiers
   suivants par ceux de ce dossier (memes chemins relatifs depuis la racine du projet) :
   - `src/main/java/rprfom/codegen/Main.java`
   - `src/main/java/rprfom/codegen/parse/FomXsdValidator.java`
   - `src/main/java/rprfom/codegen/generate/TypeMapper.java`
   - `src/main/java/rprfom/codegen/generate/CppGenerator.java`
   - `src/main/java/rprfom/codegen/generate/JavaGenerator.java`
   - `src/main/java/rprfom/codegen/generate/UnrealGenerator.java`
2. Recompilez (`mvn -q package`, ou `javac` + `jar` si vous n'utilisez pas Maven).
   Un `rprfom-codegen.jar` deja recompile est aussi fourni dans ce dossier si vous
   préférez l'utiliser directement sans recompiler.
3. Relancez votre generation habituelle sur les 15 fichiers RPR-FOM v3.0 (2025) :
   elle doit maintenant produire l'integralite des classes C++, Java et Unreal sans
   erreur (706 types de donnees, 94 objectClass, 99 interactionClass).

## Pourquoi ca ne fonctionnait pas — 10 problemes corriges

Le generateur avait ete valide a l'origine sur RPR-FOM v2.0 (2010) et sur des FOM de
test plus simples. Les 15 fichiers normatifs v3.0 (2025) utilisent plusieurs
constructions du standard que le generateur ne savait pas encore traiter. Voici,
fichier par fichier, ce qui a ete trouve et corrige.

### 1. `Main.java` — l'absence du dossier Unreal faisait planter toute generation
Quand l'option `--out-cpp-unreal` n'etait pas fournie, le generateur construisait une
liste de dossiers de sortie avec `List.of(outCpp, outJava, outCppUnreal)`. Or `outCppUnreal`
peut etre `null`, et `List.of` interdit les elements `null` (il leve une
`NullPointerException`). Resultat : meme sans rien demander d'Unreal, la generation
plantait au nettoyage/sauvegarde des dossiers de sortie. Remplace par
`Arrays.asList(...)`, qui accepte les `null`.

### 2. `FomXsdValidator.java` — la validation XSD rejetait la section `<time>` de RPR-Base
`RPR-Base_v3.0.xml` utilise encore les noms d'elements `<timeStamp>` / `<lookahead>`
dans sa section `<time>` (anciens noms conserves par heritage dans ce fichier), alors
que le schema `IEEE1516-DIF-2025.xsd` attend desormais `<logicalTime>` /
`<logicalTimeInterval>`. La validation XSD rejetait donc le fichier avant meme le
parsing. Comme le generateur ne lit de toute facon pas cette section `<time>` (elle ne
produit aucune classe), l'erreur a ete requalifiee en simple avertissement
(non bloquant) plutot qu'en echec.

### 3. `TypeMapper.java` — `HLAinteger64Time` non reconnu
Le MIM (Management Object Model) HLA 1516.2 definit deux representations de base
standard pour l'horodatage — `HLAinteger64Time` et `HLAfloat64Time` — qui ne sont pas
redeclarees dans les fichiers FOM (elles font partie du socle HLA, pas du FOM
applicatif). Le generateur ne connaissait que les representations explicitement
listees dans les FOM et levait une erreur "representation inconnue" sur ces deux-la.
Elles ont ete ajoutees en dur a la liste des representations de base reconnues
(encodage identique a `HLAinteger64BE` / `HLAfloat64BE`).

### 4. `TypeMapper.java` / `CppGenerator.java` — une alternative de variant avec PLUSIEURS enumerateurs
Le schema OMT autorise qu'une alternative de `variantRecordData` soit associee a une
LISTE d'enumerateurs separes par des virgules (ex. `<enumerator>HAVE_QUICK_I,
HAVE_QUICK_II, SATURN</enumerator>` dans `RPR-Communication_v3.0.xml`), c'est-a-dire
que plusieurs valeurs du discriminant doivent toutes mener a la meme alternative. Le
generateur ne gerait qu'un seul enumerateur par alternative. Il genere maintenant,
cote Java comme C++, TOUTES les valeurs du discriminant pour une alternative (en C++ :
plusieurs `case` qui tombent dans le meme bloc).

### 5. `TypeMapper.java` / `JavaGenerator.java` — tableau a cardinalite FIXE utilise comme element imbrique, alternative ou attribut
Le generateur ne savait generer, cote Java, une classe "wrapper" que pour les tableaux
DYNAMIQUES (cardinalite variable) ; pour un tableau a cardinalite FIXE
(`HLAfixedArray`), il levait une exception des qu'il fallait l'utiliser ailleurs qu'en
champ direct d'une structure (ex. `OpaqueDatum64` imbrique dans
`OpaqueDatum64Array1Plus`, ou `RPRnoData` -- cardinalite 0 -- utilise comme donnee
vide). Le generateur cree maintenant une classe wrapper pour tous les tableaux
(fixes ou dynamiques), avec un codec dedie qui lit/ecrit exactement N elements, sans
compteur sur le fil (contrairement a un tableau dynamique).

### 6. `CppGenerator.java` — variant SANS aucune alternative
`ModeSDownlinkAircraftParameterVariantStruct` (dans `RPR-DER_v3.0.xml`) est declare
avec zero `<alternative>` -- un cas limite reel du standard, marque d'un encodage
specifique (`RPRextendedVariantRecord`). Cote C++, cela produisait un
`std::variant<>` vide, qui est invalide en C++ (un `std::variant` doit avoir au moins
un type). Le generateur insere maintenant `std::monostate` comme type de remplissage
dans ce cas, pour que le code compile ; une tentative d'encoder cette structure leve
une erreur explicite a l'execution, puisqu'il n'y a de toute facon rien a encoder.

### 7. `JavaGenerator.java` — syntaxe Java invalide pour la valeur par defaut d'un champ de variant
Pour certains types primitifs utilises comme alternative de variant, le generateur
tentait de produire une valeur par defaut via une expression du type `new int()`, qui
n'est pas une syntaxe Java valide (un type primitif ne se construit pas avec `new`).
Resultat : le fichier `.java` genere ne compilait pas. La valeur par defaut fabriquee a
ete supprimee : le champ reste simplement `null` jusqu'a ce qu'une alternative soit
choisie explicitement (comportement deja utilise ailleurs dans le generateur).

### 8. `JavaGenerator.java` — incoherence de type dans les classes de variant
Les methodes generees `ofXxx(...)` / `asXxx()` d'une classe de variant utilisaient le
type de CHAMP (ex. `int`) alors que le registre d'encodage/decodage generique attend
partout le type dit "attribut" (ex. `HLAUnsignedInteger16`, une classe wrapper
identifiable par son `Class<T>`). Cette incoherence provoquait des erreurs de
compilation ou des erreurs de type a l'execution des qu'un variant etait realise
avec une alternative basique. Les deux methodes utilisent desormais le meme type
"attribut" que l'enregistrement du codec, pour rester coherentes avec le reste du
generateur.

### 9. `CppGenerator.java` — tableau fixe imbrique : aucun codec genere (bug decouvert seulement par un test d'encodage/decodage reel)
Une fois les tableaux fixes autorises comme element imbrique (point 5), le C++ genere
compilait... mais UNIQUEMENT parce que les templates C++ ne sont types-verifies qu'au
moment ou ils sont reellement utilises. A l'execution, encoder une structure comme
`OpaqueDatum64Array1Plus` (qui contient des `std::array<Octet,8>` imbriques) levait
`"Aucun HLAEncoderDetail<T> specialise pour ce type RPR-FOM."` -- aucune
specialisation generique de l'encodeur/decodeur n'existait pour la forme
`std::array<T, N>` (seul `std::vector<T>` en avait une). Ce bug n'a ete trouve qu'en
ecrivant un veritable test d'encodage puis decodage (et non une simple compilation),
ce qui a ete fait specifiquement pour cette revue. Les specialisations generiques
manquantes ont ete ajoutees, et placees AVANT la generation du code qui les utilise
(une specialisation C++ declaree apres qu'un type ait deja ete implicitement instancie
est une erreur de compilation -- il a fallu les positionner au bon endroit dans le
fichier genere).

### 10. `UnrealGenerator.java` — la couche Unreal ne connaissait pas non plus `HLAinteger64Time` / `HLAfloat64Time`
Consequence directe du point 3 : la correction du cas 3 a permis au parsing et aux
generateurs C++/Java de reconnaitre ces deux representations, mais le generateur de la
couche de presentation Unreal (Blueprint) avait sa propre table de correspondance
separee, qui ne les connaissait pas non plus, et faisait donc planter la generation
Unreal des que l'un de ces deux types etait rencontre (3 occurrences dans
`RPR-Base_v3.0.xml`). Completee de la meme maniere que pour C++/Java
(`HLAinteger64Time` -> `int64`, `HLAfloat64Time` -> `double`, cote Blueprint).

## Verification effectuee

- Generation complete des 15 fichiers normatifs (706 types de donnees, 94
  objectClass, 99 interactionClass) : C++, Java et Unreal generes sans aucune erreur
  (un seul avertissement non bloquant, voir point 2).
- Tous les fichiers C++ generes compilent ensemble (`g++ -std=c++17`).
- Tous les fichiers Java generes compilent ensemble (`javac`).
- Tests d'encodage PUIS decodage (round-trip) ecrits specifiquement pour les cas
  reels identifies ci-dessus (tableau fixe imbrique, tableau de cardinalite 0,
  variant a discriminant multiple, variant sans alternative, tableau fixe en
  attribut direct) : tous reussis, en Java comme en C++.
