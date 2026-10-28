package rprfom.codegen.usertype;

import rprfom.codegen.generate.TypeMapper;
import rprfom.codegen.model.DataType;
import rprfom.codegen.model.FomModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Simulation, cote generateur, de la disposition binaire (alignement HLA)
 * d'un fixedRecordData. Sert au test de conformite genere : elle donne la
 * taille attendue de l'encodage d'un type de taille fixe, et la position
 * des champs enumeres (pour fabriquer des tampons aleatoires dont les
 * enumerations restent decodables).
 *
 * Les parties dynamiques (tableaux variables, chaines) sont simulees
 * VIDES : compteur a zero (emplacement force a 0), aucun element. Le test
 * ne les exerce donc pas avec des elements (cf. hasDynamic). Un variantRecord
 * est simule sur sa PREMIERE alternative (discriminant force, cf. hasVariant) ;
 * un type non resolu n'est pas simulable : le test se limite alors a
 * l'instance par defaut.
 *
 * Les regles reproduisent exactement celles du code genere (cf.
 * JavaGenerator/CppGenerator.emitEncodeField) : chaque champ est aligne
 * sur son octet boundary (par rapport au debut du record), un champ compose
 * s'aligne sur SON octet boundary avant d'etre ecrit, un marqueur de
 * bourrage aligne sur 32 ou 64 bits, aucun octet n'est ajoute en fin de
 * record.
 */
public final class UserTypeLayout {

    /** Emplacement d'un champ enumere : valeurs valides a y ecrire. */
    public record EnumSlot(long offset, int size, boolean littleEndian, long[] values) {
    }

    /** true si la taille de l'encodage ne depend pas des valeurs (aucune partie dynamique) et est simulable. */
    public final boolean fixedSize;
    /** true si un tampon de test peut etre fabrique (taille minimale, parties dynamiques vides). */
    public final boolean simulatable;
    /** true si le record contient des tableaux variables ou des chaines (testes vides). */
    public final boolean hasDynamic;
    /** true si le record contient un variantRecord (teste sur sa premiere alternative seulement). */
    public final boolean hasVariant;
    /** Taille de l'encodage (parties dynamiques vides) -- valable si simulatable. */
    public final long size;
    /** Emplacements a valeurs contraintes : enumerations (valeurs valides) et compteurs dynamiques (0). */
    public final List<EnumSlot> enumSlots;

    private UserTypeLayout(boolean fixedSize, boolean simulatable, boolean hasDynamic, boolean hasVariant, long size, List<EnumSlot> enumSlots) {
        this.hasVariant = hasVariant;
        this.fixedSize = fixedSize;
        this.simulatable = simulatable;
        this.hasDynamic = hasDynamic;
        this.size = size;
        this.enumSlots = enumSlots;
    }

    public static UserTypeLayout compute(FomModel model, TypeMapper mapper, String typeName) {
        Walker w = new Walker(model, mapper);
        long end = w.walkRecord(typeName, 0);
        return new UserTypeLayout(!w.hasDynamic && !w.hasVariant && w.simulatable, w.simulatable, w.hasDynamic, w.hasVariant, end, w.slots);
    }

    private static final class Walker {
        final FomModel model;
        final TypeMapper mapper;
        boolean simulatable = true;
        boolean hasDynamic = false;
        boolean hasVariant = false;
        final List<EnumSlot> slots = new ArrayList<>();
        int depth = 0;

        Walker(FomModel model, TypeMapper mapper) {
            this.model = model;
            this.mapper = mapper;
        }

        static long align(long offset, int boundary) {
            if (boundary <= 1) return offset;
            long r = offset % boundary;
            return r == 0 ? offset : offset + (boundary - r);
        }

        long walkRecord(String typeName, long offset) {
            DataType dt = model.dataType(typeName);
            if (!(dt instanceof DataType.FixedRecord rec) || depth > 32) {
                simulatable = false;
                return offset;
            }
            depth++;
            long off = offset;
            for (DataType.FixedRecord.Field f : rec.fields) {
                off = walkField(f.dataTypeName, off);
            }
            depth--;
            return off;
        }

        long walkField(String dataTypeName, long offset) {
            if (mapper.isPaddingMarker(dataTypeName)) {
                return align(offset, mapper.paddingMarkerBoundary(dataTypeName));
            }
            TypeMapper.BasicRepr basic = mapper.resolveBasicRepr(dataTypeName);
            if (basic != null) {
                String r = basic.representation;
                if (r.equals("HLAASCIIstring") || r.equals("HLAunicodeString")) {
                    // chaine vide : longueur (4 octets, alignee sur 4) forcee a 0
                    hasDynamic = true;
                    long start = align(offset, 4);
                    slots.add(new EnumSlot(start, 4, false, new long[] {0}));
                    return start + 4;
                }
                if (r.startsWith("HLAoctetPair")) {
                    return offset + 2;
                }
                long start = align(offset, basic.octetBoundary);
                return start + basic.octetBoundary;
            }
            DataType.Array array = mapper.resolveArrayType(dataTypeName);
            if (array != null) {
                if (array.isDynamic()) {
                    // tableau dynamique VIDE : meme alignement que l'encodeur (octetBoundaryOf du champ),
                    // puis compteur a 0 (variable), rien (lengthless) ou terminateur seul (null-terminated)
                    hasDynamic = true;
                    long start = align(offset, mapper.octetBoundaryOf(dataTypeName));
                    switch (array.encoding) {
                        case RPR_LENGTHLESS_ARRAY:
                            return start;
                        case RPR_NULL_TERMINATED_ARRAY:
                            slots.add(new EnumSlot(start, 1, false, new long[] {0}));
                            return start + 1;
                        default:
                            slots.add(new EnumSlot(start, 4, false, new long[] {0}));
                            return start + 4;
                    }
                }
                long off = offset;
                for (int i = 0; i < array.fixedCardinality; i++) {
                    off = walkField(array.elementDataTypeName, off);
                }
                return off;
            }
            DataType dt = model.dataType(dataTypeName);
            if (dt instanceof DataType.Enumerated e) {
                TypeMapper.BasicRepr underlying = mapper.resolveEnumUnderlying(e);
                int size = underlying != null ? underlying.octetBoundary : 4;
                boolean le = underlying != null && underlying.representation.endsWith("LE");
                long start = align(offset, size);
                long[] values = new long[e.enumerators.size()];
                for (int i = 0; i < values.length; i++) values[i] = e.enumerators.get(i).value;
                slots.add(new EnumSlot(start, size, le, values));
                return start + size;
            }
            if (dt instanceof DataType.FixedRecord) {
                long start = align(offset, mapper.octetBoundaryOf(dataTypeName));
                return walkRecord(dataTypeName, start);
            }
            if (dt instanceof DataType.VariantRecord v && !v.alternatives.isEmpty()) {
                TypeMapper.BasicRepr disc = mapper.resolveDiscriminantUnderlying(v);
                if (disc != null && depth <= 32) {
                    // Premiere alternative : discriminant force a sa valeur, puis alignement de l'alternative
                    // et son contenu (cf. generateVariantEncoderRegistration).
                    hasVariant = true;
                    DataType.VariantRecord.Alternative alt = v.alternatives.get(0);
                    long start = align(offset, mapper.octetBoundaryOf(dataTypeName));
                    long discStart = align(start, disc.octetBoundary);
                    slots.add(new EnumSlot(discStart, disc.octetBoundary, disc.representation.endsWith("LE"),
                            new long[] {mapper.discriminantValueOf(v, alt)}));
                    long altStart = align(discStart + disc.octetBoundary, mapper.octetBoundaryOf(alt.dataTypeName));
                    depth++;
                    long end = walkField(alt.dataTypeName, altStart);
                    depth--;
                    return end;
                }
            }
            // Simple non resolu, type inconnu, variant sans discriminant : non simulable.
            simulatable = false;
            return offset;
        }
    }
}
