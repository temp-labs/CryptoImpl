package crypt.ssl.testing;

import crypt.ssl.utils.Hex;

import java.util.regex.Pattern;

public class Parser {

    private static final Pattern SPACES = Pattern.compile("\\s+");

    public static byte[] parseSpacedHex(String spacedHexBytes) {
        String[] hexBytes = SPACES.split(spacedHexBytes.trim());
        byte[] bytes = new byte[hexBytes.length];

        for (int i = 0; i < hexBytes.length; i++) {
            bytes[i] = Hex.fromHex(hexBytes[i]);
        }

        return bytes;
    }
}
