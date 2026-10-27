package rprfom.codegen.usertype;

import rprfom.codegen.generate.NameUtils;
import rprfom.codegen.generate.TypeMapper;
import rprfom.codegen.model.DataType;
import rprfom.codegen.model.FomModel;
import rprfom.codegen.model.InteractionClassNode;
import rprfom.codegen.model.ObjectClassNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Valide les types declares "a responsabilite du developpeur" et decide,
 * pour chaque langage, si c'est le fichier du developpeur (CUSTOM) ou la
 * classe de secours generee (FALLBACK) qui sera copiee a la destination.
 *
 * Toutes les verifications se font AVANT toute ecriture : une erreur
 * (Plan.errors non vide) doit interrompre la generation sans rien
 * modifier. Les fichiers fournis sont lus en memoire ici, pour qu'un
 * fichier source situe dans le dossier de destination puisse etre
 * reecrit sans etre perdu.
 *
 * Priorite de resolution : fichier explicite (--user-type-java/cpp),
 * puis convention de dossier (--user-types-java/cpp), puis type integre
 * au generateur ({@link BuiltinUserTypes}, ressources embarquees), puis
 * classe de secours.
 */
public final class UserTypeResolver {

    public enum Source { CUSTOM, BUILTIN, FALLBACK }

    /** Resultat de resolution pour UN type. */
    public static final class Entry {
        public final String typeName;
        public final String className;      // identifiant Java/C++ du type
        public final String fingerprint;
        public final UserTypeLayout layout;
        /** Octet boundary (alignement) du type selon la FOM. */
        public final int boundary;
        /**
         * true si ce type a ete declare explicitement en ligne de commande
         * (--user-type ou equivalent), false s'il provient uniquement du
         * registre des types integres au generateur ({@link BuiltinUserTypes}).
         * Determine la severite des verifications en {@link #resolve} / notFound :
         * une anomalie sur un type integre ne declenche jamais --strict-user-types
         * ni une erreur bloquante, ce mecanisme devant fonctionner sans
         * configuration sur n'importe quel FOM.
         */
        public boolean explicitlyDeclared = true;

        public Source javaSource = Source.FALLBACK;
        public Path javaFile;
        public String javaText;             // contenu du fichier custom (lu en memoire)

        public Source cppSource = Source.FALLBACK;
        public Path cppHeaderFile;
        public Path cppCodecFile;
        public String cppHeaderText;
        public String cppCodecText;

        public final List<String> notes = new ArrayList<>();

        Entry(String typeName, String fingerprint, UserTypeLayout layout, int boundary) {
            this.typeName = typeName;
            this.boundary = boundary;
            this.className = NameUtils.sanitizeIdentifier(typeName);
            this.fingerprint = fingerprint;
            this.layout = layout;
        }
    }

    public static final class Plan {
        public final List<Entry> entries = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public Set<String> typeNames() {
            Set<String> names = new LinkedHashSet<>();
            for (Entry e : entries) names.add(e.typeName);
            return names;
        }
    }

    private UserTypeResolver() {
    }

    /**
     * @param model              modele APRES filtrage federe (--federate-fom)
     * @param knownBeforeFilter  noms de types du modele AVANT filtrage (pour distinguer faute de frappe / type filtre)
     */
    public static Plan resolve(FomModel model, Set<String> knownBeforeFilter, UserTypeOptions o, String javaPackage) {
        Plan plan = new Plan();
        List<String> builtinNames = BuiltinUserTypes.registeredNames();
        if (!o.isActive() && builtinNames.isEmpty()) return plan;
        TypeMapper mapper = new TypeMapper(model);

        // Noms de classes deja utilises par les classes d'objet/interaction generees (collision de fichier).
        Set<String> generatedClassNames = new HashSet<>();
        collectObjectNames(model.root, generatedClassNames, true);
        collectInteractionNames(model.interactionRoot, generatedClassNames, true);

        // --- 1) Declarations explicites (CLI) : dedoublonnage + validation STRICTE contre la FOM ---
        Set<String> seen = new LinkedHashSet<>();
        for (String name : o.declared) {
            if (!seen.add(name)) {
                plan.warnings.add("Type '" + name + "' declare plusieurs fois : declaration ignoree.");
                continue;
            }
            addEntry(name, true, model, knownBeforeFilter, generatedClassNames, mapper, plan);
        }

        // --- 1bis) Types integres au generateur (BuiltinUserTypes) : memes types,
        //           mais validation SOUPLE -- jamais d'erreur bloquante pour un type
        //           que le projet n'a jamais demande explicitement (cf. addEntry).
        //           Un nom deja declare explicitement ci-dessus garde la main.
        for (String name : builtinNames) {
            if (!seen.add(name)) continue;
            addEntry(name, false, model, knownBeforeFilter, generatedClassNames, mapper, plan);
        }

        // --- 2) Resolution par langage ---
        for (Entry e : plan.entries) {
            resolveJava(e, o, plan);
            resolveCpp(e, o, plan);
        }

        // --- 3) Fichiers "orphelins" dans les dossiers de convention ---
        Set<String> declaredClasses = new HashSet<>();
        for (String n : seen) declaredClasses.add(NameUtils.sanitizeIdentifier(n));
        warnStrayFiles(o.javaDir, ".java", declaredClasses, false, plan);
        warnStrayFiles(o.cppDir, ".hpp", declaredClasses, true, plan);
        return plan;
    }

    /**
     * Valide un nom de type contre la FOM et, si valide, cree son {@link Entry}.
     * {@code explicitDecl} decide la severite : une declaration CLI explicite
     * (--user-type et equivalents) produit une erreur bloquante sur tout ecart,
     * comme avant ; un type qui ne provient QUE du registre des types integres
     * au generateur ({@link BuiltinUserTypes}) est silencieusement ignore sur le
     * meme ecart (ou, quand c'est utile a la mise au point du generateur,
     * signale par un simple avertissement) -- jamais une erreur qui interromprait
     * la generation d'un projet qui n'a rien demande de particulier.
     */
    private static void addEntry(String name, boolean explicitDecl, FomModel model, Set<String> knownBeforeFilter,
                                  Set<String> generatedClassNames, TypeMapper mapper, Plan plan) {
        if (!knownBeforeFilter.contains(name)) {
            if (explicitDecl) {
                plan.errors.add("Type '" + name + "' inconnu dans la FOM." + suggestion(name, knownBeforeFilter));
            }
            // Type integre non declare explicitement : silencieux, cette FOM ne definit simplement pas ce type.
            return;
        }
        DataType dt = model.dataType(name);
        if (dt == null) {
            if (explicitDecl) {
                plan.warnings.add("Type '" + name + "' non utilise par ce federe (retire par --federate-fom) : "
                        + "declaration ignoree, rien n'est genere ni copie pour lui.");
            }
            return;
        }
        if (!(dt instanceof DataType.FixedRecord)) {
            String msg = "seuls les fixedRecordData peuvent etre geres par le developpeur (ce type est un "
                    + kindOf(dt) + "). Les enumerations, tableaux et variants ont une forme generee particuliere "
                    + "qui n'est pas prise en charge.";
            if (explicitDecl) {
                plan.errors.add("Type '" + name + "' : " + msg);
            } else {
                plan.warnings.add("Type integre au generateur '" + name + "' ignore : " + msg);
            }
            return;
        }
        String cls = NameUtils.sanitizeIdentifier(name);
        if (generatedClassNames.contains(cls)) {
            String msg = "le nom '" + cls + "' est deja utilise par une classe d'objet ou d'interaction generee "
                    + "(collision de fichier).";
            if (explicitDecl) {
                plan.errors.add("Type '" + name + "' : " + msg);
            } else {
                plan.warnings.add("Type integre au generateur '" + name + "' ignore : " + msg);
            }
            return;
        }
        Entry e = new Entry(name, UserTypeFingerprint.compute(model, mapper, name),
                UserTypeLayout.compute(model, mapper, name), mapper.octetBoundaryOf(name));
        e.explicitlyDeclared = explicitDecl;
        plan.entries.add(e);
    }

    // ====================================================================
    // Java
    // ====================================================================

    private static void resolveJava(Entry e, UserTypeOptions o, Plan plan) {
        Path file = o.javaFiles.get(e.typeName);
        boolean explicit = file != null;
        if (file == null && o.javaDir != null) {
            file = o.javaDir.resolve(e.className + ".java");
        }
        if (file != null && Files.isRegularFile(file)) {
            String text = read(file, plan);
            if (text == null) return;

            String cls = Pattern.quote(e.className);
            boolean ok = true;
            if (!Pattern.compile("\\bclass\\s+" + cls + "\\b").matcher(text).find()) {
                plan.errors.add(prefix(e, "Java", file) + "aucune declaration 'class " + e.className + "' dans ce fichier "
                        + "(le nom de la classe doit etre celui du type FOM).");
                ok = false;
            }
            if (!Pattern.compile("static\\s+void\\s+encodeInto\\s*\\(\\s*(?:java\\.io\\.)?ByteArrayOutputStream\\s+\\w+\\s*,\\s*"
                    + cls + "\\s+\\w+\\s*\\)").matcher(text).find()) {
                plan.errors.add(prefix(e, "Java", file) + "methode obligatoire absente : "
                        + "public static void encodeInto(ByteArrayOutputStream buffer, " + e.className + " value).");
                ok = false;
            }
            if (!Pattern.compile("static\\s+" + cls + "\\s+decodeAt\\s*\\(\\s*byte\\s*\\[\\s*\\]\\s+\\w+\\s*,\\s*"
                    + "(?:HLADecoding\\.)?Offset\\s+\\w+\\s*\\)").matcher(text).find()) {
                plan.errors.add(prefix(e, "Java", file) + "methode obligatoire absente : "
                        + "public static " + e.className + " decodeAt(byte[] bytes, HLADecoding.Offset offset).");
                ok = false;
            }
            boolean hasCtor = Pattern.compile("\\b" + cls + "\\s*\\(").matcher(text).find();
            if (hasCtor && !Pattern.compile("\\b" + cls + "\\s*\\(\\s*\\)").matcher(text).find()) {
                plan.errors.add(prefix(e, "Java", file) + "un constructeur est declare mais aucun constructeur sans "
                        + "argument : le code genere fait 'new " + e.className + "()'.");
                ok = false;
            }
            if (!checkFingerprint(e, "Java", file, text, o, plan)) ok = false;
            if (!ok) return;

            e.javaSource = Source.CUSTOM;
            e.javaFile = file;
            e.javaText = text;
            return;
        }

        if (explicit) {
            // Chemin explicite (--user-type-java) mais fichier absent : erreur/avertissement de
            // configuration precis a CE type, jamais de repli silencieux vers autre chose que le
            // secours standard (cf. notFound) -- on ne tente donc pas le type integre ici.
            notFound(e, "Java", file, true, o, plan);
            return;
        }

        // Aucun fichier fourni par la ligne de commande pour ce type/langage (ni explicite,
        // ni par convention) : type integre au generateur, en dernier recours avant le secours.
        if (tryBuiltinJava(e, plan)) return;
        notFound(e, "Java", null, false, o, plan);
    }

    /**
     * Tente le recouvrement Java integre au generateur ({@link BuiltinUserTypes}) pour ce type,
     * uniquement appele quand aucun fichier CLI n'a ete trouve pour lui. Validation souple :
     * tout ecart de contrat ou d'empreinte desactive silencieusement (ou avec un simple
     * avertissement) ce recouvrement pour CE type -- jamais une erreur bloquante.
     */
    private static boolean tryBuiltinJava(Entry e, Plan plan) {
        String text = BuiltinUserTypes.readJava(e.className);
        if (text == null) return false;

        String cls = Pattern.quote(e.className);
        boolean contractOk = Pattern.compile("\\bclass\\s+" + cls + "\\b").matcher(text).find()
                && Pattern.compile("static\\s+void\\s+encodeInto\\s*\\(\\s*(?:java\\.io\\.)?ByteArrayOutputStream\\s+\\w+\\s*,\\s*"
                        + cls + "\\s+\\w+\\s*\\)").matcher(text).find()
                && Pattern.compile("static\\s+" + cls + "\\s+decodeAt\\s*\\(\\s*byte\\s*\\[\\s*\\]\\s+\\w+\\s*,\\s*"
                        + "(?:HLADecoding\\.)?Offset\\s+\\w+\\s*\\)").matcher(text).find();
        if (!contractOk) {
            plan.warnings.add("Type integre au generateur '" + e.typeName + "' [Java] ignore : fichier integre "
                    + "invalide (contrat non respecte) -- anomalie du generateur a signaler. Generation standard utilisee.");
            return false;
        }
        String declared = UserTypeFingerprint.declaredIn(text);
        if (declared == null || !declared.equals(e.fingerprint)) {
            plan.warnings.add("Type integre au generateur '" + e.typeName + "' [Java] ignore : son empreinte FOM "
                    + "(" + (declared == null ? "absente" : declared) + ") ne correspond pas a la definition de ce "
                    + "type dans cette FOM (" + e.fingerprint + ") -- generation standard utilisee pour ce projet.");
            return false;
        }
        e.javaSource = Source.BUILTIN;
        e.javaText = text;
        return true;
    }

    // ====================================================================
    // C++
    // ====================================================================

    private static void resolveCpp(Entry e, UserTypeOptions o, Plan plan) {
        Path header = null;
        Path codec = null;
        boolean explicit = false;
        Path[] pair = o.cppFiles.get(e.typeName);
        if (pair != null) {
            explicit = true;
            header = pair[0];
            codec = pair[1] != null ? pair[1] : header.resolveSibling(e.className + "Codec.hpp");
        } else if (o.cppDir != null) {
            header = o.cppDir.resolve(e.className + ".hpp");
            codec = o.cppDir.resolve(e.className + "Codec.hpp");
        }

        boolean hasHeader = header != null && Files.isRegularFile(header);
        boolean hasCodec = codec != null && Files.isRegularFile(codec);

        if (hasHeader && hasCodec) {
            String headerText = read(header, plan);
            String codecText = read(codec, plan);
            if (headerText == null || codecText == null) return;

            boolean ok = true;
            String cls = Pattern.quote(e.className);
            if (!Pattern.compile("\\b(?:struct|class)\\s+" + cls + "\\b").matcher(headerText).find()) {
                plan.errors.add(prefix(e, "C++", header) + "aucune declaration 'struct " + e.className + "' dans ce fichier.");
                ok = false;
            }
            if (!Pattern.compile("namespace\\s+rprfom\\b").matcher(headerText).find()) {
                plan.errors.add(prefix(e, "C++", header) + "la struct doit etre declaree dans 'namespace rprfom'.");
                ok = false;
            }
            if (!Pattern.compile("HLAEncoderDetail\\s*<\\s*" + cls + "\\s*>\\s*::\\s*encodeInto").matcher(codecText).find()) {
                plan.errors.add(prefix(e, "C++", codec) + "definition obligatoire absente : "
                        + "inline void HLAEncoderDetail<" + e.className + ">::encodeInto(std::vector<uint8_t>& b, const "
                        + e.className + "& v).");
                ok = false;
            }
            if (!Pattern.compile("HLADecoderDetail\\s*<\\s*" + cls + "\\s*>\\s*::\\s*decodeAt").matcher(codecText).find()) {
                plan.errors.add(prefix(e, "C++", codec) + "definition obligatoire absente : inline " + e.className
                        + " HLADecoderDetail<" + e.className + ">::decodeAt(const std::vector<uint8_t>& bytes, size_t& offset).");
                ok = false;
            }
            if (!checkFingerprint(e, "C++", header, headerText, o, plan)) ok = false;
            if (!ok) return;

            e.cppSource = Source.CUSTOM;
            e.cppHeaderFile = header;
            e.cppCodecFile = codec;
            e.cppHeaderText = headerText;
            e.cppCodecText = codecText;
            return;
        }

        if (hasHeader != hasCodec) {
            plan.errors.add(prefix(e, "C++", hasHeader ? codec : header) + "fichier manquant : un type C++ se fournit en "
                    + "DEUX fichiers (" + e.className + ".hpp = la struct, " + e.className + "Codec.hpp = encodage/decodage). "
                    + "Fournir un seul des deux est presque toujours une erreur.");
            return;
        }

        // Ni header ni codec : soit header==null (aucun mecanisme CLI configure pour ce type),
        // soit les deux sont absents du chemin CLI trouve.
        if (explicit) {
            notFound(e, "C++", header, true, o, plan);
            return;
        }
        if (tryBuiltinCpp(e, plan)) return;
        notFound(e, "C++", header, false, o, plan);
    }

    /**
     * Tente le recouvrement C++ integre au generateur ({@link BuiltinUserTypes}) pour ce type,
     * uniquement appele quand aucun fichier CLI n'a ete trouve pour lui. Meme validation souple
     * que {@link #tryBuiltinJava} : tout ecart desactive silencieusement (ou avec un simple
     * avertissement) ce recouvrement pour CE type, jamais une erreur bloquante.
     */
    private static boolean tryBuiltinCpp(Entry e, Plan plan) {
        String[] files = BuiltinUserTypes.readCpp(e.className);
        if (files == null) return false;
        String headerText = files[0];
        String codecText = files[1];

        String cls = Pattern.quote(e.className);
        boolean contractOk = Pattern.compile("\\b(?:struct|class)\\s+" + cls + "\\b").matcher(headerText).find()
                && Pattern.compile("namespace\\s+rprfom\\b").matcher(headerText).find()
                && Pattern.compile("HLAEncoderDetail\\s*<\\s*" + cls + "\\s*>\\s*::\\s*encodeInto").matcher(codecText).find()
                && Pattern.compile("HLADecoderDetail\\s*<\\s*" + cls + "\\s*>\\s*::\\s*decodeAt").matcher(codecText).find();
        if (!contractOk) {
            plan.warnings.add("Type integre au generateur '" + e.typeName + "' [C++] ignore : fichiers integres "
                    + "invalides (contrat non respecte) -- anomalie du generateur a signaler. Generation standard utilisee.");
            return false;
        }
        String declared = UserTypeFingerprint.declaredIn(headerText);
        if (declared == null || !declared.equals(e.fingerprint)) {
            plan.warnings.add("Type integre au generateur '" + e.typeName + "' [C++] ignore : son empreinte FOM "
                    + "(" + (declared == null ? "absente" : declared) + ") ne correspond pas a la definition de ce "
                    + "type dans cette FOM (" + e.fingerprint + ") -- generation standard utilisee pour ce projet.");
            return false;
        }
        e.cppSource = Source.BUILTIN;
        e.cppHeaderText = headerText;
        e.cppCodecText = codecText;
        return true;
    }

    // ====================================================================
    // Communs
    // ====================================================================

    /** Empreinte du fichier fourni : perimee = erreur (sauf --allow-stale-user-types), absente = avertissement (erreur si --strict). */
    private static boolean checkFingerprint(Entry e, String lang, Path file, String text, UserTypeOptions o, Plan plan) {
        String declared = UserTypeFingerprint.declaredIn(text);
        if (declared == null) {
            String msg = prefix(e, lang, file) + "aucune empreinte '@rprfom-fom-fingerprint: <hash>' : impossible de verifier que ce "
                    + "fichier suit la definition FOM courante (empreinte actuelle : " + e.fingerprint + ").";
            if (o.strict) {
                plan.errors.add(msg);
                return false;
            }
            plan.warnings.add(msg);
            e.notes.add(lang + " : empreinte non declaree");
            return true;
        }
        if (!declared.equals(e.fingerprint)) {
            String msg = prefix(e, lang, file) + "COPIE PERIMEE : ce fichier a ete ecrit pour l'empreinte " + declared
                    + " mais la definition FOM courante a l'empreinte " + e.fingerprint + ". Comparez avec la classe de secours "
                    + "regeneree (dossier fallback), mettez votre fichier a jour puis changez son empreinte.";
            if (o.allowStale) {
                plan.warnings.add(msg + " (tolere par --allow-stale-user-types)");
                e.notes.add(lang + " : empreinte perimee tolere");
                return true;
            }
            plan.errors.add(msg);
            return false;
        }
        return true;
    }

    /**
     * Fichier absent : secours (avertissement), ou erreur en mode strict. Le mode strict ne
     * s'applique jamais a un type qui ne provient QUE du registre des types integres au
     * generateur ({@code e.explicitlyDeclared == false}) : --strict-user-types sanctionne une
     * declaration explicite du projet, pas un type integre qu'il n'a jamais demande.
     */
    private static void notFound(Entry e, String lang, Path file, boolean explicit, UserTypeOptions o, Plan plan) {
        String where = file != null ? " (" + file + ")" : "";
        if (o.strict && e.explicitlyDeclared) {
            plan.errors.add("Type '" + e.typeName + "' [" + lang + "] : aucun fichier fourni" + where
                    + " et --strict-user-types est actif.");
            return;
        }
        if (explicit) {
            plan.warnings.add("Type '" + e.typeName + "' [" + lang + "] : le fichier indique n'existe pas" + where
                    + " -> classe de secours utilisee.");
        }
    }

    private static String prefix(Entry e, String lang, Path file) {
        return "Type '" + e.typeName + "' [" + lang + "] " + file + " : ";
    }

    private static String read(Path file, Plan plan) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            plan.errors.add("Lecture impossible de " + file + " : " + ex.getMessage());
            return null;
        }
    }

    private static void warnStrayFiles(Path dir, String extension, Set<String> declaredClasses, boolean cpp, Plan plan) {
        if (dir == null) return;
        if (!Files.isDirectory(dir)) {
            plan.warnings.add("Dossier de types utilisateur introuvable : " + dir);
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(extension)).sorted().forEach(p -> {
                String fn = p.getFileName().toString();
                String base = fn.substring(0, fn.length() - extension.length());
                String typeBase = cpp && base.endsWith("Codec") ? base.substring(0, base.length() - "Codec".length()) : base;
                if (!declaredClasses.contains(typeBase) && !declaredClasses.contains(base)) {
                    plan.warnings.add("Fichier '" + p + "' ignore : aucun type declare ne correspond a '" + typeBase
                            + "'. Faute de frappe ? Declarez le type avec --user-type." + suggestion(typeBase, declaredClasses));
                }
            });
        } catch (IOException ex) {
            plan.warnings.add("Lecture du dossier " + dir + " impossible : " + ex.getMessage());
        }
    }

    private static void collectObjectNames(ObjectClassNode node, Set<String> out, boolean isRoot) {
        if (!isRoot) out.add(NameUtils.sanitizeIdentifier(node.name));
        for (ObjectClassNode c : node.children.values()) collectObjectNames(c, out, false);
    }

    private static void collectInteractionNames(InteractionClassNode node, Set<String> out, boolean isRoot) {
        if (!isRoot) out.add(NameUtils.sanitizeIdentifier(node.name));
        for (InteractionClassNode c : node.children.values()) collectInteractionNames(c, out, false);
    }

    private static String kindOf(DataType dt) {
        if (dt instanceof DataType.Enumerated) return "enumeratedData";
        if (dt instanceof DataType.Array) return "arrayData";
        if (dt instanceof DataType.VariantRecord) return "variantRecordData";
        if (dt instanceof DataType.Simple) return "simpleData";
        return "type non supporte";
    }

    /** " Vouliez-vous dire : A, B ?" avec les 3 noms les plus proches (distance d'edition), ou "". */
    static String suggestion(String name, Set<String> candidates) {
        Map<String, Integer> scored = new LinkedHashMap<>();
        for (String c : candidates) {
            int d = distance(name.toLowerCase(), c.toLowerCase());
            if (d <= Math.max(3, name.length() / 3)) scored.put(c, d);
        }
        if (scored.isEmpty()) return "";
        List<String> best = new ArrayList<>(scored.keySet());
        best.sort(Comparator.<String>comparingInt(scored::get).thenComparing(Comparator.<String>naturalOrder()));
        return " Vouliez-vous dire : " + String.join(", ", best.subList(0, Math.min(3, best.size()))) + " ?";
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
