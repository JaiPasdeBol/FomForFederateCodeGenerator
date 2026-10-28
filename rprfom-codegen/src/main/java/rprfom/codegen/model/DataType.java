package rprfom.codegen.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modele en memoire d'un type de donnee FOM (element de
 * <dataTypes> dans un fichier OMT XML). Une seule hierarchie de classes
 * couvre les 5 categories rencontrees dans la RPR-FOM :
 * simpleData, enumeratedData, fixedRecordData, arrayData, variantRecordData.
 */
public abstract class DataType {
    public final String name;

    protected DataType(String name) {
        this.name = name;
    }

    /** Nom du/des type(s) FOM dont depend ce type (pour le tri topologique). */
    public abstract List<String> dependsOn();

    // ================================================================

    /** <simpleData> : alias direct d'une representation de base HLA. */
    public static final class Simple extends DataType {
        /** Representation de base HLA, ex. "HLAinteger16BE", "HLAfloat32BE", "HLAboolean". */
        public final String representation;

        public Simple(String name, String representation) {
            super(name);
            this.representation = representation;
        }

        @Override
        public List<String> dependsOn() {
            return List.of();
        }
    }

    /** <enumeratedData> : enumeration avec representation entiere sous-jacente. */
    public static final class Enumerated extends DataType {
        public final String representation; // basicData ou simpleData sous-jacent
        public final List<Enumerator> enumerators = new ArrayList<>();

        public Enumerated(String name, String representation) {
            super(name);
            this.representation = representation;
        }

        @Override
        public List<String> dependsOn() {
            return List.of();
        }

        public static final class Enumerator {
            public final String name;
            public final long value;

            public Enumerator(String name, long value) {
                this.name = name;
                this.value = value;
            }
        }
    }

    /** <fixedRecordData> : struct/HLAfixedRecord, champs ordonnes. */
    public static final class FixedRecord extends DataType {
        public final List<Field> fields = new ArrayList<>();

        public FixedRecord(String name) {
            super(name);
        }

        @Override
        public List<String> dependsOn() {
            List<String> deps = new ArrayList<>();
            for (Field f : fields) deps.add(f.dataTypeName);
            return deps;
        }

        public static final class Field {
            public final String name;
            public final String dataTypeName;

            public Field(String name, String dataTypeName) {
                this.name = name;
                this.dataTypeName = dataTypeName;
            }
        }
    }

    /**
     * <arrayData>. En pratique, la RPR-FOM utilise plusieurs encodages
     * distincts pour les tableaux dynamiques, pas seulement
     * HLAvariableArray :
     *   - HLAvariableArray     : compteur HLAinteger32BE + elements
     *   - RPRlengthlessArray   : pas de compteur, les elements occupent
     *     tout le reste du buffer (valide seulement en derniere
     *     position d'une valeur encodee)
     *   - HLAfixedArray        : cardinalite fixe, pas de compteur
     *     (deja gere avant cette extension)
     * Deux encodages supplementaires rencontres dans la RPR-FOM
     * standard ne sont PAS des tableaux a elements au sens normal et
     * sont traites a part (cf. TypeMapper.isPaddingMarker) :
     *   - RPRpaddingTo32Array / RPRpaddingTo64Array : champ de bourrage
     *     pur, ne porte aucune valeur, amene la longueur de
     *     l'enregistrement courant a un multiple de 32/64 bits.
     */
    public enum ArrayEncoding {
        HLA_VARIABLE_ARRAY,
        RPR_LENGTHLESS_ARRAY,
        HLA_FIXED_ARRAY,
        /** Champ de bourrage pur (pas d'elements) -- cf. TypeMapper.isPaddingMarker. */
        RPR_PADDING_32,
        RPR_PADDING_64,
        /**
         * Sequence d'elements suivie d'un octet 0x00 terminateur (comme une
         * chaine C). Rencontre en pratique dans la RPR-FOM reelle via
         * RTIobjectId (element HLAASCIIchar) -- supporte uniquement pour
         * des elements d'1 octet (TypeMapper.octetBoundaryOf == 1), le
         * generateur echoue explicitement sinon plutot que de produire un
         * encodage incorrect.
         */
        RPR_NULL_TERMINATED_ARRAY,
    }

    public static final class Array extends DataType {
        public final String elementDataTypeName;
        /** -1 si cardinalite Dynamic/variable, sinon taille fixe. */
        public final int fixedCardinality;
        public final ArrayEncoding encoding;

        public Array(String name, String elementDataTypeName, int fixedCardinality, ArrayEncoding encoding) {
            super(name);
            this.elementDataTypeName = elementDataTypeName;
            this.fixedCardinality = fixedCardinality;
            this.encoding = encoding;
        }

        /** Constructeur de commodite : deduit l'encodage par defaut depuis la seule cardinalite (retro-compatible). */
        public Array(String name, String elementDataTypeName, int fixedCardinality) {
            this(name, elementDataTypeName, fixedCardinality,
                    fixedCardinality < 0 ? ArrayEncoding.HLA_VARIABLE_ARRAY : ArrayEncoding.HLA_FIXED_ARRAY);
        }

        public boolean isDynamic() {
            return fixedCardinality < 0;
        }

        @Override
        public List<String> dependsOn() {
            return List.of(elementDataTypeName);
        }
    }

    /**
     * <variantRecordData> : union discriminee. SIMPLIFICATION (documentee
     * et deja appliquee a la main pour SpatialVariantStruct) : genere comme
     * un fixedRecord contenant le discriminant PUIS tous les champs de
     * TOUTES les alternatives concatenes, plutot qu'un vrai variant
     * n'encodant que l'alternative active. A affiner si besoin.
     */
    public static final class VariantRecord extends DataType {
        public final String discriminantName;
        public final String discriminantDataTypeName;
        public final List<Alternative> alternatives = new ArrayList<>();

        public VariantRecord(String name, String discriminantName, String discriminantDataTypeName) {
            super(name);
            this.discriminantName = discriminantName;
            this.discriminantDataTypeName = discriminantDataTypeName;
        }

        @Override
        public List<String> dependsOn() {
            List<String> deps = new ArrayList<>();
            deps.add(discriminantDataTypeName);
            for (Alternative a : alternatives) deps.add(a.dataTypeName);
            return deps;
        }

        public static final class Alternative {
            public final String enumerator;
            public final String fieldName;
            public final String dataTypeName;

            public Alternative(String enumerator, String fieldName, String dataTypeName) {
                this.enumerator = enumerator;
                this.fieldName = fieldName;
                this.dataTypeName = dataTypeName;
            }
        }
    }

    // ================================================================
    // Noms des representations de base HLA reconnues directement (pas
    // besoin d'une entree simpleData pour ces noms -- ce sont les
    // <basicData> elementaires de la norme).
    // ================================================================
    public static final Map<String, String> BASIC_REPRESENTATIONS = new LinkedHashMap<>();

    static {
        for (String n : List.of(
                "HLAoctet", "HLAinteger16BE", "HLAinteger32BE", "HLAinteger64BE",
                "HLAunsignedInteger16BE", "HLAunsignedInteger32BE",
                "HLAfloat32BE", "HLAfloat64BE", "HLAboolean",
                "HLAASCIIchar", "HLAunicodeChar")) {
            BASIC_REPRESENTATIONS.put(n, n);
        }
    }
}
