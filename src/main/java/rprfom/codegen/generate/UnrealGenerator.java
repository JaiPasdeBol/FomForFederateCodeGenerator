package rprfom.codegen.generate;

import rprfom.codegen.model.DataType;
import rprfom.codegen.model.FomModel;
import rprfom.codegen.model.InteractionClassNode;
import rprfom.codegen.model.ObjectClassNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Genere resultatCppUnreal : une COUCHE DE PRESENTATION Blueprint (USTRUCT/
 * UENUM/UCLASS) au-dessus du C++ deja genere par {@link CppGenerator}
 * (resultatCpp), jamais une reimplementation de l'encodage/decodage HLA.
 *
 * Decisions (actees avec l'utilisateur avant implementation, cf. le fichier
 * de suivi du projet) :
 *   - UNIQUEMENT des donnees sont exposees en Blueprint ; pas de fonctions
 *     d'encodage/decodage.
 *   - objectClass/interactionClass -> UCLASS (UObject), avec heritage
 *     Unreal reel qui suit l'heritage FOM. Les deux autres sorties
 *     generent deja le natif rprfom::X correspondant (dependance par un
 *     simple #include, jamais de dependance inverse).
 *   - fixedRecordData/variantRecordData/enumeratedData -> USTRUCT/UENUM,
 *     avec des fonctions de conversion ToUnreal_X()/ToNative_X() generees
 *     pour chaque type compose (y compris un type "a responsabilite du
 *     developpeur" : cette sortie ne regarde que la FORME FOM du type,
 *     jamais son implementation d'encodage -- le miroir Unreal est donc
 *     automatiquement genere que le type soit CUSTOM ou SECOURS cote
 *     Java/C++, cf. UserTypeWriter).
 *   - variantRecordData : option (a) -- un champ discriminant PLUS tous
 *     les champs de toutes les alternatives presents simultanement
 *     (jamais seulement l'alternative active, contrairement au natif qui
 *     utilise un std::variant).
 *   - HLAoctetPairBE/LE (paire d'octets brute) : type exclu de cette
 *     sortie (ni champ, ni parametre, ni UPROPERTY).
 *   - Entiers non signes larges (HLAunsignedInteger32/64) : elargis vers
 *     un entier SIGNE Blueprint-natif (int32/int64) -- perte de portee
 *     acceptee uniquement sur HLAunsignedInteger64.
 *   - Ownership (HLAAttribute&lt;T&gt;) expose par des fonctions separees
 *     (Get/Set/IsXxxOwned), jamais par un UPROPERTY direct -- un Set est
 *     un no-op silencieux si l'attribut n'est pas possede. La publication
 *     RTI elle-meme reste geree ailleurs (HLAabstract core, PluginUnrealHLA).
 *   - Pas de macro d'export (*_API) : PluginUnrealHLA n'existe pas encore,
 *     ces fichiers .h/.cpp sont prevus pour y etre copies plus tard.
 */
public final class UnrealGenerator {

    private final FomModel model;
    private final TypeMapper mapper;

    public UnrealGenerator(FomModel model) {
        this.model = model;
        this.mapper = new TypeMapper(model);
    }

    // ====================================================================
    // Resolution de type FOM -> Unreal (miroir de TypeMapper.cppTypeName,
    // cote Unreal : memes redirections simpleData -> representation/array,
    // mais destination UCLASS/USTRUCT/UENUM/TArray/FString a la place de
    // C++ natif).
    // ====================================================================

    private enum Kind { SCALAR, ENUM, STRUCT, VARIANT, ARRAY_NAMED, ARRAY_INLINE }

    /** true si le type (ou, recursivement, l'element d'un tableau) est HLAoctetPairBE/LE : exclu de cette sortie. */
    public boolean isExcludedFromUnreal(String dataTypeName) {
        TypeMapper.BasicRepr basic = mapper.resolveBasicRepr(dataTypeName);
        if (basic != null) return isOctetPairRepr(basic.representation);
        DataType.Array array = mapper.resolveArrayType(dataTypeName);
        if (array != null) return isExcludedFromUnreal(array.elementDataTypeName);
        return false;
    }

    private static boolean isOctetPairRepr(String representation) {
        return representation.equals("HLAoctetPairBE") || representation.equals("HLAoctetPairLE");
    }

    /** Type Blueprint-natif pour une representation de base HLA, ou null si exclue (paire d'octets). */
    private static String unrealScalarType(String representation) {
        return switch (representation) {
            case "HLAoctet", "HLAunsignedInteger8", "RPRunsignedInteger8BE", "HLAbyte", "HLAASCIIchar" -> "uint8";
            case "HLAinteger16BE", "HLAinteger16LE", "HLAunicodeChar",
                 "HLAunsignedInteger16BE", "RPRunsignedInteger16BE", "HLAunsignedInteger16LE" -> "int32";
            case "HLAinteger32BE", "HLAinteger32LE" -> "int32";
            case "HLAunsignedInteger32BE", "RPRunsignedInteger32BE", "HLAunsignedInteger32LE" -> "int64";
            case "HLAinteger64BE", "HLAinteger64LE", "HLAinteger64Time" -> "int64";
            // Perte de portee ACCEPTEE (decision explicite) : seul cas ou l'elargissement n'est pas exact.
            case "HLAunsignedInteger64BE", "RPRunsignedInteger64BE", "HLAunsignedInteger64LE" -> "int64";
            case "HLAfloat32BE", "HLAfloat32LE" -> "float";
            case "HLAfloat64BE", "HLAfloat64LE", "HLAfloat64Time" -> "double";
            case "HLAboolean" -> "bool";
            case "HLAASCIIstring", "HLAunicodeString" -> "FString";
            case "HLAoctetPairBE", "HLAoctetPairLE" -> null;
            default -> throw new IllegalStateException(
                    "Representation HLA non prise en charge par le generateur Unreal : '" + representation + "'");
        };
    }

    /** Type de champ/UPROPERTY Unreal pour un type FOM. N'appeler que si !isExcludedFromUnreal(dataTypeName). */
    public String unrealFieldType(String dataTypeName) {
        TypeMapper.BasicRepr basic = mapper.resolveBasicRepr(dataTypeName);
        if (basic != null) {
            String t = unrealScalarType(basic.representation);
            if (t == null) {
                throw new IllegalStateException("unrealFieldType appele sur un type exclu : " + dataTypeName);
            }
            return t;
        }
        DataType.Array array = mapper.resolveArrayType(dataTypeName);
        if (array != null) {
            return "TArray<" + unrealFieldType(array.elementDataTypeName) + ">";
        }
        DataType dt = model.dataType(dataTypeName);
        if (dt instanceof DataType.Enumerated e) {
            return NameUtils.unrealEnumName(e.name);
        }
        if (dt instanceof DataType.FixedRecord r) {
            return NameUtils.unrealStructName(r.name);
        }
        if (dt instanceof DataType.VariantRecord v) {
            return NameUtils.unrealStructName(v.name);
        }
        throw new IllegalStateException("Type FOM non pris en charge par le generateur Unreal : '" + dataTypeName + "'");
    }

    /** Expression C++ convertissant une valeur NATIVE (rprfom::...) designee par nativeExpr en valeur Unreal. */
    private String toUnrealExpr(String dataTypeName, String nativeExpr) {
        TypeMapper.BasicRepr basic = mapper.resolveBasicRepr(dataTypeName);
        if (basic != null) {
            String repr = basic.representation;
            if (repr.equals("HLAASCIIstring")) return "FString((" + nativeExpr + ").c_str())";
            if (repr.equals("HLAunicodeString")) return "RPRFOM_ToUnrealUnicodeString(" + nativeExpr + ")";
            String t = unrealScalarType(repr);
            if (t == null) throw new IllegalStateException("toUnrealExpr appele sur un type exclu : " + dataTypeName);
            return "static_cast<" + t + ">(" + nativeExpr + ")";
        }
        DataType.Array array = mapper.resolveArrayType(dataTypeName);
        if (array != null) {
            if (array.isDynamic()) {
                return "ToUnreal_" + NameUtils.sanitizeIdentifier(array.name) + "(" + nativeExpr + ")";
            }
            return inlineArrayToUnreal(array, nativeExpr);
        }
        DataType dt = model.dataType(dataTypeName);
        if (dt instanceof DataType.Enumerated e) {
            return "static_cast<" + NameUtils.unrealEnumName(e.name) + ">(static_cast<int64_t>(" + nativeExpr + "))";
        }
        if (dt instanceof DataType.FixedRecord r) {
            return "ToUnreal_" + NameUtils.sanitizeIdentifier(r.name) + "(" + nativeExpr + ")";
        }
        if (dt instanceof DataType.VariantRecord v) {
            return "ToUnreal_" + NameUtils.sanitizeIdentifier(v.name) + "(" + nativeExpr + ")";
        }
        throw new IllegalStateException("Type FOM non pris en charge par le generateur Unreal : '" + dataTypeName + "'");
    }

    /**
     * Comme TypeMapper.cppTypeName, mais pleinement qualifie (prefixe "rprfom::") pour un usage
     * HORS du namespace rprfom -- necessaire ici car tout le code genere par UnrealGenerator (y
     * compris les tableaux fixes inline, std::array&lt;Element, N&gt;) vit dans le namespace global,
     * alors que cppTypeName suppose toujours d'etre appele depuis l'INTERIEUR de "namespace rprfom"
     * (cf. CppGenerator) et renvoie donc des noms non qualifies ("Octet", "Float32", "MonType"...).
     */
    private String nativeTypeOutsideNamespace(String dataTypeName) {
        TypeMapper.BasicRepr basic = mapper.resolveBasicRepr(dataTypeName);
        if (basic != null) {
            String t = basic.fieldCppType;
            if (t.equals("bool") || t.startsWith("std::")) return t;
            return "rprfom::" + t;
        }
        DataType.Array array = mapper.resolveArrayType(dataTypeName);
        if (array != null) {
            if (array.isDynamic()) return "rprfom::" + NameUtils.sanitizeIdentifier(array.name);
            return "std::array<" + nativeTypeOutsideNamespace(array.elementDataTypeName) + ", " + array.fixedCardinality + ">";
        }
        DataType dt = model.dataType(dataTypeName);
        if (dt instanceof DataType.Enumerated e) return "rprfom::" + NameUtils.sanitizeIdentifier(e.name);
        if (dt instanceof DataType.FixedRecord r) return "rprfom::" + NameUtils.sanitizeIdentifier(r.name);
        if (dt instanceof DataType.VariantRecord v) return "rprfom::" + NameUtils.sanitizeIdentifier(v.name);
        throw new IllegalStateException("Type FOM non pris en charge par le generateur Unreal : '" + dataTypeName + "'");
    }

    /** Inverse de toUnrealExpr : expression C++ convertissant une valeur Unreal designee par unrealExpr en valeur NATIVE. */
    private String toNativeExpr(String dataTypeName, String unrealExpr) {
        TypeMapper.BasicRepr basic = mapper.resolveBasicRepr(dataTypeName);
        if (basic != null) {
            String repr = basic.representation;
            if (repr.equals("HLAASCIIstring")) {
                return "std::string(TCHAR_TO_UTF8(*(" + unrealExpr + ")))";
            }
            if (repr.equals("HLAunicodeString")) {
                return "RPRFOM_ToNativeUnicodeString(" + unrealExpr + ")";
            }
            String nativeType = nativeTypeOutsideNamespace(dataTypeName);
            return "static_cast<" + nativeType + ">(" + unrealExpr + ")";
        }
        DataType.Array array = mapper.resolveArrayType(dataTypeName);
        if (array != null) {
            if (array.isDynamic()) {
                return "ToNative_" + NameUtils.sanitizeIdentifier(array.name) + "(" + unrealExpr + ")";
            }
            return inlineArrayToNative(array, unrealExpr);
        }
        DataType dt = model.dataType(dataTypeName);
        if (dt instanceof DataType.Enumerated) {
            String nativeType = nativeTypeOutsideNamespace(dataTypeName);
            return "static_cast<" + nativeType + ">(static_cast<int64_t>(" + unrealExpr + "))";
        }
        if (dt instanceof DataType.FixedRecord r) {
            return "ToNative_" + NameUtils.sanitizeIdentifier(r.name) + "(" + unrealExpr + ")";
        }
        if (dt instanceof DataType.VariantRecord v) {
            return "ToNative_" + NameUtils.sanitizeIdentifier(v.name) + "(" + unrealExpr + ")";
        }
        throw new IllegalStateException("Type FOM non pris en charge par le generateur Unreal : '" + dataTypeName + "'");
    }

    /**
     * HLAfixedArray (tableau de cardinalite FIXE) : jamais de type nomme cote natif (toujours un
     * std::array&lt;Element, N&gt; inline, cf. TypeMapper.cppTypeName) -- donc pas de fonction de
     * conversion dediee possible, l'expression est generee en ligne (lambda immediatement invoquee)
     * a chaque point d'usage. Si le TArray fourni par Blueprint a plus/moins de N elements, le
     * resultat est tronque/complete par defaut plutot que de planter.
     */
    private String inlineArrayToUnreal(DataType.Array array, String nativeExpr) {
        String elemUnreal = unrealFieldType(array.elementDataTypeName);
        String elemExpr = toUnrealExpr(array.elementDataTypeName, "__e");
        return "([&]{ TArray<" + elemUnreal + "> __r; __r.Reserve((int32)(" + nativeExpr + ").size()); "
                + "for (auto const& __e : " + nativeExpr + ") { __r.Add(" + elemExpr + "); } return __r; }())";
    }

    private String inlineArrayToNative(DataType.Array array, String unrealExpr) {
        String elemNative = nativeTypeOutsideNamespace(array.elementDataTypeName);
        String elemExpr = toNativeExpr(array.elementDataTypeName, "(" + unrealExpr + ")[__i]");
        return "([&]{ std::array<" + elemNative + ", " + array.fixedCardinality + "> __r{}; "
                + "for (int32 __i = 0; __i < (" + unrealExpr + ").Num() && __i < " + array.fixedCardinality + "; ++__i) "
                + "{ __r[__i] = " + elemExpr + "; } return __r; }())";
    }

    // ====================================================================
    // RPRFOMUnrealTypes.h / .cpp : UENUM/USTRUCT + conversions, pour tous
    // les types de base composes (hors HLAfixedArray, inline -- cf.
    // ci-dessus -- et hors paire d'octets, exclue).
    // ====================================================================

    public String generateTypesHeader() {
        StringBuilder sb = new StringBuilder();
        sb.append("#pragma once\n//\n// RPRFOMUnrealTypes.h -- GENERE depuis le(s) fichier(s) FOM.\n")
          .append("// Miroirs Blueprint (USTRUCT/UENUM) des types de base RPR-FOM, et leurs\n")
          .append("// fonctions de conversion vers/depuis les types natifs (resultatCpp).\n")
          .append("// Couche de PRESENTATION uniquement : aucun encodage/decodage HLA ici.\n")
          .append("//\n// A placer, avec HLATypes.hpp (resultatCpp), dans les chemins d'inclusion\n")
          .append("// du module Unreal qui recevra ces fichiers (aucune macro d'export *_API\n")
          .append("// n'est emise : le module PluginUnrealHLA n'existe pas encore).\n//\n\n");
        sb.append("#include \"CoreMinimal.h\"\n");
        sb.append("#include <cstdint>\n#include <string>\n\n");
        sb.append("#include \"HLATypes.hpp\"\n\n");
        sb.append("#include \"RPRFOMUnrealTypes.generated.h\"\n\n");

        sb.append("// Conversion dediee (pas de fonction ToUnreal/ToNative generique pour les\n");
        sb.append("// chaines Unicode : utilisee aussi bien pour un champ direct que pour un\n");
        sb.append("// element de tableau ou une alternative de variant).\n");
        sb.append("FString RPRFOM_ToUnrealUnicodeString(const std::u16string& Native);\n");
        sb.append("std::u16string RPRFOM_ToNativeUnicodeString(const FString& Unreal);\n\n");

        for (DataType dt : mapper.topologicalOrder()) {
            if (dt instanceof DataType.Enumerated e) {
                sb.append(generateEnum(e));
            } else if (dt instanceof DataType.FixedRecord r) {
                sb.append(generateStructDecl(r.name, toFields(r)));
            } else if (dt instanceof DataType.VariantRecord v) {
                sb.append(generateVariantStructDecl(v));
            } else if (dt instanceof DataType.Array array && array.isDynamic() && isGeneratableNamedArray(array)) {
                sb.append(generateNamedArrayDecl(array));
            }
        }
        return sb.toString();
    }

    public String generateTypesConversions() {
        StringBuilder sb = new StringBuilder();
        sb.append("//\n// RPRFOMUnrealTypes.cpp -- GENERE depuis le(s) fichier(s) FOM.\n//\n\n");
        sb.append("#include \"RPRFOMUnrealTypes.h\"\n\n");

        sb.append("FString RPRFOM_ToUnrealUnicodeString(const std::u16string& Native) {\n")
          .append("    return FString(reinterpret_cast<const UTF16CHAR*>(Native.c_str()));\n}\n\n");
        sb.append("std::u16string RPRFOM_ToNativeUnicodeString(const FString& Unreal) {\n")
          .append("    FTCHARToUTF16 Converter(*Unreal);\n")
          .append("    return std::u16string(reinterpret_cast<const char16_t*>(Converter.Get()), Converter.Length());\n}\n\n");

        for (DataType dt : mapper.topologicalOrder()) {
            if (dt instanceof DataType.FixedRecord r) {
                sb.append(generateStructConversions(r.name, toFields(r)));
            } else if (dt instanceof DataType.VariantRecord v) {
                sb.append(generateVariantConversions(v));
            } else if (dt instanceof DataType.Array array && array.isDynamic() && isGeneratableNamedArray(array)) {
                sb.append(generateNamedArrayConversions(array));
            }
        }
        return sb.toString();
    }

    private boolean isGeneratableNamedArray(DataType.Array array) {
        return !mapper.isPaddingMarker(array.name) && !isExcludedFromUnreal(array.elementDataTypeName);
    }

    private String generateEnum(DataType.Enumerated e) {
        TypeMapper.BasicRepr underlying = mapper.resolveEnumUnderlying(e);
        // UENUM Blueprint attend un type sous 64 valeurs/8 bits dans la pratique courante ;
        // uint8 reste le choix standard UE pour un enum Blueprint-expose (edite en liste
        // deroulante). Les valeurs FOM qui ne rentreraient pas sur 8 bits sont signalees en
        // commentaire plutot que de produire un enum invalide pour l'editeur.
        StringBuilder sb = new StringBuilder();
        String name = NameUtils.unrealEnumName(e.name);
        boolean overflows = false;
        for (DataType.Enumerated.Enumerator en : e.enumerators) {
            if (en.value < 0 || en.value > 255) { overflows = true; break; }
        }
        if (overflows) {
            sb.append("// ATTENTION : '").append(e.name).append("' a au moins une valeur hors de [0, 255] -- \n")
              .append("// un UENUM Blueprint (uint8) ne peut pas la representer fidelement. Verifiez cette\n")
              .append("// enumeration a la main si elle est utilisee par ce FOM.\n");
        }
        sb.append("UENUM(BlueprintType)\nenum class ").append(name).append(" : uint8 {\n");
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        for (DataType.Enumerated.Enumerator en : e.enumerators) {
            String enumeratorName = NameUtils.sanitizeIdentifier(en.name);
            Integer count = seen.merge(enumeratorName, 1, Integer::sum);
            if (count > 1) enumeratorName = enumeratorName + "_" + count;
            long value = ((en.value % 256) + 256) % 256; // tronque si overflows (deja signale ci-dessus)
            sb.append("    ").append(enumeratorName).append(" = ").append(value).append(" UMETA(DisplayName=\"")
              .append(en.name.replace("\"", "'")).append("\"),\n");
        }
        sb.append("};\n\n");
        return sb.toString();
    }

    private record FieldSpec(String name, String dataTypeName) {
    }

    private List<FieldSpec> toFields(DataType.FixedRecord r) {
        List<FieldSpec> fields = new ArrayList<>();
        for (DataType.FixedRecord.Field f : r.fields) fields.add(new FieldSpec(f.name, f.dataTypeName));
        return fields;
    }

    private String category(String fomName) {
        return fomName.replace("\"", "'");
    }

    /**
     * Category Blueprint pour les UFUNCTION d'une objectClass/interactionClass, basee
     * sur la hierarchie FOM (decision explicite) : "HLAobjectRoot.BaseEntity.PhysicalEntity"
     * devient "BaseEntity|PhysicalEntity" (racine HLAxxxRoot omise, "|" = separateur de
     * sous-categories dans l'editeur Blueprint).
     */
    private String categoryPath(List<String> pathFromRoot) {
        List<String> withoutRoot = pathFromRoot.size() > 1
                ? pathFromRoot.subList(1, pathFromRoot.size())
                : pathFromRoot;
        return String.join("|", withoutRoot).replace("\"", "'");
    }

    private String generateStructDecl(String name, List<FieldSpec> fields) {
        String structName = NameUtils.unrealStructName(name);
        StringBuilder sb = new StringBuilder();
        sb.append("USTRUCT(BlueprintType)\nstruct ").append(structName).append(" {\n")
          .append("    GENERATED_BODY()\n\n");
        for (FieldSpec f : fields) {
            if (mapper.isPaddingMarker(f.dataTypeName) || isExcludedFromUnreal(f.dataTypeName)) {
                sb.append("    // '").append(f.name).append("' omis (bourrage ou type exclu de cette sortie)\n");
                continue;
            }
            sb.append("    UPROPERTY(BlueprintReadWrite, Category=\"").append(category(name)).append("\")\n")
              .append("    ").append(unrealFieldType(f.dataTypeName)).append(" ")
              .append(NameUtils.unrealPropertyName(f.name)).append("{};\n\n");
        }
        sb.append("};\n\n");
        sb.append(structName).append(" ToUnreal_").append(NameUtils.sanitizeIdentifier(name))
          .append("(const rprfom::").append(NameUtils.sanitizeIdentifier(name)).append("& Native);\n");
        sb.append("rprfom::").append(NameUtils.sanitizeIdentifier(name)).append(" ToNative_")
          .append(NameUtils.sanitizeIdentifier(name)).append("(const ").append(structName).append("& Unreal);\n\n");
        return sb.toString();
    }

    private String generateStructConversions(String name, List<FieldSpec> fields) {
        String fomId = NameUtils.sanitizeIdentifier(name);
        String structName = NameUtils.unrealStructName(name);
        StringBuilder sb = new StringBuilder();
        sb.append(structName).append(" ToUnreal_").append(fomId).append("(const rprfom::").append(fomId).append("& Native) {\n");
        sb.append("    ").append(structName).append(" Result;\n");
        for (FieldSpec f : fields) {
            if (mapper.isPaddingMarker(f.dataTypeName) || isExcludedFromUnreal(f.dataTypeName)) continue;
            sb.append("    Result.").append(NameUtils.unrealPropertyName(f.name)).append(" = ")
              .append(toUnrealExpr(f.dataTypeName, "Native." + NameUtils.toFieldName(f.name))).append(";\n");
        }
        sb.append("    return Result;\n}\n\n");

        sb.append("rprfom::").append(fomId).append(" ToNative_").append(fomId).append("(const ").append(structName).append("& Unreal) {\n");
        sb.append("    rprfom::").append(fomId).append(" Result;\n");
        for (FieldSpec f : fields) {
            if (mapper.isPaddingMarker(f.dataTypeName) || isExcludedFromUnreal(f.dataTypeName)) continue;
            sb.append("    Result.").append(NameUtils.toFieldName(f.name)).append(" = ")
              .append(toNativeExpr(f.dataTypeName, "Unreal." + NameUtils.unrealPropertyName(f.name))).append(";\n");
        }
        sb.append("    return Result;\n}\n\n");
        return sb.toString();
    }

    /**
     * variantRecordData, option (a) : un champ discriminant PLUS tous les champs de toutes les
     * alternatives presents simultanement (jamais seulement l'alternative active). Cote natif
     * (std::variant, une seule alternative reellement stockee), la correspondance se fait par
     * POSITION (alternative.index()), jamais par type C++ (deux alternatives pourraient partager
     * le meme type de charge utile).
     */
    private String generateVariantStructDecl(DataType.VariantRecord v) {
        String structName = NameUtils.unrealStructName(v.name);
        StringBuilder sb = new StringBuilder();
        sb.append("// HLAvariantRecord (option retenue : discriminant + TOUTES les alternatives\n");
        sb.append("// presentes simultanement, pour un acces Blueprint simple -- cf. ToUnreal_/ToNative_\n");
        sb.append("// pour la correspondance avec l'alternative reellement active cote natif).\n");
        sb.append("USTRUCT(BlueprintType)\nstruct ").append(structName).append(" {\n")
          .append("    GENERATED_BODY()\n\n");
        sb.append("    UPROPERTY(BlueprintReadWrite, Category=\"").append(category(v.name)).append("\")\n")
          .append("    ").append(unrealFieldType(v.discriminantDataTypeName)).append(" ")
          .append(NameUtils.unrealPropertyName(v.discriminantName)).append("{};\n\n");
        for (DataType.VariantRecord.Alternative a : v.alternatives) {
            if (isExcludedFromUnreal(a.dataTypeName)) {
                sb.append("    // Alternative '").append(a.fieldName).append("' omise (type exclu de cette sortie)\n");
                continue;
            }
            sb.append("    UPROPERTY(BlueprintReadWrite, Category=\"").append(category(v.name)).append("\")\n")
              .append("    ").append(unrealFieldType(a.dataTypeName)).append(" ")
              .append(NameUtils.unrealPropertyName(a.fieldName)).append("{};\n\n");
        }
        sb.append("};\n\n");
        String fomId = NameUtils.sanitizeIdentifier(v.name);
        sb.append(structName).append(" ToUnreal_").append(fomId).append("(const rprfom::").append(fomId).append("& Native);\n");
        sb.append("rprfom::").append(fomId).append(" ToNative_").append(fomId).append("(const ").append(structName).append("& Unreal);\n\n");
        return sb.toString();
    }

    private String generateVariantConversions(DataType.VariantRecord v) {
        String fomId = NameUtils.sanitizeIdentifier(v.name);
        String structName = NameUtils.unrealStructName(v.name);
        String discProp = NameUtils.unrealPropertyName(v.discriminantName);
        String discUnrealType = unrealFieldType(v.discriminantDataTypeName);
        StringBuilder sb = new StringBuilder();

        sb.append(structName).append(" ToUnreal_").append(fomId).append("(const rprfom::").append(fomId).append("& Native) {\n");
        sb.append("    ").append(structName).append(" Result;\n");
        sb.append("    switch (Native.alternative.index()) {\n");
        for (int i = 0; i < v.alternatives.size(); i++) {
            DataType.VariantRecord.Alternative a = v.alternatives.get(i);
            long discValue = mapper.discriminantValueOf(v, a);
            sb.append("    case ").append(i).append(": {\n");
            sb.append("        Result.").append(discProp).append(" = static_cast<").append(discUnrealType)
              .append(">(static_cast<int64_t>(").append(discValue).append("));\n");
            if (!isExcludedFromUnreal(a.dataTypeName)) {
                sb.append("        Result.").append(NameUtils.unrealPropertyName(a.fieldName)).append(" = ")
                  .append(toUnrealExpr(a.dataTypeName, "std::get<" + i + ">(Native.alternative)")).append(";\n");
            }
            sb.append("        break;\n    }\n");
        }
        sb.append("    default: break;\n    }\n");
        sb.append("    return Result;\n}\n\n");

        sb.append("rprfom::").append(fomId).append(" ToNative_").append(fomId).append("(const ").append(structName).append("& Unreal) {\n");
        sb.append("    rprfom::").append(fomId).append(" Result;\n");
        sb.append("    const int64_t __disc = static_cast<int64_t>(Unreal.").append(discProp).append(");\n");
        for (int i = 0; i < v.alternatives.size(); i++) {
            DataType.VariantRecord.Alternative a = v.alternatives.get(i);
            // Plusieurs valeurs de discriminant peuvent partager une meme
            // alternative (cf. javadoc de TypeMapper.discriminantValuesOf) :
            // une condition "__disc == v" par valeur, reunies par "||".
            StringBuilder cond = new StringBuilder();
            for (long discValue : mapper.discriminantValuesOf(v, a)) {
                if (cond.length() > 0) cond.append(" || ");
                cond.append("__disc == ").append(discValue).append("LL");
            }
            sb.append(i == 0 ? "    if" : "    else if").append(" (").append(cond).append(") {\n");
            if (isExcludedFromUnreal(a.dataTypeName)) {
                sb.append("        // Alternative '").append(a.fieldName).append("' omise (type exclu de cette sortie) : non reconstructible depuis Unreal.\n");
            } else {
                // Construction par POSITION (emplace<i>), jamais par affectation de type :
                // certains variants FOM (ex. EnvironmentRecVariantStruct, SpatialVariantStruct)
                // ont deux alternatives distinctes qui partagent exactement le meme type natif,
                // ce qui rend le type present plus d'une fois dans la liste de types du
                // std::variant -- operator= par type est alors ambigu/illegal (contrainte
                // __exactly_once de std::variant). emplace<i> leve toute ambiguite.
                sb.append("        Result.alternative.emplace<").append(i).append(">(")
                  .append(toNativeExpr(a.dataTypeName, "Unreal." + NameUtils.unrealPropertyName(a.fieldName))).append(");\n");
            }
            sb.append("    }\n");
        }
        sb.append("    return Result;\n}\n\n");
        return sb.toString();
    }

    private String generateNamedArrayDecl(DataType.Array array) {
        String fomId = NameUtils.sanitizeIdentifier(array.name);
        String unrealType = unrealFieldType(array.name); // "TArray<Element>"
        StringBuilder sb = new StringBuilder();
        sb.append("// Tableau dynamique nomme '").append(array.name).append("' : ").append(unrealType).append(".\n");
        sb.append(unrealType).append(" ToUnreal_").append(fomId).append("(const rprfom::").append(fomId).append("& Native);\n");
        sb.append("rprfom::").append(fomId).append(" ToNative_").append(fomId).append("(const ").append(unrealType).append("& Unreal);\n\n");
        return sb.toString();
    }

    private String generateNamedArrayConversions(DataType.Array array) {
        String fomId = NameUtils.sanitizeIdentifier(array.name);
        String unrealType = unrealFieldType(array.name);
        StringBuilder sb = new StringBuilder();
        sb.append(unrealType).append(" ToUnreal_").append(fomId).append("(const rprfom::").append(fomId).append("& Native) {\n");
        sb.append("    ").append(unrealType).append(" Result;\n");
        sb.append("    Result.Reserve((int32)Native.value.size());\n");
        sb.append("    for (auto const& Elem : Native.value) { Result.Add(")
          .append(toUnrealExpr(array.elementDataTypeName, "Elem")).append("); }\n");
        sb.append("    return Result;\n}\n\n");

        sb.append("rprfom::").append(fomId).append(" ToNative_").append(fomId).append("(const ").append(unrealType).append("& Unreal) {\n");
        sb.append("    rprfom::").append(fomId).append(" Result;\n");
        sb.append("    Result.value.reserve((size_t)Unreal.Num());\n");
        sb.append("    for (int32 Index = 0; Index < Unreal.Num(); ++Index) { Result.value.push_back(")
          .append(toNativeExpr(array.elementDataTypeName, "Unreal[Index]")).append("); }\n");
        sb.append("    return Result;\n}\n\n");
        return sb.toString();
    }

    // ====================================================================
    // Une UCLASS par objectClass/interactionClass (miroir Blueprint, lecture
    // + Set garde par l'ownership -- cf. javadoc de la classe).
    // ====================================================================

    public String generateObjectClassHeader(ObjectClassNode node, List<String> pathFromRoot, ObjectClassNode parent) {
        String className = NameUtils.unrealClassName(node.name);
        String fomId = NameUtils.sanitizeIdentifier(node.name);
        boolean isRootClass = (parent == null);
        StringBuilder sb = new StringBuilder();
        sb.append("#pragma once\n//\n// ").append(className).append(".h -- GENERE depuis le(s) fichier(s) FOM.\n")
          .append("// FOM: ").append(NameUtils.dottedPath(pathFromRoot)).append("\n")
          .append("// Miroir Blueprint en LECTURE des attributs de cette classe ; la publication\n")
          .append("// RTI reste geree ailleurs (HLAabstract core, PluginUnrealHLA). SetXxx() est\n")
          .append("// un no-op silencieux si l'attribut n'est pas possede (IsXxxOwned() == false).\n//\n\n");
        sb.append("#include \"CoreMinimal.h\"\n");
        if (isRootClass) {
            sb.append("#include \"UObject/NoExportTypes.h\"\n");
        } else {
            // Nom de fichier = sanitizeIdentifier(parent.name) (sans le prefixe "U"),
            // conforme au nom de fichier reellement ecrit par Main.java pour la classe
            // parente -- unrealClassName(parent.name) produirait "UXxx.h", qui n'existe pas.
            sb.append("#include \"").append(NameUtils.sanitizeIdentifier(parent.name)).append(".h\"\n");
        }
        sb.append("#include \"RPRFOMUnrealTypes.h\"\n");
        sb.append("#include \"").append(fomId).append(".hpp\"\n\n");
        sb.append("#include \"").append(className.substring(1)).append(".generated.h\"\n\n");

        sb.append("UCLASS(BlueprintType)\nclass ").append(className).append(" : public ")
          .append(isRootClass ? "UObject" : NameUtils.unrealClassName(parent.name)).append(" {\n")
          .append("    GENERATED_BODY()\npublic:\n");

        if (isRootClass) {
            sb.append("    /** Objet natif reflete (non possede : son cycle de vie reste gere en dehors de cette couche). */\n");
            sb.append("    void SetNative(rprfom::").append(fomId).append("* InNative) { Native = InNative; }\n\n");
        }

        for (ObjectClassNode.AttributeDef a : node.ownAttributes) {
            if (isExcludedFromUnreal(a.dataTypeName)) {
                sb.append("    // Attribut '").append(a.name).append("' omis (type exclu de cette sortie)\n\n");
                continue;
            }
            String propName = NameUtils.unrealPropertyName(a.name);
            String type = unrealFieldType(a.dataTypeName);
            String cat = categoryPath(pathFromRoot);
            sb.append("    UFUNCTION(BlueprintCallable, BlueprintPure, Category=\"").append(cat).append("\")\n")
              .append("    ").append(type).append(" Get").append(propName).append("() const;\n\n");
            sb.append("    UFUNCTION(BlueprintCallable, Category=\"").append(cat).append("\")\n")
              .append("    void Set").append(propName).append("(const ").append(type).append("& InValue);\n\n");
            sb.append("    UFUNCTION(BlueprintCallable, BlueprintPure, Category=\"").append(cat).append("\")\n")
              .append("    bool Is").append(propName).append("Owned() const;\n\n");
        }

        if (isRootClass) {
            sb.append("protected:\n    rprfom::").append(fomId).append("* Native = nullptr;\n");
        }
        sb.append("};\n");
        return sb.toString();
    }

    public String generateObjectClassCpp(ObjectClassNode node, List<String> pathFromRoot, ObjectClassNode parent) {
        String className = NameUtils.unrealClassName(node.name);
        String fomId = NameUtils.sanitizeIdentifier(node.name);
        boolean isRootClass = (parent == null);
        StringBuilder sb = new StringBuilder();
        sb.append("//\n// ").append(className).append(".cpp -- GENERE depuis le(s) fichier(s) FOM.\n//\n\n");
        sb.append("#include \"").append(className.substring(1)).append(".h\"\n\n");

        for (ObjectClassNode.AttributeDef a : node.ownAttributes) {
            if (isExcludedFromUnreal(a.dataTypeName)) continue;
            String propName = NameUtils.unrealPropertyName(a.name);
            String type = unrealFieldType(a.dataTypeName);
            String fieldExpr = NameUtils.toFieldName(a.name);
            String selfDecl = isRootClass
                    ? "    rprfom::" + fomId + "* Self = Native;\n"
                    : "    rprfom::" + fomId + "* Self = static_cast<rprfom::" + fomId + "*>(Native);\n";

            sb.append(type).append(" ").append(className).append("::Get").append(propName).append("() const {\n");
            sb.append(selfDecl);
            sb.append("    if (!Self) return ").append(type).append("{};\n");
            sb.append("    return ").append(toUnrealExpr(a.dataTypeName, "Self->" + fieldExpr + ".get()")).append(";\n}\n\n");

            sb.append("void ").append(className).append("::Set").append(propName).append("(const ").append(type).append("& InValue) {\n");
            sb.append(selfDecl);
            sb.append("    if (!Self || !Self->").append(fieldExpr).append(".isOwned()) return;\n");
            sb.append("    Self->").append(fieldExpr).append(".set(").append(toNativeExpr(a.dataTypeName, "InValue")).append(");\n}\n\n");

            sb.append("bool ").append(className).append("::Is").append(propName).append("Owned() const {\n");
            sb.append(selfDecl);
            sb.append("    return Self && Self->").append(fieldExpr).append(".isOwned();\n}\n\n");
        }
        return sb.toString();
    }

    public String generateInteractionClassHeader(InteractionClassNode node, List<String> pathFromRoot, InteractionClassNode parent) {
        String className = NameUtils.unrealClassName(node.name);
        String fomId = NameUtils.sanitizeIdentifier(node.name);
        boolean isRootClass = (parent == null);
        StringBuilder sb = new StringBuilder();
        sb.append("#pragma once\n//\n// ").append(className).append(".h -- GENERE depuis le(s) fichier(s) FOM.\n")
          .append("// FOM (interaction): ").append(NameUtils.dottedPath(pathFromRoot)).append("\n")
          .append("// Miroir Blueprint en LECTURE/ECRITURE des parametres de cette interaction\n")
          .append("// (pas d'ownership pour une interaction, cf. HLAParameter.hpp). L'envoi de\n")
          .append("// l'interaction elle-meme reste gere ailleurs (HLAabstract core, PluginUnrealHLA).\n//\n\n");
        sb.append("#include \"CoreMinimal.h\"\n");
        if (isRootClass) {
            sb.append("#include \"UObject/NoExportTypes.h\"\n");
        } else {
            // Idem generateObjectClassHeader : nom de fichier base sur sanitizeIdentifier,
            // pas sur le nom de classe prefixe "U" (qui ne correspond a aucun fichier ecrit).
            sb.append("#include \"").append(NameUtils.sanitizeIdentifier(parent.name)).append(".h\"\n");
        }
        sb.append("#include \"RPRFOMUnrealTypes.h\"\n");
        sb.append("#include \"").append(fomId).append(".hpp\"\n\n");
        sb.append("#include \"").append(className.substring(1)).append(".generated.h\"\n\n");

        sb.append("UCLASS(BlueprintType)\nclass ").append(className).append(" : public ")
          .append(isRootClass ? "UObject" : NameUtils.unrealClassName(parent.name)).append(" {\n")
          .append("    GENERATED_BODY()\npublic:\n");

        if (isRootClass) {
            sb.append("    /** Occurrence native refletee (non possedee : son cycle de vie reste gere en dehors de cette couche). */\n");
            sb.append("    void SetNative(rprfom::").append(fomId).append("* InNative) { Native = InNative; }\n\n");
        }

        for (InteractionClassNode.ParameterDef p : node.ownParameters) {
            if (isExcludedFromUnreal(p.dataTypeName)) {
                sb.append("    // Parametre '").append(p.name).append("' omis (type exclu de cette sortie)\n\n");
                continue;
            }
            String propName = NameUtils.unrealPropertyName(p.name);
            String type = unrealFieldType(p.dataTypeName);
            String cat = categoryPath(pathFromRoot);
            sb.append("    UFUNCTION(BlueprintCallable, BlueprintPure, Category=\"").append(cat).append("\")\n")
              .append("    ").append(type).append(" Get").append(propName).append("() const;\n\n");
            sb.append("    UFUNCTION(BlueprintCallable, Category=\"").append(cat).append("\")\n")
              .append("    void Set").append(propName).append("(const ").append(type).append("& InValue);\n\n");
            sb.append("    UFUNCTION(BlueprintCallable, BlueprintPure, Category=\"").append(cat).append("\")\n")
              .append("    bool Has").append(propName).append("() const;\n\n");
        }

        if (isRootClass) {
            sb.append("protected:\n    rprfom::").append(fomId).append("* Native = nullptr;\n");
        }
        sb.append("};\n");
        return sb.toString();
    }

    public String generateInteractionClassCpp(InteractionClassNode node, List<String> pathFromRoot, InteractionClassNode parent) {
        String className = NameUtils.unrealClassName(node.name);
        String fomId = NameUtils.sanitizeIdentifier(node.name);
        boolean isRootClass = (parent == null);
        StringBuilder sb = new StringBuilder();
        sb.append("//\n// ").append(className).append(".cpp -- GENERE depuis le(s) fichier(s) FOM.\n//\n\n");
        sb.append("#include \"").append(className.substring(1)).append(".h\"\n\n");

        for (InteractionClassNode.ParameterDef p : node.ownParameters) {
            if (isExcludedFromUnreal(p.dataTypeName)) continue;
            String propName = NameUtils.unrealPropertyName(p.name);
            String type = unrealFieldType(p.dataTypeName);
            String fieldExpr = NameUtils.toFieldName(p.name);
            String selfDecl = isRootClass
                    ? "    rprfom::" + fomId + "* Self = Native;\n"
                    : "    rprfom::" + fomId + "* Self = static_cast<rprfom::" + fomId + "*>(Native);\n";

            sb.append(type).append(" ").append(className).append("::Get").append(propName).append("() const {\n");
            sb.append(selfDecl);
            sb.append("    if (!Self) return ").append(type).append("{};\n");
            sb.append("    return ").append(toUnrealExpr(p.dataTypeName, "Self->" + fieldExpr + ".get()")).append(";\n}\n\n");

            sb.append("void ").append(className).append("::Set").append(propName).append("(const ").append(type).append("& InValue) {\n");
            sb.append(selfDecl);
            sb.append("    if (!Self) return;\n");
            sb.append("    Self->").append(fieldExpr).append(".set(").append(toNativeExpr(p.dataTypeName, "InValue")).append(");\n}\n\n");

            sb.append("bool ").append(className).append("::Has").append(propName).append("() const {\n");
            sb.append(selfDecl);
            sb.append("    return Self && Self->").append(fieldExpr).append(".hasValue();\n}\n\n");
        }
        return sb.toString();
    }
}
