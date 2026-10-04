package wallet.tools;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import wallet.Constants;
import wallet.main.R;
import wallet.main.WalletAddressType;
import wallet.qr.QrCodeGenerator;

/** Simple single-page paper-wallet print layout. */
final class PaperWalletPrintAdapter extends android.print.PrintDocumentAdapter {
    private final Context context;
    private final String address;
    private final String addressType;
    private final String privateText;
    private final boolean addressVisible;
    private final boolean privateVisible;
    private android.graphics.pdf.PdfDocument document;

    PaperWalletPrintAdapter(Context context, String address, String addressType, String privateText,
                            boolean addressVisible, boolean privateVisible) {
        this.context = context;
        this.address = address;
        this.addressType = addressType;
        this.privateText = privateText;
        this.addressVisible = addressVisible;
        this.privateVisible = privateVisible;
    }

    @Override
    public void onLayout(android.print.PrintAttributes oldAttributes,
                          android.print.PrintAttributes newAttributes,
                          android.os.CancellationSignal cancellationSignal,
                          LayoutResultCallback callback, Bundle extras) {
        if (cancellationSignal.isCanceled()) {
            callback.onLayoutCancelled();
            return;
        }
        document = new android.graphics.pdf.PdfDocument();
        callback.onLayoutFinished(new android.print.PrintDocumentInfo.Builder("bitcoin-paper-wallet.pdf")
                .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(1)
                .build(), true);
    }

    @Override
    public void onWrite(android.print.PageRange[] pages,
                        android.os.ParcelFileDescriptor destination,
                        android.os.CancellationSignal cancellationSignal,
                        WriteResultCallback callback) {
        if (cancellationSignal.isCanceled()) {
            callback.onWriteCancelled();
            return;
        }

        Bitmap addressQr = addressVisible && !TextUtils.isEmpty(address)
                ? QrCodeGenerator.generate(address, 700) : null;
        Bitmap privateQr = privateVisible && !TextUtils.isEmpty(privateText)
                ? QrCodeGenerator.generate(privateText, 700) : null;

        Bitmap referenceQr = addressQr != null ? addressQr : privateQr;
        int inkColor = referenceQr != null
                ? findQrInk(referenceQr)
                : resolveThemeColor(android.R.attr.textColorPrimary, android.graphics.Color.BLACK);
        int paperColor = referenceQr != null
                ? findQrPaper(referenceQr)
                : resolveThemeColor(android.R.attr.colorBackground, android.graphics.Color.WHITE);

        android.graphics.pdf.PdfDocument.PageInfo pageInfo =
                new android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create();
        android.graphics.pdf.PdfDocument.Page page = document.startPage(pageInfo);
        android.graphics.Canvas canvas = page.getCanvas();
        canvas.drawColor(paperColor);

        View printView = android.view.LayoutInflater.from(context)
                .inflate(R.layout.paper_wallet_print, null, false);
        bindPrintView(printView, addressQr, privateQr, inkColor, paperColor);

        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(595f * density);
        int height = Math.round(842f * density);
        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
        printView.measure(widthSpec, heightSpec);
        printView.layout(0, 0, width, height);

        canvas.save();
        canvas.scale(1f / density, 1f / density);
        printView.draw(canvas);
        canvas.restore();

        document.finishPage(page);
        try (ParcelFileDescriptor.AutoCloseOutputStream output =
                     new ParcelFileDescriptor.AutoCloseOutputStream(destination)) {
            document.writeTo(output);
            callback.onWriteFinished(new android.print.PageRange[]{android.print.PageRange.ALL_PAGES});
        } catch (Exception e) {
            callback.onWriteFailed(e.getMessage());
        } finally {
            document.close();
            document = null;
            if (addressQr != null) addressQr.recycle();
            if (privateQr != null) privateQr.recycle();
        }
    }

    private void bindPrintView(View root, Bitmap addressQr, Bitmap privateQr, int inkColor, int paperColor) {
        TextView title = root.findViewById(R.id.paperPrintTitle);
        TextView addressLabel = root.findViewById(R.id.paperPrintAddressLabel);
        TextView addressValue = root.findViewById(R.id.paperPrintAddressValue);
        TextView privateLabel = root.findViewById(R.id.paperPrintPrivateLabel);
        TextView privateValue = root.findViewById(R.id.paperPrintPrivateValue);
        TextView warning = root.findViewById(R.id.paperPrintWarning);
        ImageView addressImage = root.findViewById(R.id.paperPrintAddressQr);
        ImageView privateImage = root.findViewById(R.id.paperPrintPrivateQr);
        View divider = root.findViewById(R.id.paperPrintDivider);

        root.setBackgroundColor(paperColor);
        title.setTextColor(inkColor);
        addressLabel.setTextColor(inkColor);
        addressValue.setTextColor(inkColor);
        privateLabel.setTextColor(inkColor);
        privateValue.setTextColor(inkColor);
        warning.setTextColor(inkColor);
        divider.setBackgroundColor(inkColor);

        addressLabel.setText(context.getString(R.string.paper_wallet_print_public_key_format,
                detectAddressType(addressType)));
        privateLabel.setText(context.getString(R.string.paper_wallet_print_private_key_format,
                detectPrivateType(privateText)));

        if (addressQr != null && addressVisible) {
            addressImage.setImageBitmap(addressQr);
            addressImage.setVisibility(View.VISIBLE);
            addressValue.setText(address);
            fitSingleLine(addressValue);
            addressValue.setVisibility(View.VISIBLE);
        } else {
            addressImage.setVisibility(View.GONE);
            addressValue.setVisibility(View.GONE);
        }

        if (privateQr != null && privateVisible) {
            privateImage.setImageBitmap(privateQr);
            privateImage.setVisibility(View.VISIBLE);
            privateValue.setText(privateText);
            fitSingleLine(privateValue);
            privateValue.setVisibility(View.VISIBLE);
        } else {
            privateImage.setVisibility(View.GONE);
            privateValue.setVisibility(View.GONE);
        }
    }

    private float dp(float value) {
        return value * context.getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * context.getResources().getDisplayMetrics().scaledDensity;
    }

    private void fitSingleLine(TextView view) {
        float width = dp(523f);
        float size = sp(10.5f);
        float minimum = sp(7f);
        while (size > minimum) {
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size);
            if (view.getPaint().measureText(view.getText().toString()) <= width) {
                break;
            }
            size -= sp(0.5f);
        }
    }

    /** Returns the label for the exact address type used to generate the paper wallet. */
    private String detectAddressType(String type) {
        return WalletAddressType.label(context, type);
    }

    /** Detects the private-key encoding by validating its Base58Check payload. */
    private String detectPrivateType(String privateText) {
        if (TextUtils.isEmpty(privateText)) {
            return context.getString(R.string.paper_wallet_print_type_unknown);
        }
        try {
            byte[] payload = Base58Check.decode(privateText.trim());
            if (payload.length == 39 && (payload[0] & 0xff) == 0x01
                    && ((payload[1] & 0xff) == 0x42 || (payload[1] & 0xff) == 0x43)) {
                return context.getString(R.string.paper_wallet_print_type_bip38);
            }
            if ((payload.length == 33 || payload.length == 34)
                    && (payload[0] & 0xff) == (Constants.IS_PRODUCTION ? 0x80 : 0xEF)
                    && (payload.length == 33 || (payload[33] & 0xff) == 0x01)) {
                return context.getString(R.string.paper_wallet_print_type_wif);
            }
        } catch (Exception ignored) {
            // Keep the print label safe when an invalid/private value is supplied.
        }
        return context.getString(R.string.paper_wallet_print_type_unknown);
    }

    private static int findQrInk(Bitmap bitmap) {
        if (bitmap != null) {
            int stepX = Math.max(1, bitmap.getWidth() / 80);
            int stepY = Math.max(1, bitmap.getHeight() / 80);
            for (int y = 0; y < bitmap.getHeight(); y += stepY) {
                for (int x = 0; x < bitmap.getWidth(); x += stepX) {
                    int color = bitmap.getPixel(x, y);
                    if (android.graphics.Color.alpha(color) > 0 &&
                            android.graphics.Color.red(color) < 128 &&
                            android.graphics.Color.green(color) < 128 &&
                            android.graphics.Color.blue(color) < 128) {
                        return color;
                    }
                }
            }
        }
        return 0xFF000000;
    }

    private static int findQrPaper(Bitmap bitmap) {
        if (bitmap != null) {
            int color = bitmap.getPixel(0, 0);
            if (android.graphics.Color.alpha(color) > 0) return color;
        }
        return android.graphics.Color.WHITE;
    }

    private int resolveThemeColor(int attribute, int fallback) {
        android.util.TypedValue value = new android.util.TypedValue();
        if (context.getTheme().resolveAttribute(attribute, value, true)) {
            if (value.resourceId != 0) {
                try {
                    return context.getResources().getColor(value.resourceId);
                } catch (Exception ignored) {
                    // Ignore an individual malformed watch script.
                }
            }
            return value.data;
        }
        return fallback;
    }
}
