package jarjar;

import ox.File;

public class JarJarCommandLine {

  public static void main(String[] args) {
    JarJar.project(File.ofPath(args[0]))
        .main(args[1])
        .build(File.ofPath(args[2]));
  }

}
