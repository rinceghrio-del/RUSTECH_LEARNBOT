# RUSTECH Learn (learning version ng RUSTECH_APP_ESP32)

Hiwalay na app ito sa lumang FaceRobot/RUSTECH app mo:

| | Lumang app | Learning app |
|---|---|---|
| applicationId | `com.example.facerobot` | `com.rustech.learnbot` |
| Pangalan sa launcher | FaceRobot | RUSTECH Matuto |
| Kulay ng icon | cyan | purple (pati ang backdrop ng robot) |

Dahil magkaiba ang applicationId, **sabay silang pwedeng naka-install** at hindi nagbabahagi ng data.

## Bago dito

### 1. Teach Mode (turuan ang robot gamit ang boses)
- Sabihin: **"matuto ka"** (o "turuan kita") - o pindutin ang menu card na **Teach Mode**.
- Robot: "Ano ang sasabihin mo sa akin?" -> sabihin ang trigger
- Robot: "Ano naman ang isasagot ko?" -> sabihin ang sagot
- Robot uulitin ang narinig at magtatanong "Tama ba?" -> **oo** = save, **hindi** = huwag, **ulitin** = ulit sa umpisa
- Kahit kailan: **"kanselahin"** para huminto. May 25 segundong timeout bawat hakbang.
- Kapag may kaparehong trigger na, **idadagdag lang** ang bagong sagot bilang variation (`||`), hindi buburahin ang luma.
- Ang itinuro ay may "owner" (ang taong nakilala sa mukha). Makikita ito sa **Mga Utos** na may 🎓.
- **"ano ang tinuro ko"** / **"ano ang natutunan mo"** - ililista ng robot ang mga itinuro.
- Hindi tinatanggap na trigger: mga salitang pang-hinto (hinto/stop/tigil), masyadong maikli (< 4 letra), at ang mismong pang-turo na parirala.
- Ang **STOP** ay laging gumagana, kahit habang nagtuturo (kinakansela ang pagtuturo at tumitigil ang robot).

### 2. Memorya ng bawat tao
Naka-key sa pangalan ng naka-enroll na mukha. Lokal lang sa phone.
- Ilang beses nang dumalaw (bagong pagdalaw = 10 minutong hindi nakita), huling dalaw, madalas na command, madalas na DFPlayer track.
- **Personalized na bati**: matagal nang hindi nakita (3 araw o higit) -> "Ngayon lang ulit kita nakita!"; bumalik sa parehong araw -> "Balik ka na pala!". Hindi na uulit-ulitin ang bati habang nasa harap lang siya sa parehong pagdalaw. (Pwedeng i-OFF sa Menu > Memorya.)
- **"kailan mo ako huling nakita"**, **"ilang beses mo na ako nakita"**, **"patugtog ang paborito ko"**.
- Ipinapasa rin kay Gemini ang maikling tala tungkol sa kausap, at ang mga itinuro ay nauuna sa listahan ng alam niya.
- Menu > **Memorya**: listahan ng mga tao, may **Kalimutan** button.

## Paalala sa bagong install
Hiwalay ang data ng app na ito, kaya kailangan sa unang bukas:
- i-enroll ulit ang mga mukha (rusty, ghrio, sauty, mama)
- ilagay ulit ang ESP32 IP, Gemini API key, ElevenLabs key
- ma-download ulit ang Vosk Filipino model (~320 MB)

## Bagong GitHub repo
1. Gumawa ng bagong repo, i-upload lahat ng laman ng folder na ito (kasama ang `.github` at ang dalawang `.tflite` sa `app/src/main/assets/`).
2. Wala kang kailangang i-set na secrets: kung walang `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD`, kusa itong gagawa ng bagong debug keystore sa workflow. Kung gusto mong gamitin ang parehong keystore, i-set lang ang dalawang secrets.
3. Actions > Build APK > i-download ang `RustechLearn-debug-apk`.

## Gusto mong palitan ang pangalan/ID?
- `app/build.gradle.kts` -> `applicationId`
- `app/src/main/AndroidManifest.xml` -> `android:label`
- `app/src/main/res/values/ic_launcher_background_colors.xml` -> kulay ng icon
(Huwag galawin ang `namespace` at ang package ng mga `.kt` file.)
