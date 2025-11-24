package uj.wmii.pwj.gvt;

import java.util.*;
import java.io.IOException;
import java.nio.file.*;

public class Gvt {

    private final ExitHandler exitHandler;
    private Repository repo;

    public Gvt(ExitHandler exitHandler)
    {
        this.exitHandler = exitHandler;
        repo = new Repository(exitHandler);
    }

    public static void main(String... args)
    {
        Gvt gvt = new Gvt(new ExitHandler());
        gvt.mainInternal(args);
    }

    public void mainInternal(String... args)
    {
        if (args == null || args.length == 0)
        {
            exitHandler.exit(1, "Please specify command.");
        }
        else
        {
            String command = args[0];

            if (!"init".equals(command) && !repo.initiated)
            {
                exitHandler.exit(-2, "Current directory is not initialized. Please use init command to initialize.");
            }
            else
            {
                try {
                    switch (command)
                    {
                        case "init":
                            repo.init();
                            break;
                        case "add":
                            repo.add(args);
                            break;
                        case "detach":
                            repo.detach(args);
                            break;
                        case "checkout":
                            repo.checkout(args);
                            break;
                        case "commit":
                            repo.commit(args);
                            break;
                        case "history":
                            repo.history(args);
                            break;
                        case "version":
                            repo.version(args);
                            break;
                        default:
                            exitHandler.exit(1, "Unknown command " + command + ".");
                            break;
                    }
                } catch (IOException e) {
                    e.printStackTrace(System.err);
                    exitHandler.exit(-3, "Underlying system problem. See ERR for details.");
                }
            }
        }
    }
}

class Repository {
    public Path catalog;
    public Path versions;
    public Path active;
    public Path last;
    public boolean initiated;
    public ExitHandler exitHandler;

    public Repository(ExitHandler exitHandler)
    {
        catalog = Path.of(".gvt");
        versions = catalog.resolve("versions");
        active = catalog.resolve("active");
        last = catalog.resolve("last");
        this.exitHandler = exitHandler;
        try {
            if (Files.exists(catalog) && Files.isDirectory(catalog) && Files.exists(last)) {
                initiated = true;
            } else {
                initiated = false;
            }
        } catch (Exception e) {
            initiated = false;
        }
    }

    public void init() throws IOException
    {
        try {
            if (Files.exists(catalog))
            {
                exitHandler.exit(10, "Current directory is already initialized.");
            }
            else
            {
                Files.createDirectories(catalog);
                Files.createDirectories(versions.resolve("0"));

                Files.writeString(versions.resolve("0").resolve("description.txt"), "GVT initialized.");
                Files.writeString(active, "0");
                Files.writeString(last, "0");

                initiated = true;

                exitHandler.exit(0, "Current directory initialized successfully.");
            }
        } catch (IOException e) {
            e.printStackTrace(System.err);
            exitHandler.exit(-3, "Underlying system problem. See ERR for details.");
        }
    }

    public void add(String... args)
    {
        if (args.length < 2)
        {
            exitHandler.exit(20, "Please specify file to add.");
        }
        else
        {
            String file = args[1];
            Path filePath = Path.of(file);
            if (!Files.exists(filePath))
            {
                exitHandler.exit(21, "File not found. File: " + file);
            }
            else
            {
                try {
                    int lastVersion = Integer.parseInt(Files.readString(last).trim());

                    Path lastFilePath = versions.resolve(String.valueOf(lastVersion)).resolve(filePath.getFileName().toString());
                    if (Files.exists(lastFilePath))
                    {
                        exitHandler.exit(0, "File already added. File: " + file);
                    }
                    else
                    {
                        int newVersion = lastVersion + 1;
                        copyFiles(lastVersion, newVersion);

                        Path newCatalog = versions.resolve(String.valueOf(newVersion));
                        Files.createDirectories(newCatalog);

                        Files.copy(filePath, newCatalog.resolve(filePath.getFileName().toString()));

                        Files.writeString(last, String.valueOf(newVersion));

                        String message = (args.length > 2 && args[2].equals("-m")) ? args[3] : "File added successfully. File: " + file;
                        Files.writeString(versions.resolve(String.valueOf(newVersion)).resolve("description.txt"), message);

                        exitHandler.exit(0, "File added successfully. File: " + file);
                    }
                } catch (IOException e) {
                    e.printStackTrace(System.err);
                    exitHandler.exit(22, "File cannot be added. See ERR for details. File: " + file);
                }
            }
        }
    }

    public void detach(String... args)
    {
        if (args.length < 2)
        {
            exitHandler.exit(30, "Please specify file to detach.");
        }
        else
        {
            String file = args[1];

            try {
                int lastVersion = Integer.parseInt(Files.readString(last).trim());

                Path lastPath = versions.resolve(String.valueOf(lastVersion)).resolve(file);
                if (!Files.exists(lastPath))
                {
                    exitHandler.exit(0, "File is not added to gvt. File: " + file);
                    return;
                }

                int newVersion = lastVersion + 1;
                copyFiles(lastVersion, newVersion);

                Path newPath = versions.resolve(String.valueOf(newVersion)).resolve(file);
                Files.deleteIfExists(newPath);

                Files.writeString(last, String.valueOf(newVersion));

                String message = (args.length > 2 && args[2].equals("-m")) ? args[3] : "File detached successfully. File: " + file;
                Files.writeString(versions.resolve(String.valueOf(newVersion)).resolve("description.txt"), message);

                exitHandler.exit(0, "File detached successfully. File: " + file);
            } catch (IOException e) {
                e.printStackTrace(System.err);
                exitHandler.exit(31, "File cannot be detached, see ERR for details. File: " + file);
            }
        }
    }

    public void checkout(String... args) {
        if (args.length < 2)
        {
            exitHandler.exit(60, "Invalid version number: ");
        }
        else
        {
            int versionNumber;
            try {
                versionNumber = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                exitHandler.exit(60, "Invalid version number: " + args[1]);
                return;
            }

            try {
                Path versionPath = versions.resolve(String.valueOf(versionNumber));
                if (!Files.exists(versionPath))
                {
                    exitHandler.exit(60, "Invalid version number: " + versionNumber);
                }
                else
                {
                    List<String> files = new ArrayList<>();
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(versionPath)) {
                        for (Path path : stream)
                        {
                            if (Files.isRegularFile(path))
                            {
                                String name = path.getFileName().toString();
                                if (!"description.txt".equals(name))
                                {
                                    files.add(name);
                                }
                            }
                        }
                    }

                    for (String names : files)
                    {
                        Path oldFile = versionPath.resolve(names);
                        Path newFile = Path.of(names);
                        Files.copy(oldFile, newFile, StandardCopyOption.REPLACE_EXISTING);
                    }

                    Files.writeString(active, String.valueOf(versionNumber));

                    exitHandler.exit(0, "Checkout successful for version: " + versionNumber);
                }
            } catch (IOException e) {
                e.printStackTrace(System.err);
                exitHandler.exit(-3, "Underlying system problem. See ERR for details.");
            }
        }
    }

    public void commit(String... args)
    {
        if (args.length < 2)
        {
            exitHandler.exit(50, "Please specify file to commit.");
        }
        else
        {
            String file = args[1];
            Path filePath = Path.of(file);

            if (!Files.exists(filePath))
            {
                exitHandler.exit(51, "File not found. File: " + file);
            }
            else
            {
                try {
                    int lastVersion = Integer.parseInt(Files.readString(last).trim());


                    Path lastPath = versions.resolve(String.valueOf(lastVersion)).resolve(filePath.getFileName().toString());
                    if (!Files.exists(lastPath))
                    {
                        exitHandler.exit(0, "File is not added to gvt. File: " + file);
                    }
                    else
                    {
                        int newVersion = lastVersion + 1;
                        copyFiles(lastVersion, newVersion);

                        Path newCatalog = versions.resolve(String.valueOf(newVersion));
                        Files.createDirectories(newCatalog);


                        Path commitPath = newCatalog.resolve(filePath.getFileName().toString());
                        Files.copy(filePath, commitPath, StandardCopyOption.REPLACE_EXISTING);

                        Files.writeString(last, String.valueOf(newVersion));

                        String message = (args.length > 2 && args[2].equals("-m")) ? args[3] : "File committed successfully. File: " + file;
                        Files.writeString(versions.resolve(String.valueOf(newVersion)).resolve("description.txt"), message);

                        exitHandler.exit(0, "File committed successfully. File: " + file);
                    }
                } catch (IOException e) {
                    e.printStackTrace(System.err);
                    exitHandler.exit(52, "File cannot be committed, see ERR for details. File: " + file);
                }
            }
        }
    }

    public void history(String... args)
    {
        try {

            List<Integer> versionsList = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(versions))
            {
                for (Path path : stream)
                {
                    if (Files.isDirectory(path))
                    {
                        versionsList.add(Integer.parseInt(path.getFileName().toString()));
                    }
                }
            }


            versionsList.sort(Collections.reverseOrder());

            boolean hasLimit = false;
            int limitValue = 0;

            if (args.length >= 3)
            {
                if ("-last".equals(args[1]))
                {
                    try {
                        limitValue = Integer.parseInt(args[2]);
                        hasLimit = true;
                    } catch (NumberFormatException ignored) {

                    }
                }
            }

            if (hasLimit && limitValue > 0)
            {
                if (limitValue < versionsList.size())
                {
                    versionsList = versionsList.subList(0, limitValue);
                }
                else
                {
                    versionsList = versionsList.subList(0, versionsList.size());
                }
            }

            String result = "";
            for (int v : versionsList) {
                String msg = Files.readString(
                        versions.resolve(String.valueOf(v)).resolve("description.txt")
                );
                result += v + ": " + msg.split("\r?\n")[0] + "\n";
            }

            exitHandler.exit(0, result);
        } catch (IOException e) {
            e.printStackTrace(System.err);
            exitHandler.exit(-3, "Underlying system problem. See ERR for details.");
        }
    }

    public void version(String... args)
    {
        try {
            int versionNumber = Integer.parseInt(Files.readString(last).trim());
            if (args.length >= 2)
            {
                try {
                    versionNumber = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    exitHandler.exit(60, "Invalid version number: " + args[1] + ".");
                    return;
                }
            }

            Path versionPath = versions.resolve(String.valueOf(versionNumber));
            if (!Files.exists(versionPath))
            {
                exitHandler.exit(60, "Invalid version number: " + versionNumber + ".");
            }
            else
            {
                String description = Files.readString(versionPath.resolve("description.txt"));
                exitHandler.exit(0, "Version: " + versionNumber + "\n" + description);
            }
        } catch (IOException e) {
            e.printStackTrace(System.err);
            exitHandler.exit(-3, "Underlying system problem. See ERR for details.");
        }
    }

    private void copyFiles(int oldVersion, int newVersion) throws IOException
    {
        Path oldPath = versions.resolve(String.valueOf(oldVersion));

        Path newPath = versions.resolve(String.valueOf(newVersion));
        Files.createDirectories(newPath);

        if (Files.exists(oldPath))
        {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(oldPath)) {
                for (Path path : stream)
                {
                    if (!"description.txt".equals(path.getFileName().toString()))
                    {
                        Files.copy(path, newPath.resolve(path.getFileName().toString()));
                    }
                }
            }
        }
    }
}
