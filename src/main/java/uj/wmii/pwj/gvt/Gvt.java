package uj.wmii.pwj.gvt;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Gvt {

    private final ExitHandler exitHandler;

    private static final String GVT_DIR = ".gvt";
    private static final String VERSIONS_DIR = GVT_DIR + File.separator + "versions";
    private static final String OBJECTS_DIR = GVT_DIR + File.separator + "objects";
    private static final String META_FILE = GVT_DIR + File.separator + "meta.properties";

    private int lastVersion = -1;
    private int activeVersion = -1;

    public Gvt(ExitHandler exitHandler) {
        this.exitHandler = exitHandler;
    }

    public static void main(String... args) {
        Gvt gvt = new Gvt(new ExitHandler());
        gvt.mainInternal(args);
    }

    public void mainInternal(String... args) {
        if (args == null || args.length == 0) {
            exitHandler.exit(1, "Please specify command.");
            return;
        }
        String cmd = args[0];
        try {
            switch (cmd) {
                case "init":
                    init();
                    break;
                case "add":
                    ensureInitializedOrExit();
                    add(stripFirst(args));
                    break;
                case "detach":
                    ensureInitializedOrExit();
                    detach(stripFirst(args));
                    break;
                case "commit":
                    ensureInitializedOrExit();
                    commit(stripFirst(args));
                    break;
                case "checkout":
                    ensureInitializedOrExit();
                    checkout(stripFirst(args));
                    break;
                case "history":
                    ensureInitializedOrExit();
                    history(stripFirst(args));
                    break;
                case "version":
                    ensureInitializedOrExit();
                    version(stripFirst(args));
                    break;
                default:
                    exitHandler.exit(1, "Unknown command " + cmd + ".");
            }
        } catch (UnderlyingException ue) {
        }
    }

    private void init() {
        try {
            Path gvt = Paths.get(GVT_DIR);
            if (Files.exists(gvt)) {
                exitHandler.exit(10, "Current directory is already initialized.");
                return;
            }
            Files.createDirectory(gvt);
            Files.createDirectories(Paths.get(VERSIONS_DIR));
            Files.createDirectories(Paths.get(OBJECTS_DIR));
            writeVersionMetadata(0, "GVT initialized.", Collections.emptyList());
            writeMetaProps(0, 0);
            Files.createDirectories(Paths.get(OBJECTS_DIR, "0"));
            exitHandler.exit(0, "Current directory initialized successfully.");
        } catch (IOException e) {
            printStackAndExitWithUnderlying(e);
        }
    }

    private void add(String[] args) {
        ParsedCommand pc = parseFileAndMessage(args);
        if (pc.fileName == null) {
            exitHandler.exit(20, "Please specify file to add.");
            return;
        }
        Path file = Paths.get(pc.fileName);
        if (!Files.exists(file)) {
            exitHandler.exit(21, "File not found. File: " + pc.fileName);
            return;
        }
        try {
            loadMeta();
            VersionMeta lastMeta = loadVersionMeta(lastVersion);
            if (lastMeta.files.contains(pc.fileName)) {
                exitHandler.exit(0, "File already added. File: " + pc.fileName);
                return;
            }
            List<String> newFiles = new ArrayList<>(lastMeta.files);
            newFiles.add(pc.fileName);
            int newVersion = nextVersion();
            Path objDir = Paths.get(OBJECTS_DIR, String.valueOf(newVersion));
            Files.createDirectories(objDir);
            copyFileToObjects(file, objDir.resolve(pc.fileName));
            writeVersionMetadata(newVersion, defaultMessage("File added successfully. File: " + pc.fileName, pc.message), newFiles);
            exitHandler.exit(0, "File added successfully. File: " + pc.fileName);
        } catch (IOException e) {
            System.err.println();
            e.printStackTrace(System.err);
            exitHandler.exit(22, "File cannot be added. See ERR for details. File: " + pc.fileName);
        }
    }

    private void detach(String[] args) {
        ParsedCommand pc = parseFileAndMessage(args);
        if (pc.fileName == null) {
            exitHandler.exit(30, "Please specify file to detach.");
            return;
        }
        try {
            loadMeta();
            VersionMeta lastMeta = loadVersionMeta(lastVersion);
            if (!lastMeta.files.contains(pc.fileName)) {
                exitHandler.exit(0, "File is not added to gvt. File: " + pc.fileName);
                return;
            }
            List<String> newFiles = new ArrayList<>(lastMeta.files);
            newFiles.remove(pc.fileName);
            int newVersion = nextVersion();
            Files.createDirectories(Paths.get(OBJECTS_DIR, String.valueOf(newVersion)));
            writeVersionMetadata(newVersion, defaultMessage("File detached successfully. File: " + pc.fileName, pc.message), newFiles);
            exitHandler.exit(0, "File detached successfully. File: " + pc.fileName);
        } catch (IOException e) {
            System.err.println();
            e.printStackTrace(System.err);
            exitHandler.exit(31, "File cannot be detached, see ERR for details. File: " + pc.fileName);
        }
    }

    private void commit(String[] args) {
        ParsedCommand pc = parseFileAndMessage(args);
        if (pc.fileName == null) {
            exitHandler.exit(50, "Please specify file to commit.");
            return;
        }
        Path file = Paths.get(pc.fileName);
        if (!Files.exists(file)) {
            exitHandler.exit(51, "File not found. File: " + pc.fileName);
            return;
        }
        try {
            loadMeta();
            VersionMeta lastMeta = loadVersionMeta(lastVersion);
            if (!lastMeta.files.contains(pc.fileName)) {
                exitHandler.exit(0, "File is not added to gvt. File: " + pc.fileName);
                return;
            }
            List<String> newFiles = new ArrayList<>(lastMeta.files);
            int newVersion = nextVersion();
            Path objDir = Paths.get(OBJECTS_DIR, String.valueOf(newVersion));
            Files.createDirectories(objDir);
            copyFileToObjects(file, objDir.resolve(pc.fileName));
            for (String f : newFiles) {
                if (f.equals(pc.fileName)) continue;
                Path latestObj = findLatestObjectForFile(lastVersion, f);
                if (latestObj != null) {
                    Path dest = objDir.resolve(f);
                    if (dest.getParent() != null) Files.createDirectories(dest.getParent());
                    Files.copy(latestObj, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            writeVersionMetadata(newVersion, defaultMessage("File committed successfully. File: " + pc.fileName, pc.message), newFiles);
            exitHandler.exit(0, "File committed successfully. File: " + pc.fileName);
        } catch (IOException e) {
            System.err.println();
            e.printStackTrace(System.err);
            exitHandler.exit(52, "File cannot be committed, see ERR for details. File: " + pc.fileName);
        }
    }

    private void checkout(String[] args) {
        if (args == null || args.length == 0) {
            exitHandler.exit(60, "Invalid version number: ");
            return;
        }
        String verStr = args[0];
        int ver;
        try {
            ver = Integer.parseInt(verStr);
        } catch (NumberFormatException nfe) {
            exitHandler.exit(60, "Invalid version number: " + verStr);
            return;
        }
        try {
            loadMeta();
            if (!versionExists(ver)) {
                exitHandler.exit(60, "Invalid version number: " + verStr);
                return;
            }
            VersionMeta target = loadVersionMeta(ver);
            Path objDir = Paths.get(OBJECTS_DIR, String.valueOf(ver));
            for (String f : target.files) {
                Path src = objDir.resolve(f);
                if (Files.exists(src)) {
                    Path dest = Paths.get(f);
                    Path parent = dest.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            activeVersion = ver;
            writeMetaProps(lastVersion, activeVersion);
            exitHandler.exit(0, "Checkout successful for version: " + ver);
        } catch (IOException e) {
            printStackAndExitWithUnderlying(e);
        }
    }

    private void history(String[] args) {
        try {
            loadMeta();
            int lastN = -1;
            if (args != null && args.length >= 2 && "-last".equals(args[0])) {
                try {
                    lastN = Integer.parseInt(args[1]);
                } catch (Exception ignored) {
                    lastN = -1;
                }
            }
            List<Integer> versions = listAllVersions();
            if (lastN > 0 && lastN < versions.size()) {
                versions = versions.subList(Math.max(0, versions.size() - lastN), versions.size());
            }
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < versions.size(); i++) {
                int v = versions.get(i);
                VersionMeta meta = loadVersionMeta(v);
                String firstLine = firstLineOf(meta.message);
                out.append(v).append(": ").append(firstLine);
                if (i < versions.size() - 1) out.append(System.lineSeparator());
            }
            exitHandler.exit(0, out.toString());
        } catch (IOException e) {
            printStackAndExitWithUnderlying(e);
        }
    }

    private void version(String[] args) {
        try {
            loadMeta();
            int ver;
            if (args == null || args.length == 0) {
                ver = activeVersion;
            } else {
                try {
                    ver = Integer.parseInt(args[0]);
                } catch (NumberFormatException nfe) {
                    exitHandler.exit(60, "Invalid version number: " + args[0] + ".");
                    return;
                }
            }
            if (!versionExists(ver)) {
                exitHandler.exit(60, "Invalid version number: " + ver + ".");
                return;
            }
            VersionMeta meta = loadVersionMeta(ver);
            StringBuilder sb = new StringBuilder();
            sb.append("Version: ").append(ver).append(System.lineSeparator());
            sb.append(meta.message == null ? "" : meta.message);
            exitHandler.exit(0, sb.toString());
        } catch (IOException e) {
            printStackAndExitWithUnderlying(e);
        }
    }

    private String[] stripFirst(String[] args) {
        if (args == null || args.length <= 1) return new String[0];
        String[] res = new String[args.length - 1];
        System.arraycopy(args, 1, res, 0, res.length);
        return res;
    }

    private static class ParsedCommand {
        String fileName;
        String message;
    }

    private ParsedCommand parseFileAndMessage(String[] args) {
        ParsedCommand pc = new ParsedCommand();
        if (args == null || args.length == 0) return pc;
        List<String> arr = new ArrayList<>(Arrays.asList(args));
        int mIdx = -1;
        for (int i = 0; i < arr.size(); i++) if ("-m".equals(arr.get(i))) mIdx = i;
        if (mIdx != -1 && mIdx + 1 < arr.size()) {
            pc.message = arr.get(mIdx + 1);
            arr.remove(mIdx + 1);
            arr.remove(mIdx);
        }
        if (!arr.isEmpty()) pc.fileName = arr.get(0);
        return pc;
    }

    private String defaultMessage(String base, String user) {
        if (user == null || user.isEmpty()) return base + (base.endsWith(".") ? "" : ".");
        return base + " " + user;
    }

    private String firstLineOf(String s) {
        if (s == null) return "";
        int idx = s.indexOf(' ');
        if (idx == -1) return s;
        return s.substring(0, idx);
    }

    private void ensureInitializedOrExit() {
        if (!Files.exists(Paths.get(GVT_DIR))) {
            exitHandler.exit(-2, "Current directory is not initialized. Please use \"init\" command to initialize.");
            throw new UnderlyingException();
        }
    }

    private void loadMeta() throws IOException {
        Properties p = new Properties();
        Path meta = Paths.get(META_FILE);
        if (!Files.exists(meta)) {
            exitHandler.exit(-2, "Current directory is not initialized. Please use \"init\" command to initialize.");
            throw new UnderlyingException();
        }
        try (InputStream in = Files.newInputStream(meta)) {
            p.load(in);
        }
        lastVersion = Integer.parseInt(p.getProperty("last", "0"));
        activeVersion = Integer.parseInt(p.getProperty("active", "0"));
    }

    private void writeMetaProps(int last, int active) throws IOException {
        Properties p = new Properties();
        p.setProperty("last", String.valueOf(last));
        p.setProperty("active", String.valueOf(active));
        try (OutputStream out = Files.newOutputStream(Paths.get(META_FILE))) {
            p.store(out, "gvt meta");
        }
        this.lastVersion = last;
        this.activeVersion = active;
    }

    private void writeVersionMetadata(int version, String message, List<String> files) throws IOException {
        Path vdir = Paths.get(VERSIONS_DIR);
        if (!Files.exists(vdir)) Files.createDirectories(vdir);
        Path metaFile = Paths.get(VERSIONS_DIR, version + ".meta");
        try (BufferedWriter bw = Files.newBufferedWriter(metaFile)) {
            bw.write(message == null ? "" : message);
            bw.newLine();
            for (String f : files) {
                bw.write(f);
                bw.newLine();
            }
        }
        writeMetaProps(version, this.activeVersion == -1 ? version : this.activeVersion);
    }

    private VersionMeta loadVersionMeta(int version) throws IOException {
        Path metaFile = Paths.get(VERSIONS_DIR, version + ".meta");
        if (!Files.exists(metaFile)) {
            throw new FileNotFoundException("version meta not found");
        }
        List<String> lines = Files.readAllLines(metaFile);
        String message = lines.isEmpty() ? "" : lines.get(0);
        List<String> files = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (!lines.get(i).trim().isEmpty()) files.add(lines.get(i));
        }
        return new VersionMeta(message, files);
    }

    private static class VersionMeta {
        String message;
        List<String> files;

        VersionMeta(String message, List<String> files) {
            this.message = message;
            this.files = files;
        }
    }

    private int nextVersion() throws IOException {
        loadMeta();
        int nv = lastVersion + 1;
        Files.createDirectories(Paths.get(OBJECTS_DIR, String.valueOf(nv)));
        return nv;
    }

    private void copyFileToObjects(Path src, Path dest) throws IOException {
        if (dest.getParent() != null) Files.createDirectories(dest.getParent());
        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path findLatestObjectForFile(int fromVersionInclusive, String filename) {
        for (int v = fromVersionInclusive; v >= 0; v--) {
            Path p = Paths.get(OBJECTS_DIR, String.valueOf(v), filename);
            if (Files.exists(p)) return p;
        }
        return null;
    }

    private List<Integer> listAllVersions() throws IOException {
        Path vd = Paths.get(VERSIONS_DIR);
        if (!Files.exists(vd)) return Collections.emptyList();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(vd, "*.meta")) {
            List<Integer> res = new ArrayList<>();
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (name.endsWith(".meta")) {
                    String num = name.substring(0, name.length() - 5);
                    try {
                        res.add(Integer.parseInt(num));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            Collections.sort(res);
            return res;
        }
    }

    private boolean versionExists(int v) throws IOException {
        Path p = Paths.get(VERSIONS_DIR, v + ".meta");
        return Files.exists(p);
    }

    private void printStackAndExitWithUnderlying(IOException e) {
        e.printStackTrace(System.err);
        exitHandler.exit(-3, "Underlying system problem. See ERR for details.");
    }

    private static class UnderlyingException extends RuntimeException {
    }
}
