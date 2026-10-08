package wallet.util;

import android.app.Activity;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * NFC helpers for the B-Lite payment-request exchange.
 *
 * <p>B-Lite uses a dual NFC transport strategy:
 * <ul>
 *   <li>Schildbach-compatible NDEF MIME PaymentRequest publishing when the platform exposes it.</li>
 *   <li>Modern Host Card Emulation + reader mode for direct B-Lite phone-to-phone exchange.</li>
 *   <li>Direct NDEF reading for tags and other NFC sources.</li>
 * </ul>
 */
public final class Nfc {
    public static final String PAYMENT_AID = "F001424C49544501";
    public static final int HCE_MAX_PAYLOAD = 50_000;
    private static final int HCE_CHUNK_SIZE = 240;
    private static final byte SW1_SUCCESS = (byte) 0x90;
    private static final byte SW2_SUCCESS = 0x00;

    private Nfc() {
    }

    public static NdefRecord createMime(final String mimeType, final byte[] payload) {
        final byte[] mimeBytes = mimeType.getBytes(StandardCharsets.US_ASCII);
        return new NdefRecord(NdefRecord.TNF_MIME_MEDIA, mimeBytes, new byte[0], payload);
    }

    public static byte[] extractMimePayload(final String mimeType, final NdefMessage message) {
        if (message == null) {
            return null;
        }
        final byte[] mimeBytes = mimeType.getBytes(StandardCharsets.US_ASCII);
        for (final NdefRecord record : message.getRecords()) {
            if (record.getTnf() == NdefRecord.TNF_MIME_MEDIA
                    && Arrays.equals(record.getType(), mimeBytes)) {
                return record.getPayload();
            }
        }
        return null;
    }

    /**
     * Publish the Schildbach-compatible NDEF PaymentRequest transport when the platform/OEM
     * still exposes it. The call is intentionally reflective so the same code remains usable
     * across API levels. On newer Android releases this may be unavailable or ignored; the
     * modern HCE transport is published in parallel when supported.
     */
    public static boolean setNdefPushMessage(final NfcAdapter adapter, final NdefMessage message,
                                              final Activity activity) {
        if (adapter == null || message == null || activity == null) {
            return false;
        }
        try {
            final Method setNdefPushMessage = adapter.getClass().getMethod(
                    "setNdefPushMessage", NdefMessage.class, Activity.class, Activity[].class);
            setNdefPushMessage.invoke(adapter, message, activity, new Activity[0]);
            return true;
        } catch (final ReflectiveOperationException ignored) {
            return false;
        } catch (final Exception ignored) {
            return false;
        }
    }

    public static boolean hasModernHceSupport(final android.content.Context context) {
        return Build.VERSION.SDK_INT >= 19
                && context.getPackageManager().hasSystemFeature(
                "android.hardware.nfc.hce");
    }


    /** Read an NDEF message directly while Reader Mode is active. */
    public static NdefMessage readNdefMessage(final Tag tag) throws Exception {
        if (tag == null) {
            throw new IllegalArgumentException("Missing NFC tag");
        }
        final android.nfc.tech.Ndef ndef = android.nfc.tech.Ndef.get(tag);
        if (ndef == null) {
            throw new IllegalArgumentException("NFC tag does not expose NDEF");
        }
        ndef.connect();
        try {
            return ndef.getNdefMessage();
        } finally {
            try {
                ndef.close();
            } catch (final Exception ignored) {
            }
        }
    }

    /** Extract a Bitcoin URI from an NDEF message, if present. */
    public static String extractBitcoinUri(final NdefMessage message) {
        if (message == null) {
            return null;
        }
        for (final NdefRecord record : message.getRecords()) {
            try {
                final android.net.Uri uri = record.toUri();
                if (uri != null && "bitcoin".equalsIgnoreCase(uri.getScheme())) {
                    return uri.toString();
                }
            } catch (final IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    /**
     * Read a B-Lite payment request from the peer's HostApduService.
     * Must be called off the main thread.
     */
    public static byte[] readHcePaymentRequest(final Tag tag) throws Exception {
        if (tag == null) {
            throw new IllegalArgumentException("Missing NFC tag");
        }

        final IsoDep isoDep = IsoDep.get(tag);
        if (isoDep == null) {
            throw new IllegalArgumentException("NFC peer does not expose ISO-DEP");
        }

        isoDep.setTimeout(1_500);
        isoDep.connect();
        try {
            final byte[] select = selectAid(hexToBytes(PAYMENT_AID));
            requireSuccess(transceive(isoDep, select), "NFC service unavailable");

            final byte[] lengthResponse = transceive(isoDep,
                    new byte[]{0x00, (byte) 0xCA, 0x00, 0x00, 0x00});
            final byte[] lengthData = requireSuccess(lengthResponse, "NFC length request failed");
            if (lengthData.length != 4) {
                throw new IllegalStateException("Invalid NFC request length");
            }

            final int length = ((lengthData[0] & 0xff) << 24)
                    | ((lengthData[1] & 0xff) << 16)
                    | ((lengthData[2] & 0xff) << 8)
                    | (lengthData[3] & 0xff);
            if (length <= 0 || length > HCE_MAX_PAYLOAD) {
                throw new IllegalStateException("Invalid NFC request size");
            }

            final ByteArrayOutputStream output = new ByteArrayOutputStream(length);
            int offset = 0;
            while (offset < length) {
                final int chunk = Math.min(HCE_CHUNK_SIZE, length - offset);
                final byte[] command = new byte[]{
                        0x00,
                        (byte) 0xB0,
                        (byte) ((offset >>> 8) & 0xff),
                        (byte) (offset & 0xff),
                        (byte) chunk
                };
                final byte[] response = requireSuccess(transceive(isoDep, command),
                        "NFC data request failed");
                if (response.length != chunk) {
                    throw new IllegalStateException("Invalid NFC data chunk");
                }
                output.write(response);
                offset += chunk;
            }
            return output.toByteArray();
        } finally {
            try {
                isoDep.close();
            } catch (final Exception ignored) {
            }
        }
    }

    private static byte[] selectAid(final byte[] aid) {
        final byte[] command = new byte[6 + aid.length - 0];
        command[0] = 0x00;
        command[1] = (byte) 0xA4;
        command[2] = 0x04;
        command[3] = 0x00;
        command[4] = (byte) aid.length;
        System.arraycopy(aid, 0, command, 5, aid.length);
        command[5 + aid.length] = 0x00;
        return command;
    }

    private static byte[] transceive(final IsoDep isoDep, final byte[] command) throws Exception {
        return isoDep.transceive(command);
    }

    private static byte[] requireSuccess(final byte[] response, final String error) {
        if (response == null || response.length < 2
                || response[response.length - 2] != SW1_SUCCESS
                || response[response.length - 1] != SW2_SUCCESS) {
            throw new IllegalStateException(error);
        }
        return Arrays.copyOf(response, response.length - 2);
    }

    private static byte[] hexToBytes(final String hex) {
        final byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
