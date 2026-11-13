# Live AI Dost AutoReply (WhatsApp & WhatsApp Business)

A real-time conversational AI Android application written in **Kotlin** (minSdk 26, targetSdk 34) that chats continuously and naturally in **Pakistani / Desi Roman Urdu** just like a genuine friend on WhatsApp and WhatsApp Business.

---

## ✨ Key Features

1. **Continuous Real-Time Chat (Zero Canned "I am busy" Replies)**
   - Listens to incoming WhatsApp (`com.whatsapp`) and WhatsApp Business (`com.whatsapp.w4b`) notifications using Android's `NotificationListenerService`.
   - Replies directly in the notification drawer using `RemoteInput` without opening the app or unlocking the screen.

2. **Authentic Pakistani Roman Urdu Persona**
   - Pre-loaded with a human friend persona: speaks naturally in Pakistani Roman Urdu (e.g. *"haan yaar"*, *"bhai"*, *"sahi hai"*, *"kya scene hai"*, *"Alhamdulillah"*).
   - Converses on any topic (cricket, WWE, studies, casual banter, plans) in short 1-to-2 line natural messages.
   - Persona instructions are fully editable and customizable in the app dashboard.

3. **Smart Deduplication & Rapid Back-and-Forth Chat**
   - Intelligently prevents duplicate replies to re-issued or updated notification bundles.
   - Immediately replies when the friend sends a new message, allowing natural continuous banter.

4. **Realistic Human Typing Delay**
   - Configurable delay (default 3 seconds) using Kotlin Coroutines (`Dispatchers.IO`) before triggering `RemoteInput` `PendingIntent` so replies appear naturally paced.

5. **Rolling Multi-Turn Context Memory (`ConversationMemoryManager`)**
   - Keeps a rolling memory of the last 12 turns (user & assistant) per sender in private `SharedPreferences`.
   - The AI remembers past questions, topics, and references throughout continuous conversations.
   - Includes a one-tap "Clear Conversation History" option.

6. **Dual 100% Free AI Engine Backend (`AiChatEngine`)**
   - **Mode A (Default - Zero Key Required)**: Uses Pollinations free OpenAI-compatible text endpoint (`POST https://text.pollinations.ai/openai`) with fallback to URL-based GET endpoint. Zero sign-up, zero API key needed.
   - **Mode B (Optional Free Gemini API Key)**: Connect your Google Gemini API key to leverage Google's `gemini-2.5-flash` model.

7. **Safety Filters & Smart Dashboard**
   - **Notification Permission Banner**: Shows real-time status with a 1-tap button to open Notification Access settings.
   - **Master Switch**: Pause or resume the auto-reply engine anytime.
   - **Ignore Group Chats**: ON by default to keep replies private.
   - **Do-Not-Reply Blacklist**: Comma-separated list (e.g. `Ammi, Abba, Sir, Boss`) ensuring family and elders never receive automated replies.
   - **Live Activity Log**: Displays the last 15 incoming messages and outgoing Roman Urdu replies in real time.

8. **Cloud APK Builder (GitHub Actions)**
   - Pre-configured `.github/workflows/build-apk.yml` with JDK 17, Android SDK setup, `./gradlew assembleDebug`, and automated APK artifact upload.

---

## 📁 Project Architecture

```
live-ai-dost-autoreply/
├── .github/
│   └── workflows/
│       └── build-apk.yml           # Automated GitHub Actions APK build workflow
├── app/
│   ├── build.gradle.kts            # App module build script (minSdk 26, targetSdk 34)
│   ├── proguard-rules.pro          # Proguard rules for release optimization
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml # Permissions, NotificationListenerService, MainActivity
│           ├── java/com/livedost/autoreply/
│           │   ├── MainActivity.kt               # Main dashboard UI & settings controller
│           │   ├── WhatsappAiService.kt          # NotificationListenerService & RemoteInput engine
│           │   ├── AiChatEngine.kt               # Dual AI backend (Pollinations & Gemini)
│           │   ├── ConversationMemoryManager.kt  # Rolling 12-turn conversation memory
│           │   ├── AppSettings.kt                # Preferences, safety filters, live logs
│           │   ├── ReplyLogItem.kt               # Log data model
│           │   └── ReplyLogAdapter.kt            # Recycler view adapter for live activity
│           └── res/
│               ├── layout/
│               │   ├── activity_main.xml         # Modern Material 3 dashboard layout
│               │   └── item_reply_log.xml        # Card layout for incoming & outgoing replies
│               ├── values/
│               │   ├── colors.xml                # WhatsApp & Material palette
│               │   ├── strings.xml               # UI labels & default Roman Urdu prompt
│               │   └── themes.xml                # Material 3 Day/Night theme
│               └── drawable/ & mipmap-anydpi-v26/
├── gradle/
│   └── wrapper/
│       └── gradle-wrapper.properties
├── build.gradle.kts                # Root project build script
├── settings.gradle.kts             # Settings script
├── gradle.properties
├── gradlew                         # Executable Gradle Wrapper script
└── README.md
```

---

## 🚀 How to Build

### Option 1: Cloud APK Build with GitHub Actions (No Local Setup Required)
1. Push this project to your GitHub repository:
   ```bash
   cd /data/user/0/com.termux/files/home/projects/live-ai-dost-autoreply
   git init
   git add .
   git commit -m "Initial commit: Live AI Dost AutoReply"
   git branch -M main
   git remote add origin https://github.com/<your-username>/live-ai-dost-autoreply.git
   git push -u origin main
   ```
2. Navigate to your repository on GitHub and open the **Actions** tab.
3. The **Build Android Debug APK** workflow will run automatically.
4. Once completed (approx. 2-3 minutes), download the ready-to-install `LiveAiDostAutoReply-debug-apk` from the **Artifacts** section!

### Option 2: Local Build with Android Studio or Command Line
If you have Java 17 and the Android SDK installed:
```bash
./gradlew assembleDebug
```
The compiled APK will be generated at:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## 📱 How to Use on Your Phone

1. Install the APK on your Android device (Android 8.0 / API 26 or higher).
2. Open **Live AI Dost AutoReply**.
3. Tap the **Grant Notification Access** button on the top banner and toggle **Live AI Dost AutoReply** to **Allowed**.
4. The banner will turn green: *"Notification Access is Active"*.
5. (Optional) Customize your Roman Urdu friend prompt, adjust typing delay (default 3s), or add contacts to the Blacklist (e.g. `Ammi, Abba, Boss`).
6. Select your preferred AI engine:
   - **Free No-Key AI (Pollinations)**: Works out-of-the-box with zero setup.
   - **Google Gemini**: Paste your free Gemini API key from Google AI Studio.
7. Have a friend send you a message on WhatsApp or WhatsApp Business—watch the app automatically reply like a real friend in Roman Urdu!
