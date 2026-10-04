# Kompresor zdjęć

Aplikacja desktopowa w Java 11+ i Swing, bez wymaganych zewnętrznych bibliotek.

## Uruchomienie

Wymagany jest JDK 11 lub nowszy (z poleceniami `javac` i `java`). W katalogu projektu:

```sh
javac -encoding UTF-8 KompresorZdjec.java
java KompresorZdjec
```

## Funkcje

- Warianty 480p, 720p, 1080p i oryginalna rozdzielczość; bez powiększania zdjęć.
- Wybór krótszego lub dłuższego boku jako podstawy skalowania.
- Jakość JPG od 50 do 95% oraz rzeczywiste rozmiary wynikowych plików.
- JPG i PNG oraz opcjonalny WebP; porównanie rozmiarów formatów.
- Podgląd przed i po kompresji, z dekodowaniem wynikowego pliku.
- Przetwarzanie folderu i podsumowanie zmiany rozmiaru.
- Wykrywanie EXIF/GPS w JPEG i korekta orientacji EXIF.
- Zapis bez kopiowania metadanych źródłowego zdjęcia.
- Wczytywanie zdjęć przez przeciąganie pliku do okna.

PNG jest bezstratny; suwak jakości dla tego formatu jest nieaktywny.

## Opcjonalny WebP

Java nie obsługuje WebP domyślnie. Po dodaniu kompatybilnej wtyczki ImageIO z obsługą zapisu WebP, np. `webp-imageio` od Sejda, format pojawia się na liście.

Windows:

```powershell
java -cp ".;webp-imageio.jar" KompresorZdjec
```

Linux / macOS:

```sh
java -cp ".:webp-imageio.jar" KompresorZdjec
```

Wtyczka może wymagać dodatkowych zależności na classpath, zależnie od wybranej dystrybucji.
