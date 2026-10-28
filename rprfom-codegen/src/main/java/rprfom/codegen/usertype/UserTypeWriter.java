package rprfom.codegen.usertype;

import rprfom.codegen.generate.CppGenerator;
import rprfom.codegen.generate.JavaGenerator;
import rprfom.codegen.usertype.UserTypeResolver.Entry;
import rprfom.codegen.usertype.UserTypeResolver.Plan;
import rprfom.codegen.usertype.UserTypeResolver.Source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ecrit les fichiers des types a responsabilite du developpeur :
 * <ul>
 *   <li>la classe de SECOURS de chaque type, dans un dossier separe
 *       (fallback/) -- toujours regeneree, meme quand le developpeur
 *       fournit son propre fichier, pour servir de reference et de modele ;</li>
 *   <li>la copie vers la DESTINATION (sources compilees) : le fichier du
 *       developpeur s'il existe, sinon le secours ;</li>
 *   <li>le test de conformite (sauf --skip-conformance-test) ;</li>
 *   <li>un manifeste des fichiers ecrits, qui permet a la generation
 *       suivante de supprimer ce qui n'a plus lieu d'etre (un ancien
 *       X.java resterait sinon dans le package et masquerait le type
 *       redevenu genere) ; jamais un fichier que l'outil n'a pas ecrit.</li>
 * </ul>
 * Un fichier existant non gere par l'outil et de contenu different n'est
 * jamais ecrase sans sauvegarde (copie ".bak" a cote).
 */
public final class UserTypeWriter {

    private static final String MANIFEST = ".rprfom-user-types.manifest";
    private static final int TRIALS = 300;

    private UserTypeWriter() {
    }

    // ====================================================================
    // Manifeste
    // ====================================================================

    private static final class Manifest {
        final Path file;
        final Path root;
        final Set<Path> previous = new HashSet<>();
        final List<Path> written = new ArrayList<>();

        Manifest(Path outDir) throws IOException {
            this.file = outDir.resolve(MANIFEST);
            this.root = outDir.toAbsolutePath().normalize();
            if (Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) previous.add(Path.of(line.trim()).toAbsolutePath().normalize());
                }
            }
        }

        void deletePrevious() throws IOException {
            for (Path p : previous) {
                Files.deleteIfExists(p);
                // supprime les dossiers devenus vides, sans jamais sortir du dossier de sortie ni le supprimer
                for (Path d = p.getParent(); d != null && d.startsWith(root) && !d.equals(root) && Files.isDirectory(d); d = d.getParent()) {
                    try (var st = Files.list(d)) {
                        if (st.findAny().isPresent()) break;
                    }
                    Files.delete(d);
                }
            }
        }

        void save() throws IOException {
            if (written.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            List<String> lines = new ArrayList<>();
            for (Path p : written) lines.add(p.toString());
            Files.write(file, lines, StandardCharsets.UTF_8);
        }

        /** Ecrit dest ; sauvegarde (.bak) et avertit si un fichier NON gere de contenu different existe deja. */
        void write(Path dest, String text, Plan plan) throws IOException {
            Path abs = dest.toAbsolutePath().normalize();
            Files.createDirectories(abs.getParent());
            if (Files.isRegularFile(abs)) {
                String existing = Files.readString(abs, StandardCharsets.UTF_8);
                if (!existing.equals(text)) {
                    Path bak = abs.resolveSibling(abs.getFileName() + ".bak");
                    Files.copy(abs, bak, StandardCopyOption.REPLACE_EXISTING);
                    plan.warnings.add("Le fichier '" + abs + "' n'a pas ete ecrit par rprfom-codegen et son contenu differe : "
                            + "il est remplace, l'ancienne version est sauvegardee dans '" + bak.getFileName() + "'.");
                }
            }
            Files.writeString(abs, text, StandardCharsets.UTF_8);
            written.add(abs);
        }
    }

    /** Supprime les fichiers ecrits par une execution precedente (types utilisateur retires depuis). */
    public static void cleanupOnly(Path outDir) throws IOException {
        if (outDir == null) return;
        Manifest m = new Manifest(outDir);
        m.deletePrevious();
        m.save();
    }

    // ====================================================================
    // Java
    // ====================================================================

    public static void writeJava(Plan plan, JavaGenerator gen, Path outJava, String pkg, UserTypeOptions o) throws IOException {
        Manifest m = new Manifest(outJava);
        m.deletePrevious();

        Path fallbackDir = (o.fallbackDir != null ? o.fallbackDir.resolve("java") : outJava.resolve("fallback"));
        Path destDir = outJava.resolve("src").resolve("main").resolve("java").resolve(pkg.replace('.', '/'));
        Path testDir = outJava.resolve("src").resolve("test").resolve("java").resolve(pkg.replace('.', '/')).resolve("conformance");

        for (Entry e : plan.entries) {
            String fallbackText = gen.generateUserTypeClass(e.typeName, e.fingerprint, pkg, e.className, false);
            m.write(fallbackDir.resolve(e.className + ".java"), fallbackText, plan);

            Path dest = destDir.resolve(e.className + ".java");
            String destText;
            if (e.javaSource == Source.CUSTOM) {
                if (e.javaFile.toAbsolutePath().normalize().equals(dest.toAbsolutePath().normalize())) {
                    plan.warnings.add("Type '" + e.typeName + "' [Java] : le fichier fourni EST le fichier de destination (" + dest
                            + "). Il est reecrit a l'identique, mais gardez vos sources dans un dossier a part : les dossiers generes "
                            + "peuvent etre nettoyes.");
                }
                destText = rewritePackage(e.javaText, pkg, e, plan);
            } else if (e.javaSource == Source.BUILTIN) {
                // Type integre au generateur (ressources) : meme traitement de package que CUSTOM,
                // mais sans fichier projet a comparer au chemin de destination (rien a avertir ici).
                destText = rewritePackage(e.javaText, pkg, e, plan);
            } else {
                destText = fallbackText;
            }
            m.write(dest, destText, plan);
        }

        if (!o.skipConformanceTest && !plan.entries.isEmpty()) {
            for (Entry e : plan.entries) {
                m.write(testDir.resolve("Ref_" + e.className + ".java"),
                        gen.generateUserTypeClass(e.typeName, e.fingerprint, pkg + ".conformance", "Ref_" + e.className, true), plan);
            }
            m.write(testDir.resolve("UserTypesConformanceCheck.java"), javaCheckClass(plan, gen, pkg), plan);
        }
        m.save();
    }

    /** Portee reelle du test pour un type (a afficher : un test partiel ne doit pas passer pour un test complet). */
    private static String scopeNote(UserTypeLayout lay) {
        if (!lay.simulatable) return " ; controle PARTIEL : type non simulable, instance par defaut seulement";
        List<String> parts = new ArrayList<>();
        if (lay.hasDynamic) parts.add("tableaux/chaines dynamiques testes VIDES");
        if (lay.hasVariant) parts.add("variants testes sur leur 1re alternative");
        return parts.isEmpty() ? "" : " ; " + String.join(" ; ", parts);
    }

    private static final Pattern PACKAGE_LINE = Pattern.compile("(?m)^[ \\t]*package[ \\t]+([\\w.]+)[ \\t]*;");

    /** Aligne "package ..." et les imports du fichier du developpeur sur le package racine demande (--java-package). */
    private static String rewritePackage(String text, String pkg, Entry e, Plan plan) {
        Matcher m = PACKAGE_LINE.matcher(text);
        if (!m.find()) {
            plan.warnings.add("Type '" + e.typeName + "' [Java] : aucune ligne 'package' dans le fichier fourni : 'package " + pkg + ";' ajoute.");
            return "package " + pkg + ";\n\n" + text;
        }
        String old = m.group(1);
        if (old.equals(pkg)) return text;
        plan.warnings.add("Type '" + e.typeName + "' [Java] : package du fichier fourni reecrit de '" + old + "' vers '" + pkg
                + "' (ainsi que ses imports).");
        String out = text.substring(0, m.start()) + "package " + pkg + ";" + text.substring(m.end());
        return out.replaceAll("(?m)^([ \\t]*import[ \\t]+(?:static[ \\t]+)?)" + Pattern.quote(old) + "(?=[.;])", "$1" + Matcher.quoteReplacement(pkg));
    }

    private static String javaCheckClass(Plan plan, JavaGenerator gen, String pkg) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".conformance;\n\n");
        sb.append("import java.io.ByteArrayOutputStream;\nimport java.util.Random;\n\n");
        sb.append("import ").append(pkg).append(".runtime.*;\n");
        for (Entry e : plan.entries) sb.append("import ").append(pkg).append(".").append(e.className).append(";\n");
        sb.append("\n/**\n * UserTypesConformanceCheck.java -- GENERE. Verifie que le code de chaque type a responsabilite du\n");
        sb.append(" * developpeur (le sien ou la classe de secours) encode/decode EXACTEMENT comme le code standard genere\n");
        sb.append(" * depuis la FOM (classes Ref_*) : memes octets, memes tailles consommees, alignement enregistre conforme.\n");
        sb.append(" * ").append(TRIALS).append(" tampons pseudo-aleatoires par type (enumerations valides ; tableaux variables et chaines testes VIDES ;\n");
        sb.append(" * variants testes sur leur 1re alternative) ; type non simulable : instance par defaut seulement (controle partiel).\n *\n");
        sb.append(" * Lancement : java -cp target/classes:target/test-classes ").append(pkg).append(".conformance.UserTypesConformanceCheck\n");
        sb.append(" * (echoue par une exception si un ecart est detecte ; execute automatiquement par 'mvn test').\n */\n");
        sb.append("public final class UserTypesConformanceCheck {\n\n");
        sb.append("    private UserTypesConformanceCheck() {\n    }\n\n");
        sb.append("    public static void main(String[] args) {\n        int failures = 0;\n");
        for (Entry e : plan.entries) sb.append("        failures += check_").append(e.className).append("();\n");
        sb.append("        if (failures > 0) {\n")
          .append("            throw new IllegalStateException(\"Test de conformite des types a responsabilite du developpeur : \" + failures\n")
          .append("                    + \" echec(s). Comparez votre code avec la classe de secours (dossier fallback).\");\n        }\n");
        sb.append("        System.out.println(\"Test de conformite des types a responsabilite du developpeur : OK (")
          .append(plan.entries.size()).append(" type(s)).\");\n    }\n\n");

        for (Entry e : plan.entries) {
            String cls = e.className;
            UserTypeLayout lay = e.layout;
            int boundary = e.boundary;
            sb.append("    private static int check_").append(cls).append("() {\n");
            sb.append("        final String name = \"").append(e.typeName).append("\";\n");
            sb.append("        int failures = 0;\n");
            sb.append("        if (HLAAlignment.octetBoundary(").append(cls).append(".class) != ").append(boundary).append(") {\n")
              .append("            failures += fail(name, \"alignement enregistre = \" + HLAAlignment.octetBoundary(").append(cls)
              .append(".class) + \", attendu ").append(boundary).append(" (octet boundary de la FOM)\");\n        }\n");
            if (lay.simulatable) {
                sb.append("        final int size = ").append(lay.size).append(";\n");
                sb.append("        final long[][] slots = new long[][] {\n");
                for (UserTypeLayout.EnumSlot s : lay.enumSlots) {
                    sb.append("            {").append(s.offset()).append("L, ").append(s.size()).append("L, ").append(s.littleEndian() ? 1 : 0).append("L");
                    for (long v : s.values()) sb.append(", ").append(v).append("L");
                    sb.append("},\n");
                }
                sb.append("        };\n");
            }
            sb.append("        Random rnd = new Random(0x5EEDL ^ name.hashCode());\n");
            sb.append("        int done = 0;\n");
            if (!lay.simulatable) sb.append("        boolean partial = false;\n");
            sb.append("        int trials = ").append(lay.simulatable ? "" + TRIALS : "1").append(";\n");
            sb.append("        for (int t = 0; t < trials && failures < 3; t++) {\n");
            sb.append("            byte[] refBytes;\n");
            sb.append("            try {\n");
            if (lay.simulatable) {
                sb.append("                byte[] input = new byte[size];\n");
                sb.append("                rnd.nextBytes(input);\n");
                sb.append("                patchEnums(input, rnd, slots);\n");
                sb.append("                Ref_").append(cls).append(" rv = Ref_").append(cls).append(".decodeAt(input, new HLADecoding.Offset());\n");
            } else {
                sb.append("                Ref_").append(cls).append(" rv = new Ref_").append(cls).append("();\n");
            }
            sb.append("                ByteArrayOutputStream rb = new ByteArrayOutputStream();\n");
            sb.append("                Ref_").append(cls).append(".encodeInto(rb, rv);\n");
            sb.append("                refBytes = rb.toByteArray();\n");
            sb.append("            } catch (RuntimeException ex) {\n");
            if (lay.simulatable) {
                sb.append("                failures += fail(name, \"la reference n'a pas pu traiter un tampon de test : \" + ex);\n                break;\n");
            } else {
                sb.append("                System.out.println(\"[conformite] \" + name + \" : type non simulable et instance par defaut non encodable (\" + ex\n")
                  .append("                        + \") -> SEUL L'ALIGNEMENT EST VERIFIE.\");\n                partial = true;\n                break;\n");
            }
            sb.append("            }\n");
            if (lay.simulatable) {
                sb.append("            if (refBytes.length != size) {\n")
                  .append("                failures += fail(name, \"la reference encode \" + refBytes.length + \" octets, la FOM en attend \" + size\n")
                  .append("                        + \" (anomalie du generateur : signalez-la)\");\n                break;\n            }\n");
            }
            sb.append("            try {\n");
            sb.append("                HLADecoding.Offset uo = new HLADecoding.Offset();\n");
            sb.append("                ").append(cls).append(" u = HLADecoder.decodeAt(refBytes, uo, ").append(cls).append(".class);\n");
            sb.append("                if (uo.value != refBytes.length) {\n")
              .append("                    failures += fail(name, \"decodeAt a consomme \" + uo.value + \" octet(s) au lieu de \" + refBytes.length);\n")
              .append("                    break;\n                }\n");
            sb.append("                byte[] ub = HLAEncoder.encode(").append(cls).append(".class, u);\n");
            sb.append("                int d = firstDiff(ub, refBytes);\n");
            sb.append("                if (d >= 0) {\n")
              .append("                    failures += fail(name, \"encodeInto different de la reference a l'octet \" + d + \" (\" + describe(ub, refBytes, d) + \")\");\n")
              .append("                    break;\n                }\n");
            sb.append("            } catch (RuntimeException ex) {\n")
              .append("                failures += fail(name, \"exception dans le code du type : \" + ex);\n                break;\n            }\n");
            sb.append("            done++;\n        }\n");
            String scope = scopeNote(lay);
            sb.append("        if (failures == 0").append(lay.simulatable ? "" : " && !partial").append(") {\n            System.out.println(\"[conformite] \" + name + \" : OK (\" + done + \" tirage(s), alignement \" + HLAAlignment.octetBoundary(")
              .append(cls).append(".class) + \"").append(scope).append(").\");\n        }\n");
            sb.append("        return failures;\n    }\n\n");
        }

        sb.append("    private static int fail(String name, String message) {\n")
          .append("        System.err.println(\"[ECHEC conformite] \" + name + \" : \" + message);\n        return 1;\n    }\n\n");
        sb.append("    private static int firstDiff(byte[] a, byte[] b) {\n")
          .append("        int n = Math.min(a.length, b.length);\n")
          .append("        for (int i = 0; i < n; i++) {\n            if (a[i] != b[i]) return i;\n        }\n")
          .append("        return a.length == b.length ? -1 : n;\n    }\n\n");
        sb.append("    private static String describe(byte[] actual, byte[] expected, int index) {\n")
          .append("        String a = index < actual.length ? String.format(\"0x%02x\", actual[index] & 0xFF) : \"fin\";\n")
          .append("        String e = index < expected.length ? String.format(\"0x%02x\", expected[index] & 0xFF) : \"fin\";\n")
          .append("        return \"obtenu \" + a + \", attendu \" + e + \" ; longueurs \" + actual.length + \" / \" + expected.length;\n    }\n\n");
        sb.append("    /** Ecrit, a chaque emplacement d'enumeration, une valeur valide tiree au hasard. */\n");
        sb.append("    private static void patchEnums(byte[] buf, Random rnd, long[][] slots) {\n")
          .append("        for (long[] s : slots) {\n")
          .append("            int offset = (int) s[0];\n            int size = (int) s[1];\n            boolean le = s[2] == 1;\n")
          .append("            long v = s[3 + rnd.nextInt(s.length - 3)];\n")
          .append("            for (int i = 0; i < size; i++) {\n")
          .append("                int shift = 8 * (le ? i : size - 1 - i);\n")
          .append("                buf[offset + i] = (byte) (v >>> shift);\n            }\n        }\n    }\n}\n");
        return sb.toString();
    }

    // ====================================================================
    // C++
    // ====================================================================

    public static void writeCpp(Plan plan, CppGenerator gen, Path outCpp, UserTypeOptions o) throws IOException {
        Manifest m = new Manifest(outCpp);
        m.deletePrevious();

        Path fallbackDir = (o.fallbackDir != null ? o.fallbackDir.resolve("cpp") : outCpp.resolve("fallback")).resolve("usertypes");
        Path includeDir = outCpp.resolve("include").resolve("rprfom");
        Path destDir = includeDir.resolve("usertypes");

        for (Entry e : plan.entries) {
            String fbHeader = gen.generateUserTypeHeader(e.typeName, e.fingerprint);
            String fbCodec = gen.generateUserTypeCodec(e.typeName, e.fingerprint);
            m.write(fallbackDir.resolve(e.className + ".hpp"), fbHeader, plan);
            m.write(fallbackDir.resolve(e.className + "Codec.hpp"), fbCodec, plan);

            boolean custom = e.cppSource == Source.CUSTOM;
            boolean override = custom || e.cppSource == Source.BUILTIN;
            if (custom) {
                Path destHeader = destDir.resolve(e.className + ".hpp");
                if (e.cppHeaderFile.toAbsolutePath().normalize().equals(destHeader.toAbsolutePath().normalize())) {
                    plan.warnings.add("Type '" + e.typeName + "' [C++] : le fichier fourni EST le fichier de destination (" + destHeader
                            + "). Gardez vos sources dans un dossier a part : les dossiers generes peuvent etre nettoyes.");
                }
            }
            m.write(destDir.resolve(e.className + ".hpp"), override ? e.cppHeaderText : fbHeader, plan);
            m.write(destDir.resolve(e.className + "Codec.hpp"), override ? e.cppCodecText : fbCodec, plan);
        }
        if (!plan.entries.isEmpty()) {
            m.write(includeDir.resolve("runtime").resolve("HLAUserCodecs.hpp"), gen.generateHLAUserCodecs(), plan);
            if (!o.skipConformanceTest) {
                m.write(outCpp.resolve("test").resolve("UserTypesConformance.cpp"), cppCheckSource(plan, gen), plan);
            }
        }
        m.save();
    }

    private static String cppCheckSource(Plan plan, CppGenerator gen) {
        StringBuilder sb = new StringBuilder();
        sb.append("//\n// UserTypesConformance.cpp -- GENERE. Verifie que le code de chaque type a responsabilite du\n");
        sb.append("// developpeur (le sien ou la classe de secours) encode/decode EXACTEMENT comme le code standard\n");
        sb.append("// genere depuis la FOM (Ref_*) : memes octets, memes tailles consommees, alignement conforme.\n");
        sb.append("// ").append(TRIALS).append(" tampons pseudo-aleatoires par type (enumerations valides ; tableaux variables et chaines testes VIDES ;\n");
        sb.append("// variants testes sur leur 1re alternative) ; type non simulable : instance par defaut seulement (controle partiel).\n//\n");
        sb.append("// Compilation (depuis le dossier de sortie C++) :\n");
        sb.append("//     g++ -std=c++17 -Iinclude test/UserTypesConformance.cpp -o user_types_conformance && ./user_types_conformance\n");
        sb.append("// Code de retour non nul si un ecart est detecte.\n//\n\n");
        sb.append("#include <cstdint>\n#include <cstdio>\n#include <exception>\n#include <functional>\n#include <random>\n#include <string>\n#include <vector>\n\n");
        sb.append("#include \"rprfom/HLATypes.hpp\"\n#include \"rprfom/runtime/HLAAlignment.hpp\"\n");
        sb.append("#include \"rprfom/runtime/HLADecoder.hpp\"\n#include \"rprfom/runtime/HLAEncoder.hpp\"\n\n");
        sb.append("using namespace rprfom;\n\nnamespace {\n\n");
        sb.append("struct EnumSlot {\n    size_t offset;\n    size_t size;\n    bool littleEndian;\n    std::vector<uint64_t> values;\n};\n\n");
        sb.append("int fail(const char* name, const std::string& message) {\n")
          .append("    std::fprintf(stderr, \"[ECHEC conformite] %s : %s\\n\", name, message.c_str());\n    return 1;\n}\n\n");
        sb.append("long firstDiff(const std::vector<uint8_t>& a, const std::vector<uint8_t>& b) {\n")
          .append("    size_t n = a.size() < b.size() ? a.size() : b.size();\n")
          .append("    for (size_t i = 0; i < n; ++i) {\n        if (a[i] != b[i]) return static_cast<long>(i);\n    }\n")
          .append("    return a.size() == b.size() ? -1 : static_cast<long>(n);\n}\n\n");
        sb.append("/** Ecrit, a chaque emplacement d'enumeration, une valeur valide tiree au hasard. */\n");
        sb.append("void patchEnums(std::vector<uint8_t>& buf, std::mt19937_64& rng, const std::vector<EnumSlot>& slots) {\n")
          .append("    for (const auto& s : slots) {\n")
          .append("        uint64_t v = s.values[static_cast<size_t>(rng() % s.values.size())];\n")
          .append("        for (size_t i = 0; i < s.size; ++i) {\n")
          .append("            size_t shift = 8 * (s.littleEndian ? i : s.size - 1 - i);\n")
          .append("            buf[s.offset + i] = static_cast<uint8_t>(v >> shift);\n        }\n    }\n}\n\n");

        for (Entry e : plan.entries) {
            sb.append(gen.generateUserTypeReference(e.typeName));
        }

        for (Entry e : plan.entries) {
            String cls = e.className;
            UserTypeLayout lay = e.layout;
            sb.append("int check_").append(cls).append("() {\n");
            sb.append("    const char* name = \"").append(e.typeName).append("\";\n");
            sb.append("    int failures = 0;\n");
            sb.append("    if (HLAOctetBoundary<").append(cls).append(">::value != ").append(e.boundary).append(") {\n")
              .append("        failures += fail(name, \"alignement = \" + std::to_string(HLAOctetBoundary<").append(cls)
              .append(">::value) + \", attendu ").append(e.boundary).append(" (octet boundary de la FOM)\");\n    }\n");
            if (lay.simulatable) {
                sb.append("    const size_t size = ").append(lay.size).append(";\n");
                sb.append("    const std::vector<EnumSlot> slots = {\n");
                for (UserTypeLayout.EnumSlot s : lay.enumSlots) {
                    sb.append("        {").append(s.offset()).append(", ").append(s.size()).append(", ").append(s.littleEndian() ? "true" : "false").append(", {");
                    for (int i = 0; i < s.values().length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(s.values()[i] < 0 ? "static_cast<uint64_t>(" + s.values()[i] + "LL)" : s.values()[i] + "ULL");
                    }
                    sb.append("}},\n");
                }
                sb.append("    };\n");
            }
            sb.append("    std::mt19937_64 rng(0x5EEDULL ^ std::hash<std::string>{}(name));\n");
            sb.append("    int done = 0;\n");
            sb.append("    const int trials = ").append(lay.simulatable ? "" + TRIALS : "1").append(";\n");
            sb.append("    for (int t = 0; t < trials && failures < 3; ++t) {\n");
            sb.append("        std::vector<uint8_t> refBytes;\n");
            sb.append("        Ref_").append(cls).append(" rv{};\n");
            if (lay.simulatable) {
                sb.append("        std::vector<uint8_t> input(size);\n");
                sb.append("        for (auto& x : input) x = static_cast<uint8_t>(rng());\n");
                sb.append("        patchEnums(input, rng, slots);\n");
                sb.append("        size_t ro = 0;\n");
                sb.append("        rv = ref_decode_").append(cls).append("(input, ro);\n");
            }
            sb.append("        ref_encode_").append(cls).append("(refBytes, rv);\n");
            if (lay.simulatable) {
                sb.append("        if (refBytes.size() != size) {\n")
                  .append("            failures += fail(name, \"la reference encode \" + std::to_string(refBytes.size()) + \" octets, la FOM en attend \"\n")
                  .append("                                   + std::to_string(size) + \" (anomalie du generateur : signalez-la)\");\n            break;\n        }\n");
            }
            sb.append("        try {\n");
            sb.append("            size_t uo = 0;\n");
            sb.append("            ").append(cls).append(" u = HLADecoder<").append(cls).append(">::decodeAt(refBytes, uo);\n");
            sb.append("            if (uo != refBytes.size()) {\n")
              .append("                failures += fail(name, \"decodeAt a consomme \" + std::to_string(uo) + \" octet(s) au lieu de \" + std::to_string(refBytes.size()));\n")
              .append("                break;\n            }\n");
            sb.append("            std::vector<uint8_t> ub = HLAEncoder<").append(cls).append(">::encode(u);\n");
            sb.append("            long d = firstDiff(ub, refBytes);\n");
            sb.append("            if (d >= 0) {\n")
              .append("                failures += fail(name, \"encodeInto different de la reference a l'octet \" + std::to_string(d)\n")
              .append("                                       + \" ; longueurs \" + std::to_string(ub.size()) + \" / \" + std::to_string(refBytes.size()));\n")
              .append("                break;\n            }\n");
            sb.append("        } catch (const std::exception& ex) {\n")
              .append("            failures += fail(name, std::string(\"exception dans le code du type : \") + ex.what());\n            break;\n        }\n");
            sb.append("        ++done;\n    }\n");
            String scopeCpp = scopeNote(lay);
            sb.append("    if (failures == 0) {\n        std::printf(\"[conformite] %s : OK (%d tirage(s), alignement %zu").append(scopeCpp).append(").\\n\", name, done, HLAOctetBoundary<")
              .append(cls).append(">::value);\n    }\n");
            sb.append("    return failures;\n}\n\n");
        }

        sb.append("} // namespace\n\nint main() {\n    int failures = 0;\n");
        for (Entry e : plan.entries) sb.append("    failures += check_").append(e.className).append("();\n");
        sb.append("    if (failures > 0) {\n")
          .append("        std::fprintf(stderr, \"Test de conformite : %d echec(s). Comparez votre code avec la classe de secours (dossier fallback).\\n\", failures);\n")
          .append("        return 1;\n    }\n");
        sb.append("    std::printf(\"Test de conformite des types a responsabilite du developpeur : OK (%d type(s)).\\n\", ")
          .append(plan.entries.size()).append(");\n    return 0;\n}\n");
        return sb.toString();
    }

    // ====================================================================
    // Rapport
    // ====================================================================

    private static String sourceLabel(Source s) {
        return switch (s) {
            case CUSTOM -> "CUSTOM";
            case BUILTIN -> "INTEGRE";
            case FALLBACK -> "SECOURS";
        };
    }

    public static void printReport(Plan plan, UserTypeOptions o, Path outJava, Path outCpp) {
        System.out.println();
        System.out.println("Types a responsabilite du developpeur :");
        int w = "Type".length();
        for (Entry e : plan.entries) w = Math.max(w, e.typeName.length());
        System.out.println(String.format("  %-" + w + "s  %-10s  %-10s  %s", "Type", "Java", "C++", "Empreinte FOM"));
        for (Entry e : plan.entries) {
            System.out.println(String.format("  %-" + w + "s  %-10s  %-10s  %s", e.typeName,
                    sourceLabel(e.javaSource), sourceLabel(e.cppSource), e.fingerprint));
            if (e.javaSource == Source.CUSTOM) System.out.println("      Java : " + e.javaFile);
            if (e.javaSource == Source.BUILTIN) System.out.println("      Java : (type integre au generateur)");
            if (e.cppSource == Source.CUSTOM) System.out.println("      C++  : " + e.cppHeaderFile + " + " + e.cppCodecFile);
            if (e.cppSource == Source.BUILTIN) System.out.println("      C++  : (type integre au generateur)");
            for (String n : e.notes) System.out.println("      note : " + n);
        }
        Path fbJava = o.fallbackDir != null ? o.fallbackDir.resolve("java") : outJava.resolve("fallback");
        Path fbCpp = (o.fallbackDir != null ? o.fallbackDir.resolve("cpp") : outCpp.resolve("fallback")).resolve("usertypes");
        System.out.println("  Classes de secours (toujours regenerees, modele a copier pour personnaliser) :");
        System.out.println("      Java : " + fbJava);
        System.out.println("      C++  : " + fbCpp);
        if (!o.skipConformanceTest) {
            System.out.println("  Tests de conformite generes :");
            System.out.println("      Java : " + outJava.resolve("src/test/java") + " (" + "conformance/UserTypesConformanceCheck, lance par 'mvn test')");
            System.out.println("      C++  : " + outCpp.resolve("test/UserTypesConformance.cpp"));
        }
    }
}
