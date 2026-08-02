package com.termux.app.filebrowser;

import java.io.File;

/** TermuxMod: a single row shown in the file browser (a directory or a file). */
public class FileEntry {

    public final File file;
    public final boolean isDirectory;
    public final boolean isScript;

    public FileEntry(File file) {
        this.file = file;
        this.isDirectory = file.isDirectory();
        this.isScript = !isDirectory && file.getName().toLowerCase().endsWith(".sh");
    }

    public String getName() {
        return file.getName();
    }

    public long getSize() {
        return isDirectory ? 0 : file.length();
    }
}
