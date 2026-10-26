package rprfom;

import java.io.ByteArrayOutputStream;

import static rprfom.HLATypes.*;
import rprfom.runtime.*;

/**
 * WorldLocationStruct.java -- EXEMPLE de type FOM a RESPONSABILITE DU DEVELOPPEUR.
 *
 * Position geocentrique (ECEF, metres) de la FOM RPR, enrichie d'accesseurs
 * WGS84 latitude / longitude / altitude ellipsoidale.
 *
 * Principe a retenir : l'ETAT reste les champs de la FOM (x, y, z) et c'est
 * ce qui est encode/decode, exactement comme la classe de secours. Les
 * conversions WGS84 sont des methodes CALCULEES a partir de x, y, z. Ainsi
 * l'encodage reste identique bit a bit a celui de la FOM (le test de
 * conformite verifie encode(decode(octets)) == octets). Une classe qui
 * stockerait latitude/longitude/altitude comme etat ne passerait pas ce test :
 * la conversion ECEF -> WGS84 -> ECEF n'est pas exacte a l'octet pres.
 *
 * Utilisation :
 * <pre>
 *   WorldLocationStruct p = WorldLocationStruct.fromGeodetic(48.8566, 2.3522, 35.0);
 *   double lat = p.latitudeDeg();
 * </pre>
 *
 * @rprfom-fom-fingerprint: 80bf448eeef3f1f4
 */
public final class WorldLocationStruct {

    // WGS84
    private static final double A = 6378137.0;
    private static final double F = 1.0 / 298.257223563;
    private static final double E2 = F * (2.0 - F);

    // --- Champs de la FOM (seuls ces champs passent sur le fil) ---
    public double x;
    public double y;
    public double z;

    public WorldLocationStruct() {
    }

    // --- Services personnalises : WGS84 ---

    /** Construit une position depuis latitude/longitude (degres) et altitude ellipsoidale WGS84 (metres). */
    public static WorldLocationStruct fromGeodetic(double latitudeDeg, double longitudeDeg, double altitudeM) {
        double lat = Math.toRadians(latitudeDeg);
        double lon = Math.toRadians(longitudeDeg);
        double sinLat = Math.sin(lat);
        double n = A / Math.sqrt(1.0 - E2 * sinLat * sinLat);
        WorldLocationStruct p = new WorldLocationStruct();
        p.x = (n + altitudeM) * Math.cos(lat) * Math.cos(lon);
        p.y = (n + altitudeM) * Math.cos(lat) * Math.sin(lon);
        p.z = (n * (1.0 - E2) + altitudeM) * sinLat;
        return p;
    }

    public double longitudeDeg() {
        return Math.toDegrees(Math.atan2(y, x));
    }

    public double latitudeDeg() {
        return geodetic()[0];
    }

    /** Altitude au-dessus de l'ellipsoide WGS84 (pas au-dessus du niveau moyen de la mer). */
    public double altitudeM() {
        return geodetic()[1];
    }

    /** {latitude en degres, altitude en metres} par iterations (converge en quelques tours). */
    private double[] geodetic() {
        double p = Math.hypot(x, y);
        double lat = Math.atan2(z, p * (1.0 - E2));
        double h = 0.0;
        for (int i = 0; i < 10; i++) {
            double sinLat = Math.sin(lat);
            double n = A / Math.sqrt(1.0 - E2 * sinLat * sinLat);
            h = Math.abs(Math.cos(lat)) > 1e-9 ? p / Math.cos(lat) - n : Math.abs(z) / Math.abs(sinLat) - n * (1.0 - E2);
            lat = Math.atan2(z, p * (1.0 - E2 * n / (n + h)));
        }
        return new double[] {Math.toDegrees(lat), h};
    }

    // --- Encodage/decodage HLA : UNIQUEMENT les champs de la FOM ---

    public static void encodeInto(ByteArrayOutputStream b, WorldLocationStruct v) {
        HLAEncoding.appendFloat64BE(b, v.x);
        HLAEncoding.appendFloat64BE(b, v.y);
        HLAEncoding.appendFloat64BE(b, v.z);
    }

    public static WorldLocationStruct decodeAt(byte[] bytes, HLADecoding.Offset offset) {
        WorldLocationStruct v = new WorldLocationStruct();
        v.x = HLADecoding.readFloat64BE(bytes, offset);
        v.y = HLADecoding.readFloat64BE(bytes, offset);
        v.z = HLADecoding.readFloat64BE(bytes, offset);
        return v;
    }
}
