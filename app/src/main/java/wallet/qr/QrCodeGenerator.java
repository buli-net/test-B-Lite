package wallet.qr;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/** Creates QR bitmaps from wallet data. */
public final class QrCodeGenerator {

    private QrCodeGenerator() {
    }

    public static Bitmap generate(String data, int size) {
        if (data == null || data.length() == 0) {
            throw new IllegalArgumentException("QR data is empty");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("QR size must be positive");
        }

        try {
            BitMatrix matrix = new QRCodeWriter().encode(
                    data,
                    BarcodeFormat.QR_CODE,
                    size,
                    size
            );

            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];

            // Build the bitmap in one bulk operation. Calling Bitmap.setPixel()
            // once per module is unnecessarily expensive on Android.
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    pixels[offset + x] = matrix.get(x, y)
                            ? 0xFF000000
                            : 0xFFFFFFFF;
                }
            }

            return Bitmap.createBitmap(
                    pixels,
                    0,
                    width,
                    width,
                    height,
                    Bitmap.Config.ARGB_8888
            );
        } catch (WriterException e) {
            throw new IllegalArgumentException(
                    "Unable to generate QR code",
                    e
            );
        }
    }
}
