package com.linachat.model;

import java.util.UUID;

public record User(String id, String name, String status, String avatarBase64, boolean online) {
    // create() initialise un profil cote client avec un identifiant unique.
    public static User create(String name, String status, String avatarBase64) {
        return new User(UUID.randomUUID().toString(), name, status == null ? "" : status, avatarBase64, false);
    }
}
