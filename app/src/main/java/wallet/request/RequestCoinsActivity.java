package wallet.request;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.protocols.payments.PaymentProtocol;
import org.bitcoinj.wallet.Wallet;

import wallet.main.BaseActivity;
import wallet.main.MainActivityPresenter;
import wallet.main.NetworkConfig;
import wallet.main.R;
import wallet.nfc.PaymentRequestHceService;
import wallet.qr.QrCodeGenerator;
import wallet.util.Nfc;

/**
 * Creates a Schildbach-compatible legacy request.
 *
 * <p>The request is always a standard BIP21 URI for QR/share. NFC uses the same BIP70
 * PaymentRequest payload as Schildbach. On Android 9 and older that payload is published via
 * Android Beam/NDEF push for Schildbach compatibility. On Android 10+ B-Lite uses public HCE
 * and reader-mode APIs for a direct tap between two B-Lite devices.</p>
 */
public final class RequestCoinsActivity extends BaseActivity {
    private EditText amountInput;
    private EditText labelInput;
    private TextView addressText;
    private TextView uriText;
    private TextView nfcStatusText;
    private ImageView qrImage;
    private Button copyButton;
    private Button shareButton;
    private String currentUri;
    private byte[] currentPaymentRequest;
    private NfcAdapter nfcAdapter;

    @Override
    protected void onCreate(final Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_request_coins);
        setSupportActionBar(findViewById(R.id.toolbar));
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.request_coins_title);
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);

        amountInput = findViewById(R.id.requestAmount);
        labelInput = findViewById(R.id.requestLabel);
        addressText = findViewById(R.id.requestAddress);
        uriText = findViewById(R.id.requestUri);
        nfcStatusText = findViewById(R.id.requestNfcStatus);
        qrImage = findViewById(R.id.requestQr);
        copyButton = findViewById(R.id.requestCopy);
        shareButton = findViewById(R.id.requestShare);

        findViewById(R.id.requestGenerate).setOnClickListener(v -> generateRequest());
        copyButton.setOnClickListener(v -> copyUri());
        shareButton.setOnClickListener(v -> shareUri());
        generateRequest();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateNfcStatus();
        publishCurrentRequest();
    }

    @Override
    protected void onPause() {
        clearModernNfcRequest();
        super.onPause();
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
        final String paymentLabel = TextUtils.isEmpty(label) ? null : label;

        new Thread(() -> {
            try {
                org.bitcoinj.core.Context.propagate(org.bitcoinj.core.Context.getOrCreate(
                        NetworkConfig.parameters(NetworkConfig.get(this))));

                final Address address = wallet.freshReceiveAddress(ScriptType.P2PKH);
                final String uri = org.bitcoinj.uri.BitcoinURI.convertToBitcoinURI(
                        address, amount, paymentLabel, null);
                final byte[] paymentRequest = PaymentProtocol.createPaymentRequest(
                        NetworkConfig.parameters(NetworkConfig.get(this)),
                        amount, address, paymentLabel, null, null)
                        .build()
                        .toByteArray();
                final Bitmap qr = QrCodeGenerator.generate(uri, 700);

                runOnUiThread(() -> {
                    currentUri = uri;
                    currentPaymentRequest = paymentRequest;
                    addressText.setText(address.toString());
                    uriText.setText(uri);
                    qrImage.setImageBitmap(qr);
                    copyButton.setEnabled(true);
                    shareButton.setEnabled(true);
                    publishCurrentRequest();
                });
            } catch (Exception exception) {
                runOnUiThread(() -> showMessage(R.string.request_generation_failed));
            }
        }, "request-legacy-generator").start();
    }

    private void publishCurrentRequest() {
        if (currentPaymentRequest == null) {
            return;
        }

        if (nfcAdapter == null || !nfcAdapter.isEnabled()) {
            updateNfcStatus();
            return;
        }

        // Modern path: keep the PaymentRequest available through HCE for B-Lite peers.
        if (Nfc.hasModernHceSupport(this)) {
            PaymentRequestHceService.setPayload(currentPaymentRequest);
        }

        // Schildbach-compatible path: also expose the same protobuf as an NDEF MIME record.
        // The platform may ignore this on newer Android releases, in which case HCE remains
        // the primary phone-to-phone transport.
        Nfc.setNdefPushMessage(nfcAdapter,
                new android.nfc.NdefMessage(new android.nfc.NdefRecord[]{
                        Nfc.createMime(PaymentProtocol.MIMETYPE_PAYMENTREQUEST, currentPaymentRequest)
                }), this);

        updateNfcStatus();
    }

    private void clearModernNfcRequest() {
        if (Nfc.hasModernHceSupport(this)) {
            PaymentRequestHceService.clearPayload();
        }
    }

    private void updateNfcStatus() {
        if (nfcStatusText == null) {
            return;
        }
        if (nfcAdapter == null) {
            nfcStatusText.setText(R.string.request_nfc_unavailable);
        } else if (!nfcAdapter.isEnabled()) {
            nfcStatusText.setText(R.string.request_nfc_disabled);
        } else if (Nfc.hasModernHceSupport(this)) {
            nfcStatusText.setText(R.string.request_nfc_dual_ready);
        } else if (Build.VERSION.SDK_INT < 29) {
            nfcStatusText.setText(R.string.request_nfc_ready);
        } else {
            nfcStatusText.setText(R.string.request_nfc_ndef_only);
        }
    }

    private Coin parseAmount() {
        final String text = amountInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        try {
            final Coin coin = Coin.parseCoin(text);
            if (!coin.isPositive()) {
                showMessage(R.string.request_amount_invalid);
                return null;
            }
            return coin;
        } catch (Exception exception) {
            showMessage(R.string.request_amount_invalid);
            return null;
        }
    }

    private void copyUri() {
        if (TextUtils.isEmpty(currentUri)) {
            return;
        }
        final ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Bitcoin request", currentUri));
        Toast.makeText(this, R.string.request_uri_copied, Toast.LENGTH_SHORT).show();
    }

    private void shareUri() {
        if (TextUtils.isEmpty(currentUri)) {
            return;
        }
        final Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, currentUri);
        startActivity(Intent.createChooser(intent, getString(R.string.request_share_title)));
    }

    private void showMessage(final int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show();
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
