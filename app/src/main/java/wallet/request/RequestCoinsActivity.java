package wallet.request;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.uri.BitcoinURI;
import org.bitcoinj.wallet.Wallet;

import wallet.main.MainActivityPresenter;
import wallet.main.NetworkConfig;
import wallet.main.R;
import wallet.qr.QrCodeGenerator;
import wallet.main.BaseActivity;

/** Creates a BIP21 request URI using a fresh legacy (P2PKH) receive address. */
public final class RequestCoinsActivity extends BaseActivity {
    private EditText amountInput;
    private EditText labelInput;
    private TextView addressText;
    private TextView uriText;
    private ImageView qrImage;
    private Button copyButton;
    private Button shareButton;
    private String currentUri;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_request_coins);
        setSupportActionBar(findViewById(R.id.toolbar));
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.request_coins_title);
        }

        amountInput = findViewById(R.id.requestAmount);
        labelInput = findViewById(R.id.requestLabel);
        addressText = findViewById(R.id.requestAddress);
        uriText = findViewById(R.id.requestUri);
        qrImage = findViewById(R.id.requestQr);
        copyButton = findViewById(R.id.requestCopy);
        shareButton = findViewById(R.id.requestShare);

        findViewById(R.id.requestGenerate).setOnClickListener(v -> generateRequest());
        copyButton.setOnClickListener(v -> copyUri());
        shareButton.setOnClickListener(v -> shareUri());
        generateRequest();
    }

    private void generateRequest() {
        final Wallet wallet = MainActivityPresenter.getActiveWallet();
        if (wallet == null) {
            showMessage(R.string.wallet_not_ready);
            return;
        }

        final Coin amount = parseAmount();
        if (amount == null && !TextUtils.isEmpty(amountInput.getText().toString().trim())) {
            return;
        }
        final String label = labelInput.getText().toString().trim();

        new Thread(() -> {
            try {
                Address address = wallet.freshReceiveAddress(ScriptType.P2PKH);
                String uri = BitcoinURI.convertToBitcoinURI(
                        address, amount, TextUtils.isEmpty(label) ? null : label, null);
                Bitmap qr = QrCodeGenerator.generate(uri, 700);
                runOnUiThread(() -> {
                    currentUri = uri;
                    addressText.setText(address.toString());
                    uriText.setText(uri);
                    qrImage.setImageBitmap(qr);
                    copyButton.setEnabled(true);
                    shareButton.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> showMessage(R.string.request_generation_failed));
            }
        }, "request-legacy-generator").start();
    }

    private Coin parseAmount() {
        String text = amountInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        try {
            Coin coin = Coin.parseCoin(text);
            if (!coin.isPositive()) {
                showMessage(R.string.request_amount_invalid);
                return null;
            }
            return coin;
        } catch (Exception e) {
            showMessage(R.string.request_amount_invalid);
            return null;
        }
    }

    private void copyUri() {
        if (TextUtils.isEmpty(currentUri)) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Bitcoin request", currentUri));
        Toast.makeText(this, R.string.request_uri_copied, Toast.LENGTH_SHORT).show();
    }

    private void shareUri() {
        if (TextUtils.isEmpty(currentUri)) return;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, currentUri);
        startActivity(Intent.createChooser(intent, getString(R.string.request_share_title)));
    }

    private void showMessage(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
