package rprfom.codegen;

import rprfom.codegen.filter.FederateScopeFilter;
import rprfom.codegen.generate.CppGenerator;
import rprfom.codegen.generate.JavaGenerator;
import rprfom.codegen.generate.NameUtils;
import rprfom.codegen.generate.UnrealGenerator;
import rprfom.codegen.model.FederateSelection;
import rprfom.codegen.model.FomModel;
import rprfom.codegen.model.InteractionClassNode;
import rprfom.codegen.model.ObjectClassNode;
import rprfom.codegen.parse.FederateFomProviderParser;
import rprfom.codegen.parse.FomXmlParser;
import rprfom.codegen.parse.FomXsdValidator;
import rprfom.codegen.usertype.UserTypeOptions;
import rprfom.codegen.usertype.UserTypeResolver;
import rprfom.codegen.usertype.UserTypeWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * rprfom-codegen -- genere des bibliotheques C++ et Java (HLAAttribute,
 * HLAParameter, HLAEncoder/HLADecoder, hierarchies d'objectClass ET
 * d'interactionClass avec map generique par handle) a partir d'un ou
 * plusieurs fichiers FOM XML (format OMT SISO-STD-004.1, celui de la
 * RPR-FOM et de ses extensions). Les objectClass ont un mecanisme
 * d'ownership (HLAAttribute) ; les interactionClass n'en ont pas
 * (HLAParameter), une interaction etant un message envoye une fois et
 * non un etat persistant possede par un federe.
 *
 * Usage :
 *   java -jar rprfom-codegen.jar \
 *       --fom RPR-Base.xml --fom RPR-Physical.xml --fom MonEnrichissement.xml \
 *       --out-cpp ./out/cpp --out-java ./out/java --java-package com.example.rprfom
 *
 * Avec --federate-fom FederateExample.xml (fichier conforme a
 * federatesFOMProvider.xsd, produit par l'outil FOMProvider, valide contre
 * ce schema), seuls les objectClass/interactionClass necessaires a CE
 * federe sont generes, en respectant les regles d'heritage de la FOM (cf.
 * FederateScopeFilter) : un objet/une interaction enfant tire ses classes
 * ancetres (necessaires a l'heritage C++/Java), mais SEULS les attributs
 * effectivement listes dans le fichier federe sont generes -- jamais la
 * totalite des attributs d'une classe ancetre -- et un attribut herite est
 * place sur la classe qui le declare reellement dans la FOM.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        List<Path> fomFiles = new ArrayList<>();
        Path outCpp = null;
        Path outJava = null;
        Path outCppUnreal = null;
        String javaPackage = "rprfom";
        boolean skipXsdValidation = false;
        Path federateFom = null;
        UserTypeOptions userTypes = new UserTypeOptions();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--fom" -> fomFiles.add(Path.of(args[++i]));
                case "--out-cpp" -> outCpp = Path.of(args[++i]);
                case "--out-java" -> outJava = Path.of(args[++i]);
                case "--out-cpp-unreal" -> outCppUnreal = Path.of(args[++i]);
                case "--java-package" -> javaPackage = args[++i];
                case "--skip-xsd-validation" -> skipXsdValidation = true;
                case "--federate-fom" -> federateFom = Path.of(args[++i]);
                case "--user-type" -> userTypes.declare(args[++i]);
                case "--user-type-java", "--user-type-cpp" -> {
                    String option = args[i];
                    try {
                        if (option.equals("--user-type-java")) userTypes.addJavaFile(args[++i]);
                        else userTypes.addCppFile(args[++i]);
                    } catch (IllegalArgumentException ex) {
                        System.err.println(ex.getMessage());
                        System.exit(1);
                    }
                }
                case "--user-types-config" -> {
                    try {
                        userTypes.loadConfig(Path.of(args[++i]));
                    } catch (IllegalArgumentException ex) {
                        System.err.println(ex.getMessage());
                        System.exit(1);
                    }
                }
                case "--user-types-java" -> userTypes.javaDir = Path.of(args[++i]);
                case "--user-types-cpp" -> userTypes.cppDir = Path.of(args[++i]);
                case "--fallback-dir" -> userTypes.fallbackDir = Path.of(args[++i]);
                case "--strict-user-types" -> userTypes.strict = true;
                case "--allow-stale-user-types" -> userTypes.allowStale = true;
                case "--skip-conformance-test" -> userTypes.skipConformanceTest = true;
                case "--help", "-h" -> {
                    printUsage();
                    return;
                }
                default -> {
                    System.err.println("Argument inconnu: " + args[i]);
                    printUsage();
                    System.exit(1);
                }
            }
        }

        if (fomFiles.isEmpty() || outCpp == null || outJava == null) {
            printUsage();
            System.exit(1);
            return;
        }

        System.out.println("rprfom-codegen");
        System.out.println("  Fichiers FOM   : " + fomFiles);
        System.out.println("  Sortie C++     : " + outCpp);
        System.out.println("  Sortie Java    : " + outJava);
        if (outCppUnreal != null) {
            System.out.println("  Sortie Unreal  : " + outCppUnreal + " (couche de presentation Blueprint)");
        }
        System.out.println("  Package Java   : " + javaPackage);
        if (federateFom != null) {
            System.out.println("  Federe (FOMProvider) : " + federateFom);
        }
        if (userTypes.isActive()) {
            System.out.println("  Types a responsabilite du developpeur : " + String.join(", ", new LinkedHashSet<>(userTypes.declared))
                    + (userTypes.strict ? " (mode strict)" : ""));
        }
        System.out.println();

        // ------------------------------------------------------------------
        // 0) Valider chaque fichier FOM contre le schema IEEE1516-DIF (schema
        //    officiel de la norme, permissif -- accepte des fichiers
        //    individuellement incomplets, cf. --skip-xsd-validation). Le
        //    schema effectivement applique (2025 ou 2010) est detecte
        //    automatiquement d'apres le namespace declare par chaque
        //    fichier, pour rester compatible avec les FOM officiels non
        //    republies sous le nouveau namespace (cf. FomXsdValidator).
        // ------------------------------------------------------------------
        if (!skipXsdValidation) {
            FomXsdValidator validator = new FomXsdValidator();
            boolean anyBlocking = false;
            Map<String, Integer> schemaUsageCounts = new LinkedHashMap<>();
            for (Path fom : fomFiles) {
                FomXsdValidator.Result result = validator.validate(fom.toFile());
                if (!result.warnings().isEmpty()) {
                    System.out.println("Avertissements XSD (sans consequence) : " + fom);
                    for (String warning : result.warnings()) {
                        System.out.println(warning);
                    }
                }
                if (!result.isValid()) {
                    anyBlocking = true;
                    String schemaLabel = result.schemaUsed() != null ? result.schemaUsed() : "namespace non reconnu";
                    System.err.println("Fichier FOM invalide (" + schemaLabel + ") : " + fom);
                    for (String problem : result.blockingErrors()) {
                        System.err.println(problem);
                    }
                    System.err.println();
                } else {
                    schemaUsageCounts.merge(result.schemaUsed(), 1, Integer::sum);
                }
            }
            if (anyBlocking) {
                System.err.println("Generation interrompue : corrige les fichiers ci-dessus, ou relance avec");
                System.err.println("--skip-xsd-validation pour forcer la generation malgre les erreurs");
                System.err.println("(deconseille : le comportement du generateur n'est alors plus garanti).");
                System.exit(1);
                return;
            }
            System.out.println("Validation XSD : " + fomFiles.size() + " fichier(s) OK "
                    + "(" + describeSchemaUsage(schemaUsageCounts) + ").");

            if (federateFom != null) {
                // federatesFOMProvider.xsd referencait a l'origine ses types
                // complexes sans le prefixe "tns:" (ex. type="FederateFOMObject"
                // au lieu de type="tns:FederateFOMObject"), ce qui le faisait
                // rejeter des le chargement par un validateur XSD W3C strict --
                // corrige (cf. la ressource bundlee ici), la validation est
                // donc bien active.
                FomXsdValidator federateValidator = new FomXsdValidator("schema/federatesFOMProvider.xsd", false);
                FomXsdValidator.Result result = federateValidator.validate(federateFom.toFile());
                if (!result.warnings().isEmpty()) {
                    System.out.println("Avertissements XSD (sans consequence) : " + federateFom);
                    for (String warning : result.warnings()) {
                        System.out.println(warning);
                    }
                }
                if (!result.isValid()) {
                    System.err.println("Fichier FederateFOMProvider invalide (federatesFOMProvider.xsd) : " + federateFom);
                    for (String problem : result.blockingErrors()) {
                        System.err.println(problem);
                    }
                    System.err.println();
                    System.err.println("Generation interrompue : corrige le fichier ci-dessus, ou relance avec");
                    System.err.println("--skip-xsd-validation pour forcer la generation malgre les erreurs.");
                    System.exit(1);
                    return;
                }
                System.out.println("Validation XSD (federatesFOMProvider.xsd) : " + federateFom + " OK.");
            }
        }

        // ------------------------------------------------------------------
        // 1) Parser et fusionner tous les fichiers FOM dans un seul modele
        // ------------------------------------------------------------------
        FomModel model = new FomModel();
        FomXmlParser parser = new FomXmlParser();
        for (Path fom : fomFiles) {
            System.out.println("Parsing " + fom + " ...");
            parser.parseInto(model, fom.toFile());
        }
        System.out.println("  -> " + model.dataTypes.size() + " types de donnees, arbre objectClass et interactionClass fusionnes.");
        System.out.println();
        // Noms connus AVANT filtrage federe : distingue une faute de frappe (type inconnu)
        // d'un type simplement inutile a ce federe (retire par le filtre).
        java.util.Set<String> knownTypesBeforeFilter = new LinkedHashSet<>(model.dataTypes.keySet());

        // ------------------------------------------------------------------
        // 1bis) Si --federate-fom est fourni : reduire le modele au
        //       sous-ensemble necessaire a ce federe (regles d'heritage
        //       de la FOM respectees, cf. FederateScopeFilter).
        // ------------------------------------------------------------------
        if (federateFom != null) {
            System.out.println("Parsing " + federateFom + " (perimetre du federe) ...");
            FederateFomProviderParser federateParser = new FederateFomProviderParser();
            FederateSelection selection = federateParser.parse(federateFom.toFile());
            if (selection.federateName != null && !selection.federateName.isBlank()) {
                System.out.println("  Federe : " + selection.federateName);
            }
            System.out.println("  -> " + selection.objectAttributes.size() + " objet(s) et "
                    + selection.interactionNames.size() + " interaction(s) demande(s) par le federe.");

            FederateScopeFilter.Result filterResult = new FederateScopeFilter().apply(model, selection);
            for (String warning : filterResult.warnings()) {
                System.out.println("  [avertissement] " + warning);
            }
            System.out.println("  -> types de donnees reduits a " + filterResult.dataTypesKept()
                    + " (" + filterResult.dataTypesRemoved() + " retire(s), non utilise(s) par les "
                    + "objectClass/interactionClass generes) : HLATypes ne contiendra que ceux-ci.");
            System.out.println();
        }

        // ------------------------------------------------------------------
        // 1ter) Types a responsabilite du developpeur : validation COMPLETE
        //       avant toute ecriture (fichiers fournis, empreintes FOM,
        //       collisions...). Une erreur interrompt la generation.
        // ------------------------------------------------------------------
        UserTypeResolver.Plan userPlan = UserTypeResolver.resolve(model, knownTypesBeforeFilter, userTypes, javaPackage);
        for (String warning : userPlan.warnings) {
            System.out.println("  [avertissement types utilisateur] " + warning);
        }
        if (userPlan.hasErrors()) {
            System.err.println();
            for (String error : userPlan.errors) {
                System.err.println("Erreur (types a responsabilite du developpeur) : " + error);
            }
            System.err.println();
            System.err.println("Generation interrompue : rien n'a ete ecrit. Corrigez les points ci-dessus.");
            System.exit(1);
            return;
        }
        if (userPlan.entries.isEmpty()) {
            // Aucun type utilisateur a traiter : option retiree depuis la derniere execution, OU tous les types
            // declares sont ecartes par --federate-fom. Supprime les fichiers utilisateur precedemment
            // ecrits par l'outil (sinon un ancien X.java resterait dans la sortie, perime).
            UserTypeWriter.cleanupOnly(outJava);
            UserTypeWriter.cleanupOnly(outCpp);
        }

        // Nettoyage automatique de la generation precedente (changement de fichier federe, etc.)
        java.util.Set<Path> outDirsSeen = new LinkedHashSet<>();
        int cleaned = 0;
        for (Path outDir : java.util.Arrays.asList(outCpp, outJava, outCppUnreal)) {
            if (outDir == null) continue;
            if (outDirsSeen.add(outDir.toAbsolutePath().normalize())) {
                cleaned += cleanPreviousGeneration(outDir);
            }
        }
        if (cleaned > 0) {
            System.out.println("  Nettoyage : " + cleaned + " fichier(s) de la generation precedente supprime(s) (ecrits par rprfom-codegen).");
        }

        // ------------------------------------------------------------------
        // 2) Generer le C++
        // ------------------------------------------------------------------
        // Meme separation que cote Java (cf. CppGenerator) : cppInclude ne
        // recoit que ce qui reflete directement le FOM (HLATypes.hpp + une
        // classe par objectClass/interactionClass) ; cppRuntimeInclude
        // regroupe toute la mecanique d'encodage/decodage HLA centralisee
        // (statique ou generee), qui ne depend d'aucune classe metier.
        Path cppInclude = outCpp.resolve("include").resolve("rprfom");
        Path cppRuntimeInclude = cppInclude.resolve("runtime");
        Files.createDirectories(cppInclude);
        Files.createDirectories(cppRuntimeInclude);
        copyResource("runtime/cpp/HLAAttribute.hpp", cppRuntimeInclude.resolve("HLAAttribute.hpp"));
        copyResource("runtime/cpp/IHLAAttribute.h", cppRuntimeInclude.resolve("IHLAAttribute.h"));
        copyResource("runtime/cpp/HLAOwnership.hpp", cppRuntimeInclude.resolve("HLAOwnership.hpp"));
        copyResource("runtime/cpp/HLAEncoding.hpp", cppRuntimeInclude.resolve("HLAEncoding.hpp"));
        copyResource("runtime/cpp/HLADecoding.hpp", cppRuntimeInclude.resolve("HLADecoding.hpp"));
        copyResource("runtime/cpp/HLAParameter.hpp", cppRuntimeInclude.resolve("HLAParameter.hpp"));
        copyResource("runtime/cpp/IHLAParameter.h", cppRuntimeInclude.resolve("IHLAParameter.h"));

        CppGenerator cppGen = new CppGenerator(model);
        cppGen.setUserTypes(userPlan.typeNames());
        writeFile(cppInclude.resolve("HLATypes.hpp"), cppGen.generateHLATypes());
        writeFile(cppRuntimeInclude.resolve("HLAAlignment.hpp"), cppGen.generateHLAAlignment());
        writeFile(cppRuntimeInclude.resolve("HLAEncoder.hpp"), cppGen.generateHLAEncoder());
        writeFile(cppRuntimeInclude.resolve("HLADecoder.hpp"), cppGen.generateHLADecoder());

        List<GeneratedClass> classes = collectObjectClasses(model);
        for (GeneratedClass gc : classes) {
            String content = cppGen.generateObjectClass(gc.node, gc.path, gc.parent);
            writeFile(cppInclude.resolve(NameUtils.sanitizeIdentifier(gc.node.name) + ".hpp"), content);
        }
        List<GeneratedInteraction> interactions = collectInteractionClasses(model);
        for (GeneratedInteraction gi : interactions) {
            String content = cppGen.generateInteractionClass(gi.node, gi.path, gi.parent);
            writeFile(cppInclude.resolve(NameUtils.sanitizeIdentifier(gi.node.name) + ".hpp"), content);
        }
        if (!userPlan.entries.isEmpty()) {
            UserTypeWriter.writeCpp(userPlan, cppGen, outCpp, userTypes);
        }
        System.out.println("C++ genere : " + (7 + classes.size() + interactions.size())
                + " fichiers statiques/generes -- " + cppInclude + " (classes FOM : HLATypes + "
                + classes.size() + " objectClass, " + interactions.size() + " interactionClass), "
                + cppRuntimeInclude + " (encodage/runtime HLA)");

        // ------------------------------------------------------------------
        // 3) Generer le Java
        // ------------------------------------------------------------------
        // Deux dossiers/packages distincts : javaSrc (package javaPackage)
        // recoit uniquement ce qui reflete directement le FOM (HLATypes.java
        // + une classe par objectClass/interactionClass) ; javaRuntimeSrc
        // (sous-package "<javaPackage>.runtime") regroupe toute la mecanique
        // d'encodage/decodage HLA centralisee (statique ou generee), qui ne
        // depend d'aucune classe metier -- cf. Javadoc de JavaGenerator.
        Path javaSrc = outJava.resolve("src").resolve("main").resolve("java")
                .resolve(javaPackage.replace('.', '/'));
        Path javaRuntimeSrc = javaSrc.resolve("runtime");
        String javaRuntimePackage = javaPackage + ".runtime";
        Files.createDirectories(javaSrc);
        Files.createDirectories(javaRuntimeSrc);
        for (String staticFile : List.of(
                "IHLAAttributeBase.java", "HLAAttribute.java", "HLAEncoding.java", "HLADecoding.java",
                "HLAEncoderDetail.java", "HLADecoderDetail.java", "OwnershipState.java",
                "HLAOctet.java", "HLAUnsignedInteger16.java", "HLAUnsignedInteger32.java",
                "HLAInteger16LE.java", "HLAUnsignedInteger16LE.java",
                "HLAInteger32LE.java", "HLAUnsignedInteger32LE.java",
                "HLAInteger64LE.java", "HLAUnsignedInteger64.java", "HLAUnsignedInteger64LE.java",
                "HLAFloat32LE.java", "HLAFloat64LE.java",
                "HLAOctetPair.java", "HLAASCIIString.java",
                "IHLAParameterBase.java", "HLAParameter.java")) {
            copyResourceAsJavaPackage("runtime/java/" + staticFile, javaRuntimeSrc.resolve(staticFile), javaRuntimePackage);
        }

        JavaGenerator javaGen = new JavaGenerator(model, javaPackage);
        javaGen.setUserTypes(userPlan.typeNames());
        writeFile(javaSrc.resolve("HLATypes.java"), javaGen.generateHLATypes());
        writeFile(javaRuntimeSrc.resolve("HLAAlignment.java"), javaGen.generateHLAAlignment());
        writeFile(javaRuntimeSrc.resolve("HLAEncoder.java"), javaGen.generateHLAEncoder());
        writeFile(javaRuntimeSrc.resolve("HLADecoder.java"), javaGen.generateHLADecoder());
        for (GeneratedClass gc : classes) {
            String content = javaGen.generateObjectClass(gc.node, gc.path, gc.parent);
            writeFile(javaSrc.resolve(NameUtils.sanitizeIdentifier(gc.node.name) + ".java"), content);
        }
        for (GeneratedInteraction gi : interactions) {
            String content = javaGen.generateInteractionClass(gi.node, gi.path, gi.parent);
            writeFile(javaSrc.resolve(NameUtils.sanitizeIdentifier(gi.node.name) + ".java"), content);
        }
        boolean withConformance = !userPlan.entries.isEmpty() && !userTypes.skipConformanceTest;
        writeFile(outJava.resolve("pom.xml"), generatePom(javaPackage, withConformance));
        if (!userPlan.entries.isEmpty()) {
            UserTypeWriter.writeJava(userPlan, javaGen, outJava, javaPackage, userTypes);
        }
        System.out.println("Java genere : " + (12 + classes.size() + interactions.size())
                + " fichiers statiques/generes -- " + javaSrc + " (classes FOM : HLATypes + "
                + classes.size() + " objectClass, " + interactions.size() + " interactionClass), "
                + javaRuntimeSrc + " (encodage/runtime HLA)");

        // ------------------------------------------------------------------
        // 4) Generer la couche de presentation Unreal (optionnel, --out-cpp-unreal)
        // ------------------------------------------------------------------
        // Couche de PRESENTATION Blueprint uniquement (UCLASS/USTRUCT/UENUM +
        // conversions vers/depuis le natif rprfom:: deja genere ci-dessus) : ne
        // reimplemente jamais l'encodage/decodage HLA (cf. Javadoc d'UnrealGenerator).
        // Fonctionne sur le MEME modele deja filtre par --federate-fom que les deux
        // autres sorties (pas de filtrage specifique a cette sortie) ; un type "a
        // responsabilite du developpeur" recoit son miroir Unreal normalement (cette
        // sortie ne regarde que la forme FOM du type, jamais son implementation).
        if (outCppUnreal != null) {
            UnrealGenerator unrealGen = new UnrealGenerator(model);
            Files.createDirectories(outCppUnreal);
            writeFile(outCppUnreal.resolve("RPRFOMUnrealTypes.h"), unrealGen.generateTypesHeader());
            writeFile(outCppUnreal.resolve("RPRFOMUnrealTypes.cpp"), unrealGen.generateTypesConversions());
            for (GeneratedClass gc : classes) {
                String header = unrealGen.generateObjectClassHeader(gc.node, gc.path, gc.parent);
                String cpp = unrealGen.generateObjectClassCpp(gc.node, gc.path, gc.parent);
                writeFile(outCppUnreal.resolve(NameUtils.sanitizeIdentifier(gc.node.name) + ".h"), header);
                writeFile(outCppUnreal.resolve(NameUtils.sanitizeIdentifier(gc.node.name) + ".cpp"), cpp);
            }
            for (GeneratedInteraction gi : interactions) {
                String header = unrealGen.generateInteractionClassHeader(gi.node, gi.path, gi.parent);
                String cpp = unrealGen.generateInteractionClassCpp(gi.node, gi.path, gi.parent);
                writeFile(outCppUnreal.resolve(NameUtils.sanitizeIdentifier(gi.node.name) + ".h"), header);
                writeFile(outCppUnreal.resolve(NameUtils.sanitizeIdentifier(gi.node.name) + ".cpp"), cpp);
            }
            System.out.println("Unreal genere (couche de presentation Blueprint) : "
                    + (2 + 2 * (classes.size() + interactions.size())) + " fichier(s) .h/.cpp -- " + outCppUnreal
                    + " (" + classes.size() + " objectClass, " + interactions.size() + " interactionClass). "
                    + "A copier dans PluginUnrealHLA (aucune macro d'export *_API emise).");
        }

        if (!userPlan.entries.isEmpty()) {
            UserTypeWriter.printReport(userPlan, userTypes, outJava, outCpp);
            for (String warning : userPlan.warnings) {
                if (warning.startsWith("Le fichier '") || warning.contains("reecrit")) {
                    System.out.println("  [avertissement types utilisateur] " + warning);
                }
            }
        }

        java.util.Set<Path> savedSeen = new LinkedHashSet<>();
        for (Path outDir : java.util.Arrays.asList(outCpp, outJava, outCppUnreal)) {
            if (outDir == null) continue;
            if (savedSeen.add(outDir.toAbsolutePath().normalize())) {
                saveGeneratedManifest(outDir);
            }
        }

        System.out.println();
        System.out.println("Termine.");
    }

    // ====================================================================

    private record GeneratedClass(ObjectClassNode node, List<String> path, ObjectClassNode parent) {
    }

    private record GeneratedInteraction(InteractionClassNode node, List<String> path, InteractionClassNode parent) {
    }

    /** Parcourt l'arbre (hors HLAobjectRoot) et produit la liste ordonnee (parents avant enfants) des classes a generer. */
    private static List<GeneratedClass> collectObjectClasses(FomModel model) {
        List<GeneratedClass> result = new ArrayList<>();
        for (ObjectClassNode child : model.root.children.values()) {
            walk(child, List.of(model.root.name, child.name), null, result);
        }
        return result;
    }

    private static void walk(ObjectClassNode node, List<String> path, ObjectClassNode parent, List<GeneratedClass> out) {
        out.add(new GeneratedClass(node, path, parent));
        for (ObjectClassNode child : node.children.values()) {
            List<String> childPath = new ArrayList<>(path);
            childPath.add(child.name);
            walk(child, childPath, node, out);
        }
    }

    /** Miroir de collectObjectClasses, pour l'arbre des interactionClass (hors HLAinteractionRoot). */
    private static List<GeneratedInteraction> collectInteractionClasses(FomModel model) {
        List<GeneratedInteraction> result = new ArrayList<>();
        for (InteractionClassNode child : model.interactionRoot.children.values()) {
            walkInteraction(child, List.of(model.interactionRoot.name, child.name), null, result);
        }
        return result;
    }

    private static void walkInteraction(InteractionClassNode node, List<String> path, InteractionClassNode parent, List<GeneratedInteraction> out) {
        out.add(new GeneratedInteraction(node, path, parent));
        for (InteractionClassNode child : node.children.values()) {
            List<String> childPath = new ArrayList<>(path);
            childPath.add(child.name);
            walkInteraction(child, childPath, node, out);
        }
    }

    /**
     * Formate un recapitulatif du nombre de fichiers valides par schema
     * effectivement applique (ex. "6 fichier(s) via IEEE1516-DIF-2025.xsd"),
     * pour que l'utilisateur voie explicitement quand un fichier a ete
     * reconnu comme relevant de l'ancien namespace 2010 plutot que du
     * nouveau 2025 (cf. FomXsdValidator).
     */
    private static String describeSchemaUsage(Map<String, Integer> countsBySchema) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : countsBySchema.entrySet()) {
            parts.add(entry.getValue() + " via " + entry.getKey());
        }
        return String.join(", ", parts);
    }

    /** Fichiers ecrits par CETTE execution (hors types utilisateur, qui ont leur propre manifeste). */
    private static final java.util.Set<Path> GENERATED_FILES = new LinkedHashSet<>();
    private static final String GENERATED_MANIFEST = ".rprfom-generated.manifest";

    private static void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        GENERATED_FILES.add(path.toAbsolutePath().normalize());
    }

    /**
     * Nettoyage automatique : supprime les fichiers ecrits par l'execution PRECEDENTE dans ce dossier de
     * sortie (liste dans .rprfom-generated.manifest), puis les dossiers devenus vides. Evite qu'un ancien
     * fichier federe (classes d'objets/interactions qui ne sont plus dans le perimetre) reste et casse la
     * compilation. Les fichiers non ecrits par l'outil ne sont jamais touches. Retourne le nombre supprime.
     */
    private static int cleanPreviousGeneration(Path outDir) throws IOException {
        Path root = outDir.toAbsolutePath().normalize();
        Path manifest = root.resolve(GENERATED_MANIFEST);
        if (!Files.isRegularFile(manifest)) return 0;
        int removed = 0;
        java.util.TreeSet<Path> dirs = new java.util.TreeSet<>(java.util.Comparator.<Path>comparingInt(Path::getNameCount).reversed()
                .thenComparing(java.util.Comparator.<Path>naturalOrder()));
        for (String line : Files.readAllLines(manifest)) {
            if (line.isBlank()) continue;
            Path f = root.resolve(line.trim()).normalize();
            if (!f.startsWith(root) || f.equals(root)) continue; // securite : jamais hors du dossier de sortie
            if (Files.isRegularFile(f)) {
                Files.delete(f);
                removed++;
            }
            for (Path d = f.getParent(); d != null && d.startsWith(root) && !d.equals(root); d = d.getParent()) dirs.add(d);
        }
        Files.deleteIfExists(manifest);
        for (Path d : dirs) { // plus profonds d'abord
            if (Files.isDirectory(d)) {
                try (var st = Files.list(d)) {
                    if (st.findAny().isEmpty()) Files.delete(d);
                }
            }
        }
        return removed;
    }

    private static void saveGeneratedManifest(Path outDir) throws IOException {
        Path root = outDir.toAbsolutePath().normalize();
        List<String> lines = new ArrayList<>();
        for (Path f : GENERATED_FILES) {
            if (f.startsWith(root)) lines.add(root.relativize(f).toString().replace('\\', '/'));
        }
        Files.createDirectories(root);
        Files.write(root.resolve(GENERATED_MANIFEST), lines);
    }

    private static void copyResource(String resourcePath, Path dest) throws IOException {
        try (InputStream in = Main.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) throw new IOException("Ressource introuvable: " + resourcePath);
            Files.createDirectories(dest.getParent());
            try (OutputStream out = Files.newOutputStream(dest)) {
                in.transferTo(out);
            }
            GENERATED_FILES.add(dest.toAbsolutePath().normalize());
        }
    }

    /** Copie un .java statique en remplacant son "package rprfom;" par le package cible demande. */
    private static void copyResourceAsJavaPackage(String resourcePath, Path dest, String javaPackage) throws IOException {
        try (InputStream in = Main.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) throw new IOException("Ressource introuvable: " + resourcePath);
            String content = new String(in.readAllBytes());
            content = content.replaceFirst("^package rprfom;", "package " + javaPackage + ";");
            Files.createDirectories(dest.getParent());
            Files.writeString(dest, content);
            GENERATED_FILES.add(dest.toAbsolutePath().normalize());
        }
    }

    private static String generatePom(String javaPackage, boolean withConformance) {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <groupId>com.rprfom.generated</groupId>
                    <artifactId>rprfom-generated</artifactId>
                    <version>1.0.0</version>
                    <packaging>jar</packaging>

                    <properties>
                        <maven.compiler.source>17</maven.compiler.source>
                        <maven.compiler.target>17</maven.compiler.target>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    </properties>

                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <version>3.13.0</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """.replace("com.rprfom.generated", javaPackage);
        if (withConformance) {
            // Test de conformite des types a responsabilite du developpeur, lance par "mvn test"
            // (sans JUnit : le programme leve une exception en cas d'ecart, ce qui fait echouer le build).
            String plugin = """
                            <plugin>
                                <groupId>org.codehaus.mojo</groupId>
                                <artifactId>exec-maven-plugin</artifactId>
                                <version>3.2.0</version>
                                <executions>
                                    <execution>
                                        <id>user-types-conformance</id>
                                        <phase>test</phase>
                                        <goals>
                                            <goal>java</goal>
                                        </goals>
                                        <configuration>
                                            <mainClass>__PKG__.conformance.UserTypesConformanceCheck</mainClass>
                                            <classpathScope>test</classpathScope>
                                        </configuration>
                                    </execution>
                                </executions>
                            </plugin>
                    """.replace("__PKG__", javaPackage);
            pom = pom.replace("        </plugins>", plugin + "        </plugins>");
        }
        return pom;
    }

    private static void printUsage() {
        System.out.println("""
                Usage: rprfom-codegen --fom <fichier.xml> [--fom <fichier2.xml> ...] \
                --out-cpp <dossier> --out-java <dossier> [--java-package <package>] \
                [--federate-fom <fichier.xml>] [--skip-xsd-validation]

                  --fom <fichier>        Un fichier FOM XML (OMT). Repetable pour fusionner
                                          plusieurs fichiers (ex. RPR-Base + RPR-Physical +
                                          un fichier d'enrichissement personnalise).
                  --out-cpp <dossier>    Dossier de sortie du projet C++ (cree include/rprfom/...).
                                          Les classes generees a partir du FOM vivent dans
                                          include/rprfom/ ; l'encodage/decodage HLA centralise
                                          (commun a tout FOM) vit a part dans
                                          include/rprfom/runtime/.
                  --out-java <dossier>   Dossier de sortie du projet Java (Maven, cree pom.xml + src/...).
                  --out-cpp-unreal <dossier>
                                          OPTIONNEL. Genere une couche de presentation Blueprint
                                          (UCLASS/USTRUCT/UENUM, fichiers .h/.cpp a plat) au-dessus
                                          du C++ de --out-cpp : jamais de reimplementation de
                                          l'encodage/decodage HLA, uniquement des donnees + des
                                          fonctions de conversion generees. objectClass/interactionClass
                                          -> UCLASS (heritage Unreal reel) ; fixedRecordData/
                                          variantRecordData/enumeratedData -> USTRUCT/UENUM. Un
                                          variantRecordData expose son discriminant ET tous les champs
                                          de toutes les alternatives (jamais seulement l'alternative
                                          active). HLAoctetPairBE/LE est exclu de cette sortie. Les
                                          entiers non signes larges sont elargis vers un entier signe
                                          Blueprint-natif (perte de portee acceptee uniquement sur
                                          HLAunsignedInteger64). Fichiers prevus pour etre copies dans
                                          un module Unreal existant ou futur (aucune macro d'export
                                          *_API emise ; dependance vers --out-cpp par simple #include).
                  --java-package <pkg>   Package Java des classes generees (defaut: rprfom).
                                          Les classes generees a partir du FOM vivent dans
                                          <pkg> ; l'encodage/decodage HLA centralise (commun
                                          a tout FOM) vit a part dans le sous-package et
                                          sous-dossier <pkg>.runtime.
                  --federate-fom <fichier>
                                          Fichier FederateFOMProvider (conforme a
                                          federatesFOMProvider.xsd, produit par l'outil
                                          FOMProvider, valide contre ce schema avant
                                          generation) : ne genere que les objectClass/
                                          interactionClass necessaires a ce federe, avec
                                          UNIQUEMENT les attributs qu'il liste (jamais la
                                          totalite des attributs d'une classe ancetre) --
                                          un objet/une interaction enfant tire ses classes
                                          ancetres (necessaires a l'heritage), et un
                                          attribut herite est place sur la classe qui le
                                          declare reellement dans la FOM. Absent : genere
                                          tout, comme avant (comportement par defaut
                                          inchange).
                  --user-type <Nom>      Declare un type FOM (fixedRecordData) "a responsabilite
                                          du developpeur" : sa classe n'est plus generee dans
                                          HLATypes mais copiee depuis un fichier fourni, ou, a
                                          defaut, depuis une CLASSE DE SECOURS generee (identique
                                          au code standard). Repetable.
                  --user-type-java <Nom>=<fichier.java>
                                          Fichier Java du type (declare aussi le type).
                  --user-type-cpp <Nom>=<X.hpp>[,<XCodec.hpp>]
                                          Fichiers C++ du type : la struct et son codec
                                          (defaut du codec : <Nom>Codec.hpp a cote).
                  --user-types-java <dossier>
                                          Convention : <dossier>/<Nom>.java pour chaque type declare.
                  --user-types-cpp <dossier>
                                          Convention : <dossier>/<Nom>.hpp + <Nom>Codec.hpp.
                                          Priorite : fichier explicite > dossier > classe de secours.
                                          Un fichier du dossier qui ne correspond a aucun type
                                          declare est signale (faute de frappe probable).
                  --user-types-config <fichier.xml>
                                          Fichier XML associant a chaque type son dossier Java
                                          et/ou son dossier C++ (declare aussi les types) :
                                          <userTypes><userType name="X"><java dir="..."/>
                                          <cpp dir="..."/></userType></userTypes>. Chemins
                                          relatifs au fichier XML. Les options explicites
                                          --user-type-java/-cpp restent prioritaires.
                  --fallback-dir <dossier>
                                          Dossier des classes de secours (sous-dossiers java/ et
                                          cpp/usertypes/). Defaut : <out-java>/fallback et
                                          <out-cpp>/fallback. Toujours regenere : c'est aussi le
                                          modele a copier pour personnaliser un type.
                  --strict-user-types    Un fichier absent (au lieu du secours) ou sans empreinte
                                          FOM devient une erreur.
                  --allow-stale-user-types
                                          Une empreinte FOM perimee devient un avertissement au
                                          lieu d'une erreur (deconseille : encodage possiblement
                                          non conforme a la FOM).
                  --skip-conformance-test
                                          Ne genere pas les tests de conformite des types
                                          utilisateur (Java : src/test, lance par mvn test ;
                                          C++ : test/UserTypesConformance.cpp).
                  --skip-xsd-validation  Desactive la validation des fichiers --fom contre le
                                          schema IEEE1516-DIF (2025 ou 2010, detecte
                                          automatiquement d'apres le namespace de chaque
                                          fichier) et, le cas echeant, du fichier
                                          --federate-fom contre federatesFOMProvider.xsd
                                          (deconseille).
                """);
    }
}
