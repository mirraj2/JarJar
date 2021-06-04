package jarjar;

import ox.File;
import ox.Json;

public class JarJarCommandLine {

  private void run(Json json) {
    json.log();
    JarJar.project(File.ofPath(json.get("project"))).main(json.get("main")).build(File.ofPath(json.get("target")));
  }

  public static void main(String[] args) {
    new JarJarCommandLine().run(new Json(args[0]));
  }

}
