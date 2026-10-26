#pragma once
//
// WorldLocationStruct.hpp -- EXEMPLE de type FOM a RESPONSABILITE DU DEVELOPPEUR (C++).
//
// Position geocentrique (ECEF, metres) de la FOM RPR, enrichie d'accesseurs WGS84
// latitude / longitude / altitude ellipsoidale. L'ETAT reste x, y, z (les champs de la FOM) ;
// les conversions sont des methodes calculees. Le codec (WorldLocationStructCodec.hpp) n'encode
// que x, y, z. Voir l'equivalent Java pour la justification (test de conformite exact).
//
// Ne pas inclure ce fichier directement : incluez HLATypes.hpp.
//
// @rprfom-fom-fingerprint: 80bf448eeef3f1f4
//

#include <array>
#include <cmath>
#include <cstdint>
#include <string>
#include <variant>
#include <vector>

namespace rprfom {

struct WorldLocationStruct {
    // --- Champs de la FOM ---
    Float64 x{};
    Float64 y{};
    Float64 z{};

    // --- Services personnalises : WGS84 ---
    static WorldLocationStruct fromGeodetic(double latitudeDeg, double longitudeDeg, double altitudeM) {
        const double a = 6378137.0, f = 1.0 / 298.257223563, e2 = f * (2.0 - f);
        const double deg = 3.14159265358979323846 / 180.0;
        const double lat = latitudeDeg * deg, lon = longitudeDeg * deg;
        const double sinLat = std::sin(lat);
        const double n = a / std::sqrt(1.0 - e2 * sinLat * sinLat);
        WorldLocationStruct p;
        p.x = (n + altitudeM) * std::cos(lat) * std::cos(lon);
        p.y = (n + altitudeM) * std::cos(lat) * std::sin(lon);
        p.z = (n * (1.0 - e2) + altitudeM) * sinLat;
        return p;
    }

    double longitudeDeg() const { return std::atan2(y, x) * 180.0 / 3.14159265358979323846; }

    /** Latitude en degres (WGS84). */
    double latitudeDeg() const { return geodetic().first; }

    /** Altitude au-dessus de l'ellipsoide WGS84 (pas au-dessus du niveau moyen de la mer). */
    double altitudeM() const { return geodetic().second; }

private:
    std::pair<double, double> geodetic() const {
        const double a = 6378137.0, f = 1.0 / 298.257223563, e2 = f * (2.0 - f);
        const double p = std::hypot(x, y);
        double lat = std::atan2(z, p * (1.0 - e2));
        double h = 0.0;
        for (int i = 0; i < 10; ++i) {
            const double sinLat = std::sin(lat);
            const double n = a / std::sqrt(1.0 - e2 * sinLat * sinLat);
            h = std::fabs(std::cos(lat)) > 1e-9 ? p / std::cos(lat) - n
                                                 : std::fabs(z) / std::fabs(sinLat) - n * (1.0 - e2);
            lat = std::atan2(z, p * (1.0 - e2 * n / (n + h)));
        }
        return {lat * 180.0 / 3.14159265358979323846, h};
    }
};

} // namespace rprfom
