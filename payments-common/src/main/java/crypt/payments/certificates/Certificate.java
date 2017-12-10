package crypt.payments.certificates;

import crypt.payments.signatures.SignedData;
import crypt.payments.signatures.encoding.Encoder;
import crypt.payments.signatures.rsa.RSAPublicKey;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Certificate implements SignedData {

    private String subjectName;
    private LocalDateTime expirationDate;
    private RSAPublicKey publicKey;

    private byte[] signature;

    @Override
    public byte[] encode() {
        return new Encoder()
                .putString(this.subjectName)
                .putLocalDateTime(this.expirationDate)
                .putBytes(this.publicKey.encode())
                .encode();
    }
}
