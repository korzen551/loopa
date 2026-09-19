# Loopa

Apka na Androida do zapętlania klipów z YouTube i TikToka — z własnym punktem startu,
playlistami i dźwiękiem, który leci dalej po zgaszeniu ekranu.

Cel: klip ma 18 sekund, ale interesuje Cię tylko od 9. sekundy. Ustawiasz to raz,
a potem leci w kółko od 9, a nie od zera.

## Co potrafi

- **Udostępnianie prosto do apki** — w TikToku/YouTubie dajesz „Udostępnij → Loopa”.
  Wyskakuje półarkusz, w którym zaznaczasz playlisty. Bez wychodzenia z tamtej apki.
- **Playlisty** — dowolnie dużo, film może być w kilku naraz.
- **Własne punkty startu i końca** — trzy niezależne znaczniki:
  - *start pierwszego odtworzenia*,
  - *start każdego powtórzenia* (to jest właśnie ta 9. sekunda),
  - *koniec segmentu* (0:00 = do końca filmu).
- **Trzy tryby powtarzania** — raz / w kółko / dokładnie N razy.
- **Ustawienia per playlista** — ten sam klip może mieć inną pętlę w dwóch playlistach.
  Domyślne ustawienia trzyma film, a wpis w playliście może je nadpisać.
- **Granie w tle i na ekranie blokady** — zgaszenie ekranu nie przerywa dźwięku.
  Sterowanie pojawia się w powiadomieniach i na ekranie blokady, tak jak w odtwarzaczu
  muzyki. Wracasz, odblokowujesz i decydujesz, czy lecimy dalej.
- **Zero plików na telefonie** — nic się nie pobiera. W bazie siedzą wyłącznie metadane
  (tytuł, miniatura, punkty pętli). Sam obraz i dźwięk idą strumieniem z sieci przy
  każdym odtworzeniu.

## Jak zbudować APK

### Wariant A — Android Studio (lokalnie)

1. Zainstaluj [Android Studio](https://developer.android.com/studio).
2. `File → Open` i wskaż ten katalog.
3. Poczekaj, aż Gradle pobierze zależności. Przy pierwszym otwarciu Android Studio
   samo dogra wrapper (`gradlew`) — w repo go nie ma, bo `gradle-wrapper.jar` to
   plik binarny.
4. Podłącz telefon kablem (z włączonym debugowaniem USB) i kliknij ▶ *Run*.

Albo z terminala, jeśli masz już SDK i Gradle 8.9+:

```bash
gradle assembleRelease
```

APK ląduje w `app/build/outputs/apk/release/`.

### Wariant B — GitHub Actions (bez instalowania niczego)

1. Wrzuć ten katalog do repozytorium na GitHubie.
2. Zakładka **Actions** → *Build APK* → **Run workflow**.
3. Po kilku minutach pobierz artefakt `loopa-apk` i przerzuć plik na telefon.

## Wydania i aktualizacja z poziomu apki

`.github/workflows/release.yml` buduje APK i publikuje wydanie po wypchnięciu taga:

```bash
git tag v1.3 && git push origin v1.3
```

Do wydania trafiają dwa pliki: `Loopa-<wersja>.apk` oraz `latest.json` z numerem
wersji i adresem pliku. W apce, w **Ustawienia → Adres aktualizacji**, wklejasz raz:

```
https://api.github.com/repos/<użytkownik>/<repo>/releases/latest
```

i nigdy więcej tego nie zmieniasz — GitHub sam wskazuje najnowsze wydanie, a apka
najpierw porównuje numer wersji i pobiera 16 MB tylko wtedy, gdy faktycznie jest co
pobierać.

### Jeden warunek: ten sam klucz podpisu

Android instaluje „po wierzchu" wyłącznie pakiety podpisane tym samym kluczem.
Inny klucz = trzeba odinstalować starą wersję = **playlisty przepadają**. Dlatego
przepływ odtwarza keystore z sekretu repozytorium `DEBUG_KEYSTORE_B64`
(zawartość `~/.android/debug.keystore` zakodowana base64). Bez tego sekretu build
celowo przerywa się z błędem, zamiast po cichu wyprodukować plik, którego nie da
się zainstalować.

Repozytorium musi być **publiczne**, żeby przycisk aktualizacji działał bez
logowania — pliki wydań w repozytorium prywatnym wymagają tokenu.

W obu wariantach APK jest podpisany kluczem debugowym, więc instaluje się bez
dodatkowych kroków. Przy pierwszej instalacji Android zapyta o zgodę na
„instalowanie nieznanych aplikacji” — to normalne przy apce spoza sklepu.

## Jak to działa w środku

```
udostępnienie linku
   └─ LinkParser          rozpoznaje YouTube/TikTok, wyłuskuje id i ?t=
       └─ ResolverRegistry pobiera metadane, cache'uje adresy strumieni (TTL)
           ├─ YouTubeResolver  NewPipeExtractor
           └─ TikTokResolver   oEmbed + JSON ze strony filmu

odtwarzanie
   PlayerConnection (UI)  ──MediaController──▶  PlaybackService
                                                   ├─ ExoPlayer
                                                   ├─ LoopaMediaSourceFactory
                                                   │    loopa://play?… → prawdziwy adres
                                                   └─ LoopController
                                                        pilnuje punktów startu/końca
```

Dwie rzeczy warte wyjaśnienia:

**Adresy strumieni nigdy nie trafiają do bazy.** Wygasają (YouTube kilka godzin,
TikTok krócej), więc rozwiązywane są leniwie, przy każdym otwarciu źródła —
`LoopaMediaSourceFactory` podmienia `loopa://play?…` na prawdziwy adres w momencie,
gdy ExoPlayer faktycznie sięga po dane. Gdy adres wygaśnie w trakcie grania, przy
kolejnym otwarciu po prostu przychodzi świeży.

**Pętla ma dwa zabezpieczenia.** Odpytywanie pozycji łapie własny koniec segmentu,
a `REPEAT_MODE_ONE` plus nasłuch nieciągłości łapie naturalny koniec filmu, zanim
ExoPlayer zdąży przeskoczyć do następnej pozycji w kolejce. Bez tego drugiego
przy dłuższej playliście co jakiś czas uciekałoby jedno powtórzenie.

## Czego ta apka nie zrobi

To jest apka do sideloadu na własny telefon, nie do Google Play:

- **Regulaminy.** Odtwarzanie YouTube'a poza ich własnym odtwarzaczem (a zwłaszcza
  w tle) jest niezgodne z warunkami korzystania z YouTube'a. Google odrzuciłby to
  w sklepie. Na własnym telefonie to Twoja decyzja — ale wiedz, na czym stoisz.
- **Kruchość.** `NewPipeExtractor` przestaje działać, gdy YouTube coś zmieni —
  zwykle kilka razy w roku. Wtedy trzeba podbić wersję w
  `gradle/libs.versions.toml` (`newpipe = "vX.Y.Z"`) i zbudować od nowa.
  Wyciąganie adresu z TikToka jest jeszcze bardziej podatne na zmiany strony.
- **Jakość obrazu z YouTube'a.** Bierzemy strumień z obrazem i dźwiękiem w jednym
  pliku — zwykle 360p. Wyższe rozdzielczości YouTube serwuje jako osobne ścieżki
  wideo i audio, co wymagałoby sklejania dwóch źródeł i rozwiązywania adresów
  z góry dla całej playlisty. Przy klipach na telefonie to świadomy kompromis;
  gdybyś chciał wyżej, punkt zaczepienia jest w `LoopaMediaSourceFactory`.
- **Awaryjny odtwarzacz.** Gdy ekstrakcja padnie, klip i tak da się obejrzeć —
  apka przełącza się na oficjalny embed w WebView. Wtedy jednak nie ma grania
  w tle ani własnych punktów pętli, bo steruje nim cudzy odtwarzacz.
- **Treści prywatne / wiekowe / regionalne.** Filmy wymagające logowania się nie
  odtworzą.

## Struktura

| Ścieżka | Co tam jest |
| --- | --- |
| `data/` | Room: filmy, playlisty, wpisy, ustawienia pętli |
| `resolve/` | rozpoznawanie linków i wyciąganie adresów strumieni |
| `media/` | serwis odtwarzania, ExoPlayer, kontroler pętli |
| `ui/library/` | biblioteka: playlisty + filmy luzem |
| `ui/playlist/` | zawartość playlisty, kolejność, edycja pętli |
| `ui/player/` | ekran odtwarzania, mini-odtwarzacz, edytor pętli |
| `ui/share/` | półarkusz „Udostępnij → zapisz gdzie” |

Minimalny Android: 8.0 (API 26), `targetSdk` 35.

**Uwaga:** ten kod nie był uruchomiony ani na emulatorze, ani na fizycznym telefonie —
powstał w całości statycznie. Pierwsze `assembleRelease` może wymagać drobnych
poprawek (najczęściej: wersja `NewPipeExtractor`, która żyje własnym życiem).
