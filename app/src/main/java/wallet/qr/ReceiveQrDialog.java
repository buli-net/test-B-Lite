package wallet.qr;

import android.app.Activity;
import android.graphics.Bitmap;
import androidx.appcompat.app.AlertDialog;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import wallet.main.R;
import wallet.ui.TextViewUtils;

/** Displays the current receive address as a large QR code. */
public final class ReceiveQrDialog {

    private ReceiveQrDialog() {
    }

    public static void show(Activity activity, String address) {
        if (activity == null || TextUtils.isEmpty(address)) {
            return;
        }

        View content = activity.getLayoutInflater()
                .inflate(R.layout.dialog_receive_qr, null);
        ImageView image = content.findViewById(R.id.receiveQrImage);
        TextView addressText = content.findViewById(R.id.receiveQrAddress);
        TextViewUtils.configureSelectableMiddleEllipsis(addressText);
        TextViewUtils.setTextIfChanged(addressText, address);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.receive_qr_title)
                .setView(content)
                .setPositiveButton(R.string.got_it, null)
                .create();
        dialog.show();

        new Thread(() -> {
            try {
                final Bitmap qr = QrCodeGenerator.generate(address, 800);
                activity.runOnUiThread(() -> {
                    if (dialog.isShowing()) {
                        image.setImageBitmap(qr);
                    }
                });
            } catch (Exception ignored) {
                // Keep the dialog usable if QR generation fails.
            }
        }, "receive-qr-dialog").start();
    }
}
