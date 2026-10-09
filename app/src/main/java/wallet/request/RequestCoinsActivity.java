package wallet.request;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ShareCompat;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.protocols.payments.PaymentProtocol;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.Wallet;

import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import wallet.main.BaseActivity;
import wallet.main.MainActivityPresenter;
import wallet.main.R;
import wallet.main.WalletAddressType;
import wallet.qr.QrCodeGenerator;
import wallet.send.SendActivity;
import wallet.util.Nfc;

/** Request Bitcoin using the same BIP21, PaymentRequest, NDEF and local-app flow as the reference wallet. */
public final class RequestCoinsActivity extends BaseActivity {
    private static final String[] ADDRESS_TYPES = {
            WalletAddressType.P2PKH,
            WalletAddressType.P2WPKH,
            WalletAddressType.P2SH_P2WPKH
    };

    private final ExecutorService requestExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger requestVersion = new AtomicInteger();

    private EditText amountInput;
    private EditText labelInput;
    private Spinner addressTypeInput;
    private TextView addressTypeDescription;
    private TextView requestInstructions;
    private ImageView qrImage;
    private NfcAdapter nfcAdapter;

    private volatile Address currentAddress;
    private volatile String currentUri;
    private volatile byte[] currentPaymentRequest;
    private String selectedAddressType = WalletAddressType.P2WPKH;
    private boolean suppressAddressTypeCallback;

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
        addressTypeInput = findViewById(R.id.requestAddressType);
        addressTypeDescription = findViewById(R.id.requestAddressTypeDescription);
        requestInstructions = findViewById(R.id.requestNfcStatus);
        qrImage = findViewById(R.id.requestQr);

        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{
                        getString(R.string.request_address_type_legacy),
                        getString(R.string.request_address_type_native_segwit),
                        getString(R.string.request_address_type_nested_segwit)
                });
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        addressTypeInput.setAdapter(adapter);

        selectedAddressType = defaultAddressTypeFromMainWallet();
        suppressAddressTypeCallback = true;
        addressTypeInput.setSelection(selectionForType(selectedAddressType));
        suppressAddressTypeCallback = false;
        updateAddressTypeDescription();

        addressTypeInput.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                if (suppressAddressTypeCallback) return;
                final String selected = ADDRESS_TYPES[position];
                if (!selected.equals(selectedAddressType)) {
                    selectedAddressType = selected;
                    updateAddressTypeDescription();
                    generateFreshAddress();
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        final TextWatcher requestFieldsWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateRequestForCurrentAddress();
            }
            @Override public void afterTextChanged(Editable s) { }
        };
        amountInput.addTextChangedListener(requestFieldsWatcher);
        labelInput.addTextChangedListener(requestFieldsWatcher);

        updateNfcInstruction();
        generateFreshAddress();
    }

    @Override
    protected void onResume() {
        super.onResume();
        publishCurrentRequest();
        updateNfcInstruction();
    }

    @Override
    protected void onDestroy() {
        requestExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.menu_request_coins, menu);
        final boolean requestReady = !TextUtils.isEmpty(currentUri);
        menu.findItem(R.id.requestCoinsCopy).setEnabled(requestReady);
        menu.findItem(R.id.requestCoinsShare).setEnabled(requestReady);
        menu.findItem(R.id.requestCoinsLocalApp).setEnabled(requestReady);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(final Menu menu) {
        final boolean requestReady = !TextUtils.isEmpty(currentUri);
        MenuItem copy = menu.findItem(R.id.requestCoinsCopy);
        MenuItem share = menu.findItem(R.id.requestCoinsShare);
        MenuItem localApp = menu.findItem(R.id.requestCoinsLocalApp);
        if (copy != null) copy.setEnabled(requestReady);
        if (share != null) share.setEnabled(requestReady);
        if (localApp != null) localApp.setEnabled(requestReady);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == android.R.id.home) {
            finish();
            return true;
        } else if (itemId == R.id.requestCoinsCopy) {
            handleCopy();
            return true;
        } else if (itemId == R.id.requestCoinsShare) {
            handleShare();
            return true;
        } else if (itemId == R.id.requestCoinsLocalApp) {
            handleLocalApp();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private String defaultAddressTypeFromMainWallet() {
        final Wallet wallet = MainActivityPresenter.getActiveWallet();
        if (wallet == null) return WalletAddressType.P2WPKH;
        try {
            final String address = wallet.currentReceiveAddress().toString().toLowerCase(Locale.ROOT);
            if (address.startsWith("bc1q") || address.startsWith("tb1q")) {
                return WalletAddressType.P2WPKH;
            }
            if (address.startsWith("3") || address.startsWith("2")) {
                return WalletAddressType.P2SH_P2WPKH;
            }
            return WalletAddressType.P2PKH;
        } catch (RuntimeException ignored) {
            return WalletAddressType.P2WPKH;
        }
    }

    private int selectionForType(final String type) {
        for (int i = 0; i < ADDRESS_TYPES.length; i++) {
            if (ADDRESS_TYPES[i].equals(type)) return i;
        }
        return 1;
    }

    private void updateAddressTypeDescription() {
        if (addressTypeDescription == null) return;
        if (WalletAddressType.P2PKH.equals(selectedAddressType)) {
            addressTypeDescription.setText(R.string.request_address_type_description_legacy);
        } else if (WalletAddressType.P2SH_P2WPKH.equals(selectedAddressType)) {
            addressTypeDescription.setText(R.string.request_address_type_description_nested_segwit);
        } else {
            addressTypeDescription.setText(R.string.request_address_type_description_native_segwit);
        }
    }

    private void generateFreshAddress() {
        final int version = requestVersion.incrementAndGet();
        currentAddress = null;
        currentUri = null;
        currentPaymentRequest = null;
        qrImage.setImageDrawable(null);
        invalidateOptionsMenu();

        final String type = selectedAddressType;
        requestExecutor.execute(() -> {
            try {
                if (version != requestVersion.get()) return;
                final Wallet wallet = MainActivityPresenter.getActiveWallet();
                if (wallet == null) throw new IllegalStateException("Wallet is not ready");
                org.bitcoinj.core.Context.propagate(
                        org.bitcoinj.core.Context.getOrCreate(wallet.getParams()));

                if (version != requestVersion.get()) return;
                final Address address;
                if (WalletAddressType.P2PKH.equals(type)) {
                    address = wallet.freshReceiveAddress(ScriptType.P2PKH);
                } else if (WalletAddressType.P2WPKH.equals(type)) {
                    address = wallet.freshReceiveAddress(ScriptType.P2WPKH);
                } else {
                    final ECKey key = wallet.freshKey();
                    final Script script = WalletAddressType.scriptForKey(
                            wallet.getParams(), key, WalletAddressType.P2SH_P2WPKH);
                    address = WalletAddressType.addressForKey(
                            wallet.getParams(), key, WalletAddressType.P2SH_P2WPKH);
                    wallet.addWatchedScripts(Collections.singletonList(script));
                    final MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
                    if (presenter != null) presenter.saveWalletNow();
                }

                runOnUiThread(() -> {
                    if (isFinishing() || version != requestVersion.get()) return;
                    currentAddress = address;
                    updateRequestForCurrentAddress();
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    if (isFinishing() || version != requestVersion.get()) return;
                    Toast.makeText(this, R.string.request_generation_failed, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void updateRequestForCurrentAddress() {
        final Address address = currentAddress;
        if (address == null) return;

        final Coin amount = parseAmountWithoutShowingToast();
        final String labelText = labelInput.getText().toString().trim();
        final String label = labelText.isEmpty() ? null : labelText;
        final String uri;
        final int version = requestVersion.incrementAndGet();
        try {
            // Keep URI and PaymentRequest updates synchronous, as in the reference
            // view model. QR bitmap encoding alone runs off the UI thread.
            uri = org.bitcoinj.uri.BitcoinURI.convertToBitcoinURI(address, amount, label, null);
            final Wallet wallet = MainActivityPresenter.getActiveWallet();
            if (wallet == null) throw new IllegalStateException("Wallet is not ready");
            final org.bitcoinj.core.NetworkParameters parameters = wallet.getParams();
            final byte[] paymentRequest = PaymentProtocol.createPaymentRequest(
                    parameters, amount, address, label, null, null).build().toByteArray();

            currentUri = uri;
            currentPaymentRequest = paymentRequest;
            publishCurrentRequest();
            updateNfcInstruction();
            invalidateOptionsMenu();

            requestExecutor.execute(() -> {
                try {
                    if (version != requestVersion.get()) return;
                    final Bitmap qr = QrCodeGenerator.generate(uri, 700);
                    runOnUiThread(() -> {
                        if (isFinishing() || version != requestVersion.get() || address != currentAddress) return;
                        qrImage.setImageBitmap(qr);
                    });
                } catch (RuntimeException exception) {
                    runOnUiThread(() -> {
                        if (isFinishing() || version != requestVersion.get()) return;
                        qrImage.setImageDrawable(null);
                    });
                }
            });
        } catch (Exception exception) {
            currentUri = null;
            currentPaymentRequest = null;
            qrImage.setImageDrawable(null);
            invalidateOptionsMenu();
        }
    }

    private Coin parseAmountWithoutShowingToast() {
        final String text = amountInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return null;
        try {
            final Coin value = Coin.parseCoin(text);
            return value.isPositive() ? value : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void publishCurrentRequest() {
        final byte[] paymentRequest = currentPaymentRequest;
        if (nfcAdapter == null || !nfcAdapter.isEnabled() || paymentRequest == null) return;
        final NdefMessage message = new NdefMessage(new android.nfc.NdefRecord[]{
                Nfc.createMime(PaymentProtocol.MIMETYPE_PAYMENTREQUEST, paymentRequest)
        });
        Nfc.setNdefPushMessage(nfcAdapter, message, this);
    }

    private void updateNfcInstruction() {
        if (requestInstructions == null) return;
        String instruction = getString(R.string.request_instruction_qr);
        if (nfcAdapter != null && nfcAdapter.isEnabled()) {
            instruction = instruction + "\n" + getString(R.string.request_instruction_nfc);
        }
        requestInstructions.setText(instruction);
    }

    private void handleCopy() {
        final String uri = currentUri;
        if (TextUtils.isEmpty(uri)) return;
        final ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newRawUri("Bitcoin payment request", Uri.parse(uri)));
        Toast.makeText(this, R.string.request_uri_copied, Toast.LENGTH_SHORT).show();
    }

    private void handleShare() {
        final String uri = currentUri;
        if (TextUtils.isEmpty(uri)) return;
        ShareCompat.IntentBuilder.from(this)
                .setType("text/plain")
                .setText(uri)
                .setChooserTitle(R.string.request_share_title)
                .startChooser();
    }

    private void handleLocalApp() {
        final String uri = currentUri;
        if (TextUtils.isEmpty(uri)) return;
        final ComponentName component = new ComponentName(this, SendActivity.class);
        final PackageManager packageManager = getPackageManager();
        final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        try {
            // Match the reference wallet: temporarily exclude this app's send handler,
            // so ACTION_VIEW resolves to a different installed Bitcoin app.
            packageManager.setComponentEnabledSetting(component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            startActivity(intent);
        } catch (final ActivityNotFoundException exception) {
            Toast.makeText(this, R.string.request_no_local_app, Toast.LENGTH_LONG).show();
        } finally {
            packageManager.setComponentEnabledSetting(component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        }
        finish();
    }
}
