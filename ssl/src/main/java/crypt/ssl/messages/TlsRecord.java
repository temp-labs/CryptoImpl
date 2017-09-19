package crypt.ssl.messages;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.nio.ByteBuffer;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class TlsRecord {

    private ContentType type;
    private ProtocolVersion version;
    private ByteBuffer recordBody;
}
