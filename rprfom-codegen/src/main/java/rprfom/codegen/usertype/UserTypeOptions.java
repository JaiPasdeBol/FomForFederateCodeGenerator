package rprfom.codegen.usertype;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Options de ligne de commande relatives aux types "a responsabilite du
 * developpeur" : des types FOM (fixedRecordData) dont le code n'est plus
 * genere mais copie depuis des fichiers fournis par le developpeur. Pour
 * chaque type declare, si le developpeur ne fournit pas de fichier (dans
 * un langage donne), une classe de secours generee automatiquement est
 * copiee a la place -- cf. {@link UserTypeResolver}.
 *
 * <pre>
 *   --user-type &lt;Nom&gt;                     declare un type gere par le developpeur (repetable)
 *   --user-type-java &lt;Nom&gt;=&lt;fichier.java&gt;   fichier Java explicite (declare aussi le type)
 *   --user-type-cpp  &lt;Nom&gt;=&lt;X.hpp&gt;[,&lt;XCodec.hpp&gt;]
 *   --user-types-java &lt;dossier&gt;            convention : &lt;dossier&gt;/&lt;Nom&gt;.java
 *   --user-types-cpp  &lt;dossier&gt;            convention : &lt;dossier&gt;/&lt;Nom&gt;.hpp + &lt;Nom&gt;Codec.hpp
 *   --user-types-config &lt;fichier.xml&gt;      association Nom -> dossier Java / dossier C++ (voir {@link #loadConfig})
 *   --fallback-dir &lt;dossier&gt;               dossier des classes de secours (defaut : sous les dossiers de sortie)
 *   --strict-user-types                    fichier absent / sans empreinte = erreur (au lieu du secours / avertissement)
 *   --allow-stale-user-types               empreinte FOM perimee = avertissement (au lieu d'une erreur)
 *   --skip-conformance-test                ne genere pas les tests de conformite
 * </pre>
 */
public final class UserTypeOptions {

    /** Noms declares, dans l'ordre de declaration, doublons conserves (signales par le resolveur). */
    public final List<String> declared = new ArrayList<>();
    public final Map<String, Path> javaFiles = new LinkedHashMap<>();
    /** Pour le C++ : [0] = en-tete de la struct, [1] = en-tete du codec (peut etre null = "<Nom>Codec.hpp" a cote). */
    public final Map<String, Path[]> cppFiles = new LinkedHashMap<>();
    public Path javaDir;
    public Path cppDir;
    public Path fallbackDir;
    public boolean strict;
    public boolean allowStale;
    public boolean skipConformanceTest;

    public boolean isActive() {
        return !declared.isEmpty();
    }

    public void declare(String name) {
        declared.add(name);
    }

    /** "Nom=chemin" -> (Nom, chemin). Leve IllegalArgumentException si le format est invalide. */
    public static String[] splitNameValue(String option, String arg) {
        int eq = arg.indexOf('=');
        if (eq <= 0 || eq == arg.length() - 1) {
            throw new IllegalArgumentException(option + " attend le format <NomDuType>=<fichier> (recu : '" + arg + "')");
        }
        return new String[] {arg.substring(0, eq).trim(), arg.substring(eq + 1).trim()};
    }

    public void addJavaFile(String arg) {
        String[] nv = splitNameValue("--user-type-java", arg);
        declare(nv[0]);
        javaFiles.put(nv[0], Path.of(nv[1]));
    }

    public void addCppFile(String arg) {
        String[] nv = splitNameValue("--user-type-cpp", arg);
        declare(nv[0]);
        String[] parts = nv[1].split(",", 2);
        Path header = Path.of(parts[0].trim());
        Path codec = parts.length > 1 && !parts[1].isBlank() ? Path.of(parts[1].trim()) : null;
        cppFiles.put(nv[0], new Path[] {header, codec});
    }

    /**
     * Charge un fichier XML associant a chaque type un dossier Java et/ou un dossier C++ :
     * <pre>
     * &lt;userTypes&gt;
     *   &lt;userType name="WorldLocationStruct"&gt;
     *     &lt;java dir="src/fom/java"/&gt;
     *     &lt;cpp  dir="src/fom/cpp"/&gt;
     *   &lt;/userType&gt;
     * &lt;/userTypes&gt;
     * </pre>
     * Dans le dossier, la convention habituelle s'applique : &lt;Nom&gt;.java, &lt;Nom&gt;.hpp + &lt;Nom&gt;Codec.hpp.
     * Les chemins relatifs sont relatifs au dossier du fichier XML. Le type est declare par le fichier.
     * Un langage sans element (ou sans attribut dir) utilise la classe de secours. Les options explicites
     * de la ligne de commande (--user-type-java / --user-type-cpp) restent prioritaires.
     */
    public void loadConfig(Path xml) {
        Element root;
        try {
            if (!Files.isRegularFile(xml)) {
                throw new IllegalArgumentException("fichier introuvable");
            }
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            Document doc = f.newDocumentBuilder().parse(xml.toFile());
            root = doc.getDocumentElement();
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("--user-types-config " + xml + " : " + ex.getMessage());
        } catch (Exception ex) {
            throw new IllegalArgumentException("--user-types-config " + xml + " : XML illisible (" + ex.getMessage() + ")");
        }
        String where = "--user-types-config " + xml + " : ";
        if (!root.getTagName().equals("userTypes")) {
            throw new IllegalArgumentException(where + "l'element racine doit etre <userTypes> (trouve <" + root.getTagName() + ">)");
        }
        Path base = xml.toAbsolutePath().getParent();
        java.util.Set<String> seen = new java.util.HashSet<>();
        NodeList kids = root.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (!(n instanceof Element t)) continue;
            if (!t.getTagName().equals("userType")) {
                throw new IllegalArgumentException(where + "element <" + t.getTagName() + "> inattendu (seul <userType> est permis)");
            }
            String name = t.getAttribute("name").trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException(where + "<userType> sans attribut name");
            }
            if (!seen.add(name)) {
                throw new IllegalArgumentException(where + "type '" + name + "' decrit deux fois");
            }
            if (!declared.contains(name)) declare(name);
            NodeList sub = t.getChildNodes();
            boolean hasJava = false, hasCpp = false;
            for (int j = 0; j < sub.getLength(); j++) {
                if (!(sub.item(j) instanceof Element e)) continue;
                String tag = e.getTagName();
                if (!tag.equals("java") && !tag.equals("cpp")) {
                    throw new IllegalArgumentException(where + "type '" + name + "' : element <" + tag + "> inattendu (<java> ou <cpp>)");
                }
                boolean java = tag.equals("java");
                if (java ? hasJava : hasCpp) {
                    throw new IllegalArgumentException(where + "type '" + name + "' : <" + tag + "> present deux fois");
                }
                if (java) hasJava = true; else hasCpp = true;
                String dir = e.getAttribute("dir").trim();
                if (dir.isEmpty()) {
                    throw new IllegalArgumentException(where + "type '" + name + "' : <" + tag + "> sans attribut dir");
                }
                Path d = Path.of(dir);
                if (!d.isAbsolute() && base != null) d = base.resolve(d).normalize();
                if (java) {
                    javaFiles.putIfAbsent(name, d.resolve(name + ".java"));
                } else {
                    cppFiles.putIfAbsent(name, new Path[] {d.resolve(name + ".hpp"), d.resolve(name + "Codec.hpp")});
                }
            }
        }
        if (seen.isEmpty()) {
            throw new IllegalArgumentException(where + "aucun <userType> dans le fichier");
        }
    }
}
