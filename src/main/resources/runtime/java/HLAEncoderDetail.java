package rprfom;

import java.io.ByteArrayOutputStream;

/**
 * HLAEncoderDetail.java
 *
 * Miroir de HLAEncoderDetail&lt;T&gt;::encodeInto (C++). Interface
 * fonctionnelle : chaque type publiable fournit une implementation qui
 * ecrit directement dans le buffer PARTAGE recu en parametre (jamais un
 * buffer local recopie ensuite), pour que le padding soit toujours
 * calcule par rapport a la position reelle dans le flux global -- voir
 * la note d'architecture dans HLAEncoder.java.
 */
@FunctionalInterface
public interface HLAEncoderDetail<T> {
    void encodeInto(ByteArrayOutputStream buffer, T value);
}
