package wallet.nfc;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

import java.nio.ByteBuffer;
import java.util.Arrays;

import wallet.util.Nfc;

/**
 * Modern NFC transport for B-Lite payment requests.
 * The requester behaves like an NFC card; the other B-Lite device reads the request in reader mode.
 */
public final class PaymentRequestHceService extends HostApduService {
    private static final byte[] AID = hexToBytes(Nfc.PAYMENT_AID);
    private static final byte[] SW_SUCCESS = new byte[]{(byte) 0x90, 0x00};
    private static final byte[] SW_FILE_NOT_FOUND = new byte[]{(byte) 0x6A, (byte) 0x82};
    private static final byte[] SW_WRONG_LENGTH = new byte[]{(byte) 0x67, 0x00};
    private static final byte[] SW_WRONG_PARAMS = new byte[]{(byte) 0x6A, (byte) 0x86};
    private static volatile byte[] payload;
    private boolean selected;

    public static void setPayload(final byte[] paymentRequest) {
        if (paymentRequest == null || paymentRequest.length == 0 || paymentRequest.length > Nfc.HCE_MAX_PAYLOAD) {
            payload = null;
        } else {
            payload = Arrays.copyOf(paymentRequest, paymentRequest.length);
        }
    }

    public static void clearPayload() {
        payload = null;
    }

    @Override
    public byte[] processCommandApdu(final byte[] commandApdu, final Bundle extras) {
        if (commandApdu == null || commandApdu.length < 4) {
            return SW_WRONG_LENGTH;
        }

        if (isSelectAid(commandApdu)) {
            if (payload != null) {
                selected = true;
                return SW_SUCCESS;
            }
            selected = false;
            return SW_FILE_NOT_FOUND;
        }

        if (!selected) {
            return SW_FILE_NOT_FOUND;
        }

        final byte instruction = commandApdu[1];
        if (instruction == (byte) 0xCA) {
            final byte[] current = payload;
            if (current == null) {
                return SW_FILE_NOT_FOUND;
            }
            final byte[] response = ByteBuffer.allocate(4).putInt(current.length).array();
            return appendStatus(response);
        }

        if (instruction == (byte) 0xB0) {
            final byte[] current = payload;
            if (current == null || commandApdu.length < 5) {
                return SW_FILE_NOT_FOUND;
            }
            final int offset = ((commandApdu[2] & 0xff) << 8) | (commandApdu[3] & 0xff);
            int requested = commandApdu[4] & 0xff;
            if (requested == 0) {
                requested = 256;
            }
            if (offset < 0 || offset >= current.length || requested <= 0
                    || offset + requested > current.length) {
                return SW_WRONG_PARAMS;
            }
            return appendStatus(Arrays.copyOfRange(current, offset, offset + requested));
        }

        return SW_WRONG_PARAMS;
    }

    @Override
    public void onDeactivated(final int reason) {
        selected = false;
    }

    private static boolean isSelectAid(final byte[] commandApdu) {
        if (commandApdu.length < 5 || commandApdu[0] != 0x00 || commandApdu[1] != (byte) 0xA4
                || commandApdu[2] != 0x04 || commandApdu[3] != 0x00) {
            return false;
        }
        final int aidLength = commandApdu[4] & 0xff;
        if (commandApdu.length < 5 + aidLength) {
            return false;
        }
        return aidLength == AID.length && Arrays.equals(AID, Arrays.copyOfRange(commandApdu, 5, 5 + aidLength));
    }

    private static byte[] appendStatus(final byte[] data) {
        final byte[] response = Arrays.copyOf(data, data.length + 2);
        response[response.length - 2] = (byte) 0x90;
        response[response.length - 1] = 0x00;
        return response;
    }

    private static byte[] hexToBytes(final String hex) {
        final byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
