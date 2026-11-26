package com.linachat.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public final class ChatActivity extends Activity implements ProtocolClient.Listener {
    private static final int PICK_ATTACHMENT = 21;
    private static final int SAVE_ATTACHMENT = 22;
    private static final int REQUEST_MICROPHONE = 23;
    private static final long MAX_ATTACHMENT_BYTES = 15L * 1024 * 1024;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, List<ChatMessage>> conversations = new HashMap<>();
    private final ArrayDeque<String> historyRequests = new ArrayDeque<>();
    private final Map<String, ChatUser> contactsById = new HashMap<>();
    private final List<MediaPlayer> audioPlayers = new CopyOnWriteArrayList<>();
    private final WavRecorder recorder = new WavRecorder();
    private ChatUser currentUser;
    private ChatUser selectedContact;
    private ChatMessage pendingDownload;
    private File recordedAudio;
    private long recordedDuration;
    private long recordStartedAt;
    private boolean assistantOpen;
    private boolean awaitingAi;
    private LinearLayout contactsContainer;
    private LinearLayout messagesContainer;
    private LinearLayout assistantMessages;
    private LinearLayout composerPanel;
    private LinearLayout recordingPanel;
    private LinearLayout audioPreviewPanel;
    private FrameLayout chatPanel;
    private ScrollView conversationScroll;
    private ScrollView assistantScroll;
    private TextView profileName;
    private TextView connectionStatus;
    private TextView conversationTitle;
    private TextView recordingStatus;
    private EditText messageInput;
    private EditText assistantInput;
    private Button assistantSend;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        WindowInsetsHelper.apply(this);
        if (Session.client == null || Session.user == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        currentUser = Session.user;
        bindViews();
        Session.client.setListener(this);
        profileName.setText(currentUser.name);
        setAvatar(findViewById(R.id.profileAvatar), currentUser.avatarBase64);
        findViewById(R.id.assistantButton).setOnClickListener(view -> toggleAssistant());
        findViewById(R.id.disconnectButton).setOnClickListener(view -> disconnect());
        findViewById(R.id.sendButton).setOnClickListener(view -> sendText());
        messageInput.setOnEditorActionListener((view, actionId, event) -> {
            sendText();
            return true;
        });
        findViewById(R.id.attachButton).setOnClickListener(view -> chooseAttachment());
        findViewById(R.id.emojiButton).setOnClickListener(view -> showEmojiPicker());
        findViewById(R.id.recordButton).setOnClickListener(view -> requestRecording());
        findViewById(R.id.stopRecordingButton).setOnClickListener(view -> finishRecording());
        findViewById(R.id.cancelRecordingButton).setOnClickListener(view -> cancelRecording());
        findViewById(R.id.previewPlayButton).setOnClickListener(view -> playPreview());
        findViewById(R.id.sendAudioButton).setOnClickListener(view -> sendRecording());
        findViewById(R.id.deleteAudioButton).setOnClickListener(view -> deleteRecording());
        assistantSend.setOnClickListener(view -> sendAiRequest());
        assistantInput.setOnEditorActionListener((view, actionId, event) -> {
            sendAiRequest();
            return true;
        });
        connectionStatus.setText("Connecté au serveur");
    }

    private void bindViews() {
        contactsContainer = findViewById(R.id.contactsContainer);
        messagesContainer = findViewById(R.id.messagesContainer);
        assistantMessages = findViewById(R.id.assistantMessages);
        composerPanel = findViewById(R.id.composerPanel);
        recordingPanel = findViewById(R.id.recordingPanel);
        audioPreviewPanel = findViewById(R.id.audioPreviewPanel);
        chatPanel = findViewById(R.id.chatPanel);
        conversationScroll = findViewById(R.id.conversationScroll);
        assistantScroll = findViewById(R.id.assistantScroll);
        profileName = findViewById(R.id.profileName);
        connectionStatus = findViewById(R.id.connectionStatus);
        conversationTitle = findViewById(R.id.conversationTitle);
        recordingStatus = findViewById(R.id.recordingStatus);
        messageInput = findViewById(R.id.messageInput);
        assistantInput = findViewById(R.id.assistantInput);
        assistantSend = findViewById(R.id.assistantSend);
    }

    @Override
    public void onContacts(List<ChatUser> users) {
        String oldSelection = selectedContact == null ? null : selectedContact.id;
        contactsById.clear();
        contactsContainer.removeAllViews();
        for (ChatUser user : users) {
            if (user.id.equals(currentUser.id)) continue;
            contactsById.put(user.id, user);
            LinearLayout contact = new LinearLayout(this);
            contact.setGravity(Gravity.CENTER);
            contact.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable chipBackground = new GradientDrawable();
            chipBackground.setColor(getColor(user.online ? R.color.lina_teal : R.color.lina_green));
            chipBackground.setCornerRadius(dp(10));
            contact.setBackground(chipBackground);
            ImageView avatar = new ImageView(this);
            avatar.setImageResource(android.R.drawable.sym_def_app_icon);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            contact.addView(avatar, new LinearLayout.LayoutParams(dp(34), dp(34)));
            TextView contactName = new TextView(this);
            contactName.setText((user.online ? "● " : "○ ") + user.name);
            contactName.setTextColor(Color.WHITE);
            contactName.setTextSize(10);
            contactName.setMaxLines(1);
            contact.addView(contactName);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    dp(78), dp(64));
            params.setMargins(dp(3), 0, dp(3), 0);
            contactsContainer.addView(contact, params);
            loadContactAvatar(user.avatarBase64, avatar);
            contact.setOnClickListener(view -> selectContact(user));
        }
        if (oldSelection != null && contactsById.containsKey(oldSelection)) {
            selectedContact = contactsById.get(oldSelection);
            connectionStatus.setText(selectedContact.online ? "En ligne" : "Hors ligne");
        } else if (oldSelection != null && selectedContact != null) {
            selectedContact = new ChatUser(selectedContact.id, selectedContact.name,
                    selectedContact.status, selectedContact.avatarBase64, false);
            conversationTitle.setText(selectedContact.name + " · Hors ligne");
            connectionStatus.setText("Hors ligne");
        }
        if (selectedContact == null && users.size() <= 1) {
            connectionStatus.setText("Aucun autre utilisateur connecté");
        }
    }

    private void selectContact(ChatUser user) {
        selectedContact = user;
        conversationTitle.setText(user.name + (user.online ? " · En ligne" : " · Hors ligne"));
        connectionStatus.setText(user.online ? "En ligne" : "Hors ligne");
        messagesContainer.removeAllViews();
        for (ChatMessage message : conversations.getOrDefault(user.id, new ArrayList<>())) {
            renderMessage(message);
        }
        historyRequests.addLast(user.id);
        Session.client.selectConversation(user.id);
        messageInput.setHint("Écrire à " + user.name + "…");
    }

    @Override
    public void onHistory(List<ChatMessage> messages) {
        if (historyRequests.isEmpty()) return;
        String contactId = historyRequests.removeFirst();
        LinkedHashMap<String, ChatMessage> merged = new LinkedHashMap<>();
        for (ChatMessage message : messages) merged.put(message.id, message);
        for (ChatMessage message : conversations.getOrDefault(contactId, new ArrayList<>())) {
            merged.put(message.id, message);
        }
        conversations.put(contactId, new ArrayList<>(merged.values()));
        if (selectedContact != null && selectedContact.id.equals(contactId)) {
            messagesContainer.removeAllViews();
            for (ChatMessage message : merged.values()) renderMessage(message);
        }
    }

    @Override
    public void onMessage(ChatMessage message) {
        String partner = message.conversationPartner(currentUser.id);
        conversations.computeIfAbsent(partner, ignored -> new ArrayList<>()).add(message);
        if (selectedContact != null && selectedContact.id.equals(partner)) renderMessage(message);
    }

    @Override
    public void onError(String error) {
        connectionStatus.setText(error);
        Toast.makeText(this, error, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onAiResponse(String text) {
        awaitingAi = false;
        assistantSend.setEnabled(true);
        addAiBubble(text, false);
    }

    @Override
    public void onAiError(String error) {
        awaitingAi = false;
        assistantSend.setEnabled(true);
        addAiBubble(error, false);
        Toast.makeText(this, error, Toast.LENGTH_LONG).show();
    }

    private void sendText() {
        String body = messageInput.getText().toString().trim();
        if (selectedContact == null) {
            Toast.makeText(this, "Sélectionnez un contact.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (body.isEmpty()) return;
        ChatMessage message = ChatMessage.text(currentUser.id, selectedContact.id, body);
        conversations.computeIfAbsent(selectedContact.id, ignored -> new ArrayList<>()).add(message);
        renderMessage(message);
        messageInput.setText("");
        Session.client.sendText(selectedContact.id, body);
    }

    private void toggleAssistant() {
        assistantOpen = !assistantOpen;
        findViewById(R.id.assistantPanel).setVisibility(assistantOpen ? View.VISIBLE : View.GONE);
        findViewById(R.id.conversationTitle).setVisibility(assistantOpen ? View.GONE : View.VISIBLE);
        findViewById(R.id.conversationScroll).setVisibility(assistantOpen ? View.GONE : View.VISIBLE);
        composerPanel.setVisibility(assistantOpen ? View.GONE : View.VISIBLE);
        recordingPanel.setVisibility(View.GONE);
        audioPreviewPanel.setVisibility(View.GONE);
        ((Button) findViewById(R.id.assistantButton)).setText(assistantOpen ? "✕ IA" : "✨ IA");
    }

    private void sendAiRequest() {
        String text = assistantInput.getText().toString().trim();
        if (text.isEmpty() || awaitingAi) return;
        addAiBubble(text, true);
        assistantInput.setText("");
        awaitingAi = true;
        assistantSend.setEnabled(false);
        Session.client.sendAiRequest(text);
    }

    private void addAiBubble(String text, boolean outgoing) {
        TextView bubble = new TextView(this);
        bubble.setText(text);
        bubble.setTextColor(getColor(R.color.lina_text));
        bubble.setTextSize(15);
        bubble.setPadding(dp(13), dp(10), dp(13), dp(10));
        bubble.setBackgroundColor(getColor(outgoing ? R.color.lina_outgoing : android.R.color.white));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = outgoing ? Gravity.END : Gravity.START;
        params.setMargins(0, dp(4), 0, dp(4));
        assistantMessages.addView(bubble, params);
        assistantScroll.post(() -> assistantScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void showEmojiPicker() {
        String[] emojis = {"😀", "😃", "😄", "😁", "😆", "😂", "🤣", "😊", "😍", "🥰",
                "😘", "😎", "🤔", "😭", "😢", "😡", "👍", "👎", "👏", "🙏", "❤️", "💚",
                "💯", "🔥", "🎉", "☕", "🎵", "🎤", "⚽", "🇲🇦", "🇫🇷", "✨"};
        GridView grid = new GridView(this);
        grid.setNumColumns(6);
        grid.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, emojis));
        grid.setOnItemClickListener((parent, view, position, id) -> {
            int cursor = Math.max(messageInput.getSelectionStart(), 0);
            messageInput.getText().insert(cursor, emojis[position]);
        });
        new AlertDialog.Builder(this).setTitle("Choisir un emoji")
                .setView(grid).setNegativeButton("Fermer", null).show();
    }

    private void chooseAttachment() {
        if (selectedContact == null) {
            Toast.makeText(this, "Sélectionnez un contact avant de joindre un fichier.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.setType("*/*");
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(pick, PICK_ATTACHMENT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == PICK_ATTACHMENT) {
            sendAttachment(uri);
        } else if (requestCode == SAVE_ATTACHMENT && pendingDownload != null) {
            saveAttachment(uri, pendingDownload);
        }
    }

    private void sendAttachment(Uri uri) {
        String recipientId = selectedContact.id;
        ioExecutor.execute(() -> {
            try {
                byte[] bytes = readUri(uri, MAX_ATTACHMENT_BYTES);
                String name = displayName(uri);
                String mime = getContentResolver().getType(uri);
                String type = isImageMime(mime) && isValidImage(bytes)
                        ? "IMAGE" : "FILE";
                ChatMessage message = ChatMessage.attachment(type, currentUser.id, recipientId,
                        name, bytes, 0);
                Session.client.sendAttachment(message);
                runOnUiThread(() -> {
                    conversations.computeIfAbsent(recipientId, ignored -> new ArrayList<>()).add(message);
                    if (selectedContact != null && selectedContact.id.equals(recipientId)) renderMessage(message);
                    connectionStatus.setText(type.equals("IMAGE") ? "Image envoyée." : "Fichier envoyé.");
                });
            } catch (Exception error) {
                runOnUiThread(() -> showToast(error.getMessage() == null
                        ? "Impossible d'envoyer le fichier." : error.getMessage()));
            }
        });
    }

    private byte[] readUri(Uri uri, long limit) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalArgumentException("Fichier inaccessible.");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > limit) {
                    throw new IllegalArgumentException("Fichier trop volumineux (15 Mo maximum).");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) return safeFileName(name);
            }
        } catch (Exception ignored) {
            // Le nom de repli permet de garder le transfert si le fournisseur n'expose pas DISPLAY_NAME.
        }
        return "piece-jointe";
    }

    private String safeFileName(String name) {
        String clean = name.replace('\\', '_').replace('/', '_').replace('\n', '_').replace('\r', '_');
        return clean.trim().isEmpty() ? "piece-jointe" : clean;
    }

    private boolean isImageMime(String mime) {
        return mime != null && (mime.equalsIgnoreCase("image/png")
                || mime.equalsIgnoreCase("image/jpeg") || mime.equalsIgnoreCase("image/gif"));
    }

    private boolean isValidImage(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                || (long) bounds.outWidth * bounds.outHeight > 40_000_000L) return false;
        BitmapFactory.Options sample = new BitmapFactory.Options();
        sample.inSampleSize = 4;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, sample);
        if (bitmap == null) return false;
        bitmap.recycle();
        return true;
    }

    private void renderMessage(ChatMessage message) {
        boolean outgoing = message.sender.equals(currentUser.id);
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(11), dp(8), dp(11), dp(7));
        bubble.setBackgroundColor(getColor(outgoing ? R.color.lina_outgoing : android.R.color.white));
        LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels - dp(48), dp(340)),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bubbleParams.gravity = outgoing ? Gravity.END : Gravity.START;
        bubbleParams.setMargins(0, dp(4), 0, dp(4));

        if (!outgoing) {
            TextView sender = new TextView(this);
            ChatUser user = contactsById.get(message.sender);
            sender.setText(user == null ? message.sender : user.name);
            sender.setTextColor(getColor(R.color.lina_green));
            sender.setTextSize(12);
            sender.setTypeface(null, Typeface.BOLD);
            bubble.addView(sender);
        }
        if ("IMAGE".equals(message.type)) {
            renderImage(message, bubble);
        } else if ("FILE".equals(message.type)) {
            renderFile(message, bubble);
        } else if ("AUDIO".equals(message.type)) {
            renderAudio(message, bubble);
        } else {
            TextView body = new TextView(this);
            body.setText(message.text == null ? "" : message.text);
            body.setTextColor(getColor(R.color.lina_text));
            body.setTextSize(16);
            body.setTextIsSelectable(true);
            bubble.addView(body);
        }
        TextView time = new TextView(this);
        time.setText(formatTime(message.timestamp));
        time.setTextColor(getColor(R.color.lina_muted));
        time.setTextSize(10);
        time.setGravity(Gravity.END);
        bubble.addView(time);
        messagesContainer.addView(bubble, bubbleParams);
        conversationScroll.post(() -> conversationScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void renderImage(ChatMessage message, LinearLayout bubble) {
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setMaxHeight(dp(260));
        image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        bubble.addView(image, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        image.setOnClickListener(view -> openAttachment(message));
        ioExecutor.execute(() -> {
            try {
                byte[] bytes = Base64.decode(message.contentBase64, Base64.DEFAULT);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                        || (long) bounds.outWidth * bounds.outHeight > 40_000_000L) {
                    throw new IllegalArgumentException("Image trop volumineuse.");
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = Math.max(1,
                        Math.max(bounds.outWidth / 1400, bounds.outHeight / 1000));
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (bitmap == null) throw new IllegalArgumentException("Image invalide.");
                runOnUiThread(() -> image.setImageBitmap(bitmap));
            } catch (Exception error) {
                runOnUiThread(() -> addSmallLabel(bubble, "Image indisponible."));
            }
        });
    }

    private void renderFile(ChatMessage message, LinearLayout bubble) {
        addSmallLabel(bubble, "📎  " + safeFileName(message.fileName) + " · " + formatSize(message.fileSize));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button open = actionButton("Ouvrir");
        Button download = actionButton("Télécharger");
        actions.addView(open);
        actions.addView(download);
        bubble.addView(actions);
        open.setOnClickListener(view -> openAttachment(message));
        download.setOnClickListener(view -> requestDownload(message));
    }

    private void renderAudio(ChatMessage message, LinearLayout bubble) {
        Button play = actionButton("▶ Écouter le message vocal");
        bubble.addView(play);
        play.setOnClickListener(view -> playAudioMessage(message, play));
    }

    private Button actionButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinHeight(dp(38));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.lina_mint)));
        button.setTextColor(getColor(R.color.lina_green));
        return button;
    }

    private void addSmallLabel(LinearLayout parent, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(getColor(R.color.lina_text));
        label.setTextSize(14);
        parent.addView(label);
    }

    private File cacheAttachment(ChatMessage message) throws Exception {
        byte[] bytes = Base64.decode(message.contentBase64, Base64.DEFAULT);
        if (bytes.length != message.fileSize) throw new IllegalArgumentException("Taille de pièce jointe invalide.");
        File directory = new File(getCacheDir(), "attachments");
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Cache indisponible.");
        File target = new File(directory, safeFileName(message.id + "-" + message.fileName));
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(bytes);
        }
        return target;
    }

    private void openAttachment(ChatMessage message) {
        ioExecutor.execute(() -> {
            try {
                File file = cacheAttachment(message);
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
                Intent open = new Intent(Intent.ACTION_VIEW);
                open.setDataAndType(uri, mimeForName(message.fileName));
                open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> {
                    try {
                        startActivity(Intent.createChooser(open, "Ouvrir avec"));
                    } catch (android.content.ActivityNotFoundException error) {
                        showToast("Aucune application ne peut ouvrir ce fichier.");
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> showToast(error.getMessage() == null
                        ? "Impossible d'ouvrir la pièce jointe." : error.getMessage()));
            }
        });
    }

    private void requestDownload(ChatMessage message) {
        pendingDownload = message;
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.setType(mimeForName(message.fileName));
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.putExtra(Intent.EXTRA_TITLE, safeFileName(message.fileName));
        startActivityForResult(create, SAVE_ATTACHMENT);
    }

    private void saveAttachment(Uri destination, ChatMessage message) {
        ioExecutor.execute(() -> {
            try {
                byte[] bytes = Base64.decode(message.contentBase64, Base64.DEFAULT);
                if (bytes.length != message.fileSize) throw new IllegalArgumentException("Taille de fichier invalide.");
                try (java.io.OutputStream output = getContentResolver().openOutputStream(destination)) {
                    if (output == null) throw new IllegalStateException("Destination inaccessible.");
                    output.write(bytes);
                }
                runOnUiThread(() -> showToast("Fichier téléchargé."));
            } catch (Exception error) {
                runOnUiThread(() -> showToast("Téléchargement impossible : " + error.getMessage()));
            } finally {
                pendingDownload = null;
            }
        });
    }

    private String mimeForName(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return "application/octet-stream";
    }

    private void playAudioMessage(ChatMessage message, Button button) {
        ioExecutor.execute(() -> {
            try {
                File audio = cacheAttachment(message);
                MediaPlayer player = new MediaPlayer();
                player.setDataSource(audio.getAbsolutePath());
                player.setOnCompletionListener(completed -> {
                    runOnUiThread(() -> button.setText("▶ Écouter le message vocal"));
                    completed.release();
                    audioPlayers.remove(completed);
                });
                player.setOnErrorListener((failed, what, extra) -> {
                    failed.release();
                    audioPlayers.remove(failed);
                    runOnUiThread(() -> showToast("Impossible de lire ce message vocal."));
                    return true;
                });
                player.setOnPreparedListener(prepared -> {
                    runOnUiThread(() -> button.setText("❚❚ Lecture audio"));
                    prepared.start();
                });
                audioPlayers.add(player);
                player.prepareAsync();
                runOnUiThread(() -> button.setText("⏳ Chargement audio…"));
            } catch (Exception error) {
                runOnUiThread(() -> showToast("Impossible de lire ce message vocal."));
            }
        });
    }

    private void requestRecording() {
        if (selectedContact == null) {
            showToast("Sélectionnez un contact avant d'enregistrer.");
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
            return;
        }
        startRecording();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MICROPHONE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording();
            } else {
                showToast("L'autorisation du microphone est nécessaire pour enregistrer.");
            }
        }
    }

    private void startRecording() {
        ioExecutor.execute(() -> {
            try {
                recorder.start(getCacheDir());
                recordStartedAt = System.currentTimeMillis();
                runOnUiThread(() -> {
                    composerPanel.setVisibility(View.GONE);
                    recordingPanel.setVisibility(View.VISIBLE);
                    recordingStatus.setText("● Enregistrement · 00:00");
                    updateRecordingTimer();
                });
            } catch (Exception error) {
                runOnUiThread(() -> showToast(error.getMessage() == null
                        ? "Impossible de démarrer le microphone." : error.getMessage()));
            }
        });
    }

    private void updateRecordingTimer() {
        if (recordingPanel.getVisibility() != View.VISIBLE) return;
        long seconds = Math.max(0, (System.currentTimeMillis() - recordStartedAt) / 1000);
        recordingStatus.setText(String.format(Locale.getDefault(),
                "● Enregistrement · %02d:%02d", seconds / 60, seconds % 60));
        mainHandler.postDelayed(this::updateRecordingTimer, 1000);
    }

    private void finishRecording() {
        recordingPanel.setVisibility(View.GONE);
        recordingStatus.setText("Finalisation de l'audio…");
        ioExecutor.execute(() -> {
            try {
                File audio = recorder.stop();
                long duration = recorder.durationMillis();
                if (audio.length() <= 44 || duration < 250) {
                    audio.delete();
                    throw new IllegalArgumentException("Enregistrement trop court.");
                }
                runOnUiThread(() -> {
                    recordedAudio = audio;
                    recordedDuration = duration;
                    composerPanel.setVisibility(View.GONE);
                    audioPreviewPanel.setVisibility(View.VISIBLE);
                    showToast("Écoutez votre message avant l'envoi.");
                });
            } catch (Exception error) {
                recorder.cancel();
                runOnUiThread(() -> {
                    composerPanel.setVisibility(View.VISIBLE);
                    showToast(error.getMessage() == null
                            ? "Impossible de finaliser le message vocal." : error.getMessage());
                });
            }
        });
    }

    private void cancelRecording() {
        recordingPanel.setVisibility(View.GONE);
        composerPanel.setVisibility(View.VISIBLE);
        ioExecutor.execute(recorder::cancel);
    }

    private void playPreview() {
        if (recordedAudio == null || !recordedAudio.isFile()) return;
        File preview = recordedAudio;
        ioExecutor.execute(() -> {
            try {
                MediaPlayer player = new MediaPlayer();
                player.setDataSource(preview.getAbsolutePath());
                player.setOnCompletionListener(completed -> {
                    completed.release();
                    audioPlayers.remove(completed);
                });
                player.prepare();
                player.start();
                audioPlayers.add(player);
            } catch (Exception error) {
                runOnUiThread(() -> showToast("Impossible de lire l'aperçu audio."));
            }
        });
    }

    private void sendRecording() {
        if (recordedAudio == null || selectedContact == null) return;
        String recipientId = selectedContact.id;
        File audio = recordedAudio;
        long duration = recordedDuration;
        ioExecutor.execute(() -> {
            try {
                byte[] bytes = java.nio.file.Files.readAllBytes(audio.toPath());
                if (bytes.length > MAX_ATTACHMENT_BYTES) {
                    throw new IllegalArgumentException("Audio trop volumineux (15 Mo maximum).");
                }
                ChatMessage message = ChatMessage.attachment("AUDIO", currentUser.id,
                        recipientId, audio.getName(), bytes, duration);
                Session.client.sendAttachment(message);
                runOnUiThread(() -> {
                    conversations.computeIfAbsent(recipientId, ignored -> new ArrayList<>()).add(message);
                    if (selectedContact != null && selectedContact.id.equals(recipientId)) renderMessage(message);
                    deleteRecording();
                    showToast("Message vocal envoyé.");
                });
            } catch (Exception error) {
                runOnUiThread(() -> showToast(error.getMessage() == null
                        ? "Impossible d'envoyer l'audio." : error.getMessage()));
            }
        });
    }

    private void deleteRecording() {
        audioPreviewPanel.setVisibility(View.GONE);
        composerPanel.setVisibility(View.VISIBLE);
        if (recordedAudio != null) recordedAudio.delete();
        recordedAudio = null;
        recordedDuration = 0;
    }

    private void setAvatar(ImageView view, String avatar) {
        if (avatar == null || avatar.trim().isEmpty()) {
            view.setImageResource(android.R.drawable.sym_def_app_icon);
            return;
        }
        try {
            byte[] bytes = Base64.decode(avatar, Base64.DEFAULT);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 4;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap != null) view.setImageBitmap(bitmap);
            else view.setImageResource(android.R.drawable.sym_def_app_icon);
        } catch (IllegalArgumentException error) {
            view.setImageResource(android.R.drawable.sym_def_app_icon);
        }
    }

    private void loadContactAvatar(String encoded, ImageView view) {
        if (encoded == null || encoded.trim().isEmpty()) return;
        ioExecutor.execute(() -> {
            try {
                byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 4;
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (bitmap != null) runOnUiThread(() -> view.setImageBitmap(bitmap));
            } catch (IllegalArgumentException ignored) {
                // Un avatar invalide ne doit pas empêcher l'affichage du contact.
            }
        });
    }

    private String formatTime(String timestamp) {
        try {
            java.time.Instant instant = java.time.Instant.parse(timestamp);
            return java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                    .withZone(java.time.ZoneId.systemDefault()).format(instant);
        } catch (Exception error) {
            return "";
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " o";
        if (bytes < 1024 * 1024) return String.format(Locale.FRANCE, "%.1f Ko", bytes / 1024.0);
        return String.format(Locale.FRANCE, "%.1f Mo", bytes / (1024.0 * 1024.0));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void showToast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private void disconnect() {
        ProtocolClient client = Session.client;
        Session.client = null;
        Session.user = null;
        if (client != null) client.disconnect();
        finish();
    }

    @Override
    public void onBackPressed() {
        if (assistantOpen) {
            toggleAssistant();
        } else {
            disconnect();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) {
            mainHandler.removeCallbacksAndMessages(null);
            ioExecutor.shutdownNow();
            for (MediaPlayer player : new ArrayList<>(audioPlayers)) {
                try {
                    player.stop();
                    player.release();
                } catch (Exception ignored) {
                    // Certains lecteurs ont déjà été libérés à la fin de la lecture.
                }
            }
            audioPlayers.clear();
            new Thread(recorder::cancel, "linachat-audio-cleanup").start();
            if (recordedAudio != null) recordedAudio.delete();
            if (Session.client != null) {
                Session.client.close();
                Session.client = null;
                Session.user = null;
            }
        }
    }
}
