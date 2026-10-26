package rprfom.codegen.usertype;

import rprfom.codegen.generate.TypeMapper;
import rprfom.codegen.model.DataType;
import rprfom.codegen.model.FomModel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Empreinte de la definition FOM d'un type (nom, champs, ordre, types
 * imbriques resolus recursivement jusqu'aux representations de base). Le
 * fichier fourni par le developpeur doit declarer l'empreinte de la
 * definition pour laquelle il a ete ecrit ; si la FOM evolue, l'empreinte
 * change et la copie perimee est detectee (cf. UserTypeResolver) au lieu
 * de produire silencieusement un encodage qui ne suit plus la FOM.
 *
 * Marqueur reconnu dans un commentaire, en Java comme en C++ :
 * {@code @rprfom-fom-fingerprint: 0123456789abcdef}
 */
public final class UserTypeFingerprint {

    private static final Pattern MARKER = Pattern.compile("@rprfom-fom-fingerprint:\\s*([0-9a-fA-F]{8,64})");

    private UserTypeFingerprint() {
    }

    public static String compute(FomModel model, TypeMapper mapper, String typeName) {
        StringBuilder sb = new StringBuilder();
        describe(model, mapper, typeName, sb, new HashSet<>());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", digest[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Empreinte declaree dans le texte d'un fichier fourni, ou null si absente. */
    public static String declaredIn(String fileContent) {
        Matcher m = MARKER.matcher(fileContent);
        return m.find() ? m.group(1).toLowerCase() : null;
    }

    private static void describe(FomModel model, TypeMapper mapper, String name, StringBuilder sb, Set<String> visiting) {
        TypeMapper.BasicRepr basic = mapper.resolveBasicRepr(name);
        if (basic != null) {
            sb.append("basic:").append(basic.representation).append(';');
            return;
        }
        DataType dt = model.dataType(name);
        if (dt == null) {
            sb.append("unknown:").append(name).append(';');
            return;
        }
        if (!visiting.add(name)) {
            sb.append("cycle:").append(name).append(';');
            return;
        }
        if (dt instanceof DataType.Enumerated e) {
            TypeMapper.BasicRepr u = mapper.resolveEnumUnderlying(e);
            sb.append("enum:").append(name).append(':').append(u != null ? u.representation : "?").append(';');
        } else if (dt instanceof DataType.FixedRecord r) {
            sb.append("rec:").append(name).append('{');
            for (DataType.FixedRecord.Field f : r.fields) {
                sb.append(f.name).append('=');
                describe(model, mapper, f.dataTypeName, sb, visiting);
            }
            sb.append('}');
        } else if (dt instanceof DataType.Array a) {
            sb.append("arr:").append(a.encoding).append(':').append(a.fixedCardinality).append('<');
            describe(model, mapper, a.elementDataTypeName, sb, visiting);
            sb.append('>');
        } else if (dt instanceof DataType.VariantRecord v) {
            sb.append("var:").append(name).append('{').append(v.discriminantName).append('=');
            describe(model, mapper, v.discriminantDataTypeName, sb, visiting);
            for (DataType.VariantRecord.Alternative alt : v.alternatives) {
                sb.append(alt.enumerator).append(':').append(alt.fieldName).append('=');
                describe(model, mapper, alt.dataTypeName, sb, visiting);
            }
            sb.append('}');
        } else if (dt instanceof DataType.Simple s) {
            sb.append("simple:").append(name).append("->").append(s.representation).append(';');
        }
        visiting.remove(name);
    }
}
