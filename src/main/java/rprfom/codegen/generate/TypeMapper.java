package rprfom.codegen.generate;

import rprfom.codegen.model.DataType;
import rprfom.codegen.model.FomModel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resout un nom de type FOM vers ses representations C++ et Java, et
 * fournit le tri topologique necessaire cote C++ (une specialisation de
 * template doit etre textuellement declaree avant d'etre utilisee par
 * une autre specialisation dans le meme header).
 *
 * POINT IMPORTANT (Java uniquement) : le registre HLAEncoder/HLADecoder
 * cote Java est indexe par Class<?> (effacement de type oblige, cf.
 * HLAAttribute.java). Cela signifie que deux representations HLA
 * distinctes ne doivent JAMAIS partager la meme classe Java boxee, sous
 * peine de collision dans le registre (une representation ecraserait
 * l'encodage de l'autre). Java n'ayant pas d'entiers non signes, les
 * representations "unsigned" ont besoin d'une classe Java DEDIEE
 * (HLAOctet, HLAUnsignedInteger16, HLAUnsignedInteger32 -- fournies par
 * le runtime statique, cf. RuntimeFiles) plutot que de reutiliser Short/
 * Integer/Long qui sont deja pris par les representations signees.
 * Cote C++, ce probleme n'existe pas : int16_t et uint16_t sont deux
 * types distincts, la specialisation de template les distingue nativement.
 */
public final class TypeMapper {

    public final FomModel model;

    public TypeMapper(FomModel model) {
        this.model = model;
        // HLAopaqueData / HLAhandle ne sont pas des representations a
        // largeur fixe : ce sont des blobs d'octets de longueur variable
        // (HLAvariableArray d'HLAoctet, cf. IEEE 1516.2). Plutot que de
        // dupliquer tout le mecanisme d'encodage/decodage des tableaux
        // pour ce cas particulier, on les enregistre comme de VRAIS
        // DataType.Array(HLAoctet, Dynamic) synthetiques : tout le code
        // generique de gestion des tableaux (deja ecrit, deja teste)
        // les prend alors en charge automatiquement, sans aucun cas
        // particulier supplementaire ailleurs dans le generateur.
        for (String opaqueTypeName : List.of("HLAopaqueData", "HLAhandle")) {
            if (!model.dataTypes.containsKey(opaqueTypeName)) {
                model.addDataType(new DataType.Array(opaqueTypeName, "HLAoctet", -1));
            }
        }
    }

    /**
     * Description d'une representation de base HLA et de ses mappings.
     *
     * IMPORTANT : deux notions de "type" distinctes.
     *   - field*   : type utilise pour un CHAMP de struct. Jamais de
     *     collision possible ici (resolu a la generation, pas via un
     *     registre a l'execution) : on peut donc reutiliser librement
     *     le meme type primitif pour, par exemple, HLAinteger16BE ET
     *     HLAinteger16LE -- seul le NOM DE FONCTION d'encodage/decodage
     *     differe, pas le type Java/C++ du champ.
     *   - attr*    : type utilise pour un ATTRIBUT (HLAAttribute<T>).
     *     LA doit etre unique par representation, car c'est ce type qui
     *     sert de cle dans le registre Java (Class<?>) et de parametre
     *     de specialisation de template C++. Quand le type "naturel"
     *     est deja pris par une autre representation (ex. int16_t deja
     *     utilise par HLAinteger16BE), un wrapper dedie est necessaire
     *     (ex. Integer16LE) -- attrCppAccessor/attrJavaAccessor valent
     *     alors ".value" (acces au champ interne du wrapper), sinon "".
     */
    public static final class BasicRepr {
        public final String representation;
        public final String fieldCppType;
        public final String fieldJavaType;
        public final String attrCppType;
        public final String attrCppAccessor;
        public final String attrJavaClass;
        public final String attrJavaAccessor;
        public final String encodeFn;
        public final String decodeFn;
        public final int octetBoundary;

        BasicRepr(String representation, String fieldCppType, String fieldJavaType,
                  String attrCppType, String attrCppAccessor,
                  String attrJavaClass, String attrJavaAccessor,
                  String encodeFn, String decodeFn, int octetBoundary) {
            this.representation = representation;
            this.fieldCppType = fieldCppType;
            this.fieldJavaType = fieldJavaType;
            this.attrCppType = attrCppType;
            this.attrCppAccessor = attrCppAccessor;
            this.attrJavaClass = attrJavaClass;
            this.attrJavaAccessor = attrJavaAccessor;
            this.encodeFn = encodeFn;
            this.decodeFn = decodeFn;
            this.octetBoundary = octetBoundary;
        }

        /** Retro-compatibilite : ancien nom utilise par endroits pour le type de champ C++. */
        public String cppAlias() {
            return fieldCppType;
        }
    }

    private static final List<BasicRepr> BASIC_REPRS = List.of(
            // representation                field(C++)   field(Java)  attr(C++)          accessor  attr(Java)               accessor   encodeFn                    decodeFn                    boundary
            new BasicRepr("HLAoctet",                 "Octet",     "int",     "Octet",           "",       "HLAOctet",              ".value",  "appendOctet",              "readOctet",              1),
            new BasicRepr("HLAunsignedInteger8",       "Octet",     "int",     "Octet",           "",       "HLAOctet",              ".value",  "appendOctet",              "readOctet",              1),
            // RPRunsignedInteger8BE : representation personnalisee de la RPR-FOM
            // (pas dans le coeur IEEE 1516.2), mais wire-identique a HLAoctet/
            // HLAunsignedInteger8 (1 octet non signe) -> reutilise le meme
            // encodage/type sans wrapper supplementaire.
            new BasicRepr("RPRunsignedInteger8BE",     "Octet",     "int",     "Octet",           "",       "HLAOctet",              ".value",  "appendOctet",              "readOctet",              1),
            new BasicRepr("HLAbyte",                   "Octet",     "int",     "Octet",           "",       "HLAOctet",              ".value",  "appendOctet",              "readOctet",              1),
            new BasicRepr("HLAASCIIchar",              "Octet",     "int",     "Octet",           "",       "HLAOctet",              ".value",  "appendOctet",              "readOctet",              1),

            new BasicRepr("HLAinteger16BE",            "Integer16", "short",   "Integer16",       "",       "Short",                 "",        "appendInteger16BE",        "readInteger16BE",        2),
            new BasicRepr("HLAinteger16LE",            "Integer16", "short",   "Integer16LE",     ".value", "HLAInteger16LE",        ".value",  "appendInteger16LE",        "readInteger16LE",        2),
            new BasicRepr("HLAunicodeChar",            "Integer16", "short",   "Integer16",       "",       "Short",                 "",        "appendInteger16BE",        "readInteger16BE",        2),

            new BasicRepr("HLAunsignedInteger16BE",    "UnsignedInteger16", "int",  "UnsignedInteger16",   "",       "HLAUnsignedInteger16",   ".value", "appendUnsignedInteger16BE", "readUnsignedInteger16BE", 2),
            // RPRunsignedInteger16BE : representation personnalisee RPR-FOM,
            // wire-identique a HLAunsignedInteger16BE -> meme encodage/type reutilise.
            new BasicRepr("RPRunsignedInteger16BE",    "UnsignedInteger16", "int",  "UnsignedInteger16",   "",       "HLAUnsignedInteger16",   ".value", "appendUnsignedInteger16BE", "readUnsignedInteger16BE", 2),
            new BasicRepr("HLAunsignedInteger16LE",    "UnsignedInteger16", "int",  "UnsignedInteger16LE", ".value", "HLAUnsignedInteger16LE", ".value", "appendUnsignedInteger16LE", "readUnsignedInteger16LE", 2),

            new BasicRepr("HLAinteger32BE",            "Integer32", "int",     "Integer32",       "",       "Integer",               "",        "appendInteger32BE",        "readInteger32BE",        4),
            new BasicRepr("HLAinteger32LE",            "Integer32", "int",     "Integer32LE",     ".value", "HLAInteger32LE",        ".value",  "appendInteger32LE",        "readInteger32LE",        4),

            new BasicRepr("HLAunsignedInteger32BE",    "UnsignedInteger32", "long", "UnsignedInteger32",   "",       "HLAUnsignedInteger32",   ".value", "appendUnsignedInteger32BE", "readUnsignedInteger32BE", 4),
            // RPRunsignedInteger32BE : representation personnalisee RPR-FOM,
            // wire-identique a HLAunsignedInteger32BE -> meme encodage/type reutilise.
            new BasicRepr("RPRunsignedInteger32BE",    "UnsignedInteger32", "long", "UnsignedInteger32",   "",       "HLAUnsignedInteger32",   ".value", "appendUnsignedInteger32BE", "readUnsignedInteger32BE", 4),
            new BasicRepr("HLAunsignedInteger32LE",    "UnsignedInteger32", "long", "UnsignedInteger32LE", ".value", "HLAUnsignedInteger32LE", ".value", "appendUnsignedInteger32LE", "readUnsignedInteger32LE", 4),

            new BasicRepr("HLAinteger64BE",            "Integer64", "long",    "Integer64",       "",       "Long",                  "",        "appendInteger64BE",        "readInteger64BE",        8),
            new BasicRepr("HLAinteger64LE",            "Integer64", "long",    "Integer64LE",     ".value", "HLAInteger64LE",        ".value",  "appendInteger64LE",        "readInteger64LE",        8),
            // HLAinteger64Time : simpleData standard IEEE 1516.2 (defini dans le
            // MIM, cf. HLAstandardMIM.xml), representation = HLAinteger64BE.
            // La RPR-FOM l'utilise (ex. RPRUserDefinedTag.Timestamp) SANS le
            // redeclarer via un <simpleData> local -- comme HLAboolean ou
            // HLAASCIIchar, c'est un nom du vocabulaire de base de la norme
            // que tout processeur OMT est suppose reconnaitre directement.
            // Wire-identique a HLAinteger64BE -> meme encodage/type reutilise.
            new BasicRepr("HLAinteger64Time",          "Integer64", "long",    "Integer64",       "",       "Long",                  "",        "appendInteger64BE",        "readInteger64BE",        8),

            new BasicRepr("HLAunsignedInteger64BE",    "UnsignedInteger64", "long", "UnsignedInteger64",   "",       "HLAUnsignedInteger64",   ".value", "appendUnsignedInteger64BE", "readUnsignedInteger64BE", 8),
            // RPRunsignedInteger64BE : representation personnalisee RPR-FOM,
            // wire-identique a HLAunsignedInteger64BE -> meme encodage/type reutilise.
            new BasicRepr("RPRunsignedInteger64BE",    "UnsignedInteger64", "long", "UnsignedInteger64",   "",       "HLAUnsignedInteger64",   ".value", "appendUnsignedInteger64BE", "readUnsignedInteger64BE", 8),
            new BasicRepr("HLAunsignedInteger64LE",    "UnsignedInteger64", "long", "UnsignedInteger64LE", ".value", "HLAUnsignedInteger64LE", ".value", "appendUnsignedInteger64LE", "readUnsignedInteger64LE", 8),

            new BasicRepr("HLAfloat32BE",              "Float32",   "float",   "Float32",         "",       "Float",                 "",        "appendFloat32BE",          "readFloat32BE",          4),
            new BasicRepr("HLAfloat32LE",              "Float32",   "float",   "Float32LE",       ".value", "HLAFloat32LE",          ".value",  "appendFloat32LE",          "readFloat32LE",          4),

            new BasicRepr("HLAfloat64BE",              "Float64",   "double",  "Float64",         "",       "Double",                "",        "appendFloat64BE",          "readFloat64BE",          8),
            new BasicRepr("HLAfloat64LE",              "Float64",   "double",  "Float64LE",       ".value", "HLAFloat64LE",          ".value",  "appendFloat64LE",          "readFloat64LE",          8),
            // HLAfloat64Time : meme cas que HLAinteger64Time ci-dessus, mais
            // representation = HLAfloat64BE (non confirme utilise dans les 15
            // fichiers RPR-FOM v3.0 actuels, ajoute par coherence/anticipation
            // avec le MIM standard IEEE 1516.2).
            new BasicRepr("HLAfloat64Time",            "Float64",   "double",  "Float64",         "",       "Double",                "",        "appendFloat64BE",          "readFloat64BE",          8),

            new BasicRepr("HLAboolean",                "bool",       "boolean", "bool",           "",       "Boolean",               "",        "appendBoolean",            "readBoolean",            4),

            new BasicRepr("HLAoctetPairBE",            "std::array<Octet, 2>", "byte[]",  "OctetPair",       ".value", "HLAOctetPair",          ".value",  "appendOctetPair",          "readOctetPair",          1),
            new BasicRepr("HLAoctetPairLE",            "std::array<Octet, 2>", "byte[]",  "OctetPair",       ".value", "HLAOctetPair",          ".value",  "appendOctetPair",          "readOctetPair",          1),

            new BasicRepr("HLAASCIIstring",            "std::string",     "String", "std::string",     "",  "HLAASCIIString",        ".value",  "appendASCIIString",        "readASCIIString",        4),
            new BasicRepr("HLAunicodeString",          "std::u16string",  "String", "std::u16string",  "",  "String",                "",        "appendUnicodeString",      "readUnicodeString",      4)
    );

    /**
     * HLAopaqueData / HLAhandle sont geres via l'injection de types
     * Array synthetiques dans le modele (cf. constructeur ci-dessus),
     * pas via cette table de representations scalaires.
     */

    public static BasicRepr basicRepr(String representationName) {
        for (BasicRepr r : BASIC_REPRS) {
            if (r.representation.equals(representationName)) return r;
        }
        return null;
    }

    public static List<BasicRepr> allBasicReprs() {
        return BASIC_REPRS;
    }

    /**
     * Resout un nom de type FOM (nom de simpleData, ou directement un nom
     * de representation de base) vers sa BasicRepr, en suivant au besoin
     * un niveau d'indirection simpleData -> representation. Retourne null
     * si le nom designe un type compose (fixedRecord/enumerated/array/
     * variantRecord) ou est totalement inconnu.
     */
    public BasicRepr resolveBasicRepr(String dataTypeName) {
        if (dataTypeName == null) return null;
        BasicRepr direct = basicRepr(dataTypeName);
        if (direct != null) return direct;
        DataType dt = model.dataType(dataTypeName);
        if (dt instanceof DataType.Simple simple) {
            return basicRepr(simple.representation);
        }
        return null;
    }

    /**
     * Resout la representation entiere sous-jacente d'un type enumere
     * (le champ `representation` d'un enumeratedData designe soit une
     * representation de base directement, soit un simpleData qui pointe
     * lui-meme vers une representation de base).
     */
    public BasicRepr resolveEnumUnderlying(DataType.Enumerated enumerated) {
        BasicRepr direct = basicRepr(enumerated.representation);
        if (direct != null) return direct;
        DataType dt = model.dataType(enumerated.representation);
        if (dt instanceof DataType.Simple simple) {
            return basicRepr(simple.representation);
        }
        return null;
    }

    /**
     * Octet boundary (alignement) d'un type FOM compose, pour l'encodage
     * des tableaux et le positionnement d'un type compose comme champ.
     * Calcule recursivement comme le plus grand octet boundary parmi les
     * champs (ou alternatives, pour un variantRecord).
     */
    public int octetBoundaryOf(String dataTypeName) {
        BasicRepr basic = resolveBasicRepr(dataTypeName);
        if (basic != null) return basic.octetBoundary;

        DataType dt = model.dataType(dataTypeName);
        if (dt instanceof DataType.Enumerated enumerated) {
            BasicRepr underlying = resolveEnumUnderlying(enumerated);
            return underlying != null ? underlying.octetBoundary : 1;
        }
        if (dt instanceof DataType.FixedRecord rec) {
            int max = 1;
            for (DataType.FixedRecord.Field f : rec.fields) {
                max = Math.max(max, octetBoundaryOf(f.dataTypeName));
            }
            return max;
        }
        if (dt instanceof DataType.VariantRecord variant) {
            int max = octetBoundaryOf(variant.discriminantDataTypeName);
            for (DataType.VariantRecord.Alternative a : variant.alternatives) {
                max = Math.max(max, octetBoundaryOf(a.dataTypeName));
            }
            return max;
        }
        if (dt instanceof DataType.Array array) {
            // HLAvariableArray demarre par un compteur HLAinteger32BE : son
            // propre octet boundary est donc 4, quel que soit l'element.
            // RPRlengthlessArray n'a pas de compteur : son octet boundary
            // est celui de son PREMIER element ecrit, donc celui de l'element.
            if (array.encoding == DataType.ArrayEncoding.RPR_LENGTHLESS_ARRAY) {
                return octetBoundaryOf(array.elementDataTypeName);
            }
            return 4;
        }
        return 1;
    }

    /**
     * true si dataTypeName designe un des champs de bourrage purs de la
     * RPR-FOM (RPRpaddingTo32Array / RPRpaddingTo64Array) : ce ne sont
     * PAS des tableaux a elements, mais un marqueur qui amene la
     * longueur de l'enregistrement courant a un multiple de 32/64 bits,
     * sans porter aucune valeur. Aucun membre de classe/struct n'est
     * genere pour un tel champ ; seul un appel de padding est insere a
     * sa position dans l'encodeur/decodeur.
     */
    public boolean isPaddingMarker(String dataTypeName) {
        DataType dt = model.dataType(dataTypeName);
        return dt instanceof DataType.Array array &&
                (array.encoding == DataType.ArrayEncoding.RPR_PADDING_32 ||
                 array.encoding == DataType.ArrayEncoding.RPR_PADDING_64);
    }

    /** 4 ou 8 (octets) selon RPRpaddingTo32Array/RPRpaddingTo64Array -- n'appeler que si isPaddingMarker(dataTypeName). */
    public int paddingMarkerBoundary(String dataTypeName) {
        DataType.Array array = (DataType.Array) model.dataType(dataTypeName);
        return array.encoding == DataType.ArrayEncoding.RPR_PADDING_64 ? 8 : 4;
    }

    /**
     * Tri topologique des types composes du modele (FixedRecord,
     * Enumerated, VariantRecord, Array), dependances avant dependants --
     * necessaire cote C++ ou une specialisation de template doit etre
     * textuellement visible avant d'etre utilisee. Les Simple ne sont
     * pas inclus (ce sont de purs alias, rien a generer pour eux).
     */
    public List<DataType> topologicalOrder() {
        List<DataType> composites = new ArrayList<>();
        for (DataType dt : model.dataTypes.values()) {
            if (!(dt instanceof DataType.Simple)) {
                composites.add(dt);
            }
        }

        List<DataType> ordered = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        Set<String> visiting = new LinkedHashSet<>();

        for (DataType dt : composites) {
            visit(dt, visited, visiting, ordered);
        }
        return ordered;
    }

    private void visit(DataType dt, Set<String> visited, Set<String> visiting, List<DataType> ordered) {
        if (visited.contains(dt.name)) return;
        if (visiting.contains(dt.name)) {
            // Dependance circulaire (ne devrait pas arriver dans un FOM
            // valide) : on casse le cycle plutot que de planter.
            return;
        }
        visiting.add(dt.name);
        for (String dep : dt.dependsOn()) {
            DataType depType = model.dataType(dep);
            if (depType != null && !(depType instanceof DataType.Simple)) {
                visit(depType, visited, visiting, ordered);
            }
        }
        visiting.remove(dt.name);
        visited.add(dt.name);
        ordered.add(dt);
    }

    // ====================================================================
    // Resolution de type concret (utilise pour les champs de struct, les
    // attributs, et les elements de tableau)
    // ====================================================================

    /**
     * Nom du type C++ pour un CHAMP de struct (reutilisable librement,
     * jamais de collision possible a ce niveau -- cf. javadoc de BasicRepr).
     * Pour un tableau dynamique, renvoie directement "std::vector<Element>"
     * (pas de type nomme genere). Pour un tableau fixe, "std::array<Element, N>".
     */
    /**
     * Resout la representation entiere sous-jacente du discriminant d'un
     * variantRecordData (peut etre une representation de base directe,
     * un simpleData, ou un enumeratedData).
     */
    public BasicRepr resolveDiscriminantUnderlying(DataType.VariantRecord variant) {
        BasicRepr direct = resolveBasicRepr(variant.discriminantDataTypeName);
        if (direct != null) return direct;
        DataType dt = model.dataType(variant.discriminantDataTypeName);
        if (dt instanceof DataType.Enumerated enumerated) {
            return resolveEnumUnderlying(enumerated);
        }
        return null;
    }

    /**
     * Valeur numerique CANONIQUE (la premiere de la liste, cf.
     * discriminantValuesOf) du discriminant correspondant a une
     * alternative donnee -- utilisee partout ou une seule valeur suffit
     * (encodeur : une valeur deja construite en memoire ne porte plus
     * l'information de LAQUELLE des valeurs synonymes du FOM l'a produite,
     * donc toute ecriture sur le fil re-encode avec la premiere valeur du
     * groupe ; cf. javadoc de discriminantValuesOf).
     */
    public long discriminantValueOf(DataType.VariantRecord variant, DataType.VariantRecord.Alternative alt) {
        return discriminantValuesOf(variant, alt).get(0);
    }

    /**
     * Valeurs numeriques du discriminant correspondant a une alternative
     * donnee. Si le discriminant est un enumeratedData, `alt.enumerator`
     * est normalement le NOM d'un enumerateur de ce type -- mais le schema
     * OMT officiel (IEEE1516-DIF-*.xsd, element "enumerator" d'une
     * alternative) documente ce champ comme pouvant lister "enumerators OR
     * enumerator ranges", c'est-a-dire PLUSIEURS noms separes par une
     * virgule designant tous la MEME alternative. Cas reel rencontre dans
     * la RPR-FOM v3.0/2025 (RPR-Communication_v3.0.xml,
     * RFModulationSystemParametersVariantStruct) :
     *   <enumerator>HAVE_QUICK_I, HAVE_QUICK_II, SATURN</enumerator>
     * -- les 3 valeurs partagent la structure BasicHAVEQUICKModulation.
     * Les plages numeriques (ex. "1-5") ne sont PAS implementees ici : non
     * rencontrees dans les 15 fichiers RPR-FOM v3.0 actuels (verifie), et
     * leur syntaxe exacte n'est pas normalisee par le schema -- a ajouter
     * si un FOM futur en a reellement besoin.
     *
     * Chaque jeton est resolu independamment (nom d'enumerateur si le
     * discriminant est un enumeratedData, sinon valeur numerique litterale).
     * La liste renvoyee n'est jamais vide.
     */
    public List<Long> discriminantValuesOf(DataType.VariantRecord variant, DataType.VariantRecord.Alternative alt) {
        DataType dt = model.dataType(variant.discriminantDataTypeName);
        List<Long> values = new ArrayList<>();
        for (String token : alt.enumerator.split(",")) {
            String name = token.trim();
            if (name.isEmpty()) continue;
            if (dt instanceof DataType.Enumerated enumerated) {
                Long resolved = null;
                for (DataType.Enumerated.Enumerator e : enumerated.enumerators) {
                    if (e.name.equals(name)) {
                        resolved = e.value;
                        break;
                    }
                }
                if (resolved == null) {
                    throw new IllegalStateException(
                            "Enumerateur '" + name + "' introuvable dans l'enumeratedData '" + enumerated.name + "'");
                }
                values.add(resolved);
            } else {
                try {
                    values.add(Long.parseLong(name));
                } catch (NumberFormatException e) {
                    throw new IllegalStateException(
                            "Valeur de discriminant non numerique et non resolue via un enumeratedData: '" +
                                    name + "' (variant '" + variant.name + "')");
                }
            }
        }
        if (values.isEmpty()) {
            throw new IllegalStateException(
                    "Discriminant vide pour une alternative de '" + variant.name + "'");
        }
        return values;
    }

    /**
     * RPRnullTerminatedArray n'est implemente que pour des elements d'1
     * octet (cas reel rencontre : RTIobjectId, element HLAASCIIchar) --
     * le terminateur est un simple octet 0x00. Leve une erreur claire a
     * la generation plutot que de produire un encodage incorrect si un
     * FOM l'utilise un jour avec un element plus large.
     */
    public void validateNullTerminatedArray(DataType.Array array) {
        if (array.encoding == DataType.ArrayEncoding.RPR_NULL_TERMINATED_ARRAY
                && octetBoundaryOf(array.elementDataTypeName) != 1) {
            throw new IllegalStateException(
                    "Le tableau '" + array.name + "' utilise RPRnullTerminatedArray avec un element de " +
                            octetBoundaryOf(array.elementDataTypeName) + " octet(s) d'alignement : seuls les " +
                            "elements d'1 octet (ex. HLAASCIIchar) sont supportes pour cet encodage.");
        }
    }

    /**
     * Resout dataTypeName jusqu'a son DataType.Array sous-jacent, en
     * suivant au besoin l'indirection simpleData -> representation
     * (necessaire pour HLAopaqueData/HLAhandle, qui sont des simpleData
     * dont la representation pointe vers le tableau synthetique injecte
     * dans le constructeur). Renvoie null si ce n'est pas un tableau.
     */
    public DataType.Array resolveArrayType(String dataTypeName) {
        DataType dt = model.dataType(dataTypeName);
        if (dt instanceof DataType.Array array) return array;
        if (dt instanceof DataType.Simple simple) {
            DataType redirected = model.dataType(simple.representation);
            if (redirected instanceof DataType.Array array) return array;
        }
        return null;
    }

    public String cppTypeName(String dataTypeName) {
        BasicRepr basic = resolveBasicRepr(dataTypeName);
        if (basic != null) return basic.fieldCppType;

        DataType dt = model.dataType(dataTypeName);
        if (dt == null) {
            throw new IllegalStateException("Type FOM inconnu: " + dataTypeName);
        }
        if (dt instanceof DataType.Simple simple) {
            DataType redirected = model.dataType(simple.representation);
            if (redirected instanceof DataType.Array) {
                // HLAopaqueData / HLAhandle : redirige vers le tableau
                // dynamique d'Octet synthetique (cf. constructeur).
                return cppTypeName(simple.representation);
            }
            throw new IllegalStateException(
                    "Representation HLA non reconnue par le generateur: '" + simple.representation +
                            "' (type FOM '" + dataTypeName + "'). Ce n'est pas une representation de " +
                            "base standard IEEE 1516.2/1516-2025 -- verifiez l'orthographe ou ajoutez-la " +
                            "a TypeMapper.BASIC_REPRS si c'est une extension legitime de ton FOM.");
        }
        if (dt instanceof DataType.Array array) {
            if (array.isDynamic()) {
                // Tableau dynamique : type COMPOSE genere a part (pas
                // d'inline std::vector<T> anonyme), pour eviter toute
                // collision de specialisation de template quand le meme
                // type d'element est utilise avec des encodages differents
                // ailleurs dans le FOM (HLAvariableArray vs
                // RPRlengthlessArray) -- constate en pratique dans la
                // RPR-FOM reelle (ex. Float32, Octet, VariableDatumStruct).
                return NameUtils.sanitizeIdentifier(array.name);
            }
            String elementType = cppTypeName(array.elementDataTypeName);
            return "std::array<" + elementType + ", " + array.fixedCardinality + ">";
        }
        // Enumerated / FixedRecord / VariantRecord -> type genere du meme nom.
        return NameUtils.sanitizeIdentifier(dt.name);
    }

    /**
     * Nom du type C++ pour un ATTRIBUT (HLAAttribute&lt;T&gt;) : DOIT etre
     * unique par representation (sert de parametre de specialisation de
     * template pour HLAEncoderDetail/HLADecoderDetail), d'ou un wrapper
     * dedie pour les representations qui partageraient sinon le meme
     * type primitif qu'une autre (LE, non-signe...).
     */
    public String cppAttributeTypeName(String dataTypeName) {
        BasicRepr basic = resolveBasicRepr(dataTypeName);
        if (basic != null) return basic.attrCppType;
        // Composites (enum/struct/tableau) : meme resolution qu'un champ,
        // aucune ambiguite possible (noms generes uniques par construction).
        return cppTypeName(dataTypeName);
    }

    /** Accesseur (".value" ou "") pour atteindre la valeur brute d'un type d'attribut C++ eventuellement wrappe. */
    public String cppAttributeAccessor(String dataTypeName) {
        BasicRepr basic = resolveBasicRepr(dataTypeName);
        return basic != null ? basic.attrCppAccessor : "";
    }

    /** Type primitif Java pour un CHAMP de struct (reutilisable librement, pas de collision possible). */
    public String javaFieldTypeName(String dataTypeName) {
        BasicRepr basic = resolveBasicRepr(dataTypeName);
        if (basic != null) return basic.fieldJavaType;

        DataType dt = model.dataType(dataTypeName);
        if (dt == null) {
            throw new IllegalStateException("Type FOM inconnu: " + dataTypeName);
        }
        if (dt instanceof DataType.Simple simple) {
            DataType redirected = model.dataType(simple.representation);
            if (redirected instanceof DataType.Array) {
                return javaFieldTypeName(simple.representation);
            }
            throw new IllegalStateException(
                    "Representation HLA non reconnue par le generateur: '" + simple.representation +
                            "' (type FOM '" + dataTypeName + "').");
        }
        if (dt instanceof DataType.Array array) {
            if (array.isDynamic()) {
                return NameUtils.sanitizeIdentifier(array.name);
            }
            String elementType = javaFieldTypeName(array.elementDataTypeName);
            if ("int".equals(elementType)
                    && resolveBasicRepr(array.elementDataTypeName) != null
                    && resolveBasicRepr(array.elementDataTypeName).representation.equals("HLAoctet")) {
                return "byte[]"; // tableau fixe d'octets -> byte[] (cf. MarkingStruct)
            }
            String boxedElement = javaAttributeClassName(array.elementDataTypeName);
            return "java.util.List<" + boxedElement + ">";
        }
        return NameUtils.sanitizeIdentifier(dt.name);
    }

    /**
     * Classe Java pour un ATTRIBUT HLAAttribute&lt;T&gt; ou un element de
     * liste : DOIT etre unique par representation (cle du registre
     * HLAEncoder/HLADecoder), d'ou un wrapper dedie quand le type
     * "naturel" est deja pris par une autre representation.
     */
    public String javaAttributeClassName(String dataTypeName) {
        BasicRepr basic = resolveBasicRepr(dataTypeName);
        if (basic != null) return basic.attrJavaClass;

        DataType dt = model.dataType(dataTypeName);
        if (dt == null) {
            throw new IllegalStateException("Type FOM inconnu: " + dataTypeName);
        }
        if (dt instanceof DataType.Simple simple) {
            DataType redirected = model.dataType(simple.representation);
            if (redirected instanceof DataType.Array) {
                // HLAopaqueData / HLAhandle : redirige vers le type nomme
                // du tableau dynamique synthetique (cf. constructeur).
                return javaAttributeClassName(simple.representation);
            }
            throw new IllegalStateException(
                    "Representation HLA non reconnue par le generateur: '" + simple.representation +
                            "' (type FOM '" + dataTypeName + "').");
        }
        if (dt instanceof DataType.Array array) {
            // Type compose genere a part -- un attribut/parametre ou un
            // element de liste peut y faire reference directement
            // (HLAAttribute<CeType>). Depuis l'extension RPR-FOM v3.0/2025
            // (cas reels : OpaqueDatum64 imbrique dans
            // OpaqueDatum64Array1Plus ; RPRnoData et ASCIIcharArray8 en
            // alternative/attribut direct), ceci vaut aussi pour un
            // tableau a cardinalite FIXE -- JavaGenerator genere desormais
            // un type nomme (avec encodeur/decodeur exact-N-elements, pas
            // de compteur sur le fil) pour tout tableau "generatable" (cf.
            // isGeneratableArray), fixe ou dynamique, pas seulement
            // dynamique comme avant cette extension.
            return NameUtils.sanitizeIdentifier(array.name);
        }
        return NameUtils.sanitizeIdentifier(dt.name);
    }

    /** Accesseur (".value" ou "") pour atteindre la valeur brute d'un type d'attribut Java eventuellement wrappe. */
    public String javaAttributeAccessor(String dataTypeName) {
        BasicRepr basic = resolveBasicRepr(dataTypeName);
        return basic != null ? basic.attrJavaAccessor : "";
    }
}
