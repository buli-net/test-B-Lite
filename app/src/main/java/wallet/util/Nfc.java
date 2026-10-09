package wallet.util;

import android.app.Activity;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** NDEF helpers for standard Bitcoin PaymentRequest exchange. */
public final class Nfc {
    private Nfc() {
    }

    public static NdefRecord createMime(final String mimeType, final byte[] payload) {
        final byte[] mimeBytes = mimeType.getBytes(StandardCharsets.US_ASCII);
        return new NdefRecord(NdefRecord.TNF_MIME_MEDIA, mimeBytes, new byte[0], payload);
    }

    public static byte[] extractMimePayload(final String mimeType, final NdefMessage message) {
        if (message == null) return null;
        final byte[] mimeBytes = mimeType.getBytes(StandardCharsets.US_ASCII);
        for (final NdefRecord record : message.getRecords()) {
            if (record.getTnf() == NdefRecord.TNF_MIME_MEDIA
                    && Arrays.equals(record.getType(), mimeBytes)) {
                return record.getPayload();
            }
        }
        return null;
    }

    public static void setNdefPushMessage(final NfcAdapter adapter, final NdefMessage message,
                                          final Activity activity) {
        if (adapter == null || message == null || activity == null) return;
        try {
            // Use the same NDEF push path as the reference wallet, where the platform exposes it.
            final Method setNdefPushMessage = adapter.getClass().getMethod("setNdefPushMessage",
                    NdefMessage.class, Activity.class, Activity[].class);
            setNdefPushMessage.invoke(adapter, message, activity, new Activity[0]);
        } catch (final ReflectiveOperationException ignored) {
            // This platform does not expose legacy NDEF push; QR and URI sharing remain available.
        }
    }
}
