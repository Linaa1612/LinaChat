# LinaChat Android

Cette application Android native (Java 17 pour le code compilé) est un client supplémentaire du serveur LinaChat existant. Elle n'utilise pas JavaFX et ne démarre pas son propre serveur. Le protocole est identique au client Desktop : longueur de trame sur 4 octets en ordre réseau, suivie du JSON UTF-8. Les messages, images, fichiers, WAV et requêtes IA utilisent les types de trame déjà gérés par `ChatServer`.

Le serveur Desktop reste la source commune des profils connectés, des historiques en mémoire et du routage. Le port par défaut est `5050`. L'Assistant IA s'exécute côté serveur avec sa configuration `GEMINI_API_KEY`; aucune clé n'est stockée ou transmise par l'application Android. Les fichiers sont limités à 15 Mo comme dans le client Desktop.

## Préparer Windows 10

1. **Ne pas installer, désinstaller ou remplacer le JDK actuel.** Ce poste utilise déjà Java `25.0.2`. Vérifier le JDK actif dans PowerShell sans modifier les variables d'environnement :

   ```powershell
   where.exe java
   java -version
   ```

   Le résultat attendu est Java 25.0.2. Laisser `JAVA_HOME` et `PATH` tels quels.

2. Installer Android Studio depuis le site officiel Android Developers. Dans l'assistant d'installation, sélectionner **Android SDK**, **Android SDK Platform-Tools** et **Android Virtual Device**. L'installation d'Android Studio ne nécessite pas de changer le JDK système.

3. Ouvrir le dossier `LinaChat\android` dans une version récente d'Android Studio (et non le dossier racine Maven). Dans **File > Settings > Build, Execution, Deployment > Build Tools > Gradle**, sélectionner **GRADLE JDK > Add JDK** et choisir le JDK déjà utilisé par Windows (`C:\Program Files\Java\jdk-25.0.2` sur ce poste). Ne pas choisir ou installer un autre JDK et ne pas modifier `JAVA_HOME` ou `PATH`. Gradle 9.1 et AGP 9.0.1 sont configurés pour Java 25. Le build Desktop reste configuré dans son `pom.xml` avec `maven.compiler.release=21`; ne modifiez pas Maven ou le JDK système.

4. Ouvrir **Tools > SDK Manager**. Dans **SDK Platforms**, cocher **Android 15.0 ("Vanilla Ice Cream") / API Level 35**, puis **Apply**. Dans **SDK Tools**, cocher **Android SDK Build-Tools 36.0.0** (version minimale requise par AGP 9.0) et **Android SDK Platform-Tools**, puis **Apply > OK** et accepter les licences. Vérifier dans **SDK Location** le chemin affiché; Android Studio générera le fichier local `android\local.properties`. Ne pas ajouter ce fichier au dépôt. L'application conserve `compileSdk 35` et `targetSdk 35`; les Build-Tools 36 ne changent pas le niveau Android ciblé.

5. Cliquer **File > Sync Project with Gradle Files**. Attendre **BUILD SUCCESSFUL** dans la fenêtre Build. Le wrapper télécharge Gradle 9.1.0; Gradle télécharge AGP 9.0.1 et les dépendances. Si Android Studio demande l'actualisation du format Gradle ou une mise à niveau supplémentaire, ne pas lancer d'Upgrade Assistant : ces versions sont déjà déclarées dans `android\build.gradle` et `android\gradle\wrapper\gradle-wrapper.properties`.

6. Pour générer l'APK depuis Android Studio, choisir **Build > Build Bundle(s) / APK(s) > Build APK(s)**, puis cliquer le lien **locate** lorsque la compilation réussit. Depuis PowerShell, dans le dossier `LinaChat\android`, exécuter :

   ```powershell
   .\gradlew.bat :app:assembleDebug
   ```

   Le message attendu est `BUILD SUCCESSFUL`; le fichier généré est `android\app\build\outputs\apk\debug\app-debug.apk`. Si `SDK location not found`, terminer l'installation du SDK et relancer **Sync Project**. Si `Unsupported class file major version 69`, vérifier que **Gradle JDK** pointe vers Java 25 et que le wrapper utilise Gradle 9.1.0, sans toucher à `JAVA_HOME` ou `PATH`.

## Installer et lancer sur un téléphone USB

7. Sur le téléphone, ouvrir **Paramètres > À propos du téléphone** (parfois **Informations logicielles**) et toucher **Numéro de build** sept fois. Saisir le code de déverrouillage demandé; Android affiche la confirmation du mode développeur.

8. Revenir aux **Paramètres > Système > Options pour les développeurs** (l'emplacement varie selon la marque) et activer **Débogage USB**. Confirmer l'avertissement.

9. Connecter le téléphone au PC avec un câble USB qui transporte les données, déverrouiller l'écran et choisir **Transfert de fichiers** si le téléphone le demande. Accepter sur le téléphone la boîte de dialogue **Autoriser le débogage USB ?** et cocher **Toujours autoriser depuis cet ordinateur** uniquement si cet ordinateur est de confiance.

10. Dans Android Studio, regarder le sélecteur d'appareil à côté de **Run** et sélectionner le téléphone (son modèle ou numéro de série). Dans un terminal PowerShell, vérifier ADB en appelant directement Platform-Tools, sans modifier PATH :

   ```powershell
   $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
   & $adb devices -l
   ```

   Le résultat doit contenir le numéro de série du téléphone et l'état `device`. `unauthorized` : déverrouiller le téléphone et accepter la demande RSA, puis relancer la commande. Liste vide : essayer un câble de données et un autre port USB, installer le pilote USB OEM du fabricant sous Windows si nécessaire, et vérifier **Débogage USB**. `offline` : débrancher/rebrancher et redémarrer ADB avec `& $adb kill-server; & $adb start-server`.

11. Dans Android Studio, cliquer sur **Run** (flèche verte) pour installer et démarrer LinaChat sur l'appareil sélectionné. Sinon, installer l'APK Debug avec :

   ```powershell
   $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
   & $adb install -r .\app\build\outputs\apk\debug\app-debug.apk
   ```

   Le résultat attendu est `Success`. Pour la lancer directement par ADB :

   ```powershell
   & $adb shell monkey -p com.linachat.android 1
   ```

   Si Windows ne trouve pas `adb.exe`, vérifier le chemin **SDK Location > Android SDK Location** dans Android Studio, puis remplacer le chemin PowerShell ci-dessus par `<ce-chemin>\platform-tools\adb.exe`. Si l'installation échoue, vérifier que le fichier APK existe et que le téléphone autorise les installations via USB; `-r` met à jour une installation existante sans effacer ses données.

## Démarrer le serveur et se connecter en Wi-Fi

12. Sur le PC qui héberge LinaChat, ouvrir PowerShell à la racine du dépôt (`LinaChat`, le dossier qui contient `mvnw.cmd`) et lancer le serveur existant :

   ```powershell
   java "-Dmaven.multiModuleProjectDirectory=$PWD" -cp .mvn\wrapper\maven-wrapper.jar org.apache.maven.wrapper.MavenWrapperMain exec:java "-Dexec.mainClass=com.linachat.ServerMain" "-Dexec.args=5050"
   ```

   Garder cette fenêtre ouverte; l'affichage attendu est `LinaChat Server écoute sur le port 5050`. Cette commande ne démarre pas un second serveur mobile. Le client Desktop peut se connecter simultanément au même serveur.

13. Dans PowerShell, afficher l'adresse IPv4 du PC :

   ```powershell
   ipconfig
   ```

   Dans la sortie `ipconfig`, relever **Adresse IPv4** de la carte **Wi-Fi** connectée au même réseau que le téléphone (ex. `192.168.1.10`), pas la passerelle, une adresse Bluetooth ou une carte virtuelle VPN. Le réseau Wi-Fi doit autoriser les appareils à communiquer entre eux.

14. Dans LinaChat sur le téléphone, saisir le nom, l'adresse IPv4 du PC dans **Adresse IP du serveur**, et `5050` dans **Port**, puis appuyer sur **Se connecter**. Ne pas saisir `127.0.0.1` : sur le téléphone, cette adresse désigne le téléphone. L'application mémorise seulement la dernière IP et le dernier port.

15. Si Windows affiche une demande de pare-feu, autoriser Java sur le profil **Privé** uniquement. Sinon ouvrir **Sécurité Windows > Pare-feu et protection réseau > Paramètres avancés > Règles de trafic entrant > Nouvelle règle**; sélectionner **Port > TCP > Ports locaux spécifiques : 5050 > Autoriser la connexion > Privé**, puis nommer la règle `LinaChat TCP 5050`. Ne pas créer de règle UDP ni désactiver le pare-feu.

16. Vérifier depuis PowerShell sur le PC :

   ```powershell
   Test-NetConnection 192.168.1.10 -Port 5050
   ```

   Remplacer l'adresse par celle de `ipconfig`. Le résultat attendu est `TcpTestSucceeded : True`. Si le test échoue, vérifier que la fenêtre serveur écoute toujours sur le port 5050, que l'adresse IP est celle du PC, que les deux appareils partagent le même Wi-Fi et que la règle TCP du pare-feu est autorisée.

17. Une fois le téléphone connecté, démarrer également le client Desktop et vérifier que les deux profils apparaissent en ligne. Tester un message dans chaque sens, puis :

   - Un client Desktop et un client Android connectés en même temps, avec échange de messages dans les deux sens.
   - Deux téléphones Android (ou un téléphone et un émulateur) connectés en plus du Desktop; vérifier que les profils en ligne se mettent à jour.
   - Sélection de plusieurs contacts et consultation de l'historique conservé pendant l'exécution du serveur.
   - Envoi d'une image PNG/JPEG, d'un PDF et d'un autre fichier; vérifier l'aperçu, l'ouverture ou l'enregistrement sur Android.
   - Enregistrement d'un court message vocal Android, puis lecture côté Android et Desktop; tester aussi les emojis.
   - Assistant IA si `GEMINI_API_KEY` est configurée dans l'environnement du serveur; vérifier qu'une conversation privée ne reçoit pas les requêtes IA.
   - Déconnexion, reconnexion avec un nouveau profil, et mise à jour des listes d'utilisateurs.

   Les tests d'IA nécessitent la configuration Gemini du serveur et sa clé dans l'environnement du serveur, jamais dans l'application Android.

## Émulateur Android

18. (Facultatif) Dans **Tools > Device Manager** d'Android Studio, choisir **Create Virtual Device**, créer un téléphone et démarrer l'émulateur avec le bouton ▶. Si le serveur tourne sur ce même ordinateur, entrer `10.0.2.2` et `5050` dans Login : cette adresse spéciale de l'émulateur pointe vers Windows. Pour un serveur sur un autre PC, saisir son IPv4 LAN. L'émulateur ne correspond pas à `127.0.0.1` de Windows.

## Réseaux différents / Internet

19. Le protocole TCP actuel n'ajoute ni chiffrement TLS ni authentification de compte. **Ne redirigez pas directement le port 5050 vers Internet pour un usage réel.** Pour un test à distance, privilégier un VPN privé (par exemple Tailscale ou WireGuard) installé sur le PC serveur et le téléphone : démarrer le serveur sur le PC, puis saisir dans Login l'adresse VPN du PC et `5050`. Les deux appareils doivent être autorisés sur le même réseau VPN et le pare-feu Windows doit autoriser TCP 5050 sur le profil réseau correspondant.

   Une redirection de port sur le routeur et l'utilisation de l'IP publique peuvent fonctionner techniquement, mais exposent un protocole non chiffré sans authentification. Cela nécessite une règle TCP sur le routeur, une adresse publique accessible et une autorisation du pare-feu; le CGNAT de certains fournisseurs empêche la redirection. Ce mode n'est pas recommandé et n'est pas sécurisé. Une exposition Internet sûre demanderait une évolution serveur vers une authentification et un transport chiffré, ce qui dépasserait le client Android et le protocole actuel.

## Limites fonctionnelles du protocole existant

- Les profils, historiques et contextes IA sont en mémoire serveur; ils disparaissent à l'arrêt du serveur. L'historique est disponible pour les contacts dont les identifiants sont connus, mais le serveur n'offre pas de livraison hors ligne.
- La sélection de fichiers utilise le sélecteur Android. Les images JPEG, PNG et GIF sont envoyées comme images; les autres pièces jointes restent des fichiers génériques. Les fichiers reçus peuvent être ouverts via une application installée ou enregistrés dans l'emplacement choisi.
- Les messages vocaux sont enregistrés en PCM WAV par le microphone Android et envoyés avec le type `AUDIO`, comme les messages vocaux du Desktop.
- Le microphone nécessite l'autorisation Android `RECORD_AUDIO`. La permission Internet est déclarée dans le manifeste; aucun accès général au stockage n'est requis grâce aux sélecteurs de documents Android.
