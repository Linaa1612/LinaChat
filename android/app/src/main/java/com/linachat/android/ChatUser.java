package com.linachat.android;

import org.json.JSONObject;

import java.util.UUID;

final class ChatUser {
    final String id;
    final String name;
    final String status;
    final String avatarBase64;
    final boolean online;

    ChatUser(String id, String name, String status, String avatarBase64, boolean online) {
        this.id = id;
        this.name = name;
        this.status = status == null ? "" : status;
        this.avatarBase64 = avatarBase64;
        this.online = online;
    }

    static ChatUser create(String name, String avatarBase64) {
        return new ChatUser(UUID.randomUUID().toString(), name, "", avatarBase64, false);
    }

    JSONObject toJson() {
        JSONObject user = new JSONObject();
        put(user, "id", id);
        put(user, "name", name);
        put(user, "status", status);
        put(user, "avatarBase64", avatarBase64 == null ? JSONObject.NULL : avatarBase64);
        put(user, "online", online);
        return user;
    }

    private static void put(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (org.json.JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    static ChatUser fromJson(JSONObject user) {
        return new ChatUser(user.optString("id"), user.optString("name"),
                user.optString("status", ""), user.optString("avatarBase64", null),
                user.optBoolean("online"));
    }
}
