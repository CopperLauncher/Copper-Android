package net.kdt.pojavlaunch.utils;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class ZipUtils {
    /**
     * Gets an InputStream for a given ZIP entry, throwing an IOException if the ZIP entry does not
     * exist.
     * @param zipFile The ZipFile to get the entry from
     * @param entryPath The full path inside of the ZipFile
     * @return The InputStream provided by the ZipFile
     * @throws IOException if the entry was not found
     */
    public static InputStream getEntryStream(ZipFile zipFile, String entryPath) throws IOException{
        ZipEntry entry = zipFile.getEntry(entryPath);
        if(entry == null) throw new IOException("No entry in ZIP file: "+entryPath);
        return zipFile.getInputStream(entry);
    }

    /**
     * Extracts all files in a ZipFile inside of a given directory to a given destination directory
     * How to specify dirName:
     * If you want to extract all files in the ZipFile, specify ""
     * If you want to extract a single directory, specify its full path followed by a trailing /
     * @param zipFile The ZipFile to extract files from
     * @param dirName The directory to extract the files from
     * @param destination The destination directory to extract the files into
     * @throws IOException if it was not possible to create a directory or file extraction failed
     */
    public static void zipExtract(ZipFile zipFile, String dirName, File destination) throws IOException {
        Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();

        int dirNameLen = dirName.length();
        while(zipEntries.hasMoreElements()) {
            ZipEntry zipEntry = zipEntries.nextElement();
            String entryName = zipEntry.getName();
            if(!entryName.startsWith(dirName) || zipEntry.isDirectory()) continue;
            File zipDestination = new File(destination, entryName.substring(dirNameLen));
            FileUtils.ensureParentDirectory(zipDestination);
            try (InputStream inputStream = zipFile.getInputStream(zipEntry);
                 OutputStream outputStream = new FileOutputStream(zipDestination)) {
                IOUtils.copy(inputStream, outputStream);
            }
        }
    }

    /**
     * Writes a raw byte array as a new entry in an open ZipOutputStream.
     * Used to write modrinth.index.json into a .mrpack being built.
     * @param zipOutputStream the open ZipOutputStream to write into
     * @param entryName the full path of the entry inside the zip
     * @param data the raw bytes to write as the entry's content
     * @throws IOException if writing the entry failed
     */
    public static void putBytes(ZipOutputStream zipOutputStream, String entryName, byte[] data) throws IOException {
        zipOutputStream.putNextEntry(new ZipEntry(entryName));
        zipOutputStream.write(data);
        zipOutputStream.closeEntry();
    }

    /**
     * Copies a file on disk into an open ZipOutputStream as a new entry.
     * Used to write override files into a .mrpack being built.
     * @param zipOutputStream the open ZipOutputStream to write into
     * @param entryName the full path of the entry inside the zip
     * @param file the source file to copy the content of
     * @throws IOException if reading the source file or writing the entry failed
     */
    public static void putFile(ZipOutputStream zipOutputStream, String entryName, File file) throws IOException {
        zipOutputStream.putNextEntry(new ZipEntry(entryName));
        try (InputStream inputStream = new FileInputStream(file)) {
            IOUtils.copy(inputStream, zipOutputStream);
        }
        zipOutputStream.closeEntry();
    }
}