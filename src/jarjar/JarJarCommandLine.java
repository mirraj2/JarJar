package jarjar;

import ox.File;
import ox.Json;

public class JarJarCommandLine {

  private void run(Json json) {
    json.log();
    JarJar jarjar = JarJar.project(File.ofPath(json.get("project")))
        .main(json.get("main"));
    if (json.getBoolean("skipCompile", false)) {
      jarjar = jarjar.skipCompile();
    }
    jarjar.build(File.ofPath(json.get("target")));
  }

  public static void main(String[] args) {
    // new JarJarCommandLine()
    // .run(Json.object().with("project", "/users/jason/workspace/bowser").with("target",
    // "/users/jason/Downloads/bowser.jar").with("skipCompile", true));
    new JarJarCommandLine().run(new Json(args[0]));
  }

}
