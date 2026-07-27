# Render APK deployment

This repository includes a multi-stage Docker build that:

1. compiles the native Android app with Android SDK 36 and Gradle 8.13;
2. copies the debug APK into a small Nginx runtime image;
3. serves a download page and the APK from the Render service URL.

## Deploy

1. In Render, select **New > Blueprint**.
2. Connect `Luyandosnt/Ankyra`.
3. Select the `agent/native-android-timer` branch when asked.
4. Render reads `render.yaml` and creates the free `ankyra-apk` web service.
5. Open the assigned `onrender.com` URL after the first deployment finishes.

The APK is available at `/Ankyra-debug.apk`. Each commit to the configured
branch triggers a fresh build and replaces the downloadable APK.

Free Render services can sleep after inactivity, so the first request after
sleeping can take longer to load.
