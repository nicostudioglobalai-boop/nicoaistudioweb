import javax.swing.*;
import javax.swing.filechooser.FileSystemView;
import java.awt.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * SwiftVault-PC - desktop companion for SwiftVault (Android).
 * Organises a project folder, compresses old files into archives,
 * finds duplicate files, and reports free space.
 *
 * GUI (double-click the jar) or command line:
 *   java -jar SwiftVault-PC.jar <folder> [days]
 *   java -jar SwiftVault-PC.jar --selftest
 */
public class DesktopVault {

    private Path folder;
    private final JTextArea log = new JTextArea();
    private final JLabel folderLabel =
            new JLabel("  Folder: (none chosen)  ");
    private static final String[] TYPES = {
            "Images", "Videos", "Audio", "Documents", "Archives", "Other"
    };

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--selftest".equals(args[0])) {
            System.exit(selftest() ? 0 : 1);
        }
        if (args.length > 0 && Files.isDirectory(Paths.get(args[0]))) {
            Path f = Paths.get(args[0]);
            int days = args.length > 1 ? Integer.parseInt(args[1]) : 90;
            organise(f); zipOld(f, days); findDuplicates(f); freeSpace(f);
            System.out.println("Done.");
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("No display. Use: java -jar SwiftVault-PC.jar <folder> [days]");
            System.exit(2);
        }
        SwingUtilities.invokeLater(() -> new DesktopVault().show());
    }

    private void show() {
        JFrame f = new JFrame("SwiftVault PC - project file organiser");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setSize(620, 480);
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JButton pick = new JButton("1. Choose folder");
        JButton org = new JButton("2. Organise by type");
        JButton zip = new JButton("3. Compress old files (90+ days)");
        JButton dup = new JButton("4. Find duplicates");
        JButton space = new JButton("5. Free-space report");

        pick.addActionListener(e -> {
            JFileChooser ch = new JFileChooser();
            ch.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            ch.setDialogTitle("Pick your projects folder");
            if (ch.showOpenDialog(f) == JFileChooser.APPROVE_OPTION) {
                folder = ch.getSelectedFile().toPath();
                folderLabel.setText("  Folder: " + folder + "  ");
                if (System.getProperty("os.name", "").contains("Mac")) {
                    FileSystemView.getFileSystemView();
                }
            }
        });
        org.addActionListener(e -> run(() -> organise(folder)));
        zip.addActionListener(e -> run(() -> zipOld(folder, 90)));
        dup.addActionListener(e -> run(() -> findDuplicates(folder)));
        space.addActionListener(e -> run(() -> freeSpace(folder)));

        JPanel top = new JPanel(new GridLayout(1, 5));
        top.add(pick); top.add(org); top.add(zip); top.add(dup); top.add(space);
        f.add(folderLabel, BorderLayout.NORTH);
        f.add(new JScrollPane(log), BorderLayout.CENTER);
        f.add(top, BorderLayout.SOUTH);
        f.setLocationRelativeTo(null);
        f.setVisible(true);
    }

    private interface Task { void go() throws Exception; }

    private void run(Task task) {
        if (folder == null) {
            say("Choose a folder first (button 1).");
            return;
        }
        new Thread(() -> {
            try { task.go(); } catch (Exception ex) { say("Error: " + ex); }
        }, "vault").start();
    }

    private void say(String s) {
        SwingUtilities.invokeLater(() -> log.append(s + "\n"));
        System.out.println(s);
    }

    /** Moves every file at the top level into <type>/<year-month>/ subfolders. */
    static void organise(Path root) throws IOException {
        List<Path> files = topFiles(root);
        DateTimeFormatter ym = DateTimeFormatter.ofPattern("yyyy-MM");
        for (Path p : files) {
            String name = p.getFileName().toString().toLowerCase();
            String type = typeOf(name);
            LocalDate date = LocalDate.now();
            try {
                date = Files.getLastModifiedTime(p).toInstant()
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            } catch (Exception ignore) { }
            Path dir = root.resolve(type).resolve(ym.format(date));
            Files.createDirectories(dir);
            Path target = unique(dir.resolve(p.getFileName()));
            Files.move(p, target, StandardCopyOption.ATOMIC_MOVE);
            System.out.println("moved: " + p.getFileName() + " -> " + type + "/" + ym.format(date));
        }
        System.out.println("Organised " + files.size() + " file(s).");
    }

    /** Zips files older than [days] into Archive/<name>.zip and deletes the original. */
    static void zipOld(Path root, int days) throws IOException {
        long cutoff = System.currentTimeMillis() - days * 24L * 3600_000L;
        Files.createDirectories(root.resolve("Archive"));
        int count = 0;
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toList())) {
                if (inside(p, root, "Archive")) continue;
                if (Files.getLastModifiedTime(p).toMillis() >= cutoff) continue;
                String base = p.getFileName().toString();
                int dot = base.lastIndexOf('.');
                if (dot > 0) base = base.substring(0, dot);
                Path zip = root.resolve("Archive").resolve(unique(root
                        .resolve("Archive").resolve(base + ".zip")).getFileName());
                try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip.toFile()))) {
                    z.putNextEntry(new ZipEntry(p.getFileName().toString()));
                    Files.copy(p, z);
                    z.closeEntry();
                }
                Files.delete(p);
                count++;
            }
        }
        System.out.println("Compressed " + count + " old file(s) into Archive/.");
    }

    /** Writes duplicates-report.txt listing size+MD5 duplicate groups. */
    static void findDuplicates(Path root) throws IOException {
        Map<Long, List<Path>> bySize = new HashMap<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toList())) {
                if (inside(p, root, "Archive")) continue;
                bySize.computeIfAbsent(Files.size(p), k -> new ArrayList<>()).add(p);
            }
        }
        List<String> report = new ArrayList<>();
        int dupes = 0;
        for (List<Path> group : bySize.values()) {
            if (group.size() < 2) continue;
            Map<String, List<Path>> byHash = new LinkedHashMap<>();
            for (Path p : group) {
                byHash.computeIfAbsent(md5(p), k -> new ArrayList<>()).add(p);
            }
            for (List<Path> same : byHash.values()) {
                if (same.size() < 2) continue;
                dupes += same.size() - 1;
                report.add("DUPLICATE (" + fmt(Files.size(same.get(0))) + " each):");
                same.forEach(x -> report.add("  " + x));
                report.add("");
            }
        }
        Path out = root.resolve("duplicates-report.txt");
        Files.write(out, report);
        System.out.println("Found " + dupes + " duplicate file(s). Report: " + out);
    }

    static void freeSpace(Path root) throws IOException {
        FileStore store = Files.getFileStore(root);
        System.out.println("Folder: " + root);
        System.out.println("Drive:  " + store.name());
        System.out.println("Total:  " + fmt(store.getTotalSpace()));
        System.out.println("Used:   " + fmt(store.getTotalSpace() - store.getUsableSpace()));
        System.out.println("Free:   " + fmt(store.getUsableSpace()));
    }

    /* ---------- helpers ---------- */

    private static String typeOf(String lowerName) {
        if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")
                || lowerName.endsWith(".png") || lowerName.endsWith(".gift")
                || lowerName.endsWith(".gif") || lowerName.endsWith(".webp")
                || lowerName.endsWith(".heic")) return "Images";
        if (lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv")
                || lowerName.endsWith(".mov") || lowerName.endsWith(".avi"))
            return "Videos";
        if (lowerName.endsWith(".mp3") || lowerName.endsWith(".wav")
                || lowerName.endsWith(".m4a") || lowerName.endsWith(".ogg"))
            return "Audio";
        if (lowerName.endsWith(".doc") || lowerName.endsWith(".docx")
                || lowerName.endsWith(".pdf") || lowerName.endsWith(".xls")
                || lowerName.endsWith(".xlsx") || lowerName.endsWith(".ppt")
                || lowerName.endsWith(".pptx") || lowerName.endsWith(".txt")
                || lowerName.endsWith(".csv")) return "Documents";
        if (lowerName.endsWith(".zip") || lowerName.endsWith(".rar")
                || lowerName.endsWith(".7z") || lowerName.endsWith(".tar")
                || lowerName.endsWith(".gz")) return "Archives";
        return "Other";
    }

    private static boolean inside(Path p, Path root, String dir) {
        return p.getParent() != null && p.getParent().startsWith(root.resolve(dir));
    }

    private static Path unique(Path target) {
        if (!Files.exists(target)) return target;
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            Path cand = target.resolveSibling(stem + " (" + i + ")" + ext);
            if (!Files.exists(cand)) return cand;
        }
    }

    private static List<Path> topFiles(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(root, Files::isRegularFile)) {
            for (Path p : s) out.add(p);
        }
        return out;
    }

    private static String md5(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "err-" + UUID.randomUUID();
        }
    }

    private static String fmt(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        for (String u : new String[]{"KB", "MB", "GB", "TB"}) {
            v /= 1024;
            if (v < 1024) return String.format("%.1f %s", v, u);
        }
        return String.format("%.1f PB", v / 1024);
    }

    /* ---------- selftest ---------- */

    static boolean selftest() throws Exception {
        boolean ok = true;
        Path t = Files.createTempDirectory("vault-test");
        System.out.println("== SwiftVault-PC selftest in " + t + " ==");

        Files.write(t.resolve("a-report.csv"), "id,name\n1,big project\n".getBytes());
        Files.write(t.resolve("logo.png"), new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        Files.write(t.resolve("logo copy.png"), new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        Files.write(t.resolve("brief.pdf"), "brief".getBytes());

        organise(t);
        Map<String, Boolean> checks = new LinkedHashMap<>();
        String ym = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        checks.put("organise Images", Files.exists(t.resolve("Images").resolve(ym).resolve("logo.png")));
        checks.put("organise Documents", Files.exists(t.resolve("Documents").resolve(ym).resolve("brief.pdf")));
        checks.put("organise folder bucketed", Files.exists(t.resolve("a-report.csv")) == false);
        ok &= checks.values().stream().allMatch(Boolean::booleanValue);

        Path old = t.resolve("Images").resolve("ancient.png");
        Files.write(old, new byte[]{9, 9});
        Files.setLastModifiedTime(old,
                java.nio.file.attribute.FileTime.fromMillis(
                        System.currentTimeMillis() - 200L * 24 * 3600_000L));
        zipOld(t, 90);
        boolean zipped = Files.exists(t.resolve("Archive").resolve("ancient.png".replace(".png", "") + ".zip"))
                && !Files.exists(old);
        ok &= zipped;
        System.out.println("zipOld (90 days):  " + (zipped ? "PASS" : "FAIL"));

        findDuplicates(t);
        String rep = new String(Files.readAllBytes(t.resolve("duplicates-report.txt")));
        boolean dupOk = rep.contains("logo.png") && rep.contains("logo copy.png");
        ok &= dupOk;
        System.out.println("findDuplicates:    " + (dupOk ? "PASS" : "FAIL"));

        freeSpace(t);
        for (Map.Entry<String, Boolean> e : checks.entrySet()) {
            System.out.printf("%-18s %s%n", e.getKey() + ":", e.getValue() ? "PASS" : "FAIL");
        }
        System.out.println("== SELFTEST " + (ok ? "PASSED" : "FAILED") + " ==");
        return ok;
    }
}
