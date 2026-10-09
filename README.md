#  ₿itcoin Wallet Bitcoin Wallet

Lightweight Bitcoin Wallet for Android

Bitcoin Wallet is a lightweight, open-source Bitcoin wallet for Android, built with BitcoinJ and designed with a simple, clean interface.

---

✨ Features

- ₿ Bitcoin Mainnet
  Send and receive real Bitcoin.

- 👛 Wallet
  Create and load a Bitcoin wallet.

- 🔐 Backup & Restore
  Securely backup and restore wallet data.

- 📤 Send
  Send Bitcoin with transaction details and fee information.

- 📥 Receive
  Display Bitcoin addresses and QR codes.

- 👁️ Watch-Only
  Monitor Bitcoin addresses without private keys.

- 📜 Transactions
  View wallet transaction history.

- 🔄 Synchronization
  Synchronize wallet data with Bitcoin Mainnet or Signet. Mainnet and Signet each support Lite and Full Block sync modes.

- 🌓 System Theme
  Automatically follows the Android light/dark system theme.

---

📱 Screens

Bitcoin Wallet is designed around a simple wallet interface without unnecessary graphics or visual components.

The interface focuses on:

- Wallet balance
- Selected Bitcoin address
- Receive QR code
- Transaction history
- Send and receive actions
- Watch-only address monitoring

---

🔎 Watch-Only

Bitcoin Wallet supports watch-only Bitcoin addresses.

A watch-only address can be used to:

- Monitor the address balance
- View incoming and outgoing transactions
- Display the address and QR code

Watch-only addresses do not contain private keys and cannot be used to spend Bitcoin.

---

🌐 Network

Bitcoin Wallet supports:

Bitcoin Mainnet
Bitcoin Signet

Each network keeps separate wallet and blockchain storage. Sync mode can be selected independently per network:

- Lite: filtered block synchronization with lower data and resource usage.
- Full Block: complete block transaction download for richer blockchain data.

---

🛠️ Built With

- Android
- Java
- BitcoinJ
- Gradle

---

🔐 Security

Bitcoin Wallet is designed to keep wallet data on the Android device.

Always maintain a secure backup of your wallet before:

- Restoring a wallet
- Moving to another device
- Modifying wallet data
- Performing wallet maintenance

Never share

- Wallet backups
- Private keys
- Seed phrases
- Wallet credentials

Bitcoin transactions are irreversible. Always verify the recipient address and transaction amount before confirming a transaction.

---

📜 License

Bitcoin Wallet is licensed under the:

Apache License 2.0

See `LICENSE` for the complete license text.

---

⚠️ Disclaimer

Bitcoin Wallet is open-source software provided for informational and personal use.

Use the application responsibly and always maintain secure backups of your wallet data.

---

## NFC Request Transport

Bitcoin Wallet keeps the legacy-compatible PaymentRequest payload while using a dual transport strategy:

- Modern Bitcoin Wallet peers: Host Card Emulation (HCE) + NFC Reader Mode.
- legacy NDEF peers: NDEF MIME `application/bitcoin-paymentrequest` when the platform exposes NDEF push.
- NFC tags: NDEF PaymentRequest or `bitcoin:` URI can be read while Send is open.
- QR and Share remain available as transport-independent fallbacks.

The same PaymentRequest is used across the transports, so the wallet does not need a separate request format for each NFC path.

<p align="center">₿ Bitcoin Wallet

A simple Bitcoin wallet for Android.

</p>
