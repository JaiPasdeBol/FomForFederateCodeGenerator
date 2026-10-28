package rprfom.codegen.generate;

import java.util.Set;

public final class NameUtils {
    private NameUtils() {
    }

    /**
     * Mots reserves C++ ET Java (union des deux, le code genere doit etre
     * valide dans les deux langages) -- un identifiant qui tombe dessus
     * (typiquement un nom de champ FOM "Class", "Public", etc.) recoit un
     * "_" en suffixe plutot que de produire du code qui ne compile pas.
     */
    private static final Set<String> RESERVED_WORDS = Set.of(
            // C++
            "alignas", "alignof", "and", "asm", "auto", "bool", "break", "case", "catch", "char",
            "class", "const", "constexpr", "continue", "decltype", "default", "delete", "do",
            "double", "dynamic_cast", "else", "enum", "explicit", "export", "extern", "false",
            "float", "for", "friend", "goto", "if", "inline", "int", "long", "mutable",
            "namespace", "new", "noexcept", "not", "nullptr", "operator", "or", "private",
            "protected", "public", "register", "reinterpret_cast", "return", "short", "signed",
            "sizeof", "static", "static_assert", "static_cast", "struct", "switch", "template",
            "this", "thread_local", "throw", "true", "try", "typedef", "typeid", "typename",
            "union", "unsigned", "using", "virtual", "void", "volatile", "wchar_t", "while",
            // Java (mots deja presents ci-dessus non repetes)
            "abstract", "assert", "boolean", "byte", "extends", "final", "finally",
            "implements", "import", "instanceof", "interface", "native", "package", "strictfp",
            "super", "synchronized", "throws", "transient", "var", "yield", "record", "sealed",
            "permits");

    private static String escapeReserved(String identifier) {
        return RESERVED_WORDS.contains(identifier) ? identifier + "_" : identifier;
    }

    /** "EntityType" -> "entityType" (nom de champ Java/C++ a partir d'un nom d'attribut FOM). */
    public static String toFieldName(String fomName) {
        if (fomName == null || fomName.isEmpty()) return fomName;
        // Nettoie d'abord les caracteres invalides (ex. "NumberOfBytes-A-Value"
        // contient un tiret litteral dans certains champs FOM reels), puis
        // met en minuscule la premiere lettre, puis echappe les mots reserves.
        String cleaned = sanitizeIdentifier(fomName);
        String lowered = Character.toLowerCase(cleaned.charAt(0)) + cleaned.substring(1);
        return escapeReserved(lowered);
    }

    /** "NoDamage" / "no-damage" / "NO_DAMAGE" -> "NO_DAMAGE" (constante d'enum Java). */
    public static String toEnumConstant(String fomEnumeratorName) {
        StringBuilder sb = new StringBuilder();
        char[] chars = fomEnumeratorName.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '-' || c == ' ' || c == '.') {
                sb.append('_');
                continue;
            }
            if (Character.isUpperCase(c) && i > 0
                    && Character.isLowerCase(chars[i - 1])) {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        String result = sb.toString().replaceAll("_+", "_");
        if (!result.isEmpty() && Character.isDigit(result.charAt(0))) {
            result = "V_" + result; // un identifiant ne peut pas commencer par un chiffre
        }
        return result;
    }

    /** "worldLocation" -> "WorldLocation" (pour nommer les methodes ofXxx/asXxx d'un variant). */
    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Sanitize minimal pour un nom de type/champ FOM utilise tel quel comme identifiant. */
    public static String sanitizeIdentifier(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9_]", "_");
        if (!cleaned.isEmpty() && Character.isDigit(cleaned.charAt(0))) {
            cleaned = "_" + cleaned;
        }
        return escapeReserved(cleaned);
    }

    /** Chemin FOM complet ("HLAobjectRoot.BaseEntity.PhysicalEntity") pour HLA_OBJECT_CLASS_NAME. */
    public static String dottedPath(java.util.List<String> pathFromRoot) {
        return String.join(".", pathFromRoot);
    }

    // ====================================================================
    // Cote Unreal (resultatCppUnreal) : prefixes imposes par Unreal Header
    // Tool (F pour un USTRUCT, U pour un UCLASS/UObject, E pour un UENUM).
    // Le nom FOM d'origine est conserve tel quel apres le prefixe (decision
    // explicite : pas de renommage/relookup supplementaire, pas de nouvelle
    // liste de mots reserves par langage de sortie -- sanitizeIdentifier
    // suffit, comme pour le C++/Java).
    // ====================================================================

    /** "WorldLocationStruct" -> "FWorldLocationStruct" (USTRUCT : fixedRecordData/variantRecordData). */
    public static String unrealStructName(String fomName) {
        return "F" + sanitizeIdentifier(fomName);
    }

    /** "BaseEntity" -> "UBaseEntity" (UCLASS : objectClass/interactionClass). */
    public static String unrealClassName(String fomName) {
        return "U" + sanitizeIdentifier(fomName);
    }

    /** "DamageStatusEnum32" -> "EDamageStatusEnum32" (UENUM : enumeratedData). */
    public static String unrealEnumName(String fomName) {
        return "E" + sanitizeIdentifier(fomName);
    }

    /**
     * Nom d'UPROPERTY/de parametre de fonction a partir d'un nom de champ ou
     * d'attribut FOM : contrairement a toFieldName (Java/C++, camelCase), le
     * nom FOM d'origine est conserve tel quel (deja en general PascalCase
     * dans un FOM reel, ce qui correspond directement a la convention
     * UPROPERTY attendue par l'editeur Blueprint).
     */
    public static String unrealPropertyName(String fomName) {
        return sanitizeIdentifier(fomName);
    }
}
