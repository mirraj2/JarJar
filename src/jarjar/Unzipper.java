package jarjar;

import static ox.util.Utils.propagate;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ox.File;
import ox.IO;

public class Unzipper {

  private ZipFile zipFile;

  public Unzipper(File file) {
    try {
      zipFile = new ZipFile(file.file);
    } catch (IOException e) {
      throw propagate(e);
    }
  }

  public void forEach(Consumer<ZipEntry> callback) {
    zipFile.stream().forEach(callback::accept);
  }

  public InputStream openStream(ZipEntry entry) {
    try {
      return zipFile.getInputStream(entry);
    } catch (IOException e) {
      throw propagate(e);
    }
  }

  public void finish() {
    IO.close(zipFile);
  }

}
