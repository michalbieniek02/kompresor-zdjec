/*
 * Kompresor zdjęć – aplikacja desktopowa (Java 11+, Swing, bez zewnętrznych bibliotek)
 *
 * Kompilacja i uruchomienie:
 *     javac -encoding UTF-8 KompresorZdjec.java
 *     java KompresorZdjec
 *
 * Opcjonalnie WebP: Java nie obsługuje go domyślnie. Jeśli dodasz do classpath wtyczkę
 * ImageIO dla WebP (np. biblioteka "webp-imageio" od Sejda), pozycja "WebP" pojawi się
 * sama w liście formatów:
 *     java -cp ".:webp-imageio.jar" KompresorZdjec      (Windows: ".;webp-imageio.jar")
 *
 * Funkcje:
 *  - warianty 480p / 720p / 1080p / oryginał z prawdziwym rozmiarem pliku w podpisie
 *  - suwak jakości (50–95%) z przeliczaniem rozmiarów na żywo
 *  - wybór formatu JPG / PNG / (WebP) i porównanie rozmiarów formatów
 *  - podgląd przed/po (po = zdekodowany plik, więc widać artefakty kompresji)
 *  - przetwarzanie całego folderu z podsumowaniem zaoszczędzonego miejsca
 *  - wykrywanie EXIF/GPS w oryginale; zapisane pliki nie mają metadanych
 *  - poprawna orientacja zdjęć z telefonu (odczyt tagu EXIF Orientation)
 */

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class KompresorZdjec extends JFrame {

    // =====================================================================
    //  LOGIKA (bez GUI)
    // =====================================================================

    /** Wysokości wariantów; 0 = oryginalna rozdzielczość (tylko rekompresja). */
    static final int[] PRESETS = {480, 720, 1080, 0};
    static final Locale PL = Locale.forLanguageTag("pl-PL");
    static final List<String> FORMATS = availableFormats();

    static List<String> availableFormats() {
        List<String> f = new ArrayList<>(Arrays.asList("JPG", "PNG"));
        if (ImageIO.getImageWritersByFormatName("webp").hasNext()) f.add("WebP");
        return f;
    }

    static String presetName(int p) {
        return p == 0 ? "Oryginał" : p + "p";
    }

    static String ext(String fmt) {
        return fmt.equals("JPG") ? ".jpg" : fmt.equals("PNG") ? ".png" : ".webp";
    }

    static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(PL, "%.0f KB", b / 1024.0);
        return String.format(PL, "%.2f MB", b / 1048576.0);
    }

    static String delta(long now, long orig) {
        if (orig <= 0) return "";
        double pct = 100.0 * (now - orig) / orig;
        return (pct <= 0 ? "−" : "+") + String.format(PL, "%.0f%%", Math.abs(pct));
    }

    static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    /** Docelowy rozmiar: preset to długość krótszego (lub dłuższego) boku. Nie powiększamy. */
    static Dimension target(int w, int h, int preset, boolean byShort) {
        if (preset == 0) return new Dimension(w, h);
        int side = byShort ? Math.min(w, h) : Math.max(w, h);
        if (side <= preset) return new Dimension(w, h);
        double s = (double) preset / side;
        return new Dimension(Math.max(1, (int) Math.round(w * s)), Math.max(1, (int) Math.round(h * s)));
    }

    static BufferedImage scaleTo(BufferedImage img, int preset, boolean byShort) {
        Dimension d = target(img.getWidth(), img.getHeight(), preset, byShort);
        if (d.width == img.getWidth() && d.height == img.getHeight()) return img;
        return scale(img, d.width, d.height);
    }

    /** Skalowanie z jakością: kolejne połowienie + końcowy krok bicubic (bez "schodków"). */
    static BufferedImage scale(BufferedImage src, int w, int h) {
        int type = src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage cur = src;
        int cw = src.getWidth(), ch = src.getHeight();
        while (cw / 2 >= w && ch / 2 >= h) {
            cw /= 2;
            ch /= 2;
            cur = step(cur, cw, ch, type);
        }
        if (cw != w || ch != h) cur = step(cur, w, h, type);
        return cur;
    }

    private static BufferedImage step(BufferedImage s, int w, int h, int type) {
        BufferedImage d = new BufferedImage(w, h, type);
        Graphics2D g = d.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(s, 0, 0, w, h, null);
        g.dispose();
        return d;
    }

    static BufferedImage thumb(BufferedImage img, int max) {
        int m = Math.max(img.getWidth(), img.getHeight());
        if (m <= max) return img;
        double s = (double) max / m;
        return scale(img, Math.max(1, (int) Math.round(img.getWidth() * s)),
                Math.max(1, (int) Math.round(img.getHeight() * s)));
    }

    /** JPEG nie ma kanału alfa – spłaszczamy na białym tle. */
    static BufferedImage toRgb(BufferedImage img) {
        int t = img.getType();
        if (!img.getColorModel().hasAlpha()
                && (t == BufferedImage.TYPE_INT_RGB || t == BufferedImage.TYPE_3BYTE_BGR || t == BufferedImage.TYPE_BYTE_GRAY)) {
            return img;
        }
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }

    /** Kodowanie do bajtów w pamięci – dzięki temu rozmiary w podpisach są prawdziwe. */
    static byte[] encode(BufferedImage img, String fmt, int quality) throws IOException {
        String name = fmt.equalsIgnoreCase("JPG") ? "jpeg" : fmt.toLowerCase();
        BufferedImage src = fmt.equalsIgnoreCase("JPG") ? toRgb(img) : img;
        ImageWriter w = ImageIO.getImageWritersByFormatName(name).next();
        try {
            ImageWriteParam p = w.getDefaultWriteParam();
            if (!fmt.equalsIgnoreCase("PNG") && p.canWriteCompressed()) {
                p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                String[] types = p.getCompressionTypes();
                if (types != null && types.length > 0) {
                    String chosen = types[0];
                    for (String t : types) if (t.equalsIgnoreCase("Lossy")) chosen = t;
                    p.setCompressionType(chosen);
                }
                p.setCompressionQuality(quality / 100f);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
                w.setOutput(ios);
                w.write(null, new IIOImage(src, null, null), p);
            }
            return bos.toByteArray();
        } finally {
            w.dispose();
        }
    }

    // ---------- EXIF (minimalny parser: orientacja + obecność GPS) ----------

    static class ExifInfo {
        boolean has;
        boolean gps;
        int orientation = 1;
    }

    static ExifInfo readExif(File f) {
        ExifInfo e = new ExifInfo();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            if (in.readUnsignedShort() != 0xFFD8) return e;
            while (true) {
                int marker = in.readUnsignedShort();
                if ((marker & 0xFF00) != 0xFF00 || marker == 0xFFDA) break;
                int len = in.readUnsignedShort() - 2;
                if (len < 0) break;
                if (marker == 0xFFE1 && len >= 14) {
                    byte[] seg = new byte[len];
                    in.readFully(seg);
                    if (seg[0] == 'E' && seg[1] == 'x' && seg[2] == 'i' && seg[3] == 'f') {
                        e.has = true;
                        parseTiff(seg, 6, e);
                    }
                } else {
                    int left = len;
                    while (left > 0) {
                        int s = in.skipBytes(left);
                        if (s <= 0) {
                            in.readByte();
                            s = 1;
                        }
                        left -= s;
                    }
                }
            }
        } catch (IOException ignored) {
            // koniec pliku / uszkodzony nagłówek – zostaje to, co udało się odczytać
        }
        return e;
    }

    private static void parseTiff(byte[] b, int base, ExifInfo e) {
        try {
            boolean le = b[base] == 'I';
            int ifd = base + rd32(b, base + 4, le);
            int n = rd16(b, ifd, le);
            for (int i = 0; i < n; i++) {
                int p = ifd + 2 + i * 12;
                int tag = rd16(b, p, le);
                if (tag == 0x0112) {
                    e.orientation = rd16(b, p + 8, le);
                } else if (tag == 0x8825) {
                    int gifd = base + rd32(b, p + 8, le);
                    if (rd16(b, gifd, le) > 1) e.gps = true;
                }
            }
        } catch (RuntimeException ex) {
            // uszkodzony EXIF – ignorujemy
        }
    }

    private static int rd16(byte[] b, int p, boolean le) {
        return le ? (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8)
                : ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    private static int rd32(byte[] b, int p, boolean le) {
        return le ? (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24)
                : ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    /** Obraca/odbija piksele zgodnie z tagiem EXIF Orientation (1–8). */
    static BufferedImage applyOrientation(BufferedImage src, int o) {
        if (o < 2 || o > 8) return src;
        int w = src.getWidth(), h = src.getHeight();
        boolean swap = o >= 5;
        int nw = swap ? h : w, nh = swap ? w : h;
        int[] in = src.getRGB(0, 0, w, h, null, 0, w);
        int[] out = new int[in.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dx, dy;
                switch (o) {
                    case 2: dx = w - 1 - x; dy = y; break;
                    case 3: dx = w - 1 - x; dy = h - 1 - y; break;
                    case 4: dx = x; dy = h - 1 - y; break;
                    case 5: dx = y; dy = x; break;
                    case 6: dx = h - 1 - y; dy = x; break;
                    case 7: dx = h - 1 - y; dy = w - 1 - x; break;
                    default: dx = y; dy = w - 1 - x; break;
                }
                out[dy * nw + dx] = in[y * w + x];
            }
        }
        boolean alpha = src.getColorModel().hasAlpha();
        BufferedImage dst = new BufferedImage(nw, nh, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        dst.setRGB(0, 0, nw, nh, out, 0, nw);
        return dst;
    }

    static boolean isImageName(String n) {
        n = n.toLowerCase();
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".bmp")
                || n.endsWith(".gif") || (FORMATS.contains("WebP") && n.endsWith(".webp"));
    }

    static BufferedImage readOriented(File f) throws IOException {
        BufferedImage img = ImageIO.read(f);
        if (img == null) throw new IOException("Nieobsługiwany format pliku.");
        return applyOrientation(img, readExif(f).orientation);
    }

    // ---------- modele danych ----------

    /** Wczytane zdjęcie + podręczne pamięci (przeskalowane wersje i zakodowane bajty). */
    static class Loaded {
        final File file;
        final BufferedImage img;
        final BufferedImage thumb;
        final long fileSize;
        final ExifInfo exif;
        private final Map<String, BufferedImage> scaledCache = new HashMap<>();
        private final Map<String, byte[]> encCache = new HashMap<>();

        Loaded(File file, BufferedImage img, long fileSize, ExifInfo exif) {
            this.file = file;
            this.img = img;
            this.fileSize = fileSize;
            this.exif = exif;
            this.thumb = thumb(img, 1000);
        }

        synchronized BufferedImage scaledFor(int preset, boolean byShort) {
            String key = preset + (byShort ? "s" : "l");
            BufferedImage c = scaledCache.get(key);
            if (c == null) {
                c = scaleTo(img, preset, byShort);
                scaledCache.put(key, c);
            }
            return c;
        }

        byte[] encoded(int preset, boolean byShort, String fmt, int q) throws IOException {
            String key = preset + "|" + byShort + "|" + fmt + "|" + (fmt.equals("PNG") ? 0 : q);
            synchronized (this) {
                byte[] b = encCache.get(key);
                if (b != null) return b;
            }
            byte[] b = encode(scaledFor(preset, byShort), fmt, q);
            synchronized (this) {
                if (encCache.size() > 40) encCache.clear();
                encCache.put(key, b);
            }
            return b;
        }
    }

    static class Result {
        Loaded loaded;
        String format;
        int selected;
        final Map<Integer, byte[]> bytes = new HashMap<>();
        final Map<Integer, Dimension> dims = new HashMap<>();
        final Map<String, Long> formatSizes = new LinkedHashMap<>();
        BufferedImage preview;
    }

    // =====================================================================
    //  GUI
    // =====================================================================

    private Loaded loaded;
    private Result lastResult;
    private int gen = 0;
    private SwingWorker<Result, Void> worker;
    private final javax.swing.Timer debounce = new javax.swing.Timer(300, e -> refresh());

    private final JRadioButton[] radios = new JRadioButton[PRESETS.length];
    private final JComboBox<String> sideBox =
            new JComboBox<>(new String[]{"krótszego boku (jak w wideo)", "dłuższego boku"});
    private final JComboBox<String> formatBox = new JComboBox<>(FORMATS.toArray(new String[0]));
    private final JSlider qualitySlider = new JSlider(50, 95, 80);
    private final JLabel qualityLabel = new JLabel("Jakość: 80%");
    private final JLabel noteLabel = new JLabel(" ");
    private final JLabel compareLabel = new JLabel(" ");
    private final JLabel exifLabel = new JLabel("Dane EXIF: wczytaj zdjęcie");
    private final JLabel fileLabel = new JLabel("Nie wczytano zdjęcia");
    private final JLabel statusLabel = new JLabel("Gotowe");
    private final JLabel leftCaption = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel rightCaption = new JLabel(" ", SwingConstants.CENTER);
    private final ImagePanel leftPanel = new ImagePanel();
    private final ImagePanel rightPanel = new ImagePanel();
    private final JButton loadBtn = new JButton("Wczytaj zdjęcie…");
    private final JButton batchBtn = new JButton("Przetwórz cały folder…");
    private final JButton saveBtn = new JButton("Zapisz wybrany wariant…");
    private final JProgressBar progress = new JProgressBar(0, 100);

    public KompresorZdjec() {
        super("Kompresor zdjęć");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        debounce.setRepeats(false);

        // --- rozdzielczość ---
        JPanel resPanel = box("Rozdzielczość");
        ButtonGroup group = new ButtonGroup();
        for (int i = 0; i < radios.length; i++) {
            radios[i] = new JRadioButton(presetName(PRESETS[i]));
            radios[i].setAlignmentX(Component.LEFT_ALIGNMENT);
            radios[i].addActionListener(e -> refresh());
            group.add(radios[i]);
            resPanel.add(radios[i]);
        }
        radios[1].setSelected(true);
        noteLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        resPanel.add(noteLabel);
        resPanel.add(Box.createVerticalStrut(6));
        JLabel sideLbl = new JLabel("Rozdzielczość odnosi się do:");
        sideLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        sideBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        resPanel.add(sideLbl);
        resPanel.add(sideBox);

        // --- format i jakość ---
        JPanel fmtPanel = box("Format i jakość");
        JLabel fmtLbl = new JLabel("Format wyjściowy:");
        fmtLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        formatBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        qualityLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        qualitySlider.setAlignmentX(Component.LEFT_ALIGNMENT);
        qualitySlider.setMajorTickSpacing(15);
        qualitySlider.setMinorTickSpacing(5);
        qualitySlider.setPaintTicks(true);
        qualitySlider.setPaintLabels(true);
        compareLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        fmtPanel.add(fmtLbl);
        fmtPanel.add(formatBox);
        fmtPanel.add(Box.createVerticalStrut(6));
        fmtPanel.add(qualityLabel);
        fmtPanel.add(qualitySlider);
        fmtPanel.add(Box.createVerticalStrut(6));
        fmtPanel.add(compareLabel);

        // --- informacje (EXIF) ---
        JPanel infoPanel = box("Prywatność (EXIF)");
        exifLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        infoPanel.add(exifLabel);

        saveBtn.setEnabled(false);
        saveBtn.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.add(resPanel);
        side.add(Box.createVerticalStrut(8));
        side.add(fmtPanel);
        side.add(Box.createVerticalStrut(8));
        side.add(infoPanel);
        side.add(Box.createVerticalStrut(10));
        side.add(saveBtn);
        side.add(Box.createVerticalGlue());

        // --- góra, środek, dół ---
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        top.add(loadBtn);
        top.add(batchBtn);
        top.add(fileLabel);

        JPanel center = new JPanel(new GridLayout(1, 2, 8, 8));
        center.add(framed("Oryginał", leftPanel, leftCaption));
        center.add(framed("Po kompresji", rightPanel, rightCaption));

        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.add(statusLabel, BorderLayout.CENTER);
        bottom.add(progress, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(top, BorderLayout.NORTH);
        root.add(side, BorderLayout.WEST);
        root.add(center, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        setContentPane(root);

        // --- zdarzenia ---
        loadBtn.addActionListener(e -> chooseFile());
        batchBtn.addActionListener(e -> batch());
        saveBtn.addActionListener(e -> save());
        formatBox.addActionListener(e -> {
            updateQualityUi();
            refresh();
        });
        sideBox.addActionListener(e -> refresh());
        qualitySlider.addChangeListener(e -> {
            updateQualityUi();
            debounce.restart();
        });

        root.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport s) {
                return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport s) {
                try {
                    List<File> files = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (!files.isEmpty()) loadFile(files.get(0));
                    return true;
                } catch (Exception ex) {
                    return false;
                }
            }
        });
    }

    private static JPanel box(String title) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(title), BorderFactory.createEmptyBorder(2, 6, 6, 6)));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private static JPanel framed(String title, ImagePanel p, JLabel caption) {
        JPanel f = new JPanel(new BorderLayout(0, 4));
        f.setBorder(BorderFactory.createTitledBorder(title));
        f.add(p, BorderLayout.CENTER);
        f.add(caption, BorderLayout.SOUTH);
        return f;
    }

    private int selectedPreset() {
        for (int i = 0; i < radios.length; i++) if (radios[i].isSelected()) return PRESETS[i];
        return 720;
    }

    private void updateQualityUi() {
        boolean png = "PNG".equals(formatBox.getSelectedItem());
        qualitySlider.setEnabled(!png);
        qualityLabel.setText(png ? "Jakość: PNG jest bezstratny (suwak nieaktywny)"
                : "Jakość: " + qualitySlider.getValue() + "%");
    }

    private void busy(boolean b) {
        progress.setIndeterminate(b);
        if (!b) progress.setValue(0);
    }

    // ---------- wczytywanie ----------

    private void chooseFile() {
        JFileChooser fc = new JFileChooser();
        List<String> exts = new ArrayList<>(Arrays.asList("jpg", "jpeg", "png", "bmp", "gif"));
        if (FORMATS.contains("WebP")) exts.add("webp");
        fc.setFileFilter(new FileNameExtensionFilter("Obrazy", exts.toArray(new String[0])));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) loadFile(fc.getSelectedFile());
    }

    private void loadFile(File f) {
        statusLabel.setText("Wczytywanie: " + f.getName() + "…");
        busy(true);
        new SwingWorker<Loaded, Void>() {
            @Override
            protected Loaded doInBackground() throws Exception {
                BufferedImage img = ImageIO.read(f);
                if (img == null) throw new IOException("Nieobsługiwany format pliku.");
                ExifInfo ex = readExif(f);
                img = applyOrientation(img, ex.orientation);
                return new Loaded(f, img, f.length(), ex);
            }

            @Override
            protected void done() {
                try {
                    Loaded L = get();
                    loaded = L;
                    lastResult = null;
                    leftPanel.setImage(L.thumb);
                    rightPanel.setImage(null);
                    leftCaption.setText(String.format(PL, "%d×%d · %s", L.img.getWidth(), L.img.getHeight(), size(L.fileSize)));
                    rightCaption.setText(" ");
                    fileLabel.setText(L.file.getName());
                    if (L.exif.has) {
                        exifLabel.setText("<html><b>Uwaga:</b> oryginał zawiera dane EXIF"
                                + (L.exif.gps ? " <b>z lokalizacją GPS</b>" : "")
                                + ".<br>W zapisanych plikach są usuwane.</html>");
                    } else {
                        exifLabel.setText("<html>Oryginał nie zawiera danych EXIF.<br>"
                                + "(Zapisane pliki też nie mają metadanych.)</html>");
                    }
                    refresh();
                } catch (Exception ex) {
                    busy(false);
                    statusLabel.setText("Nie udało się wczytać pliku");
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(KompresorZdjec.this,
                            "Nie udało się wczytać zdjęcia:\n" + c.getMessage(), "Błąd", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    // ---------- przeliczanie rozmiarów ----------

    private void refresh() {
        if (loaded == null) return;
        if (worker != null) worker.cancel(false);
        final int myGen = ++gen;
        final Loaded L = loaded;
        final String fmt = (String) formatBox.getSelectedItem();
        final int q = qualitySlider.getValue();
        final int sel = selectedPreset();
        final boolean byShort = sideBox.getSelectedIndex() == 0;
        saveBtn.setEnabled(false);
        busy(true);
        statusLabel.setText("Liczenie rozmiarów…");

        worker = new SwingWorker<>() {
            @Override
            protected Result doInBackground() throws Exception {
                Result r = new Result();
                r.loaded = L;
                r.format = fmt;
                r.selected = sel;
                for (int p : PRESETS) {
                    if (isCancelled()) return r;
                    BufferedImage s = L.scaledFor(p, byShort);
                    r.dims.put(p, new Dimension(s.getWidth(), s.getHeight()));
                    r.bytes.put(p, L.encoded(p, byShort, fmt, q));
                }
                for (String f : FORMATS) {
                    if (isCancelled()) return r;
                    r.formatSizes.put(f, (long) L.encoded(sel, byShort, f, q).length);
                }
                byte[] selBytes = r.bytes.get(sel);
                BufferedImage dec = ImageIO.read(new ByteArrayInputStream(selBytes));
                r.preview = thumb(dec != null ? dec : L.scaledFor(sel, byShort), 1000);
                return r;
            }

            @Override
            protected void done() {
                if (myGen != gen || isCancelled()) return;
                try {
                    Result r = get();
                    lastResult = r;
                    showResult(r);
                } catch (Exception ex) {
                    busy(false);
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    statusLabel.setText("Błąd: " + c.getMessage());
                }
            }
        };
        worker.execute();
    }

    private void showResult(Result r) {
        Loaded L = r.loaded;
        long orig = L.fileSize;
        boolean anyNoUpscale = false;
        for (int i = 0; i < PRESETS.length; i++) {
            int p = PRESETS[i];
            Dimension d = r.dims.get(p);
            long b = r.bytes.get(p).length;
            boolean noUp = p != 0 && d.width == L.img.getWidth() && d.height == L.img.getHeight();
            anyNoUpscale |= noUp;
            radios[i].setText(String.format(PL, "%s%s  ·  %d×%d  ·  %s  (%s)",
                    presetName(p), noUp ? "*" : "", d.width, d.height, size(b), delta(b, orig)));
        }
        noteLabel.setText(anyNoUpscale
                ? "<html>* zdjęcie jest mniejsze niż ta rozdzielczość – nie powiększamy</html>" : " ");

        StringBuilder sb = new StringBuilder("<html>Wariant " + presetName(r.selected) + " w różnych formatach:<br>");
        boolean first = true;
        for (Map.Entry<String, Long> en : r.formatSizes.entrySet()) {
            if (!first) sb.append(" · ");
            sb.append("<b>").append(en.getKey()).append("</b> ").append(size(en.getValue()));
            first = false;
        }
        if (!FORMATS.contains("WebP")) sb.append("<br><i>WebP: brak wtyczki (patrz nagłówek pliku)</i>");
        sb.append("</html>");
        compareLabel.setText(sb.toString());

        Dimension sd = r.dims.get(r.selected);
        long sb2 = r.bytes.get(r.selected).length;
        rightPanel.setImage(r.preview);
        rightCaption.setText(String.format(PL, "%d×%d · %s (%s)", sd.width, sd.height, size(sb2), delta(sb2, orig)));
        statusLabel.setText("Gotowe – " + presetName(r.selected) + ": " + size(sb2)
                + " (" + delta(sb2, orig) + " względem oryginału)");
        saveBtn.setEnabled(true);
        busy(false);
    }

    // ---------- zapis ----------

    private void save() {
        if (lastResult == null || loaded == null) return;
        int p = selectedPreset();
        byte[] data = lastResult.bytes.get(p);
        String ext = ext(lastResult.format);
        JFileChooser fc = new JFileChooser(loaded.file.getParentFile());
        fc.setSelectedFile(new File(loaded.file.getParentFile(),
                stripExt(loaded.file.getName()) + "_" + (p == 0 ? "oryg" : p + "p") + ext));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File out = fc.getSelectedFile();
        String n = out.getName().toLowerCase();
        boolean hasExt = n.endsWith(ext) || (ext.equals(".jpg") && n.endsWith(".jpeg"));
        if (!hasExt) out = new File(out.getParentFile(), out.getName() + ext);
        if (out.exists() && JOptionPane.showConfirmDialog(this,
                "Plik " + out.getName() + " już istnieje. Nadpisać?", "Zapis",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try {
            Files.write(out.toPath(), data);
            statusLabel.setText("Zapisano: " + out.getName() + " (" + size(data.length) + ")");
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Nie udało się zapisać pliku:\n" + ex.getMessage(),
                    "Błąd", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---------- tryb wsadowy (cały folder) ----------

    private void batch() {
        JFileChooser fc = new JFileChooser(loaded != null ? loaded.file.getParentFile() : null);
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Wybierz folder ze zdjęciami");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File dir = fc.getSelectedFile();
        File[] files = dir.listFiles((d, n) -> isImageName(n));
        if (files == null || files.length == 0) {
            JOptionPane.showMessageDialog(this, "W tym folderze nie ma zdjęć (jpg, png, bmp, gif).");
            return;
        }
        Arrays.sort(files);

        final String fmt = (String) formatBox.getSelectedItem();
        final int q = qualitySlider.getValue();
        final int preset = selectedPreset();
        final boolean byShort = sideBox.getSelectedIndex() == 0;
        final File outDir = new File(dir, "skompresowane_" + (preset == 0 ? "oryginal" : preset + "p"));
        final String label = preset == 0 ? "oryg" : preset + "p";

        int ok = JOptionPane.showConfirmDialog(this,
                "Znaleziono zdjęć: " + files.length + "\nUstawienia: " + presetName(preset) + ", " + fmt
                        + ("PNG".equals(fmt) ? "" : ", jakość " + q + "%")
                        + "\nWyniki trafią do folderu:\n" + outDir.getAbsolutePath(),
                "Przetwarzanie folderu", JOptionPane.OK_CANCEL_OPTION);
        if (ok != JOptionPane.OK_OPTION) return;

        final File[] list = files;
        loadBtn.setEnabled(false);
        batchBtn.setEnabled(false);
        progress.setIndeterminate(false);
        progress.setMaximum(list.length);
        progress.setValue(0);
        statusLabel.setText("Przetwarzanie folderu…");

        new SwingWorker<long[], Integer>() {
            @Override
            protected long[] doInBackground() {
                outDir.mkdirs();
                long okCount = 0, errCount = 0, in = 0, out = 0;
                for (int i = 0; i < list.length; i++) {
                    File f = list[i];
                    try {
                        BufferedImage img = readOriented(f);
                        byte[] data = encode(scaleTo(img, preset, byShort), fmt, q);
                        Files.write(new File(outDir, stripExt(f.getName()) + "_" + label + ext(fmt)).toPath(), data);
                        in += f.length();
                        out += data.length;
                        okCount++;
                    } catch (Exception | OutOfMemoryError ex) {
                        errCount++;
                    }
                    publish(i + 1);
                }
                return new long[]{okCount, errCount, in, out};
            }

            @Override
            protected void process(List<Integer> chunks) {
                progress.setValue(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                loadBtn.setEnabled(true);
                batchBtn.setEnabled(true);
                progress.setValue(0);
                try {
                    long[] r = get();
                    long saved = r[2] - r[3];
                    String msg = String.format(PL,
                            "Przetworzono: %d plików%s\nPrzed: %s\nPo: %s\nZaoszczędzono: %s (%s)\n\nFolder wynikowy:\n%s",
                            r[0], r[1] > 0 ? " (błędy: " + r[1] + ")" : "",
                            size(r[2]), size(r[3]), size(Math.max(0, saved)),
                            r[2] > 0 ? String.format(PL, "%.0f%%", 100.0 * saved / r[2]) : "0%",
                            outDir.getAbsolutePath());
                    statusLabel.setText("Folder gotowy: zaoszczędzono " + size(Math.max(0, saved)));
                    JOptionPane.showMessageDialog(KompresorZdjec.this, msg, "Gotowe", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    statusLabel.setText("Błąd przetwarzania folderu");
                }
            }
        }.execute();
    }

    // ---------- panel podglądu ----------

    static class ImagePanel extends JPanel {
        private BufferedImage img;

        ImagePanel() {
            setBackground(new Color(0x303030));
            setPreferredSize(new Dimension(300, 300));
        }

        void setImage(BufferedImage i) {
            img = i;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (img == null) {
                String s = "Przeciągnij tu zdjęcie";
                g.setColor(Color.LIGHT_GRAY);
                FontMetrics fm = g.getFontMetrics();
                g.drawString(s, (getWidth() - fm.stringWidth(s)) / 2, getHeight() / 2);
            } else {
                double sc = Math.min((double) getWidth() / img.getWidth(), (double) getHeight() / img.getHeight());
                int w = Math.max(1, (int) (img.getWidth() * sc));
                int h = Math.max(1, (int) (img.getHeight() * sc));
                g.drawImage(img, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
            }
            g.dispose();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // zostaje domyślny wygląd
            }
            KompresorZdjec f = new KompresorZdjec();
            f.setSize(1150, 700);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }
}
