package com.linachat.service;

import java.io.*; import java.nio.file.*; import java.util.Properties;

public final class AppConfig {
    // charge d'abord server.properties, puis surcharge les valeurs avec le fichier
    // personnel de l'utilisateur situe dans son dossier home.
    private final Path file; private final Properties values = new Properties();
    public AppConfig() throws IOException {
        file=Paths.get(System.getProperty("user.home"),".linachat","config.properties");
        try (InputStream in = AppConfig.class.getResourceAsStream("/com/linachat/server.properties")) {
            if (in != null) values.load(in);
        }
        if(Files.exists(file))try(InputStream in=Files.newInputStream(file)){values.load(in);}
    }
    public String get(String key, String fallback) { return values.getProperty(key, fallback); }
    public void set(String key, String value) { values.setProperty(key,value); }
    public void save() throws IOException { Files.createDirectories(file.getParent()); try(OutputStream out=Files.newOutputStream(file)){values.store(out,"LinaChat configuration");} }
}
