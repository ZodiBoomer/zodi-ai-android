# Zodi AI Android

Application Android native de **Zodi AI**, connectée au moteur conversationnel hébergé sur Vercel.

## Fonctionnalités

- Interface de chat native Android en français.
- Connexion à l'API existante `https://zodi-ai-core.vercel.app`.
- Vérification de l'état du serveur et de la configuration IA.
- Historique local des conversations.
- Aucun secret API dans l'application : les clés restent côté serveur.
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

## Sécurité et limites

- L'application ne contient aucune clé OpenAI/OpenRouter.
- Les échanges utilisent HTTPS et l'API serveur existante.
- L'historique est stocké dans les préférences privées de l'application sur l'appareil.
- Cette première version est une APK de test/debug. Une version de publication signée devra être créée séparément avec une clé de signature privée conservée hors du dépôt.
