# Zodi AI Android

Application Android native de **Zodi AI**, connectée au moteur conversationnel hébergé sur Vercel.

## Fonctionnalités

- Application Android légère qui ouvre Zodi AI dans un onglet Chrome personnalisé.
- Connexion Google réalisée dans le navigateur système, compatible avec les restrictions OAuth de Google.
- Même interface responsive, mêmes conversations et même mémoire cloud que le site web.
- Aucun secret API ni jeton de session stocké dans l'APK.
- Bouton de nouvelle conversation.

## Construire l'APK

Le workflow GitHub Actions **Build Zodi AI APK** compile automatiquement l'application à chaque modification Android sur `main`, ou manuellement depuis l'onglet Actions.

1. Ouvrir l'onglet **Actions** du dépôt.
2. Sélectionner **Build Zodi AI APK**.
3. Lancer **Run workflow** si nécessaire.
4. Télécharger l'artefact **zodi-ai-debug-apk** à la fin du build.
5. Extraire `app-debug.apk), puis l'ouvrir sur Android pour l'installer.

## Compilation locale

Prérequis : JDK 17, Android SDK (API 35) et Gradle 8.9.

```bash
gradle --no-daemon :app:assembleDebug
```

APK générée : `app/build/outputs/apk/debug/app-debug.apk`.

## Architecture et sécurité

- L'APK est un lanceur sécurisé vers l'application web partagée, pas un second moteur de chat.
- Le Custom Tab utilise le navigateur système afin que Google OAuth ne soit pas bloqué comme dans un WebView intégré.
- Les conversations et la mémoire sont enregistrées côté serveur et liées au compte Google.
- Les échanges utilisent HTTPS ; aucune clé API ni aucun secret OAuth n'est intégré à l'APK.
- Cette première version est une APK de test/debug. Une version de publication signée devra être créée séparément avec une clé privée conservée hors du dépôt.
