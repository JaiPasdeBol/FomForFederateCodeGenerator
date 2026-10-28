package rprfom.codegen.generate;

import rprfom.codegen.model.DataType;
import rprfom.codegen.model.FomModel;
import rprfom.codegen.model.InteractionClassNode;
import rprfom.codegen.model.ObjectClassNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Genere les fichiers Java derives du FOM, miroir de CppGenerator.
 *
 * Deux packages Java distincts sont produits, dans deux sous-dossiers
 * distincts de src/main/java/ (cf. Main) :
 * <ul>
 *   <li>{@code packageName} (ex. "rprfom") : les classes qui refletent
 *       directement le contenu du/des fichier(s) FOM XML -- HLATypes.java
 *       (types de donnees du FOM) et une classe par objectClass/
 *       interactionClass (Radio.java, EmitterSystem.java, ...).</li>
 *   <li>{@code runtimePackageName} (ex. "rprfom.runtime") : l'infrastructure
 *       d'encodage/decodage HLA, commune a tous les FOM et jamais
 *       specifique a l'un d'eux -- les fichiers "statiques" copies tels
 *       quels depuis les ressources du generateur (HLAAttribute.java,
 *       HLAParameter.java, HLAEncoding.java, HLADecoding.java,
 *       HLAEncoderDetail.java, HLADecoderDetail.java, HLAOctet.java et les
 *       autres representations de base HLAUnsignedInteger16/32/64(LE),
 *       HLAInteger16/32/64LE, HLAFloat32/64LE, HLAOctetPair,
 *       HLAASCIIString) ainsi que les fichiers generes qui orchestrent
 *       l'encodage pour CE FOM precis mais ne contiennent aucune classe
 *       metier (HLAAlignment.java, HLAEncoder.java, HLADecoder.java).
 * </ul>
 * Cette separation repond a une demande explicite : pouvoir distinguer
 * d'un coup d'oeil "les classes generees a partir du FOM" de "la
 * mecanique d'encodage centralisee", sans avoir a lire chaque fichier.
 * Les interfaces COMMUNES a tous les federes (HLAObjectClass,
 * HLAInteractionClass, IHLAAttributeBase, IHLAParameterBase, OwnershipState)
 * ne sont PAS copiees : elles viennent du jar partage rprfom-runtime-api
 * (package fixe rprfom.api), dont le code genere depend.
 *
 * Les classes du package racine importent le package runtime via
 * {@code import <runtimePackageName>.*;} pour rester utilisables
 * normalement (HLAAttribute, HLAOctet, etc. restent visibles sans
 * prefixe).
 */
public final class JavaGenerator {

    private final FomModel model;
    private final TypeMapper mapper;
    public final String packageName;
    public final String runtimePackageName;

    /**
     * Types FOM "a responsabilite du developpeur" : leur code n'est PAS
     * genere dans HLATypes/HLAEncoder/HLADecoder (cf. usertype.*), il est
     * copie depuis un fichier fourni ou, a defaut, depuis la classe de
     * secours generee par {@link #generateUserTypeClass}. Ce sont des
     * classes Java de premier niveau du package racine.
     */
    private java.util.Set<String> userTypes = java.util.Set.of();

    /** Prefixes des appels HLAEncoder/HLADecoder : vides dans HLAEncoder/HLADecoder eux-memes, qualifies dans une classe autonome. */
    private String encPrefix = "";
    private String decPrefix = "";

    public JavaGenerator(FomModel model, String packageName) {
        this.model = model;
        this.mapper = new TypeMapper(model);
        this.packageName = packageName;
        this.runtimePackageName = packageName + ".runtime";
    }

    public void setUserTypes(java.util.Set<String> names) {
        this.userTypes = new java.util.LinkedHashSet<>(names);
    }

    /** Imports (types utilisateur, package racine) pour une classe situee dans un AUTRE package que le package racine. */
    private String userTypeImports(String fromPackage, String excludeTypeName) {
        StringBuilder sb = new StringBuilder();
        for (String name : userTypes) {
            if (name.equals(excludeTypeName)) continue;
            if (!fromPackage.equals(packageName)) {
                sb.append("import ").append(packageName).append(".").append(NameUtils.sanitizeIdentifier(name)).append(";\n");
            }
        }
        return sb.toString();
    }

    // ====================================================================
    // HLATypes.java
    // ====================================================================

    public String generateHLATypes() {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import java.util.Arrays;\n");
        sb.append("import java.util.List;\n\n");
        sb.append("import ").append(runtimePackageName).append(".*;\n\n");
        sb.append("/**\n * HLATypes.java -- GENERE depuis le(s) fichier(s) FOM. Ne pas editer a la\n")
          .append(" * main, regenerer via rprfom-codegen a la place.\n */\n");
        sb.append("public final class HLATypes {\n\n    private HLATypes() {\n    }\n\n");

        for (DataType dt : mapper.topologicalOrder()) {
            if (userTypes.contains(dt.name)) {
                continue; // type a responsabilite du developpeur : classe de premier niveau a part
            }
            if (dt instanceof DataType.Enumerated e) {
                sb.append(generateEnum(e));
            } else if (dt instanceof DataType.FixedRecord r) {
                sb.append(generateStruct(r.name, toFields(r)));
            } else if (dt instanceof DataType.VariantRecord v) {
                sb.append(generateVariantClass(v));
            } else if (dt instanceof DataType.Array array && isGeneratableArray(array)) {
                // Type nomme genere pour TOUT tableau "generatable", fixe
                // OU dynamique (cf. javadoc de generateArrayWrapper) --
                // seul le marqueur de bourrage (RPRpaddingTo32/64Array)
                // n'a jamais de type du tout, cf. TypeMapper.isPaddingMarker.
                // Un tableau fixe utilise UNIQUEMENT comme champ direct
                // d'un struct reste traite en inline (byte[]/List<T>, cf.
                // TypeMapper.javaFieldTypeName) : ce type nomme-ci n'est
                // alors simplement jamais referencé, sans consequence.
                sb.append(generateArrayWrapper(array));
            }
        }

        sb.append("}\n");
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
     * Type nomme pour un tableau "generatable" (cf. isGeneratableArray),
     * fixe OU dynamique (HLAvariableArray, RPRlengthlessArray ou
     * HLAfixedArray). Un seul champ "value" ; le choix entre les
     * encodages se fait a l'encodage/decodage (cf. generateHLAEncoder/
     * generateHLADecoder), pas dans la classe elle-meme -- pour un
     * tableau fixe, "value" doit porter exactement array.fixedCardinality
     * elements (non impose par le type Java lui-meme, seulement par
     * l'encodeur/decodeur genere, cf. commentaire equivalent pour le
     * champ de struct dans TypeMapper.javaFieldTypeName).
     */
    private String generateArrayWrapper(DataType.Array array) {
        String name = NameUtils.sanitizeIdentifier(array.name);
        String elementClass = mapper.javaAttributeClassName(array.elementDataTypeName);
        StringBuilder sb = new StringBuilder();
        if (!array.isDynamic()) {
            sb.append("    // HLAfixedArray : cardinalite fixe (").append(array.fixedCardinality)
              .append("), pas de compteur sur le fil.\n");
        } else {
            sb.append("    // ").append(array.encoding == DataType.ArrayEncoding.RPR_LENGTHLESS_ARRAY
                    ? "RPRlengthlessArray : pas de compteur, les elements occupent tout le buffer restant.\n"
                    : "HLAvariableArray : compteur HLAinteger32BE + elements.\n");
        }
        sb.append("    public static final class ").append(name).append(" {\n")
          .append("        public List<").append(elementClass).append("> value = new java.util.ArrayList<>();\n")
          .append("    }\n\n");
        return sb.toString();
    }

    private String generateEnum(DataType.Enumerated e) {
        String name = NameUtils.sanitizeIdentifier(e.name);
        StringBuilder sb = new StringBuilder();
        sb.append("    public enum ").append(name).append(" {\n");
        // Cf. le commentaire equivalent dans CppGenerator.generateEnum :
        // deux enumerateurs FOM distincts peuvent converger vers la meme
        // constante apres sanitisation (rencontre en pratique dans de tres
        // gros enums compiles) -- on suffixe par un numero en cas de
        // collision plutot que de produire une redeclaration invalide.
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        for (int i = 0; i < e.enumerators.size(); i++) {
            DataType.Enumerated.Enumerator en = e.enumerators.get(i);
            String constName = NameUtils.toEnumConstant(en.name);
            Integer count = seen.merge(constName, 1, Integer::sum);
            if (count > 1) constName = constName + "_" + count;
            sb.append("        ").append(constName).append("(").append(en.value).append(")")
              .append(i == e.enumerators.size() - 1 ? ";\n" : ",\n");
        }
        sb.append("\n        public final long code;\n\n")
          .append("        ").append(name).append("(long code) {\n            this.code = code;\n        }\n\n")
          .append("        public static ").append(name).append(" fromCode(long code) {\n")
          .append("            for (").append(name).append(" v : values()) {\n")
          .append("                if (v.code == code) return v;\n            }\n")
          .append("            throw new IllegalArgumentException(\"").append(name).append(" inconnu: \" + code);\n")
          .append("        }\n    }\n\n");
        return sb.toString();
    }

    /**
     * HLAvariantRecord -> classe "tagged union" a la main (index de
     * l'alternative active + valeur), pas une hierarchie scellee avec
     * pattern matching : ca reste compatible Java 17 sans preview
     * feature, et ca fait exactement le meme travail ici.
     */
    private String generateVariantClass(DataType.VariantRecord v) {
        String name = NameUtils.sanitizeIdentifier(v.name);
        StringBuilder sb = new StringBuilder();
        sb.append("    // HLAvariantRecord : seule l'alternative active est stockee et encodee\n");
        sb.append("    // (discriminant FOM : ").append(v.discriminantName).append(").\n");
        sb.append("    public static final class ").append(name).append(" {\n");
        sb.append("        private final int alternativeIndex;\n");
        sb.append("        private final Object value;\n\n");
        sb.append("        private ").append(name).append("(int alternativeIndex, Object value) {\n")
          .append("            this.alternativeIndex = alternativeIndex;\n            this.value = value;\n        }\n\n");
        // IMPORTANT : type d'ATTRIBUT (javaAttributeClassName), pas type de
        // champ (javaFieldTypeName) -- cette valeur transite par le
        // registre generique HLAEncoder/HLADecoder (encodeInto/decodeAt,
        // indexe par Class<?>, cf. generateVariantEncoderRegistration/
        // generateVariantDecoderRegistration), qui exige systematiquement
        // le type d'attribut (ex. HLAUnsignedInteger16, pas le primitif
        // "int" qu'un champ de struct utiliserait librement -- cf. javadoc
        // de TypeMapper.BasicRepr). Utiliser javaFieldTypeName ici
        // desalignerait silencieusement les deux (erreur de compilation
        // avec certaines representations de base, ex.
        // TransponderDeltaAltitudeVariantStruct/UnsignedInteger16).
        for (int i = 0; i < v.alternatives.size(); i++) {
            DataType.VariantRecord.Alternative alt = v.alternatives.get(i);
            String altType = mapper.javaAttributeClassName(alt.dataTypeName);
            String methodSuffix = NameUtils.capitalize(alt.fieldName);
            sb.append("        public static ").append(name).append(" of").append(methodSuffix).append("(")
              .append(altType).append(" value) {\n")
              .append("            return new ").append(name).append("(").append(i).append(", value);\n        }\n\n");
        }
        sb.append("        public int alternativeIndex() {\n            return alternativeIndex;\n        }\n\n");
        for (int i = 0; i < v.alternatives.size(); i++) {
            DataType.VariantRecord.Alternative alt = v.alternatives.get(i);
            String altType = mapper.javaAttributeClassName(alt.dataTypeName);
            String methodSuffix = NameUtils.capitalize(alt.fieldName);
            sb.append("        public ").append(altType).append(" as").append(methodSuffix).append("() {\n")
              .append("            return (").append(altType).append(") value;\n        }\n\n");
        }
        sb.append("    }\n\n");
        return sb.toString();
    }


    private List<FieldSpec> toFields(DataType.FixedRecord r) {
        List<FieldSpec> fields = new ArrayList<>();
        for (DataType.FixedRecord.Field f : r.fields) fields.add(new FieldSpec(f.name, f.dataTypeName));
        return fields;
    }

    private List<FieldSpec> toFields(DataType.VariantRecord v) {
        List<FieldSpec> fields = new ArrayList<>();
        fields.add(new FieldSpec(v.discriminantName, v.discriminantDataTypeName));
        for (DataType.VariantRecord.Alternative a : v.alternatives) fields.add(new FieldSpec(a.fieldName, a.dataTypeName));
        return fields;
    }

    private String generateStruct(String name, List<FieldSpec> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("    public static final class ").append(NameUtils.sanitizeIdentifier(name)).append(" {\n");
        for (FieldSpec f : fields) {
            if (mapper.isPaddingMarker(f.dataTypeName)) {
                continue; // marqueur de bourrage pur (RPRpaddingTo32/64Array) : pas de champ, cf. TypeMapper.isPaddingMarker
            }
            String javaType = mapper.javaFieldTypeName(f.dataTypeName);
            String fieldName = NameUtils.toFieldName(f.name);
            sb.append("        public ").append(javaType).append(" ").append(fieldName)
              .append(defaultInit(f.dataTypeName, javaType)).append(";\n");
        }
        sb.append("    }\n\n");
        return sb.toString();
    }

    /** Initialisation par defaut d'un champ (les types composes ont besoin d'un "= new Xxx();"). */
    private String defaultInit(String dataTypeName, String javaType) {
        TypeMapper.BasicRepr basic = mapper.resolveBasicRepr(dataTypeName);
        if (basic != null) {
            // Cas particulier : HLAoctetPairBE/LE -> champ byte[2], a
            // defaut d'une representation composee pour ce cas.
            return javaType.equals("byte[]") ? " = new byte[2]" : "";
        }
        DataType dt = model.dataType(dataTypeName);
        if (dt instanceof DataType.Enumerated) return "";
        DataType.Array array = mapper.resolveArrayType(dataTypeName);
        if (array != null && !array.isDynamic()) {
            // Tableau a cardinalite fixe : reste inline (byte[] ou List<T>
            // recree a chaque fois), pas de type nomme genere pour lui.
            if (javaType.equals("byte[]")) {
                return " = new byte[" + array.fixedCardinality + "]";
            }
            return " = new java.util.ArrayList<>()";
        }
        if (dt instanceof DataType.VariantRecord) {
            // Pas d'initialisation fabriquee : construire une alternative
            // "par defaut" est fragile en toute generalite (cas reels
            // RPR-FOM v3.0/2025 qui la rendent impossible ou trompeuse) --
            // un type enumere en premiere alternative n'a pas de
            // constructeur "new" ; un variantRecordData en premiere
            // alternative (ex. SensorTypeVariantStruct ->
            // MinefieldSensorTypeVariantStruct) a un constructeur PRIVE
            // (cf. generateVariantClass) ; et au-dela de la compilation,
            // fabriquer une valeur "0" pour une alternative que
            // l'utilisateur n'a jamais choisie risquerait de laisser
            // passer silencieusement une donnee par defaut non voulue.
            // Le champ reste a sa valeur par defaut Java (null) ; le code
            // appelant doit explicitement choisir une alternative via
            // une des methodes "ofXxx" avant d'encoder.
            return "";
        }
        // FixedRecord / VariantRecord deja traite plus haut / tableau dynamique nomme
        return " = new " + NameUtils.sanitizeIdentifier(dataTypeName) + "()";
    }


    // ====================================================================
    // HLAAlignment.java
    // ====================================================================

    public String generateHLAAlignment() {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(runtimePackageName).append(";\n\n");
        sb.append("import java.util.HashMap;\nimport java.util.Map;\n\n");
        sb.append("import static ").append(packageName).append(".HLATypes.*;\n");
        sb.append(userTypeImports(runtimePackageName, null));
        sb.append("\n/** HLAAlignment.java -- GENERE depuis le(s) fichier(s) FOM. */\n");
        sb.append("public final class HLAAlignment {\n\n    private HLAAlignment() {\n    }\n\n");
        sb.append("    private static final Map<Class<?>, Integer> OCTET_BOUNDARY = new HashMap<>();\n\n");
        sb.append("    static {\n");
        for (DataType dt : mapper.topologicalOrder()) {
            if (dt instanceof DataType.Array) continue;
            int boundary = mapper.octetBoundaryOf(dt.name);
            if (boundary > 1) {
                sb.append("        register(").append(NameUtils.sanitizeIdentifier(dt.name)).append(".class, ")
                  .append(boundary).append(");\n");
            }
        }
        sb.append("    }\n\n");
        sb.append("    public static void register(Class<?> type, int octetBoundary) {\n")
          .append("        OCTET_BOUNDARY.put(type, octetBoundary);\n    }\n\n");
        sb.append("    public static int octetBoundary(Class<?> type) {\n")
          .append("        return OCTET_BOUNDARY.getOrDefault(type, 1);\n    }\n}\n");
        return sb.toString();
    }

    // ====================================================================
    // HLAEncoder.java / HLADecoder.java
    // ====================================================================

    public String generateHLAEncoder() {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(runtimePackageName).append(";\n\n");
        sb.append("import java.io.ByteArrayOutputStream;\nimport java.util.HashMap;\nimport java.util.List;\nimport java.util.Map;\n\n");
        sb.append("import static ").append(packageName).append(".HLATypes.*;\n");
        sb.append(userTypeImports(runtimePackageName, null));
        sb.append("\n/** HLAEncoder.java -- GENERE depuis le(s) fichier(s) FOM. */\n");
        sb.append("public final class HLAEncoder {\n\n    private HLAEncoder() {\n    }\n\n");
        sb.append("    private static final Map<Class<?>, HLAEncoderDetail<?>> REGISTRY = new HashMap<>();\n\n");
        sb.append("    public static <T> void register(Class<T> type, HLAEncoderDetail<T> detail) {\n")
          .append("        REGISTRY.put(type, detail);\n    }\n\n");
        sb.append("    @SuppressWarnings(\"unchecked\")\n")
          .append("    private static <T> HLAEncoderDetail<T> lookup(Class<T> type) {\n")
          .append("        HLAEncoderDetail<T> detail = (HLAEncoderDetail<T>) REGISTRY.get(type);\n")
          .append("        if (detail == null) {\n")
          .append("            throw new IllegalStateException(\"Aucun HLAEncoderDetail specialise pour ce type RPR-FOM: \" + type.getName());\n")
          .append("        }\n        return detail;\n    }\n\n");
        sb.append("    public static <T> byte[] encode(Class<T> type, T value) {\n")
          .append("        ByteArrayOutputStream buffer = new ByteArrayOutputStream();\n")
          .append("        encodeInto(buffer, type, value);\n        return buffer.toByteArray();\n    }\n\n");
        sb.append("    public static <T> void encodeInto(ByteArrayOutputStream buffer, Class<T> type, T value) {\n")
          .append("        lookup(type).encodeInto(buffer, value);\n    }\n\n");
        sb.append("    public static <E> byte[] encodeList(List<E> elements, Class<E> elementType) {\n")
          .append("        ByteArrayOutputStream buffer = new ByteArrayOutputStream();\n")
          .append("        encodeListInto(buffer, elements, elementType);\n        return buffer.toByteArray();\n    }\n\n");
        sb.append("    public static <E> void encodeListInto(ByteArrayOutputStream buffer, List<E> elements, Class<E> elementType) {\n")
          .append("        HLAEncoding.appendUnsignedInteger32BE(buffer, elements.size());\n")
          .append("        int boundary = HLAAlignment.octetBoundary(elementType);\n")
          .append("        for (E element : elements) {\n")
          .append("            HLAEncoding.appendPadding(buffer, boundary);\n")
          .append("            encodeInto(buffer, elementType, element);\n        }\n    }\n\n");
        sb.append("    /** Miroir de encodeListInto, sans compteur (RPRlengthlessArray). */\n");
        sb.append("    public static <E> void encodeListIntoLengthless(ByteArrayOutputStream buffer, List<E> elements, Class<E> elementType) {\n")
          .append("        int boundary = HLAAlignment.octetBoundary(elementType);\n")
          .append("        for (E element : elements) {\n")
          .append("            HLAEncoding.appendPadding(buffer, boundary);\n")
          .append("            encodeInto(buffer, elementType, element);\n        }\n    }\n\n");
        sb.append("    /** Sequence d'elements suivie d'un octet 0x00 terminateur (RPRnullTerminatedArray, ex. RTIobjectId). */\n");
        sb.append("    public static <E> void encodeListIntoNullTerminated(ByteArrayOutputStream buffer, List<E> elements, Class<E> elementType) {\n")
          .append("        for (E element : elements) {\n")
          .append("            encodeInto(buffer, elementType, element);\n        }\n")
          .append("        buffer.write(0);\n    }\n\n");
        sb.append("    /** Tableau a cardinalite FIXE (HLAfixedArray) : identique a encodeListIntoLengthless (pas de compteur), alias documente pour la lisibilite du code genere. */\n");
        sb.append("    public static <E> void encodeListIntoFixed(ByteArrayOutputStream buffer, List<E> elements, Class<E> elementType) {\n")
          .append("        encodeListIntoLengthless(buffer, elements, elementType);\n    }\n\n");

        sb.append("    static {\n");
        java.util.Set<String> emittedEnc = new java.util.LinkedHashSet<>();
        for (TypeMapper.BasicRepr basic : TypeMapper.allBasicReprs()) {
            if (!emittedEnc.add(basic.attrJavaClass)) continue;
            sb.append("        register(").append(basic.attrJavaClass).append(".class, (b, v) -> HLAEncoding.")
              .append(basic.encodeFn).append("(b, v").append(basic.attrJavaAccessor).append("));\n");
        }
        for (DataType dt : mapper.topologicalOrder()) {
            if (userTypes.contains(dt.name)) {
                String cls = NameUtils.sanitizeIdentifier(dt.name);
                sb.append("        register(").append(cls).append(".class, ").append(cls).append("::encodeInto); // type a responsabilite du developpeur\n");
                continue;
            }
            if (dt instanceof DataType.Enumerated e) {
                TypeMapper.BasicRepr underlying = mapper.resolveEnumUnderlying(e);
                String encodeFn = underlying != null ? underlying.encodeFn : "appendInteger32BE";
                String cast = javaEnumEncodeCast(encodeFn);
                sb.append("        register(").append(NameUtils.sanitizeIdentifier(e.name)).append(".class, (b, v) -> HLAEncoding.")
                  .append(encodeFn).append("(b, ").append(cast).append("v.code));\n");
            } else if (dt instanceof DataType.FixedRecord r) {
                sb.append(generateStructEncoderRegistration(r.name, toFields(r)));
            } else if (dt instanceof DataType.VariantRecord v) {
                sb.append(generateVariantEncoderRegistration(v));
            } else if (dt instanceof DataType.Array array && isGeneratableArray(array)) {
                sb.append(generateArrayEncoderRegistration(array));
            }
        }
        sb.append("    }\n}\n");
        return sb.toString();
    }

    private String generateArrayEncoderRegistration(DataType.Array array) {
        String name = NameUtils.sanitizeIdentifier(array.name);
        String elementClass = mapper.javaAttributeClassName(array.elementDataTypeName);
        // Tableau fixe (HLAfixedArray) : jamais de compteur sur le fil,
        // quel que soit array.encoding -- priorite sur le switch ci-dessous
        // qui ne distingue que les encodages DYNAMIQUES entre eux.
        String helper = !array.isDynamic() ? "encodeListIntoFixed" : switch (array.encoding) {
            case RPR_LENGTHLESS_ARRAY -> "encodeListIntoLengthless";
            case RPR_NULL_TERMINATED_ARRAY -> "encodeListIntoNullTerminated";
            default -> "encodeListInto";
        };
        StringBuilder sb = new StringBuilder();
        sb.append("        register(").append(name).append(".class, (b, v) -> ")
          .append(helper).append("(b, v.value, ").append(elementClass).append(".class));\n");
        return sb.toString();
    }

    /** Prefixe de cast Java pour une valeur long vers le type primitif attendu par encodeFn. Reutilise pour les enums ET le discriminant d'un variantRecordData. */
    private String javaEnumEncodeCast(String encodeFn) {
        return switch (encodeFn) {
            case "appendOctet", "appendUnsignedInteger16BE", "appendUnsignedInteger16LE", "appendInteger32BE", "appendInteger32LE" -> "(int) ";
            case "appendInteger16BE", "appendInteger16LE" -> "(short) ";
            default -> ""; // appendUnsignedInteger32BE/LE, appendInteger64BE/LE, appendUnsignedInteger64BE/LE prennent deja un long
        };
    }

    private String generateVariantEncoderRegistration(DataType.VariantRecord v) {
        String name = NameUtils.sanitizeIdentifier(v.name);
        TypeMapper.BasicRepr discUnderlying = mapper.resolveDiscriminantUnderlying(v);
        if (discUnderlying == null) {
            throw new IllegalStateException("Discriminant non resolu pour le variant '" + v.name + "'");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("        register(").append(name).append(".class, (b, v) -> {\n")
          .append("            switch (v.alternativeIndex()) {\n");
        for (int i = 0; i < v.alternatives.size(); i++) {
            DataType.VariantRecord.Alternative alt = v.alternatives.get(i);
            long discVal = mapper.discriminantValueOf(v, alt);
            String altClass = mapper.javaAttributeClassName(alt.dataTypeName);
            String methodSuffix = NameUtils.capitalize(alt.fieldName);
            String cast = javaEnumEncodeCast(discUnderlying.encodeFn);
            sb.append("                case ").append(i).append(":\n")
              .append("                    HLAEncoding.").append(discUnderlying.encodeFn).append("(b, ")
              .append(cast).append(discVal).append(discVal > Integer.MAX_VALUE || discVal < Integer.MIN_VALUE ? "L" : "").append(");\n")
              .append("                    HLAEncoding.appendPadding(b, ").append(mapper.octetBoundaryOf(alt.dataTypeName)).append(");\n")
              .append("                    encodeInto(b, ").append(altClass).append(".class, v.as").append(methodSuffix).append("());\n")
              .append("                    break;\n");
        }
        sb.append("                default:\n")
          .append("                    throw new IllegalStateException(\"").append(name).append(" : alternative active inconnue\");\n")
          .append("            }\n        });\n");
        return sb.toString();
    }


    private String generateStructEncoderRegistration(String name, List<FieldSpec> fields) {
        String javaName = NameUtils.sanitizeIdentifier(name);
        StringBuilder sb = new StringBuilder();
        sb.append("        register(").append(javaName).append(".class, (b, v) -> {\n");
        for (FieldSpec f : fields) {
            emitEncodeField(sb, "v." + NameUtils.toFieldName(f.name), f.dataTypeName, "            ");
        }
        sb.append("        });\n");
        return sb.toString();
    }

    private void emitEncodeField(StringBuilder sb, String accessExpr, String dataTypeName, String indent) {
        if (mapper.isPaddingMarker(dataTypeName)) {
            sb.append(indent).append("HLAEncoding.appendPadding(b, ").append(mapper.paddingMarkerBoundary(dataTypeName)).append(");\n");
            return;
        }
        TypeMapper.BasicRepr basic = mapper.resolveBasicRepr(dataTypeName);
        if (basic != null) {
            sb.append(indent).append("HLAEncoding.").append(basic.encodeFn).append("(b, ").append(accessExpr).append(");\n");
            return;
        }
        DataType.Array array = mapper.resolveArrayType(dataTypeName);
        if (array != null) {
            if (array.isDynamic()) {
                // Tableau dynamique nomme : type COMPOSE a part entiere
                // desormais (cf. TypeMapper), avec son propre encodeur
                // enregistre (generateArrayEncoderRegistration) -- meme
                // traitement que n'importe quel autre type compose, pas de
                // List<T> direct ici.
                sb.append(indent).append("HLAEncoding.appendPadding(b, ").append(mapper.octetBoundaryOf(dataTypeName)).append(");\n");
                sb.append(indent).append(encPrefix).append("encodeInto(b, ").append(mapper.javaAttributeClassName(dataTypeName)).append(".class, ").append(accessExpr).append(");\n");
            } else if (isOctetArray(array)) {
                sb.append(indent).append("for (byte _elem : ").append(accessExpr).append(") {\n")
                  .append(indent).append("    HLAEncoding.appendOctet(b, _elem);\n")
                  .append(indent).append("}\n");
            } else {
                sb.append(indent).append("for (var _elem : ").append(accessExpr).append(") {\n");
                emitEncodeField(sb, "_elem", array.elementDataTypeName, indent + "    ");
                sb.append(indent).append("}\n");
            }
            return;
        }
        DataType dt = model.dataType(dataTypeName);
        sb.append(indent).append("HLAEncoding.appendPadding(b, ").append(mapper.octetBoundaryOf(dataTypeName)).append(");\n");
        sb.append(indent).append(encPrefix).append("encodeInto(b, ").append(NameUtils.sanitizeIdentifier(dt.name))
          .append(".class, ").append(accessExpr).append(");\n");
    }

    private boolean isOctetArray(DataType.Array array) {
        TypeMapper.BasicRepr elem = mapper.resolveBasicRepr(array.elementDataTypeName);
        return elem != null && elem.representation.equals("HLAoctet");
    }

    public String generateHLADecoder() {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(runtimePackageName).append(";\n\n");
        sb.append("import java.util.ArrayList;\nimport java.util.HashMap;\nimport java.util.List;\nimport java.util.Map;\n\n");
        sb.append("import static ").append(packageName).append(".HLATypes.*;\n");
        sb.append(userTypeImports(runtimePackageName, null));
        sb.append("\n/** HLADecoder.java -- GENERE depuis le(s) fichier(s) FOM. */\n");
        sb.append("public final class HLADecoder {\n\n    private HLADecoder() {\n    }\n\n");
        sb.append("    private static final Map<Class<?>, HLADecoderDetail<?>> REGISTRY = new HashMap<>();\n\n");
        sb.append("    public static <T> void register(Class<T> type, HLADecoderDetail<T> detail) {\n")
          .append("        REGISTRY.put(type, detail);\n    }\n\n");
        sb.append("    @SuppressWarnings(\"unchecked\")\n")
          .append("    private static <T> HLADecoderDetail<T> lookup(Class<T> type) {\n")
          .append("        HLADecoderDetail<T> detail = (HLADecoderDetail<T>) REGISTRY.get(type);\n")
          .append("        if (detail == null) {\n")
          .append("            throw new IllegalStateException(\"Aucun HLADecoderDetail specialise pour ce type RPR-FOM: \" + type.getName());\n")
          .append("        }\n        return detail;\n    }\n\n");
        sb.append("    public static <T> T decode(Class<T> type, byte[] bytes) {\n")
          .append("        HLADecoding.Offset offset = new HLADecoding.Offset();\n")
          .append("        return decodeAt(bytes, offset, type);\n    }\n\n");
        sb.append("    public static <T> T decodeAt(byte[] bytes, HLADecoding.Offset offset, Class<T> type) {\n")
          .append("        return lookup(type).decodeAt(bytes, offset);\n    }\n\n");
        sb.append("    public static <E> List<E> decodeList(byte[] bytes, Class<E> elementType) {\n")
          .append("        HLADecoding.Offset offset = new HLADecoding.Offset();\n")
          .append("        return decodeListAt(bytes, offset, elementType);\n    }\n\n");
        sb.append("    public static <E> List<E> decodeListAt(byte[] bytes, HLADecoding.Offset offset, Class<E> elementType) {\n")
          .append("        long count = HLADecoding.readUnsignedInteger32BE(bytes, offset);\n")
          .append("        List<E> result = new ArrayList<>();\n")
          .append("        int boundary = HLAAlignment.octetBoundary(elementType);\n")
          .append("        for (long i = 0; i < count; ++i) {\n")
          .append("            HLADecoding.skipPadding(bytes, offset, boundary);\n")
          .append("            result.add(decodeAt(bytes, offset, elementType));\n        }\n")
          .append("        return result;\n    }\n\n");
        sb.append("    /** Miroir de decodeListAt, sans compteur : consomme jusqu'a epuisement du buffer (RPRlengthlessArray). */\n");
        sb.append("    public static <E> List<E> decodeListAtLengthless(byte[] bytes, HLADecoding.Offset offset, Class<E> elementType) {\n")
          .append("        List<E> result = new ArrayList<>();\n")
          .append("        int boundary = HLAAlignment.octetBoundary(elementType);\n")
          .append("        while (offset.value < bytes.length) {\n")
          .append("            HLADecoding.skipPadding(bytes, offset, boundary);\n")
          .append("            if (offset.value >= bytes.length) break;\n")
          .append("            result.add(decodeAt(bytes, offset, elementType));\n        }\n")
          .append("        return result;\n    }\n\n");
        sb.append("    /** Sequence d'elements jusqu'a un octet 0x00 terminateur, consomme (RPRnullTerminatedArray, ex. RTIobjectId). Element d'1 octet requis. */\n");
        sb.append("    public static <E> List<E> decodeListAtNullTerminated(byte[] bytes, HLADecoding.Offset offset, Class<E> elementType) {\n")
          .append("        List<E> result = new ArrayList<>();\n")
          .append("        while (offset.value < bytes.length && bytes[offset.value] != 0) {\n")
          .append("            result.add(decodeAt(bytes, offset, elementType));\n        }\n")
          .append("        if (offset.value < bytes.length) {\n")
          .append("            offset.value++; // consomme le terminateur nul\n")
          .append("        }\n")
          .append("        return result;\n    }\n\n");
        sb.append("    /** Tableau a cardinalite FIXE (HLAfixedArray) : lit exactement 'count' elements, pas de compteur sur le fil (contrairement a decodeListAt). */\n");
        sb.append("    public static <E> List<E> decodeListAtFixed(byte[] bytes, HLADecoding.Offset offset, Class<E> elementType, int count) {\n")
          .append("        List<E> result = new ArrayList<>();\n")
          .append("        int boundary = HLAAlignment.octetBoundary(elementType);\n")
          .append("        for (int i = 0; i < count; ++i) {\n")
          .append("            HLADecoding.skipPadding(bytes, offset, boundary);\n")
          .append("            result.add(decodeAt(bytes, offset, elementType));\n        }\n")
          .append("        return result;\n    }\n\n");

        sb.append("    static {\n");
        java.util.Set<String> emittedDec = new java.util.LinkedHashSet<>();
        for (TypeMapper.BasicRepr basic : TypeMapper.allBasicReprs()) {
            if (!emittedDec.add(basic.attrJavaClass)) continue;
            if (basic.attrJavaAccessor.isEmpty()) {
                sb.append("        register(").append(basic.attrJavaClass).append(".class, HLADecoding::")
                  .append(basic.decodeFn).append(");\n");
            } else {
                sb.append("        register(").append(basic.attrJavaClass).append(".class, (bytes, offset) -> new ")
                  .append(basic.attrJavaClass).append("(HLADecoding.").append(basic.decodeFn).append("(bytes, offset)));\n");
            }
        }
        for (DataType dt : mapper.topologicalOrder()) {
            if (userTypes.contains(dt.name)) {
                String cls = NameUtils.sanitizeIdentifier(dt.name);
                sb.append("        register(").append(cls).append(".class, ").append(cls).append("::decodeAt); // type a responsabilite du developpeur\n");
                continue;
            }
            if (dt instanceof DataType.Enumerated e) {
                TypeMapper.BasicRepr underlying = mapper.resolveEnumUnderlying(e);
                String decodeFn = underlying != null ? underlying.decodeFn : "readInteger32BE";
                String name = NameUtils.sanitizeIdentifier(e.name);
                sb.append("        register(").append(name).append(".class, (bytes, offset) -> ")
                  .append(name).append(".fromCode(HLADecoding.").append(decodeFn).append("(bytes, offset)));\n");
            } else if (dt instanceof DataType.FixedRecord r) {
                sb.append(generateStructDecoderRegistration(r.name, toFields(r)));
            } else if (dt instanceof DataType.VariantRecord v) {
                sb.append(generateVariantDecoderRegistration(v));
            } else if (dt instanceof DataType.Array array && isGeneratableArray(array)) {
                sb.append(generateArrayDecoderRegistration(array));
            }
        }
        sb.append("    }\n}\n");
        return sb.toString();
    }

    private String generateArrayDecoderRegistration(DataType.Array array) {
        String name = NameUtils.sanitizeIdentifier(array.name);
        String elementClass = mapper.javaAttributeClassName(array.elementDataTypeName);
        // Tableau fixe (HLAfixedArray) : decodeListAtFixed a une signature
        // differente (parametre 'count' supplementaire, pas de compteur
        // lu sur le fil) -- priorite sur le switch ci-dessous qui ne
        // distingue que les encodages DYNAMIQUES entre eux.
        StringBuilder sb = new StringBuilder();
        if (!array.isDynamic()) {
            sb.append("        register(").append(name).append(".class, (bytes, offset) -> {\n")
              .append("            ").append(name).append(" v = new ").append(name).append("();\n")
              .append("            v.value = decodeListAtFixed(bytes, offset, ").append(elementClass)
              .append(".class, ").append(array.fixedCardinality).append(");\n")
              .append("            return v;\n        });\n");
            return sb.toString();
        }
        String helper = switch (array.encoding) {
            case RPR_LENGTHLESS_ARRAY -> "decodeListAtLengthless";
            case RPR_NULL_TERMINATED_ARRAY -> "decodeListAtNullTerminated";
            default -> "decodeListAt";
        };
        sb.append("        register(").append(name).append(".class, (bytes, offset) -> {\n")
          .append("            ").append(name).append(" v = new ").append(name).append("();\n")
          .append("            v.value = ").append(helper).append("(bytes, offset, ").append(elementClass).append(".class);\n")
          .append("            return v;\n        });\n");
        return sb.toString();
    }

    private String generateVariantDecoderRegistration(DataType.VariantRecord v) {
        String name = NameUtils.sanitizeIdentifier(v.name);
        TypeMapper.BasicRepr discUnderlying = mapper.resolveDiscriminantUnderlying(v);
        if (discUnderlying == null) {
            throw new IllegalStateException("Discriminant non resolu pour le variant '" + v.name + "'");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("        register(").append(name).append(".class, (bytes, offset) -> {\n")
          .append("            long disc = HLADecoding.").append(discUnderlying.decodeFn).append("(bytes, offset);\n");
        for (int i = 0; i < v.alternatives.size(); i++) {
            DataType.VariantRecord.Alternative alt = v.alternatives.get(i);
            String altClass = mapper.javaAttributeClassName(alt.dataTypeName);
            String methodSuffix = NameUtils.capitalize(alt.fieldName);
            // Plusieurs valeurs de discriminant peuvent partager une meme
            // alternative (cf. javadoc de TypeMapper.discriminantValuesOf) :
            // une condition "disc == v" par valeur, reunies par "||".
            StringBuilder cond = new StringBuilder();
            for (long discVal : mapper.discriminantValuesOf(v, alt)) {
                if (cond.length() > 0) cond.append(" || ");
                cond.append("disc == ").append(discVal).append("L");
            }
            sb.append("            if (").append(cond).append(") {\n")
              .append("                HLADecoding.skipPadding(bytes, offset, ").append(mapper.octetBoundaryOf(alt.dataTypeName)).append(");\n")
              .append("                return ").append(name).append(".of").append(methodSuffix)
              .append("(decodeAt(bytes, offset, ").append(altClass).append(".class));\n")
              .append("            }\n");
        }
        sb.append("            throw new IllegalStateException(\"").append(name).append(" : discriminant inconnu: \" + disc);\n")
          .append("        });\n");
        return sb.toString();
    }

    private String generateStructDecoderRegistration(String name, List<FieldSpec> fields) {
        String javaName = NameUtils.sanitizeIdentifier(name);
        StringBuilder sb = new StringBuilder();
        sb.append("        register(").append(javaName).append(".class, (bytes, offset) -> {\n")
          .append("            ").append(javaName).append(" v = new ").append(javaName).append("();\n");
        for (FieldSpec f : fields) {
            emitDecodeField(sb, "v." + NameUtils.toFieldName(f.name), f.dataTypeName, "            ");
        }
        sb.append("            return v;\n        });\n");
        return sb.toString();
    }

    private void emitDecodeField(StringBuilder sb, String accessExpr, String dataTypeName, String indent) {
        if (mapper.isPaddingMarker(dataTypeName)) {
            sb.append(indent).append("HLADecoding.skipPadding(bytes, offset, ").append(mapper.paddingMarkerBoundary(dataTypeName)).append(");\n");
            return;
        }
        TypeMapper.BasicRepr basic = mapper.resolveBasicRepr(dataTypeName);
        if (basic != null) {
            sb.append(indent).append(accessExpr).append(" = HLADecoding.").append(basic.decodeFn).append("(bytes, offset);\n");
            return;
        }
        DataType.Array array = mapper.resolveArrayType(dataTypeName);
        if (array != null) {
            if (array.isDynamic()) {
                sb.append(indent).append("HLADecoding.skipPadding(bytes, offset, ").append(mapper.octetBoundaryOf(dataTypeName)).append(");\n");
                sb.append(indent).append(accessExpr).append(" = ").append(decPrefix).append("decodeAt(bytes, offset, ").append(mapper.javaAttributeClassName(dataTypeName)).append(".class);\n");
            } else if (isOctetArray(array)) {
                String idx = "_i" + Math.abs(accessExpr.hashCode() % 1000);
                sb.append(indent).append("for (int ").append(idx).append(" = 0; ").append(idx).append(" < ")
                  .append(accessExpr).append(".length; ++").append(idx).append(") {\n")
                  .append(indent).append("    ").append(accessExpr).append("[").append(idx)
                  .append("] = (byte) HLADecoding.readOctet(bytes, offset);\n")
                  .append(indent).append("}\n");
            } else {
                String idx = "_i" + Math.abs(accessExpr.hashCode() % 1000);
                sb.append(indent).append("for (int ").append(idx).append(" = 0; ").append(idx).append(" < ")
                  .append(array.fixedCardinality).append("; ++").append(idx).append(") {\n")
                  .append(indent).append("    ").append(accessExpr).append(".add(").append(decPrefix).append("decodeAt(bytes, offset, ")
                  .append(mapper.javaAttributeClassName(array.elementDataTypeName)).append(".class));\n")
                  .append(indent).append("}\n");
            }
            return;
        }
        DataType dt = model.dataType(dataTypeName);
        sb.append(indent).append("HLADecoding.skipPadding(bytes, offset, ").append(mapper.octetBoundaryOf(dataTypeName)).append(");\n");
        sb.append(indent).append(accessExpr).append(" = ").append(decPrefix).append("decodeAt(bytes, offset, ")
          .append(NameUtils.sanitizeIdentifier(dt.name)).append(".class);\n");
    }

    // ====================================================================
    // Type "a responsabilite du developpeur" : classe autonome (secours,
    // ou reference du test de conformite)
    // ====================================================================

    /**
     * Genere la classe Java AUTONOME d'un fixedRecordData : structure +
     * {@code encodeInto} + {@code decodeAt}, avec exactement le code que
     * generateHLAEncoder/generateHLADecoder auraient enregistre pour ce
     * type. C'est (1) la classe de SECOURS copiee a la destination quand le
     * developpeur ne fournit pas de fichier, et (2) le modele de depart que
     * le developpeur peut copier puis adapter, et (3), avec
     * {@code reference=true}, la reference du test de conformite.
     *
     * CONTRAT (a respecter par tout fichier fourni par le developpeur) :
     * <ul>
     *   <li>classe publique de premier niveau, dans le package racine,
     *       avec un constructeur sans argument public ;</li>
     *   <li>{@code public static void encodeInto(ByteArrayOutputStream, T)} :
     *       ecrit UNIQUEMENT les champs prevus par la FOM, dans l'ordre, en
     *       big-endian, avec le padding INTERNE au record. Le padding AVANT
     *       le record est ajoute par l'appelant ;</li>
     *   <li>{@code public static T decodeAt(byte[], HLADecoding.Offset)} :
     *       lit exactement ce que encodeInto ecrit et laisse l'offset apres
     *       le dernier champ ;</li>
     *   <li>la ligne {@code @rprfom-fom-fingerprint: <hash>} dans un
     *       commentaire : empreinte de la definition FOM pour laquelle le
     *       fichier a ete ecrit.</li>
     * </ul>
     * Le developpeur peut ajouter champs et methodes a volonte (ex. latitude/
     * longitude/altitude calculees) tant que ce qui passe sur le fil reste
     * conforme a la FOM.
     *
     * @param targetPackage package de la classe produite (le package racine, sauf pour la reference du test)
     * @param className     nom de la classe produite
     */
    public String generateUserTypeClass(String typeName, String fingerprint, String targetPackage,
                                        String className, boolean reference) {
        DataType dt = model.dataType(typeName);
        if (!(dt instanceof DataType.FixedRecord rec)) {
            throw new IllegalStateException("Type '" + typeName + "' : seul un fixedRecordData peut etre gere par le developpeur.");
        }
        List<FieldSpec> fields = toFields(rec);
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(targetPackage).append(";\n\n");
        sb.append("import java.io.ByteArrayOutputStream;\n\n");
        sb.append("import static ").append(packageName).append(".HLATypes.*;\n");
        sb.append("import ").append(runtimePackageName).append(".*;\n");
        sb.append(userTypeImports(targetPackage, typeName));
        sb.append("\n/**\n");
        if (reference) {
            sb.append(" * ").append(className).append(".java -- REFERENCE du test de conformite (code standard genere\n")
              .append(" * pour le type FOM ").append(typeName).append("). Ne pas utiliser en dehors du test.\n");
        } else {
            sb.append(" * ").append(className).append(".java -- type FOM \"").append(typeName)
              .append("\" a RESPONSABILITE DU DEVELOPPEUR.\n *\n")
              .append(" * Cette classe est la CLASSE DE SECOURS generee par rprfom-codegen : elle n'offre\n")
              .append(" * aucun service personnalise, elle encode/decode strictement selon la FOM. Elle est\n")
              .append(" * copiee dans les sources tant que vous ne fournissez pas votre propre fichier.\n *\n")
              .append(" * Pour la personnaliser : copiez ce fichier hors du dossier genere, modifiez-le (ajoutez\n")
              .append(" * vos champs/methodes), puis fournissez-le avec --user-types-java <dossier> ou\n")
              .append(" * --user-type-java ").append(typeName).append("=<fichier>. Regles a respecter :\n")
              .append(" *   - conservez encodeInto/decodeAt (signatures identiques) ;\n")
              .append(" *   - n'encodez/decodez QUE les champs de la FOM, dans l'ordre, en big-endian, avec le\n")
              .append(" *     padding interne au record (le padding avant le record est ajoute par l'appelant) ;\n")
              .append(" *   - gardez la ligne d'empreinte ci-dessous : si la FOM change, le generateur refuse\n")
              .append(" *     la copie perimee et vous demande de la remettre a jour.\n")
              .append(" * Le test de conformite genere verifie ensuite que votre encodage reste identique a celui-ci.\n *\n")
              .append(" * @rprfom-fom-fingerprint: ").append(fingerprint).append("\n");
        }
        sb.append(" */\n");
        sb.append("public final class ").append(className).append(" {\n\n");

        for (FieldSpec f : fields) {
            if (mapper.isPaddingMarker(f.dataTypeName)) continue;
            String javaType = mapper.javaFieldTypeName(f.dataTypeName);
            sb.append("    public ").append(javaType).append(" ").append(NameUtils.toFieldName(f.name))
              .append(defaultInit(f.dataTypeName, javaType)).append(";\n");
        }
        sb.append("\n    public ").append(className).append("() {\n    }\n\n");

        String savedEnc = encPrefix;
        String savedDec = decPrefix;
        encPrefix = "HLAEncoder.";
        decPrefix = "HLADecoder.";
        try {
            sb.append("    /** Ecrit les champs de la FOM dans le buffer partage (padding interne inclus). */\n");
            sb.append("    public static void encodeInto(ByteArrayOutputStream b, ").append(className).append(" v) {\n");
            for (FieldSpec f : fields) {
                emitEncodeField(sb, "v." + NameUtils.toFieldName(f.name), f.dataTypeName, "        ");
            }
            sb.append("    }\n\n");
            sb.append("    /** Lit les champs de la FOM depuis le buffer partage, l'offset finit apres le dernier champ. */\n");
            sb.append("    public static ").append(className).append(" decodeAt(byte[] bytes, HLADecoding.Offset offset) {\n");
            sb.append("        ").append(className).append(" v = new ").append(className).append("();\n");
            for (FieldSpec f : fields) {
                emitDecodeField(sb, "v." + NameUtils.toFieldName(f.name), f.dataTypeName, "        ");
            }
            sb.append("        return v;\n    }\n}\n");
        } finally {
            encPrefix = savedEnc;
            decPrefix = savedDec;
        }
        return sb.toString();
    }

    // ====================================================================
    // Une classe par objectClass
    // ====================================================================

    public String generateObjectClass(ObjectClassNode node, List<String> pathFromRoot, ObjectClassNode parent) {
        String className = NameUtils.sanitizeIdentifier(node.name);
        boolean isRootClass = (parent == null);
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        if (isRootClass) {
            sb.append("import java.util.Collections;\nimport java.util.HashMap;\nimport java.util.Map;\n\n");
        }
        // Contrat PARTAGE (jar rprfom-runtime-api) : toutes les classes generees, quel
        // que soit leur package, implementent HLAObjectClass -- c'est ce qu'utilise une
        // couche RTI (HLAbstract...) sans reflexion. Imports explicites : prioritaires
        // sur les imports "*" ci-dessous en cas d'homonyme.
        sb.append("import rprfom.api.HLAObjectClass;\nimport rprfom.api.IHLAAttributeBase;\n\n");
        // NOTE : un tableau dynamique nomme du FOM est desormais un type
        // COMPOSE a part entiere (cf. TypeMapper), plus un List<Element>
        // direct -- aucun import de java.util.List n'est donc necessaire
        // ici (il vit dans HLATypes.java, a l'interieur du type genere).
        sb.append("import static ").append(packageName).append(".HLATypes.*;\n\n");
        // HLAAttribute, IHLAAttributeBase, et les representations de base
        // (HLAOctet, HLAUnsignedInteger32, ...) vivent dans le package
        // runtime, separe des classes generees a partir du FOM -- cf.
        // Javadoc de classe.
        sb.append("import ").append(runtimePackageName).append(".*;\n\n");
        sb.append("/**\n * ").append(className).append(".java -- GENERE depuis le(s) fichier(s) FOM.\n")
          .append(" * FOM: ").append(String.join(".", pathFromRoot)).append("\n */\n");
        sb.append("public class ").append(className);
        if (!isRootClass) sb.append(" extends ").append(NameUtils.sanitizeIdentifier(parent.name));
        else sb.append(" implements HLAObjectClass");
        sb.append(" {\n\n");
        sb.append("    public static final String HLA_OBJECT_CLASS_NAME = \"")
          .append(String.join(".", pathFromRoot)).append("\";\n\n");

        for (ObjectClassNode.AttributeDef a : node.ownAttributes) {
            sb.append("    public final HLAAttribute<").append(javaAttributeGenericType(a.dataTypeName)).append("> ")
              .append(NameUtils.toFieldName(a.name)).append(" = ").append(javaAttributeFactory(a.dataTypeName)).append(";\n");
        }

        if (isRootClass) {
            sb.append("\n    private final Map<String, IHLAAttributeBase> attributesByName = new HashMap<>();\n");
            sb.append("    private final Map<String, IHLAAttributeBase> attributesByNameView = Collections.unmodifiableMap(attributesByName);\n");
            sb.append("    private final Map<Long, IHLAAttributeBase> attributesByHandle = new HashMap<>();\n\n");
        }

        sb.append("\n    public ").append(className).append("() {\n");
        for (ObjectClassNode.AttributeDef a : node.ownAttributes) {
            sb.append("        registerAttribute(\"").append(a.name).append("\", ")
              .append(NameUtils.toFieldName(a.name)).append(");\n");
        }
        sb.append("    }\n");

        // HLAObjectClass : redefini dans CHAQUE classe (nom FOM de la classe la plus derivee).
        sb.append("\n    @Override\n    public String hlaObjectClassName() {\n")
          .append("        return HLA_OBJECT_CLASS_NAME;\n    }\n");

        if (isRootClass) {
            sb.append("\n    /** HLAObjectClass : attributs de l'instance par nom FOM, heritage compris (lecture seule). */\n")
              .append("    @Override\n    public final Map<String, IHLAAttributeBase> attributesByName() {\n")
              .append("        return attributesByNameView;\n    }\n");
            sb.append("\n    public void bindAttributeHandle(String attributeName, long handle) {\n")
              .append("        IHLAAttributeBase attr = attributesByName.get(attributeName);\n")
              .append("        if (attr != null) {\n            attributesByHandle.put(handle, attr);\n        }\n    }\n\n");
            sb.append("    public IHLAAttributeBase attribute(long handle) {\n")
              .append("        return attributesByHandle.get(handle);\n    }\n\n");
            sb.append("    public Map<Long, IHLAAttributeBase> attributesByHandle() {\n")
              .append("        return attributesByHandle;\n    }\n\n");
            sb.append("    protected void registerAttribute(String name, IHLAAttributeBase attribute) {\n")
              .append("        attributesByName.put(name, attribute);\n    }\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    /** Type generique Java de l'attribut (T dans HLAAttribute<T>). */
    /**
     * Type generique Java de l'attribut (T dans HLAAttribute<T>). Un
     * tableau dynamique nomme du FOM est desormais un type COMPOSE a
     * part entiere (cf. TypeMapper -- plus jamais un List<Element>
     * anonyme), donc javaAttributeClassName suffit pour tous les cas.
     */
    private String javaAttributeGenericType(String dataTypeName) {
        return mapper.javaAttributeClassName(dataTypeName);
    }

    /** Expression Java construisant le HLAAttribute<T>. */
    private String javaAttributeFactory(String dataTypeName) {
        return "new HLAAttribute<>(" + mapper.javaAttributeClassName(dataTypeName) + ".class)";
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
        sb.append("package ").append(packageName).append(";\n\n");
        if (isRootClass) {
            sb.append("import java.util.Collections;\nimport java.util.HashMap;\nimport java.util.Map;\n\n");
        }
        // Contrat PARTAGE (jar rprfom-runtime-api), cf. generateObjectClass.
        sb.append("import rprfom.api.HLAInteractionClass;\nimport rprfom.api.IHLAParameterBase;\n\n");
        sb.append("import static ").append(packageName).append(".HLATypes.*;\n\n");
        // HLAParameter, IHLAParameterBase et les representations de base
        // vivent dans le package runtime -- cf. Javadoc de classe.
        sb.append("import ").append(runtimePackageName).append(".*;\n\n");
        sb.append("/**\n * ").append(className).append(".java -- GENERE depuis le(s) fichier(s) FOM.\n")
          .append(" * FOM (interaction): ").append(String.join(".", pathFromRoot)).append("\n */\n");
        sb.append("public class ").append(className);
        if (!isRootClass) sb.append(" extends ").append(NameUtils.sanitizeIdentifier(parent.name));
        else sb.append(" implements HLAInteractionClass");
        sb.append(" {\n\n");
        sb.append("    public static final String HLA_INTERACTION_CLASS_NAME = \"")
          .append(String.join(".", pathFromRoot)).append("\";\n\n");

        for (InteractionClassNode.ParameterDef p : node.ownParameters) {
            sb.append("    public final HLAParameter<").append(javaParameterGenericType(p.dataTypeName)).append("> ")
              .append(NameUtils.toFieldName(p.name)).append(" = ").append(javaParameterFactory(p.dataTypeName)).append(";\n");
        }

        if (isRootClass) {
            sb.append("\n    private final Map<String, IHLAParameterBase> parametersByName = new HashMap<>();\n");
            sb.append("    private final Map<String, IHLAParameterBase> parametersByNameView = Collections.unmodifiableMap(parametersByName);\n");
            sb.append("    private final Map<Long, IHLAParameterBase> parametersByHandle = new HashMap<>();\n\n");
        }

        sb.append("\n    public ").append(className).append("() {\n");
        for (InteractionClassNode.ParameterDef p : node.ownParameters) {
            sb.append("        registerParameter(\"").append(p.name).append("\", ")
              .append(NameUtils.toFieldName(p.name)).append(");\n");
        }
        sb.append("    }\n");

        // HLAInteractionClass : redefini dans CHAQUE classe (nom FOM de la classe la plus derivee).
        sb.append("\n    @Override\n    public String hlaInteractionClassName() {\n")
          .append("        return HLA_INTERACTION_CLASS_NAME;\n    }\n");

        if (isRootClass) {
            sb.append("\n    /** HLAInteractionClass : parametres de l'instance par nom FOM, heritage compris (lecture seule). */\n")
              .append("    @Override\n    public final Map<String, IHLAParameterBase> parametersByName() {\n")
              .append("        return parametersByNameView;\n    }\n");
            sb.append("\n    public void bindParameterHandle(String parameterName, long handle) {\n")
              .append("        IHLAParameterBase param = parametersByName.get(parameterName);\n")
              .append("        if (param != null) {\n            parametersByHandle.put(handle, param);\n        }\n    }\n\n");
            sb.append("    public IHLAParameterBase parameter(long handle) {\n")
              .append("        return parametersByHandle.get(handle);\n    }\n\n");
            sb.append("    public Map<Long, IHLAParameterBase> parametersByHandle() {\n")
              .append("        return parametersByHandle;\n    }\n\n");
            sb.append("    protected void registerParameter(String name, IHLAParameterBase parameter) {\n")
              .append("        parametersByName.put(name, parameter);\n    }\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    /** Type generique Java du parametre (T dans HLAParameter<T>). */
    /** Miroir de javaAttributeGenericType pour les parametres d'interaction. */
    private String javaParameterGenericType(String dataTypeName) {
        return mapper.javaAttributeClassName(dataTypeName);
    }

    /** Expression Java construisant le HLAParameter<T>. */
    private String javaParameterFactory(String dataTypeName) {
        return "new HLAParameter<>(" + mapper.javaAttributeClassName(dataTypeName) + ".class)";
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
