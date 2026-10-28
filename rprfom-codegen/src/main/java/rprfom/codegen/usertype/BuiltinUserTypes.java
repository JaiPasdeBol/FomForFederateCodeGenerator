package rprfom.codegen.usertype;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Types "a responsabilite du developpeur" fournis PAR DEFAUT avec le
 * generateur lui-meme (bundles dans ses ressources), par opposition aux
 * types declares a l'appel (--user-type / --user-type-java / --user-type-cpp /
 * --user-types-java / --user-types-cpp / --user-types-config), qui restent
 * inchanges et toujours prioritaires sur ce mecanisme.
 *
 * Objectif : couvrir, une fois pour toutes dans le generateur, les types
 * recurrents que la plupart des projets reecrivent de la meme facon (ex.
 * WorldLocationStruct en latitude/longitude/altitude), sans que chaque
 * projet ait besoin de redeclarer quoi que ce soit en ligne de commande.
 * Un projet qui a besoin d'une version differente garde la main en
 * ajoutant simplement un fichier de meme nom dans SA PROPRE arborescence
 * (--user-types-java/--user-types-cpp/--user-types-config) : cf.
 * {@link UserTypeResolver}, qui tente toujours, pour chaque type et
 * chaque langage, dans l'ordre : fichier explicite (CLI) -> dossier de
 * convention (CLI) -> type integre ici -> classe de secours generee.
 *
 * <p>Convention de ressources, sous {@code src/main/resources/usertypes-builtin/} :
 * <pre>
 *   registry.txt         -- un nom de type (fixedRecordData de la FOM) par ligne ;
 *                            lignes vides et lignes commencant par '#' ignorees.
 *                            C'est la SEULE liste des types geres par ce mecanisme :
 *                            un fichier java/cpp present mais absent de registry.txt
 *                            est ignore.
 *   java/&lt;Nom&gt;.java       -- optionnel : recouvrement Java integre pour ce type.
 *   cpp/&lt;Nom&gt;.hpp
 *   cpp/&lt;Nom&gt;Codec.hpp     -- optionnels, ensemble (comme pour --user-type-cpp) :
 *                            fournir un seul des deux desactive le recouvrement C++
 *                            integre pour ce type (pas une erreur : cf. ci-dessous).
 * </pre>
 *
 * <p>Un type liste dans {@code registry.txt} sans fichier java/cpp correspondant
 * n'a simplement pas de recouvrement integre dans ce langage (classe de secours
 * generee normalement, comme si le type n'etait pas liste). Contrairement a
 * {@code --user-type}, un type integre absent de la FOM courante, filtre par
 * {@code --federate-fom}, non {@code fixedRecordData}, en collision de nom avec une
 * classe generee, dont le contrat (methodes/struct attendues) n'est pas respecte,
 * ou dont l'empreinte FOM ne correspond pas a la definition de ce type dans la FOM
 * courante, est simplement IGNORE pour ce langage (repli silencieux sur la classe
 * de secours standard) -- jamais une erreur bloquante : ce mecanisme doit
 * fonctionner sans aucune configuration, quel que soit le FOM fourni en entree.
 */
public final class BuiltinUserTypes {

    private static final String BASE = "usertypes-builtin/";

    private BuiltinUserTypes() {
    }

    /** Noms declares dans registry.txt, dans l'ordre, sans doublon. Jamais null. */
    public static List<String> registeredNames() {
        Set<String> names = new LinkedHashSet<>();
        try (InputStream in = BuiltinUserTypes.class.getClassLoader().getResourceAsStream(BASE + "registry.txt")) {
            if (in == null) return List.of();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                names.add(t);
            }
        } catch (IOException ex) {
            return List.of();
        }
        return new ArrayList<>(names);
    }

    /** Contenu du fichier Java integre pour ce type (nom de classe sanitise), ou null si absent. */
    public static String readJava(String typeClassName) {
        return readResource(BASE + "java/" + typeClassName + ".java");
    }

    /** {en-tete, codec} integres pour ce type, ou null si l'un des deux (ou les deux) est absent. */
    public static String[] readCpp(String typeClassName) {
        String header = readResource(BASE + "cpp/" + typeClassName + ".hpp");
        String codec = readResource(BASE + "cpp/" + typeClassName + "Codec.hpp");
        if (header == null || codec == null) return null;
        return new String[] {header, codec};
    }

    private static String readResource(String path) {
        try (InputStream in = BuiltinUserTypes.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return null;
        }
    }
}
