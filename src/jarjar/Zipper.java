package jarjar;

import static ox.util.Utils.propagate;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import ox.File;
import ox.IO;

public class Zipper {

  private final ZipOutputStream zos;

  public Zipper(File out) {
    zos = new ZipOutputStream(out.outputStream());
  }

  public void putNextEntry(String name, InputStream data) {
    try {
      zos.putNextEntry(new ZipEntry(name));
      IO.from(data).keepOutputAlive().to(zos);
    } catch (IOException e) {
      propagate(e);
    }
  }

  public void close() {
    IO.close(zos);
  }

}
