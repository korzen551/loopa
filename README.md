# Loopa

Apka na Androida do zapętlania klipów z YouTube i TikToka — z własnym punktem startu,
playlistami i dźwiękiem, który leci dalej po zgaszeniu ekranu.

Cel: klip ma 18 sekund, ale interesuje Cię tylko od 9. sekundy. Ustawiasz to raz,
a potem leci w kółko od 9, a nie od zera.

## Co potrafi

- **Udostępnianie prosto do apki** — w TikToku/YouTubie dajesz „Udostępnij → Loopa”.
  Wyskakuje półarkusz, w którym zaznaczasz playlisty. Bez wychodzenia z tamtej apki.
- **Muzyka ze Spotify** — tak samo udostępniasz utwór, album albo całą playlistę.
  Spotify nie wypuszcza dźwięku poza swoją apkę, więc każdy utwór jest wyszukiwany
  na YouTube (tytuł, wykonawca, a przede wszystkim długość nagrania co do sekundy)
  i zapisywany jako zwykły film. Playlista trafia do nowej playlisty w Loopa,
  w tej samej kolejności. Publiczny podgląd Spotify pokazuje do 100 utworów playlisty.
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
- **Pętla bez przerwy** — powtórzenie nie przewija filmu, tylko przechodzi do
  wcześniej załadowanego kolejnego przejścia, więc nie ma ułamka sekundy ciszy.
- **Strumień zamiast plików** — w bazie siedzą wyłącznie metadane (tytuł, miniatura,
  punkty pętli). Obraz i dźwięk idą z sieci; ostatnio grane klipy leżą w cache
  (do 300 MB, system czyści go sam), żeby pętla i powrót do filmu nie ściągały go
  drugi raz.

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
                                                   │    + cache na dysku
                                                   │    └─ LoopSegmentMediaSource
                                                   │         punkty pętli w osi czasu
                                                   └─ LoopController
                                                        liczy powtórzenia, start 1. razu

udostępnienie ze Spotify
   └─ SpotifyClient     publiczny podgląd open.spotify.com/embed → tytuły i długości
       └─ YouTubeMatcher wyszukiwanie NewPipe, ocena po długości/tytule/wykonawcy
```

Trzy rzeczy warte wyjaśnienia:

**Adresy strumieni nigdy nie trafiają do bazy.** Wygasają (YouTube kilka godzin,
TikTok krócej), więc rozwiązywane są leniwie, przy każdym otwarciu źródła —
`LoopaMediaSourceFactory` podmienia `loopa://play?…` na prawdziwy adres w momencie,
gdy ExoPlayer faktycznie sięga po dane. Gdy adres wygaśnie w trakcie grania, przy
kolejnym otwarciu po prostu przychodzi świeży.

**Pętla nie przewija.** Każde przewinięcie (`seekTo`) czyści dekodery, a przy
strumieniu czeka jeszcze na bufor — stąd był ułamek sekundy ciszy przy każdym
powtórzeniu. Teraz koniec segmentu jest końcem okresu w osi czasu
(`ClippingMediaSource`), a punkt powrotu — domyślną pozycją okna.
W `REPEAT_MODE_ONE` ExoPlayer ładuje kolejne przejście z wyprzedzeniem i wchodzi
w nie bez zatrzymywania dekoderów, dokładnie jak między utworami na płycie bez
przerw. Jedyne, czego to nie ukryje: gdy punkt powrotu nie wypada na klatce
kluczowej, dekoder musi dojść do niego od najbliższej takiej klatki — dźwięk leci
wtedy bez przerwy, a obraz może na moment przytrzymać klatkę.

**Pełna długość filmu jedzie osobno.** Z własnym końcem segmentu oś czasu
odtwarzacza kończy się na tym końcu, więc paski przewijania i edytor pętli biorą
długość całego filmu z metadanych pozycji (`LoopKeys.FULL_DURATION`), a serwis
zapisuje ją do bazy, gdy tylko źródło ją pozna.

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
| `resolve/` | rozpoznawanie linków, adresy strumieni, Spotify → YouTube |
| `media/` | serwis odtwarzania, ExoPlayer, pętla w osi czasu, cache |
| `ui/library/` | biblioteka: playlisty + filmy luzem |
| `ui/playlist/` | zawartość playlisty, kolejność, edycja pętli |
| `ui/player/` | ekran odtwarzania, mini-odtwarzacz, edytor pętli |
| `ui/share/` | półarkusz „Udostępnij → zapisz gdzie” |

Minimalny Android: 8.0 (API 26), `targetSdk` 35.

**Uwaga:** ten kod nie był uruchomiony ani na emulatorze, ani na fizycznym telefonie —
powstał w całości statycznie. Pierwsze `assembleRelease` może wymagać drobnych
poprawek (najczęściej: wersja `NewPipeExtractor`, która żyje własnym życiem).
