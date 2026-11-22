package com.linachat.controller;
public final class ProfileController {
    // Cette methode fournit une normalisation simple partageable par la vue profil.
    public String normalizeName(String name) { return name == null ? "" : name.trim(); }
}
