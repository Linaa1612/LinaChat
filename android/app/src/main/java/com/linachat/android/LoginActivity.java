package com.linachat.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LoginActivity extends Activity {
    private static final int PICK_AVATAR = 11;
    private static final long MAX_AVATAR_BYTES = 5L * 1024 * 1024;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private EditText usernameInput;
    private EditText serverAddressInput;
    private EditText serverPortInput;
    private TextView statusView;
    private Button connectButton;
    private String avatarBase64;
    private boolean connecting;
    private volatile boolean activityDestroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        WindowInsetsHelper.apply(this);
        usernameInput = findViewById(R.id.usernameInput);
        serverAddressInput = findViewById(R.id.serverAddressInput);
        serverPortInput = findViewById(R.id.serverPortInput);
        statusView = findViewById(R.id.loginStatus);
        connectButton = findViewById(R.id.connectButton);

        var preferences = getSharedPreferences("linachat", MODE_PRIVATE);
        serverAddressInput.setText(preferences.getString("last_server_ip", ""));
        serverPortInput.setText(preferences.getString("last_server_port", "5050"));
        findViewById(R.id.avatarButton).setOnClickListener(view -> chooseAvatar());
        connectButton.setOnClickListener(view -> connect());
    }

    private void chooseAvatar() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.setType("image/*");
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(pick, PICK_AVATAR);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_AVATAR || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri imageUri = data.getData();
        networkExecutor.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(imageUri);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (input == null) throw new IllegalArgumentException("Image inaccessible.");
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_AVATAR_BYTES) {
                        throw new IllegalArgumentException("Photo trop volumineuse (5 Mo maximum).");
                    }
                    output.write(buffer, 0, count);
                }
                byte[] bytes = output.toByteArray();
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                        || (long) bounds.outWidth * bounds.outHeight > 40_000_000L) {
                    throw new IllegalArgumentException("Format de photo non pris en charge.");
                }
                BitmapFactory.Options sample = new BitmapFactory.Options();
                sample.inSampleSize = 4;
                android.graphics.Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, sample);
                if (decoded == null) {
                    throw new IllegalArgumentException("Format de photo non pris en charge.");
                }
                decoded.recycle();
                String encoded = Base64.encodeToString(bytes, Base64.NO_WRAP);
                runOnUiThread(() -> {
                    if (activityDestroyed) return;
                    avatarBase64 = encoded;
                    statusView.setText("Photo de profil sélectionnée.");
                    statusView.setTextColor(getColor(R.color.lina_green));
                });
            } catch (Exception error) {
                if (!activityDestroyed) {
                    runOnUiThread(() -> showError(error.getMessage() == null
                            ? "Impossible de charger cette photo." : error.getMessage()));
                }
            }
        });
    }

    private void connect() {
        if (connecting) return;
        String username = usernameInput.getText().toString().trim();
        String host = serverAddressInput.getText().toString().trim();
        String portText = serverPortInput.getText().toString().trim();
        if (username.isEmpty()) {
            showError("Saisissez votre nom d'utilisateur.");
            return;
        }
        if (!isValidIpAddress(host)) {
            showError("Adresse IP invalide. Saisissez une adresse IPv4 ou IPv6.");
            return;
        }
        int port = 5050;
        if (!portText.isEmpty()) {
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException error) {
                showError("Le port doit être un nombre entre 1 et 65535.");
                return;
            }
        }
        if (port < 1 || port > 65535) {
            showError("Le port doit être un nombre entre 1 et 65535.");
            return;
        }

        connecting = true;
        connectButton.setEnabled(false);
        statusView.setTextColor(getColor(R.color.lina_muted));
        statusView.setText("Vérification et connexion au serveur…");
        final int serverPort = port;
        final ChatUser profile = ChatUser.create(username, avatarBase64);

        networkExecutor.execute(() -> {
            ProtocolClient client = new ProtocolClient(new EmptyListener());
            try {
                client.connect(host, serverPort, profile);
                if (activityDestroyed) {
                    client.disconnect();
                    return;
                }
                Session.user = profile;
                Session.client = client;
                getSharedPreferences("linachat", MODE_PRIVATE).edit()
                        .putString("last_server_ip", host)
                        .putString("last_server_port", Integer.toString(serverPort))
                        .apply();
                runOnUiThread(() -> {
                    if (activityDestroyed) {
                        Session.client = null;
                        Session.user = null;
                        client.disconnect();
                        return;
                    }
                    connecting = false;
                    startActivity(new Intent(this, ChatActivity.class));
                    finish();
                });
            } catch (Exception error) {
                client.close();
                String message = error.getMessage();
                if (!activityDestroyed) {
                    runOnUiThread(() -> {
                        connecting = false;
                        connectButton.setEnabled(true);
                        showError(message == null || message.trim().isEmpty()
                                ? "Serveur inaccessible. Vérifiez l'adresse, le port et le réseau."
                                : "Connexion impossible : " + message);
                    });
                }
            }
        });
    }

    private boolean isValidIpAddress(String value) {
        if (value.isEmpty() || value.contains(" ") || value.contains("/") || value.contains("%")) {
            return false;
        }
        if (value.indexOf(':') >= 0) {
            try {
                return InetAddress.getByName(value) instanceof Inet6Address;
            } catch (Exception error) {
                return false;
            }
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            try {
                int octet = Integer.parseInt(part);
                if (octet < 0 || octet > 255) return false;
            } catch (NumberFormatException error) {
                return false;
            }
        }
        return true;
    }

    private void showError(String message) {
        statusView.setTextColor(getColor(R.color.lina_error));
        statusView.setText(message);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        activityDestroyed = true;
        networkExecutor.shutdownNow();
    }

    private static final class EmptyListener implements ProtocolClient.Listener {
        @Override public void onContacts(java.util.List<ChatUser> users) {}
        @Override public void onHistory(java.util.List<ChatMessage> messages) {}
        @Override public void onMessage(ChatMessage message) {}
        @Override public void onError(String error) {}
        @Override public void onAiResponse(String text) {}
        @Override public void onAiError(String error) {}
    }
}
