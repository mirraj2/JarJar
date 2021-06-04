package jarjar;

import static ox.util.Utils.propagate;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import ox.IO;

public class Unzipper extends FilterInputStream {

  private final ZipInputStream zis;

  private ZipEntry nextEntry = null;

  public Unzipper(InputStream is) {
    super(is instanceof ZipInputStream ? is : new ZipInputStream(is));

    zis = (ZipInputStream) in;
    nextEntry();
  }

  private void nextEntry() {
    try {
      nextEntry = zis.getNextEntry();
    } catch (IOException e) {
      throw propagate(e);
    }
  }

  public boolean hasNext() {
    return nextEntry != null;
  }

  public String getName() {
    return nextEntry.getName();
  }

  public boolean isDirectory() {
    return nextEntry.isDirectory();
  }

  public long getSize() {
    return nextEntry.getSize();
  }

  public void next() {
    nextEntry();
  }

  @Override
  public void close() throws IOException {
    nextEntry();
  }

  public void finish() {
    IO.close(super.in);
  }

}
