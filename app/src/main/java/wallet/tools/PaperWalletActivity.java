package wallet.tools;

import wallet.main.BaseActivity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.bitcoinj.base.Address;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.DumpedPrivateKey;
import org.bitcoinj.crypto.ECKey;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import wallet.main.R;
import wallet.main.NetworkConfig;
import wallet.main.WalletAddressType;
import wallet.qr.QrCodeGenerator;
import wallet.ui.TextViewUtils;

/** Creates an offline-style single-key paper wallet with QR codes. */
public final class PaperWalletActivity extends BaseActivity {

    private final SecureRandom secureRandom = new SecureRandom();
    private EditText importInput;
    private CheckBox bip38CheckBox;
    private Button generateButton;
    private Button importButton;
    private TextView addressValue;
    private TextView addressTypeValue;
    private RadioGroup addressTypeGroup;
    private TextView privateValue;
    private ImageView addressQr;
    private ImageView privateQr;
    private LinearLayout resultCard;
    private ImageButton addressCopyButton;
    private ImageButton addressEyeButton;
    private ImageButton addressHexButton;
    private ImageButton privateCopyButton;
    private ImageButton privateEyeButton;
    private ImageButton privateHexButton;
    private Button printButton;
    private Button exportButton;
    private String currentAddress;
    private String currentAddressType;
    private String selectedAddressType = WalletAddressType.P2PKH;
    private String currentPrivateText;
    private boolean addressVisible = true;
    private boolean privateVisible = false;

    private final ActivityResultLauncher<ScanOptions> barcodeLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (!TextUtils.isEmpty(result.getContents())) {
                    String value = result.getContents().trim();
                    if (value.startsWith("6P")) {
                        promptForImportedBip38(value);
                    } else {
                        importInput.setText(value);
                        importInput.setSelection(importInput.length());
                    }
                }
            });

    private final ActivityResultLauncher<String[]> inputFileLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    readInputFile(uri);
                }
            });

    private final ActivityResultLauncher<String> exportFileLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
                if (uri != null) {
                    writeExport(uri);
                }
            });

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_paper_wallet);
        Toolbar toolbar = findViewById(R.id.toolbar_paper_wallet);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.paper_wallet_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        importInput = findViewById(R.id.paperWalletInput);
        bip38CheckBox = findViewById(R.id.paperWalletBip38);
        generateButton = findViewById(R.id.paperWalletGenerateButton);
        importButton = findViewById(R.id.paperWalletImportButton);
        addressValue = findViewById(R.id.paperWalletAddress);
        addressTypeValue = findViewById(R.id.paperWalletAddressTypeValue);
        addressTypeGroup = findViewById(R.id.walletAddressTypeGroup);
        // Paper Wallet supports generating every address type exposed by WalletAddressType.
        findViewById(R.id.walletAddressTypeP2shP2wpkh).setEnabled(true);
        findViewById(R.id.walletAddressTypeP2tr).setEnabled(true);
        addressTypeGroup.setOnCheckedChangeListener((group, checkedId) -> selectedAddressType = addressTypeFromCheckedId(checkedId));
        TextViewUtils.configureSelectableMiddleEllipsis(addressValue);
        privateValue = findViewById(R.id.paperWalletPrivateKey);
        TextViewUtils.configureSelectableMiddleEllipsis(privateValue);
        addressQr = findViewById(R.id.paperWalletAddressQr);
        privateQr = findViewById(R.id.paperWalletPrivateQr);
        resultCard = findViewById(R.id.paperWalletResultCard);
        addressCopyButton = findViewById(R.id.paperWalletAddressCopy);
        addressEyeButton = findViewById(R.id.paperWalletAddressEye);
        addressHexButton = findViewById(R.id.paperWalletAddressHex);
        privateCopyButton = findViewById(R.id.paperWalletPrivateCopy);
        privateEyeButton = findViewById(R.id.paperWalletPrivateEye);
        privateHexButton = findViewById(R.id.paperWalletPrivateHex);
        printButton = findViewById(R.id.paperWalletPrintButton);
        exportButton = findViewById(R.id.paperWalletExportButton);

        generateButton.setOnClickListener(v -> createRandomWallet());
        importButton.setOnClickListener(v -> importWalletKey());
        findViewById(R.id.paperWalletScanButton).setOnClickListener(v -> {
            ScanOptions options = new ScanOptions()
                    .setPrompt(getString(R.string.paper_wallet_scan_prompt));
            barcodeLauncher.launch(options);
        });
        findViewById(R.id.paperWalletFileButton).setOnClickListener(v -> openInputFile());
        findViewById(R.id.paperWalletClearButton).setOnClickListener(v -> clearResult());
        addressCopyButton.setOnClickListener(v -> copyToClipboard(getString(R.string.paper_wallet_address_label), currentAddress));
        addressEyeButton.setOnClickListener(v -> toggleAddressVisibility());
        addressHexButton.setOnClickListener(v -> showHexDialog(getString(R.string.paper_wallet_address_hex_title), addressToHex(currentAddress)));
        privateCopyButton.setOnClickListener(v -> copyToClipboard(getString(R.string.paper_wallet_private_label), currentPrivateText));
        privateEyeButton.setOnClickListener(v -> togglePrivateVisibility());
        privateHexButton.setOnClickListener(v -> showPrivateHex());
        printButton.setOnClickListener(v -> printPaperWallet());
        exportButton.setOnClickListener(v -> exportText());
        resultCard.setVisibility(View.GONE);
        updateActionButtons();
    }

    private void createRandomWallet() {
        byte[] bytes = new byte[32];
        do {
            secureRandom.nextBytes(bytes);
        } while (!isValidSecret(bytes));
        try {
            ECKey key = ECKey.fromPrivate(Arrays.copyOf(bytes, bytes.length), true);
            renderKey(key);
        } catch (Exception error) {
            showError(error);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private void importWalletKey() {
        String value = importInput.getText().toString().trim();
        if (TextUtils.isEmpty(value)) {
            Toast.makeText(this, R.string.paper_wallet_input_required, Toast.LENGTH_LONG).show();
            return;
        }
        if (value.startsWith("6P")) {
            promptForImportedBip38(value);
            return;
        }
        new Thread(() -> {
            try {
                ECKey key = decodeInput(value);
                runOnUiThread(() -> renderKey(key));
            } catch (Exception error) {
                runOnUiThread(() -> showError(error));
            }
        }, "paper-wallet-import").start();
    }

    private ECKey decodeInput(String value) {
        if (value.startsWith("6P")) {
            throw new IllegalArgumentException(getString(R.string.paper_wallet_bip38_import_requires_passphrase));
        }
        NetworkParameters network = NetworkConfig.parameters(this);
        return DumpedPrivateKey.fromBase58(network, value).getKey();
    }

    private void renderKey(ECKey key) {
        if (key == null) {
            return;
        }
        try {
            NetworkParameters network = NetworkConfig.parameters(this);
            String type = WalletAddressType.normalize(selectedAddressType);
            if (WalletAddressType.requiresCompressedKey(type) && !key.isCompressed()) {
                Toast.makeText(this, R.string.paper_wallet_address_type_compressed_required, Toast.LENGTH_LONG).show();
                return;
            }
            Address generatedAddress = WalletAddressType.addressForKey(network, key, type);
            String address = generatedAddress.toString();
            currentAddressType = type;
            String privateText;
            if (bip38CheckBox.isChecked()) {
                if (!key.isCompressed()) {
                    Toast.makeText(this, R.string.paper_wallet_bip38_compressed_required, Toast.LENGTH_LONG).show();
                    return;
                }
                // BIP38 uses the key's legacy P2PKH address hash for its standard
                // non-EC-multiply checksum. The selected address type remains the
                // address shown on the paper wallet.
                String bip38Address = WalletAddressType.addressForKey(
                        network, key, WalletAddressType.P2PKH).toString();
                promptForBip38(key, bip38Address, address);
                return;
            } else {
                privateText = key.getPrivateKeyEncoded(network).toString();
            }
            showResult(address, privateText, type);
        } catch (Exception error) {
            showError(error);
        }
    }

    private void promptForBip38(ECKey key, String bip38Address, String displayAddress) {
        EditText passphrase = new EditText(this);
        passphrase.setSingleLine(true);
        passphrase.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passphrase.setHint(R.string.bip38_passphrase_hint);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.paper_wallet_bip38_title)
                .setMessage(R.string.paper_wallet_bip38_message)
                .setView(passphrase)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.paper_wallet_encrypt, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String password = passphrase.getText().toString();
            if (password.isEmpty()) {
                Toast.makeText(this, R.string.bip38_passphrase_required, Toast.LENGTH_LONG).show();
                return;
            }
            dialog.dismiss();
            new Thread(() -> {
                try {
                    String encrypted = Bip38Encoder.encrypt(key, bip38Address, password);
                    runOnUiThread(() -> showResult(displayAddress, encrypted, selectedAddressType));
                } catch (Exception error) {
                    runOnUiThread(() -> showError(error));
                }
            }, "paper-wallet-bip38").start();
        }));
        dialog.show();
    }

    private void showResult(String address, String privateText, String addressType) {
        currentAddress = address;
        currentPrivateText = privateText;
        currentAddressType = WalletAddressType.normalize(addressType);
        addressVisible = true;
        privateVisible = false;
        updateValueVisibility();
        addressQr.setImageBitmap(QrCodeGenerator.generate(address, 520));
        privateQr.setImageBitmap(QrCodeGenerator.generate(privateText, 520));
        resultCard.setVisibility(View.VISIBLE);
        updateActionButtons();
    }

    private void clearResult() {
        importInput.setText("");
        currentAddress = null;
        currentAddressType = null;
        currentPrivateText = null;
        addressValue.setText("");
        privateValue.setText("");
        addressQr.setImageDrawable(null);
        privateQr.setImageDrawable(null);
        addressQr.setVisibility(View.GONE);
        privateQr.setVisibility(View.GONE);
        resultCard.setVisibility(View.GONE);
        updateActionButtons();
    }

    private void updateValueVisibility() {
        TextViewUtils.setTextIfChanged(addressValue, currentAddress == null ? "" : (addressVisible ? currentAddress : "••••••••••••••••••••"));
        TextViewUtils.setTextIfChanged(privateValue, currentPrivateText == null ? "" : (privateVisible ? currentPrivateText : "••••••••••••••••••••"));
        addressQr.setVisibility(addressVisible && !TextUtils.isEmpty(currentAddress) ? View.VISIBLE : View.GONE);
        TextViewUtils.setTextIfChanged(addressTypeValue, currentAddressType == null ? ""
                : getString(R.string.paper_wallet_address_type_value, WalletAddressType.label(this, currentAddressType)));
        privateQr.setVisibility(privateVisible && !TextUtils.isEmpty(currentPrivateText) ? View.VISIBLE : View.GONE);
        addressEyeButton.setImageResource(addressVisible ? R.drawable.ic_visibility_18dp : R.drawable.ic_visibility_off_18dp);
        privateEyeButton.setImageResource(privateVisible ? R.drawable.ic_visibility_18dp : R.drawable.ic_visibility_off_18dp);
    }

    private void updateActionButtons() {
        boolean available = !TextUtils.isEmpty(currentAddress) && !TextUtils.isEmpty(currentPrivateText);
        addressCopyButton.setEnabled(available);
        addressEyeButton.setEnabled(available);
        addressHexButton.setEnabled(available);
        privateCopyButton.setEnabled(available);
        privateEyeButton.setEnabled(available);
        privateHexButton.setEnabled(available);
        printButton.setEnabled(available);
        exportButton.setEnabled(available);
    }

    private void toggleAddressVisibility() {
        addressVisible = !addressVisible;
        updateValueVisibility();
    }

    private void togglePrivateVisibility() {
        privateVisible = !privateVisible;
        updateValueVisibility();
    }

    private void copyToClipboard(String label, String value) {
        if (TextUtils.isEmpty(value)) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(this, R.string.paper_wallet_copied, Toast.LENGTH_SHORT).show();
    }

    private void showHexDialog(String title, String hex) {
        if (TextUtils.isEmpty(hex)) return;
        TextView value = new TextView(this);
        TextViewUtils.configureSelectableMiddleEllipsis(value);
        TextViewUtils.setTextIfChanged(value, hex);
        value.setTextSize(12);
        value.setPadding(24, 8, 24, 8);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(value)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.paper_wallet_copy_hex, (d, w) -> copyToClipboard(title, hex))
                .show();
    }

    private void showPrivateHex() {
        if (TextUtils.isEmpty(currentPrivateText)) return;
        String title;
        String hex;
        if (currentPrivateText.startsWith("6P")) {
            title = getString(R.string.paper_wallet_bip38_hex_title);
            hex = Base58Check.decodePayloadHex(currentPrivateText);
        } else {
            title = getString(R.string.paper_wallet_private_hex_title);
            hex = WifCodec.privateKeyHex(currentPrivateText, NetworkConfig.parameters(this));
        }
        showHexDialog(title, hex);
    }

    private String addressToHex(String address) {
        if (TextUtils.isEmpty(address)) return "";
        try {
            NetworkParameters network = NetworkConfig.parameters(this);
            return WifCodec.toHex(org.bitcoinj.base.Address.fromString(network, address).getHash());
        } catch (Exception error) {
            return Base58Check.addressHashHex(address);
        }
    }

    private void printPaperWallet() {
        if (TextUtils.isEmpty(currentAddress) || TextUtils.isEmpty(currentPrivateText)) return;
        android.print.PrintManager manager = (android.print.PrintManager) getSystemService(Context.PRINT_SERVICE);
        manager.print(getString(R.string.paper_wallet_print_job),
                new PaperWalletPrintAdapter(this, currentAddress, currentAddressType, currentPrivateText,
                        addressVisible, privateVisible), null);
    }

    private void exportText() {
        if (TextUtils.isEmpty(currentAddress) || TextUtils.isEmpty(currentPrivateText)) return;
        exportFileLauncher.launch("bitcoin-paper-wallet.txt");
    }

    private void writeExport(Uri uri) {
        new Thread(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IllegalArgumentException(getString(R.string.paper_wallet_export_failed));
                String addressHex = addressToHex(currentAddress);
        String privateHex = currentPrivateText.startsWith("6P")
                ? Base58Check.decodePayloadHex(currentPrivateText)
                : WifCodec.privateKeyHex(currentPrivateText, NetworkConfig.parameters(this));
        String text = getString(R.string.paper_wallet_export_template,
                        WalletAddressType.label(this, currentAddressType), currentAddress, addressHex, currentPrivateText, privateHex);
                output.write(text.getBytes(StandardCharsets.UTF_8));
                runOnUiThread(() -> Toast.makeText(this, R.string.paper_wallet_export_success, Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                runOnUiThread(() -> showError(error));
            }
        }, "paper-wallet-export").start();
    }

    private void openInputFile() {
        inputFileLauncher.launch(new String[]{"*/*"});
    }

    private void promptForImportedBip38(String encrypted) {
        EditText passphrase = new EditText(this);
        passphrase.setSingleLine(true);
        passphrase.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passphrase.setHint(R.string.bip38_passphrase_hint);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.paper_wallet_bip38_title)
                .setMessage(R.string.paper_wallet_bip38_import_message)
                .setView(passphrase)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.paper_wallet_unlock, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String password = passphrase.getText().toString();
            if (password.isEmpty()) {
                Toast.makeText(this, R.string.bip38_passphrase_required, Toast.LENGTH_LONG).show();
                return;
            }
            dialog.dismiss();
            new Thread(() -> {
                try {
                    NetworkParameters network = NetworkConfig.parameters(this);
                    ECKey key = org.bitcoinj.crypto.BIP38PrivateKey.fromBase58(network, encrypted)
                            .decrypt(password);
                    runOnUiThread(() -> renderKey(key));
                } catch (org.bitcoinj.crypto.BIP38PrivateKey.BadPassphraseException error) {
                    runOnUiThread(() -> Toast.makeText(this, R.string.bip38_wrong_passphrase, Toast.LENGTH_LONG).show());
                } catch (Exception error) {
                    runOnUiThread(() -> showError(error));
                }
            }, "paper-wallet-bip38-import").start();
        }));
        dialog.show();
    }

    private void readInputFile(Uri uri) {
        new Thread(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (input == null) throw new IllegalArgumentException(getString(R.string.paper_wallet_file_failed));
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    if (output.size() > 4096) throw new IllegalArgumentException(getString(R.string.paper_wallet_file_too_large));
                }
                String value = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
                runOnUiThread(() -> {
                    if (value.startsWith("6P")) {
                        promptForImportedBip38(value);
                    } else {
                        importInput.setText(value);
                        importInput.setSelection(importInput.length());
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> showError(error));
            }
        }, "paper-wallet-file").start();
    }

    private void showError(Exception error) {
        String message = error.getMessage();
        Toast.makeText(this, getString(R.string.paper_wallet_failed,
                message == null ? error.getClass().getSimpleName() : message), Toast.LENGTH_LONG).show();
    }

    private String addressTypeFromCheckedId(int checkedId) {
        if (checkedId == R.id.walletAddressTypeP2wpkh) return WalletAddressType.P2WPKH;
        if (checkedId == R.id.walletAddressTypeP2shP2wpkh) return WalletAddressType.P2SH_P2WPKH;
        if (checkedId == R.id.walletAddressTypeP2tr) return WalletAddressType.P2TR;
        return WalletAddressType.P2PKH;
    }

    private static boolean isValidSecret(byte[] value) {
        for (byte b : value) if (b != 0) return true;
        return false;
    }
}
