package wallet.nfc;

import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.bitcoinj.protocols.payments.PaymentProtocol;

import wallet.util.Nfc;

/** Exposes the current payment request as a read-only ISO-DEP card for nearby wallet devices. */
public final class PaymentRequestHceService extends HostApduService {
    private static final byte[] AID = hexToBytes(Nfc.PAYMENT_AID);
    // NFC Forum Type 4 Tag NDEF application, for standards-based external readers.
    private static final byte[] NDEF_AID = hexToBytes("D2760000850101");
    private static final byte[] CC_FILE_ID = new byte[]{(byte) 0xE1, 0x03};
    private static final byte[] NDEF_FILE_ID = new byte[]{(byte) 0xE1, 0x04};
    private static final byte[] CC_FILE = new byte[]{
            0x00, 0x0F, 0x20, 0x00, (byte) 0xF0, 0x00, (byte) 0xF0,
            0x04, 0x06, (byte) 0xE1, 0x04, (byte) 0xFF, (byte) 0xFF, 0x00, 0x00
    };
    private static final byte[] SW_SUCCESS = new byte[]{(byte) 0x90, 0x00};
    private static final byte[] SW_FILE_NOT_FOUND = new byte[]{(byte) 0x6A, (byte) 0x82};
    private static final byte[] SW_WRONG_LENGTH = new byte[]{(byte) 0x67, 0x00};
    private static final byte[] SW_WRONG_PARAMS = new byte[]{(byte) 0x6A, (byte) 0x86};
    private static volatile byte[] payload;
    private boolean selected;
    private boolean ndefApplicationSelected;
    private int selectedNdefFile;

    public static void setPayload(final byte[] request) {
        if (request == null || request.length == 0 || request.length > Nfc.HCE_MAX_PAYLOAD) {
            payload = null;
        } else {
            payload = Arrays.copyOf(request, request.length);
        }
    }

    public static void clearPayload() { payload = null; }

    @Override
    public byte[] processCommandApdu(final byte[] commandApdu, final Bundle extras) {
        if (commandApdu == null || commandApdu.length < 4) return SW_WRONG_LENGTH;

        if (isSelectAid(commandApdu, AID)) {
            selected = payload != null;
            ndefApplicationSelected = false;
            selectedNdefFile = 0;
            return selected ? SW_SUCCESS : SW_FILE_NOT_FOUND;
        }
        if (isSelectAid(commandApdu, NDEF_AID)) {
            ndefApplicationSelected = payload != null;
            selected = false;
            selectedNdefFile = 0;
            return ndefApplicationSelected ? SW_SUCCESS : SW_FILE_NOT_FOUND;
        }

        if (ndefApplicationSelected) {
            if (isSelectFile(commandApdu, CC_FILE_ID)) {
                selectedNdefFile = 1;
                return SW_SUCCESS;
            }
            if (isSelectFile(commandApdu, NDEF_FILE_ID)) {
                selectedNdefFile = 2;
                return SW_SUCCESS;
            }
            if (commandApdu[1] == (byte) 0xB0) {
                return readNdefBinary(commandApdu);
            }
            return SW_WRONG_PARAMS;
        }

        if (!selected) return SW_FILE_NOT_FOUND;
        final byte instruction = commandApdu[1];
        if (instruction == (byte) 0xCA) {
            final byte[] current = payload;
            if (current == null) return SW_FILE_NOT_FOUND;
            return appendStatus(ByteBuffer.allocate(4).putInt(current.length).array());
        }
        if (instruction == (byte) 0xB0) {
            final byte[] current = payload;
            if (current == null) return SW_FILE_NOT_FOUND;
            if (commandApdu.length < 5) return SW_WRONG_LENGTH;
            final int offset = ((commandApdu[2] & 0xff) << 8) | (commandApdu[3] & 0xff);
            int requested = commandApdu[4] & 0xff;
            if (requested == 0) requested = 256;
            if (offset >= current.length || offset + requested > current.length) return SW_WRONG_PARAMS;
            return appendStatus(Arrays.copyOfRange(current, offset, offset + requested));
        }
        return SW_WRONG_PARAMS;
    }

    private byte[] readNdefBinary(final byte[] commandApdu) {
        if (commandApdu.length < 5 || selectedNdefFile == 0) return SW_FILE_NOT_FOUND;
        final byte[] file;
        if (selectedNdefFile == 1) {
            file = CC_FILE;
        } else {
            final byte[] current = payload;
            if (current == null) return SW_FILE_NOT_FOUND;
            final byte[] ndefMessage = new NdefMessage(new NdefRecord[]{
                    Nfc.createMime(PaymentProtocol.MIMETYPE_PAYMENTREQUEST, current)
            }).toByteArray();
            if (ndefMessage.length > 0xFFFD) return SW_WRONG_LENGTH;
            file = new byte[ndefMessage.length + 2];
            file[0] = (byte) ((ndefMessage.length >>> 8) & 0xff);
            file[1] = (byte) (ndefMessage.length & 0xff);
            System.arraycopy(ndefMessage, 0, file, 2, ndefMessage.length);
        }

        final int offset = ((commandApdu[2] & 0xff) << 8) | (commandApdu[3] & 0xff);
        int requested = commandApdu[4] & 0xff;
        if (requested == 0) requested = 256;
        // Respect the advertised MLe and keep each response within a safe short-APDU size.
        requested = Math.min(requested, 240);
        if (offset >= file.length) return SW_WRONG_PARAMS;
        final int end = Math.min(file.length, offset + requested);
        return appendStatus(Arrays.copyOfRange(file, offset, end));
    }

    @Override
    public void onDeactivated(final int reason) {
        selected = false;
        ndefApplicationSelected = false;
        selectedNdefFile = 0;
    }

    private static boolean isSelectAid(final byte[] apdu, final byte[] expectedAid) {
        if (apdu.length < 5 || apdu[0] != 0x00 || apdu[1] != (byte) 0xA4
                || apdu[2] != 0x04 || apdu[3] != 0x00) return false;
        final int length = apdu[4] & 0xff;
        return apdu.length >= 5 + length && length == expectedAid.length
                && Arrays.equals(expectedAid, Arrays.copyOfRange(apdu, 5, 5 + length));
    }

    private static boolean isSelectFile(final byte[] apdu, final byte[] fileId) {
        return apdu.length >= 7 && apdu[0] == 0x00 && apdu[1] == (byte) 0xA4
                && apdu[2] == 0x00 && apdu[3] == 0x0C
                && apdu[4] == 0x02 && apdu[5] == fileId[0] && apdu[6] == fileId[1];
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
