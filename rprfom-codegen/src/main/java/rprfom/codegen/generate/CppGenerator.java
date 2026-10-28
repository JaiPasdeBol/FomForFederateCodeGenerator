package rprfom.codegen.generate;

import rprfom.codegen.model.DataType;
import rprfom.codegen.model.FomModel;
import rprfom.codegen.model.InteractionClassNode;
import rprfom.codegen.model.ObjectClassNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Genere les fichiers C++ derives du FOM, repartis dans deux dossiers
 * distincts sous include/rprfom/ (cf. Main), miroir de l'organisation
 * Java (cf. JavaGenerator) :
 * <ul>
 *   <li>{@code include/rprfom/} : ce qui reflete directement le contenu
 *       du/des fichier(s) FOM XML -- HLATypes.hpp (types de donnees du
 *       FOM) et un .hpp par objectClass/interactionClass.</li>
 *   <li>{@code include/rprfom/runtime/} : l'infrastructure d'encodage/
 *       decodage HLA, commune a tout FOM -- les fichiers "statiques"
 *       copies tels quels depuis les ressources du generateur
 *       (HLAAttribute.hpp, IHLAAttribute.h, HLAParameter.hpp,
 *       IHLAParameter.h, HLAOwnership.hpp, HLAEncoding.hpp,
 *       HLADecoding.hpp) ainsi que les fichiers generes qui orchestrent
 *       l'encodage pour CE FOM precis mais ne contiennent aucune classe
 *       metier (HLAAlignment.hpp, HLAEncoder.hpp, HLADecoder.hpp).
 * </ul>
 * Namespace C++ unique {@code rprfom} pour les deux dossiers (aucune
 * contrainte namespace/repertoire comme en Java) : seuls les chemins
 * des #include changent (ex. {@code "runtime/HLAAttribute.hpp"} depuis
 * include/rprfom/, {@code "../HLATypes.hpp"} depuis
 * include/rprfom/runtime/) ; tout le reste (types non qualifies) est
 * inchange.
 */
public final class CppGenerator {

    private final FomModel model;
    private final TypeMapper mapper;

    /**
     * Types FOM "a responsabilite du developpeur" (cf. usertype.*) : la
     * struct vit dans usertypes/X.hpp (incluse par HLATypes.hpp a sa
     * position topologique) et son encodage/decodage dans
     * usertypes/XCodec.hpp (definitions inline des specialisations
     * DECLAREES ici, dans HLAEncoder.hpp/HLADecoder.hpp, puis incluses via
     * runtime/HLAUserCodecs.hpp).
     */
    private java.util.Set<String> userTypes = java.util.Set.of();

    public void setUserTypes(java.util.Set<String> names) {
        this.userTypes = new java.util.LinkedHashSet<>(names);
    }

    public CppGenerator(FomModel model) {
        this.model = model;
        this.mapper = new TypeMapper(model);
    }

    // ====================================================================
    // HLATypes.hpp
    // ====================================================================

    public String generateHLATypes() {
        StringBuilder sb = new StringBuilder();
        sb.append("#pragma once\n");
        sb.append("//\n// HLATypes.hpp -- GENERE depuis le(s) fichier(s) FOM. Ne pas editer a la main,\n");
        sb.append("// regenerer via rprfom-codegen a la place.\n//\n\n");
        sb.append("#include <array>\n#include <cstdint>\n#include <string>\n#include <variant>\n#include <vector>\n\n");
        sb.append("namespace rprfom {\n\n");

        sb.append("// ============================================================\n");
        sb.append("// Alias des representations de base HLA (IEEE 1516.2 + extensions\n");
        sb.append("// non signees IEEE 1516-2025 / HLA4)\n");
        sb.append("// ============================================================\n");
        sb.append("using Integer16          = int16_t;\n");
        sb.append("using UnsignedInteger16  = uint16_t;\n");
        sb.append("using Integer32          = int32_t;\n");
        sb.append("using UnsignedInteger32  = uint32_t;\n");
        sb.append("using Integer64          = int64_t;\n");
        sb.append("using UnsignedInteger64  = uint64_t;\n");
        sb.append("using Float32            = float;\n");
        sb.append("using Float64            = double;\n");
        sb.append("using Octet              = uint8_t;\n\n");

        sb.append("// Wrappers dedies pour les variantes little-endian et les largeurs\n");
        sb.append("// deja prises par la variante big-endian correspondante (necessaire\n");
        sb.append("// pour que HLAEncoderDetail<T>/HLADecoderDetail<T> puissent les\n");
        sb.append("// distinguer par specialisation de template -- cf. TypeMapper.BasicRepr\n");
        sb.append("// du generateur pour le detail de ce choix de conception).\n");
        sb.append("struct Integer16LE { Integer16 value{}; };\n");
        sb.append("struct UnsignedInteger16LE { UnsignedInteger16 value{}; };\n");
        sb.append("struct Integer32LE { Integer32 value{}; };\n");
        sb.append("struct UnsignedInteger32LE { UnsignedInteger32 value{}; };\n");
        sb.append("struct Integer64LE { Integer64 value{}; };\n");
        sb.append("struct UnsignedInteger64LE { UnsignedInteger64 value{}; };\n");
        sb.append("struct Float32LE { Float32 value{}; };\n");
        sb.append("struct Float64LE { Float64 value{}; };\n\n");

        sb.append("// HLAoctetPairBE/LE : 2 octets bruts (cf. HLAEncoding.hpp pour la\n");
        sb.append("// simplification BE==LE appliquee ici).\n");
        sb.append("struct OctetPair { std::array<Octet, 2> value{}; };\n\n");

        for (DataType dt : mapper.topologicalOrder()) {
            if (userTypes.contains(dt.name)) {
                // Type a responsabilite du developpeur : son en-tete est inclus ICI (apres ses
                // dependances, avant ses dependants), hors du namespace ouvert -- il declare le sien.
                sb.append("} // namespace rprfom\n");
                sb.append("#include \"usertypes/").append(NameUtils.sanitizeIdentifier(dt.name)).append(".hpp\"\n");
                sb.append("namespace rprfom {\n\n");
                continue;
            }
            if (dt instanceof DataType.Enumerated e) {
                sb.append(generateEnum(e));
            } else if (dt instanceof DataType.FixedRecord r) {
                sb.append(generateStruct(r.name, toFields(r)));
            } else if (dt instanceof DataType.VariantRecord v) {
                sb.append(generateVariantStruct(v));
            } else if (dt instanceof DataType.Array array && array.isDynamic() && isGeneratableArray(array)) {
                sb.append(generateArrayWrapper(array));
            }
            // Tableau fixe (HLAfixedArray) : jamais de type nomme, resolu
            // inline en std::array<Element, N> (cf. TypeMapper.cppTypeName).
            // Marqueur de bourrage (RPRpaddingTo32/64Array) : jamais de
            // type du tout, cf. TypeMapper.isPaddingMarker.
        }

        sb.append("} // namespace rprfom\n");
        return sb.toString();
    }

    /** Exclut les marqueurs de bourrage de la generation de type ; valide RPRnullTerminatedArray au passage. */
    private boolean isGeneratableArray(DataType.Array array) {
        if (array.encoding == DataType.ArrayEncoding.RPR_PADDING_32
                || array.encoding == DataType.ArrayEncoding.RPR_PADDING_64) {
            return false;
        }
        mapper.validateNullTerminatedArray(array);
        return true;
    }

    /**
     * Type nomme pour un tableau dynamique (HLAvariableArray ou
     * RPRlengthlessArray). Un seul membre "value" ; le choix entre les
     * deux encodages se fait a l'encodage/decodage (cf.
     * generateArrayEncoder/generateArrayDecoder), pas dans la structure
     * elle-meme.
     */
    private String generateArrayWrapper(DataType.Array array) {
        String name = NameUtils.sanitizeIdentifier(array.name);
        String elementType = mapper.cppTypeName(array.elementDataTypeName);
        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(array.encoding == DataType.ArrayEncoding.RPR_LENGTHLESS_ARRAY
                ? "RPRlengthlessArray : pas de compteur, les elements occupent tout le buffer restant.\n"
                : "HLAvariableArray : compteur HLAinteger32BE + elements.\n");
        sb.append("struct ").append(name).append(" {\n")
          .append("    std::vector<").append(elementType).append("> value{};\n")
          .append("};\n\n");
        return sb.toString();
    }

    private String generateEnum(DataType.Enumerated e) {
        TypeMapper.BasicRepr underlying = mapper.resolveEnumUnderlying(e);
        String underlyingType = underlying != null ? underlying.fieldCppType : "int32_t";
        StringBuilder sb = new StringBuilder();
        sb.append("enum class ").append(NameUtils.sanitizeIdentifier(e.name))
          .append(" : ").append(underlyingType).append(" {\n");
        // Deux enumerateurs FOM distincts (ex. "Entity_Type-Kind" et
        // "Entity_Type_Kind") peuvent converger vers le meme identifiant
        // apres sanitisation -- rencontre en pratique dans de tres gros
        // enums compiles (DatumIdentifierEnum32, 763 entrees). On suffixe
        // par un numero en cas de collision plutot que de produire une
        // redeclaration invalide ; toutes les valeurs sont preservees.
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        for (DataType.Enumerated.Enumerator en : e.enumerators) {
            String name = NameUtils.sanitizeIdentifier(en.name);
            Integer count = seen.merge(name, 1, Integer::sum);
            if (count > 1) name = name + "_" + count;
            sb.append("    ").append(name)
              .append(" = ").append(en.value).append(",\n");
        }
        sb.append("};\n\n");
        return sb.toString();
    }

    private List<FieldSpec> toFields(DataType.FixedRecord r) {
        List<FieldSpec> fields = new ArrayList<>();
        for (DataType.FixedRecord.Field f : r.fields) {
            fields.add(new FieldSpec(f.name, f.dataTypeName));
        }
        return fields;
    }

    private List<FieldSpec> toFields(DataType.VariantRecord v) {
        List<FieldSpec> fields = new ArrayList<>();
        fields.add(new FieldSpec(v.discriminantName, v.discriminantDataTypeName));
        for (DataType.VariantRecord.Alternative a : v.alternatives) {
            fields.add(new FieldSpec(a.fieldName, a.dataTypeName));
        }
        return fields;
    }

    private String generateVariantStruct(DataType.VariantRecord v) {
        String name = NameUtils.sanitizeIdentifier(v.name);
        StringBuilder sb = new StringBuilder();
        sb.append("// HLAvariantRecord : seule l'alternative active est stockee et encodee\n");
        sb.append("// (discriminant FOM : ").append(v.discriminantName).append(").\n");
        sb.append("struct ").append(name).append(" {\n");
        sb.append("    std::variant<");
        if (v.alternatives.isEmpty()) {
            // Cas reel rencontre dans la RPR-FOM v3.0/2025
            // (ModeSDownlinkAircraftParameterVariantStruct, encoding FOM
            // "RPRextendedVariantRecord") : un variantRecordData SANS
            // aucune <alternative> declaree -- extension reservee par la
            // norme, pas encore definie dans cette revision. std::variant<>
            // (zero type) est invalide en C++ (static_assert de la
            // bibliotheque standard) ; std::monostate comble l'unique
            // alternative necessaire sans pretendre a une signification.
            // L'encodeur/decodeur genere n'aura dans ce cas aucun "case"
            // (cf. generateVariantEncoder/generateVariantDecoder), donc
            // toute tentative d'encoder/decoder une valeur de ce type leve
            // explicitement une erreur plutot que de produire un
            // encodage incorrect -- coherent avec le fait qu'aucune valeur
            // valide n'existe pour ce type dans cette revision du FOM.
            sb.append("std::monostate");
        }
        for (int i = 0; i < v.alternatives.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(mapper.cppTypeName(v.alternatives.get(i).dataTypeName));
        }
        sb.append("> alternative{};\n");
        sb.append("};\n\n");
        return sb.toString();
    }

    private String generateStruct(String name, List<FieldSpec> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("struct ").append(NameUtils.sanitizeIdentifier(name)).append(" {\n");
        for (FieldSpec f : fields) {
            if (mapper.isPaddingMarker(f.dataTypeName)) {
                continue; // marqueur de bourrage pur (RPRpaddingTo32/64Array) : pas de membre, cf. TypeMapper.isPaddingMarker
            }
            sb.append("    ").append(mapper.cppTypeName(f.dataTypeName)).append(" ")
              .append(NameUtils.toFieldName(f.name)).append("{};\n");
        }
        sb.append("};\n\n");
        return sb.toString();
    }

    // ====================================================================
    // HLAAlignment.hpp
    // ====================================================================

    public String generateHLAAlignment() {
        StringBuilder sb = new StringBuilder();
        sb.append("#pragma once\n");
        sb.append("//\n// HLAAlignment.hpp -- GENERE. Cf. HLATypes.hpp.\n//\n\n");
        sb.append("#include <cstddef>\n\n#include \"../HLATypes.hpp\"\n\nnamespace rprfom {\n\n");
        sb.append("template <typename T>\nstruct HLAOctetBoundary {\n    static constexpr size_t value = 1;\n};\n\n");
        for (DataType dt : mapper.topologicalOrder()) {
            if (dt instanceof DataType.Array) continue;
            int boundary = mapper.octetBoundaryOf(dt.name);
            if (boundary > 1) {
                sb.append("template <>\nstruct HLAOctetBoundary<").append(NameUtils.sanitizeIdentifier(dt.name))
                  .append("> {\n    static constexpr size_t value = ").append(boundary).append(";\n};\n\n");
            }
        }
        sb.append("} // namespace rprfom\n");
        return sb.toString();
    }

    // ====================================================================
    // HLAEncoder.hpp / HLADecoder.hpp
    // ====================================================================

    public String generateHLAEncoder() {
        StringBuilder sb = new StringBuilder();
        sb.append("#pragma once\n//\n// HLAEncoder.hpp -- GENERE depuis le(s) fichier(s) FOM.\n//\n\n");
        sb.append("#include <array>\n#include <cstdint>\n#include <stdexcept>\n#include <string>\n#include <vector>\n\n");
        sb.append("#include \"HLAAlignment.hpp\"\n#include \"HLAEncoding.hpp\"\n#include \"../HLATypes.hpp\"\n\n");
        sb.append("namespace rprfom {\n\n");
        sb.append("template <typename T>\nstruct HLAEncoderDetail {\n")
          .append("    static void encodeInto(std::vector<uint8_t>&, const T&) {\n")
          .append("        throw std::logic_error(\"Aucun HLAEncoderDetail<T> specialise pour ce type RPR-FOM.\");\n")
          .append("    }\n};\n\n");
        sb.append("template <typename T>\nstruct HLAEncoder {\n")
          .append("    static std::vector<uint8_t> encode(const T& v) {\n")
          .append("        std::vector<uint8_t> b;\n        HLAEncoderDetail<T>::encodeInto(b, v);\n        return b;\n    }\n")
          .append("    static void encodeInto(std::vector<uint8_t>& buffer, const T& v) {\n")
          .append("        HLAEncoderDetail<T>::encodeInto(buffer, v);\n    }\n};\n\n");

        // Plusieurs representations HLA distinctes peuvent partager le
        // meme type C++ d'attribut quand leur encodage est strictement
        // identique (ex. HLAoctet/HLAunsignedInteger8/HLAbyte/HLAASCIIchar
        // -> Octet) : on ne genere alors qu'UNE seule specialisation
        // (sinon double definition = erreur de compilation).
        java.util.Set<String> emittedAttrTypes = new java.util.LinkedHashSet<>();
        for (TypeMapper.BasicRepr basic : TypeMapper.allBasicReprs()) {
            if (!emittedAttrTypes.add(basic.attrCppType)) continue;
            sb.append("template <>\nstruct HLAEncoderDetail<").append(basic.attrCppType).append("> {\n")
              .append("    static void encodeInto(std::vector<uint8_t>& b, const ").append(basic.attrCppType).append("& v) {\n")
              .append("        hla_encoding::").append(basic.encodeFn).append("(b, v").append(basic.attrCppAccessor).append(");\n    }\n};\n\n");
        }

        // Specialisation GENERIQUE pour tout std::array<T, N> -- necessaire
        // depuis que cppTypeName() peut renvoyer cette forme structurelle
        // pour un tableau FIXE (HLAfixedArray) utilise comme element
        // imbrique, alternative de variantRecordData ou attribut/parametre
        // direct (cf. TypeMapper.javaAttributeClassName pour le
        // raisonnement equivalent cote Java -- cas reels RPR-FOM v3.0/2025
        // : OpaqueDatum64 imbrique dans OpaqueDatum64Array1Plus, RPRnoData
        // en alternative, ASCIIcharArray8 en attribut direct). Sans cette
        // specialisation, HLAEncoderDetail<std::array<T,N>> retombe sur le
        // template primaire (ci-dessus) qui leve une std::logic_error a
        // l'EXECUTION -- le code compile tout de meme (templates non
        // instancies avant utilisation), ce qui rend le probleme facile a
        // manquer sans test d'encodage/decodage reel. Aucun compteur ecrit
        // (N fait partie du type, pas du fil, conformement a HLAfixedArray).
        //
        // DOIT apparaitre ICI, avant la boucle topologicalOrder() ci-dessous :
        // cette boucle genere (pour OpaqueDatum64Array1Plus notamment) un
        // encodeur dont le corps appelle
        // HLAEncoderDetail<std::array<Octet,8>>::encodeInto(...), ce qui force
        // l'instanciation implicite du template PRIMAIRE pour ce type s'il n'a
        // pas deja une specialisation visible a ce point du fichier -- une
        // specialisation partielle declaree APRES une telle instanciation est
        // un cas interdit par la norme C++ (erreur de compilation
        // -fpermissive : "partial specialization ... after instantiation
        // of ..."), rencontre concretement en placant ce bloc a la fin du
        // fichier.
        sb.append("template <typename T, size_t N>\nstruct HLAEncoderDetail<std::array<T, N>> {\n")
          .append("    static void encodeInto(std::vector<uint8_t>& b, const std::array<T, N>& elements) {\n")
          .append("        for (const auto& element : elements) {\n")
          .append("            hla_encoding::appendPadding(b, HLAOctetBoundary<T>::value);\n")
          .append("            HLAEncoderDetail<T>::encodeInto(b, element);\n        }\n    }\n};\n\n");

        for (DataType dt : mapper.topologicalOrder()) {
            if (userTypes.contains(dt.name)) {
                String cls = NameUtils.sanitizeIdentifier(dt.name);
                sb.append("// Type a responsabilite du developpeur : declaration seule, la definition est\n");
                sb.append("// dans usertypes/").append(cls).append("Codec.hpp (incluse en fin de fichier).\n");
                sb.append("template <>\nstruct HLAEncoderDetail<").append(cls).append("> {\n")
                  .append("    static void encodeInto(std::vector<uint8_t>& b, const ").append(cls).append("& v);\n};\n\n");
                continue;
            }
            if (dt instanceof DataType.Enumerated e) {
                TypeMapper.BasicRepr underlying = mapper.resolveEnumUnderlying(e);
                String underlyingType = underlying != null ? underlying.fieldCppType : "Integer32";
                String encodeFn = underlying != null ? underlying.encodeFn : "appendInteger32BE";
                String name = NameUtils.sanitizeIdentifier(e.name);
                sb.append("template <>\nstruct HLAEncoderDetail<").append(name).append("> {\n")
                  .append("    static void encodeInto(std::vector<uint8_t>& b, const ").append(name).append("& v) {\n")
                  .append("        hla_encoding::").append(encodeFn).append("(b, static_cast<").append(underlyingType).append(">(v));\n    }\n};\n\n");
            } else if (dt instanceof DataType.FixedRecord r) {
                sb.append(generateStructEncoder(r.name, toFields(r)));
            } else if (dt instanceof DataType.VariantRecord v) {
                sb.append(generateVariantEncoder(v));
            } else if (dt instanceof DataType.Array array && array.isDynamic() && isGeneratableArray(array)) {
                sb.append(generateArrayEncoder(array));
            }
        }

        sb.append("template <typename T>\nstruct HLAEncoderDetail<std::vector<T>> {\n")
          .append("    static void encodeInto(std::vector<uint8_t>& b, const std::vector<T>& elements) {\n")
          .append("        hla_encoding::appendUnsignedInteger32BE(b, static_cast<uint32_t>(elements.size()));\n")
          .append("        for (const auto& element : elements) {\n")
          .append("            hla_encoding::appendPadding(b, HLAOctetBoundary<T>::value);\n")
          .append("            HLAEncoderDetail<T>::encodeInto(b, element);\n        }\n    }\n};\n\n");

        sb.append("} // namespace rprfom\n");
        if (!userTypes.isEmpty()) {
            sb.append("\n// Definitions inline des codecs des types a responsabilite du developpeur.\n");
            sb.append("#include \"HLAUserCodecs.hpp\"\n");
        }
        return sb.toString();
    }

    private String generateArrayEncoder(DataType.Array array) {
        String name = NameUtils.sanitizeIdentifier(array.name);
        String elementType = mapper.cppTypeName(array.elementDataTypeName);
        int elementBoundary = mapper.octetBoundaryOf(array.elementDataTypeName);
        StringBuilder sb = new StringBuilder();
        sb.append("template <>\nstruct HLAEncoderDetail<").append(name).append("> {\n")
          .append("    static void encodeInto(std::vector<uint8_t>& b, const ").append(name).append("& v) {\n");
        if (array.encoding == DataType.ArrayEncoding.HLA_VARIABLE_ARRAY) {
            sb.append("        hla_encoding::appendUnsignedInteger32BE(b, static_cast<uint32_t>(v.value.size()));\n");
        }
        // RPRlengthlessArray : pas de compteur -- les elements suivent
        // directement (valide seulement en derniere position d'une valeur
        // encodee, cf. commentaire de DataType.ArrayEncoding).
        sb.append("        for (const auto& element : v.value) {\n")
          .append("            hla_encoding::appendPadding(b, ").append(elementBoundary).append(");\n")
          .append("            HLAEncoderDetail<").append(elementType).append(">::encodeInto(b, element);\n")
          .append("        }\n");
        if (array.encoding == DataType.ArrayEncoding.RPR_NULL_TERMINATED_ARRAY) {
            sb.append("        hla_encoding::appendOctet(b, 0); // terminateur nul (RPRnullTerminatedArray)\n");
        }
        sb.append("    }\n};\n\n");
        return sb.toString();
    }

    private String generateVariantEncoder(DataType.VariantRecord v) {
        String name = NameUtils.sanitizeIdentifier(v.name);
        TypeMapper.BasicRepr discUnderlying = mapper.resolveDiscriminantUnderlying(v);
        if (discUnderlying == null) {
            throw new IllegalStateException("Discriminant non resolu pour le variant '" + v.name + "'");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("// HLAvariantRecord : n'encode que l'alternative active, precedee de son\n");
        sb.append("// discriminant (l'index std::variant sert directement de discriminant).\n");
        sb.append("template <>\nstruct HLAEncoderDetail<").append(name).append("> {\n")
          .append("    static void encodeInto(std::vector<uint8_t>& b, const ").append(name).append("& v) {\n")
          .append("        switch (v.alternative.index()) {\n");
        for (int i = 0; i < v.alternatives.size(); i++) {
            DataType.VariantRecord.Alternative alt = v.alternatives.get(i);
            long discVal = mapper.discriminantValueOf(v, alt);
            String altType = mapper.cppTypeName(alt.dataTypeName);
            sb.append("            case ").append(i).append(": {\n")
              .append("                hla_encoding::").append(discUnderlying.encodeFn).append("(b, static_cast<")
              .append(discUnderlying.fieldCppType).append(">(").append(discVal).append("));\n")
              .append("                hla_encoding::appendPadding(b, ").append(mapper.octetBoundaryOf(alt.dataTypeName)).append(");\n")
              .append("                HLAEncoderDetail<").append(altType).append(">::encodeInto(b, std::get<")
              .append(i).append(">(v.alternative));\n")
              .append("                break;\n            }\n");
        }
        sb.append("            default:\n")
          .append("                throw std::logic_error(\"").append(name).append(" : alternative active inconnue\");\n")
          .append("        }\n    }\n};\n\n");
        return sb.toString();
    }


    private String generateStructEncoder(String name, List<FieldSpec> fields) {
        String cppName = NameUtils.sanitizeIdentifier(name);
        StringBuilder sb = new StringBuilder();
        sb.append("template <>\nstruct HLAEncoderDetail<").append(cppName).append("> {\n")
          .append("    static void encodeInto(std::vector<uint8_t>& b, const ").append(cppName).append("& v) {\n");
        for (FieldSpec f : fields) {
            emitEncodeField(sb, "v." + NameUtils.toFieldName(f.name), f.dataTypeName, "        ");
        }
        sb.append("    }\n};\n\n");
        return sb.toString();
    }

    private void emitEncodeField(StringBuilder sb, String accessExpr, String dataTypeName, String indent) {
        if (mapper.isPaddingMarker(dataTypeName)) {
            // Champ de bourrage pur (RPRpaddingTo32/64Array) : aucune
            // valeur associee, seul l'effet de bord d'alignement compte.
            sb.append(indent).append("hla_encoding::appendPadding(b, ").append(mapper.paddingMarkerBoundary(dataTypeName)).append(");\n");
            return;
        }
        TypeMapper.BasicRepr basic = mapper.resolveBasicRepr(dataTypeName);
        if (basic != null) {
            sb.append(indent).append("hla_encoding::").append(basic.encodeFn).append("(b, ").append(accessExpr).append(");\n");
            return;
        }
        DataType.Array array = mapper.resolveArrayType(dataTypeName);
        if (array != null) {
            if (array.isDynamic()) {
                // HLAvariableArray demarre par un compteur HLAinteger32BE :
                // aligner sur 4 avant, comme tout champ compose (cf. IEEE 1516.2).
                sb.append(indent).append("hla_encoding::appendPadding(b, ").append(mapper.octetBoundaryOf(dataTypeName)).append(");\n");
                sb.append(indent).append("HLAEncoderDetail<").append(mapper.cppTypeName(dataTypeName))
                  .append(">::encodeInto(b, ").append(accessExpr).append(");\n");
            } else {
                sb.append(indent).append("for (const auto& _elem : ").append(accessExpr).append(") {\n");
                emitEncodeField(sb, "_elem", array.elementDataTypeName, indent + "    ");
                sb.append(indent).append("}\n");
            }
            return;
        }
        DataType dt = model.dataType(dataTypeName);
        // Champ compose (enum/fixedRecord/variantRecord) : aligner sur son
        // PROPRE octet boundary avant de l'ecrire (IEEE 1516.2 -- chaque
        // type a un octetBoundary, y compris les types composes). Sans ce
        // padding, l'encodage ne serait correct que par coincidence quand
        // le champ compose se trouve deja etre le premier de sa structure
        // parente (ce qui a longtemps masque ce manque dans nos tests).
        sb.append(indent).append("hla_encoding::appendPadding(b, ").append(mapper.octetBoundaryOf(dataTypeName)).append(");\n");
        sb.append(indent).append("HLAEncoderDetail<").append(NameUtils.sanitizeIdentifier(dt.name))
          .append(">::encodeInto(b, ").append(accessExpr).append(");\n");
    }

    public String generateHLADecoder() {
        StringBuilder sb = new StringBuilder();
        sb.append("#pragma once\n//\n// HLADecoder.hpp -- GENERE depuis le(s) fichier(s) FOM.\n//\n\n");
        sb.append("#include <array>\n#include <cstdint>\n#include <stdexcept>\n#include <string>\n#include <vector>\n\n");
        sb.append("#include \"HLAAlignment.hpp\"\n#include \"HLADecoding.hpp\"\n#include \"../HLATypes.hpp\"\n\n");
        sb.append("namespace rprfom {\n\n");
        sb.append("template <typename T>\nstruct HLADecoderDetail {\n")
          .append("    static T decodeAt(const std::vector<uint8_t>&, size_t&) {\n")
          .append("        throw std::logic_error(\"Aucun HLADecoderDetail<T> specialise pour ce type RPR-FOM.\");\n")
          .append("    }\n};\n\n");
        sb.append("template <typename T>\nstruct HLADecoder {\n")
          .append("    static T decode(const std::vector<uint8_t>& bytes) {\n")
          .append("        size_t offset = 0;\n        return HLADecoderDetail<T>::decodeAt(bytes, offset);\n    }\n")
          .append("    static T decodeAt(const std::vector<uint8_t>& bytes, size_t& offset) {\n")
          .append("        return HLADecoderDetail<T>::decodeAt(bytes, offset);\n    }\n};\n\n");

        java.util.Set<String> emittedAttrTypesDec = new java.util.LinkedHashSet<>();
        for (TypeMapper.BasicRepr basic : TypeMapper.allBasicReprs()) {
            if (!emittedAttrTypesDec.add(basic.attrCppType)) continue;
            String decoded = "hla_decoding::" + basic.decodeFn + "(bytes, offset)";
            String returnExpr = basic.attrCppAccessor.isEmpty()
                    ? "return " + decoded + ";"
                    : "return " + basic.attrCppType + "{" + decoded + "};";
            sb.append("template <>\nstruct HLADecoderDetail<").append(basic.attrCppType).append("> {\n")
              .append("    static ").append(basic.attrCppType).append(" decodeAt(const std::vector<uint8_t>& bytes, size_t& offset) {\n")
              .append("        ").append(returnExpr).append("\n    }\n};\n\n");
        }

        // Miroir decodeur de la specialisation generique HLAEncoderDetail<std::array<T,N>>
        // ajoutee dans HLAEncoder.hpp -- cf. son commentaire pour le contexte complet
        // (cas reels RPR-FOM v3.0/2025 : OpaqueDatum64, RPRnoData, ASCIIcharArray8).
        // Lit exactement N elements, aucun compteur sur le fil (HLAfixedArray).
        // DOIT apparaitre ICI, avant la boucle topologicalOrder() ci-dessous : cette
        // boucle genere (pour OpaqueDatum64Array1Plus notamment) un decodeur dont le
        // corps appelle HLADecoderDetail<std::array<Octet,8>>::decodeAt(...), ce qui
        // force l'instanciation implicite du template PRIMAIRE (qui leve une
        // std::logic_error) pour ce type s'il n'a pas deja une specialisation visible
        // a ce point du fichier -- une specialisation partielle declaree APRES une
        // telle instanciation est un cas interdit par la norme C++ (erreur de
        // compilation -fpermissive : "partial specialization ... after instantiation
        // of ..."), rencontre concretement en placant ce bloc a la fin du fichier.
        sb.append("template <typename T, size_t N>\nstruct HLADecoderDetail<std::array<T, N>> {\n")
          .append("    static std::array<T, N> decodeAt(const std::vector<uint8_t>& bytes, size_t& offset) {\n")
          .append("        std::array<T, N> result{};\n")
          .append("        for (size_t i = 0; i < N; ++i) {\n")
          .append("            hla_decoding::skipPadding(bytes, offset, HLAOctetBoundary<T>::value);\n")
          .append("            result[i] = HLADecoderDetail<T>::decodeAt(bytes, offset);\n        }\n")
          .append("        return result;\n    }\n};\n\n");

        for (DataType dt : mapper.topologicalOrder()) {
            if (userTypes.contains(dt.name)) {
                String cls = NameUtils.sanitizeIdentifier(dt.name);
                sb.append("// Type a responsabilite du developpeur : declaration seule, la definition est\n");
                sb.append("// dans usertypes/").append(cls).append("Codec.hpp (incluse en fin de fichier).\n");
                sb.append("template <>\nstruct HLADecoderDetail<").append(cls).append("> {\n")
                  .append("    static ").append(cls).append(" decodeAt(const std::vector<uint8_t>& bytes, size_t& offset);\n};\n\n");
                continue;
            }
            if (dt instanceof DataType.Enumerated e) {
                TypeMapper.BasicRepr underlying = mapper.resolveEnumUnderlying(e);
                String decodeFn = underlying != null ? underlying.decodeFn : "readInteger32BE";
                String name = NameUtils.sanitizeIdentifier(e.name);
                sb.append("template <>\nstruct HLADecoderDetail<").append(name).append("> {\n")
                  .append("    static ").append(name).append(" decodeAt(const std::vector<uint8_t>& bytes, size_t& offset) {\n")
                  .append("        return static_cast<").append(name).append(">(hla_decoding::").append(decodeFn).append("(bytes, offset));\n    }\n};\n\n");
            } else if (dt instanceof DataType.FixedRecord r) {
                sb.append(generateStructDecoder(r.name, toFields(r)));
            } else if (dt instanceof DataType.VariantRecord v) {
                sb.append(generateVariantDecoder(v));
            } else if (dt instanceof DataType.Array array && array.isDynamic() && isGeneratableArray(array)) {
                sb.append(generateArrayDecoder(array));
            }
        }

        sb.append("template <typename T>\nstruct HLADecoderDetail<std::vector<T>> {\n")
          .append("    static std::vector<T> decodeAt(const std::vector<uint8_t>& bytes, size_t& offset) {\n")
          .append("        uint32_t count = static_cast<uint32_t>(hla_decoding::readUnsignedInteger32BE(bytes, offset));\n")
          .append("        std::vector<T> result;\n        result.reserve(count);\n")
          .append("        for (uint32_t i = 0; i < count; ++i) {\n")
          .append("            hla_decoding::skipPadding(bytes, offset, HLAOctetBoundary<T>::value);\n")
          .append("            result.push_back(HLADecoderDetail<T>::decodeAt(bytes, offset));\n        }\n")
          .append("        return result;\n    }\n};\n\n");

        sb.append("} // namespace rprfom\n");
        if (!userTypes.isEmpty()) {
            sb.append("\n// Definitions inline des codecs des types a responsabilite du developpeur.\n");
            sb.append("#include \"HLAUserCodecs.hpp\"\n");
        }
        return sb.toString();
    }

    private String generateArrayDecoder(DataType.Array array) {
        String name = NameUtils.sanitizeIdentifier(array.name);
        String elementType = mapper.cppTypeName(array.elementDataTypeName);
        int elementBoundary = mapper.octetBoundaryOf(array.elementDataTypeName);
        StringBuilder sb = new StringBuilder();
        sb.append("template <>\nstruct HLADecoderDetail<").append(name).append("> {\n")
          .append("    static ").append(name).append(" decodeAt(const std::vector<uint8_t>& bytes, size_t& offset) {\n")
          .append("        ").append(name).append(" v{};\n");
        if (array.encoding == DataType.ArrayEncoding.HLA_VARIABLE_ARRAY) {
            sb.append("        uint32_t count = static_cast<uint32_t>(hla_decoding::readUnsignedInteger32BE(bytes, offset));\n")
              .append("        v.value.reserve(count);\n")
              .append("        for (uint32_t i = 0; i < count; ++i) {\n")
              .append("            hla_decoding::skipPadding(bytes, offset, ").append(elementBoundary).append(");\n")
              .append("            v.value.push_back(HLADecoderDetail<").append(elementType).append(">::decodeAt(bytes, offset));\n")
              .append("        }\n");
        } else if (array.encoding == DataType.ArrayEncoding.RPR_NULL_TERMINATED_ARRAY) {
            // Element d'1 octet garanti par validateNullTerminatedArray :
            // pas de padding a gerer, on peut lire l'octet directement pour
            // tester la fin de chaine avant de decoder un element complet.
            sb.append("        while (offset < bytes.size() && bytes[offset] != 0) {\n")
              .append("            v.value.push_back(HLADecoderDetail<").append(elementType).append(">::decodeAt(bytes, offset));\n")
              .append("        }\n")
              .append("        if (offset < bytes.size()) {\n")
              .append("            ++offset; // consomme le terminateur nul\n")
              .append("        }\n");
        } else {
            // RPRlengthlessArray : pas de compteur -- consomme les elements
            // jusqu'a epuisement du buffer (valide seulement en derniere
            // position d'une valeur encodee).
            sb.append("        while (offset < bytes.size()) {\n")
              .append("            hla_decoding::skipPadding(bytes, offset, ").append(elementBoundary).append(");\n")
              .append("            if (offset >= bytes.size()) break;\n")
              .append("            v.value.push_back(HLADecoderDetail<").append(elementType).append(">::decodeAt(bytes, offset));\n")
              .append("        }\n");
        }
        sb.append("        return v;\n    }\n};\n\n");
        return sb.toString();
    }

    private String generateVariantDecoder(DataType.VariantRecord v) {
        String name = NameUtils.sanitizeIdentifier(v.name);
        TypeMapper.BasicRepr discUnderlying = mapper.resolveDiscriminantUnderlying(v);
        if (discUnderlying == null) {
            throw new IllegalStateException("Discriminant non resolu pour le variant '" + v.name + "'");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("template <>\nstruct HLADecoderDetail<").append(name).append("> {\n")
          .append("    static ").append(name).append(" decodeAt(const std::vector<uint8_t>& bytes, size_t& offset) {\n")
          .append("        ").append(name).append(" v{};\n")
          .append("        auto disc = hla_decoding::").append(discUnderlying.decodeFn).append("(bytes, offset);\n")
          .append("        switch (static_cast<long long>(disc)) {\n");
        for (int i = 0; i < v.alternatives.size(); i++) {
            DataType.VariantRecord.Alternative alt = v.alternatives.get(i);
            // Plusieurs valeurs de discriminant peuvent partager une meme
            // alternative (ex. HAVE_QUICK_I/HAVE_QUICK_II/SATURN -> cf.
            // javadoc de TypeMapper.discriminantValuesOf) : un "case" par
            // valeur, tous empilant (fallthrough) sur le meme corps.
            for (long discVal : mapper.discriminantValuesOf(v, alt)) {
                sb.append("            case ").append(discVal).append(":\n");
            }
            String altType = mapper.cppTypeName(alt.dataTypeName);
            sb.append("                hla_decoding::skipPadding(bytes, offset, ").append(mapper.octetBoundaryOf(alt.dataTypeName)).append(");\n")
              .append("                v.alternative.emplace<").append(i).append(">(HLADecoderDetail<").append(altType).append(">::decodeAt(bytes, offset));\n")
              .append("                break;\n");
        }
        sb.append("            default:\n")
          .append("                throw std::logic_error(\"").append(name).append(" : discriminant inconnu\");\n")
          .append("        }\n        return v;\n    }\n};\n\n");
        return sb.toString();
    }


    private String generateStructDecoder(String name, List<FieldSpec> fields) {
        String cppName = NameUtils.sanitizeIdentifier(name);
        StringBuilder sb = new StringBuilder();
        sb.append("template <>\nstruct HLADecoderDetail<").append(cppName).append("> {\n")
          .append("    static ").append(cppName).append(" decodeAt(const std::vector<uint8_t>& bytes, size_t& offset) {\n")
          .append("        ").append(cppName).append(" v{};\n");
        for (FieldSpec f : fields) {
            emitDecodeField(sb, "v." + NameUtils.toFieldName(f.name), f.dataTypeName, "        ");
        }
        sb.append("        return v;\n    }\n};\n\n");
        return sb.toString();
    }

    private void emitDecodeField(StringBuilder sb, String accessExpr, String dataTypeName, String indent) {
        if (mapper.isPaddingMarker(dataTypeName)) {
            // Champ de bourrage pur : aucun membre a assigner, seul le
            // deplacement de l'offset compte (accessExpr n'existe pas).
            sb.append(indent).append("hla_decoding::skipPadding(bytes, offset, ").append(mapper.paddingMarkerBoundary(dataTypeName)).append(");\n");
            return;
        }
        TypeMapper.BasicRepr basic = mapper.resolveBasicRepr(dataTypeName);
        if (basic != null) {
            sb.append(indent).append(accessExpr).append(" = hla_decoding::").append(basic.decodeFn).append("(bytes, offset);\n");
            return;
        }
        DataType.Array array = mapper.resolveArrayType(dataTypeName);
        if (array != null) {
            if (array.isDynamic()) {
                sb.append(indent).append("hla_decoding::skipPadding(bytes, offset, ").append(mapper.octetBoundaryOf(dataTypeName)).append(");\n");
                sb.append(indent).append(accessExpr).append(" = HLADecoderDetail<").append(mapper.cppTypeName(dataTypeName))
                  .append(">::decodeAt(bytes, offset);\n");
            } else {
                String idx = "_i" + Math.abs(accessExpr.hashCode() % 1000);
                sb.append(indent).append("for (size_t ").append(idx).append(" = 0; ").append(idx).append(" < ")
                  .append(array.fixedCardinality).append("; ++").append(idx).append(") {\n");
                emitDecodeField(sb, accessExpr + "[" + idx + "]", array.elementDataTypeName, indent + "    ");
                sb.append(indent).append("}\n");
            }
            return;
        }
        DataType dt = model.dataType(dataTypeName);
        sb.append(indent).append("hla_decoding::skipPadding(bytes, offset, ").append(mapper.octetBoundaryOf(dataTypeName)).append(");\n");
        sb.append(indent).append(accessExpr).append(" = HLADecoderDetail<").append(NameUtils.sanitizeIdentifier(dt.name))
          .append(">::decodeAt(bytes, offset);\n");
    }

    // ====================================================================
    // Type "a responsabilite du developpeur" (secours / modele / reference)
    // ====================================================================

    /** runtime/HLAUserCodecs.hpp : inclut les codecs des types a responsabilite du developpeur. */
    public String generateHLAUserCodecs() {
        StringBuilder sb = new StringBuilder();
        sb.append("#pragma once\n//\n// HLAUserCodecs.hpp -- GENERE. Definitions inline des codecs des types FOM\n");
        sb.append("// a responsabilite du developpeur (usertypes/<Type>Codec.hpp). Inclus en fin de\n");
        sb.append("// HLAEncoder.hpp et de HLADecoder.hpp ; chaque codec inclut lui-meme les deux.\n//\n\n");
        for (String name : userTypes) {
            sb.append("#include \"../usertypes/").append(NameUtils.sanitizeIdentifier(name)).append("Codec.hpp\"\n");
        }
        return sb.toString();
    }

    private DataType.FixedRecord userRecord(String typeName) {
        DataType dt = model.dataType(typeName);
        if (!(dt instanceof DataType.FixedRecord rec)) {
            throw new IllegalStateException("Type '" + typeName + "' : seul un fixedRecordData peut etre gere par le developpeur.");
        }
        return rec;
    }

    /**
     * usertypes/X.hpp : la struct seule (classe de secours / modele).
     * Incluse par HLATypes.hpp a la position du type : ne se compile pas
     * isolement (utilise les alias et types precedents de HLATypes.hpp).
     */
    public String generateUserTypeHeader(String typeName, String fingerprint) {
        String cls = NameUtils.sanitizeIdentifier(typeName);
        List<FieldSpec> fields = toFields(userRecord(typeName));
        StringBuilder sb = new StringBuilder();
        sb.append("#pragma once\n");
        sb.append("//\n// ").append(cls).append(".hpp -- type FOM \"").append(typeName).append("\" a RESPONSABILITE DU DEVELOPPEUR.\n//\n");
        sb.append("// Ce fichier est la CLASSE DE SECOURS generee par rprfom-codegen : aucun service\n");
        sb.append("// personnalise, encodage strictement conforme a la FOM. Il est copie dans les\n");
        sb.append("// sources tant que vous ne fournissez pas votre propre version.\n//\n");
        sb.append("// Pour le personnaliser : copiez ") .append(cls).append(".hpp ET ").append(cls).append("Codec.hpp hors du dossier\n");
        sb.append("// genere, modifiez-les (ajoutez vos champs/methodes), puis fournissez-les avec\n");
        sb.append("// --user-types-cpp <dossier> ou --user-type-cpp ").append(typeName).append("=<X.hpp>[,<XCodec.hpp>].\n");
        sb.append("// Regles : struct dans 'namespace rprfom' ; le codec n'encode/decode QUE les champs de\n");
        sb.append("// la FOM, dans l'ordre, en big-endian, avec le padding interne au record (le padding\n");
        sb.append("// avant le record est ajoute par l'appelant) ; gardez l'empreinte ci-dessous.\n");
        sb.append("// Ne pas inclure ce fichier directement : incluez HLATypes.hpp.\n//\n");
        sb.append("// @rprfom-fom-fingerprint: ").append(fingerprint).append("\n//\n\n");
        sb.append("#include <array>\n#include <cstdint>\n#include <string>\n#include <variant>\n#include <vector>\n\n");
        sb.append("namespace rprfom {\n\n");
        sb.append("struct ").append(cls).append(" {\n");
        for (FieldSpec f : fields) {
            if (mapper.isPaddingMarker(f.dataTypeName)) continue;
            sb.append("    ").append(mapper.cppTypeName(f.dataTypeName)).append(" ")
              .append(NameUtils.toFieldName(f.name)).append("{};\n");
        }
        sb.append("};\n\n} // namespace rprfom\n");
        return sb.toString();
    }

    /** usertypes/XCodec.hpp : definitions inline de HLAEncoderDetail<X>::encodeInto et HLADecoderDetail<X>::decodeAt. */
    public String generateUserTypeCodec(String typeName, String fingerprint) {
        String cls = NameUtils.sanitizeIdentifier(typeName);
        List<FieldSpec> fields = toFields(userRecord(typeName));
        StringBuilder sb = new StringBuilder();
        sb.append("#pragma once\n");
        sb.append("//\n// ").append(cls).append("Codec.hpp -- encodage/decodage HLA du type FOM \"").append(typeName).append("\"\n");
        sb.append("// (classe de secours generee ; cf. ").append(cls).append(".hpp pour le mode d'emploi).\n");
        sb.append("// Les specialisations HLAEncoderDetail<").append(cls).append("> / HLADecoderDetail<").append(cls).append("> sont\n");
        sb.append("// declarees par HLAEncoder.hpp / HLADecoder.hpp : ce fichier en fournit les definitions.\n//\n");
        sb.append("// @rprfom-fom-fingerprint: ").append(fingerprint).append("\n//\n\n");
        sb.append("#include \"../runtime/HLAEncoder.hpp\"\n#include \"../runtime/HLADecoder.hpp\"\n\n");
        sb.append("namespace rprfom {\n\n");
        sb.append("inline void HLAEncoderDetail<").append(cls).append(">::encodeInto(std::vector<uint8_t>& b, const ")
          .append(cls).append("& v) {\n");
        for (FieldSpec f : fields) {
            emitEncodeField(sb, "v." + NameUtils.toFieldName(f.name), f.dataTypeName, "    ");
        }
        sb.append("}\n\n");
        sb.append("inline ").append(cls).append(" HLADecoderDetail<").append(cls)
          .append(">::decodeAt(const std::vector<uint8_t>& bytes, size_t& offset) {\n");
        sb.append("    ").append(cls).append(" v{};\n");
        for (FieldSpec f : fields) {
            emitDecodeField(sb, "v." + NameUtils.toFieldName(f.name), f.dataTypeName, "    ");
        }
        sb.append("    return v;\n}\n\n} // namespace rprfom\n");
        return sb.toString();
    }

    /**
     * Reference du test de conformite : struct "Ref_X" + fonctions libres
     * ref_encode_X / ref_decode_X portant le code standard genere pour le
     * type. A placer dans un namespace anonyme apres "using namespace rprfom;".
     */
    public String generateUserTypeReference(String typeName) {
        String cls = NameUtils.sanitizeIdentifier(typeName);
        List<FieldSpec> fields = toFields(userRecord(typeName));
        StringBuilder sb = new StringBuilder();
        sb.append("struct Ref_").append(cls).append(" {\n");
        for (FieldSpec f : fields) {
            if (mapper.isPaddingMarker(f.dataTypeName)) continue;
            sb.append("    ").append(mapper.cppTypeName(f.dataTypeName)).append(" ")
              .append(NameUtils.toFieldName(f.name)).append("{};\n");
        }
        sb.append("};\n\n");
        sb.append("[[maybe_unused]] void ref_encode_").append(cls).append("(std::vector<uint8_t>& b, const Ref_").append(cls).append("& v) {\n");
        for (FieldSpec f : fields) {
            emitEncodeField(sb, "v." + NameUtils.toFieldName(f.name), f.dataTypeName, "    ");
        }
        sb.append("}\n\n");
        sb.append("[[maybe_unused]] Ref_").append(cls).append(" ref_decode_").append(cls)
          .append("(const std::vector<uint8_t>& bytes, size_t& offset) {\n");
        sb.append("    Ref_").append(cls).append(" v{};\n");
        for (FieldSpec f : fields) {
            emitDecodeField(sb, "v." + NameUtils.toFieldName(f.name), f.dataTypeName, "    ");
        }
        sb.append("    return v;\n}\n\n");
        return sb.toString();
    }

    // ====================================================================
    // Une classe par objectClass
    // ====================================================================

    /** Genere le .hpp d'un noeud, connaissant son chemin FOM complet et son parent (null si racine). */
    public String generateObjectClass(ObjectClassNode node, List<String> pathFromRoot, ObjectClassNode parent) {
        String className = NameUtils.sanitizeIdentifier(node.name);
        boolean isRootClass = (parent == null);
        StringBuilder sb = new StringBuilder();
        sb.append("#pragma once\n//\n// ").append(className).append(".hpp -- GENERE depuis le(s) fichier(s) FOM.\n")
          .append("// FOM: ").append(String.join(".", pathFromRoot)).append("\n//\n\n");
        sb.append("#include \"runtime/HLAAttribute.hpp\"\n#include \"HLATypes.hpp\"\n");
        if (isRootClass) {
            sb.append("#include \"runtime/IHLAAttribute.h\"\n\n#include <map>\n#include <string>\n\n");
        } else {
            sb.append("#include \"").append(NameUtils.sanitizeIdentifier(parent.name)).append(".hpp\"\n\n");
        }
        sb.append("namespace rprfom {\n\n");
        sb.append("class ").append(className);
        if (!isRootClass) {
            sb.append(" : public ").append(NameUtils.sanitizeIdentifier(parent.name));
        }
        sb.append(" {\npublic:\n");
        sb.append("    static constexpr const char* HLA_OBJECT_CLASS_NAME = \"")
          .append(String.join(".", pathFromRoot)).append("\";\n\n");

        sb.append("    ").append(className).append("() {\n");
        for (ObjectClassNode.AttributeDef a : node.ownAttributes) {
            sb.append("        registerAttribute(\"").append(a.name).append("\", ")
              .append(NameUtils.toFieldName(a.name)).append(");\n");
        }
        sb.append("    }\n\n");

        if (isRootClass) {
            sb.append("    virtual ~").append(className).append("() = default;\n\n");
            sb.append("    // Non copiable : attributesByName_ range l'adresse des attributs de\n")
              .append("    // CETTE instance (une copie pointerait sur ceux de l'original).\n");
            sb.append("    ").append(className).append("(const ").append(className).append("&) = delete;\n");
            sb.append("    ").append(className).append("& operator=(const ").append(className).append("&) = delete;\n\n");
        }

        // Nom FOM de la classe dynamique (polymorphe : utile quand on ne tient
        // qu'un pointeur sur une classe ancetre).
        sb.append("    ").append(isRootClass ? "virtual " : "").append("const char* hlaObjectClassName() const")
          .append(isRootClass ? "" : " override").append(" {\n")
          .append("        return HLA_OBJECT_CLASS_NAME;\n    }\n\n");

        for (ObjectClassNode.AttributeDef a : node.ownAttributes) {
            String cppType = mapper.cppAttributeTypeName(a.dataTypeName);
            sb.append("    HLAAttribute<").append(cppType).append("> ")
              .append(NameUtils.toFieldName(a.name)).append(";\n");
        }

        if (isRootClass) {
            sb.append("\n    // ------------------------------------------------------------------\n");
            sb.append("    // Acces generique (independant de T), indexe par handle RTI\n");
            sb.append("    // ------------------------------------------------------------------\n\n");
            sb.append("    void bindAttributeHandle(const std::string& attributeName, long handle) {\n")
              .append("        auto it = attributesByName_.find(attributeName);\n")
              .append("        if (it != attributesByName_.end()) {\n")
              .append("            attributesByHandle_[handle] = it->second;\n        }\n    }\n\n");
            sb.append("    IHLAAttributeBase* attribute(long handle) const {\n")
              .append("        auto it = attributesByHandle_.find(handle);\n")
              .append("        return it != attributesByHandle_.end() ? it->second : nullptr;\n    }\n\n");
            sb.append("    const std::map<long, IHLAAttributeBase*>& attributesByHandle() const {\n")
              .append("        return attributesByHandle_;\n    }\n\n");
            sb.append("    // ------------------------------------------------------------------\n");
            sb.append("    // Acces generique par nom FOM (contrat utilise par HLAbstract C++)\n");
            sb.append("    // ------------------------------------------------------------------\n\n");
            sb.append("    // Tous les attributs generes de l'instance (heritage compris), par nom FOM.\n");
            sb.append("    const std::map<std::string, IHLAAttributeBase*>& attributesByName() const {\n")
              .append("        return attributesByName_;\n    }\n\n");
            sb.append("    // Attribut de nom FOM donne, ou nullptr s'il n'est pas genere.\n");
            sb.append("    IHLAAttributeBase* attributeByName(const std::string& attributeName) const {\n")
              .append("        auto it = attributesByName_.find(attributeName);\n")
              .append("        return it != attributesByName_.end() ? it->second : nullptr;\n    }\n\n");
            sb.append("protected:\n");
            sb.append("    void registerAttribute(const std::string& name, IHLAAttributeBase& attribute) {\n")
              .append("        attributesByName_[name] = &attribute;\n    }\n\n");
            sb.append("private:\n");
            sb.append("    std::map<std::string, IHLAAttributeBase*> attributesByName_;\n");
            sb.append("    std::map<long, IHLAAttributeBase*> attributesByHandle_;\n");
        }

        sb.append("};\n\n} // namespace rprfom\n");
        return sb.toString();
    }

    // ====================================================================
    // Une classe par interactionClass (miroir de generateObjectClass, sans
    // ownership -- une interaction est un message envoye une fois, pas un
    // etat persistant possede).
    // ====================================================================

    public String generateInteractionClass(InteractionClassNode node, List<String> pathFromRoot, InteractionClassNode parent) {
        String className = NameUtils.sanitizeIdentifier(node.name);
        boolean isRootClass = (parent == null);
        StringBuilder sb = new StringBuilder();
        sb.append("#pragma once\n//\n// ").append(className).append(".hpp -- GENERE depuis le(s) fichier(s) FOM.\n")
          .append("// FOM (interaction): ").append(String.join(".", pathFromRoot)).append("\n//\n\n");
        sb.append("#include \"runtime/HLAParameter.hpp\"\n#include \"HLATypes.hpp\"\n");
        if (isRootClass) {
            sb.append("#include \"runtime/IHLAParameter.h\"\n\n#include <map>\n#include <string>\n\n");
        } else {
            sb.append("#include \"").append(NameUtils.sanitizeIdentifier(parent.name)).append(".hpp\"\n\n");
        }
        sb.append("namespace rprfom {\n\n");
        sb.append("class ").append(className);
        if (!isRootClass) {
            sb.append(" : public ").append(NameUtils.sanitizeIdentifier(parent.name));
        }
        sb.append(" {\npublic:\n");
        sb.append("    static constexpr const char* HLA_INTERACTION_CLASS_NAME = \"")
          .append(String.join(".", pathFromRoot)).append("\";\n\n");

        sb.append("    ").append(className).append("() {\n");
        for (InteractionClassNode.ParameterDef p : node.ownParameters) {
            sb.append("        registerParameter(\"").append(p.name).append("\", ")
              .append(NameUtils.toFieldName(p.name)).append(");\n");
        }
        sb.append("    }\n\n");

        if (isRootClass) {
            sb.append("    virtual ~").append(className).append("() = default;\n\n");
            sb.append("    // Non copiable : parametersByName_ range l'adresse des parametres de\n")
              .append("    // CETTE instance (une copie pointerait sur ceux de l'original).\n");
            sb.append("    ").append(className).append("(const ").append(className).append("&) = delete;\n");
            sb.append("    ").append(className).append("& operator=(const ").append(className).append("&) = delete;\n\n");
        }

        sb.append("    ").append(isRootClass ? "virtual " : "").append("const char* hlaInteractionClassName() const")
          .append(isRootClass ? "" : " override").append(" {\n")
          .append("        return HLA_INTERACTION_CLASS_NAME;\n    }\n\n");

        for (InteractionClassNode.ParameterDef p : node.ownParameters) {
            String cppType = mapper.cppAttributeTypeName(p.dataTypeName);
            sb.append("    HLAParameter<").append(cppType).append("> ")
              .append(NameUtils.toFieldName(p.name)).append(";\n");
        }

        if (isRootClass) {
            sb.append("\n    // ------------------------------------------------------------------\n");
            sb.append("    // Acces generique (independant de T), indexe par handle RTI\n");
            sb.append("    // ------------------------------------------------------------------\n\n");
            sb.append("    void bindParameterHandle(const std::string& parameterName, long handle) {\n")
              .append("        auto it = parametersByName_.find(parameterName);\n")
              .append("        if (it != parametersByName_.end()) {\n")
              .append("            parametersByHandle_[handle] = it->second;\n        }\n    }\n\n");
            sb.append("    IHLAParameterBase* parameter(long handle) const {\n")
              .append("        auto it = parametersByHandle_.find(handle);\n")
              .append("        return it != parametersByHandle_.end() ? it->second : nullptr;\n    }\n\n");
            sb.append("    const std::map<long, IHLAParameterBase*>& parametersByHandle() const {\n")
              .append("        return parametersByHandle_;\n    }\n\n");
            sb.append("    // ------------------------------------------------------------------\n");
            sb.append("    // Acces generique par nom FOM (contrat utilise par HLAbstract C++)\n");
            sb.append("    // ------------------------------------------------------------------\n\n");
            sb.append("    // Tous les parametres generes de l'interaction (heritage compris), par nom FOM.\n");
            sb.append("    const std::map<std::string, IHLAParameterBase*>& parametersByName() const {\n")
              .append("        return parametersByName_;\n    }\n\n");
            sb.append("    // Parametre de nom FOM donne, ou nullptr s'il n'est pas genere.\n");
            sb.append("    IHLAParameterBase* parameterByName(const std::string& parameterName) const {\n")
              .append("        auto it = parametersByName_.find(parameterName);\n")
              .append("        return it != parametersByName_.end() ? it->second : nullptr;\n    }\n\n");
            sb.append("protected:\n");
            sb.append("    void registerParameter(const std::string& name, IHLAParameterBase& parameter) {\n")
              .append("        parametersByName_[name] = &parameter;\n    }\n\n");
            sb.append("private:\n");
            sb.append("    std::map<std::string, IHLAParameterBase*> parametersByName_;\n");
            sb.append("    std::map<long, IHLAParameterBase*> parametersByHandle_;\n");
        }

        sb.append("};\n\n} // namespace rprfom\n");
        return sb.toString();
    }

    private static final class FieldSpec {
        final String name;
        final String dataTypeName;

        FieldSpec(String name, String dataTypeName) {
            this.name = name;
            this.dataTypeName = dataTypeName;
        }
    }
}
