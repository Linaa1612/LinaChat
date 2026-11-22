package com.linachat.controller;
public final class SettingsController {
    // Le port est valide comme un entier dans l'intervalle reseau autorise.
    public boolean validPort(String port) { try { int value = Integer.parseInt(port); return value > 0 && value <= 65535; } catch (NumberFormatException e) { return false; } }
}
