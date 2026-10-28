package rprfom;

/**
 * HLADecoderDetail.java
 *
 * Miroir de HLADecoderDetail&lt;T&gt;::decodeAt (C++). Interface
 * fonctionnelle : chaque type decodable fournit une implementation qui
 * lit depuis le buffer partage a partir d'un Offset mutable (equivalent
 * du "size_t& offset" C++), et l'avance du nombre d'octets consommes.
 */
@FunctionalInterface
public interface HLADecoderDetail<T> {
    T decodeAt(byte[] bytes, HLADecoding.Offset offset);
}
