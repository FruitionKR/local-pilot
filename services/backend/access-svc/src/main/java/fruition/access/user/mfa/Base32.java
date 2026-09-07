package fruition.access.user.mfa;

/**
 * RFC 4648 base32 인코딩. otpauth URI의 secret 파라미터가 base32를 요구하는데
 * JDK에는 base32가 없다. 인증 앱이 읽을 문자열을 만드는 용도라 인코딩만 있으면 된다.
 */
final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {}

    static String encode(byte[] bytes) {
        StringBuilder encoded = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte current : bytes) {
            buffer = (buffer << 8) | (current & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                encoded.append(ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1f));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            encoded.append(ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1f));
        }
        // 인증 앱은 padding 없는 형태를 그대로 받아들인다.
        return encoded.toString();
    }
}
